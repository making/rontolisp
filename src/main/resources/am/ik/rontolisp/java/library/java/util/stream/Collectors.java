// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util.stream;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.DoubleSummaryStatistics;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IntSummaryStatistics;
import java.util.Iterator;
import java.util.List;
import java.util.LongSummaryStatistics;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.ToDoubleFunction;
import java.util.function.ToIntFunction;
import java.util.function.ToLongFunction;
import rontolisp.internal.Flow;

public final class Collectors {

    private Collectors() {
    }

    // one value a collector keeps while it collects
    private static final class Kept<V> {

        V value;

        boolean given;
    }

    // what partitioningBy answers: a value for false and one for true, in that order
    private static final class Halves<V> extends AbstractMap<Boolean, V> {

        V unmet;

        V met;

        Halves(V unmet, V met) {
            this.unmet = unmet;
            this.met = met;
        }

        public int size() {
            return 2;
        }

        public boolean containsKey(Object key) {
            return key instanceof Boolean;
        }

        public V get(Object key) {
            return key instanceof Boolean which ? (which ? met : unmet) : null;
        }

        public V remove(Object key) {
            throw new UnsupportedOperationException();
        }

        public Set<Map.Entry<Boolean, V>> entrySet() {
            return new AbstractSet<Map.Entry<Boolean, V>>() {
                public int size() {
                    return 2;
                }

                public Iterator<Map.Entry<Boolean, V>> iterator() {
                    return new Iterator<Map.Entry<Boolean, V>>() {
                        private int at;

                        public boolean hasNext() {
                            return at < 2;
                        }

                        public Map.Entry<Boolean, V> next() {
                            if (at >= 2) {
                                throw new NoSuchElementException();
                            }
                            boolean which = at++ == 1;
                            return new AbstractMap.SimpleImmutableEntry<Boolean, V>(which, which ? met : unmet);
                        }
                    };
                }
            };
        }
    }

    public static <T, C extends Collection<T>> Collector<T, ?, C> toCollection(Supplier<C> collectionFactory) {
        if (collectionFactory == null) {
            throw new NullPointerException();
        }
        return new Collected<T, C, C>(collectionFactory, (all, item) -> all.add(item), (left, right) -> {
            left.addAll(right);
            return left;
        }, all -> all, Collected.IDENTITY);
    }

    public static <T> Collector<T, ?, List<T>> toList() {
        return new Collected<T, List<T>, List<T>>(() -> new ArrayList<T>(), (all, item) -> all.add(item),
                (left, right) -> {
                    left.addAll(right);
                    return left;
                }, all -> all, Collected.IDENTITY);
    }

    public static <T> Collector<T, ?, List<T>> toUnmodifiableList() {
        return new Collected<T, List<T>, List<T>>(() -> new ArrayList<T>(), (all, item) -> all.add(item),
                (left, right) -> {
                    left.addAll(right);
                    return left;
                }, all -> List.copyOf(all), 0);
    }

    public static <T> Collector<T, ?, Set<T>> toSet() {
        return new Collected<T, Set<T>, Set<T>>(() -> new HashSet<T>(), (all, item) -> all.add(item),
                (left, right) -> {
                    left.addAll(right);
                    return left;
                }, all -> all, Collected.UNORDERED | Collected.IDENTITY);
    }

    public static <T> Collector<T, ?, Set<T>> toUnmodifiableSet() {
        return new Collected<T, Set<T>, Set<T>>(() -> new HashSet<T>(), (all, item) -> all.add(item),
                (left, right) -> {
                    left.addAll(right);
                    return left;
                }, all -> Set.copyOf(all), Collected.UNORDERED);
    }

    public static Collector<CharSequence, ?, String> joining() {
        return new Collected<CharSequence, StringBuilder, String>(() -> new StringBuilder(),
                (text, item) -> text.append(item), (left, right) -> left.append(right), text -> text.toString(), 0);
    }

    public static Collector<CharSequence, ?, String> joining(CharSequence delimiter) {
        return joining(delimiter, "", "");
    }

