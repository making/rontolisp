// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util.function;

public interface DoubleUnaryOperator {

    double applyAsDouble(double operand);

    default DoubleUnaryOperator compose(DoubleUnaryOperator before) {
        return operand -> applyAsDouble(before.applyAsDouble(operand));
    }

    default DoubleUnaryOperator andThen(DoubleUnaryOperator after) {
        return operand -> after.applyAsDouble(applyAsDouble(operand));
    }

    static DoubleUnaryOperator identity() {
        return operand -> operand;
    }
}
