// No main and no annotation: the public static methods of this class are the
// module's exports. build.sh compiles it with --no-wasi, a module that imports
// nothing, and wasmtime calls the methods with --invoke.
//
// Under a JDK this is an ordinary class.

public class Calc {

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
}
