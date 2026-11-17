// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util.function;

public interface IntPredicate {

    boolean test(int value);

    default IntPredicate and(IntPredicate other) {
        return value -> test(value) && other.test(value);
    }

    default IntPredicate or(IntPredicate other) {
        return value -> test(value) || other.test(value);
    }

    default IntPredicate negate() {
        return value -> !test(value);
    }
}
