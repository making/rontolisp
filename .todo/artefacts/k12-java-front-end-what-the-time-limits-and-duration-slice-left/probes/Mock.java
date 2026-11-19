import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

public class Mock {

    interface Col<T, A, R> {
        Supplier<A> supplier();
        BiConsumer<A, T> accumulator();
        Function<A, R> finisher();
    }

    record Made<T, A, R>(Supplier<A> supplier, BiConsumer<A, T> accumulator, Function<A, R> finisher)
            implements Col<T, A, R> {
    }

    static final class Cols {
        static <T> Col<T, ?, List<T>> toList() {
            return new Made<T, List<T>, List<T>>(ArrayList::new, List::add, x -> x);
        }

        static <T> Col<T, ?, Long> counting() {
            return new Made<T, long[], Long>(() -> new long[1], (a, t) -> a[0]++, a -> a[0]);
        }

        static <T, K> Col<T, ?, Map<K, List<T>>> groupingBy(Function<? super T, ? extends K> f) {
            return groupingBy(f, toList());
        }

        static <T, K, A, D> Col<T, ?, Map<K, D>> groupingBy(Function<? super T, ? extends K> f,
                Col<? super T, A, D> down) {
            Supplier<A> ds = down.supplier();
            BiConsumer<A, ? super T> da = down.accumulator();
            Function<A, D> df = down.finisher();
            return new Made<T, Map<K, A>, Map<K, D>>(HashMap::new, (m, t) -> {
                K k = f.apply(t);
                A a = m.get(k);
                if (a == null) {
                    a = ds.get();
                    m.put(k, a);
                }
                da.accept(a, t);
            }, m -> {
                Map<K, D> out = new HashMap<>();
                for (Map.Entry<K, A> e : m.entrySet()) {
                    out.put(e.getKey(), df.apply(e.getValue()));
                }
                return out;
            });
        }

        static <T, U, A, R> Col<T, ?, R> mapping(Function<? super T, ? extends U> f, Col<? super U, A, R> down) {
            BiConsumer<A, ? super U> da = down.accumulator();
            return new Made<T, A, R>(down.supplier(), (a, t) -> da.accept(a, f.apply(t)), down.finisher());
        }

        static Col<CharSequence, ?, String> joining(CharSequence sep) {
            return new Made<CharSequence, StringBuilder, String>(StringBuilder::new, (sb, s) -> {
                if (sb.length() > 0) {
                    sb.append(sep);
                }
                sb.append(s);
            }, StringBuilder::toString);
        }
    }

    static final class St<T> {
        final List<T> xs;

        St(List<T> xs) {
            this.xs = xs;
        }

        <R, A> R collect(Col<? super T, A, R> c) {
            A a = c.supplier().get();
            BiConsumer<A, ? super T> acc = c.accumulator();
            for (T x : xs) {
                acc.accept(a, x);
            }
            return c.finisher().apply(a);
        }

        <R> St<R> map(Function<? super T, ? extends R> f) {
            List<R> out = new ArrayList<>();
            for (T x : xs) {
                out.add(f.apply(x));
            }
            return new St<>(out);
        }

        St<T> filter(Predicate<? super T> p) {
            List<T> out = new ArrayList<>();
            for (T x : xs) {
                if (p.test(x)) {
                    out.add(x);
                }
            }
            return new St<>(out);
        }

        T reduce(T identity, BinaryOperator<T> op) {
            T r = identity;
            for (T x : xs) {
                r = op.apply(r, x);
            }
            return r;
        }
    }

    record Person(String name, int age, String city) {
    }

    public static void main(String[] args) {
        St<String> words = new St<>(List.of("apple", "fig", "kiwi", "plum", "pear", "banana"));
        List<String> a = words.collect(Cols.toList());
        System.out.println(a);
        Map<Integer, List<String>> b = words.collect(Cols.groupingBy(s -> s.length()));
        System.out.println(b);
        Map<Integer, Long> c = words.collect(Cols.groupingBy(s -> s.length(), Cols.counting()));
        System.out.println(c);
        Map<Integer, Long> d = words.collect(Cols.groupingBy(String::length, Cols.counting()));
        System.out.println(d);
        System.out.println(words.collect(Cols.groupingBy(String::length, Cols.counting())));
        var e = words.collect(Cols.groupingBy(s -> s.charAt(0), Cols.mapping(s -> s.toUpperCase(), Cols.toList())));
        System.out.println(e);
        String j = words.map(s -> s.toUpperCase()).filter(s -> s.length() > 3).collect(Cols.joining(", "));
        System.out.println(j);
        System.out.println(words.map(String::length).reduce(0, Integer::sum));
        System.out.println(words.map(String::length).reduce(0, (x, y) -> x + y));
        St<Person> people = new St<>(List.of(new Person("Ann", 31, "Oslo"), new Person("Bob", 25, "Rome"),
                new Person("Cy", 40, "Oslo")));
        Map<String, List<String>> byCity = people
            .collect(Cols.groupingBy(Person::city, Cols.mapping(Person::name, Cols.toList())));
        System.out.println(byCity);
        System.out.println(people.collect(Cols.groupingBy(p -> p.age() / 10 * 10, Cols.mapping(p -> p.name(), Cols.joining("+")))));
    }
}
