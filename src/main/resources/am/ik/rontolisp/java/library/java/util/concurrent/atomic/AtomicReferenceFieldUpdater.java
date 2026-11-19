// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// An updater holds the NAME of its field, as AtomicIntegerFieldUpdater's does: the
// program's three functions read, write and swap the field of a name, a swap under
// the lock of every volatile access, every other member a loop of swaps.
package java.util.concurrent.atomic;

import java.util.function.BinaryOperator;
import java.util.function.UnaryOperator;

public abstract class AtomicReferenceFieldUpdater<T, V> {

    protected AtomicReferenceFieldUpdater() {
    }

    /** Lowered where it is called: of two class literals and a constant name. */
    public static native <U, W> AtomicReferenceFieldUpdater<U, W> newUpdater(Class<U> tclass, Class<W> vclass,
            String fieldName);

    /** What newUpdater is lowered to for a field it takes. */
    static <U, W> AtomicReferenceFieldUpdater<U, W> of(Object field) {
        return new AtomicReferenceFieldUpdaterImpl<>(field);
    }

    public abstract boolean compareAndSet(T obj, V expect, V update);

    public abstract boolean weakCompareAndSet(T obj, V expect, V update);

    public abstract void set(T obj, V newValue);

    public abstract void lazySet(T obj, V newValue);

    public abstract V get(T obj);

    public V getAndSet(T obj, V newValue) {
        V previous;
        do {
            previous = get(obj);
        }
        while (!compareAndSet(obj, previous, newValue));
        return previous;
    }

    public final V getAndUpdate(T obj, UnaryOperator<V> updateFunction) {
        V previous;
        do {
            previous = get(obj);
        }
        while (!compareAndSet(obj, previous, updateFunction.apply(previous)));
        return previous;
    }

    public final V updateAndGet(T obj, UnaryOperator<V> updateFunction) {
        V previous;
        V next;
        do {
            previous = get(obj);
            next = updateFunction.apply(previous);
        }
        while (!compareAndSet(obj, previous, next));
        return next;
    }

    public final V getAndAccumulate(T obj, V x, BinaryOperator<V> accumulatorFunction) {
        V previous;
        do {
            previous = get(obj);
        }
        while (!compareAndSet(obj, previous, accumulatorFunction.apply(previous, x)));
        return previous;
    }

    public final V accumulateAndGet(T obj, V x, BinaryOperator<V> accumulatorFunction) {
        V previous;
        V next;
        do {
            previous = get(obj);
            next = accumulatorFunction.apply(previous, x);
        }
        while (!compareAndSet(obj, previous, next));
        return next;
    }

    private static native Object get0(Object field, Object object);

    private static native void set0(Object field, Object object, Object value);

    private static native boolean swap0(Object field, Object object, Object expect, Object update);

    /** Named as a JDK's, which getClass().getName() and toString() show. */
    private static final class AtomicReferenceFieldUpdaterImpl<T, V> extends AtomicReferenceFieldUpdater<T, V> {

        private final Object field;

        AtomicReferenceFieldUpdaterImpl(Object field) {
            this.field = field;
        }

        public boolean compareAndSet(T obj, V expect, V update) {
            return swap0(this.field, obj, expect, update);
        }

        public boolean weakCompareAndSet(T obj, V expect, V update) {
            return swap0(this.field, obj, expect, update);
        }

        public void set(T obj, V newValue) {
            set0(this.field, obj, newValue);
        }

        public void lazySet(T obj, V newValue) {
            set0(this.field, obj, newValue);
        }

        @SuppressWarnings("unchecked")
        public V get(T obj) {
            return (V) get0(this.field, obj);
        }
    }
}
