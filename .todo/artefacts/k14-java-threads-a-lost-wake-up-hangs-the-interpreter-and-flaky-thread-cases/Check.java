import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.eval.*;
import am.ik.rontolisp.reader.Features;

// Runs each file given on a thread of its own in one JVM on the interpreter and says, for
// each, whether it ended with the expected output (FILE.expected), differed, or hung.
public class Check {
    public static void main(String[] args) throws Exception {
        List<Thread> threads = new ArrayList<>();
        for (String a : args) {
            Path file = Path.of(a);
            String source = Files.readString(file);
            String expected = Files.readString(Path.of(a + ".expected"));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Thread t = new Thread(null, () -> {
                String how = "ended";
                try {
                    LispEvaluator evaluator = new LispEvaluator(new PrintStream(out, true, StandardCharsets.UTF_8), new ByteArrayInputStream(new byte[0]));
                    for (LispVal form : SourceLanguage.forFile(file.toString(), null).read(source, Features.INTERPRETER, file.toString(), SourceStandards.DEFAULT, evaluator.sourceLoader())) {
                        evaluator.eval(form);
                    }
                }
                catch (LispExitSignal exit) { how = "exit " + exit.code(); }
                catch (Throwable e) { how = "threw " + e; }
                String printed = out.toString(StandardCharsets.UTF_8);
                if (printed.equals(expected)) {
                    System.out.println("OK " + file.getFileName() + " " + how);
                }
                else {
                    List<String> p = printed.lines().toList(), e = expected.lines().toList();
                    int i = 0;
                    while (i < p.size() && i < e.size() && p.get(i).equals(e.get(i))) i++;
                    System.out.println("DIFF " + file.getFileName() + " " + how + " at line " + (i + 1) + ": got <" + (i < p.size() ? p.get(i) : "EOF") + "> want <" + (i < e.size() ? e.get(i) : "EOF") + ">");
                }
            }, "java-spec-" + file.getFileName(), 512L << 20);
            t.setDaemon(true);
            threads.add(t);
        }
        for (Thread t : threads) t.start();
        long deadline = System.currentTimeMillis() + 60000;
        for (Thread t : threads) { t.join(Math.max(1, deadline - System.currentTimeMillis())); }
        for (Thread t : threads) if (t.isAlive()) System.out.println("HUNG " + t.getName());
        System.exit(0);
    }
}
