// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util;

import java.util.function.Function;
import java.util.function.ToIntFunction;

public interface Comparator<T> {

    int compare(T a, T b);

    default Comparator<T> reversed() {
        return (T a, T b) -> compare(b, a);
    }

    default Comparator<T> thenComparing(Comparator<? super T> other) {
        return (T a, T b) -> {
            int order = compare(a, b);
            return order != 0 ? order : other.compare(a, b);
        };
    }

    static <T extends Comparable<? super T>> Comparator<T> naturalOrder() {
        return (T a, T b) -> a.compareTo(b);
    }

    static <T extends Comparable<? super T>> Comparator<T> reverseOrder() {
        return (T a, T b) -> b.compareTo(a);
    }

    static <T, U extends Comparable<? super U>> Comparator<T> comparing(Function<? super T, ? extends U> key) {
        return (T a, T b) -> key.apply(a).compareTo(key.apply(b));
    }

    static <T> Comparator<T> comparingInt(ToIntFunction<? super T> key) {
        return (T a, T b) -> {
            int x = key.applyAsInt(a);
            int y = key.applyAsInt(b);
            return x < y ? -1 : x > y ? 1 : 0;
        };
    }
}
