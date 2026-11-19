// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util;

import java.util.function.LongConsumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

public final class OptionalLong {

    private final boolean present;

    private final long value;

    private OptionalLong(boolean present, long value) {
        this.present = present;
        this.value = value;
    }

    public static OptionalLong empty() {
        return new OptionalLong(false, 0);
    }

    public static OptionalLong of(long value) {
        return new OptionalLong(true, value);
    }

    public boolean isPresent() {
        return present;
    }

    public boolean isEmpty() {
        return !present;
    }

    public long getAsLong() {
        if (!present) {
            throw new NoSuchElementException("No value present");
        }
        return value;
    }

    public long orElseThrow() {
        return getAsLong();
    }

    public <X extends Throwable> long orElseThrow(Supplier<? extends X> exception) throws X {
        if (!present) {
            throw exception.get();
        }
        return value;
    }

    public long orElse(long other) {
        return present ? value : other;
    }

    public long orElseGet(LongSupplier supplier) {
        return present ? value : supplier.getAsLong();
    }

    public void ifPresent(LongConsumer action) {
        if (present) {
            action.accept(value);
        }
    }

    public void ifPresentOrElse(LongConsumer action, Runnable emptyAction) {
        if (present) {
            action.accept(value);
        }
        else {
            emptyAction.run();
        }
    }

    public boolean equals(Object other) {
        return other instanceof OptionalLong that && that.present == present && that.value == value;
    }

    public int hashCode() {
        return present ? Long.hashCode(value) : 0;
    }

    public String toString() {
        return present ? "OptionalLong[" + value + "]" : "OptionalLong.empty";
    }
}
