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

import java.util.function.LongBinaryOperator;
import java.util.function.LongUnaryOperator;

public abstract class AtomicLongFieldUpdater<T> {

    protected AtomicLongFieldUpdater() {
    }

    /** Lowered where it is called: of a class literal and a constant name. */
    public static native <U> AtomicLongFieldUpdater<U> newUpdater(Class<U> tclass, String fieldName);

    /** What newUpdater is lowered to for a field it takes. */
    static <U> AtomicLongFieldUpdater<U> of(Object field) {
        return new CASUpdater<>(field);
    }

    public abstract boolean compareAndSet(T obj, long expect, long update);

    public abstract boolean weakCompareAndSet(T obj, long expect, long update);

    public abstract void set(T obj, long newValue);

    public abstract void lazySet(T obj, long newValue);

    public abstract long get(T obj);

    public long getAndSet(T obj, long newValue) {
        long previous;
        do {
            previous = get(obj);
        }
        while (!compareAndSet(obj, previous, newValue));
        return previous;
    }

    public long getAndIncrement(T obj) {
        return getAndAdd(obj, 1);
    }

    public long getAndDecrement(T obj) {
        return getAndAdd(obj, -1);
    }

    public long getAndAdd(T obj, long delta) {
        long previous;
        do {
            previous = get(obj);
        }
        while (!compareAndSet(obj, previous, previous + delta));
        return previous;
    }

    public long incrementAndGet(T obj) {
        return getAndAdd(obj, 1) + 1;
    }

    public long decrementAndGet(T obj) {
        return getAndAdd(obj, -1) - 1;
    }

    public long addAndGet(T obj, long delta) {
        return getAndAdd(obj, delta) + delta;
    }

    public final long getAndUpdate(T obj, LongUnaryOperator updateFunction) {
        long previous;
        do {
            previous = get(obj);
        }
        while (!compareAndSet(obj, previous, updateFunction.applyAsLong(previous)));
        return previous;
    }

    public final long updateAndGet(T obj, LongUnaryOperator updateFunction) {
        long previous;
        long next;
        do {
            previous = get(obj);
            next = updateFunction.applyAsLong(previous);
        }
        while (!compareAndSet(obj, previous, next));
        return next;
    }

    public final long getAndAccumulate(T obj, long x, LongBinaryOperator accumulatorFunction) {
        long previous;
        do {
            previous = get(obj);
        }
        while (!compareAndSet(obj, previous, accumulatorFunction.applyAsLong(previous, x)));
        return previous;
    }

    public final long accumulateAndGet(T obj, long x, LongBinaryOperator accumulatorFunction) {
        long previous;
        long next;
        do {
            previous = get(obj);
            next = accumulatorFunction.applyAsLong(previous, x);
        }
        while (!compareAndSet(obj, previous, next));
        return next;
    }

    private static native long get0(Object field, Object object);

    private static native void set0(Object field, Object object, long value);

    private static native boolean swap0(Object field, Object object, long expect, long update);

    /** Named as a JDK's, which getClass().getName() and toString() show. */
    private static final class CASUpdater<T> extends AtomicLongFieldUpdater<T> {

        private final Object field;

        CASUpdater(Object field) {
            this.field = field;
        }

        public boolean compareAndSet(T obj, long expect, long update) {
            return swap0(this.field, obj, expect, update);
        }

        public boolean weakCompareAndSet(T obj, long expect, long update) {
            return swap0(this.field, obj, expect, update);
        }

        public void set(T obj, long newValue) {
            set0(this.field, obj, newValue);
        }

        public void lazySet(T obj, long newValue) {
            set0(this.field, obj, newValue);
        }

        public long get(T obj) {
            return get0(this.field, obj);
        }
    }
}
