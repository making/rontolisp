// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util;

import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;
import java.util.stream.DoubleStream;

public final class OptionalDouble {

    private final boolean present;

    private final double value;

    private OptionalDouble(boolean present, double value) {
        this.present = present;
        this.value = value;
    }

    public static OptionalDouble empty() {
        return new OptionalDouble(false, 0);
    }

    public static OptionalDouble of(double value) {
        return new OptionalDouble(true, value);
    }

    public boolean isPresent() {
        return present;
    }

    public boolean isEmpty() {
        return !present;
    }

    public double getAsDouble() {
        if (!present) {
            throw new NoSuchElementException("No value present");
        }
        return value;
    }

    public double orElseThrow() {
        return getAsDouble();
    }

    public <X extends Throwable> double orElseThrow(Supplier<? extends X> exception) throws X {
        if (!present) {
            throw exception.get();
        }
        return value;
    }

    public double orElse(double other) {
        return present ? value : other;
    }

    public double orElseGet(DoubleSupplier supplier) {
        return present ? value : supplier.getAsDouble();
    }

    public void ifPresent(DoubleConsumer action) {
        if (present) {
            action.accept(value);
        }
    }

    public void ifPresentOrElse(DoubleConsumer action, Runnable emptyAction) {
        if (present) {
            action.accept(value);
        }
        else {
            emptyAction.run();
        }
    }

    public DoubleStream stream() {
        return present ? DoubleStream.of(value) : DoubleStream.empty();
    }

    public boolean equals(Object other) {
        return other instanceof OptionalDouble that && that.present == present
                && Double.compare(that.value, value) == 0;
    }

    public int hashCode() {
        return present ? Double.hashCode(value) : 0;
    }

    public String toString() {
        return present ? "OptionalDouble[" + value + "]" : "OptionalDouble.empty";
    }
}
