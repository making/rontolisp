// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util.function;

public interface IntConsumer {

    void accept(int value);

    default IntConsumer andThen(IntConsumer after) {
        return value -> {
            accept(value);
            after.accept(value);
        };
    }
}
