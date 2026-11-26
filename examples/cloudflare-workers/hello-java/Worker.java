// No library and no main: the public static methods of this class are the
// module's exports, which src/index.js calls the way it calls any JavaScript
// function. build.sh compiles it with --no-wasi -- a reactor that imports
// nothing -- and writes the glue that carries a String across (src/worker.js).
//
// Under a JDK this is an ordinary class: WorkerTest.java calls the same methods.

public class Worker {

    // Static state lives as long as the instance: one per isolate on Workers.
    private static int greetings;

    public static int add(int a, int b) {
        return a + b;
    }

    /** The nth Fibonacci number, computed iteratively. */
    public static int fib(int n) {
        int a = 0;
        int b = 1;
        for (int i = 0; i < n; i++) {
            int next = a + b;
            a = b;
            b = next;
        }
        return a;
    }

    public static String greet(String name) {
        greetings++;
        return "Hello, " + name + ", from Java compiled to WebAssembly! (greeting " + greetings + ")";
    }
}
