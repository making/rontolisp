// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// Every operation holds the object's monitor: atomic among the threads of the program
// on every target, with no compare-and-swap of the host's.
package java.util.concurrent.atomic;

import java.util.function.BinaryOperator;
import java.util.function.UnaryOperator;

public class AtomicReference<V> {

    private V value;

    public AtomicReference() {
    }

    public AtomicReference(V initialValue) {
        this.value = initialValue;
    }

    public final synchronized V get() {
        return this.value;
    }

    public final synchronized void set(V newValue) {
        this.value = newValue;
    }

    public final void lazySet(V newValue) {
        set(newValue);
    }

    public final synchronized V getAndSet(V newValue) {
        V previous = this.value;
        this.value = newValue;
        return previous;
    }

    public final synchronized boolean compareAndSet(V expectedValue, V newValue) {
        if (this.value != expectedValue) {
            return false;
        }
        this.value = newValue;
        return true;
    }

    public final synchronized V getAndUpdate(UnaryOperator<V> updateFunction) {
        V previous = this.value;
        this.value = updateFunction.apply(previous);
        return previous;
    }

    public final synchronized V updateAndGet(UnaryOperator<V> updateFunction) {
        this.value = updateFunction.apply(this.value);
        return this.value;
    }

    public final synchronized V getAndAccumulate(V x, BinaryOperator<V> accumulatorFunction) {
        V previous = this.value;
        this.value = accumulatorFunction.apply(previous, x);
        return previous;
    }

    public final synchronized V accumulateAndGet(V x, BinaryOperator<V> accumulatorFunction) {
        this.value = accumulatorFunction.apply(this.value, x);
        return this.value;
    }

    public String toString() {
        return String.valueOf(get());
    }
}
