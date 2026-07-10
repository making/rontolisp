package am.ik.rontolisp.eval;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The interpreter's opt-in {@code --simd} acceleration of {@code linalg:}
 * ({@link LinalgSimd}), the sibling of {@link VecSimdTest}. Fifteen {@code linalg.lisp}
 * defuns run on {@code jdk.incubator.vector} instead of their boxed element loops, while
 * the default interpreter keeps the scalar reference (the cross-backend byte-identity
 * oracle).
 *
 * <p>
 * Two things are checked throughout. (1) The interception actually FIRES -- without the
 * {@code #<function linalg:add>} guard the flag could be silently dead and every numeric
 * assertion here would still pass on the scalar reference
 * ([[simd-shadow-and-dead-flag-lesson]]). (2) Every accelerated result is compared
 * against the scalar oracle at both widths, at rank 1 AND rank 2 (a rank-n element-wise
 * op is the case {@code vec:} never had), on both sides of the {@code THRESHOLD = 128}
 * lane-loop gate, with a scalar operand on either side, and for the inputs the kernels
 * DECLINE (general arrays, mixed widths, plain numbers, shape errors) -- those must fall
 * back to the defun rather than change behavior.
 *
 * <p>
 * Requires {@code --add-modules jdk.incubator.vector} (the surefire {@code argLine}
 * supplies it).
 */
class LinalgSimdTest {

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
		assertThat(eval(input, true).print()).as(input).isEqualTo(eval(input, false).print());
	}

	@Test
	void theVectorApiIsAvailableUnderTheSurefireAddModules() {
		assertThat(LinalgSimd.available()).isTrue();
	}

	// --- interception fired (the "dead flag" guard) ------------------------------

	@Test
	void simdReplacesTheAcceleratedDefunsWithNativeFunctions() {
		// A linalg.lisp defun is a LispLambda ("#<lambda>"); the installed kernel is a
		// native LispFunction. This is the only assertion in the file that fails if the
		// --simd flag never reaches the interceptor.
		for (String member : new String[] { "add", "sub", "mul", "div", "sum", "norm", "amax", "amin", "argmax",
				"argmin", "trace", "transpose", "reshape", "dot", "outer" }) {
			String form = "(linalg:zeros 1) #'linalg:" + member;
			assertThat(eval(form, true).print()).as(member).isEqualTo("#<function linalg:" + member + ">");
			assertThat(eval(form, false).print()).as(member).isEqualTo("#<lambda>");
		}
	}

	@Test
	void opaqueAndTransitivelyAcceleratedFunctionsStayOnTheScalarDefuns() {
		// emap takes an arbitrary Lisp callback; det/inv/solve pivot; array-equal has a
		// per-element numberp check and can return nil (which would collide with the
		// null "declined" sentinel). mean/matmul/flatten are accelerated TRANSITIVELY --
		// their bodies call sum/dot/reshape -- so they stay lambdas too.
		for (String member : new String[] { "emap", "det", "inv", "solve", "array-equal", "mean", "matmul", "flatten",
				"zeros", "eye" }) {
			assertThat(eval("(linalg:zeros 1) #'linalg:" + member, true).print()).as(member).isEqualTo("#<lambda>");
		}
	}

	// --- element-wise: array with array -------------------------------------------

	@Test
	void elementWiseKernelsMatchTheScalarOracleAtBothSizesAndWidths() {
		for (String op : new String[] { "add", "sub", "mul", "div" }) {
			// n = 7 runs the scalar tail only; n = 200 the lane loop AND its tail.
			assertMatchesScalarOracle("(linalg:%s (linalg:arange 1 8) (linalg:arange 2 9))".formatted(op));
			assertMatchesScalarOracle("(linalg:%s (linalg:arange 1 201) (linalg:arange 2 202))".formatted(op));
			assertMatchesScalarOracle(
					"(linalg:%s (linalg:arange 1 201 1 'single-float) (linalg:arange 2 202 1 'single-float))"
						.formatted(op));
		}
	}

	@Test
	void aRank2ElementWiseResultKeepsItsDimensions() {
		// The case vec: never had: its kernels always produce a rank-1 vector, while a
		// linalg element-wise result must keep the operand's rank-n shape.
		assertThat(eval("(linalg:add (linalg:full '(2 3) 1.0) (linalg:full '(2 3) 2.0))", true).print())
			.isEqualTo("#d((3.0 3.0 3.0) (3.0 3.0 3.0))");
		assertMatchesScalarOracle("(linalg:mul (linalg:reshape (linalg:arange 200) '(10 20)) "
				+ "(linalg:reshape (linalg:arange 200) '(10 20)))");
		assertMatchesScalarOracle("(linalg:sub (linalg:reshape (linalg:arange 24) '(2 3 4)) "
				+ "(linalg:reshape (linalg:arange 24) '(2 3 4)))");
	}

	@Test
	void elementWiseKernelsPreserveTheOperandWidth() {
		assertThat(eval("(linalg:add (linalg:ones 3 'single-float) (linalg:ones 3 'single-float))", true).print())
			.isEqualTo("#f(2.0 2.0 2.0)");
		assertThat(eval("(linalg:add (linalg:ones 3) (linalg:ones 3))", true).print()).isEqualTo("#d(2.0 2.0 2.0)");
	}

	// --- element-wise: scalar broadcast -------------------------------------------

	@Test
	void scalarBroadcastMatchesTheScalarOracleOnEitherSide() {
		for (String op : new String[] { "add", "sub", "mul", "div" }) {
			assertMatchesScalarOracle("(linalg:%s (linalg:arange 1 201) 3.0)".formatted(op));
			assertMatchesScalarOracle("(linalg:%s 3.0 (linalg:arange 1 201))".formatted(op));
			assertMatchesScalarOracle("(linalg:%s (linalg:arange 1 201) 3)".formatted(op));
			assertMatchesScalarOracle("(linalg:%s (linalg:reshape (linalg:arange 1 201) '(10 20)) 2.0)".formatted(op));
		}
	}

	@Test
	void aSingleFloatArrayBroadcastAgainstAnInexactScalarStaysBitIdenticalToTheOracle() {
		// The reason the single-float broadcast kernels compute in double and narrow once
		// (a scalar loop) instead of splatting (float) s into f32 lanes: 0.1 is not
		// representable, so x +f (float) 0.1 differs from (float) ((double) x + 0.1).
		// examples/ml/nn-vec.lisp is exactly this shape -- (linalg:mul grad 0.1) over an
		// #f gradient -- and its printed output must not move under --simd.
		for (String op : new String[] { "add", "sub", "mul", "div" }) {
			assertMatchesScalarOracle("(linalg:%s (linalg:arange 1 201 1 'single-float) 0.1)".formatted(op));
			assertMatchesScalarOracle("(linalg:%s 0.1 (linalg:arange 1 201 1 'single-float))".formatted(op));
		}
	}

	// --- the inputs the kernels decline (fall back to the scalar defun) -----------

	@Test
	void generalBoxedArraysFallBackToTheScalarDefun() {
		// linalg accepts the ordinary #(...) / #2A(...) arrays too; the packed kernels
		// cannot read them, so the defun runs and still returns a packed double result.
		assertThat(eval("(linalg:add #(1 2 3) #(10 20 30))", true).print()).isEqualTo("#d(11.0 22.0 33.0)");
		assertThat(eval("(linalg:sum #(1 2 3))", true).print()).isEqualTo("6");
		assertMatchesScalarOracle("(linalg:dot #2A((1 2) (3 4)) #(1 1))");
	}

	@Test
	void mixedWidthOperandsFallBackToTheScalarDefun() {
		// Unlike vec:, a mixed-width linalg call is NOT an error: the defun widens both
		// operands and keeps the first one's width. The kernels decline so it still does.
		assertThat(eval("(linalg:add #d(1.0 2.0) #f(10.0 20.0))", true).print()).isEqualTo("#d(11.0 22.0)");
		assertThat(eval("(linalg:add #f(1.0 2.0) #d(10.0 20.0))", true).print()).isEqualTo("#f(11.0 22.0)");
		assertThat(eval("(linalg:dot #d(1.0 2.0) #f(3.0 4.0))", true).print()).isEqualTo("11.0");
	}

	@Test
	void twoNumbersFallBackToTheScalarDefunAndStayExact() {
		assertThat(eval("(linalg:add 2 3)", true).print()).isEqualTo("5");
		assertThat(eval("(linalg:div 1 3)", true).print()).isEqualTo("1/3");
	}

	@Test
	void aShapeMismatchStillSignalsTheLibraryError() {
		// The kernels decline, the defun signals -- so the message is not duplicated.
		assertThatThrownBy(() -> eval("(linalg:add #d(1.0) #d(1.0 2.0))", true))
			.hasMessageContaining("linalg: shape mismatch");
		assertThatThrownBy(() -> eval("(linalg:trace #d((1.0 2.0 3.0) (4.0 5.0 6.0)))", true))
			.hasMessageContaining("expects a square matrix");
		assertThatThrownBy(() -> eval("(linalg:dot #d(1.0) #d(1.0 2.0))", true))
			.hasMessageContaining("equal-length vectors");
		assertThatThrownBy(() -> eval("(linalg:reshape #d(1.0 2.0) '(3 3))", true))
			.hasMessageContaining("reshape size mismatch");
	}

	// --- reductions ----------------------------------------------------------------

	@Test
	void reductionsMatchTheScalarOracleAtBothSizesAndWidths() {
		for (String size : new String[] { "7", "200" }) {
			assertMatchesScalarOracle("(linalg:sum (linalg:arange %s))".formatted(size));
			assertMatchesScalarOracle("(linalg:mean (linalg:arange %s))".formatted(size));
			assertMatchesScalarOracle("(linalg:amax (linalg:arange %s))".formatted(size));
			assertMatchesScalarOracle("(linalg:amin (linalg:arange %s))".formatted(size));
			assertMatchesScalarOracle("(linalg:argmax (linalg:arange %s))".formatted(size));
			assertMatchesScalarOracle("(linalg:argmin (linalg:arange %s))".formatted(size));
			assertMatchesScalarOracle("(linalg:norm (linalg:arange %s))".formatted(size));
			assertMatchesScalarOracle("(linalg:sum (linalg:arange 0 %s 'single-float))".formatted(size));
			assertMatchesScalarOracle("(linalg:amax (linalg:arange 0 %s 'single-float))".formatted(size));
		}
		// A rank-2 reduction walks the flat store, like the defun.
		assertMatchesScalarOracle("(linalg:sum (linalg:reshape (linalg:arange 200) '(10 20)))");
		assertMatchesScalarOracle("(linalg:amin (linalg:reshape (linalg:arange 200) '(10 20)))");
		assertMatchesScalarOracle("(linalg:trace (linalg:reshape (linalg:arange 100) '(10 10)))");
		assertMatchesScalarOracle("(linalg:trace (linalg:eye 5 'single-float))");
	}

	@Test
	void amaxAndAminKeepTheOracleStrictComparisonSemantics() {
		// An all-negative array is the trap a max-reduce over zero-padded lanes falls
		// into. Ties, -0.0 and NaN all follow the defun's `(when (> x best) ...)`.
		assertThat(eval("(linalg:amax (linalg:mul (linalg:arange 1 201) -1.0))", true).print()).isEqualTo("-1.0");
		assertThat(eval("(linalg:amin (linalg:arange 1 201))", true).print()).isEqualTo("1.0");
		assertMatchesScalarOracle("(linalg:amax #d(-0.0 0.0))");
		assertMatchesScalarOracle("(linalg:amax #d(0.0 -0.0))");
		assertMatchesScalarOracle("(linalg:argmax #d(1.0 5.0 5.0 2.0))");
		assertMatchesScalarOracle("(linalg:argmin #d(3.0 1.0 1.0 2.0))");
	}

	@Test
	void singleFloatReductionsAccumulateInSinglePrecisionUnderSimd() {
		// The todo-106 precision contract, extended to linalg, and the ONLY test that
		// pins it: every other #f input in this file stays under 2^24, where an f32
		// accumulator is exact, so they pass on both contracts.
		//
		// v = #f(4096.0 1.0 ... 1.0), 1024 elements. dot(v,v) = 4096^2 + 1023 = 16778239
		// exactly. 4096^2 is 2^24, where the f32 spacing is 2, so whichever of the four
		// pinned lanes holds it swallows every 1.0 added to it; the other three fold 256
		// ones each, giving 2^24 + 768 = 16777984.
		assertThat(eval(probe32("4096.0", "(linalg:dot v v)"), true).print()).isEqualTo("16777984");
		assertThat(eval(probe32("4096.0", "(linalg:dot v v)"), false).print()).isEqualTo("16778239");
		assertThat(eval(probe32("16777216.0", "(linalg:sum v)"), true).print()).isEqualTo("16777984");
		assertThat(eval(probe32("16777216.0", "(linalg:sum v)"), false).print()).isEqualTo("16778239");
		// mean is accelerated transitively through sum, so it moves with it.
		assertThat(eval(probe32("16777216.0", "(* 1024 (linalg:mean v))"), true).print()).isEqualTo("16777984");
		assertThat(eval(probe32("16777216.0", "(* 1024 (linalg:mean v))"), false).print()).isEqualTo("16778239");
		// matrix . vector is a dot per row (four pinned lanes). The scalar path
		// accumulates 16778239 in f64 and narrows on store: an odd multiple of the f32
		// spacing at 2^24, so it ties to even -> 16778240.
		String gemv = probe32("4096.0", "(aref (linalg:dot (linalg:reshape v '(1 1024)) v) 0)");
		assertThat(eval(gemv, true).print()).isEqualTo("16777984");
		assertThat(eval(gemv, false).print()).isEqualTo("16778240");
		// The #d control: double-float reductions are untouched by the contract, and
		// exact on both paths for these inputs.
		String probe64 = """
				(let ((v (linalg:ones 1024)))
				  (setf (aref v 0) 4096.0)
				  (round (linalg:dot v v)))
				""";
		assertThat(eval(probe64, true).print()).isEqualTo("16778239");
		assertThat(eval(probe64, false).print()).isEqualTo("16778239");
	}

	/** A 1024-element {@code #f} vector of ones with {@code elem0} in element 0. */
	private String probe32(String elem0, String reduction) {
		return """
				(let ((v (linalg:ones 1024 'single-float)))
				  (setf (aref v 0) %s)
				  (round %s))
				""".formatted(elem0, reduction);
	}

	// --- products --------------------------------------------------------------------

	@Test
	void dotDispatchesLikeNumpyAndMatchesTheScalarOracle() {
		// vector . vector -> scalar; matrix . vector and vector . matrix -> vector;
		// matrix . matrix -> matrix. A scalar operand multiplies element-wise.
		assertMatchesScalarOracle("(linalg:dot (linalg:arange 200) (linalg:arange 200))");
		assertMatchesScalarOracle("(linalg:dot (linalg:reshape (linalg:arange 200) '(10 20)) (linalg:arange 20))");
		assertMatchesScalarOracle("(linalg:dot (linalg:arange 10) (linalg:reshape (linalg:arange 200) '(10 20)))");
		assertMatchesScalarOracle("(linalg:dot (linalg:reshape (linalg:arange 200) '(10 20)) "
				+ "(linalg:reshape (linalg:arange 200) '(20 10)))");
		assertMatchesScalarOracle("(linalg:dot (linalg:arange 20) 3.0)");
		assertMatchesScalarOracle("(linalg:dot 3.0 (linalg:arange 20))");
		// A row longer than THRESHOLD, so the matmul lane loop over the output row runs.
		assertMatchesScalarOracle("(linalg:dot (linalg:reshape (linalg:arange 600) '(3 200)) "
				+ "(linalg:reshape (linalg:arange 600) '(200 3)))");
		assertMatchesScalarOracle("(linalg:matmul (linalg:eye 5) (linalg:reshape (linalg:arange 25) '(5 5)))");
	}

	@Test
	void doubleFloatMatrixProductsAreBitIdenticalBecauseIkjKeepsTheOracleSummationOrder() {
		// The kernel rewrites the oracle's ijk triple loop as ikj so that b's rows are
		// read contiguously. That visits k in the same increasing order into the same
		// accumulator cell, so the f64 result is not merely close -- it is identical.
		assertThat(eval("(linalg:matmul (linalg:reshape (linalg:arange 1 10) '(3 3)) "
				+ "(linalg:reshape (linalg:arange 1 10) '(3 3)))", true)
			.print()).isEqualTo("#d((30.0 36.0 42.0) (66.0 81.0 96.0) (102.0 126.0 150.0))");
		assertMatchesScalarOracle("(linalg:matmul (linalg:linspace 0.1 9.9 99) "
				+ "(linalg:reshape (linalg:linspace 0.01 9.9 99) '(99 1)))");
	}

	@Test
	void productsPreserveTheOperandWidth() {
		assertThat(eval("(linalg:dot (linalg:eye 2 'single-float) (linalg:ones 2 'single-float))", true).print())
			.isEqualTo("#f(1.0 1.0)");
		assertThat(eval("(linalg:outer (linalg:ones 2 'single-float) (linalg:ones 3 'single-float))", true).print())
			.isEqualTo("#f((1.0 1.0 1.0) (1.0 1.0 1.0))");
	}

	@Test
	void outerMatchesTheScalarOracleAndFlattensItsInputs() {
		assertMatchesScalarOracle("(linalg:outer (linalg:arange 1 5) (linalg:arange 1 4))");
		assertMatchesScalarOracle("(linalg:outer (linalg:arange 200) (linalg:arange 200))");
		assertMatchesScalarOracle("(linalg:outer (linalg:reshape (linalg:arange 6) '(2 3)) (linalg:arange 4))");
		assertMatchesScalarOracle("(linalg:outer (linalg:arange 0 4 'single-float) (linalg:arange 0 3 'single-float))");
	}

	// --- shape ------------------------------------------------------------------------

	@Test
	void transposeReshapeAndFlattenMatchTheScalarOracle() {
		assertMatchesScalarOracle("(linalg:transpose (linalg:reshape (linalg:arange 200) '(10 20)))");
		assertMatchesScalarOracle("(linalg:transpose (linalg:reshape (linalg:arange 12) '(3 4) ))");
		assertMatchesScalarOracle("(linalg:transpose (linalg:arange 0 5 'single-float))");
		assertMatchesScalarOracle("(linalg:reshape (linalg:arange 200) '(4 5 10))");
		assertMatchesScalarOracle("(linalg:reshape (linalg:arange 0 12 'single-float) 12)");
		assertMatchesScalarOracle("(linalg:flatten (linalg:reshape (linalg:arange 200) '(10 20)))");
	}

	@Test
	void transposeReturnsAVectorUnchanged() {
		// linalg.lisp returns `a` itself for a rank-1 input, so eq must still hold.
		assertThat(eval("(let ((v (linalg:arange 5))) (eq v (linalg:transpose v)))", true).print()).isEqualTo("t");
		assertThat(eval("(let ((v (linalg:arange 5))) (eq v (linalg:transpose v)))", false).print()).isEqualTo("t");
	}

	// --- element-wise unary ufuncs (todo 109) -------------------------------------

	@Test
	void simdReplacesTheUnaryUfuncDefunsWithNativeFunctions() {
		for (String member : new String[] { "exp", "sqrt", "abs", "negative", "sign" }) {
			String form = "(linalg:zeros 1) #'linalg:" + member;
			assertThat(eval(form, true).print()).as(member).isEqualTo("#<function linalg:" + member + ">");
			assertThat(eval(form, false).print()).as(member).isEqualTo("#<lambda>");
		}
		// square/reciprocal are accelerated transitively -- their bodies call
		// linalg:mul / linalg:div -- so they stay lambdas.
		assertThat(eval("(linalg:zeros 1) #'linalg:square", true).print()).isEqualTo("#<lambda>");
		assertThat(eval("(linalg:zeros 1) #'linalg:reciprocal", true).print()).isEqualTo("#<lambda>");
	}

	@Test
	void unaryUfuncsMatchTheScalarOracleAtBothSizesWidthsAndRanks() {
		for (String op : new String[] { "sqrt", "abs", "square", "negative", "sign", "reciprocal" }) {
			// sqrt gets non-negative inputs; the rest a sign-mixed vector.
			String inner = op.equals("sqrt") ? "(linalg:add %v 1)" : "(linalg:sub %v 100)";
			for (String n : new String[] { "7", "200" }) {
				assertMatchesScalarOracle(
						"(linalg:" + op + " " + inner.replace("%v", "(linalg:arange " + n + ")") + ")");
			}
			assertMatchesScalarOracle("(linalg:" + op + " (linalg:reshape (linalg:arange 12) '(3 4)))");
			assertMatchesScalarOracle("(linalg:" + op + " (linalg:add (linalg:arange 0 200 'single-float) 1))");
		}
		// exp over reciprocal's (0, 1] range so the values stay bounded.
		assertMatchesScalarOracle("(linalg:exp (linalg:reciprocal (linalg:add (linalg:arange 200) 1)))");
		assertMatchesScalarOracle("(linalg:exp (linalg:reciprocal (linalg:add (linalg:arange 7) 1)))");
		assertMatchesScalarOracle(
				"(linalg:exp (linalg:reshape (linalg:reciprocal (linalg:add (linalg:arange 12) 1)) '(3 4)))");
		assertMatchesScalarOracle(
				"(linalg:exp (linalg:reciprocal (linalg:add (linalg:arange 0 200 'single-float) 1)))");
	}

	@Test
	void unaryUfuncsMatchTheScalarOracleOnSignedZeroEdges() {
		// Math.abs / true negation / Math.signum on both paths, matching the defun's
		// own edges (the per-backend contract; wasm's defun edges differ and its
		// kernels mirror those instead).
		assertMatchesScalarOracle("(linalg:negative #d(0.0 -0.0 1.0))");
		assertMatchesScalarOracle("(linalg:abs #d(-0.0 0.0 -2.5))");
		assertMatchesScalarOracle("(linalg:sign #d(-0.0 0.0 -3.5 3.5))");
		assertThat(eval("(linalg:negative #d(0.0))", true).print()).isEqualTo("#d(-0.0)");
	}

	@Test
	void aGeneralBoxedArrayDeclinesToTheScalarDefunForTheUnaryUfuncs() {
		// A general #(...) array is not packed, so every unary kernel declines and the
		// defun answers -- identically on both paths.
		for (String op : new String[] { "exp", "sqrt", "abs", "square", "negative", "sign", "reciprocal" }) {
			assertMatchesScalarOracle("(linalg:" + op + " #(1 4 9))");
		}
		assertThat(eval("(linalg:sqrt #(4 9))", true).print()).isEqualTo("#d(2.0 3.0)");
	}

	@Test
	void aRank3OperandDeclinesToTheScalarDefunForTheRank2OnlyMembers() {
		// transpose/trace/dot are rank <= 2 in linalg.lisp; a rank-3 input must reach the
		// defun (and signal there) rather than be silently reinterpreted by a kernel.
		// (The defun fails inside aref with a subscript-count error, whatever its class.)
		assertThatThrownBy(() -> eval("(linalg:trace (linalg:reshape (linalg:arange 24) '(2 3 4)))", true))
			.isInstanceOf(RuntimeException.class);
		assertThatThrownBy(() -> eval("(linalg:transpose (linalg:reshape (linalg:arange 24) '(2 3 4)))", true))
			.isInstanceOf(RuntimeException.class);
	}

}
