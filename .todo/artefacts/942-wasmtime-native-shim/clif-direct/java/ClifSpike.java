import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Fixnum-only Lisp subset -> CLIF text -> (FFM) Cranelift -> .o -> cc -> native exe. */
public class ClifSpike {
    // ---- tiny reader ----
    static int pos; static String src;
    static Object read() {
        skip();
        char c = src.charAt(pos);
        if (c == '(') { pos++; List<Object> l = new ArrayList<>(); skip();
            while (src.charAt(pos) != ')') { l.add(read()); skip(); } pos++; return l; }
        int s = pos; while (pos < src.length() && "() \n\t".indexOf(src.charAt(pos)) < 0) pos++;
        String t = src.substring(s, pos);
        try { return Long.parseLong(t); } catch (NumberFormatException e) { return t; }
    }
    static void skip() { while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) pos++; }

    // ---- codegen ----
    static List<String> names = new ArrayList<>();       // index == u0:N
    static Map<String, Integer> arity = new HashMap<>();
    static int idx(String linkageAndName) { int i = names.indexOf(linkageAndName);
        if (i < 0) { names.add(linkageAndName); i = names.size() - 1; } return i; }

    static StringBuilder out; static int v, blk; static Map<String, String> env; static Set<String> refs;
    static String nv() { return "v" + (v++); }
    static String expr(Object e) {
        if (e instanceof Long n) { String r = nv(); out.append("    ").append(r).append(" = iconst.i64 ").append(n).append('\n'); return r; }
        if (e instanceof String s) return env.get(s);
        List<?> l = (List<?>) e; String op = (String) l.get(0);
        switch (op) {
            case "+", "-", "*" -> { String a = expr(l.get(1)), b = expr(l.get(2)), r = nv();
                out.append("    ").append(r).append(" = ").append(op.equals("+") ? "iadd" : op.equals("-") ? "isub" : "imul")
                   .append(' ').append(a).append(", ").append(b).append('\n'); return r; }
            case "<", "=" -> { String a = expr(l.get(1)), b = expr(l.get(2)), c = nv(), r = nv();
                out.append("    ").append(c).append(" = icmp ").append(op.equals("<") ? "slt" : "eq").append(' ').append(a).append(", ").append(b).append('\n');
                out.append("    ").append(r).append(" = uextend.i64 ").append(c).append('\n'); return r; }
            case "if" -> { String c = expr(l.get(1)); int t = blk++, f = blk++, j = blk++; String r = nv();
                out.append("    brif ").append(c).append(", block").append(t).append(", block").append(f).append('\n');
                out.append("  block").append(t).append(":\n"); String a = expr(l.get(2));
                out.append("    jump block").append(j).append('(').append(a).append(")\n");
                out.append("  block").append(f).append(":\n"); String b = expr(l.get(3));
                out.append("    jump block").append(j).append('(').append(b).append(")\n");
                out.append("  block").append(j).append('(').append(r).append(": i64):\n"); return r; }
            case "print" -> { String a = expr(l.get(1)); int f = idx("import rl_print");
                refs.add("    fn" + f + " = u0:" + f + " sig5\n");
                String r = nv(); out.append("    ").append(r).append(" = call fn").append(f).append("(").append(a).append(")\n"); return r; }
            default -> { List<String> args = new ArrayList<>(); for (Object a : l.subList(1, l.size())) args.add(expr(a));
                int f = idx("local " + op); refs.add("    fn" + f + " = colocated u0:" + f + " sig" + args.size() + "\n");
                String r = nv(); out.append("    ").append(r).append(" = call fn").append(f).append('(').append(String.join(", ", args)).append(")\n"); return r; }
        }
    }
    static String sig(int n) { return "(" + String.join(", ", Collections.nCopies(n, "i64")) + ") -> i64"; }
    static String function(int id, List<String> params, List<Object> body) {
        out = new StringBuilder(); v = params.size(); blk = 1; env = new HashMap<>(); refs = new TreeSet<>();
        for (int i = 0; i < params.size(); i++) env.put(params.get(i), "v" + i);
        String r = null; for (Object b : body) r = expr(b); if (r == null) r = expr(0L);
        out.append("    return ").append(r).append('\n');
        StringBuilder f = new StringBuilder("function u0:" + id + sig(params.size()) + " {\n");
        f.append("    sig5 = (i64) -> i64 system_v\n");
        for (int n = 0; n <= 4; n++) f.append("    sig").append(n).append(" = ").append(sig(n)).append(" system_v\n");
        refs.forEach(f::append);
        List<String> ps = new ArrayList<>(); for (int i = 0; i < params.size(); i++) ps.add("v" + i + ": i64");
        f.append("  block0(").append(String.join(", ", ps)).append("):\n").append(out).append("}\n");
        return f.toString();
    }

    public static void main(String[] a) throws Throwable {
        src = Files.readString(Path.of(a[0])); pos = 0;
        List<List<?>> defuns = new ArrayList<>(); List<Object> top = new ArrayList<>();
        for (skip(); pos < src.length(); skip()) { Object f = read();
            if (f instanceof List<?> l && "defun".equals(l.get(0))) defuns.add(l); else top.add(f); }
        StringBuilder clif = new StringBuilder();
        int main = idx("export rl_main");
        for (List<?> d : defuns) idx("local " + d.get(1));
        clif.append(function(main, List.of(), top));
        for (List<?> d : defuns) { @SuppressWarnings("unchecked") List<String> ps = (List<String>) d.get(2);
            clif.append(function(idx("local " + d.get(1)), ps, new ArrayList<>(d.subList(3, d.size())))); }
        Files.writeString(Path.of(a[1] + ".clif"), clif);

        long t0 = System.nanoTime();
        Linker linker = Linker.nativeLinker();
        SymbolLookup lib = SymbolLookup.libraryLookup(Path.of(a[2]), Arena.global());
        MethodHandle compile = linker.downcallHandle(lib.find("rl_compile").orElseThrow(), FunctionDescriptor.of(
            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        MethodHandle free = linker.downcallHandle(lib.find("rl_free").orElseThrow(),
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
        try (Arena arena = Arena.ofConfined()) {
            byte[] c = clif.toString().getBytes(StandardCharsets.UTF_8);
            byte[] n = String.join("\n", names).getBytes(StandardCharsets.UTF_8);
            MemorySegment outP = arena.allocate(ValueLayout.ADDRESS), outL = arena.allocate(ValueLayout.JAVA_LONG);
            int rc = (int) compile.invokeExact(arena.allocateFrom(ValueLayout.JAVA_BYTE, c), (long) c.length,
                arena.allocateFrom(ValueLayout.JAVA_BYTE, n), (long) n.length, outP, outL);
            long len = outL.get(ValueLayout.JAVA_LONG, 0);
            MemorySegment p = outP.get(ValueLayout.ADDRESS, 0).reinterpret(len);
            byte[] bytes = p.toArray(ValueLayout.JAVA_BYTE);
            free.invokeExact(p, len);
            if (rc != 0) throw new IllegalStateException(new String(bytes, StandardCharsets.UTF_8));
            Files.write(Path.of(a[1] + ".o"), bytes);
            System.err.printf("cranelift via FFM: %d bytes CLIF -> %d bytes object in %.1f ms%n",
                c.length, bytes.length, (System.nanoTime() - t0) / 1e6);
        }
    }
}
