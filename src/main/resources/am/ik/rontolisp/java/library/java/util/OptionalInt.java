// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util;

import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import java.util.stream.IntStream;

public final class OptionalInt {

    private final boolean present;

    private final int value;

    private OptionalInt(boolean present, int value) {
        this.present = present;
        this.value = value;
    }

    public static OptionalInt empty() {
        return new OptionalInt(false, 0);
    }

    public static OptionalInt of(int value) {
        return new OptionalInt(true, value);
    }

    public boolean isPresent() {
        return present;
    }

    public boolean isEmpty() {
        return !present;
    }

    public int getAsInt() {
        if (!present) {
            throw new NoSuchElementException("No value present");
        }
        return value;
    }

    public int orElseThrow() {
        return getAsInt();
    }

    public <X extends Throwable> int orElseThrow(Supplier<? extends X> exception) throws X {
        if (!present) {
            throw exception.get();
        }
        return value;
    }

    public int orElse(int other) {
        return present ? value : other;
    }

    public int orElseGet(IntSupplier supplier) {
        return present ? value : supplier.getAsInt();
    }

    public void ifPresent(IntConsumer action) {
        if (present) {
            action.accept(value);
        }
    }

    public void ifPresentOrElse(IntConsumer action, Runnable emptyAction) {
        if (present) {
            action.accept(value);
        }
        else {
            emptyAction.run();
        }
    }

    public IntStream stream() {
        return present ? IntStream.of(value) : IntStream.empty();
    }

    public boolean equals(Object other) {
        return other instanceof OptionalInt that && that.present == present && that.value == value;
    }

    public int hashCode() {
        return present ? value : 0;
    }

    public String toString() {
        return present ? "OptionalInt[" + value + "]" : "OptionalInt.empty";
    }
}
