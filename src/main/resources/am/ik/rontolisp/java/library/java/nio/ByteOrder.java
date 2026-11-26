// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.nio;

// The two orders. The native one is little-endian on every target: a wasm module's memory
// is by its specification, and the JVM and the interpreter answer as the hosts they run on
// (x86-64, AArch64) do.
public final class ByteOrder {

    public static final ByteOrder BIG_ENDIAN = new ByteOrder("BIG_ENDIAN");

    public static final ByteOrder LITTLE_ENDIAN = new ByteOrder("LITTLE_ENDIAN");

    private final String name;

    private ByteOrder(String name) {
        this.name = name;
    }

    public static ByteOrder nativeOrder() {
        return LITTLE_ENDIAN;
    }

    public String toString() {
        return name;
    }
}
