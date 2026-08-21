package am.ik.rontolisp.eval;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import am.ik.rontolisp.LispDoubleFloatArray;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSingleFloatArray;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * The interpreter's opt-in {@code --gpu} acceleration of the {@code linalg:} matrix
 * product ({@link LinalgGpu}) -- {@code linalg:dot}'s M.M case and the STACKED
 * {@code linalg::%la-matmul-nd} behind {@code linalg:matmul} at rank &gt;= 3 -- the
 * sibling of {@link LinalgBlasTest}.
 *
 * <p>
 * Whether a device exists is a property of the MACHINE, not of the build, so the whole
 * class is conditional and skips on every CI runner this project has -- which is the same
 * answer the flag itself gives there. Nothing here may require a GPU;
 * {@link LinalgGpuDeclineTest} covers what every machine must do.
 *
 * <p>
 * Four things are checked. (1) The interception actually FIRES -- without the
 * {@code #<function linalg:dot>} guard the flag could be silently dead and every numeric
 * assertion in the file would still pass on the scalar defun
 * ([[simd-shadow-and-dead-flag-lesson]]). (2) On inputs exact at the operand width the
 * device product equals the scalar oracle EXACTLY, and on inexact ones it agrees only to
 * a relative tolerance -- the tiled reduction is the whole of the difference, and stating
 * it is the precision contract. (3) Everything the kernel declines behaves exactly as it
 * does without the flag. (4) The CHAIN: with {@code --blas} and {@code --simd} on too the
 * device is asked first, and what it declines lands on the best CPU path enabled rather
 * than on the scalar defun.
 */
@EnabledIf("am.ik.rontolisp.eval.LinalgGpuTest#gpuIsAvailable")
class LinalgGpuTest {

	static boolean gpuIsAvailable() {
		return LinalgGpu.available();
	}

	private LispVal eval(String input, boolean gpu, boolean blas, boolean simd) {
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(new ByteArrayOutputStream()));
		evaluator.setSimd(simd);
		evaluator.setBlas(blas);
		evaluator.setGpu(gpu);
		LispVal result = LispNil.INSTANCE;
		for (LispVal expr : LispReader.readAllFromString(input)) {
			result = evaluator.eval(expr);
		}
		return result;
	}

	private LispVal eval(String input, boolean gpu) {
		return eval(input, gpu, false, false);
	}

	/**
	 * Asserts that the accelerated result prints exactly what the scalar defun's does.
	 */
	private void assertMatchesScalarOracle(String input) {
		assertThat(eval(input, true).print()).as(input).isEqualTo(eval(input, false).print());
	}

	private static double[] elements(LispVal value) {
		if (value instanceof LispDoubleFloatArray a) {
			return a.data();
		}
		float[] f = ((LispSingleFloatArray) value).data();
		double[] widened = new double[f.length];
		for (int i = 0; i < f.length; i++) {
			widened[i] = f[i];
		}
		return widened;
	}

	/**
	 * The divergence between two products, as a fraction of the largest cell of the
	 * reference -- the same normalization {@code .kb/gpu.md}'s precision table uses. A
	 * PER-CELL relative error is meaningless here: the worst cell is always one whose
	 * true value cancelled to near zero, exactly as it is for the {@code --simd} f32
	 * product.
	 */
	private static double divergence(double[] accelerated, double[] reference) {
		double worst = 0, scale = 0;
		for (int i = 0; i < reference.length; i++) {
			scale = Math.max(scale, Math.abs(reference[i]));
			worst = Math.max(worst, Math.abs(accelerated[i] - reference[i]));
		}
		return worst / scale;
	}

	/**
	 * An {@code n x n} product of inexact, zero-mean operands, comfortably over the size
	 * threshold at every {@code n} used here. Dyadic test data round-trips exactly and
	 * would hide the whole question, so the operands are a sine and a cosine of an index
	 * ramp.
	 */
	private static String inexactProduct(int n, String elementType) {
		String option = elementType.isEmpty() ? "" : " :element-type '" + elementType;
		return """
				(defparameter *a* (linalg:reshape (linalg:sin (linalg:arange 0 %d%s)) '(%d %d)))
				(defparameter *b* (linalg:reshape (linalg:cos (linalg:arange 0 %d%s)) '(%d %d)))
				(linalg:matmul *a* *b*)
				""".formatted(n * n, option, n, n, n * n, option, n, n);
	}

	private static String inexactProduct(String elementType) {
		return inexactProduct(64, elementType);
	}

	// --- the dead-flag guard ---------------------------------------------------------

	@Test
	void gpuReplacesTheProductDefunWithANativeFunctionAndTouchesNothingElse() {
		// A linalg.lisp defun is a LispLambda ("#<lambda>"); the installed interceptor is
		// a native LispFunction. This is the only assertion in the file that fails if the
		// flag never reaches the interceptor -- every numeric assertion below would pass
		// just as well on a dead flag.
		assertThat(eval("(linalg:zeros 1) #'linalg:dot", true).print()).isEqualTo("#<function LINALG:DOT>");
		assertThat(eval("(linalg:zeros 1) #'linalg:dot", false).print()).isEqualTo("#<lambda>");
		// And the stacked member, whose qualified spelling carries the double colon.
		assertThat(eval("(linalg:zeros 1) #'linalg::%la-matmul-nd", true).print())
			.isEqualTo("#<function LINALG::%LA-MATMUL-ND>");
		assertThat(eval("(linalg:zeros 1) #'linalg::%la-matmul-nd", false).print()).isEqualTo("#<lambda>");
		// And every element-wise member the device takes.
		for (String member : new String[] { "exp", "log", "tanh", "sin", "cos", "tan", "asin", "acos", "atan", "sinh",
				"cosh", "erf" }) {
			assertThat(eval("(linalg:zeros 1) #'linalg:" + member, true).print()).as(member)
				.isEqualTo("#<function LINALG:" + member.toUpperCase() + ">");
			assertThat(eval("(linalg:zeros 1) #'linalg:" + member, false).print()).as(member).isEqualTo("#<lambda>");
		}
		// Fourteen members and no others. matmul, square, relu and the exact torch:gelu
		// are accelerated THROUGH them, not instead of them; and sqrt / abs / negative /
		// sign are the element-wise tier's DECLINED half -- one machine instruction per
		// element, which a round trip cannot pay for -- so they must still be the
		// library's own lambdas under the flag.
		for (String member : new String[] { "matmul", "add", "sub", "mul", "div", "sum", "outer", "transpose", "sqrt",
				"abs", "negative", "sign", "maximum", "minimum" }) {
			assertThat(eval("(linalg:zeros 1) #'linalg:" + member, true).print()).as(member).isEqualTo("#<lambda>");
		}
	}

	// --- the accelerated shape -------------------------------------------------------

	@Test
	void theMatrixProductMatchesTheScalarOracleOnExactInputs() {
		// Every partial sum here is an integer well under 2^24, so it is exact at BOTH
		// widths and a reordered reduction of it is not merely close to the oracle's, it
		// is equal.
		assertMatchesScalarOracle("""
				(defparameter *a* (linalg:add (linalg:ones '(64 64)) 2.0))
				(defparameter *b* (linalg:reshape (linalg:arange 1 4097) '(64 64)))
				(linalg:to-list (linalg:matmul *a* *b*))
				""");
		// A rectangular shape that is not a multiple of the kernel's 16x16 tile, so a
		// mis-indexed edge tile would show up.
		assertMatchesScalarOracle("""
				(defparameter *a* (linalg:add (linalg:ones '(60 70)) 1.0))
				(defparameter *b* (linalg:reshape (linalg:arange 1 3501) '(70 50)))
				(linalg:to-list (linalg:matmul *a* *b*))
				""");
	}

	@Test
	void theSingleFloatProductMatchesTheScalarOracleOnExactInputs() {
		assertMatchesScalarOracle("""
				(defparameter *a* (linalg:add (linalg:ones '(64 64) :element-type 'single-float) 2.0))
				(defparameter *b*
				  (linalg:reshape (linalg:arange 1 4097 :element-type 'single-float) '(64 64)))
				(linalg:to-list (linalg:matmul *a* *b*))
				""");
	}

	@Test
	void anInexactProductAgreesWithTheOracleOnlyToARelativeTolerance() {
		// The precision contract, and the one place --gpu differs from --simd rather than
		// merely being faster than it. The kernel folds k in the defun's own ascending
		// order, but every multiply-add in it is FUSED, so each term is rounded once
		// where the defun rounds twice: at #d -- where --simd stays bit-identical to the
		// defun -- the device does NOT. Measured on an NVIDIA GB10, worst absolute
		// difference from the scalar defun at the same width: 2.2e-15 to 4.9e-15 at #d
		// and 8.0e-7 to 2.6e-6 at #f, flat from n=64 to n=512 (the #f figure is simply
		// what f32 costs -- the same product accumulated in f32 on a CPU lands the same
		// distance from an f64 oracle). The tolerances below are the n=64 figures with
		// room, because the exact number is the device's and not ours.
		double[] doubles = elements(eval(inexactProduct(""), true));
		double[] doubleOracle = elements(eval(inexactProduct(""), false));
		assertThat(divergence(doubles, doubleOracle)).isLessThan(1e-13).isGreaterThan(0);
		double[] singles = elements(eval(inexactProduct("single-float"), true));
		double[] singleOracle = elements(eval(inexactProduct("single-float"), false));
		assertThat(divergence(singles, singleOracle)).isLessThan(1e-5).isGreaterThan(0);
	}

	@Test
	void theStackedProductMatchesTheScalarOracleAtEveryBatchShape() {
		// The shape this member exists for, and every shape the batch odometer can hand
		// the device: a plain rank-3 stack, a BROADCAST right operand (the rank-2 matrix
		// under a rank-3 activation, which is every torch:linear), a broadcast LEFT one,
		// and rank 4 with two leading axes. Integer-valued operands whose partial sums
		// stay under 2^24, so the fold is exact at both widths and this is an equality.
		assertMatchesScalarOracle("""
				(defparameter *a* (linalg:reshape (linalg:arange 1 8193) '(2 64 64)))
				(defparameter *b* (linalg:add (linalg:ones '(2 64 64)) 2.0))
				(linalg:to-list (linalg:flatten (linalg:matmul *a* *b*)))
				""");
		assertMatchesScalarOracle("""
				(defparameter *a* (linalg:reshape (linalg:arange 1 8193) '(2 64 64)))
				(defparameter *b* (linalg:add (linalg:ones '(64 64)) 2.0))
				(linalg:to-list (linalg:flatten (linalg:matmul *a* *b*)))
				""");
		assertMatchesScalarOracle("""
				(defparameter *a* (linalg:add (linalg:ones '(1 64 64)) 2.0))
				(defparameter *b* (linalg:reshape (linalg:arange 1 8193) '(2 64 64)))
				(linalg:to-list (linalg:flatten (linalg:matmul *a* *b*)))
				""");
		assertMatchesScalarOracle("""
				(defparameter *a* (linalg:reshape (linalg:arange 1 12289) '(2 3 32 64)))
				(defparameter *b* (linalg:add (linalg:ones '(2 3 64 32)) 1.0))
				(linalg:to-list (linalg:flatten (linalg:matmul *a* *b*)))
				""");
		// Single-float, and a rectangular slab that is not a multiple of the 16x16 tile.
		assertMatchesScalarOracle("""
				(defparameter *a*
				  (linalg:reshape (linalg:arange 1 8401 :element-type 'single-float) '(4 60 35)))
				(defparameter *b*
				  (linalg:add (linalg:ones '(4 35 50) :element-type 'single-float) 2.0))
				(linalg:to-list (linalg:flatten (linalg:matmul *a* *b*)))
				""");
	}

	@Test
	void aStackedCellIsAPerBatchDeviceProductAndNotTheDefunsFold() {
		// The precision contract of this member, stated the way the --simd one is: not
		// "identical to the defun" but "identical to a per-batch linalg:dot ON THE
		// DEVICE". So an inexact stack must equal the device's own rank-2 answer for the
		// same slab, bit for bit, while differing from the scalar oracle.
		String slab = """
				(defparameter *a* (linalg:reshape (linalg:sin (linalg:arange 0 4096)) '(64 64)))
				(defparameter *b* (linalg:reshape (linalg:cos (linalg:arange 0 4096)) '(64 64)))
				""";
		double[] flat = elements(eval(slab + "(linalg:matmul *a* *b*)", true));
		double[] stacked = elements(eval(slab + """
				(linalg:matmul (linalg:reshape *a* '(1 64 64)) (linalg:reshape *b* '(1 64 64)))
				""", true));
		assertThat(stacked).isEqualTo(flat);
		double[] oracle = elements(eval(slab + "(linalg:matmul *a* *b*)", false));
		assertThat(divergence(stacked, oracle)).isLessThan(1e-13).isGreaterThan(0);
	}

	// --- the element-wise tier -------------------------------------------------------

	/** The twelve members the device takes, with a domain each is defined on. */
	private static String[][] elementWiseMembers() {
		return new String[][] { { "exp", "-5.0", "5.0" }, { "log", "0.01", "100.0" }, { "tanh", "-5.0", "5.0" },
				{ "sin", "-5.0", "5.0" }, { "cos", "-5.0", "5.0" }, { "tan", "-1.4", "1.4" },
				{ "asin", "-0.999", "0.999" }, { "acos", "-0.999", "0.999" }, { "atan", "-5.0", "5.0" },
				{ "sinh", "-5.0", "5.0" }, { "cosh", "-5.0", "5.0" }, { "erf", "-3.0", "3.0" } };
	}

	/** The worst PER-ELEMENT relative difference. */
	private static double worstRelative(double[] accelerated, double[] reference) {
		double worst = 0;
		for (int i = 0; i < reference.length; i++) {
			if (reference[i] != 0) {
				worst = Math.max(worst, Math.abs(accelerated[i] - reference[i]) / Math.abs(reference[i]));
			}
		}
		return worst;
	}

	@Test
	void everyElementWiseMemberAgreesWithTheOracleOnlyToARelativeTolerance() {
		// The NEW half of the precision contract, and the sharpest break --gpu makes with
		// the other backends: an accelerated transcendental is not the scalar defun's
		// answer rounded differently in one place, it is a DIFFERENT libm. Measured on an
		// NVIDIA GB10 over 400 samples per member: worst 2.0e-16 to 1.0e-15 relative at
		// #d (~1 ulp, except erf's ~4.5 -- its CPU oracle is an A&S series rather than a
		// correctly-rounded erf) and 1.1e-7 to 1.7e-7 at #f, where the device evaluates
		// AT the operand width while the defun evaluates in double and narrows. The
		// spike's feared 4.87e-5 on tanh does not reproduce anywhere here.
		for (String[] member : elementWiseMembers()) {
			String program = """
					(defparameter *a* (linalg:linspace %s %s 20000%s))
					(linalg:%s *a*)
					""";
			String doubles = program.formatted(member[1], member[2], "", member[0]);
			assertThat(worstRelative(elements(eval(doubles, true)), elements(eval(doubles, false))))
				.as("#d linalg:%s", member[0])
				.isLessThan(1e-12);
			String singles = program.formatted(member[1], member[2], " :element-type 'single-float", member[0]);
			assertThat(worstRelative(elements(eval(singles, true)), elements(eval(singles, false))))
				.as("#f linalg:%s", member[0])
				.isLessThan(1e-5);
		}
	}

	@Test
	void anAcceleratedElementWiseCallReallyRanOnTheDevice() {
		// The tolerance above would pass on a dead flag, so this is its guard: over
		// 20000 inexact elements the device and the CPU cannot land on the same bits for
		// every one of them, and the difference must appear at BOTH widths.
		String program = """
				(defparameter *a* (linalg:linspace -5.0 5.0 20000%s))
				(linalg:erf *a*)
				""";
		assertThat(elements(eval(program.formatted(""), true)))
			.isNotEqualTo(elements(eval(program.formatted(""), false)));
		assertThat(elements(eval(program.formatted(" :element-type 'single-float"), true)))
			.isNotEqualTo(elements(eval(program.formatted(" :element-type 'single-float"), false)));
	}

	@Test
	void theExactGeluIsAcceleratedThroughErf() {
		// torch:gelu's default (:approximate :none) is built on linalg:erf, which is the
		// member the CPU is slowest at by an order of magnitude -- so the tier's whole
		// case is that this call moves. It is reached transitively, exactly as
		// linalg:matmul reaches the product.
		String program = """
				(defparameter *x* (torch:tensor (linalg:linspace -3.0 3.0 20000)))
				(linalg:sum (torch:data (torch:gelu *x*)))
				""";
		double accelerated = ((am.ik.rontolisp.LispDouble) eval(program, true)).value();
		double oracle = ((am.ik.rontolisp.LispDouble) eval(program, false)).value();
		// A SUM of 20000 device-computed elements, so the tolerance is looser than the
		// per-element one above by about the count: measured 2.0e-9 relative here.
		assertThat(accelerated).isNotEqualTo(oracle).isCloseTo(oracle, within(1e-7 * Math.abs(oracle)));
	}

	@Test
	void anElementWiseCallBelowTheThresholdIsByteIdenticalToTheOracle() {
		// The threshold is one more decline, so everything under it is untouched -- and
		// that is what keeps a program whose arrays are small byte-identical with the
		// flag on. 16383 elements is one short of it.
		assertMatchesScalarOracle("""
				(defparameter *a* (linalg:linspace -3.0 3.0 16383))
				(list (linalg:sum (linalg:erf *a*)) (linalg:sum (linalg:exp *a*))
				      (linalg:sum (linalg:tanh *a*)))
				""");
		assertMatchesScalarOracle("""
				(defparameter *a* (linalg:linspace -3.0 3.0 16383 :element-type 'single-float))
				(linalg:to-list (linalg:erf *a*))
				""");
	}

	@Test
	void theDeclinedHalfOfTheTierIsByteIdenticalAtAnySize() {
		// sqrt / abs / negative / sign and the binary add / sub / mul / div are NOT
		// offered -- measured, the device wins them by 1.4-2x at best and loses them
		// outright at #f -- so they must stay bit-identical however large the array is.
		// This is the assertion that fails if someone widens the member set without
		// measuring it.
		assertMatchesScalarOracle("""
				(defparameter *a* (linalg:linspace 0.01 9.0 1000000))
				(defparameter *b* (linalg:linspace 0.02 3.0 1000000))
				(list (linalg:sum (linalg:sqrt *a*)) (linalg:sum (linalg:abs *a*))
				      (linalg:sum (linalg:negative *a*)) (linalg:sum (linalg:sign *a*))
				      (linalg:sum (linalg:add *a* *b*)) (linalg:sum (linalg:mul *a* *b*))
				      (linalg:sum (linalg:div *a* *b*)) (linalg:sum (linalg:sub *a* *b*)))
				""");
	}

	@Test
	void aDeclinedElementWiseOperandRunsTheScalarDefunUnchanged() {
		// A general (boxed) array and a plain number: the defun's own dispatch, above
		// the threshold as well as below it.
		assertMatchesScalarOracle("(linalg:to-list (linalg:exp #(1 2 3)))");
		assertMatchesScalarOracle("(linalg:to-list (linalg:erf #(0.5 1.5)))");
	}

	@Test
	void anElementWiseCallComposesWithTheOtherFlagsWithoutChangingItsAnswer() {
		// --blas has no rung in the element-wise chain (it takes only linalg:dot), and
		// the --simd lane kernel is bit-identical to the defun for these members, so
		// there is no legible fallback probe of the kind the product has. What CAN be
		// pinned is the composition: whichever CPU flags are also on, the answer is the
		// DEVICE's, and it is the same one every time.
		String program = """
				(defparameter *a* (linalg:linspace -3.0 3.0 20000))
				(linalg:to-list (linalg:erf *a*))
				""";
		String device = eval(program, true, false, false).print();
		assertThat(eval(program, true, false, true).print()).isEqualTo(device);
		if (LinalgBlas.available()) {
			assertThat(eval(program, true, true, true).print()).isEqualTo(device);
		}
		assertThat(eval(program, false, false, true).print()).isNotEqualTo(device);
	}

	// --- what it declines ------------------------------------------------------------

	@Test
	void declinedOperandsRunTheScalarDefunUnchanged() {
		// A general (boxed) array, on either side.
		assertMatchesScalarOracle("(linalg:matmul #2A((1 2) (3 4)) #2A((5 6) (7 8)))");
		assertMatchesScalarOracle("(linalg:dot #2A((1 2) (3 4)) #d((5.0 6.0) (7.0 8.0)))");
		// Mixed widths: the defun widens both and keeps the first operand's width.
		assertMatchesScalarOracle("""
				(defparameter *d* (linalg:reshape (linalg:arange 1 4097) '(64 64)))
				(defparameter *f*
				  (linalg:reshape (linalg:arange 1 4097 :element-type 'single-float) '(64 64)))
				(linalg:to-list (linalg:matmul *d* *f*))
				""");
		// A scalar operand, which the defun routes to linalg:mul.
		assertMatchesScalarOracle("(linalg:dot #d((1.0 2.0) (3.0 4.0)) 3.0)");
		// The two gemv shapes. Unlike --blas these are NOT offered: a matrix-by-vector
		// product is memory-bound, so its whole cost is one pass over an operand the
		// device would have to be handed anyway.
		assertMatchesScalarOracle("""
				(defparameter *a* (linalg:reshape (linalg:arange 1 4097) '(64 64)))
				(linalg:to-list (linalg:dot *a* (linalg:arange 1 65)))
				""");
		assertMatchesScalarOracle("""
				(defparameter *a* (linalg:reshape (linalg:arange 1 4097) '(64 64)))
				(linalg:to-list (linalg:dot (linalg:arange 1 65) *a*))
				""");
		// A vector-by-vector dot.
		assertMatchesScalarOracle("(linalg:dot (linalg:arange 1 100) (linalg:arange 1 100))");
		// A rank-3 stack whose TOTAL work is under the threshold: %la-matmul-nd is
		// intercepted, but 2 x 4x4x4 is 128 multiply-adds and declines like any other
		// small product.
		assertMatchesScalarOracle("""
				(defparameter *a* (linalg:reshape (linalg:arange 1 33) '(2 4 4)))
				(linalg:matmul *a* *a*)
				""");
		// A rank-1 operand on either side of the stacked member: the numpy
		// promote-then-drop-the-axis rule stays in the defun.
		assertMatchesScalarOracle("""
				(defparameter *a* (linalg:reshape (linalg:arange 1 8193) '(2 64 64)))
				(linalg:to-list (linalg:flatten (linalg:matmul *a* (linalg:arange 1 65))))
				""");
		// A batch shape whose offsets are not AFFINE in the batch index -- a broadcast
		// axis UNDER a non-broadcast one. Above the threshold, and still the CPU's.
		assertMatchesScalarOracle("""
				(defparameter *a* (linalg:reshape (linalg:arange 1 3201) '(2 1 40 40)))
				(defparameter *b* (linalg:reshape (linalg:arange 1 9601) '(2 3 40 40)))
				(linalg:sum (linalg:matmul *a* *b*))
				""");
		// A non-broadcastable batch shape and a mismatched inner dimension are the
		// defun's errors to signal, at rank 3 as at rank 2.
		assertThatThrownBy(() -> eval("""
				(linalg:matmul (linalg:reshape (linalg:arange 1 8193) '(2 64 64))
				               (linalg:reshape (linalg:arange 1 12289) '(3 64 64)))
				""", true)).hasMessageContaining("matmul");
		// Below the size threshold, where a round trip cannot pay for itself. This is the
		// shape every example in the repository actually runs.
		assertMatchesScalarOracle("(linalg:matmul #d((1.0 2.0) (3.0 4.0)) #d((5.0 6.0) (7.0 8.0)))");
		assertMatchesScalarOracle("""
				(defparameter *a* (linalg:sin (linalg:reshape (linalg:arange 0 1024) '(32 32))))
				(linalg:to-list (linalg:matmul *a* *a*))
				""");
	}

	@Test
	void aShapeMismatchStillSignalsTheDefunsOwnError() {
		assertThatThrownBy(() -> eval("""
				(linalg:matmul (linalg:reshape (linalg:arange 1 4097) '(64 64))
				               (linalg:reshape (linalg:arange 1 4097) '(32 128)))
				""", true)).hasMessageContaining("matmul inner dimensions differ");
	}

	// --- the chain -------------------------------------------------------------------

	@Test
	void theDeviceIsAskedAheadOfATunedBlas() {
		// LinalgGpu.install runs LAST, so the device is the OUTERMOST layer: with both
		// flags on an accepted product must be the device's answer, bit for bit, not the
		// library's.
		assumeThat(LinalgBlas.available()).as("a tuned CBLAS on this machine").isTrue();
		// n=192 rather than 64, and that is not arbitrary: both the device kernel and a
		// tuned library fold with fused multiply-adds, and up to n~128 OpenBLAS on this
		// machine lands on the device's bits EXACTLY, which would make the assertion
		// below a tautology instead of an order pin. From n=192 the library blocks its k
		// loop and the two answers separate.
		String product = inexactProduct(192, "");
		double[] gpuOnly = elements(eval(product, true, false, false));
		double[] blasOnly = elements(eval(product, false, true, false));
		assumeThat(blasOnly).as("the library and the device round differently").isNotEqualTo(gpuOnly);
		assertThat(elements(eval(product, true, true, false))).isEqualTo(gpuOnly);
	}

	@Test
	void theDeviceIsAskedAheadOfTheLaneKernel() {
		double[] gpuOnly = elements(eval(inexactProduct(""), true, false, false));
		double[] simdOnly = elements(eval(inexactProduct(""), false, false, true));
		// At #d the lane kernel is bit-identical to the scalar defun and the device is
		// not, so these two differ by construction.
		assertThat(simdOnly).isNotEqualTo(gpuOnly);
		assertThat(elements(eval(inexactProduct(""), true, false, true))).isEqualTo(gpuOnly);
		assertThat(elements(eval(inexactProduct(""), true, true, true))).isEqualTo(gpuOnly);
	}

	@Test
	void whatTheDeviceDeclinesFallsOnTheBestCpuPathEnabledAndNotOnTheDefun() {
		// The rule that makes the flags compose: a declined product must land on the
		// --simd kernel when that flag is on, never back on the scalar defun. The probe
		// is .kb/linalg-simd.md's own: a v.M product (which --gpu never offers) whose f32
		// lane accumulator swallows every 1.0 added to 2^24, so the scalar defun and the
		// lane kernel print different integers and the fallback target is legible.
		String probe = """
				(let ((v (linalg:ones 1024 :element-type 'single-float)))
				  (setf (aref v 0) 4096.0)
				  (round (aref (linalg:dot v (linalg:reshape v '(1024 1))) 0)))
				""";
		assertThat(eval(probe, false, false, false).print()).isEqualTo("16778240");
		assertThat(eval(probe, false, false, true).print()).isEqualTo("16777216");
		// --gpu alone declines it to the defun; --gpu --simd declines it to the lanes.
		assertThat(eval(probe, true, false, false).print()).isEqualTo("16778240");
		assertThat(eval(probe, true, false, true).print()).isEqualTo("16777216");
		assertThat(eval(probe, true, true, true).print()).isEqualTo("16777216");
	}

	@Test
	void aDeclinedStackFallsOnTheLaneKernelWhenSimdIsOnAndOnTheDefunOtherwise() {
		// --blas does NOT take this member, so for a stacked product the chain is
		// device -> lane kernel -> defun, with no library rung. The probe is the rank-3
		// line of .kb/linalg-simd.md's f32 fold: the lane kernel accumulates in single
		// precision and prints 16777216, the boxed defun widens and prints 16778240, so
		// the fallback target is legible. The shape is well under the size threshold, so
		// the device declines it however the flags are set.
		String probe = """
				(let ((v (linalg:ones 1024 :element-type 'single-float)))
				  (setf (aref v 0) 4096.0)
				  (round (row-major-aref
				          (linalg:matmul (linalg:reshape v '(1 1 1024)) (linalg:reshape v '(1 1024 1))) 0)))
				""";
		assertThat(eval(probe, false, false, false).print()).isEqualTo("16778240");
		assertThat(eval(probe, true, false, false).print()).isEqualTo("16778240");
		assertThat(eval(probe, true, true, false).print()).isEqualTo("16778240");
		assertThat(eval(probe, true, false, true).print()).isEqualTo("16777216");
		assertThat(eval(probe, true, true, true).print()).isEqualTo("16777216");
	}

	@Test
	void theDeviceIsAskedAheadOfTheLaneKernelForTheStackedProductToo() {
		String stack = """
				(defparameter *a* (linalg:reshape (linalg:sin (linalg:arange 0 8192)) '(2 64 64)))
				(defparameter *b* (linalg:reshape (linalg:cos (linalg:arange 0 8192)) '(2 64 64)))
				(linalg:matmul *a* *b*)
				""";
		double[] gpuOnly = elements(eval(stack, true, false, false));
		double[] simdOnly = elements(eval(stack, false, false, true));
		// At #d the lane kernel is bit-identical to the scalar defun and the device
		// fuses, so these differ by construction.
		assertThat(simdOnly).isNotEqualTo(gpuOnly);
		assertThat(elements(eval(stack, true, false, true))).isEqualTo(gpuOnly);
		assertThat(elements(eval(stack, true, true, true))).isEqualTo(gpuOnly);
	}

	@Test
	void everyCombinationOfTheThreeFlagsRunsAnExactProgramToTheSameOutput() {
		// Eight invocations, one program, one answer: the composition is only worth
		// having if it cannot change what an exact program prints.
		String program = """
				(defparameter *a* (linalg:add (linalg:ones '(64 64)) 2.0))
				(defparameter *b* (linalg:reshape (linalg:arange 1 4097) '(64 64)))
				(defparameter *s* (linalg:reshape (linalg:arange 1 8193) '(2 64 64)))
				(list (linalg:to-list (linalg:matmul *a* *b*))
				      (linalg:to-list (linalg:dot *a* (linalg:arange 1 65)))
				      (linalg:sum (linalg:matmul *b* *a*))
				      (linalg:to-list (linalg:flatten (linalg:matmul *s* *a*)))
				      (linalg:to-list (linalg:flatten (linalg:matmul *s* (linalg:reshape *s* '(2 64 64))))))
				""";
		String oracle = eval(program, false, false, false).print();
		for (boolean gpu : new boolean[] { false, true }) {
			for (boolean blas : new boolean[] { false, true }) {
				for (boolean simd : new boolean[] { false, true }) {
					if (blas && !LinalgBlas.available()) {
						continue;
					}
					assertThat(eval(program, gpu, blas, simd).print())
						.as("--gpu=%s --blas=%s --simd=%s", gpu, blas, simd)
						.isEqualTo(oracle);
				}
			}
		}
	}

}