    public static Collector<CharSequence, ?, String> joining(CharSequence delimiter, CharSequence prefix,
            CharSequence suffix) {
        if (delimiter == null || prefix == null || suffix == null) {
            throw new NullPointerException();
        }
        // the text so far, and whether an element is in it
        return new Collected<CharSequence, Kept<StringBuilder>, String>(() -> {
            Kept<StringBuilder> joined = new Kept<>();
            joined.value = new StringBuilder().append(prefix);
            return joined;
        }, (joined, item) -> {
            if (joined.given) {
                joined.value.append(delimiter);
            }
            joined.given = true;
            joined.value.append(item);
        }, (left, right) -> {
            if (right.given) {
                if (left.given) {
                    left.value.append(delimiter);
                }
                left.given = true;
                left.value.append(right.value.substring(prefix.length()));
            }
            return left;
        }, joined -> joined.value.append(suffix).toString(), 0);
    }

    public static <T, U, A, R> Collector<T, ?, R> mapping(Function<? super T, ? extends U> mapper,
            Collector<? super U, A, R> downstream) {
        if (mapper == null) {
            throw new NullPointerException();
        }
        BiConsumer<A, ? super U> accumulator = downstream.accumulator();
        return new Collected<T, A, R>(downstream.supplier(),
                (container, item) -> accumulator.accept(container, mapper.apply(item)), downstream.combiner(),
                downstream.finisher(), Collected.marks(downstream));
    }

    public static <T, U, A, R> Collector<T, ?, R> flatMapping(
            Function<? super T, ? extends Stream<? extends U>> mapper, Collector<? super U, A, R> downstream) {
        if (mapper == null) {
            throw new NullPointerException();
        }
        BiConsumer<A, ? super U> accumulator = downstream.accumulator();
        return new Collected<T, A, R>(downstream.supplier(), (container, item) -> {
            Stream<? extends U> mapped = mapper.apply(item);
            if (mapped != null) {
                mapped.forEach(each -> accumulator.accept(container, each));
                mapped.close();
            }
        }, downstream.combiner(), downstream.finisher(), Collected.marks(downstream));
    }

    public static <T, A, R> Collector<T, ?, R> filtering(Predicate<? super T> predicate,
            Collector<? super T, A, R> downstream) {
        if (predicate == null) {
            throw new NullPointerException();
        }
        BiConsumer<A, ? super T> accumulator = downstream.accumulator();
        return new Collected<T, A, R>(downstream.supplier(), (container, item) -> {
            if (predicate.test(item)) {
                accumulator.accept(container, item);
            }
        }, downstream.combiner(), downstream.finisher(), Collected.marks(downstream));
    }

    public static <T, A, R, RR> Collector<T, A, RR> collectingAndThen(Collector<T, A, R> downstream,
            Function<R, RR> finisher) {
        if (finisher == null) {
            throw new NullPointerException();
        }
        Function<A, R> first = downstream.finisher();
        return new Collected<T, A, RR>(downstream.supplier(), downstream.accumulator(), downstream.combiner(),
                container -> finisher.apply(first.apply(container)), Collected.marks(downstream) & ~Collected.IDENTITY);
    }

    public static <T> Collector<T, ?, Long> counting() {
        return new Collected<T, long[], Long>(() -> new long[1], (count, item) -> count[0]++, (left, right) -> {
            left[0] += right[0];
            return left;
        }, count -> count[0], 0);
    }

    public static <T> Collector<T, ?, Optional<T>> minBy(Comparator<? super T> comparator) {
        if (comparator == null) {
            throw new NullPointerException();
        }
        return reducing((least, item) -> comparator.compare(least, item) <= 0 ? least : item);
    }

    public static <T> Collector<T, ?, Optional<T>> maxBy(Comparator<? super T> comparator) {
        if (comparator == null) {
            throw new NullPointerException();
        }
        return reducing((most, item) -> comparator.compare(most, item) >= 0 ? most : item);
    }

    public static <T> Collector<T, ?, Integer> summingInt(ToIntFunction<? super T> mapper) {
        if (mapper == null) {
            throw new NullPointerException();
        }
        return new Collected<T, int[], Integer>(() -> new int[1], (sum, item) -> sum[0] += mapper.applyAsInt(item),
                (left, right) -> {
                    left[0] += right[0];
                    return left;
                }, sum -> sum[0], 0);
    }

    public static <T> Collector<T, ?, Long> summingLong(ToLongFunction<? super T> mapper) {
        if (mapper == null) {
            throw new NullPointerException();
        }
        return new Collected<T, long[], Long>(() -> new long[1], (sum, item) -> sum[0] += mapper.applyAsLong(item),
                (left, right) -> {
                    left[0] += right[0];
                    return left;
                }, sum -> sum[0], 0);
    }

