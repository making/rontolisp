package am.ik.rontolisp.eval;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import am.ik.rontolisp.LispDoubleFloatArray;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The interpreter's opt-in {@code --blas} acceleration of the {@code linalg:} matrix
 * product ({@link LinalgBlas}), the sibling of {@link LinalgSimdTest}.
 *
 * <p>
 * Whether a tuned CBLAS exists is a property of the MACHINE, not of the build, so the
 * whole class is conditional: it runs where one was found (macOS always; Linux when the
 * user installed one) and skips where none was, which is the same answer the flag itself
 * gives. Nothing here is allowed to require the library -- {@link LinalgBlasDeclineTest}
 * covers what every machine must do.
 *
 * <p>
 * Three things are checked. (1) The interception actually FIRES -- without the
 * {@code #<function linalg:dot>} guard the flag could be silently dead and every numeric
 * assertion would still pass on the defun ([[simd-shadow-and-dead-flag-lesson]]). (2) On
 * inputs that are exact at the operand width the library product equals the scalar oracle
 * EXACTLY, and on inputs that are not it agrees to a tight relative tolerance -- the
 * blocked reduction is the whole of the difference. (3) Everything the kernel declines
 * (boxed arrays, mixed widths, rank 3, a product below the size threshold, a shape
 * mismatch) behaves exactly as it does without the flag.
 */
@EnabledIf("am.ik.rontolisp.eval.LinalgBlasTest#tunedBlasIsAvailable")
class LinalgBlasTest {

	static boolean tunedBlasIsAvailable() {
		return LinalgBlas.available();
	}

	private LispVal eval(String input, boolean blas, boolean simd) {
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(new ByteArrayOutputStream()));
		evaluator.setSimd(simd);
		evaluator.setBlas(blas);
		LispVal result = LispNil.INSTANCE;
		for (LispVal expr : LispReader.readAllFromString(input)) {
			result = evaluator.eval(expr);
		}
		return result;
	}

	private LispVal eval(String input, boolean blas) {
		return eval(input, blas, false);
	}

	/**
	 * Asserts that the accelerated result prints exactly what the scalar defun's does.
	 */
	private void assertMatchesScalarOracle(String input) {
		assertThat(eval(input, true).print()).as(input).isEqualTo(eval(input, false).print());
	}

	private double[] elements(String input, boolean blas) {
		return ((LispDoubleFloatArray) eval(input, blas)).data();
	}

	// --- the dead-flag guard ---------------------------------------------------------

	@Test
	void blasReplacesTheProductDefunWithANativeFunctionAndTouchesNothingElse() {
		// A linalg.lisp defun is a LispLambda ("#<lambda>"); the installed kernel is a
		// native LispFunction. This is the only assertion in the file that fails if the
		// flag never reaches the interceptor.
		assertThat(eval("(linalg:zeros 1) #'linalg:dot", true).print()).isEqualTo("#<function LINALG:DOT>");
		assertThat(eval("(linalg:zeros 1) #'linalg:dot", false).print()).isEqualTo("#<lambda>");
		// One member and no other: matmul is accelerated through dot, not instead of it.
		for (String member : new String[] { "matmul", "add", "sum", "outer", "transpose" }) {
			assertThat(eval("(linalg:zeros 1) #'linalg:" + member, true).print()).as(member).isEqualTo("#<lambda>");
		}
	}

	// --- the accelerated shapes ------------------------------------------------------

	@Test
	void theMatrixProductMatchesTheScalarOracleOnExactInputs() {
		// Integers up to 2^53 are exact at double, so a reordered reduction of them is
		// not merely close to the oracle's, it is equal.
		assertMatchesScalarOracle("""
				(defparameter *a* (linalg:reshape (linalg:arange 1 65) '(8 8)))
				(defparameter *b* (linalg:reshape (linalg:arange 65 129) '(8 8)))
				(linalg:matmul *a* *b*)
				""");
		// A non-square product, so a transposed leading dimension would show up.
		assertMatchesScalarOracle("""
				(defparameter *a* (linalg:reshape (linalg:arange 1 25) '(4 6)))
				(defparameter *b* (linalg:reshape (linalg:arange 1 31) '(6 5)))
				(linalg:matmul *a* *b*)
				""");
		// The two gemv shapes: matrix by vector, and vector by matrix (b^T x).
		assertMatchesScalarOracle("""
				(defparameter *a* (linalg:reshape (linalg:arange 1 25) '(4 6)))
				(linalg:dot *a* (linalg:arange 1 7))
				""");
		assertMatchesScalarOracle("""
				(defparameter *a* (linalg:reshape (linalg:arange 1 25) '(4 6)))
				(linalg:dot (linalg:arange 1 5) *a*)
				""");
		// linalg:solve composes inv with dot, so it rides the same interception.
		assertMatchesScalarOracle("""
				(defparameter *a* (linalg:add (linalg:eye 8) (linalg:reshape (linalg:arange 1 65) '(8 8))))
				(linalg:dot *a* (linalg:transpose *a*))
				""");
	}

	@Test
	void theSingleFloatProductMatchesTheScalarOracleOnExactInputs() {
		assertMatchesScalarOracle("""
				(defparameter *a*
				  (linalg:reshape (linalg:arange 1 65 :element-type 'single-float) '(8 8)))
				(linalg:matmul *a* *a*)
				""");
		assertMatchesScalarOracle("""
				(defparameter *a*
				  (linalg:reshape (linalg:arange 1 65 :element-type 'single-float) '(8 8)))
				(linalg:dot *a* (linalg:arange 1 9 :element-type 'single-float))
				""");
	}

	@Test
	void anInexactProductAgreesWithTheOracleToATightRelativeTolerance() {
		// The library blocks and reorders its reduction, so on inputs that are not exact
		// at the operand width it is CLOSE to the oracle rather than equal to it. That is
		// the whole of the precision contract, and this is what "close" costs: a few ulps
		// over a 64-long fold. (Which library and which version is installed decides the
		// exact figure, which is why 1e-12 rather than an equality.)
		String product = """
				(defparameter *a* (linalg:reshape (linalg:div (linalg:arange 1 4097) 7.0) '(64 64)))
				(defparameter *b* (linalg:reshape (linalg:div (linalg:arange 4097 8193) 3.0) '(64 64)))
				(linalg:matmul *a* *b*)
				""";
		double[] accelerated = elements(product, true);
		double[] oracle = elements(product, false);
		assertThat(accelerated).hasSameSizeAs(oracle);
		double worst = 0;
		for (int i = 0; i < oracle.length; i++) {
			worst = Math.max(worst, Math.abs(accelerated[i] - oracle[i]) / Math.abs(oracle[i]));
		}
		assertThat(worst).isLessThan(1e-12).isGreaterThan(0);
	}

	// --- what it declines ------------------------------------------------------------

	@Test
	void declinedOperandsRunTheScalarDefunUnchanged() {
		// A general (boxed) array, on either side or both.
		assertMatchesScalarOracle("(linalg:matmul #2A((1 2) (3 4)) #2A((5 6) (7 8)))");
		assertMatchesScalarOracle("(linalg:dot #2A((1 2) (3 4)) #d((5.0 6.0) (7.0 8.0)))");
		// Mixed widths: the defun widens both and keeps the first operand's width.
		assertMatchesScalarOracle("""
				(defparameter *d* (linalg:reshape (linalg:arange 1 65) '(8 8)))
				(defparameter *f* (linalg:reshape (linalg:arange 1 65 :element-type 'single-float) '(8 8)))
				(linalg:matmul *d* *f*)
				""");
		// A scalar operand, which the defun routes to linalg:mul.
		assertMatchesScalarOracle("(linalg:dot #d((1.0 2.0) (3.0 4.0)) 3.0)");
		// A vector-by-vector dot: memory-bound, deliberately not intercepted.
		assertMatchesScalarOracle("(linalg:dot (linalg:arange 1 100) (linalg:arange 1 100))");
		// Rank 3: the STACKED product, which goes through %la-matmul-nd.
		assertMatchesScalarOracle("""
				(defparameter *a* (linalg:reshape (linalg:arange 1 33) '(2 4 4)))
				(linalg:matmul *a* *a*)
				""");
		// Below the size threshold, where a library call cannot pay for itself.
		assertMatchesScalarOracle("(linalg:matmul #d((1.0 2.0) (3.0 4.0)) #d((5.0 6.0) (7.0 8.0)))");
	}

	@Test
	void aShapeMismatchStillSignalsTheDefunsOwnError() {
		assertThatThrownBy(() -> eval("""
				(linalg:matmul (linalg:reshape (linalg:arange 1 25) '(4 6))
				               (linalg:reshape (linalg:arange 1 25) '(4 6)))
				""", true)).hasMessageContaining("matmul inner dimensions differ");
		assertThatThrownBy(
				() -> eval("(linalg:dot (linalg:arange 1 33) (linalg:reshape (linalg:arange 1 33) '(4 8)))", true))
			.hasMessageContaining("dot");
	}

	// --- composition with --simd ------------------------------------------------------

	@Test
	void withSimdOnTooTheProductStillMatchesTheOracleAndDeclinesToTheLaneKernel() {
		// --blas installs LAST, so what it declines to is the --simd native rather than
		// the defun. Every result must still agree with the scalar oracle.
		String product = """
				(defparameter *a* (linalg:reshape (linalg:arange 1 65) '(8 8)))
				(linalg:matmul *a* *a*)
				""";
		assertThat(eval(product, true, true).print()).isEqualTo(eval(product, false, false).print());
		// A shape the library declines: the lane kernel answers, not the defun.
		String declined = "(linalg:dot (linalg:arange 1 100) (linalg:arange 1 100))";
		assertThat(eval(declined, true, true).print()).isEqualTo(eval(declined, false, false).print());
		// And the interception is still the one on top.
		assertThat(eval("(linalg:zeros 1) #'linalg:dot", true, true).print()).isEqualTo("#<function LINALG:DOT>");
	}

}
