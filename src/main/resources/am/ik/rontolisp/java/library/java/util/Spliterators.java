// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util;

import java.util.function.Consumer;
import rontolisp.internal.DoubleFlow;
import rontolisp.internal.IntFlow;
import rontolisp.internal.LongFlow;
import rontolisp.internal.Splits;

public final class Spliterators {

    private Spliterators() {
    }

    // SIZED and SUBSIZED but where CONCURRENT is given
    private static int sized(int characteristics) {
        return (characteristics & Spliterator.CONCURRENT) == 0
                ? characteristics | Spliterator.SIZED | Spliterator.SUBSIZED : characteristics;
    }

    private static void within(int length, int fromIndex, int toIndex) {
        if (fromIndex > toIndex) {
            throw new ArrayIndexOutOfBoundsException("origin(" + fromIndex + ") > fence(" + toIndex + ")");
        }
        if (fromIndex < 0) {
            throw new ArrayIndexOutOfBoundsException(fromIndex);
        }
        if (toIndex > length) {
            throw new ArrayIndexOutOfBoundsException(toIndex);
        }
    }

    public static <T> Spliterator<T> emptySpliterator() {
        return Splits.counted(new ImmutableCollections.ListN<T>(new Object[0]),
                Spliterator.SIZED | Spliterator.SUBSIZED);
    }

    public static <T> Spliterator<T> spliterator(Object[] array, int additionalCharacteristics) {
        return spliterator(array, 0, array.length, additionalCharacteristics);
    }

    public static <T> Spliterator<T> spliterator(Object[] array, int fromIndex, int toIndex,
            int additionalCharacteristics) {
        within(array.length, fromIndex, toIndex);
        List<T> range = new ImmutableCollections.ListN<T>(array).range(fromIndex, toIndex);
        return Splits.counted(range, additionalCharacteristics | Spliterator.SIZED | Spliterator.SUBSIZED);
    }

    public static <T> Spliterator<T> spliterator(Collection<? extends T> c, int characteristics) {
        return Splits.of(Objects.requireNonNull(c), sized(characteristics));
    }

    public static <T> Spliterator<T> spliterator(Iterator<? extends T> iterator, long size, int characteristics) {
        return new Splits<T>(null, Objects.requireNonNull(iterator), size, sized(characteristics), false);
    }

    public static <T> Spliterator<T> spliteratorUnknownSize(Iterator<? extends T> iterator, int characteristics) {
        return new Splits<T>(null, Objects.requireNonNull(iterator), Long.MAX_VALUE,
                characteristics & ~(Spliterator.SIZED | Spliterator.SUBSIZED), false);
    }

    public static <T> Iterator<T> iterator(Spliterator<? extends T> spliterator) {
        return new Ahead<T>(Objects.requireNonNull(spliterator));
    }

    // the elements of a spliterator, one taken ahead by hasNext
    static final class Ahead<T> implements Iterator<T> {

        private final Spliterator<? extends T> source;

        private boolean ready;

        private T next;

        Ahead(Spliterator<? extends T> source) {
            this.source = source;
        }

        public boolean hasNext() {
            if (!ready) {
                source.tryAdvance(item -> {
                    next = item;
                    ready = true;
                });
            }
            return ready;
        }

