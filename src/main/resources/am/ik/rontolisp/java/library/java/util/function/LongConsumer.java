// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util.function;

public interface LongConsumer {

    void accept(long value);

    default LongConsumer andThen(LongConsumer after) {
        return value -> {
            accept(value);
            after.accept(value);
        };
    }
}
