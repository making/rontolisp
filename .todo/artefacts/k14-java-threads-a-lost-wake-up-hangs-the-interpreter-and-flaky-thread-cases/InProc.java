// Runs each Java file given on a thread of its own in ONE JVM on the interpreter, as
// JavaSpecE2eTest's standalone interpreter legs do, and names the ones still waiting
// after 25 s (HUNG: ..). See run.sh beside it.
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.eval.*;
import am.ik.rontolisp.reader.Features;

public class InProc {
    public static void main(String[] args) throws Exception {
        List<Thread> threads = new ArrayList<>();
        for (String a : args) {
            Path file = Path.of(a);
            String source = Files.readString(file);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Thread t = new Thread(null, () -> {
                long t0 = System.nanoTime();
                String how = "ended";
                try {
                    LispEvaluator evaluator = new LispEvaluator(new PrintStream(out, true, StandardCharsets.UTF_8), new ByteArrayInputStream(new byte[0]));
                    for (LispVal form : SourceLanguage.forFile(file.toString(), null).read(source, Features.INTERPRETER, file.toString(), SourceStandards.DEFAULT, evaluator.sourceLoader())) {
                        evaluator.eval(form);
                    }
                }
                catch (LispExitSignal exit) { how = "exit " + exit.code(); }
                catch (Throwable e) { how = "threw " + e; }
                System.out.println(file.getFileName() + ": " + how + " after " + (System.nanoTime() - t0) / 1000000 + " ms, " + out.toString(StandardCharsets.UTF_8).lines().count() + " lines");
            }, "java-spec-" + file.getFileName(), 512L << 20);
            t.setDaemon(true);
            threads.add(t);
        }
        for (Thread t : threads) t.start();
        long deadline = System.currentTimeMillis() + 25000;
        for (Thread t : threads) { t.join(Math.max(1, deadline - System.currentTimeMillis())); }
        for (Thread t : threads) if (t.isAlive()) System.out.println("HUNG: " + t.getName());
        System.exit(0);
    }
}