        public T next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            T item = next;
            next = null;
            ready = false;
            return item;
        }
    }

    public abstract static class AbstractSpliterator<T> implements Spliterator<T> {

        private final long est;

        private final int marks;

        protected AbstractSpliterator(long est, int additionalCharacteristics) {
            this.est = est;
            this.marks = (additionalCharacteristics & Spliterator.SIZED) != 0
                    ? additionalCharacteristics | Spliterator.SUBSIZED : additionalCharacteristics;
        }

        public Spliterator<T> trySplit() {
            return null;
        }

        public long estimateSize() {
            return est;
        }

        public int characteristics() {
            return marks;
        }
    }

    public static Spliterator.OfInt emptyIntSpliterator() {
        return IntFlow.walk(new int[0], 0, 0, Spliterator.SIZED | Spliterator.SUBSIZED);
    }

    public static Spliterator.OfInt spliterator(int[] array, int additionalCharacteristics) {
        return spliterator(array, 0, array.length, additionalCharacteristics);
    }

    public static Spliterator.OfInt spliterator(int[] array, int fromIndex, int toIndex,
            int additionalCharacteristics) {
        within(array.length, fromIndex, toIndex);
        return IntFlow.walk(array, fromIndex, toIndex,
                additionalCharacteristics | Spliterator.SIZED | Spliterator.SUBSIZED);
    }

    public static Spliterator.OfInt spliterator(PrimitiveIterator.OfInt iterator, long size, int characteristics) {
        return IntFlow.walk(Objects.requireNonNull(iterator), size, sized(characteristics));
    }

    public static Spliterator.OfInt spliteratorUnknownSize(PrimitiveIterator.OfInt iterator, int characteristics) {
        return IntFlow.walk(Objects.requireNonNull(iterator), -1,
                characteristics & ~(Spliterator.SIZED | Spliterator.SUBSIZED));
    }

    public static PrimitiveIterator.OfInt iterator(Spliterator.OfInt spliterator) {
        return new IntAhead(Objects.requireNonNull(spliterator));
    }

    // the numbers of a spliterator, one taken ahead by hasNext
    static final class IntAhead implements PrimitiveIterator.OfInt {

        private final Spliterator.OfInt source;

        private boolean ready;

        private int next;

        IntAhead(Spliterator.OfInt source) {
            this.source = source;
        }

        public boolean hasNext() {
            if (!ready) {
                source.tryAdvance((int item) -> {
                    next = item;
                    ready = true;
                });
            }
            return ready;
        }

        public int nextInt() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            ready = false;
            return next;
        }
    }

    public abstract static class AbstractIntSpliterator implements Spliterator.OfInt {

        private final long est;

        private final int marks;

        protected AbstractIntSpliterator(long est, int additionalCharacteristics) {
            this.est = est;
            this.marks = (additionalCharacteristics & Spliterator.SIZED) != 0
                    ? additionalCharacteristics | Spliterator.SUBSIZED : additionalCharacteristics;
        }

        public Spliterator.OfInt trySplit() {
            return null;
        }

        public long estimateSize() {
            return est;
        }

        public int characteristics() {
            return marks;
        }
    }

    public static Spliterator.OfLong emptyLongSpliterator() {
        return LongFlow.walk(new long[0], 0, 0, Spliterator.SIZED | Spliterator.SUBSIZED);
    }

    public static Spliterator.OfLong spliterator(long[] array, int additionalCharacteristics) {
        return spliterator(array, 0, array.length, additionalCharacteristics);
    }

    public static Spliterator.OfLong spliterator(long[] array, int fromIndex, int toIndex,
            int additionalCharacteristics) {
        within(array.length, fromIndex, toIndex);
        return LongFlow.walk(array, fromIndex, toIndex,
                additionalCharacteristics | Spliterator.SIZED | Spliterator.SUBSIZED);
    }

    public static Spliterator.OfLong spliterator(PrimitiveIterator.OfLong iterator, long size, int characteristics) {
        return LongFlow.walk(Objects.requireNonNull(iterator), size, sized(characteristics));
    }

    public static Spliterator.OfLong spliteratorUnknownSize(PrimitiveIterator.OfLong iterator, int characteristics) {
        return LongFlow.walk(Objects.requireNonNull(iterator), -1,
                characteristics & ~(Spliterator.SIZED | Spliterator.SUBSIZED));
    }

    public static PrimitiveIterator.OfLong iterator(Spliterator.OfLong spliterator) {
        return new LongAhead(Objects.requireNonNull(spliterator));
    }

    // the numbers of a spliterator, one taken ahead by hasNext
    static final class LongAhead implements PrimitiveIterator.OfLong {

        private final Spliterator.OfLong source;

        private boolean ready;

        private long next;

        LongAhead(Spliterator.OfLong source) {
            this.source = source;
        }

        public boolean hasNext() {
            if (!ready) {
                source.tryAdvance((long item) -> {
                    next = item;
                    ready = true;
                });
            }
            return ready;
        }

        public long nextLong() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            ready = false;
            return next;
        }
    }

    public abstract static class AbstractLongSpliterator implements Spliterator.OfLong {

        private final long est;

        private final int marks;

        protected AbstractLongSpliterator(long est, int additionalCharacteristics) {
            this.est = est;
            this.marks = (additionalCharacteristics & Spliterator.SIZED) != 0
                    ? additionalCharacteristics | Spliterator.SUBSIZED : additionalCharacteristics;
        }

        public Spliterator.OfLong trySplit() {
            return null;
        }

        public long estimateSize() {
            return est;
        }

        public int characteristics() {
            return marks;
        }
    }

    public static Spliterator.OfDouble emptyDoubleSpliterator() {
        return DoubleFlow.walk(new double[0], 0, 0, Spliterator.SIZED | Spliterator.SUBSIZED);
    }

    public static Spliterator.OfDouble spliterator(double[] array, int additionalCharacteristics) {
        return spliterator(array, 0, array.length, additionalCharacteristics);
    }

    public static Spliterator.OfDouble spliterator(double[] array, int fromIndex, int toIndex,
            int additionalCharacteristics) {
        within(array.length, fromIndex, toIndex);
        return DoubleFlow.walk(array, fromIndex, toIndex,
                additionalCharacteristics | Spliterator.SIZED | Spliterator.SUBSIZED);
    }

    public static Spliterator.OfDouble spliterator(PrimitiveIterator.OfDouble iterator, long size, int characteristics) {
        return DoubleFlow.walk(Objects.requireNonNull(iterator), size, sized(characteristics));
    }

    public static Spliterator.OfDouble spliteratorUnknownSize(PrimitiveIterator.OfDouble iterator, int characteristics) {
        return DoubleFlow.walk(Objects.requireNonNull(iterator), -1,
                characteristics & ~(Spliterator.SIZED | Spliterator.SUBSIZED));
    }

    public static PrimitiveIterator.OfDouble iterator(Spliterator.OfDouble spliterator) {
        return new DoubleAhead(Objects.requireNonNull(spliterator));
    }

    // the numbers of a spliterator, one taken ahead by hasNext
    static final class DoubleAhead implements PrimitiveIterator.OfDouble {

        private final Spliterator.OfDouble source;

        private boolean ready;

        private double next;

        DoubleAhead(Spliterator.OfDouble source) {
            this.source = source;
        }

        public boolean hasNext() {
            if (!ready) {
                source.tryAdvance((double item) -> {
                    next = item;
                    ready = true;
                });
            }
            return ready;
        }

        public double nextDouble() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            ready = false;
            return next;
        }
    }

    public abstract static class AbstractDoubleSpliterator implements Spliterator.OfDouble {

        private final long est;

        private final int marks;

        protected AbstractDoubleSpliterator(long est, int additionalCharacteristics) {
            this.est = est;
            this.marks = (additionalCharacteristics & Spliterator.SIZED) != 0
                    ? additionalCharacteristics | Spliterator.SUBSIZED : additionalCharacteristics;
        }

        public Spliterator.OfDouble trySplit() {
            return null;
        }

        public long estimateSize() {
            return est;
        }

        public int characteristics() {
            return marks;
        }
    }
}
