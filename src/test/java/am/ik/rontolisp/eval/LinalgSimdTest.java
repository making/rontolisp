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
 * ({@link LinalgSimd}), the sibling of {@link VecSimdTest}. Forty-nine
 * {@code linalg.lisp} defuns run on {@code jdk.incubator.vector} instead of their boxed
 * element loops, while the default interpreter keeps the scalar reference (the
 * cross-backend byte-identity oracle).
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
			assertThat(eval(form, true).print()).as(member)
				.isEqualTo("#<function LINALG:" + member.toUpperCase(java.util.Locale.ROOT) + ">");
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

	@Test
	void axisFormsRunTheFoldKernelsAndMatchTheScalarOracle() {
		// The axis forms of sum/amax/amin/argmax/argmin (and transpose's axes form) are
		// intercepted as call SHAPES: the natives accept an arity RANGE,
		// and the fold kernels mirror %la-fold-axis / %la-argfold-axis exactly (double
		// accumulation, the defun's seeds and strict comparisons), so every axis result
		// is bit-identical to the oracle at both widths.
		assertMatchesScalarOracle("(linalg:sum (linalg:reshape (linalg:arange 6) '(2 3)) :axis 0)");
		assertMatchesScalarOracle("(linalg:sum (linalg:reshape (linalg:arange 6) '(2 3)) :axis 1 :keepdims t)");
		assertMatchesScalarOracle("(linalg:sum (linalg:reshape (linalg:arange 24) '(2 3 4)) :axis 1)");
		assertMatchesScalarOracle("(linalg:sum (linalg:reshape (linalg:arange 6) '(2 3)) :axis -1)");
		assertMatchesScalarOracle("(linalg:sum (linalg:arange 5) :axis 0)");
		assertMatchesScalarOracle("(linalg:sum (linalg:arange 5) :axis 0 :keepdims t)");
		assertMatchesScalarOracle(
				"(linalg:sum (linalg:from-list '((0.5 0.25) (0.125 2.0)) :element-type 'single-float) :axis 0)");
		assertMatchesScalarOracle("(linalg:mean (linalg:reshape (linalg:arange 6) '(2 3)) :axis 0)");
		assertMatchesScalarOracle("(linalg:mean (linalg:reshape (linalg:arange 6) '(2 3)) :axis 1 :keepdims t)");
		assertMatchesScalarOracle("(linalg:amax (linalg:reshape (linalg:arange 6) '(2 3)) :axis 1)");
		assertMatchesScalarOracle("(linalg:amin (linalg:reshape (linalg:arange 6) '(2 3)) :axis 0 :keepdims t)");
		assertMatchesScalarOracle(
				"(linalg:amax (linalg:from-list '((0.5 0.25) (0.125 2.0)) :element-type 'single-float) :axis 1)");
		assertMatchesScalarOracle("(linalg:amax (linalg:arange 5) :axis 0)");
		assertMatchesScalarOracle("(linalg:argmax (linalg:reshape (linalg:arange 6) '(2 3)) :axis 1)");
		assertMatchesScalarOracle("(linalg:argmin (linalg:reshape (linalg:arange 6) '(2 3)) :axis 0)");
		assertMatchesScalarOracle("(linalg:argmax (linalg:from-list '(3.0 9.0 2.0)) :axis 0)");
		assertMatchesScalarOracle(
				"(linalg:argmin (linalg:from-list '((0.5 0.25) (0.125 2.0)) :element-type 'single-float) :axis -1)");
		assertMatchesScalarOracle("(linalg:reshape (linalg:arange 12) '(3 -1))");
		assertThat(eval("(linalg:sum #d((1.0 2.0 3.0) (4.0 5.0 6.0)) :axis 0)", true).print())
			.isEqualTo("#d(5.0 7.0 9.0)");
		// A vector without keepdims reduces to the scalar itself; argmax to the index.
		assertThat(eval("(linalg:sum #f(0.5 0.25) :axis 0)", true).print()).isEqualTo("0.75");
		assertThat(eval("(linalg:argmax #d(3.0 9.0 2.0) :axis 0)", true).print()).isEqualTo("1");
	}

	@Test
	void axisFoldsKeepTheOracleStrictComparisonAndSeedSemantics() {
		// The fold's (if (> x acc) x acc) lets the ACCUMULATOR win ties and NaN -- the
		// opposite of the element-wise select -- and the seed is the first element
		// along the axis, so an all-negative axis never answers 0.
		assertThat(eval("(linalg:amax #d((-0.0 0.0)) :axis 1)", true).print()).isEqualTo("#d(-0.0)");
		assertThat(eval("(linalg:amin #d((0.0 -0.0)) :axis 1)", true).print()).isEqualTo("#d(0.0)");
		assertThat(eval("(linalg:amax #d((-3.0 -1.0) (-5.0 -2.0)) :axis 1)", true).print()).isEqualTo("#d(-1.0 -2.0)");
		assertThat(eval("(linalg:argmax #d((-0.0 0.0) (0.0 -0.0)) :axis 1)", true).print()).isEqualTo("#d(0.0 0.0)");
		assertMatchesScalarOracle("(linalg:amax #d((-0.0 0.0)) :axis 1)");
		assertMatchesScalarOracle("(linalg:amin #d((0.0 -0.0)) :axis 1)");
	}

	@Test
	void axisFormDeclinedInputsRunTheScalarDefun() {
		// A nil axis is the defun's no-axis path; a general boxed array, an
		// out-of-range axis and an empty axis decline too (the defun signals its own
		// errors for the latter two).
		assertMatchesScalarOracle("(linalg:sum #d((1.0 2.0)))");
		assertMatchesScalarOracle("(linalg:sum #2A((1 2) (3 4)) :axis 0)");
		assertMatchesScalarOracle("(linalg:argmax #(1 9 2) :axis 0)");
		assertThatThrownBy(() -> eval("(linalg:sum #d((1.0 2.0)) :axis 2)", true))
			.hasMessageContaining("axis out of range");
		assertThatThrownBy(() -> eval("(linalg:amax (linalg:zeros '(2 0)) :axis 1)", true))
			.hasMessageContaining("reduction of an empty axis");
	}

	@Test
	void transposeAxesMatchesTheScalarOracle() {
		// The 2-argument axes form (%la-transpose-axes) is a pure permutation copy.
		assertMatchesScalarOracle("(linalg:transpose (linalg:reshape (linalg:arange 24) '(2 3 2 2)) '(0 3 1 2))");
		assertMatchesScalarOracle("(linalg:transpose (linalg:reshape (linalg:arange 24) '(2 3 2 2)) '(0 2 3 1))");
		assertMatchesScalarOracle("(linalg:transpose (linalg:reshape (linalg:arange 6) '(2 3)) '(1 0))");
		assertMatchesScalarOracle("(linalg:transpose (linalg:arange 3) '(0))");
		assertMatchesScalarOracle(
				"(linalg:transpose (linalg:reshape (linalg:from-list '(1.0 2.0 3.0 4.0 5.0 6.0 7.0 8.0)"
						+ " :element-type 'single-float) '(2 2 2)) '(2 0 1))");
		// A bad permutation declines, so the defun's own error still signals.
		assertThatThrownBy(() -> eval("(linalg:transpose #d((1.0 2.0)) '(0 0))", true))
			.hasMessageContaining("permutation");
		assertThatThrownBy(() -> eval("(linalg:transpose #d((1.0 2.0)) '(0))", true))
			.hasMessageContaining("permutation");
	}

	// --- element-wise: array with array -------------------------------------------

	@Test
	void elementWiseKernelsMatchTheScalarOracleAtBothSizesAndWidths() {
		for (String op : new String[] { "add", "sub", "mul", "div" }) {
			// n = 7 runs the scalar tail only; n = 200 the lane loop AND its tail.
			assertMatchesScalarOracle("(linalg:%s (linalg:arange 1 8) (linalg:arange 2 9))".formatted(op));
			assertMatchesScalarOracle("(linalg:%s (linalg:arange 1 201) (linalg:arange 2 202))".formatted(op));
			assertMatchesScalarOracle(
					"(linalg:%s (linalg:arange 1 201 1 :element-type 'single-float) (linalg:arange 2 202 1 :element-type 'single-float))"
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
		assertThat(eval(
				"(linalg:add (linalg:ones 3 :element-type 'single-float) (linalg:ones 3 :element-type 'single-float))",
				true)
			.print()).isEqualTo("#f(2.0 2.0 2.0)");
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
			assertMatchesScalarOracle(
					"(linalg:%s (linalg:arange 1 201 1 :element-type 'single-float) 0.1)".formatted(op));
			assertMatchesScalarOracle(
					"(linalg:%s 0.1 (linalg:arange 1 201 1 :element-type 'single-float))".formatted(op));
		}
	}

	// --- element-wise: comparison selects ------------------------------------------

	@Test
	void simdReplacesTheComparisonSelectDefunsWithNativeFunctions() {
		for (String member : new String[] { "maximum", "minimum" }) {
			String form = "(linalg:zeros 1) #'linalg:" + member;
			assertThat(eval(form, true).print()).as(member)
				.isEqualTo("#<function LINALG:" + member.toUpperCase(java.util.Locale.ROOT) + ">");
			assertThat(eval(form, false).print()).as(member).isEqualTo("#<lambda>");
		}
		// clip / relu are accelerated transitively -- their defuns compose
		// linalg:maximum / linalg:minimum -- so they stay lambdas, like
		// square/reciprocal.
		assertThat(eval("(linalg:zeros 1) #'linalg:clip", true).print()).isEqualTo("#<lambda>");
		assertThat(eval("(linalg:zeros 1) #'linalg:relu", true).print()).isEqualTo("#<lambda>");
	}

	@Test
	void comparisonSelectsMatchTheScalarOracleAtBothSizesWidthsAndShapes() {
		for (String op : new String[] { "maximum", "minimum" }) {
			// a ascends through zero, the negation descends: the winner flips
			// mid-array; both sizes, both widths, a rank-2 shape, and both broadcast
			// sides (all values integer-valued, exact at either width).
			for (String n : new String[] { "8", "201" }) {
				assertMatchesScalarOracle(
						"(let ((a (linalg:sub (linalg:arange 1 %1$s) 100.0))) (linalg:%2$s a (linalg:negative a)))"
							.formatted(n, op));
			}
			assertMatchesScalarOracle(
					"(let ((a (linalg:arange 1 201 1 :element-type 'single-float))) (linalg:%s a (linalg:negative a)))"
						.formatted(op));
			assertMatchesScalarOracle(
					"(linalg:%s (linalg:reshape (linalg:arange 200) '(10 20)) (linalg:negative (linalg:reshape (linalg:arange 200) '(10 20))))"
						.formatted(op));
			assertMatchesScalarOracle("(linalg:%s (linalg:sub (linalg:arange 1 201) 100.0) 3.0)".formatted(op));
			assertMatchesScalarOracle("(linalg:%s 3.0 (linalg:sub (linalg:arange 1 201) 100.0))".formatted(op));
			// The f32-vs-scalar broadcast must compare the widened element against the
			// FULL double scalar (an inexact bound), like the arithmetic broadcasts.
			assertMatchesScalarOracle(
					"(linalg:%s (linalg:arange 1 201 1 :element-type 'single-float) 100.3)".formatted(op));
			assertMatchesScalarOracle(
					"(linalg:%s 100.3 (linalg:arange 1 201 1 :element-type 'single-float))".formatted(op));
		}
		// clip / relu ride the maximum/minimum kernels transitively.
		assertMatchesScalarOracle("(linalg:clip (linalg:sub (linalg:arange 1 201) 100.0) -50.0 50.0)");
		assertMatchesScalarOracle("(linalg:relu (linalg:sub (linalg:arange 1 201) 100.0))");
		assertMatchesScalarOracle("(linalg:relu (linalg:reshape (linalg:sub (linalg:arange 200) 100.0) '(10 20)))");
	}

	@Test
	void comparisonSelectsFollowTheStrictComparisonNotMathMax() {
		// The second operand wins any false comparison: ties (a -0.0/0.0 pair) and
		// unordered NaN comparisons -- unlike Math.max/Math.min. Same rule as amax.
		assertMatchesScalarOracle("(linalg:maximum #d(-0.0 0.0) #d(0.0 -0.0))");
		assertMatchesScalarOracle("(linalg:minimum #d(-0.0 0.0) #d(0.0 -0.0))");
		assertThat(eval("(linalg:maximum #d(-0.0) #d(0.0))", true).print()).isEqualTo("#d(0.0)");
		assertThat(eval("(linalg:maximum #d(0.0) #d(-0.0))", true).print()).isEqualTo("#d(-0.0)");
		assertMatchesScalarOracle("(linalg:maximum (linalg:mul (linalg:ones 2) (/ 0.0 0.0)) #d(1.0 2.0))");
		assertMatchesScalarOracle("(linalg:maximum #d(1.0 2.0) (linalg:mul (linalg:ones 2) (/ 0.0 0.0)))");
		// The scalar-broadcast tie: (linalg:maximum #d(-0.0) 0.0) selects the bound.
		assertThat(eval("(linalg:maximum #d(-0.0) 0.0)", true).print()).isEqualTo("#d(0.0)");
		assertThat(eval("(linalg:minimum #d(0.0) -0.0)", true).print()).isEqualTo("#d(-0.0)");
		// relu maps -0.0 and NaN to 0.0; clip sends a NaN element to lo.
		assertThat(eval("(linalg:relu #d(-0.0))", true).print()).isEqualTo("#d(0.0)");
		assertThat(eval("(linalg:clip (linalg:mul (linalg:ones 1) (/ 0.0 0.0)) -1.0 1.0)", true).print())
			.isEqualTo("#d(-1.0)");
	}

	@Test
	void comparisonSelectsDeclineTheSameInputsAsTheArithmeticKernels() {
		// General boxed arrays, mixed widths and two plain numbers all fall back to
		// the %la-bcast defun.
		assertThat(eval("(linalg:maximum #(1 5 3) #(4 2 3))", true).print()).isEqualTo("#d(4.0 5.0 3.0)");
		assertThat(eval("(linalg:minimum #d(1.0 5.0) #f(4.0 2.0))", true).print()).isEqualTo("#d(1.0 2.0)");
		assertThat(eval("(linalg:maximum 2 3)", true).print()).isEqualTo("3");
		assertThat(eval("(linalg:minimum 1/3 1/2)", true).print()).isEqualTo("1/3");
		// A broadcastable pair declines to the defun, which broadcasts it.
		assertThat(eval("(linalg:maximum #d(1.0) #d(1.0 2.0))", true).print()).isEqualTo("#d(1.0 2.0)");
		assertThatThrownBy(() -> eval("(linalg:maximum #d(1.0 2.0) #d(1.0 2.0 3.0))", true))
			.hasMessageContaining("linalg: shape mismatch");
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
	void broadcastPairsRunTheBcastKernelAndMatchTheScalarOracle() {
		// Two same-width arrays of different-but-broadcastable shapes run the general
		// numpy odometer kernel -- every element computed
		// in double and narrowed only into a single-float result, %la-bcast-loop's own
		// rule, so bit-identical at both widths. A mixed-width broadcast still declines
		// to the defun.
		assertThat(
				eval("(linalg:mul (linalg:reshape (linalg:from-list '(1 2 3 4)) '(2 2)) #d(10.0 20.0))", true).print())
			.isEqualTo("#d((10.0 40.0) (30.0 80.0))");
		assertMatchesScalarOracle("(linalg:add (linalg:reshape (linalg:arange 6) '(2 3)) (linalg:arange 3))");
		assertMatchesScalarOracle("(linalg:sub (linalg:reshape (linalg:arange 0 4 :element-type 'single-float) '(2 2))"
				+ " (linalg:arange 0 2 :element-type 'single-float))");
		assertThat(
				eval("(array-element-type (linalg:div (linalg:ones '(2 2) :element-type 'single-float) #d(1.0 2.0)))",
						true)
					.print())
			.isEqualTo("SINGLE-FLOAT");
		// The row and column shapes of the CNN layers, and a rank-3 vs rank-2 pair.
		assertMatchesScalarOracle("(linalg:sub (linalg:reshape (linalg:arange 6) '(2 3))"
				+ " (linalg:reshape (linalg:from-list '(100.0 200.0)) '(2 1)))");
		assertMatchesScalarOracle("(linalg:mul (linalg:reshape (linalg:arange 8) '(4 2))"
				+ " (linalg:reshape (linalg:from-list '(5.0 6.0 7.0 8.0)) '(4 1)))");
		assertMatchesScalarOracle("(linalg:div (linalg:reshape (linalg:arange 24) '(2 3 4))"
				+ " (linalg:add (linalg:reshape (linalg:arange 12) '(3 4)) 1))");
		assertMatchesScalarOracle("(linalg:mul (linalg:reshape (linalg:arange 3) '(3 1)) (linalg:arange 1 3))");
		assertMatchesScalarOracle("(linalg:add (linalg:reshape (linalg:arange 0 4 :element-type 'single-float) '(2 2))"
				+ " (linalg:reshape (linalg:arange 0 2 :element-type 'single-float) '(2 1)))");
		// The comparison selects broadcast through the same kernel: the strict select,
		// second operand wins the -0.0/0.0 tie.
		assertMatchesScalarOracle("(linalg:maximum (linalg:reshape (linalg:from-list '(1.0 5.0 3.0 2.0)) '(2 2))"
				+ " (linalg:from-list '(2.0 4.0)))");
		assertMatchesScalarOracle("(linalg:minimum (linalg:reshape (linalg:from-list '(1.0 5.0 3.0 2.0)) '(2 2))"
				+ " (linalg:reshape (linalg:from-list '(2.0 4.0)) '(2 1)))");
		assertThat(eval("(linalg:maximum #d((0.0 -0.0)) #d(-0.0 0.0))", true).print()).isEqualTo("#d((-0.0 0.0))");
	}

	@Test
	void aShapeMismatchStillSignalsTheLibraryError() {
		// The kernels decline, the defun signals -- so the message is not duplicated.
		// (1) vs (2) broadcasts since the numpy rules landed, so the non-broadcastable
		// (2) vs (3) is the mismatch case now.
		assertThatThrownBy(() -> eval("(linalg:add #d(1.0 2.0) #d(1.0 2.0 3.0))", true))
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
			assertMatchesScalarOracle("(linalg:sum (linalg:arange 0 %s :element-type 'single-float))".formatted(size));
			assertMatchesScalarOracle("(linalg:amax (linalg:arange 0 %s :element-type 'single-float))".formatted(size));
		}
		// A rank-2 reduction walks the flat store, like the defun.
		assertMatchesScalarOracle("(linalg:sum (linalg:reshape (linalg:arange 200) '(10 20)))");
		assertMatchesScalarOracle("(linalg:amin (linalg:reshape (linalg:arange 200) '(10 20)))");
		assertMatchesScalarOracle("(linalg:trace (linalg:reshape (linalg:arange 100) '(10 10)))");
		assertMatchesScalarOracle("(linalg:trace (linalg:eye 5 :element-type 'single-float))");
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
		// The single-precision #f-reduction contract (.kb/vec.md), extended to linalg,
		// and the ONLY test that pins it: every other #f input in this file stays under
		// 2^24, where an f32
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
		// linalg's matrix . vector is not a kernel of its own: LinalgSimdKernels.matvecF
		// delegates to vec:matvec's, on every backend, so it moved with it in todo-480.
		// The scalar path accumulates 16778239 in f64 and narrows on store: an odd
		// multiple of the f32 spacing at 2^24, so it ties to even -> 16778240.
		// A GEMV row is NOT vec:dot's chain any more (todo-480): above
		// MATVEC_ACC_THRESHOLD columns it folds four independent f32x4 accumulators as
		// (a0 + a1) + (a2 + a3), so 1024 columns group as sixteen lanes rather than four
		// -- the lane holding 2^24 swallows only its own 63 ones and the other fifteen
		// fold 64 each, giving 2^24 + 960 = 16778176. The scalar path is unchanged.
		String gemv = probe32("4096.0", "(aref (linalg:dot (linalg:reshape v '(1 1024)) v) 0)");
		assertThat(eval(gemv, true).print()).isEqualTo("16778176");
		assertThat(eval(gemv, false).print()).isEqualTo("16778240");
		// The MATRIX PRODUCT follows the contract too, since it gained f32 lanes: an
		// #f cell folds k in the oracle's own ascending order but at single precision.
		// p = 1 here, so the fold runs in the scalar tail.
		String vm = """
				(let ((v (linalg:ones 1024 :element-type 'single-float)))
				  (setf (aref v 0) 4096.0)
				  (round (aref (linalg:dot v (linalg:reshape v '(1024 1))) 0)))
				""";
		assertThat(eval(vm, true).print()).isEqualTo("16777216");
		assertThat(eval(vm, false).print()).isEqualTo("16778240");
		// A 200-column b runs the same fold in the LANE loop (200 crosses THRESHOLD) and
		// in the tail behind it, for every cell of the row. The lanes run across j, which
		// carries no summation, so the lane count cannot move the answer -- which is why
		// all three --simd backends agree on it.
		String mm = """
				(let ((v (linalg:ones 1024 :element-type 'single-float)))
				  (setf (aref v 0) 4096.0)
				  (let ((r (linalg:dot v (linalg:outer v (linalg:ones 200 :element-type 'single-float)))))
				    (list (round (aref r 0)) (round (aref r 199)))))
				""";
		assertThat(eval(mm, true).print()).isEqualTo("(16777216 16777216)");
		assertThat(eval(mm, false).print()).isEqualTo("(16778240 16778240)");
		// The STACKED product folds each cell exactly as a per-batch linalg:dot does --
		// that is its precision contract, and it puts %la-matmul-nd on the same
		// 16777216 the v.M probe above lands on, at rank 3 and on every --simd backend.
		String nd = """
				(let ((v (linalg:ones 1024 :element-type 'single-float)))
				  (setf (aref v 0) 4096.0)
				  (round (row-major-aref
				          (linalg:matmul (linalg:reshape v '(1 1 1024)) (linalg:reshape v '(1 1024 1))) 0)))
				""";
		assertThat(eval(nd, true).print()).isEqualTo("16777216");
		assertThat(eval(nd, false).print()).isEqualTo("16778240");
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
				(let ((v (linalg:ones 1024 :element-type 'single-float)))
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
		assertThat(eval(
				"(linalg:dot (linalg:eye 2 :element-type 'single-float) (linalg:ones 2 :element-type 'single-float))",
				true)
			.print()).isEqualTo("#f(1.0 1.0)");
		assertThat(eval(
				"(linalg:outer (linalg:ones 2 :element-type 'single-float) (linalg:ones 3 :element-type 'single-float))",
				true)
			.print()).isEqualTo("#f((1.0 1.0 1.0) (1.0 1.0 1.0))");
	}

	@Test
	void outerMatchesTheScalarOracleAndFlattensItsInputs() {
		assertMatchesScalarOracle("(linalg:outer (linalg:arange 1 5) (linalg:arange 1 4))");
		assertMatchesScalarOracle("(linalg:outer (linalg:arange 200) (linalg:arange 200))");
		assertMatchesScalarOracle("(linalg:outer (linalg:reshape (linalg:arange 6) '(2 3)) (linalg:arange 4))");
		assertMatchesScalarOracle(
				"(linalg:outer (linalg:arange 0 4 :element-type 'single-float) (linalg:arange 0 3 :element-type 'single-float))");
	}

	// --- shape ------------------------------------------------------------------------

	@Test
	void transposeReshapeAndFlattenMatchTheScalarOracle() {
		assertMatchesScalarOracle("(linalg:transpose (linalg:reshape (linalg:arange 200) '(10 20)))");
		assertMatchesScalarOracle("(linalg:transpose (linalg:reshape (linalg:arange 12) '(3 4) ))");
		assertMatchesScalarOracle("(linalg:transpose (linalg:arange 0 5 :element-type 'single-float))");
		assertMatchesScalarOracle("(linalg:reshape (linalg:arange 200) '(4 5 10))");
		assertMatchesScalarOracle("(linalg:reshape (linalg:arange 0 12 :element-type 'single-float) 12)");
		assertMatchesScalarOracle("(linalg:flatten (linalg:reshape (linalg:arange 200) '(10 20)))");
	}

	@Test
	void transposeReturnsAVectorUnchanged() {
		// linalg.lisp returns `a` itself for a rank-1 input, so eq must still hold.
		assertThat(eval("(let ((v (linalg:arange 5))) (eq v (linalg:transpose v)))", true).print()).isEqualTo("T");
		assertThat(eval("(let ((v (linalg:arange 5))) (eq v (linalg:transpose v)))", false).print()).isEqualTo("T");
	}

	// --- element-wise unary ufuncs ------------------------------------------------

	@Test
	void simdReplacesTheUnaryUfuncDefunsWithNativeFunctions() {
		for (String member : new String[] { "exp", "log", "tanh", "sin", "cos", "tan", "asin", "acos", "atan", "sinh",
				"cosh", "sqrt", "abs", "negative", "sign", "erf" }) {
			String form = "(linalg:zeros 1) #'linalg:" + member;
			assertThat(eval(form, true).print()).as(member)
				.isEqualTo("#<function LINALG:" + member.toUpperCase(java.util.Locale.ROOT) + ">");
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
			assertMatchesScalarOracle(
					"(linalg:" + op + " (linalg:add (linalg:arange 0 200 :element-type 'single-float) 1))");
		}
		// exp over reciprocal's (0, 1] range so the values stay bounded.
		assertMatchesScalarOracle("(linalg:exp (linalg:reciprocal (linalg:add (linalg:arange 200) 1)))");
		assertMatchesScalarOracle("(linalg:exp (linalg:reciprocal (linalg:add (linalg:arange 7) 1)))");
		assertMatchesScalarOracle(
				"(linalg:exp (linalg:reshape (linalg:reciprocal (linalg:add (linalg:arange 12) 1)) '(3 4)))");
		assertMatchesScalarOracle(
				"(linalg:exp (linalg:reciprocal (linalg:add (linalg:arange 0 200 :element-type 'single-float) 1)))");
		// log over strictly positive inputs, tanh over a sign-mixed range (both are
		// Math.log / Math.tanh scalar loops on this backend).
		for (String n : new String[] { "7", "200" }) {
			assertMatchesScalarOracle("(linalg:log (linalg:add (linalg:arange " + n + ") 1))");
			assertMatchesScalarOracle("(linalg:tanh (linalg:mul (linalg:sub (linalg:arange " + n + ") 100) 0.03))");
		}
		assertMatchesScalarOracle("(linalg:log (linalg:reshape (linalg:add (linalg:arange 12) 1) '(3 4)))");
		assertMatchesScalarOracle("(linalg:log (linalg:add (linalg:arange 0 200 :element-type 'single-float) 1))");
		assertMatchesScalarOracle("(linalg:tanh (linalg:reshape (linalg:arange 12) '(3 4)))");
		assertMatchesScalarOracle("(linalg:tanh (linalg:arange 0 200 :element-type 'single-float))");
		// sin / cos / tan over a sign-mixed range (Math.sin / Math.cos / Math.tan
		// scalar loops on this backend).
		for (String op : new String[] { "sin", "cos", "tan" }) {
			for (String n : new String[] { "7", "200" }) {
				assertMatchesScalarOracle("(linalg:" + op + " (linalg:sub (linalg:arange " + n + ") 100))");
			}
			assertMatchesScalarOracle("(linalg:" + op + " (linalg:reshape (linalg:arange 12) '(3 4)))");
			assertMatchesScalarOracle("(linalg:" + op + " (linalg:arange 0 200 :element-type 'single-float))");
		}
		// asin / acos over the scaled [-0.5, 0.5) domain, atan / sinh / cosh over the
		// sign-mixed range.
		for (String op : new String[] { "asin", "acos" }) {
			for (String n : new String[] { "7", "200" }) {
				assertMatchesScalarOracle(
						"(linalg:" + op + " (linalg:mul (linalg:sub (linalg:arange " + n + ") 100) 0.005))");
			}
			assertMatchesScalarOracle(
					"(linalg:" + op + " (linalg:mul (linalg:arange 0 200 :element-type 'single-float) 0.005))");
		}
		for (String op : new String[] { "atan", "sinh", "cosh" }) {
			for (String n : new String[] { "7", "200" }) {
				assertMatchesScalarOracle(
						"(linalg:" + op + " (linalg:mul (linalg:sub (linalg:arange " + n + ") 100) 0.05))");
			}
			assertMatchesScalarOracle(
					"(linalg:" + op + " (linalg:reshape (linalg:mul (linalg:arange 12) 0.05) '(3 4)))");
			assertMatchesScalarOracle(
					"(linalg:" + op + " (linalg:mul (linalg:arange 0 200 :element-type 'single-float) 0.05))");
		}
		assertMatchesScalarOracle("(linalg:asin #d(0.0 -0.0 1.0 -1.0 0.5))");
		assertMatchesScalarOracle("(linalg:acos #d(1.0 -1.0 0.0 0.5))");
		assertMatchesScalarOracle("(linalg:sinh #d(0.0 -0.0 0.25 -0.25 0.3))");
		assertMatchesScalarOracle("(linalg:cosh #d(0.0 -0.0 1.0))");
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
	void erfMatchesTheScalarOracleOverTheWholeRangeAtBothWidths() {
		// linalg:erf is the one activation primitive whose defun is an emap, so the
		// member is intercepted. The kernel keeps %la-erf-1's order of operations, so it
		// is bit-identical -- checked where that could break: x = 0 and -0.0, negatives,
		// the |x| >= 6 short circuit on both sides of the cutoff, and the |x| ~ 3 region
		// where the alternating Maclaurin series would have lost every digit.
		String range = "#d(0.0 -0.0 1.0e-8 -1.0e-8 0.5 -0.5 1.0 -1.0 2.0 -2.0 2.9 -2.9 3.0 -3.0 3.1 -3.1 "
				+ "4.0 -4.0 5.0 -5.0 5.999999 -5.999999 6.0 -6.0 6.0000001 -6.0000001 7.0 -7.0 12.5 -12.5)";
		assertMatchesScalarOracle("(linalg:erf " + range + ")");
		assertMatchesScalarOracle("(linalg:erf " + range.replace("#d", "#f") + ")");
		// Both ranks and both lengths, over a sign-mixed sweep.
		for (String n : new String[] { "7", "200" }) {
			assertMatchesScalarOracle("(linalg:erf (linalg:mul (linalg:sub (linalg:arange " + n + ") 100) 0.07))");
		}
		assertMatchesScalarOracle("(linalg:erf (linalg:reshape (linalg:mul (linalg:arange 12) 0.5) '(3 4)))");
		assertMatchesScalarOracle("(linalg:erf (linalg:mul (linalg:arange 0 200 :element-type 'single-float) 0.07))");
		// The exact torch:gelu rides on it, and must not move either.
		assertMatchesScalarOracle("(torch:data (torch:gelu (torch:tensor '(-3.0 -1.0 0.0 1.0 3.0))))");
	}

	@Test
	void aGeneralBoxedArrayDeclinesToTheScalarDefunForTheUnaryUfuncs() {
		// A general #(...) array is not packed, so every unary kernel declines and the
		// defun answers -- identically on both paths.
		for (String op : new String[] { "exp", "log", "tanh", "sin", "cos", "tan", "sqrt", "abs", "square", "negative",
				"sign", "reciprocal", "erf" }) {
			assertMatchesScalarOracle("(linalg:" + op + " #(1 4 9))");
		}
		for (String op : new String[] { "asin", "acos", "atan", "sinh", "cosh" }) {
			assertMatchesScalarOracle("(linalg:" + op + " #(0 1))");
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

	// --- CNN window unfolding: %la-im2col / %la-col2im -----------------------------

	@Test
	void im2colAndCol2imAreInterceptedUnderSimd() {
		// The internal pair is intercepted too (the dead-flag guard: a --simd run that
		// silently fell back would still pass every value test): the installed kernel
		// is a native LispFunction, the default a linalg.lisp lambda.
		for (String member : new String[] { "%la-im2col", "%la-col2im" }) {
			String form = "(linalg:zeros 1) #'linalg::" + member;
			assertThat(eval(form, true).print()).as(member)
				.isEqualTo("#<function LINALG::" + member.toUpperCase(java.util.Locale.ROOT) + ">");
			assertThat(eval(form, false).print()).as(member).isEqualTo("#<lambda>");
		}
	}

	@Test
	void im2colMatchesTheScalarOracleAtBothWidths() {
		// Batch > 1, channels > 1, stride > 1 and pad > 0, so every index-arithmetic
		// branch (skipped padding rows, partially clipped filter columns) is exercised;
		// im2col only copies elements, so this is exact by construction.
		String x = "(linalg:reshape (linalg:arange 96) '(2 3 4 4))";
		String xf = "(linalg:reshape (linalg:arange 0 96 1 :element-type 'single-float) '(2 3 4 4))";
		assertMatchesScalarOracle("(linalg::%la-im2col " + x + " 2 2 1 0)");
		assertMatchesScalarOracle("(linalg::%la-im2col " + x + " 3 3 2 1)");
		assertMatchesScalarOracle("(linalg::%la-im2col " + x + " 4 4 1 2)");
		assertMatchesScalarOracle("(linalg::%la-im2col " + xf + " 3 3 2 1)");
		assertThat(eval("(linalg::%la-im2col (linalg:reshape (linalg:arange 4) '(1 1 2 2)) 2 2 1 0)", true).print())
			.isEqualTo("#d((0.0 1.0 2.0 3.0))");
	}

	@Test
	void col2imMatchesTheScalarOracleAtBothWidths() {
		// Overlapping windows (stride < filter) accumulate; the float path narrows each
		// accumulation exactly as the defun's widen-add-narrow round trip does.
		String col = "(linalg::%la-im2col (linalg:reshape (linalg:arange 96) '(2 3 4 4)) 3 3 1 1)";
		String colF = "(linalg::%la-im2col (linalg:reshape (linalg:arange 0 96 1 :element-type 'single-float) '(2 3 4 4)) 3 3 1 1)";
		assertMatchesScalarOracle("(linalg::%la-col2im " + col + " '(2 3 4 4) 3 3 1 1)");
		assertMatchesScalarOracle("(linalg::%la-col2im " + colF + " '(2 3 4 4) 3 3 1 1)");
		assertMatchesScalarOracle(
				"(linalg::%la-col2im (linalg::%la-im2col (linalg:reshape (linalg:arange 32) '(2 1 4 4)) 2 2 2 0)"
						+ " '(2 1 4 4) 2 2 2 0)");
	}

	// --- the stacked matrix product: %la-matmul-nd ---------------------------------

	@Test
	void matmulNdIsInterceptedUnderSimd() {
		// The dead-flag guard for this member: a --simd run that silently fell back
		// would still pass every value test below.
		String form = "(linalg:zeros 1) #'linalg::%la-matmul-nd";
		assertThat(eval(form, true).print()).isEqualTo("#<function LINALG::%LA-MATMUL-ND>");
		assertThat(eval(form, false).print()).isEqualTo("#<lambda>");
	}

	@Test
	void matmulNdMatchesTheScalarOracleAtBothWidthsAndEveryBatchShape() {
		// Plain rank 3, a BROADCAST leading axis on either side (stride 0), a rank-2
		// operand against a rank-3 one, and rank 4 with two leading axes -- every shape
		// the %la-batch-strides odometer has to walk. Integer-valued operands, so the
		// f32 fold is exact and the oracle comparison is an equality.
		String a3 = "(linalg:reshape (linalg:arange 24) '(2 3 4))";
		String b3 = "(linalg:reshape (linalg:arange 32) '(2 4 4))";
		assertMatchesScalarOracle("(linalg:matmul " + a3 + " " + b3 + ")");
		assertMatchesScalarOracle("(linalg:matmul (linalg:reshape (linalg:arange 12) '(1 3 4)) " + b3 + ")");
		assertMatchesScalarOracle("(linalg:matmul " + a3 + " (linalg:reshape (linalg:arange 8) '(1 4 2)))");
		assertMatchesScalarOracle("(linalg:matmul " + b3 + " (linalg:reshape (linalg:arange 8) '(4 2)))");
		assertMatchesScalarOracle("(linalg:matmul (linalg:reshape (linalg:arange 8) '(2 4)) " + b3 + ")");
		assertMatchesScalarOracle("(linalg:matmul (linalg:reshape (linalg:arange 48) '(2 3 2 4))"
				+ " (linalg:reshape (linalg:arange 24) '(1 3 4 2)))");
		// Single width, and a p wide enough to cross the THRESHOLD = 128 lane gate.
		assertMatchesScalarOracle(
				"(linalg:matmul (linalg:reshape (linalg:arange 0 24 1 :element-type 'single-float) '(2 3 4))"
						+ " (linalg:reshape (linalg:arange 0 32 1 :element-type 'single-float) '(2 4 4)))");
		assertMatchesScalarOracle("(linalg:sum (linalg:matmul (linalg:reshape (linalg:arange 512) '(2 2 128))"
				+ " (linalg:reshape (linalg:arange 512) '(2 128 2))))");
	}

	@Test
	void matmulNdDeclinedInputsRunTheScalarDefun() {
		// A general boxed operand, a rank-1 side (the numpy promote-then-drop rule the
		// kernel leaves in the defun), and a mixed-width pair: all answer identically.
		assertMatchesScalarOracle("(linalg:matmul (make-array '(2 2 2) :initial-element 1) (linalg:zeros '(2 2)))");
		assertMatchesScalarOracle("(linalg:matmul (linalg:reshape (linalg:arange 24) '(2 3 4)) (linalg:arange 4))");
		assertMatchesScalarOracle("(linalg:matmul (linalg:arange 4) (linalg:reshape (linalg:arange 32) '(2 4 4)))");
		assertMatchesScalarOracle("(linalg:matmul (linalg:reshape (linalg:arange 24) '(2 3 4))"
				+ " (linalg:reshape (linalg:arange 0 32 1 :element-type 'single-float) '(2 4 4)))");
		// The library errors still come from the library, with its own messages.
		assertThatThrownBy(() -> eval("(linalg:matmul (linalg:reshape (linalg:arange 24) '(3 2 4))"
				+ " (linalg:reshape (linalg:arange 32) '(2 4 4)))", true))
			.hasMessageContaining("batch dimensions do not broadcast");
		assertThatThrownBy(() -> eval("(linalg:matmul (linalg:reshape (linalg:arange 24) '(2 3 4))"
				+ " (linalg:reshape (linalg:arange 24) '(2 3 4)))", true))
			.hasMessageContaining("inner dimensions differ");
	}

	// --- the fused optimizer update and the generator fill --------------------------

	/**
	 * A rule vector builder plus a driver that runs {@code n} Adam steps over four
	 * aligned arrays, with the bias corrections moving as they do in a real run.
	 */
	private static final String ADAM_PRELUDE = """
			(defun rule (mode it)
			  (let ((r (linalg::%la-make 11 0.0 nil)))
			    (setf (aref r 0) 0.01)
			    (setf (aref r 1) (* 0.01 0.1))
			    (setf (aref r 2) 0.1)
			    (setf (aref r 3) 0.9)
			    (setf (aref r 4) (- 1.0 0.9))
			    (setf (aref r 5) 0.999)
			    (setf (aref r 6) (- 1.0 0.999))
			    (setf (aref r 7) 1.0e-8)
			    (setf (aref r 8) (- 1.0 (expt 0.9 it)))
			    (setf (aref r 9) (- 1.0 (expt 0.999 it)))
			    (setf (aref r 10) mode)
			    r))
			(defun run (et mode steps)
			  (linalg:seed 11)
			  (let ((x (linalg:randn '(3 5) :element-type et))
			        (g (linalg:randn '(3 5) :element-type et))
			        (m (linalg:zeros '(3 5) :element-type et))
			        (v (linalg:zeros '(3 5) :element-type et)))
			    (do ((it 1 (+ it 1)))
			        ((> it steps))
			      (linalg::%la-adam-step x g m v (rule mode it)))
			    (list x m v)))
			""";

	@Test
	void theAdamStepAndTheGeneratorFillAreInterceptedUnderSimd() {
		// The dead-flag guard for todo-473's two members: a --simd run that silently
		// fell back would still pass every value assertion below.
		for (String member : new String[] { "%la-adam-step", "%la-rng-fill" }) {
			String form = "(linalg:zeros 1) #'linalg::" + member;
			assertThat(eval(form, true).print()).as(member)
				.isEqualTo("#<function LINALG::" + member.toUpperCase(java.util.Locale.ROOT) + ">");
			assertThat(eval(form, false).print()).as(member).isEqualTo("#<lambda>");
		}
	}

	@Test
	void theAdamStepIsBitIdenticalToTheScalarOracleAtBothWidthsAndEveryDecayMode() {
		// The kernel keeps the defun's order of operations and computes in double at
		// both widths, narrowing only on the store -- so this is byte identity, not a
		// tolerance. All three weight-decay modes: none, coupled (torch.optim.Adam) and
		// decoupled (torch.optim.AdamW).
		for (String et : new String[] { "nil", "'single-float" }) {
			for (String mode : new String[] { "0", "1", "2" }) {
				assertMatchesScalarOracle(ADAM_PRELUDE + "(run " + et + " " + mode + " 4)");
			}
		}
		// And through the optimizers themselves, which are its only real caller.
		assertMatchesScalarOracle("""
				(linalg:seed 3)
				(defparameter *w* (torch:tensor (linalg:randn '(4 5)) :requires-grad t))
				(defparameter *x* (torch:tensor (linalg:randn '(3 4))))
				(defparameter *o* (torch:adamw (list *w*) :lr 0.01 :weight-decay 0.1))
				(do ((i 0 (+ i 1)))
				    ((>= i 3))
				  (torch:zero-grad *o*)
				  (torch:backward (torch:sum (torch:matmul *x* *w*)))
				  (torch:step *o*))
				(torch:data *w*)
				""");
	}

	@Test
	void adamStepDeclinedInputsRunTheScalarDefun() {
		// A scalar parameter and a scalar gradient (the one branch the defun keeps for
		// itself), a general boxed array, a mixed-width quadruple, a length mismatch,
		// and a malformed rule vector: every one answers what the defun answers.
		String rule = ADAM_PRELUDE;
		assertMatchesScalarOracle(
				rule + "(linalg::%la-adam-step 0.5 0.25 (linalg:zeros 1) (linalg:zeros 1)" + " (rule 2 1))");
		assertMatchesScalarOracle(rule + "(linalg::%la-adam-step (linalg:ones 3) 0.25 (linalg:zeros 3)"
				+ " (linalg:zeros 3) (rule 1 1))");
		assertMatchesScalarOracle(rule + "(linalg::%la-adam-step (make-array 3 :initial-element 1.0)"
				+ " (linalg:ones 3) (linalg:zeros 3) (linalg:zeros 3) (rule 0 1))");
		assertMatchesScalarOracle(rule + "(linalg::%la-adam-step (linalg:ones 3 :element-type 'single-float)"
				+ " (linalg:ones 3) (linalg:zeros 3) (linalg:zeros 3) (rule 0 1))");
		// A rule vector of the wrong length, and a mode outside 0..2.
		assertMatchesScalarOracle(rule + "(linalg::%la-adam-step (linalg:ones 3) (linalg:ones 3) (linalg:zeros 3)"
				+ " (linalg:zeros 3) (rule 7 1))");
		assertThatThrownBy(() -> eval("(linalg::%la-adam-step (linalg:ones 3) (linalg:ones 3) (linalg:zeros 3)"
				+ " (linalg:zeros 3) (linalg:zeros 4))", true))
			.isInstanceOf(RuntimeException.class);
	}

	@Test
	void theSeededGeneratorIsBitIdenticalToTheScalarOracleAtBothWidths() {
		// linalg:seed promises that one seed reproduces one sequence on every backend,
		// so the fill kernel is byte identity or nothing -- every draw, both widths,
		// every rule, and the interleaving with the scalar draws that keep using the
		// specials (choice / permutation / %la-rng-next).
		assertMatchesScalarOracle("""
				(linalg:seed 42)
				(list (linalg:rand 5) (linalg:randn '(2 3)) (linalg:uniform -1 3 4)
				      (linalg:choice 10 5) (linalg:permutation 6)
				      (linalg:rand '(2 2) :element-type 'single-float)
				      (linalg:randn 3 :element-type 'single-float)
				      (linalg:uniform 0.5 1.5 3 :element-type 'single-float)
				      (linalg::%la-rng-next))
				""");
		// An empty fill draws nothing and must leave the state exactly where it was.
		assertMatchesScalarOracle("(linalg:seed 1) (list (linalg:rand 0) (linalg::%la-rng-next))");
		// A fill long enough to wrap each of the three moduli many times over. The
		// ARRAY is compared, not a reduction of it: linalg:sum is itself a lane
		// reduction under --simd and follows the reduction contract, not byte identity.
		assertMatchesScalarOracle("(linalg:seed 9) (linalg:randn 3000)");
	}

	@Test
	void generatorFillDeclinedInputsRunTheScalarDefun() {
		// A general boxed destination, a state vector of the wrong length, a state word
		// outside the generator's range, an out-of-range mode and a non-numeric bound:
		// each declines, and the defun answers (or signals) identically.
		assertMatchesScalarOracle("(linalg:seed 4) (linalg::%la-rng-fill (make-array 3 :initial-element 0.0)"
				+ " (linalg::%la-rng-state) 0 0.0 1.0)");
		assertMatchesScalarOracle("(linalg:seed 4) (linalg::%la-rng-fill (linalg:zeros 3) (linalg:zeros 4) 0 0.0 1.0)");
		assertMatchesScalarOracle("(linalg:seed 4) (let ((s (linalg::%la-rng-state)))"
				+ " (setf (aref s 0) -3.0) (linalg::%la-rng-fill (linalg:zeros 3) s 0 0.0 1.0))");
		assertMatchesScalarOracle("(linalg:seed 4) (let ((s (linalg::%la-rng-state)))"
				+ " (setf (aref s 1) 0.5) (linalg::%la-rng-fill (linalg:zeros 3) s 0 0.0 1.0))");
		assertMatchesScalarOracle(
				"(linalg:seed 4) (linalg::%la-rng-fill (linalg:zeros 3) (linalg::%la-rng-state) 5 0.0 1.0)");
		assertThatThrownBy(() -> eval(
				"(linalg:seed 4) (linalg::%la-rng-fill (linalg:zeros 3)" + " (linalg::%la-rng-state) 2 'a 1.0)", true))
			.isInstanceOf(RuntimeException.class);
	}

	/** The selects and copies of the 2026-08-22 member extension, shape by shape. */
	private static final String[] SELECT_AND_COPY_CASES = {
			// The comparison masks: both widths, a scalar on either side, equal dims and
			// a broadcast pair, and the two-number case the kernels decline.
			"(linalg:equal #d(1.0 2.0 0.0) 0)", "(linalg:greater #f(1.0 2.0 0.0) 0.5)",
			"(linalg:greater 0.5 #f(1.0 2.0 0.0))", "(linalg:greater-equal #d(1.0 2.0 0.0) #d(1.0 3.0 -1.0))",
			"(linalg:less #f((1.0 2.0) (3.0 4.0)) #f((2.0 2.0) (2.0 5.0)))",
			"(linalg:less-equal #d((1.0 2.0) (3.0 4.0)) #d(2.0 3.0))",
			"(linalg:greater #d((1.0 2.0) (3.0 4.0)) #d(2.0 3.0))", "(linalg:equal 1 2)",
			"(linalg:greater #d(0.0 -0.0 1.0) -0.0)", "(linalg:equal #f(0.0 -0.0) 0.0)",
			"(linalg:less-equal #d(1.0 2.0) #f(1.0 3.0))",
			// where: every operand mix -- array masks against arrays and scalars of both
			// widths, a scalar mask, three numbers (declined), a width-mixed pair.
			"(linalg:where #d((0.0 1.0) (1.0 0.0)) 9 #d((1.0 2.0) (3.0 4.0)))",
			"(linalg:where #d(0.0 1.0) #f((1.0 2.0) (3.0 4.0)) -1.5)", "(linalg:where 1 #d(1.0 2.0) #d(3.0 4.0))",
			"(linalg:where 0 #d(1.0 2.0) 5)", "(linalg:where #d((1.0) (0.0)) #d(1.0 2.0 3.0) #f(7.0 8.0 9.0))",
			"(linalg:where 2 3 4)", "(linalg:where #d(-0.0 1.0) #d(1.0 2.0) #d(3.0 4.0))",
			"(linalg:where #f((1.0 0.0)) #f((1.0 2.0) (3.0 4.0)) #d((5.0 6.0) (7.0 8.0)))",
			// take-rows, slice (the strided gather) and the scatter-add adjoint.
			"(linalg:take-rows #d((1.0 2.0) (3.0 4.0) (5.0 6.0)) #d(2.0 0.0 2.0))",
			"(linalg:take-rows #f((1.0 2.0) (3.0 4.0)) (linalg:zeros 0))",
			"(linalg:take-rows #f(((1.0 2.0) (3.0 4.0)) ((5.0 6.0) (7.0 8.0))) #d(1.0 1.0))",
			"(linalg:take-rows #d((1.0 2.0) (3.0 4.0)) #d(1.7 0.2))",
			"(linalg:slice #d((1.0 2.0 3.0) (4.0 5.0 6.0)) '(nil (2 0 -1)))",
			"(linalg:slice #f((1.0 2.0 3.0) (4.0 5.0 6.0)) '((1 2) (0 3 2)))",
			"(linalg:slice #d((1.0 2.0 3.0) (4.0 5.0 6.0)) '((0 2) (1 1)))",
			"(linalg:slice #d(((1.0 2.0) (3.0 4.0)) ((5.0 6.0) (7.0 8.0))) '((1 2) nil (-1 nil -1)))",
			"(linalg::%la-gather-strided #d((1.0 2.0 3.0) (4.0 5.0 6.0)) '(2 2) '(1 3) 1 0)",
			"(linalg::%la-gather-strided #f((1.0 2.0 3.0) (4.0 5.0 6.0)) '(3) '(2) 0 1)",
			"(linalg::%la-scatter-rows (linalg:zeros '(3 2)) #d((1.0 2.0) (3.0 4.0) (5.0 6.0)) #d(2.0 0.0 2.0))",
			"(linalg::%la-scatter-rows (linalg:zeros '(3 2) :element-type 'single-float)"
					+ " #f((1.0 2.0) (3.0 4.0) (5.0 6.0)) #d(2.0 0.0 2.0))",
			// The two halves of clip-grad-norm: the left fold from an accumulator (a
			// ratio accumulator declines to the defun's exact arithmetic) and the scale.
			"(linalg::%la-sum-squares #d(1.0 2.0 3.0) 0.5)", "(linalg::%la-sum-squares #f(0.1 0.2) 0.0)",
			"(linalg::%la-sum-squares #d(1.0 2.0) 1/3)", "(linalg::%la-scale #d(1.0 2.0 3.0) 0.5)",
			"(linalg::%la-scale #f(1.0 2.0 3.0) 0.1)", "(linalg::%la-scale #f(1.0 2.0 3.0) 2)",
			// Boxed operands decline to the defun everywhere.
			"(linalg:greater (make-array 3 :initial-element 1) 0)",
			"(linalg:where (make-array 2 :initial-element 1) #d(1.0 2.0) #d(3.0 4.0))",
			"(linalg:take-rows (make-array '(2 2) :initial-element 1) #d(1.0))",
			"(linalg::%la-sum-squares (make-array 2 :initial-element 2) 0.0)",
			// The causal mask torch:masked-fill builds, combined as the transformer
			// example combines it, and the masked fill itself at a transformer's shape.
			"(linalg:maximum (linalg:expand-dims (linalg:equal (linalg:from-list '(1 2 0 0)) 0) 1)"
					+ " (linalg:expand-dims (linalg:triu (linalg:ones '(4 4)) :k 1) 0))",
			"(linalg:where (linalg:expand-dims (linalg:triu (linalg:ones '(3 3)) :k 1) 0) -1.0e9"
					+ " (linalg:reshape (linalg:arange 0 18 :element-type 'single-float) '(2 3 3)))", };

	@Test
	void theSelectsAndCopiesAreInterceptedUnderSimd() {
		// The dead-flag guard for the 2026-08-22 members -- the comparison masks, where,
		// take-rows, the strided gather behind slice and broadcast-to, take-rows'
		// scatter-add adjoint and the two halves of torch:clip-grad-norm. A --simd run
		// that silently fell back would still pass every value assertion below.
		for (String member : new String[] { "greater", "greater-equal", "less", "less-equal", "equal", "where",
				"take-rows" }) {
			String form = "(linalg:zeros 1) #'linalg:" + member;
			assertThat(eval(form, true).print()).as(member)
				.isEqualTo("#<function LINALG:" + member.toUpperCase(java.util.Locale.ROOT) + ">");
			assertThat(eval(form, false).print()).as(member).isEqualTo("#<lambda>");
		}
		for (String member : new String[] { "%la-gather-strided", "%la-scatter-rows", "%la-sum-squares",
				"%la-scale" }) {
			String form = "(linalg:zeros 1) #'linalg::" + member;
			assertThat(eval(form, true).print()).as(member)
				.isEqualTo("#<function LINALG::" + member.toUpperCase(java.util.Locale.ROOT) + ">");
			assertThat(eval(form, false).print()).as(member).isEqualTo("#<lambda>");
		}
	}

	@Test
	void theSelectsAndCopiesAreBitIdenticalToTheScalarOracleAtEveryShapeAndWidth() {
		// Pure selects and copies: an IEEE compare, an `== 0` test, a widened add. Every
		// case is byte identity, the declines included (they run the defun).
		for (String form : SELECT_AND_COPY_CASES) {
			assertMatchesScalarOracle(form);
		}
		// And the callers that put them on the seam, at a small transformer's shapes:
		// torch:masked-fill, torch:index-select's backward, torch:cat's backward and
		// torch:clip-grad-norm, all through the same seeded weights.
		assertMatchesScalarOracle("""
				(linalg:seed 11)
				(defparameter *tab* (torch:tensor (linalg:randn '(6 4)) :requires-grad t))
				(defparameter *e* (torch:index-select *tab* #d(2.0 0.0 2.0 5.0)))
				(defparameter *w* (torch:tensor (linalg:randn '(4 4)) :requires-grad t))
				(defparameter *s* (torch:mul *e* *w*))
				(defparameter *m* (torch:masked-fill *s* (torch:subsequent-mask 4) -1.0e9))
				(defparameter *c* (torch:cat (list *m* (torch:slice *m* '((0 2)))) :axis 0))
				(torch:backward (torch:sum (torch:softmax *c* :axis -1)))
				(list (torch:data *c*) (torch:grad *tab*) (torch:grad *w*)
				      (torch:clip-grad-norm (list *tab* *w*) 0.01) (torch:grad *tab*) (torch:grad *w*))
				""");
	}

	@Test
	void theSelectsAndCopiesDeclineWhatTheDefunSignalsAndSignalItUnchanged() {
		// A slice whose walk would leave the array, an index outside the rows, a
		// scatter whose slab counts disagree: the kernels check up front, touch nothing,
		// and the defun then signals exactly what it always did.
		assertThatThrownBy(() -> eval("(linalg::%la-gather-strided #d(1.0 2.0 3.0) '(4) '(1) 0 1)", true))
			.isInstanceOf(RuntimeException.class);
		assertThatThrownBy(() -> eval("(linalg:take-rows #d((1.0) (2.0)) #d(2.0))", true))
			.isInstanceOf(RuntimeException.class);
		assertThatThrownBy(() -> eval("(linalg:take-rows #d((1.0) (2.0)) #d(-1.0))", true))
			.isInstanceOf(RuntimeException.class);
		assertThatThrownBy(
				() -> eval("(linalg::%la-scatter-rows (linalg:zeros '(2 2)) #d((1.0 2.0)) #d(0.0 1.0))", true))
			.isInstanceOf(RuntimeException.class);
		assertThatThrownBy(() -> eval("(linalg:where #d(1.0 0.0 1.0) #d(1.0 2.0) #d(3.0 4.0))", true))
			.isInstanceOf(RuntimeException.class);
	}

	@Test
	void im2colDeclinedInputsRunTheScalarDefun() {
		// A general boxed rank-4 array is not packed, so the kernel declines and the
		// defun answers (as a packed double result) -- identically on both paths.
		assertMatchesScalarOracle("(linalg::%la-im2col (make-array '(1 1 2 2) :initial-element 1) 2 2 1 0)");
		// A column matrix whose size does not match the dims declines; the defun then
		// signals its own subscript error on both paths.
		assertThatThrownBy(() -> eval("(linalg::%la-col2im (linalg:zeros '(2 4)) '(1 1 4 4) 2 2 1 0)", true))
			.isInstanceOf(RuntimeException.class);
	}

}
