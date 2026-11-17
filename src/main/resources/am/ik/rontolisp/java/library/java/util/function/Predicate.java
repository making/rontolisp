// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util.function;

public interface Predicate<T> {

    boolean test(T t);

    default Predicate<T> and(Predicate<? super T> other) {
        return (T t) -> test(t) && other.test(t);
    }

    default Predicate<T> or(Predicate<? super T> other) {
        return (T t) -> test(t) || other.test(t);
    }

    default Predicate<T> negate() {
        return (T t) -> !test(t);
    }

    static <T> Predicate<T> not(Predicate<? super T> target) {
        return (T t) -> !target.test(t);
    }

    static <T> Predicate<T> isEqual(Object target) {
        return (T t) -> target == null ? t == null : target.equals(t);
    }
}
