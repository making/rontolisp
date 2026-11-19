// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util.stream;

import java.util.Objects;
import java.util.Spliterator;
import java.util.function.Supplier;
import rontolisp.internal.ObjectFlow;

public final class StreamSupport {

    private StreamSupport() {
    }

    public static <T> Stream<T> stream(Spliterator<T> spliterator, boolean parallel) {
        Objects.requireNonNull(spliterator);
        return ObjectFlow.split(() -> spliterator, spliterator.characteristics(), parallel);
    }

    public static <T> Stream<T> stream(Supplier<? extends Spliterator<T>> supplier, int characteristics,
            boolean parallel) {
        Objects.requireNonNull(supplier);
        return ObjectFlow.split(supplier, characteristics, parallel);
    }
}
