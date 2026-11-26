// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util.function;

public interface LongPredicate {

    boolean test(long value);

    default LongPredicate and(LongPredicate other) {
        return value -> test(value) && other.test(value);
    }

    default LongPredicate or(LongPredicate other) {
        return value -> test(value) || other.test(value);
    }

    default LongPredicate negate() {
        return value -> !test(value);
    }
}