    // the sum of doubles as rontolisp.internal.Flow keeps one, then how many
    private static double[] sumsJoined(double[] left, double[] right) {
        double plain = left[2] + right[2];
        Flow.add(left, right[0]);
        Flow.add(left, -right[1]);
        left[2] = plain;
        left[3] += right[3];
        return left;
    }

    public static <T> Collector<T, ?, Double> summingDouble(ToDoubleFunction<? super T> mapper) {
        if (mapper == null) {
            throw new NullPointerException();
        }
        return new Collected<T, double[], Double>(() -> new double[4],
                (sum, item) -> Flow.add(sum, mapper.applyAsDouble(item)),
                (left, right) -> sumsJoined(left, right), sum -> Flow.total(sum), 0);
    }

    public static <T> Collector<T, ?, Double> averagingInt(ToIntFunction<? super T> mapper) {
        if (mapper == null) {
            throw new NullPointerException();
        }
        return new Collected<T, long[], Double>(() -> new long[2], (sum, item) -> {
            sum[0] += mapper.applyAsInt(item);
            sum[1]++;
        }, (left, right) -> {
            left[0] += right[0];
            left[1] += right[1];
            return left;
        }, sum -> sum[1] == 0 ? 0.0 : (double) sum[0] / sum[1], 0);
    }

    public static <T> Collector<T, ?, Double> averagingLong(ToLongFunction<? super T> mapper) {
        if (mapper == null) {
            throw new NullPointerException();
        }
        return new Collected<T, long[], Double>(() -> new long[2], (sum, item) -> {
            sum[0] += mapper.applyAsLong(item);
            sum[1]++;
        }, (left, right) -> {
            left[0] += right[0];
            left[1] += right[1];
            return left;
        }, sum -> sum[1] == 0 ? 0.0 : (double) sum[0] / sum[1], 0);
    }

    public static <T> Collector<T, ?, Double> averagingDouble(ToDoubleFunction<? super T> mapper) {
        if (mapper == null) {
            throw new NullPointerException();
        }
        return new Collected<T, double[], Double>(() -> new double[4], (sum, item) -> {
            Flow.add(sum, mapper.applyAsDouble(item));
            sum[3]++;
        }, (left, right) -> sumsJoined(left, right), sum -> sum[3] == 0 ? 0.0 : Flow.total(sum) / sum[3], 0);
    }

    public static <T> Collector<T, ?, T> reducing(T identity, BinaryOperator<T> op) {
        if (op == null) {
            throw new NullPointerException();
        }
        return new Collected<T, Kept<T>, T>(() -> {
            Kept<T> kept = new Kept<>();
            kept.value = identity;
            return kept;
        }, (kept, item) -> kept.value = op.apply(kept.value, item), (left, right) -> {
            left.value = op.apply(left.value, right.value);
            return left;
        }, kept -> kept.value, 0);
    }

    public static <T> Collector<T, ?, Optional<T>> reducing(BinaryOperator<T> op) {
        if (op == null) {
            throw new NullPointerException();
        }
        return new Collected<T, Kept<T>, Optional<T>>(() -> new Kept<T>(), (kept, item) -> {
            kept.value = kept.given ? op.apply(kept.value, item) : item;
            kept.given = true;
        }, (left, right) -> {
            if (right.given) {
                left.value = left.given ? op.apply(left.value, right.value) : right.value;
                left.given = true;
            }
            return left;
        }, kept -> Optional.ofNullable(kept.value), 0);
    }

    public static <T, U> Collector<T, ?, U> reducing(U identity, Function<? super T, ? extends U> mapper,
            BinaryOperator<U> op) {
        if (mapper == null || op == null) {
            throw new NullPointerException();
        }
        return new Collected<T, Kept<U>, U>(() -> {
            Kept<U> kept = new Kept<>();
            kept.value = identity;
            return kept;
        }, (kept, item) -> kept.value = op.apply(kept.value, mapper.apply(item)), (left, right) -> {
            left.value = op.apply(left.value, right.value);
            return left;
        }, kept -> kept.value, 0);
    }

    public static <T, K> Collector<T, ?, Map<K, List<T>>> groupingBy(Function<? super T, ? extends K> classifier) {
        return groupingBy(classifier, toList());
    }

    public static <T, K, A, D> Collector<T, ?, Map<K, D>> groupingBy(Function<? super T, ? extends K> classifier,
            Collector<? super T, A, D> downstream) {
        return groupingBy(classifier, () -> new HashMap<K, D>(), downstream);
    }

