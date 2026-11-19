// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util;

import java.util.function.Consumer;
import rontolisp.internal.Splits;

public final class Spliterators {

    private Spliterators() {
    }

    // SIZED and SUBSIZED but where CONCURRENT is given
    private static int sized(int characteristics) {
        return (characteristics & Spliterator.CONCURRENT) == 0
                ? characteristics | Spliterator.SIZED | Spliterator.SUBSIZED : characteristics;
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
        if (fromIndex > toIndex) {
            throw new ArrayIndexOutOfBoundsException("origin(" + fromIndex + ") > fence(" + toIndex + ")");
        }
        if (fromIndex < 0) {
            throw new ArrayIndexOutOfBoundsException(fromIndex);
        }
        if (toIndex > array.length) {
            throw new ArrayIndexOutOfBoundsException(toIndex);
        }
        List<T> range = new ImmutableCollections.ListN<T>(array).subList(fromIndex, toIndex);
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
}
