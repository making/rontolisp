// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// An updater holds the NAME of its field, a value of the run time's: the lowering finds
// the field where newUpdater is called (the class literal and the name are written
// there) and makes three functions of the program that read, write and swap the field
// of a name.
// A swap runs under the lock every volatile access of the program runs under, so it
// is atomic beside a plain write of the field too; every other member is a loop of
// swaps.
package java.util.concurrent.atomic;

import java.util.function.IntBinaryOperator;
import java.util.function.IntUnaryOperator;

public abstract class AtomicIntegerFieldUpdater<T> {

    protected AtomicIntegerFieldUpdater() {
    }

    /** Lowered where it is called: of a class literal and a constant name. */
    public static native <U> AtomicIntegerFieldUpdater<U> newUpdater(Class<U> tclass, String fieldName);

    /** What newUpdater is lowered to for a field it takes. */
    static <U> AtomicIntegerFieldUpdater<U> of(Object field) {
        return new AtomicIntegerFieldUpdaterImpl<>(field);
    }

    public abstract boolean compareAndSet(T obj, int expect, int update);

    public abstract boolean weakCompareAndSet(T obj, int expect, int update);

    public abstract void set(T obj, int newValue);

    public abstract void lazySet(T obj, int newValue);

    public abstract int get(T obj);

    public int getAndSet(T obj, int newValue) {
        int previous;
        do {
            previous = get(obj);
        }
        while (!compareAndSet(obj, previous, newValue));
        return previous;
    }

    public int getAndIncrement(T obj) {
        return getAndAdd(obj, 1);
    }

    public int getAndDecrement(T obj) {
        return getAndAdd(obj, -1);
    }

    public int getAndAdd(T obj, int delta) {
        int previous;
        do {
            previous = get(obj);
        }
        while (!compareAndSet(obj, previous, previous + delta));
        return previous;
    }

    public int incrementAndGet(T obj) {
        return getAndAdd(obj, 1) + 1;
    }

    public int decrementAndGet(T obj) {
        return getAndAdd(obj, -1) - 1;
    }

    public int addAndGet(T obj, int delta) {
        return getAndAdd(obj, delta) + delta;
    }

    public final int getAndUpdate(T obj, IntUnaryOperator updateFunction) {
        int previous;
        do {
            previous = get(obj);
        }
        while (!compareAndSet(obj, previous, updateFunction.applyAsInt(previous)));
        return previous;
    }

    public final int updateAndGet(T obj, IntUnaryOperator updateFunction) {
        int previous;
        int next;
        do {
            previous = get(obj);
            next = updateFunction.applyAsInt(previous);
        }
        while (!compareAndSet(obj, previous, next));
        return next;
    }

    public final int getAndAccumulate(T obj, int x, IntBinaryOperator accumulatorFunction) {
        int previous;
        do {
            previous = get(obj);
        }
        while (!compareAndSet(obj, previous, accumulatorFunction.applyAsInt(previous, x)));
        return previous;
    }

    public final int accumulateAndGet(T obj, int x, IntBinaryOperator accumulatorFunction) {
        int previous;
        int next;
        do {
            previous = get(obj);
            next = accumulatorFunction.applyAsInt(previous, x);
        }
        while (!compareAndSet(obj, previous, next));
        return next;
    }

    private static native int get0(Object field, Object object);

    private static native void set0(Object field, Object object, int value);

    private static native boolean swap0(Object field, Object object, int expect, int update);

    /** Named as a JDK's, which getClass().getName() and toString() show. */
    private static final class AtomicIntegerFieldUpdaterImpl<T> extends AtomicIntegerFieldUpdater<T> {

        private final Object field;

        AtomicIntegerFieldUpdaterImpl(Object field) {
            this.field = field;
        }

        public boolean compareAndSet(T obj, int expect, int update) {
            return swap0(this.field, obj, expect, update);
        }

        public boolean weakCompareAndSet(T obj, int expect, int update) {
            return swap0(this.field, obj, expect, update);
        }

        public void set(T obj, int newValue) {
            set0(this.field, obj, newValue);
        }

        public void lazySet(T obj, int newValue) {
            set0(this.field, obj, newValue);
        }

        public int get(T obj) {
            return get0(this.field, obj);
        }
    }
}