    // a group's container stands in the map the factory made until the finisher puts
    // what it finishes as in its place
    public static <T, K, D, A, M extends Map<K, D>> Collector<T, ?, M> groupingBy(
            Function<? super T, ? extends K> classifier, Supplier<M> mapFactory,
            Collector<? super T, A, D> downstream) {
        if (classifier == null || mapFactory == null) {
            throw new NullPointerException();
        }
        Supplier<A> made = downstream.supplier();
        BiConsumer<A, ? super T> accumulator = downstream.accumulator();
        BinaryOperator<A> combiner = downstream.combiner();
        Function<A, D> finisher = downstream.finisher();
        return new Collected<T, Map<K, Object>, M>(() -> (Map<K, Object>) mapFactory.get(), (groups, item) -> {
            K key = classifier.apply(item);
            if (key == null) {
                throw new NullPointerException("element cannot be mapped to a null key");
            }
            A group = (A) groups.computeIfAbsent(key, absent -> made.get());
            accumulator.accept(group, item);
        }, (left, right) -> {
            for (Map.Entry<K, Object> each : right.entrySet()) {
                left.merge(each.getKey(), each.getValue(), (mine, theirs) -> combiner.apply((A) mine, (A) theirs));
            }
            return left;
        }, groups -> {
            groups.replaceAll((key, group) -> finisher.apply((A) group));
            return (M) groups;
        }, Collected.marks(downstream) & Collected.IDENTITY);
    }

    public static <T> Collector<T, ?, Map<Boolean, List<T>>> partitioningBy(Predicate<? super T> predicate) {
        return partitioningBy(predicate, toList());
    }

    public static <T, D, A> Collector<T, ?, Map<Boolean, D>> partitioningBy(Predicate<? super T> predicate,
            Collector<? super T, A, D> downstream) {
        if (predicate == null) {
            throw new NullPointerException();
        }
        Supplier<A> made = downstream.supplier();
        BiConsumer<A, ? super T> accumulator = downstream.accumulator();
        BinaryOperator<A> combiner = downstream.combiner();
        Function<A, D> finisher = downstream.finisher();
        return new Collected<T, Halves<A>, Map<Boolean, D>>(() -> new Halves<A>(made.get(), made.get()),
                (halves, item) -> accumulator.accept(predicate.test(item) ? halves.met : halves.unmet, item),
                (left, right) -> {
                    left.unmet = combiner.apply(left.unmet, right.unmet);
                    left.met = combiner.apply(left.met, right.met);
                    return left;
                }, halves -> new Halves<D>(finisher.apply(halves.unmet), finisher.apply(halves.met)),
                Collected.marks(downstream) & Collected.IDENTITY);
    }

    private static IllegalStateException twice(Object key, Object first, Object second) {
        return new IllegalStateException(
                "Duplicate key " + key + " (attempted merging values " + first + " and " + second + ")");
    }

    public static <T, K, U> Collector<T, ?, Map<K, U>> toMap(Function<? super T, ? extends K> keyMapper,
            Function<? super T, ? extends U> valueMapper) {
        if (keyMapper == null || valueMapper == null) {
            throw new NullPointerException();
        }
        return new Collected<T, Map<K, U>, Map<K, U>>(() -> new HashMap<K, U>(), (map, item) -> {
            K key = keyMapper.apply(item);
            U value = valueMapper.apply(item);
            if (value == null) {
                throw new NullPointerException();
            }
            U before = map.putIfAbsent(key, value);
            if (before != null) {
                throw twice(key, before, value);
            }
        }, (left, right) -> {
            for (Map.Entry<K, U> each : right.entrySet()) {
                U before = left.putIfAbsent(each.getKey(), each.getValue());
                if (before != null) {
                    throw twice(each.getKey(), before, each.getValue());
                }
            }
            return left;
        }, map -> map, Collected.IDENTITY);
    }

    public static <T, K, U> Collector<T, ?, Map<K, U>> toUnmodifiableMap(Function<? super T, ? extends K> keyMapper,
            Function<? super T, ? extends U> valueMapper) {
        Collector<T, ?, Map<K, U>> plain = toMap(keyMapper, valueMapper);
        return collectingAndThen(plain, map -> Map.copyOf(map));
    }

    public static <T, K, U> Collector<T, ?, Map<K, U>> toMap(Function<? super T, ? extends K> keyMapper,
            Function<? super T, ? extends U> valueMapper, BinaryOperator<U> mergeFunction) {
        return toMap(keyMapper, valueMapper, mergeFunction, () -> new HashMap<K, U>());
    }

