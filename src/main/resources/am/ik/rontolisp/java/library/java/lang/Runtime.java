// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.lang;

// What a program can ask of every target: one processor is answered, since the threads
// of a .wasm take turns on one stack, and nothing is collected at a program's ask. The
// hooks are run at the end by rontolisp.internal.Shutdown, which halt passes by.
public class Runtime {

    private static final Runtime CURRENT = new Runtime();

    private Runtime() {
    }

    public static Runtime getRuntime() {
        return CURRENT;
    }

    public int availableProcessors() {
        return 1;
    }

    public void gc() {
    }

    public void exit(int status) {
        System.exit(status);
    }

    public void halt(int status) {
        halt0(status);
    }

    public void addShutdownHook(Thread hook) {
        rontolisp.internal.Shutdown.addHook(hook);
    }

    public boolean removeShutdownHook(Thread hook) {
        return rontolisp.internal.Shutdown.removeHook(hook);
    }

    /** The host's exit: what System.exit is where the program registers nothing for its end. */
    private static native void halt0(int status);
}
