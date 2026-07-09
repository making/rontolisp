package am.ik.rontolisp.eval;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import am.ik.rontolisp.LispDoubleFloatArray;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSingleFloatArray;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The interpreter's opt-in {@code --simd} acceleration ({@link VecSimd}): the seven
 * vectorizable {@code vec:} kernels run on {@code jdk.incubator.vector} instead of the
 * scalar {@code vec.lisp} defuns, while the default interpreter keeps the scalar
 * reference (the cross-backend byte-identity oracle).
 *
 * <p>
 * Every kernel is checked against the scalar reference on f64-exact inputs (integers), at
 * both widths and on both sides of the {@code THRESHOLD = 128} lane-loop gate -- so the
 * scalar tail and the vector loop are both exercised, and reduction associativity cannot
 * make the two disagree. Requires {@code --add-modules jdk.incubator.vector} (the
 * surefire {@code argLine} supplies it).
 */
class VecSimdTest {

	private LispVal eval(String input, boolean simd) {
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(new ByteArrayOutputStream()));
		evaluator.setSimd(simd);
		LispVal result = LispNil.INSTANCE;
		for (LispVal expr : LispReader.readAllFromString(input)) {
			result = evaluator.eval(expr);
		}
		return result;
	}

	/** Asserts that the accelerated result is identical to the scalar reference's. */
	private void assertMatchesScalarOracle(String input) {
		assertThat(eval(input, true).print()).isEqualTo(eval(input, false).print());
	}

	@Test
	void theVectorApiIsAvailableUnderTheSurefireAddModules() {
		assertThat(VecSimd.available()).isTrue();
	}

	// --- interception fired (the "dead flag" guard) ------------------------------

	@Test
	void simdReplacesTheVectorizableDefunsWithNativeFunctions() {
		// A vec.lisp defun is a LispLambda ("#<lambda>"); the installed kernel is a
		// native LispFunction. Without this the flag could be silently dead and every
		// numeric assertion below would still pass on the scalar reference.
		assertThat(eval("(vec:dot #d(1.0) #d(1.0)) #'vec:dot", true).print()).isEqualTo("#<function vec:dot>");
		assertThat(eval("(vec:dot #d(1.0) #d(1.0)) #'vec:dot", false).print()).isEqualTo("#<lambda>");
	}

	@Test
	void nonVectorizableVecFunctionsStayOnTheScalarDefuns() {
		// Construction / access / list conversion are untouched by --simd.
		assertThat(eval("(vec:zeros 1) #'vec:from-list", true).print()).isEqualTo("#<lambda>");
		assertThat(eval("(vec:zeros 1) #'vec:aref", true).print()).isEqualTo("#<lambda>");
	}

	// --- reductions --------------------------------------------------------------

	@Test
	void dotAndSumMatchTheScalarOracleBelowTheLaneLoopThreshold() {
		assertMatchesScalarOracle("(vec:dot (vec:arange 7) (vec:arange 7))");
		assertMatchesScalarOracle("(vec:sum (vec:arange 7))");
	}

	@Test
	void dotAndSumMatchTheScalarOracleAboveTheLaneLoopThreshold() {
		// n = 200 > THRESHOLD (128), and not a multiple of any lane count, so the vector
		// loop AND its scalar tail both run.
		assertMatchesScalarOracle("(vec:dot (vec:arange 200) (vec:arange 200))");
		assertMatchesScalarOracle("(vec:sum (vec:arange 200))");
	}

	@Test
	void dotAndSumMatchTheScalarOracleForSingleFloatVectors() {
		assertMatchesScalarOracle("(vec:dot (vec:arange 200 'single-float) (vec:arange 200 'single-float))");
		assertMatchesScalarOracle("(vec:sum (vec:arange 200 'single-float))");
		assertMatchesScalarOracle("(vec:dot (vec:arange 7 'single-float) (vec:arange 7 'single-float))");
	}

	@Test
	void meanAndNormAreAcceleratedTransitivelyThroughSumAndDot() {
		// vec:mean / vec:norm keep their scalar bodies; the vec:sum / vec:dot they call
		// resolve to the installed natives (Lisp-2 global function namespace).
		assertThat(eval("(vec:mean #d(1.0 2.0 3.0 4.0))", true).print()).isEqualTo("2.5");
		assertThat(eval("(vec:norm #d(3.0 4.0))", true).print()).isEqualTo("5.0");
		assertMatchesScalarOracle("(vec:mean (vec:arange 200))");
		assertMatchesScalarOracle("(vec:norm (vec:arange 200))");
	}

	// --- element-wise ------------------------------------------------------------

	@Test
	void elementWiseKernelsMatchTheScalarOracleAtBothSizes() {
		for (String n : new String[] { "7", "200" }) {
			assertMatchesScalarOracle("(vec:add (vec:arange %s) (vec:ones %s))".formatted(n, n));
			assertMatchesScalarOracle("(vec:sub (vec:arange %s) (vec:ones %s))".formatted(n, n));
			assertMatchesScalarOracle("(vec:mul (vec:arange %s) (vec:arange %s))".formatted(n, n));
			assertMatchesScalarOracle("(vec:scale (vec:arange %s) 0.5)".formatted(n));
		}
	}

	@Test
	void elementWiseKernelsPreserveTheOperandWidth() {
		assertThat(eval("(vec:add #d(1.0 2.0) #d(3.0 4.0))", true)).isInstanceOf(LispDoubleFloatArray.class);
		assertThat(eval("(vec:add #f(1.0 2.0) #f(3.0 4.0))", true)).isInstanceOf(LispSingleFloatArray.class);
		assertThat(eval("(vec:scale #f(1.0 2.0) 2)", true).print()).isEqualTo("#f(2.0 4.0)");
		assertMatchesScalarOracle("(vec:mul (vec:arange 200 'single-float) (vec:arange 200 'single-float))");
	}

	// --- matvec (GEMV) -----------------------------------------------------------

	@Test
	void matvecMatchesTheScalarOracle() {
		assertThat(eval("(vec:matvec #d((1.0 2.0) (3.0 4.0)) #d(5.0 6.0))", true).print()).isEqualTo("#d(17.0 39.0)");
		assertMatchesScalarOracle("(vec:matvec #d((1.0 2.0 3.0) (4.0 5.0 6.0)) #d(1.0 2.0 3.0))");
		assertMatchesScalarOracle("(vec:matvec #f((1.0 2.0) (3.0 4.0)) #f(5.0 6.0))");
	}

	@Test
	void matvecMatchesTheScalarOracleWithARowLongerThanTheLaneLoopThreshold() {
		String matrix = """
				(let ((w (make-array '(3 200) :element-type 'double-float :initial-element 0.0))
				      (x (vec:arange 200)))
				  (dotimes (i 3)
				    (dotimes (j 200)
				      (setf (aref w i j) (float (+ (* i 200) j)))))
				  (vec:matvec w x))
				""";
		assertMatchesScalarOracle(matrix);
	}

	@Test
	void matvecRejectsARank1Matrix() {
		assertThatThrownBy(() -> eval("(vec:matvec #d(1.0 2.0) #d(1.0 2.0))", true))
			.isInstanceOf(LispEvalException.class)
			.hasMessageContaining("expects a rank-2 matrix");
	}

	// --- fixed-width contract ----------------------------------------------------

	@Test
	void mixingSingleAndDoubleFloatOperandsIsAnError() {
		assertThatThrownBy(() -> eval("(vec:add #d(1.0) #f(1.0))", true)).isInstanceOf(LispEvalException.class)
			.hasMessageContaining("must share an element type");
		assertThatThrownBy(() -> eval("(vec:dot #f(1.0) #d(1.0))", true)).isInstanceOf(LispEvalException.class)
			.hasMessageContaining("must share an element type");
	}

	@Test
	void aNonPackedArgumentIsAClearError() {
		assertThatThrownBy(() -> eval("(vec:sum '(1 2 3))", true)).isInstanceOf(LispEvalException.class)
			.hasMessageContaining("expects a packed float array");
	}

}