    public static <T, K, U> Collector<T, ?, Map<K, U>> toUnmodifiableMap(Function<? super T, ? extends K> keyMapper,
            Function<? super T, ? extends U> valueMapper, BinaryOperator<U> mergeFunction) {
        Collector<T, ?, Map<K, U>> plain = toMap(keyMapper, valueMapper, mergeFunction);
        return collectingAndThen(plain, map -> Map.copyOf(map));
    }

    public static <T, K, U, M extends Map<K, U>> Collector<T, ?, M> toMap(Function<? super T, ? extends K> keyMapper,
            Function<? super T, ? extends U> valueMapper, BinaryOperator<U> mergeFunction, Supplier<M> mapFactory) {
        if (keyMapper == null || valueMapper == null || mergeFunction == null || mapFactory == null) {
            throw new NullPointerException();
        }
        return new Collected<T, M, M>(mapFactory,
                (map, item) -> map.merge(keyMapper.apply(item), valueMapper.apply(item), mergeFunction),
                (left, right) -> {
                    for (Map.Entry<K, U> each : right.entrySet()) {
                        left.merge(each.getKey(), each.getValue(), mergeFunction);
                    }
                    return left;
                }, map -> map, Collected.IDENTITY);
    }

    public static <T> Collector<T, ?, IntSummaryStatistics> summarizingInt(ToIntFunction<? super T> mapper) {
        if (mapper == null) {
            throw new NullPointerException();
        }
        return new Collected<T, IntSummaryStatistics, IntSummaryStatistics>(() -> new IntSummaryStatistics(),
                (all, item) -> all.accept(mapper.applyAsInt(item)), (left, right) -> {
                    left.combine(right);
                    return left;
                }, all -> all, Collected.IDENTITY);
    }

    public static <T> Collector<T, ?, LongSummaryStatistics> summarizingLong(ToLongFunction<? super T> mapper) {
        if (mapper == null) {
            throw new NullPointerException();
        }
        return new Collected<T, LongSummaryStatistics, LongSummaryStatistics>(() -> new LongSummaryStatistics(),
                (all, item) -> all.accept(mapper.applyAsLong(item)), (left, right) -> {
                    left.combine(right);
                    return left;
                }, all -> all, Collected.IDENTITY);
    }

    public static <T> Collector<T, ?, DoubleSummaryStatistics> summarizingDouble(
            ToDoubleFunction<? super T> mapper) {
        if (mapper == null) {
            throw new NullPointerException();
        }
        return new Collected<T, DoubleSummaryStatistics, DoubleSummaryStatistics>(
                () -> new DoubleSummaryStatistics(), (all, item) -> all.accept(mapper.applyAsDouble(item)),
                (left, right) -> {
                    left.combine(right);
                    return left;
                }, all -> all, Collected.IDENTITY);
    }

    public static <T, R1, R2, R> Collector<T, ?, R> teeing(Collector<? super T, ?, R1> downstream1,
            Collector<? super T, ?, R2> downstream2, BiFunction<? super R1, ? super R2, R> merger) {
        return both(downstream1, downstream2, merger);
    }

    private static <T, A1, A2, R1, R2, R> Collector<T, ?, R> both(Collector<? super T, A1, R1> first,
            Collector<? super T, A2, R2> second, BiFunction<? super R1, ? super R2, R> merger) {
        if (merger == null) {
            throw new NullPointerException();
        }
        Supplier<A1> made1 = first.supplier();
        Supplier<A2> made2 = second.supplier();
        BiConsumer<A1, ? super T> accumulator1 = first.accumulator();
        BiConsumer<A2, ? super T> accumulator2 = second.accumulator();
        BinaryOperator<A1> combiner1 = first.combiner();
        BinaryOperator<A2> combiner2 = second.combiner();
        Function<A1, R1> finisher1 = first.finisher();
        Function<A2, R2> finisher2 = second.finisher();
        return new Collected<T, Object[], R>(() -> new Object[] { made1.get(), made2.get() }, (pair, item) -> {
            accumulator1.accept((A1) pair[0], item);
            accumulator2.accept((A2) pair[1], item);
        }, (left, right) -> {
            left[0] = combiner1.apply((A1) left[0], (A1) right[0]);
            left[1] = combiner2.apply((A2) left[1], (A2) right[1]);
            return left;
        }, pair -> merger.apply(finisher1.apply((A1) pair[0]), finisher2.apply((A2) pair[1])),
                Collected.marks(first) & Collected.marks(second) & ~Collected.IDENTITY);
    }
}
