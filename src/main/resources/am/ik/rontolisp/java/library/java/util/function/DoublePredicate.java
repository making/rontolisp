// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util.function;

public interface DoublePredicate {

    boolean test(double value);

    default DoublePredicate and(DoublePredicate other) {
        return value -> test(value) && other.test(value);
    }

    default DoublePredicate or(DoublePredicate other) {
        return value -> test(value) || other.test(value);
    }

    default DoublePredicate negate() {
        return value -> !test(value);
    }
}
