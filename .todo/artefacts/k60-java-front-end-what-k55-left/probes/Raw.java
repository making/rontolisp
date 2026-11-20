import java.util.*;

public class Raw {
    static void attempt(String label, java.util.function.Supplier<Object> s) {
        try {
            System.out.println(label + " = " + s.get());
        } catch (RuntimeException e) {
            System.out.println(label + " ! " + e.getClass().getName() + " " + e.getMessage());
        }
    }

    record P(int x) implements Comparable<P> {
        public int compareTo(P o) { return Integer.compare(x, o.x); }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void main(String[] args) {
        Comparable i = 1;
        Comparable s = "a";
        Comparable d = 1.5;
        Comparable c = 'x';
        Comparable b = true;
        Comparable l = 7L;
        Comparable p = new P(1);
        Comparable e = Thread.State.NEW;
        attempt("Integer vs String", () -> i.compareTo("a"));
        attempt("String vs Integer", () -> s.compareTo(1));
        attempt("Double vs Integer", () -> d.compareTo(1));
        attempt("Integer vs Long", () -> i.compareTo(7L));
        attempt("Long vs Integer", () -> l.compareTo(1));
        attempt("Character vs String", () -> c.compareTo("x"));
        attempt("Boolean vs Integer", () -> b.compareTo(1));
        attempt("Integer vs null", () -> i.compareTo(null));
        attempt("String vs null", () -> s.compareTo(null));
        attempt("P vs Integer", () -> p.compareTo(1));
        attempt("Integer vs P", () -> i.compareTo(new P(1)));
        attempt("enum vs Integer", () -> e.compareTo(1));
        attempt("Integer vs Integer", () -> i.compareTo(2));
        attempt("String vs String", () -> s.compareTo("b"));
        attempt("TreeMap mixed", () -> { Map m = new TreeMap(); m.put(1, "a"); m.put("b", "c"); return m; });
        attempt("TreeSet mixed", () -> { Set m = new TreeSet(); m.add("b"); m.add(1); return m; });
        attempt("sort mixed", () -> { List m = new ArrayList(List.of(1, "a", 2)); Collections.sort(m); return m; });
        attempt("Arrays.sort mixed", () -> { Object[] a = {1, "a"}; Arrays.sort(a); return Arrays.toString(a); });
        attempt("binarySearch mixed", () -> Collections.binarySearch((List) List.of(1, 2, 3), "a"));
        attempt("catch CCE", () -> { try { i.compareTo("a"); return "no"; } catch (ClassCastException x) { return "caught"; } });
    }
}
