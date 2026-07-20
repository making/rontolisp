package am.ik.rontolisp.eval;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import am.ik.rontolisp.LispArray;
import am.ik.rontolisp.LispDouble;
import am.ik.rontolisp.LispDoubleFloatArray;
import am.ik.rontolisp.LispFloatArray;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSingleFloatArray;
import am.ik.rontolisp.LispTrue;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The packed {@link LispFloatArray} umbrella on the interpreter, at both widths: the
 * {@code #d(...)} / {@code #f(...)} literals and {@code make-array :element-type
 * 'double-float | 'single-float}, the array-dispatch built-ins, the widen-on-read /
 * narrow-on-write element access, the type-error on a non-real store, and the printed
 * form that round-trips to a packed array of the same width (rather than degrading to a
 * general boxed array). {@code #d(...)} = double-float ({@link LispDoubleFloatArray}, f64
 * backing); {@code #f(...)} = single-float ({@link LispSingleFloatArray}, f32 backing;
 * scalars still widen to double).
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

	// --- double-float (#d) -----------------------------------------------------

	@Test
	void rank1DoubleLiteralIsAPackedDoubleFloatArray() {
		LispVal v = eval("#d(1.0 2.0 3.0)");
		assertThat(v).isInstanceOf(LispDoubleFloatArray.class);
		assertThat(((LispDoubleFloatArray) v).data()).containsExactly(1.0, 2.0, 3.0);
		assertThat(((LispDoubleFloatArray) v).dims()).containsExactly(3);
	}

	@Test
	void rank1DoubleLiteralPrintsWithDSyntaxAndRoundTripsToPacked() {
		// A packed array prints with its own reader syntax, so its printed form reads
		// back
		// as a packed array of the SAME width (preserving the unboxed representation)
		// rather than as a general boxed array -- deliberately NOT #(1.0 2.0 3.0).
		assertThat(print("#d(1.0 2.0 3.0)")).isEqualTo("#d(1.0 2.0 3.0)");
		assertThat(print("#d(1.0 2.0 3.0)")).isNotEqualTo(print("#(1.0 2.0 3.0)"));
		assertThat(eval(print("#d(1.0 2.0 3.0)"))).isInstanceOf(LispDoubleFloatArray.class);
	}

	@Test
	void rank2DoubleLiteralIsAMatrixThatPrintsWithDSyntax() {
		LispVal v = eval("#d((1.0 2.0) (3.0 4.0))");
		assertThat(v).isInstanceOf(LispDoubleFloatArray.class);
		assertThat(((LispDoubleFloatArray) v).dims()).containsExactly(2, 2);
		assertThat(v.print()).isEqualTo("#d((1.0 2.0) (3.0 4.0))");
		assertThat(eval(v.print())).isInstanceOf(LispDoubleFloatArray.class);
	}

	@Test
	void integerAndRatioLeavesCoerceToDouble() {
		assertThat(print("#d(1 2 3)")).isEqualTo("#d(1.0 2.0 3.0)");
		assertThat(print("#d(1/2 3/4)")).isEqualTo("#d(0.5 0.75)");
	}

	@Test
	void arefReadsABoxedDoubleFromADoubleArray() {
		assertThat(eval("(aref #d(1.0 2.0 3.0) 1)")).isEqualTo(new LispDouble(2.0));
		assertThat(eval("(aref #d((1.0 2.0) (3.0 4.0)) 1 0)")).isEqualTo(new LispDouble(3.0));
		assertThat(eval("(row-major-aref #d((1.0 2.0) (3.0 4.0)) 2)")).isEqualTo(new LispDouble(3.0));
	}

	@Test
	void setfArefCoercesAndMutatesADoubleArrayInPlace() {
		assertThat(print("(let ((v #d(1.0 2.0 3.0))) (setf (aref v 1) 9.0) v)")).isEqualTo("#d(1.0 9.0 3.0)");
		// An integer store coerces to a double.
		assertThat(eval("(let ((v #d(1.0 2.0))) (setf (aref v 0) 5) (aref v 0))")).isEqualTo(new LispDouble(5.0));
	}

	@Test
	void makeArrayWithDoubleFloatElementTypeIsPacked() {
		LispVal v = eval("(make-array 3 :element-type 'double-float)");
		assertThat(v).isInstanceOf(LispDoubleFloatArray.class);
		assertThat(v.print()).isEqualTo("#d(0.0 0.0 0.0)");
		assertThat(print("(make-array 3 :element-type 'double-float :initial-element 2.0)"))
			.isEqualTo("#d(2.0 2.0 2.0)");
		assertThat(print("(make-array '(2 2) :element-type 'double-float)")).isEqualTo("#d((0.0 0.0) (0.0 0.0))");
	}

	// --- single-float (#f) -----------------------------------------------------

	@Test
	void rank1SingleLiteralIsAPackedSingleFloatArray() {
		LispVal v = eval("#f(1.0 2.0 3.0)");
		assertThat(v).isInstanceOf(LispSingleFloatArray.class);
		assertThat(((LispSingleFloatArray) v).data()).containsExactly(1.0f, 2.0f, 3.0f);
		assertThat(((LispSingleFloatArray) v).dims()).containsExactly(3);
	}

	@Test
	void rank1SingleLiteralPrintsWithFSyntaxAndRoundTripsToPacked() {
		// Uses f32-exact values (powers of two) so the widened print is exact.
		assertThat(print("#f(1.0 2.0 3.0)")).isEqualTo("#f(1.0 2.0 3.0)");
		assertThat(eval(print("#f(1.0 2.0 3.0)"))).isInstanceOf(LispSingleFloatArray.class);
	}

	@Test
	void rank2SingleLiteralIsAMatrixThatPrintsWithFSyntax() {
		LispVal v = eval("#f((1.0 2.0) (3.0 4.0))");
		assertThat(v).isInstanceOf(LispSingleFloatArray.class);
		assertThat(((LispSingleFloatArray) v).dims()).containsExactly(2, 2);
		assertThat(v.print()).isEqualTo("#f((1.0 2.0) (3.0 4.0))");
		assertThat(eval(v.print())).isInstanceOf(LispSingleFloatArray.class);
	}

	@Test
	void arefWidensAnF32ElementToDouble() {
		// f32-exact values read back exactly (1.5 = 3 * 2^-1 is representable in f32).
		assertThat(eval("(aref #f(1.5 2.5 3.5) 1)")).isEqualTo(new LispDouble(2.5));
		assertThat(eval("(aref #f((1.0 2.0) (3.0 4.0)) 1 0)")).isEqualTo(new LispDouble(3.0));
	}

	@Test
	void setfArefNarrowsToF32AndReadsBackWidened() {
		// An f32-exact value stores and reads back exactly.
		assertThat(print("(let ((v #f(1.0 2.0 3.0))) (setf (aref v 1) 9.0) v)")).isEqualTo("#f(1.0 9.0 3.0)");
		// 0.1 is NOT representable in f32: the store narrows f64 -> f32 and the read
		// widens f32 -> f64, so the value comes back as the f32-rounded double. This
		// proves
		// the storage width is really f32 (an interpreter-only value; cross-backend tests
		// use f32-exact values).
		double f32rounded = (float) 0.1;
		assertThat(eval("(let ((v #f(1.0))) (setf (aref v 0) 0.1) (aref v 0))")).isEqualTo(new LispDouble(f32rounded));
		// An integer store coerces then narrows to f32.
		assertThat(eval("(let ((v #f(1.0 2.0))) (setf (aref v 0) 5) (aref v 0))")).isEqualTo(new LispDouble(5.0));
	}

	@Test
	void rowMajorAsetNarrowsToF32() {
		double f32rounded = (float) 0.2;
		assertThat(eval("(let ((v #f(1.0 2.0))) (setf (row-major-aref v 1) 0.2) (row-major-aref v 1))"))
			.isEqualTo(new LispDouble(f32rounded));
	}

	@Test
	void makeArrayWithSingleFloatElementTypeIsPacked() {
		LispVal v = eval("(make-array 3 :element-type 'single-float)");
		assertThat(v).isInstanceOf(LispSingleFloatArray.class);
		assertThat(v.print()).isEqualTo("#f(0.0 0.0 0.0)");
		assertThat(print("(make-array 3 :element-type 'single-float :initial-element 2.0)"))
			.isEqualTo("#f(2.0 2.0 2.0)");
		assertThat(print("(make-array '(2 2) :element-type 'single-float)")).isEqualTo("#f((0.0 0.0) (0.0 0.0))");
	}

	// --- width-agnostic behavior (both widths) ---------------------------------

	@Test
	void aNonRealLeafIsAReadError() {
		assertThatThrownBy(() -> eval("#f(1.0 \"x\")")).hasMessageContaining("packed float array: expected a number");
		assertThatThrownBy(() -> eval("#d(1.0 \"x\")")).hasMessageContaining("packed float array: expected a number");
	}

	@Test
	void storingANonRealIsATypeError() {
		assertThatThrownBy(() -> eval("(let ((v #f(1.0))) (setf (aref v 0) \"x\"))")).hasMessageContaining("number");
		assertThatThrownBy(() -> eval("(let ((v #d(1.0))) (setf (aref v 0) \"x\"))")).hasMessageContaining("number");
	}

	@Test
	void lengthAndShapeBuiltins() {
		assertThat(eval("(length #f(1.0 2.0 3.0))").print()).isEqualTo("3");
		assertThat(eval("(length #d(1.0 2.0 3.0))").print()).isEqualTo("3");
		assertThat(eval("(array-rank #f((1.0 2.0) (3.0 4.0)))").print()).isEqualTo("2");
		assertThat(eval("(array-dimensions #d((1.0 2.0) (3.0 4.0)))").print()).isEqualTo("(2 2)");
		assertThat(eval("(array-total-size #f((1.0 2.0) (3.0 4.0)))").print()).isEqualTo("4");
	}

	@Test
	void predicates() {
		// %arrayp is the internal predicate arrayp/typep expand to; the raw evaluator
		// here
		// does not run the macro layer, so the internal name is exercised directly.
		assertThat(eval("(%arrayp #f(1.0 2.0))")).isEqualTo(LispTrue.INSTANCE);
		assertThat(eval("(%arrayp #d(1.0 2.0))")).isEqualTo(LispTrue.INSTANCE);
		assertThat(eval("(vectorp #f(1.0 2.0))")).isEqualTo(LispTrue.INSTANCE);
		assertThat(eval("(vectorp #d(1.0 2.0))")).isEqualTo(LispTrue.INSTANCE);
		assertThat(eval("(array-element-type #f(1.0))").print()).isEqualTo("SINGLE-FLOAT");
		assertThat(eval("(array-element-type #d(1.0))").print()).isEqualTo("DOUBLE-FLOAT");
		assertThat(eval("(array-has-fill-pointer-p #f(1.0))")).isEqualTo(LispNil.INSTANCE);
		assertThat(eval("(adjustable-array-p #d(1.0))")).isEqualTo(LispNil.INSTANCE);
	}

	@Test
	void fillPointerOrAdjustableFallsBackToAGeneralArray() {
		// :element-type double-float/single-float combined with :fill-pointer/:adjustable
		// is
		// NOT packed -- those live only on the general array.
		assertThat(eval("(make-array 3 :element-type 'double-float :fill-pointer 0)")).isInstanceOf(LispArray.class);
		assertThat(eval("(make-array 3 :element-type 'single-float :adjustable t)")).isInstanceOf(LispArray.class);
	}

	@Test
	void arraysAreComparedByIdentity() {
		// Two distinct packed arrays are never eq/equal even with identical contents.
		assertThat(eval("(eq #f(1.0) #f(1.0))")).isEqualTo(LispNil.INSTANCE);
		assertThat(eval("(equal #d(1.0 2.0) #d(1.0 2.0))")).isEqualTo(LispNil.INSTANCE);
		assertThat(eval("(let ((v #f(1.0))) (eq v v))")).isEqualTo(LispTrue.INSTANCE);
	}

	@Test
	void singleAndDoubleAreDistinctTypesThatPrintDifferently() {
		// Same contents, different width: distinct types, distinct printed prefixes.
		assertThat(eval("#f(1.0 2.0)")).isInstanceOf(LispSingleFloatArray.class);
		assertThat(eval("#d(1.0 2.0)")).isInstanceOf(LispDoubleFloatArray.class);
		assertThat(print("#f(1.0 2.0)")).isEqualTo("#f(1.0 2.0)");
		assertThat(print("#d(1.0 2.0)")).isEqualTo("#d(1.0 2.0)");
	}

}
