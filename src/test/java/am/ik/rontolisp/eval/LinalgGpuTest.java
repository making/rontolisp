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

	/**
	 * Whether this machine's device has a {@code double} at all. It does on CUDA and does
	 * NOT on Metal, where MSL rejects the type outright -- so every {@code #d} assertion
	 * below that needs the device to ACCEPT is written at {@link #TYPE} instead, and the
	 * ones that need it to DECLINE hold on both.
	 */
	private static final boolean DOUBLES = am.ik.gpu.GpuThresholds.supportsDouble();

	/** The element type an ACCEPTED call has to be written at on this machine. */
	private static final String TYPE = DOUBLES ? "" : "single-float";

	/**
	 * The side of the smallest square product this machine will actually accept, with a
	 * safety factor so nothing sits on the threshold. 64 on CUDA and 208 on Metal, whose
	 * floor is five times higher; a hard-coded 64 would make every accepted-product
	 * assertion below vacuous on the second one.
	 */
	private static final int SIDE = squareSide();

	/** Comfortably above the element-wise threshold in force: 32768 or 262144. */
	private static final int MAP_N = (int) am.ik.gpu.GpuThresholds.mapMinElements() * 2;

	private static int squareSide() {
		int n = (int) Math.ceil(Math.cbrt((double) am.ik.gpu.GpuThresholds.minWork()));
		return Math.max(64, (n + n / 4 + 15) / 16 * 16);
	}

	/**
	 * {@code :element-type} for {@link #TYPE}, or nothing when the width is the default.
	 */
	private static String option() {
		return TYPE.isEmpty() ? "" : " :element-type '" + TYPE;
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
		return inexactProduct(SIDE, elementType);
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
		// And its two TRANSPOSED siblings, the shape both matmul adjoints have.
		for (String member : new String[] { "%la-matmul-nd-ta", "%la-matmul-nd-tb" }) {
			assertThat(eval("(linalg:zeros 1) #'linalg::" + member, true).print()).as(member)
				.isEqualTo("#<function LINALG::" + member.toUpperCase() + ">");
			assertThat(eval("(linalg:zeros 1) #'linalg::" + member, false).print()).as(member).isEqualTo("#<lambda>");
		}
		// And every element-wise member the device takes.
		for (String member : new String[] { "exp", "log", "tanh", "sin", "cos", "tan", "asin", "acos", "atan", "sinh",
				"cosh", "erf" }) {
			assertThat(eval("(linalg:zeros 1) #'linalg:" + member, true).print()).as(member)
				.isEqualTo("#<function LINALG:" + member.toUpperCase() + ">");
			assertThat(eval("(linalg:zeros 1) #'linalg:" + member, false).print()).as(member).isEqualTo("#<lambda>");
		}
		// And every member of the STRIDED tier: the six binary ops (the override is
		// installed unconditionally, the SHAPE and the residency are what the kernel
		// declines on), the three axis folds and the axes transpose -- and the RESIDENT
		// tier (.todo/491): sqrt / abs / negative / sign, the five comparison masks,
		// where
		// and the Adam update, members over a resident operand only -- plus the INDEX
		// tier and the clip norm's sum of squares, resident-only as well.
		for (String member : new String[] { "add", "sub", "mul", "div", "maximum", "minimum", "sum", "amax", "amin",
				"transpose", "sqrt", "abs", "negative", "sign", "greater", "greater-equal", "less", "less-equal",
				"equal", "where", "reshape", "concatenate", "take-rows", "gather", "softmax" }) {
			assertThat(eval("(linalg:zeros 1) #'linalg:" + member, true).print()).as(member)
				.isEqualTo("#<function LINALG:" + member.toUpperCase() + ">");
			assertThat(eval("(linalg:zeros 1) #'linalg:" + member, false).print()).as(member).isEqualTo("#<lambda>");
		}
		for (String internal : new String[] { "%la-adam-step", "%la-gather-strided", "%la-scale", "%la-scatter-rows",
				"%la-sum-squares",
				// The fused tier (.todo/499): the compositions torch.lisp spells as one
				// member each.
				"%la-softmax-grad", "%la-gelu", "%la-gelu-grad", "%la-layer-norm", "%la-layer-norm-grad",
				"%la-dropout-mask" }) {
			assertThat(eval("(linalg:zeros 1) #'linalg::" + internal, true).print()).as(internal)
				.isEqualTo("#<function LINALG::" + internal.toUpperCase() + ">");
			assertThat(eval("(linalg:zeros 1) #'linalg::" + internal, false).print()).as(internal)
				.isEqualTo("#<lambda>");
		}
		// Fifty-one linalg: members and no others. matmul, mean, var, log-softmax,
		// square, relu, slice and flatten are accelerated THROUGH them, not instead of
		// them, so they must still be the library's own lambdas under the flag.
		for (String member : new String[] { "matmul", "outer", "norm", "trace", "argmax", "argmin", "log-softmax",
				"mean", "var", "slice", "flatten", "stack" }) {
			assertThat(eval("(linalg:zeros 1) #'linalg:" + member, true).print()).as(member).isEqualTo("#<lambda>");
		}
		// And the one member OUTSIDE linalg: -- vec:matvec, installed when the vec
		// library loads, on top of whatever that library bound; vec:dot is not one.
		assertThat(eval("(vec:zeros 1) #'vec:matvec", true).print()).isEqualTo("#<function VEC:MATVEC>");
		assertThat(eval("(vec:zeros 1) #'vec:matvec", false).print()).isEqualTo("#<lambda>");
		assertThat(eval("(vec:zeros 1) #'vec:dot", true).print()).isEqualTo("#<lambda>");
	}

	// --- the fused tier (.todo/499) --------------------------------------------------

	/** Rows enough for the fold rule and the fold threshold at a 384-wide row. */
	private static final int FUSED_ROWS = (int) Math.max(256, (am.ik.gpu.GpuThresholds.foldMinElements() + 383) / 384);

	/** The fused tier's operands: two inexact {@code FUSED_ROWS x 384} arrays. */
	private static String fusedOperands(String option) {
		int n = FUSED_ROWS * 384;
		return """
				(defparameter *x* (linalg:reshape (linalg:linspace -3.0 3.0 %d%s) '(%d 384)))
				(defparameter *g* (linalg:reshape (linalg:linspace 1.0 -2.0 %d%s) '(%d 384)))
				""".formatted(n, option, FUSED_ROWS, n, option, FUSED_ROWS);
	}

	@Test
	void theFusedTierLandsOnTheChainsBitsWhereItHasNoLibmAndWithinToleranceWhereItHas() {
		// layer-norm and its adjoint, softmax's adjoint and the dropout mask replay
		// chains with no library function in them and are byte-identical to the defun;
		// softmax and the exact GELU carry the device's exp and erf and stand to the
		// defun as those members do. The state vector the mask advances is checked too.
		for (String option : DOUBLES ? new String[] { "", " :element-type 'single-float" }
				: new String[] { " :element-type 'single-float" }) {
			String operands = fusedOperands(option);
			assertMatchesScalarOracle(operands + "(linalg::%la-layer-norm *x* 1.0e-5)");
			assertMatchesScalarOracle(operands + "(linalg::%la-layer-norm-grad *g* *x* 1.0e-5 nil)");
			assertMatchesScalarOracle(operands + "(linalg::%la-layer-norm-grad *g* *x* 1.0e-5 *g*)");
			assertMatchesScalarOracle(operands + "(linalg::%la-softmax-grad *g* *x* 1)");
			String mask = "(linalg::%la-dropout-mask '(" + FUSED_ROWS + " 384) 0.1 *st* "
					+ (option.isEmpty() ? "nil" : "t") + ")";
			assertMatchesScalarOracle("(linalg:seed 5) (defparameter *st* (linalg::%la-rng-state)) " + mask);
			assertMatchesScalarOracle("(linalg:seed 5) (defparameter *st* (linalg::%la-rng-state)) " + mask + " *st*");
			// softmax sits where a single libm member sits, per element. The GELU pair
			// CANCELS: `1 + erf(x / sqrt 2)` is 0.0027 at x = -3, so erf's last-ulp
			// difference is 2e-5 relative there at #f (measured), and the adjoint ends in
			// a sum of two branches with the same property -- so the pair is pinned the
			// way an inexact product is, as a fraction of the largest element.
			assertThat(worstRelative(elements(eval(operands + "(linalg:softmax *x* :axis -1)", true)),
					elements(eval(operands + "(linalg:softmax *x* :axis -1)", false))))
				.as("softmax" + option)
				.isLessThan(option.isEmpty() ? 1e-12 : 1e-5);
			for (String call : new String[] { "(linalg::%la-gelu *x*)", "(linalg::%la-gelu-grad *g* *x* nil)",
					"(linalg::%la-gelu-grad *g* *x* *g*)" }) {
				assertThat(divergence(elements(eval(operands + call, true)), elements(eval(operands + call, false))))
					.as(call + option)
					.isLessThan(option.isEmpty() ? 1e-12 : 1e-5);
			}
		}
	}

	@Test
	void theFusedTierReallyRanOnTheDeviceAndAnyOtherAxisDeclines() {
		// Bit-identity cannot say a member ran, the residency hit count can: a fused
		// member over an operand the device already holds is a hit. And softmax over
		// any axis but the last declines to the defun, whose members run one by one --
		// the same answer, from a different route.
		String operands = fusedOperands(" :element-type 'single-float");
		long hits = am.ik.gpu.GpuThresholds.residencyHits();
		eval(operands + "(linalg::%la-layer-norm-grad *g* (linalg::%la-layer-norm *x* 1.0e-5) 1.0e-5 nil)", true);
		assertThat(am.ik.gpu.GpuThresholds.residencyHits()).isGreaterThan(hits);
		assertThat(worstRelative(elements(eval(operands + "(linalg:softmax *x* :axis 0)", true)),
				elements(eval(operands + "(linalg:softmax *x* :axis 0)", false))))
			.isLessThan(1e-5);
	}

	// --- the matrix-by-vector product (vec:matvec, 2026-08-22) -----------------------

	/**
	 * Whether this device takes the GEMV at all (the CUDA one does; Metal keeps no
	 * copies).
	 */
	private static boolean takesMatvec() {
		return am.ik.gpu.GpuThresholds.matvecMinElements() < Long.MAX_VALUE;
	}

	/** The side of a square matrix comfortably over the GEMV threshold in force. */
	private static int matvecSide() {
		return 16 * (int) Math.ceil(Math.sqrt(2.0 * am.ik.gpu.GpuThresholds.matvecMinElements()) / 16);
	}

	/**
	 * {@code W x} with {@code W} of exact +1 / -1 entries (the sign of a sine, exact at
	 * both widths) and {@code x} an index ramp, so every row's dot is an exact integer
	 * far under 2^24 and the device's reordered double sum must land on the oracle's bits
	 * exactly. The product is taken TWICE: the first sight of a matrix declines, and the
	 * value of the last form -- the second call -- is what the device answered.
	 */
	private static String exactMatvec(int side, String option) {
		return """
				(defparameter *w* (linalg:reshape (linalg:sign (linalg:sin (linalg:arange 1 %d%s))) '(%d %d)))
				(defparameter *x* (linalg:arange 0 %d%s))
				(vec:matvec *w* *x*)
				(vec:matvec *w* *x*)
				""".formatted(side * side + 1, option, side, side, side, option);
	}

	@Test
	void theMatrixByVectorProductMatchesTheScalarOracleOnExactInputsOnceResident() {
		assumeThat(takesMatvec()).as("this device keeps resident copies").isTrue();
		int side = matvecSide();
		assertMatchesScalarOracle(exactMatvec(side, option()));
		assertMatchesScalarOracle(exactMatvec(side, " :element-type 'single-float"));
	}

	@Test
	void theMatrixByVectorProductReallyRanOnTheDeviceOnTheSecondSight() {
		assumeThat(takesMatvec()).as("this device keeps resident copies").isTrue();
		// The accepted call is written to land on the oracle's bits, so no printed value
		// can say it ran; the residency hit count can -- the second sight uploads the
		// matrix, the third finds it.
		String program = exactMatvec(matvecSide(), " :element-type 'single-float") + "(vec:matvec *w* *x*)\n";
		long hits = am.ik.gpu.GpuThresholds.residencyHits();
		eval(program, true);
		assertThat(am.ik.gpu.GpuThresholds.residencyHits()).isGreaterThan(hits);
	}

	@Test
	void theDeviceIsAskedOnTheSecondSightAndTheLaneKernelOnTheFirst() {
		assumeThat(takesMatvec()).as("this device keeps resident copies").isTrue();
		// .kb/vec.md's f32 reduction probe, as a matrix row: the scalar defun (double
		// accumulation, narrowed) prints 16778240 and the --simd lane kernel (four float
		// lanes) 16777984. The device accumulates in double, so its answer is the
		// DEFUN's -- and the first call is the lane kernel's, because the first sight of
		// a
		// matrix declines. That makes the chain legible from Lisp: (lane device).
		int rows = (int) Math.max(128, (am.ik.gpu.GpuThresholds.matvecMinElements() + 1023) / 1024);
		String program = """
				(defparameter *w* (linalg:zeros '(%d 1024) :element-type 'single-float))
				(setf (aref *w* 0 0) 4096.0)
				(dotimes (j 1023) (setf (aref *w* 0 (+ j 1)) 1.0))
				(defparameter *x* (linalg:ones '(1024) :element-type 'single-float))
				(setf (aref *x* 0) 4096.0)
				(defun probe () (round (aref (vec:matvec *w* *x*) 0)))
				(list (probe) (probe))
				""".formatted(rows);
		assertThat(eval(program, true, false, true).print()).as("--gpu --simd").isEqualTo("(16777984 16778240)");
		assertThat(eval(program, false, false, true).print()).as("--simd").isEqualTo("(16777984 16777984)");
		assertThat(eval(program, true, false, false).print()).as("--gpu").isEqualTo("(16778240 16778240)");
		assertThat(eval(program, false, false, false).print()).as("scalar").isEqualTo("(16778240 16778240)");
	}

	@Test
	void everyMatrixByVectorDeclineRunsWhatWasBoundBeforeUnchanged() {
		// A matrix below the threshold, a mixed-width pair (which the defun computes and
		// the lane kernel refuses), a rank-1 "matrix", a vector of the wrong length: each
		// is the captured binding's outcome, value or error, with the flag and without.
		for (String form : new String[] { """
				(defparameter *w* (linalg:reshape (linalg:arange 1 257) '(16 16)))
				(list (vec:matvec *w* (linalg:arange 1 17)) (vec:matvec *w* (linalg:arange 1 17)))
				""", "(vec:matvec #f((1.0 2.0) (3.0 4.0)) #d(1.0 2.0))", "(vec:matvec #d(1.0 2.0) #d(1.0 2.0))",
				"(vec:matvec #d((1.0 2.0) (3.0 4.0)) #d(1.0))" }) {
			assertThat(outcome(form, true, false)).as(form).isEqualTo(outcome(form, false, false));
			assertThat(outcome(form, true, true)).as(form + " --simd").isEqualTo(outcome(form, false, true));
		}
	}

	/** What a program prints, or the error it signals, under the given flags. */
	private String outcome(String program, boolean gpu, boolean simd) {
		try {
			return eval(program, gpu, false, simd).print();
		}
		catch (RuntimeException ex) {
			return "error: " + ex.getMessage();
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
		// room, because the exact number is the device's and not ours. The #d half runs
		// only where the device has a double: on Metal it always declines, and asserting
		// a divergence there would be asserting that the decline protocol is broken.
		if (DOUBLES) {
			double[] doubles = elements(eval(inexactProduct(""), true));
			double[] doubleOracle = elements(eval(inexactProduct(""), false));
			assertThat(divergence(doubles, doubleOracle)).isLessThan(1e-13).isGreaterThan(0);
		}
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
				(defparameter *a* (linalg:reshape (linalg:sin (linalg:arange 0 %d%s)) '(%d %d)))
				(defparameter *b* (linalg:reshape (linalg:cos (linalg:arange 0 %d%s)) '(%d %d)))
				""".formatted(SIDE * SIDE, option(), SIDE, SIDE, SIDE * SIDE, option(), SIDE, SIDE);
		double[] flat = elements(eval(slab + "(linalg:matmul *a* *b*)", true));
		double[] stacked = elements(eval(slab + """
				(linalg:matmul (linalg:reshape *a* '(1 %d %d)) (linalg:reshape *b* '(1 %d %d)))
				""".formatted(SIDE, SIDE, SIDE, SIDE), true));
		assertThat(stacked).isEqualTo(flat);
		double[] oracle = elements(eval(slab + "(linalg:matmul *a* *b*)", false));
		assertThat(divergence(stacked, oracle)).isLessThan(DOUBLES ? 1e-13 : 1e-5).isGreaterThan(0);
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
					(defparameter *a* (linalg:linspace %s %s %d%s))
					(linalg:%s *a*)
					""".replace("%d", Integer.toString(MAP_N));
			if (DOUBLES) {
				String doubles = program.formatted(member[1], member[2], "", member[0]);
				assertThat(worstRelative(elements(eval(doubles, true)), elements(eval(doubles, false))))
					.as("#d linalg:%s", member[0])
					.isLessThan(1e-12);
			}
			String singles = program.formatted(member[1], member[2], " :element-type 'single-float", member[0]);
			assertThat(worstRelative(elements(eval(singles, true)), elements(eval(singles, false))))
				.as("#f linalg:%s", member[0])
				.isLessThan(1e-5);
		}
	}

	@Test
	void anAcceleratedElementWiseCallReallyRanOnTheDevice() {
		// The tolerance above would pass on a dead flag, so this is its guard: over an
		// array the device accepts, it and the CPU cannot land on the same bits for every
		// inexact element -- at every width the device HAS.
		String program = """
				(defparameter *a* (linalg:linspace -5.0 5.0 %d%s))
				(linalg:erf *a*)
				""".replace("%d", Integer.toString(MAP_N));
		if (DOUBLES) {
			assertThat(elements(eval(program.formatted(""), true)))
				.isNotEqualTo(elements(eval(program.formatted(""), false)));
		}
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
				(defparameter *x* (torch:tensor (linalg:linspace -3.0 3.0 %d%s)))
				(linalg:sum (torch:data (torch:gelu *x*)))
				""".formatted(MAP_N, option());
		double accelerated = ((am.ik.rontolisp.LispDouble) eval(program, true)).value();
		double oracle = ((am.ik.rontolisp.LispDouble) eval(program, false)).value();
		// A SUM of every device-computed element, so the tolerance is looser than the
		// per-element one above by about the count: measured 2.0e-9 relative at #d.
		assertThat(accelerated).isNotEqualTo(oracle)
			.isCloseTo(oracle, within((DOUBLES ? 1e-7 : 1e-3) * Math.abs(oracle)));
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
	void theGeneratorFillIsByteIdenticalToTheScalarOracleOnTheDevice() {
		// linalg:seed's promise holds across the device: the closed-form jump reproduces
		// the sequential walk bit for bit, at both widths and every rule, and the
		// generator continues where the walk would have -- the scalar draws after a
		// device fill are the oracle's. (On a device without the member -- Metal -- every
		// fill declines and the program is identical anyway.)
		assertMatchesScalarOracle("""
				(linalg:seed 7)
				(defparameter *a* (linalg:rand 100000))
				(defparameter *b* (linalg:randn 50000))
				(defparameter *c* (linalg:uniform -2 3 70000))
				(defparameter *d* (linalg:rand '(200 300) :element-type 'single-float))
				(defparameter *e* (linalg:randn 30000 :element-type 'single-float))
				(defparameter *f* (linalg:uniform 0.5 1.5 9000 :element-type 'single-float))
				(list (linalg:to-list (linalg:slice *a* '((8190 8200))))
				      (linalg:to-list (linalg:slice *b* '((49990 50000))))
				      (linalg:to-list (linalg:slice *c* '((0 10))))
				      (linalg:to-list (linalg:slice *d* '((199 200) (290 300))))
				      (linalg:to-list (linalg:slice *e* '((12340 12350))))
				      (linalg:to-list (linalg:slice *f* '((8990 9000))))
				      (linalg::%la-rng-next) (linalg:rand 3) (linalg:choice 10 4))
				""");
	}

	@Test
	void aGeneratorFillReallyRanOnTheDevice() {
		// The value assertion above would pass on a dead flag: the observable difference
		// is that the member is BOUND to the device interceptor -- and that a fill below
		// the threshold, or a state word outside the generator's range, is declined to
		// what was bound before and answers the same bytes.
		assertThat(eval("(linalg:zeros 1) #'linalg::%la-rng-fill", true).print())
			.isEqualTo("#<function LINALG::%LA-RNG-FILL>");
		assertThat(eval("(linalg:zeros 1) #'linalg::%la-rng-fill", false).print()).isEqualTo("#<lambda>");
		assertMatchesScalarOracle("(linalg:seed 3) (list (linalg:rand 8191) (linalg::%la-rng-next))");
		assertMatchesScalarOracle("(linalg:seed 4) (let ((s (linalg::%la-rng-state)))"
				+ " (setf (aref s 0) -3.0) (linalg::%la-rng-fill (linalg:zeros 20000) s 0 0.0 1.0))");
		assertMatchesScalarOracle(
				"(linalg:seed 4) (linalg::%la-rng-fill (linalg:zeros 20000) (linalg::%la-rng-state) 5 0.0 1.0)");
	}

	@Test
	void theStridedTierIsBitIdenticalToTheScalarOracle() {
		// The claim that separates this tier from the element-wise one: a broadcast
		// binary op, an axis fold and an axes transpose are BIT-IDENTICAL to the defun at
		// both widths, because the kernel widens to double, computes in double and
		// narrows only on the store -- %la-bcast-loop's and %la-fold-axis's own rule --
		// and there is no libm anywhere in them. Asserted over INEXACT data, above the
		// thresholds, so the device really is asked.
		assertMatchesScalarOracle("""
				(defparameter *x* (linalg:reshape (linalg:linspace 0.013 3.7 262144) '(64 4096)))
				(defparameter *m* (linalg:amax *x* :axis 1 :keepdims t))
				(defparameter *s* (linalg:sub *x* *m*))
				(list (linalg:to-list (linalg:flatten *m*))
				      (linalg:to-list (linalg:flatten (linalg:sum *x* :axis 1 :keepdims t)))
				      (linalg:to-list (linalg:flatten (linalg:amin *x* :axis 1)))
				      (linalg:sum *s*)
				      (linalg:sum (linalg:div *s* (linalg:sum *s* :axis 1 :keepdims t)))
				      (linalg:sum (linalg:mul *x* *m*))
				      (linalg:sum (linalg:add *x* *m*))
				      (linalg:sum (linalg:maximum *x* *m*))
				      (linalg:sum (linalg:minimum *x* *m*))
				      (linalg:sum (linalg:transpose *x* '(1 0))))
				""");
		assertMatchesScalarOracle("""
				(defparameter *y* (linalg:reshape
				                   (linalg:linspace 0.013 3.7 262144 :element-type 'single-float)
				                   '(4 64 1024)))
				(defparameter *r* (linalg:mean *y* :axis 2 :keepdims t))
				(list (linalg:sum (linalg:sub *y* *r*))
				      (linalg:sum (linalg:div *y* *r*))
				      (linalg:to-list (linalg:flatten (linalg:sum *y* :axis 0)))
				      (linalg:sum (linalg:var *y* :axis 2 :keepdims t))
				      (linalg:sum (linalg:transpose *y* '(0 2 1)))
				      (linalg:shape (linalg:transpose *y* '(2 0 1))))
				""");
	}

	@Test
	void aStridedCallReallyRanOnTheDevice() {
		// The numeric assertions above would pass on the defun, so this is the one that
		// fails if the strided tier is dead: with the flag on, a broadcast subtraction
		// above the threshold must not be the CPU's -- and the only observable difference
		// is that the device was ASKED, which the interceptor's own binding shows.
		assertThat(eval("(linalg:zeros 1) #'linalg:sub", true).print()).isEqualTo("#<function LINALG:SUB>");
		// A shape the device takes, and one it refuses: an EQUAL-shaped pair stays the
		// CPU's however big it is, which is the element-wise tier's measurement and is
		// what
		// stops this
		// tier quietly widening.
		assertMatchesScalarOracle("""
				(defparameter *x* (linalg:reshape (linalg:linspace 0.013 3.7 262144) '(64 4096)))
				(linalg:sum (linalg:sub *x* *x*))
				""");
	}

	@Test
	void everyStridedDeclineRunsTheScalarDefunUnchanged() {
		// The shapes the kernel refuses, each answered by the captured binding: an equal
		// -shaped pair, a mixed-width pair, a scalar operand, a boxed array, a
		// non-broadcastable pair (the defun's error), a whole-array fold, a nil axis, a
		// bad permutation (the defun's error) and the plain no-axes transpose.
		assertMatchesScalarOracle("""
				(defparameter *x* (linalg:reshape (linalg:linspace 0.013 3.7 262144) '(64 4096)))
				(list (linalg:sum (linalg:mul *x* 2.5)) (linalg:sum (linalg:sub 1.0 *x*))
				      (linalg:sum *x*) (linalg:amax *x*) (linalg:amin *x*)
				      (linalg:sum *x* :keepdims t)
				      (linalg:shape (linalg:transpose *x*))
				      (linalg:to-list (linalg:add #(1 2 3) #(10 20 30))))
				""");
		assertThatThrownBy(() -> eval("""
				(defparameter *x* (linalg:reshape (linalg:linspace 0.013 3.7 262144) '(64 4096)))
				(linalg:add *x* (linalg:zeros '(3 5)))
				""", true)).hasMessageContaining("linalg");
		assertThatThrownBy(() -> eval("""
				(defparameter *x* (linalg:reshape (linalg:linspace 0.013 3.7 262144) '(64 4096)))
				(linalg:transpose *x* '(0 0))
				""", true)).hasMessageContaining("linalg");
		// A mixed-width pair: the defun widens and keeps the FIRST operand's width, which
		// no kernel over this seam reproduces.
		assertMatchesScalarOracle("""
				(defparameter *x* (linalg:reshape (linalg:linspace 0.013 3.7 262144) '(64 4096)))
				(defparameter *f* (linalg:reshape
				                   (linalg:linspace 0.5 1.5 64 :element-type 'single-float) '(64 1)))
				(linalg:sum (linalg:mul *x* *f*))
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
				(defparameter *a* (linalg:linspace -3.0 3.0 %d%s))
				(linalg:to-list (linalg:erf *a*))
				""".formatted(MAP_N, option());
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
		String product = inexactProduct(Math.max(192, SIDE), TYPE);
		double[] gpuOnly = elements(eval(product, true, false, false));
		double[] blasOnly = elements(eval(product, false, true, false));
		assumeThat(blasOnly).as("the library and the device round differently").isNotEqualTo(gpuOnly);
		assertThat(elements(eval(product, true, true, false))).isEqualTo(gpuOnly);
	}

	@Test
	void theDeviceIsAskedAheadOfTheLaneKernel() {
		String product = inexactProduct(TYPE);
		double[] gpuOnly = elements(eval(product, true, false, false));
		double[] simdOnly = elements(eval(product, false, false, true));
		// The lane kernel and the device round differently -- at #d because the lanes are
		// bit-identical to the scalar defun and the device fuses, at #f because two f32
		// reductions in different orders are two different answers -- so these differ by
		// construction and the order pin below is not a tautology.
		assertThat(simdOnly).isNotEqualTo(gpuOnly);
		assertThat(elements(eval(product, true, false, true))).isEqualTo(gpuOnly);
		assertThat(elements(eval(product, true, true, true))).isEqualTo(gpuOnly);
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
		// The two legible answers are READ rather than written down: which integer the
		// lane kernel prints depends on the machine's lane count (16777216 on a GB10,
		// 16777728 on an M4 Max), and hard-coding one of them pins the CPU backend's
		// vector width in a --gpu test, which is not what this is about.
		String defun = eval(probe, false, false, false).print();
		String lanes = eval(probe, false, false, true).print();
		assumeThat(lanes).as("the lane kernel and the defun answer differently here").isNotEqualTo(defun);
		// --gpu alone declines it to the defun; --gpu --simd declines it to the lanes.
		assertThat(eval(probe, true, false, false).print()).isEqualTo(defun);
		assertThat(eval(probe, true, false, true).print()).isEqualTo(lanes);
		if (LinalgBlas.available()) {
			// With --blas on the best CPU path for this shape is the LIBRARY's, not the
			// lane kernel's: unlike --gpu, --blas does take the gemv shapes. So the claim
			// is "the same answer the CPU flags alone would have given", which is what
			// composition means -- comparing against the lane kernel here would be
			// asserting that a tuned sgemv sums the way our lanes do, and on Apple's
			// Accelerate it does not.
			assertThat(eval(probe, true, true, true).print()).isEqualTo(eval(probe, false, true, true).print());
		}
	}

	@Test
	void aDeclinedStackFallsOnTheLaneKernelWhenSimdIsOnAndOnTheDefunOtherwise() {
		// --blas does NOT take this member, so for a stacked product the chain is
		// device -> lane kernel -> defun, with no library rung. The probe is the rank-3
		// line of .kb/linalg-simd.md's f32 fold: the lane kernel accumulates in single
		// precision and the boxed defun widens, so the two print different integers and
		// the fallback target is legible. Which integers is the machine's lane count and
		// is read rather than written down. The shape is well under the size threshold,
		// so
		// the device declines it however the flags are set.
		String probe = """
				(let ((v (linalg:ones 1024 :element-type 'single-float)))
				  (setf (aref v 0) 4096.0)
				  (round (row-major-aref
				          (linalg:matmul (linalg:reshape v '(1 1 1024)) (linalg:reshape v '(1 1024 1))) 0)))
				""";
		String defun = eval(probe, false, false, false).print();
		String lanes = eval(probe, false, false, true).print();
		assumeThat(lanes).as("the lane kernel and the defun answer differently here").isNotEqualTo(defun);
		assertThat(eval(probe, true, false, false).print()).isEqualTo(defun);
		assertThat(eval(probe, true, true, false).print()).isEqualTo(defun);
		assertThat(eval(probe, true, false, true).print()).isEqualTo(lanes);
		assertThat(eval(probe, true, true, true).print()).isEqualTo(lanes);
	}

	@Test
	void theDeviceIsAskedAheadOfTheLaneKernelForTheStackedProductToo() {
		String stack = """
				(defparameter *a* (linalg:reshape (linalg:sin (linalg:arange 0 %d%s)) '(2 %d %d)))
				(defparameter *b* (linalg:reshape (linalg:cos (linalg:arange 0 %d%s)) '(2 %d %d)))
				(linalg:matmul *a* *b*)
				""".formatted(2 * SIDE * SIDE, option(), SIDE, SIDE, 2 * SIDE * SIDE, option(), SIDE, SIDE);
		double[] gpuOnly = elements(eval(stack, true, false, false));
		double[] simdOnly = elements(eval(stack, false, false, true));
		// The lane kernel and the device round differently at either width, so these
		// differ by construction.
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

	// --- device residency (2026-08-22) ------------------------------------------------

	/** Runs the program and answers what it printed. */
	private String output(String input, boolean gpu, boolean simd) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(out));
		evaluator.setSimd(simd);
		evaluator.setGpu(gpu);
		for (LispVal expr : LispReader.readAllFromString(input)) {
			evaluator.eval(expr);
		}
		return out.toString();
	}

	/**
	 * Every in-place writer of a packed float array there is, each followed by the same
	 * bit-identical device member over the array it wrote, so that a writer the residency
	 * invalidation does not see shows up as a wrong sum. The first call makes both
	 * operands resident; {@code check} then prints a sum the oracle must print too.
	 * {@code %la-scale} / {@code %la-scatter-rows} / {@code %la-adam-step} /
	 * {@code %la-rng-fill} and the {@code vec:} {@code -into} family are the kernels that
	 * bypass the element setter under {@code --simd}, which is why the program is run
	 * with that flag as well as without it. {@code fill} and {@code replace} are not here
	 * because the interpreter does not accept a packed array for either; on the JVM they
	 * expand to the setter this program already exercises. {@code read-sequence} over a
	 * binary file IS here: its bulk primitive writes the storage behind the setter's back
	 * on both backends, and it is how a model's weights arrive ({@code examples/llama2}).
	 */
	private static String residencyWriters(int side, String type, String file) {
		int n = side * side;
		return """
				(defparameter *a* (linalg:reshape (linalg:arange 1 %d%s) '(%d %d)))
				(defparameter *row* (linalg:reshape (linalg:arange 1 %d%s) '(1 %d)))
				(defparameter *v* (linalg:arange 1 %d%s))
				(defparameter *one* (linalg:ones '(1)%s))
				(defun check (tag)
				  (format t "~a ~a ~a~%%" tag (linalg:sum (linalg:add *a* *row*)) (linalg:sum (linalg:add *v* *one*))))
				(check "resident")
				(setf (aref *a* 3 4) 0.5) (check "aset")
				(setf (row-major-aref *a* 777) -1.25) (check "row-major-aset")
				(setf (aref *row* 0 5) 100) (check "aset-other-operand")
				(setf (aref *v* 10) 3) (check "aset-vector")
				(setf (row-major-aref *v* 11) 4) (check "row-major-aset-vector")
				(linalg::%%la-scale *a* 3) (check "la-scale")
				(linalg::%%la-scale *v* 0.5) (check "la-scale-vector")
				(linalg::%%la-scatter-rows *a* (linalg:ones '(2 %d)%s) #d(1.0 5.0)) (check "la-scatter-rows")
				(linalg::%%la-adam-step *a* (linalg:ones '(%d %d)%s) (linalg:zeros '(%d %d)%s) (linalg:zeros '(%d %d)%s)
				                        #d(0.01 0.0 0.0 0.9 0.1 0.999 0.001 0.00000001 1.0 1.0 0.0))
				(check "la-adam-step")
				(linalg::%%la-rng-fill *a* #d(11.0 22.0 33.0) 0 0.0 1.0) (check "la-rng-fill")
				(linalg::%%la-rng-fill *v* #d(44.0 55.0 66.0) 1 0.0 1.0) (check "la-rng-fill-vector")
				(vec:scale-into *v* *v* 2) (check "vec-scale-into")
				(vec:add-into *v* *v* *v*) (check "vec-add-into")
				(vec:negative-into *v* *v*) (check "vec-negative-into")
				(vec:clip-into *v* *v* -1 1) (check "vec-clip-into")
				(vec:matvec-into *v* (linalg:ones '(%d 1)%s) *one*) (check "vec-matvec-into")
				(with-open-file (s "%s" :element-type '(unsigned-byte 8)) (read-sequence *v* s)) (check "read-sequence")
				""".formatted(n + 1, type, side, side, side + 1, type, side, n + 1, type, type, side, type, side, side,
				type, side, side, type, side, side, type, n, type, file);
	}

	/**
	 * A binary file of {@code count} raw little-endian elements of value 7, at the width
	 * of {@code type}, for the {@code read-sequence} writer above.
	 */
	static java.nio.file.Path residencyFile(int count, boolean single) throws java.io.IOException {
		java.nio.ByteBuffer bytes = java.nio.ByteBuffer.allocate(count * (single ? 4 : 8))
			.order(java.nio.ByteOrder.LITTLE_ENDIAN);
		for (int i = 0; i < count; i++) {
			if (single) {
				bytes.putFloat(7.0f);
			}
			else {
				bytes.putDouble(7.0);
			}
		}
		java.nio.file.Path file = java.nio.file.Files.createTempFile("resident", ".bin");
		java.nio.file.Files.write(file, bytes.array());
		return file;
	}

	@Test
	void everyEnumeratedWriterInvalidatesTheResidentCopy() throws java.io.IOException {
		// The side of a square above the strided threshold, so the broadcast add really
		// goes to the device and both operands really are resident afterwards.
		int side = 16 * (int) Math.ceil(Math.sqrt(2.0 * am.ik.gpu.GpuThresholds.stridedMinElements()) / 16);
		java.nio.file.Path file = residencyFile(side, !DOUBLES);
		String program = residencyWriters(side, option(), file.toString());
		String oracle = output(program, false, false);
		assertThat(oracle).contains("resident ").contains("vec-matvec-into ").contains("read-sequence ");
		// --gpu alone: the in-place members run their defuns, which write through the
		// element setter; --gpu --simd: they run the kernels, which report themselves.
		// The second leg's oracle is --simd itself, so that the lane sum's own fold
		// order (and the lane Adam step's own rounding) is on both sides and the device
		// is the only variable.
		assertThat(output(program, true, false)).as("--gpu").isEqualTo(oracle);
		assertThat(output(program, true, true)).as("--gpu --simd").isEqualTo(output(program, false, true));
	}

	// --- lazy results and the resident tier (.todo/491) -------------------------------

	/**
	 * Every HOST READ of a packed array's storage there is, each over a result the device
	 * produced and -- since {@code .todo/491} -- left on the device: the element reads,
	 * the printer, a defun that walks the array, the lane and the {@code vec:} kernels, a
	 * typed loop, {@code to-list}, the bulk {@code write-sequence}, and the writes that
	 * must bring a result home BEFORE they land ({@code (setf (aref ...))}, an in-place
	 * kernel, an {@code -into}). A reader the materialization does not see prints the
	 * zeros of an array nobody filled. The members are the bit-identical ones (a
	 * broadcast add, a transpose), so the oracle is the same program without the flag.
	 */
	private static String residencyReaders(int side, String type, String file) {
		int n = side * side;
		return """
				(defparameter *a* (linalg:reshape (linalg:arange 1 %d%s) '(%d %d)))
				(defparameter *row* (linalg:reshape (linalg:arange 1 %d%s) '(1 %d)))
				(defparameter *v* (linalg:arange 1 %d%s))
				(defparameter *one* (linalg:ones '(1)%s))
				(defparameter *r* (linalg:add *a* *row*))
				(defparameter *rv* (linalg:add *v* *one*))
				(format t "aref ~a ~a ~a ~a~%%" (aref *r* 3 4) (row-major-aref *r* 777) (aref *rv* 10) (row-major-aref *rv* 11))
				(format t "print ~a~%%" (subseq (format nil "~a" *rv*) 0 24))
				(format t "prin1 ~a~%%" (subseq (prin1-to-string *r*) 0 24))
				(format t "array-equal ~a~%%" (linalg:array-equal *r* (linalg:add *a* *row*)))
				(format t "sum ~a ~a~%%" (linalg:sum *r*) (linalg:sum *rv*))
				(format t "vec ~a ~a~%%" (vec:sum *rv*) (vec:dot *rv* *rv*))
				(let ((rv *rv*) (acc 0.0))
				  (dotimes (i %d) (setq acc (+ acc (aref rv i))))
				  (format t "loop ~a~%%" acc))
				(format t "to-list ~a~%%" (subseq (linalg:to-list *rv*) 0 4))
				(format t "length ~a ~a~%%" (length *rv*) (array-dimensions *r*))
				(with-open-file (s "%s" :direction :output :element-type '(unsigned-byte 8) :if-exists :supersede)
				  (write-sequence *rv* s))
				(defparameter *back* (linalg:zeros '(%d)%s))
				(with-open-file (s "%s" :element-type '(unsigned-byte 8)) (read-sequence *back* s))
				(format t "write-sequence ~a ~a~%%" (aref *back* 7) (linalg:sum *back*))
				(defparameter *r2* (linalg:add *a* *row*))
				(setf (aref *r2* 0 0) -1.0)
				(format t "aset-into-result ~a ~a ~a~%%" (aref *r2* 0 0) (aref *r2* 1 1) (linalg:sum *r2*))
				(defparameter *r3* (linalg:add *a* *row*))
				(linalg::%%la-scale *r3* 2)
				(format t "scale-result ~a~%%" (linalg:sum *r3*))
				(defparameter *r4* (linalg:add *v* *one*))
				(vec:scale-into *r4* *r4* 0.5)
				(format t "into-result ~a~%%" (vec:sum *r4*))
				(format t "chain ~a~%%" (linalg:sum (linalg:add (linalg:add *a* *row*) *row*)))
				(format t "transpose ~a~%%" (linalg:sum (linalg:transpose (linalg:add *a* *row*) '(1 0))))
				"""
			.formatted(n + 1, type, side, side, side + 1, type, side, n + 1, type, type, side, file, n, type, file);
	}

	@Test
	void everyEnumeratedReaderMaterializesTheDeviceResult() throws java.io.IOException {
		int side = 16 * (int) Math.ceil(Math.sqrt(2.0 * am.ik.gpu.GpuThresholds.stridedMinElements()) / 16);
		java.nio.file.Path file = java.nio.file.Files.createTempFile("lazy", ".bin");
		String program = residencyReaders(side, option(), file.toString());
		String oracle = output(program, false, false);
		assertThat(oracle).contains("aref ").contains("write-sequence ").contains("transpose ");
		assertThat(output(program, true, false)).as("--gpu").isEqualTo(oracle);
		// The --simd leg against a --simd oracle: the lane sum's own fold order is on
		// both sides, and the device is the only variable.
		assertThat(output(program, true, true)).as("--gpu --simd").isEqualTo(output(program, false, true));
	}

	@Test
	void aDeviceResultStaysOnTheDeviceUntilTheHostFirstReadsIt() {
		assumeThat(am.ik.gpu.GpuThresholds.lazyResultsOn()).as("lazy results pay on this backend (CUDA, not Metal)")
			.isTrue();
		int side = 16 * (int) Math.ceil(Math.sqrt(2.0 * am.ik.gpu.GpuThresholds.stridedMinElements()) / 16);
		String program = """
				(defparameter *a* (linalg:reshape (linalg:arange 1 %d%s) '(%d %d)))
				(defparameter *row* (linalg:reshape (linalg:arange 1 %d%s) '(1 %d)))
				(linalg:add *a* *row*)
				""".formatted(side * side + 1, option(), side, side, side + 1, option(), side);
		int dirty = am.ik.gpu.GpuThresholds.dirtyCount();
		int backed = am.ik.gpu.GpuThresholds.backingCount();
		LispVal result = eval(program, true);
		// The value came back as a packed array whose storage the device still holds:
		// one more dirty copy, and on the host a STUB -- no array of the result's size
		// was allocated at all (.kb/gpu.md, "Lazy results, and the result that has no
		// host array") ...
		assertThat(am.ik.gpu.GpuThresholds.dirtyCount()).isEqualTo(dirty + 1);
		assertThat(am.ik.gpu.GpuThresholds.backingCount()).isEqualTo(backed);
		Object storage = result instanceof LispSingleFloatArray f ? f.storage()
				: ((LispDoubleFloatArray) result).storage();
		assertThat(storage instanceof float[] f ? f.length : ((double[]) storage).length).isZero();
		assertThat(((am.ik.rontolisp.LispFloatArray) result).totalSize()).isEqualTo(side * side);
		// ... until the first read through the accessor brings it home, once, into a
		// backing the accessor answers; the record's storage stays the stub it keys on.
		double[] elements = elements(result);
		assertThat(am.ik.gpu.GpuThresholds.dirtyCount()).isEqualTo(dirty);
		assertThat(am.ik.gpu.GpuThresholds.backingCount()).isEqualTo(backed + 1);
		assertThat(elements[5]).isEqualTo(6 + 6);
		assertThat(elements[side + 3]).isEqualTo(side + 4 + 4);
		assertThat(elements(result)[5]).isEqualTo(12);
		assertThat(storage instanceof float[] f ? f.length : ((double[]) storage).length).isZero();
		assertThat(am.ik.gpu.GpuThresholds.backingCount()).isEqualTo(backed + 1);
	}

	/**
	 * The resident tier over both backends' interceptors: the equal-shape binary ops, the
	 * scalar forms, the comparison masks, sqrt / abs / negative / sign, where and the
	 * Adam update, each over an operand the device holds ({@code *r*}, a device result)
	 * and each bit-identical to the CPU kernel it replaces.
	 */
	private static String residentTier(int side, String type) {
		int n = side * side;
		return """
				(defparameter *a* (linalg:reshape (linalg:arange 1 %d%s) '(%d %d)))
				(defparameter *row* (linalg:reshape (linalg:arange 1 %d%s) '(1 %d)))
				(defparameter *r* (linalg:add *a* *row*))
				(defparameter *b* (linalg:reshape (linalg:linspace 0.5 9.5 %d%s) '(%d %d)))
				(defun s (x) (linalg:sum x))
				(format t "equal ~a ~a ~a ~a ~a ~a~%%" (s (linalg:add *r* *b*)) (s (linalg:sub *b* *r*)) (s (linalg:mul *r* *b*))
				        (s (linalg:div *b* *r*)) (s (linalg:maximum *r* *b*)) (s (linalg:minimum *b* *r*)))
				(format t "compare ~a ~a ~a ~a ~a~%%" (s (linalg:greater *r* *b*)) (s (linalg:greater-equal *b* *r*))
				        (s (linalg:less *r* *b*)) (s (linalg:less-equal *b* *r*)) (s (linalg:equal *r* *r*)))
				(format t "scalar ~a ~a ~a ~a ~a~%%" (s (linalg:mul *r* 0.3)) (s (linalg:div *r* 7)) (s (linalg:sub 2.5 *r*))
				        (s (linalg:div 1 *r*)) (s (linalg:greater *r* 100)))
				(format t "unary ~a ~a ~a ~a~%%" (s (linalg:sqrt *r*)) (s (linalg:abs (linalg:sub 100 *r*)))
				        (s (linalg:negative *r*)) (s (linalg:sign (linalg:sub *r* 50))))
				(format t "where ~a ~a~%%" (s (linalg:where (linalg:greater *row* 3) *r* -1.5)) (s (linalg:where *r* 2.0 *b*)))
				(defparameter *g* (linalg:mul *r* 0.01))
				(defparameter *x* (linalg:reshape (linalg:linspace -1.0 1.0 %d%s) '(%d %d)))
				(defparameter *m* (linalg:zeros '(%d %d)%s))
				(defparameter *v* (linalg:zeros '(%d %d)%s))
				(linalg::%%la-adam-step *x* *g* *m* *v* #d(0.01 0.001 0.1 0.9 0.1 0.999 0.001 0.00000001 0.1 0.001 2.0))
				(linalg::%%la-adam-step *x* *g* *m* *v* #d(0.01 0.001 0.1 0.9 0.1 0.999 0.001 0.00000001 0.19 0.001999 1.0))
				(format t "adam ~a ~a ~a~%%" (s *x*) (s *m*) (s *v*))
				(format t "chain ~a~%%" (s (linalg:div (linalg:sub (linalg:mul *r* 2) *b*) (linalg:add *b* 0.5))))
				(format t "reshape ~a ~a~%%" (s (linalg:mul (linalg:reshape *r* (list (* %d 2) (/ %d 2))) 1.5))
				        (linalg:to-list (linalg:slice (linalg:reshape *r* (list (* %d 2) (/ %d 2))) '((1 3) (3 7)))))
				(format t "transpose ~a~%%" (linalg:to-list (linalg:slice (linalg:transpose *r*) '((2 4) (0 3)))))
				(format t "slice ~a ~a~%%" (s (linalg:slice *r* '((1 %d 3) (5 nil 2))))
				        (linalg:to-list (linalg:slice *r* '((10 2 -4) (7 1 -3)))))
				(format t "cat ~a ~a~%%" (s (linalg:concatenate (list *r* *b* *r*) :axis 0))
				        (linalg:to-list (linalg:slice (linalg:concatenate (list *b* *r*) :axis 1) (list '(3 4) (list (- %d 2) (+ %d 2))))))
				(defparameter *g2* (linalg:mul *r* 0.5))
				(linalg::%%la-scale *g2* 0.125)
				(format t "scale ~a ~a~%%" (s *g2*) (aref *g2* 2 3))
				"""
			.formatted(n + 1, type, side, side, side + 1, type, side, n, type, side, side, n, type, side, side, side,
					side, type, side, side, type, side, side, side, side, side, side, side);
	}

	@Test
	void theResidentTierRunsOverAResidentOperandAndLandsOnTheCpuKernelsBits() {
		int side = 16 * (int) Math.ceil(Math.sqrt(2.0 * am.ik.gpu.GpuThresholds.stridedMinElements()) / 16);
		String program = residentTier(side, option());
		String oracle = output(program, false, false);
		assertThat(oracle).contains("equal ").contains("adam ").contains("chain ");
		long hits = am.ik.gpu.GpuThresholds.residencyHits();
		assertThat(output(program, true, false)).as("--gpu").isEqualTo(oracle);
		// The dead-flag guard: every member above read a RESIDENT operand on the device,
		// and the cache counted each as a hit. Thirty-odd members; the bound is loose. On
		// a backend where lazy results do not pay (Metal, measured) nothing but a GEMV
		// matrix is ever resident, the tier is never offered, and the program still
		// prints the oracle -- which the assertion above has checked.
		if (am.ik.gpu.GpuThresholds.lazyResultsOn()) {
			assertThat(am.ik.gpu.GpuThresholds.residencyHits()).isGreaterThan(hits + 20);
		}
		assertThat(output(program, true, true)).as("--gpu --simd").isEqualTo(output(program, false, true));
	}

	@Test
	void theResidentTierDeclinesWithoutAResidentOperandAndTheCpuRunsUnchanged() {
		// The same members over operands the device has never seen: every one declines
		// (a round trip cannot beat the lane loop, .kb/gpu.md), the cache counts no hit,
		// and the program prints what it prints without the flag. linspace and arange
		// allocate on the host and are not members.
		String program = """
				(defparameter *a* (linalg:linspace 0.5 9.5 %d%s))
				(defparameter *b* (linalg:linspace 0.1 3.1 %d%s))
				(list (linalg:sum (linalg:add *a* *b*)) (linalg:sum (linalg:mul *a* 0.3)) (linalg:sum (linalg:sqrt *a*))
				      (linalg:sum (linalg:where (linalg:greater *a* 5) *b* 0.0)) (linalg:sum (linalg:greater *a* *b*)))
				""".formatted(MAP_N, option(), MAP_N, option());
		long hits = am.ik.gpu.GpuThresholds.residencyHits();
		assertMatchesScalarOracle(program);
		assertThat(am.ik.gpu.GpuThresholds.residencyHits()).isEqualTo(hits);
	}

	/**
	 * The INDEX tier over a resident table: the embedding lookup, the per-row pick and
	 * the scatter-add adjoint, all three index-driven copies and all three BIT-IDENTICAL
	 * to the CPU kernels, so the assertion is equality against the same program without
	 * the flag. The indices REPEAT, which is where the scatter's order can be seen at
	 * all.
	 */
	@Test
	void theIndexTierRunsOverAResidentTableAndLandsOnTheCpuKernelsBits() {
		int side = 16 * (int) Math.ceil(Math.sqrt(2.0 * am.ik.gpu.GpuThresholds.stridedMinElements()) / 16);
		String program = indexTier(side, option());
		String oracle = output(program, false, false);
		assertThat(oracle).contains("take ").contains("pick ").contains("scatter ");
		long hits = am.ik.gpu.GpuThresholds.residencyHits();
		assertThat(output(program, true, false)).as("--gpu").isEqualTo(oracle);
		if (am.ik.gpu.GpuThresholds.lazyResultsOn()) {
			assertThat(am.ik.gpu.GpuThresholds.residencyHits()).isGreaterThan(hits);
		}
		assertThat(output(program, true, true)).as("--gpu --simd").isEqualTo(output(program, false, true));
	}

	@Test
	void theIndexTierDeclinesWithoutAResidentTableAndTheCpuRunsUnchanged() {
		// A table the device has never seen: the lookup and its adjoint decline whatever
		// the size, and the program prints what it prints without the flag.
		String program = """
				(defparameter *t* (linalg:reshape (linalg:linspace 0.5 9.5 %d%s) '(%d %d)))
				(defparameter *i* (let ((v (linalg:zeros '(%d)%s)))
				                    (dotimes (i %d v) (setf (aref v i) (mod (* i 7) %d)))))
				(defparameter *z* (linalg:zeros '(%d %d)%s))
				(list (linalg:sum (linalg:take-rows *t* *i*)) (linalg:sum (linalg:gather *t* *i*))
				      (linalg:sum (linalg::%%la-scatter-rows *z* (linalg:take-rows *t* *i*) *i*)))
				""".formatted(MAP_N, option(), 64, MAP_N / 64, 64, option(), 64, 64, 64, MAP_N / 64, option());
		long hits = am.ik.gpu.GpuThresholds.residencyHits();
		assertMatchesScalarOracle(program);
		assertThat(am.ik.gpu.GpuThresholds.residencyHits()).isEqualTo(hits);
	}

	/**
	 * The one member of this flag whose fold ORDER is not the defun's: a clip norm's sum
	 * of squares, folded in blocks on the device. So the assertion is NOT equality -- it
	 * is that the answer is within a few ulps of the sequential fold, that it is the same
	 * on every run (the block count is a function of the length, not of the schedule),
	 * and that the scale it produces still rewrites the gradients the same way.
	 */
	@Test
	void theClipNormFoldsInBlocksOnTheDeviceCloseToTheSequentialSumAndReproducibly() {
		int side = 16 * (int) Math.ceil(Math.sqrt(2.0 * am.ik.gpu.GpuThresholds.stridedMinElements()) / 16);
		String program = """
				(defparameter *a* (linalg:reshape (linalg:arange 1 %d%s) '(%d %d)))
				(defparameter *g* (linalg:mul *a* 0.001))
				(format t "norm ~a~%%" (sqrt (linalg::%%la-sum-squares *g* 0.25)))
				(linalg::%%la-scale *g* 0.5)
				(format t "scaled ~a~%%" (sqrt (linalg::%%la-sum-squares *g* 0.0)))
				""".formatted(side * side + 1, option(), side, side);
		double[] oracle = numbers(output(program, false, false));
		double[] device = numbers(output(program, true, false));
		assertThat(device).hasSameSizeAs(oracle);
		for (int i = 0; i < oracle.length; i++) {
			assertThat(device[i]).as("value %d", i).isCloseTo(oracle[i], within(Math.abs(oracle[i]) * 1e-9));
		}
		// Reproducible: the same program, the same digits, every time.
		assertThat(output(program, true, false)).isEqualTo(output(program, true, false));
	}

	/** The doubles of a printed program's output, in order. */
	private static double[] numbers(String printed) {
		java.util.List<Double> values = new java.util.ArrayList<>();
		java.util.regex.Matcher m = java.util.regex.Pattern.compile("-?\\d+\\.\\d+(?:[eEdD]-?\\d+)?").matcher(printed);
		while (m.find()) {
			values.add(Double.parseDouble(m.group().replace('d', 'e').replace('D', 'E')));
		}
		double[] out = new double[values.size()];
		for (int i = 0; i < out.length; i++) {
			out[i] = values.get(i);
		}
		return out;
	}

	/**
	 * The index tier's program: a resident table (a broadcast add makes it one), the
	 * lookup, the pick, and the scatter-add over an index vector whose values repeat --
	 * without repeats the accumulation order the kernel keeps would not be visible.
	 */
	private static String indexTier(int side, String type) {
		int n = side * side;
		return """
				(defun ix (m k)
				  (let ((v (linalg:zeros (list m)%s)))
				    (dotimes (i m v) (setf (aref v i) (mod (* i 7) k)))))
				(defparameter *a* (linalg:reshape (linalg:arange 1 %d%s) '(%d %d)))
				(defparameter *row* (linalg:reshape (linalg:arange 1 %d%s) '(1 %d)))
				(defparameter *t* (linalg:add *a* *row*))
				(defparameter *idx* (ix %d %d))
				(defparameter *col* (ix %d %d))
				(defun s (x) (linalg:sum x))
				(format t "take ~a ~a~%%" (s (linalg:take-rows *t* *idx*))
				        (linalg:to-list (linalg:slice (linalg:take-rows *t* *idx*) '((2 4) (0 3)))))
				(format t "pick ~a ~a~%%" (s (linalg:gather *t* *col*))
				        (linalg:to-list (linalg:slice (linalg:gather *t* *col*) '((0 5)))))
				(defparameter *z* (linalg:mul *t* 0.5))
				(format t "scatter ~a ~a~%%"
				        (s (linalg::%%la-scatter-rows *z* (linalg:take-rows *t* *idx*) *idx*)) (aref *z* 3 4))
				""".formatted(type, n + 1, type, side, side, side + 1, type, side, 4 * side, side, side, side);
	}

}
