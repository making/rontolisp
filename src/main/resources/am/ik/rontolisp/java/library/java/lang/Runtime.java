// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.lang;

// What a program can ask of every target: one processor is answered, since a .wasm
// runs its threads on one stack, and nothing is collected at a program's ask.
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
        System.exit(status);
    }
}
