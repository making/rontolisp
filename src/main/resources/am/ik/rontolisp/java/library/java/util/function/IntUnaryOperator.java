// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util.function;

public interface IntUnaryOperator {

    int applyAsInt(int operand);

    default IntUnaryOperator compose(IntUnaryOperator before) {
        return operand -> applyAsInt(before.applyAsInt(operand));
    }

    default IntUnaryOperator andThen(IntUnaryOperator after) {
        return operand -> after.applyAsInt(applyAsInt(operand));
    }

    static IntUnaryOperator identity() {
        return operand -> operand;
    }
}
