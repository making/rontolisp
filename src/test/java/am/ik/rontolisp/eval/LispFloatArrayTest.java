package am.ik.rontolisp.eval;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import am.ik.rontolisp.LispDouble;
import am.ik.rontolisp.LispFloatArray;
import am.ik.rontolisp.LispArray;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispTrue;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The packed {@link LispFloatArray} representation on the interpreter: the
 * {@code #f(...)} literal, {@code make-array :element-type 'double-float}, the
 * array-dispatch built-ins, double coercion, the type-error on a non-real store, and
 * byte-identical printing to a general double array.
 */
class LispFloatArrayTest {

	private LispVal eval(String input) {
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(new ByteArrayOutputStream()));
		LispVal result = LispNil.INSTANCE;
		for (LispVal expr : LispReader.readAllFromString(input)) {
			result = evaluator.eval(expr);
		}
		return result;
	}

	private String print(String input) {
		return eval(input).print();
	}

	@Test
	void rank1LiteralIsAPackedFloatArray() {
		LispVal v = eval("#f(1.0 2.0 3.0)");
		assertThat(v).isInstanceOf(LispFloatArray.class);
		assertThat(((LispFloatArray) v).data()).containsExactly(1.0, 2.0, 3.0);
		assertThat(((LispFloatArray) v).dims()).containsExactly(3);
	}

	@Test
	void rank1LiteralPrintsIdenticallyToAGeneralDoubleArray() {
		// A packed array must be byte-identical to #( ... ) of the same doubles.
		assertThat(print("#f(1.0 2.0 3.0)")).isEqualTo("#(1.0 2.0 3.0)");
		assertThat(print("#f(1.0 2.0 3.0)")).isEqualTo(print("#(1.0 2.0 3.0)"));
	}

	@Test
	void rank2LiteralIsAMatrixThatPrintsAsGeneral() {
		LispVal v = eval("#f((1.0 2.0) (3.0 4.0))");
		assertThat(v).isInstanceOf(LispFloatArray.class);
		assertThat(((LispFloatArray) v).dims()).containsExactly(2, 2);
		assertThat(v.print()).isEqualTo("#2A((1.0 2.0) (3.0 4.0))");
		assertThat(v.print()).isEqualTo(print("#2A((1.0 2.0) (3.0 4.0))"));
	}

	@Test
	void integerAndRatioLeavesCoerceToDouble() {
		assertThat(print("#f(1 2 3)")).isEqualTo("#(1.0 2.0 3.0)");
		assertThat(print("#f(1/2 3/4)")).isEqualTo("#(0.5 0.75)");
	}

	@Test
	void aNonRealLeafIsAReadError() {
		assertThatThrownBy(() -> eval("#f(1.0 \"x\")")).hasMessageContaining("#f: expected a number");
	}

	@Test
	void arefReadsABoxedDouble() {
		assertThat(eval("(aref #f(1.0 2.0 3.0) 1)")).isEqualTo(new LispDouble(2.0));
		assertThat(eval("(aref #f((1.0 2.0) (3.0 4.0)) 1 0)")).isEqualTo(new LispDouble(3.0));
		assertThat(eval("(row-major-aref #f((1.0 2.0) (3.0 4.0)) 2)")).isEqualTo(new LispDouble(3.0));
	}

	@Test
	void setfArefCoercesAndMutatesInPlace() {
		assertThat(print("(let ((v #f(1.0 2.0 3.0))) (setf (aref v 1) 9.0) v)")).isEqualTo("#(1.0 9.0 3.0)");
		// An integer store coerces to a double.
		assertThat(eval("(let ((v #f(1.0 2.0))) (setf (aref v 0) 5) (aref v 0))")).isEqualTo(new LispDouble(5.0));
	}

	@Test
	void storingANonRealIsATypeError() {
		assertThatThrownBy(() -> eval("(let ((v #f(1.0))) (setf (aref v 0) \"x\"))")).hasMessageContaining("number");
	}

	@Test
	void lengthAndShapeBuiltins() {
		assertThat(eval("(length #f(1.0 2.0 3.0))").print()).isEqualTo("3");
		assertThat(eval("(array-rank #f((1.0 2.0) (3.0 4.0)))").print()).isEqualTo("2");
		assertThat(eval("(array-dimensions #f((1.0 2.0) (3.0 4.0)))").print()).isEqualTo("(2 2)");
		assertThat(eval("(array-total-size #f((1.0 2.0) (3.0 4.0)))").print()).isEqualTo("4");
	}

	@Test
	void predicates() {
		// %arrayp is the internal predicate arrayp/typep expand to; the raw evaluator
		// here
		// does not run the macro layer, so the internal name is exercised directly.
		assertThat(eval("(%arrayp #f(1.0 2.0))")).isEqualTo(LispTrue.INSTANCE);
		assertThat(eval("(vectorp #f(1.0 2.0))")).isEqualTo(LispTrue.INSTANCE);
		assertThat(eval("(array-element-type #f(1.0))").print()).isEqualTo("double-float");
		assertThat(eval("(array-has-fill-pointer-p #f(1.0))")).isEqualTo(LispNil.INSTANCE);
		assertThat(eval("(adjustable-array-p #f(1.0))")).isEqualTo(LispNil.INSTANCE);
	}

	@Test
	void makeArrayWithDoubleFloatElementTypeIsPacked() {
		LispVal v = eval("(make-array 3 :element-type 'double-float)");
		assertThat(v).isInstanceOf(LispFloatArray.class);
		assertThat(v.print()).isEqualTo("#(0.0 0.0 0.0)");
		assertThat(print("(make-array 3 :element-type 'double-float :initial-element 2.0)"))
			.isEqualTo("#(2.0 2.0 2.0)");
		assertThat(print("(make-array '(2 2) :element-type 'double-float)")).isEqualTo("#2A((0.0 0.0) (0.0 0.0))");
	}

	@Test
	void fillPointerOrAdjustableFallsBackToAGeneralArray() {
		// :element-type double-float combined with :fill-pointer/:adjustable is NOT
		// packed
		// -- those live only on the general array.
		assertThat(eval("(make-array 3 :element-type 'double-float :fill-pointer 0)")).isInstanceOf(LispArray.class);
		assertThat(eval("(make-array 3 :element-type 'double-float :adjustable t)")).isInstanceOf(LispArray.class);
	}

	@Test
	void arraysAreComparedByIdentity() {
		// Two distinct packed arrays are never eq/equal even with identical contents.
		assertThat(eval("(eq #f(1.0) #f(1.0))")).isEqualTo(LispNil.INSTANCE);
		assertThat(eval("(equal #f(1.0 2.0) #f(1.0 2.0))")).isEqualTo(LispNil.INSTANCE);
		assertThat(eval("(let ((v #f(1.0))) (eq v v))")).isEqualTo(LispTrue.INSTANCE);
	}

}
