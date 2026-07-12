package am.ik.rontolisp.codegen.jvm;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.eval.LinalgLibrary;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The {@code --simd} JVM acceleration of the {@code linalg:} kernels, the sibling of
 * {@link JvmSimdAccelCompilerTest}. Thirty-four {@code linalg:} call sites are routed to
 * the embedded {@link JvmSimdVectorTemplate} bridge instead of the scalar
 * {@code linalg.lisp} defun, and must produce byte-identical output at both widths, at
 * rank 1 AND rank 2.
 *
 * <p>
 * The distinguishing feature of this path is the FALLBACK: a bridge kernel returns
 * {@code null} for an input it does not handle, and the emitted call site then runs the
 * scalar defun over the same temps. So the tests cover both halves -- the accelerated
 * results, and the declined ones (general arrays, mixed widths, plain numbers, shape
 * errors), which must behave exactly as they do without the flag.
 */
class JvmLinalgSimdAccelCompilerTest {

	@TempDir
	Path tempDir;

	private byte[] compile(String lispCode, boolean accel) {
		List<LispVal> program = LinalgLibrary.process(LispReader.readAllFromString(lispCode));
		return new JvmLispCompiler("Test", false, false, accel).compile(program);
	}

	private String run(byte[] classBytes) throws Exception {
		Path classFile = tempDir.resolve("Test.class");
		Files.write(classFile, classBytes);
		try (URLClassLoader loader = new URLClassLoader(new URL[] { tempDir.toUri().toURL() },
				ClassLoader.getSystemClassLoader())) {
			Class<?> clazz = loader.loadClass("Test");
			Method main = clazz.getMethod("main", String[].class);
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			PrintStream oldOut = System.out;
			System.setOut(new PrintStream(baos));
			try {
				main.invoke(null, (Object) new String[0]);
			}
			finally {
				System.setOut(oldOut);
			}
			return baos.toString().trim();
		}
	}

	private String accel(String lispCode) throws Exception {
		return run(compile(lispCode, true));
	}

	private String scalar(String lispCode) throws Exception {
		return run(compile(lispCode, false));
	}

	/** Asserts the accelerated class prints exactly what the scalar one prints. */
	private void assertMatchesScalarReference(String lispCode) throws Exception {
		assertThat(accel(lispCode)).as(lispCode).isEqualTo(scalar(lispCode));
	}

	private static boolean embedsBridge(byte[] classBytes) {
		return new String(classBytes, StandardCharsets.ISO_8859_1).contains(JvmSimdRuntimeBuilder.BRIDGE_NAME);
	}

	// --- the dead-flag guard -------------------------------------------------------

	@Test
	void theBridgeIsEmbeddedOnlyUnderTheSimdFlagAndOnlyForAnAcceleratedLinalgMember() {
		// Without this, an interception that never fires would still pass every numeric
		// assertion below, because the scalar defun returns the right answer
		// ([[simd-shadow-and-dead-flag-lesson]]). A linalg program alone must pull the
		// bridge in -- no vec: call is involved.
		assertThat(embedsBridge(compile("(print (linalg:sum #d(1.0 2.0 3.0)))", true))).isTrue();
		assertThat(embedsBridge(compile("(print (linalg:add #d(1.0) #d(2.0)))", true))).isTrue();
		assertThat(embedsBridge(compile("(print (linalg:transpose #d((1.0 2.0) (3.0 4.0))))", true))).isTrue();
		assertThat(embedsBridge(compile("(print (linalg:sum #d(1.0 2.0 3.0)))", false))).isFalse();
		// A program that never mentions the package keeps the bridge out. (Any linalg
		// program pulls it in, even one calling only linalg:eye: the spliced linalg.lisp
		// itself contains the accelerated call sites, exactly as vec.lisp does for vec:.)
		assertThat(embedsBridge(compile("(print (+ 1 2))", true))).isFalse();
		assertThat(embedsBridge(compile("(print (linalg:eye 2))", true))).isTrue();
	}

	@Test
	void singleFloatReductionsAccumulateInSinglePrecisionUnderSimd() throws Exception {
		// The todo-106 precision contract, extended to linalg. This is the only test that
		// proves the KERNEL ran rather than the defun: a dead interception would print
		// the
		// scalar 16778239. Same values as the interpreter's LinalgSimdTest probe, so the
		// two --simd backends are pinned to each other as well as to the contract.
		// dot(v, v) = 4096^2 + 1023 = 16778239 exactly, with 4096^2 = 2^24 in one lane.
		String dot = probe32("4096.0", "(linalg:dot *v* *v*)");
		assertThat(accel(dot)).isEqualTo("16777984");
		assertThat(scalar(dot)).isEqualTo("16778239");
		// sum and mean need 2^24 itself in element 0 to reach the same swallowing point.
		String sum = probe32("16777216.0", "(linalg:sum *v*)");
		assertThat(accel(sum)).isEqualTo("16777984");
		assertThat(scalar(sum)).isEqualTo("16778239");
		String mean = probe32("16777216.0", "(* 1024 (linalg:mean *v*))");
		assertThat(accel(mean)).isEqualTo("16777984");
		assertThat(scalar(mean)).isEqualTo("16778239");
		// #d is untouched by the contract.
		String probe64 = """
				(defparameter *v* (linalg:ones 1024))
				(setf (aref *v* 0) 4096.0)
				(print (round (linalg:dot *v* *v*)))
				""";
		assertThat(accel(probe64)).isEqualTo("16778239");
		assertThat(scalar(probe64)).isEqualTo("16778239");
	}

	/** A 1024-element {@code #f} vector of ones with {@code elem0} in element 0. */
	private String probe32(String elem0, String reduction) {
		return """
				(defparameter *v* (linalg:ones 1024 'single-float))
				(setf (aref *v* 0) %s)
				(print (round %s))
				""".formatted(elem0, reduction);
	}

	// --- element-wise --------------------------------------------------------------

	@Test
	void elementWiseKernelsMatchTheScalarReferenceAtBothWidthsAndRanks() throws Exception {
		for (String op : List.of("add", "sub", "mul", "div")) {
			assertMatchesScalarReference("(print (linalg:%s #d(1.0 2.0 3.0) #d(4.0 5.0 8.0)))".formatted(op));
			assertMatchesScalarReference("(print (linalg:%s #f(1.0 2.0 3.0) #f(4.0 5.0 8.0)))".formatted(op));
			assertMatchesScalarReference(
					"(print (linalg:%s #d((1.0 2.0) (3.0 4.0)) #d((5.0 6.0) (7.0 8.0))))".formatted(op));
			// n = 200 > THRESHOLD: the lane loop and its scalar tail both run. The whole
			// array is printed -- wrapping it in linalg:sum would compare an accelerated
			// REDUCTION, whose lane associativity legitimately differs from the oracle.
			assertMatchesScalarReference(
					"(print (linalg:%s (linalg:arange 1 201) (linalg:arange 2 202)))".formatted(op));
		}
	}

	@Test
	void aRank2ElementWiseResultKeepsItsDimensions() throws Exception {
		// The case vec: never had: its kernels always produce a rank-1 [1, n, ...]
		// header.
		assertThat(accel("(print (linalg:add #d((1.0 2.0) (3.0 4.0)) #d((10.0 20.0) (30.0 40.0))))"))
			.isEqualTo("#d((11.0 22.0) (33.0 44.0))");
		assertThat(accel("(print (linalg:mul #f((1.0 2.0 3.0)) 2.0))")).isEqualTo("#f((2.0 4.0 6.0))");
		assertMatchesScalarReference("(print (linalg:sub (linalg:reshape (linalg:arange 24) '(2 3 4)) 1.0))");
	}

	@Test
	void scalarBroadcastMatchesTheScalarReferenceOnEitherSide() throws Exception {
		for (String op : List.of("add", "sub", "mul", "div")) {
			assertMatchesScalarReference("(print (linalg:%s #d(1.0 2.0 4.0) 2.0))".formatted(op));
			assertMatchesScalarReference("(print (linalg:%s 2.0 #d(1.0 2.0 4.0)))".formatted(op));
			assertMatchesScalarReference("(print (linalg:%s #d(1.0 2.0 4.0) 2))".formatted(op));
			assertMatchesScalarReference("(print (linalg:%s (linalg:arange 1 201) 3.0))".formatted(op));
			// The reason the #f broadcast kernels compute in double and narrow once:
			// 0.1 is not representable, so an f32 lane multiply would diverge here.
			// examples/ml/nn-vec.lisp is exactly this shape -- (linalg:mul grad 0.1).
			assertMatchesScalarReference("(print (linalg:%s (linalg:arange 1 201 1 'single-float) 0.1))".formatted(op));
		}
	}

	// --- the declined inputs (fall back to the scalar defun) -----------------------

	@Test
	void generalArraysMixedWidthsAndPlainNumbersFallBackToTheScalarDefun() throws Exception {
		assertThat(accel("(print (linalg:add #(1 2 3) #(10 20 30)))")).isEqualTo("#d(11.0 22.0 33.0)");
		assertThat(accel("(print (linalg:sum #(1 2 3)))")).isEqualTo("6");
		// A mixed-width linalg call is NOT an error (unlike vec:): the defun widens both
		// operands and keeps the first one's width.
		assertThat(accel("(print (linalg:add #d(1.0 2.0) #f(10.0 20.0)))")).isEqualTo("#d(11.0 22.0)");
		assertThat(accel("(print (linalg:add #f(1.0 2.0) #d(10.0 20.0)))")).isEqualTo("#f(11.0 22.0)");
		assertThat(accel("(print (linalg:add 2 3))")).isEqualTo("5");
		assertMatchesScalarReference("(print (linalg:dot #2A((1 2) (3 4)) #(1 1)))");
	}

	@Test
	void anArgumentFormIsEvaluatedExactlyOnceEvenWhenTheKernelDeclines() throws Exception {
		// The fallback reloads the temps rather than recompiling the argument forms; a
		// second evaluation would print the side effect twice and bump the counter to 2.
		String declined = """
				(defparameter *n* 0)
				(defun bump () (setq *n* (+ *n* 1)) #(1 2 3))
				(linalg:add (bump) #(1 1 1))
				(print *n*)
				""";
		assertThat(accel(declined)).isEqualTo("1");
		String accepted = """
				(defparameter *n* 0)
				(defun bump () (setq *n* (+ *n* 1)) #d(1.0 2.0 3.0))
				(linalg:add (bump) #d(1.0 1.0 1.0))
				(print *n*)
				""";
		assertThat(accel(accepted)).isEqualTo("1");
	}

	@Test
	void broadcastPairsRunTheBcastKernelAndMatchTheScalarReference() throws Exception {
		// Two same-width arrays of different-but-broadcastable shapes run the general
		// numpy odometer kernel since the todo-117 follow-up (laBcastDD/laBcastFF);
		// a boxed or mixed-width pair still declines to the defun.
		assertThat(accel("(print (linalg:mul #2A((1 2) (3 4)) #d(10.0 20.0)))"))
			.isEqualTo("#d((10.0 40.0) (30.0 80.0))");
		assertMatchesScalarReference(
				"(print (linalg:add (linalg:reshape (linalg:arange 6) '(2 3)) (linalg:arange 3)))");
		assertMatchesScalarReference("(print (linalg:sub (linalg:reshape (linalg:arange 0 4 'single-float) '(2 2))"
				+ " (linalg:arange 0 2 'single-float)))");
		assertThat(accel("(print (array-element-type (linalg:div (linalg:ones '(2 2) 'single-float) #d(1.0 2.0))))"))
			.isEqualTo("single-float");
		// The row and column shapes of the CNN layers, a rank-3 pair, and the strict
		// comparison selects through the same kernel.
		assertMatchesScalarReference("(print (linalg:mul (linalg:reshape (linalg:arange 8) '(4 2))"
				+ " (linalg:reshape (linalg:from-list '(5.0 6.0 7.0 8.0)) '(4 1))))");
		assertMatchesScalarReference("(print (linalg:div (linalg:reshape (linalg:arange 24) '(2 3 4))"
				+ " (linalg:add (linalg:reshape (linalg:arange 12) '(3 4)) 1)))");
		assertMatchesScalarReference("(print (linalg:add (linalg:reshape (linalg:arange 0 4 'single-float) '(2 2))"
				+ " (linalg:reshape (linalg:arange 0 2 'single-float) '(2 1))))");
		assertMatchesScalarReference("(print (linalg:maximum (linalg:reshape (linalg:arange 6) '(2 3))"
				+ " (linalg:from-list '(2.0 4.0 1.0))))");
		assertThat(accel("(print (linalg:maximum #d((0.0 -0.0)) #d(-0.0 0.0)))")).isEqualTo("#d((-0.0 0.0))");
	}

	@Test
	void axisFormsRunTheAxisKernelsAndMatchTheScalarReference() throws Exception {
		// The axis forms are intercepted since the todo-117 follow-up: an axis call
		// routes to the extended bridge kernel (laSumAxis &c, a 2-argument call padded
		// with null for the missing keepdims), whose folds mirror %la-fold-axis /
		// %la-argfold-axis exactly; a 1-arg call still hits the base kernel whose
		// decline branch passes an empty rest list to the variadic defun.
		assertThat(accel("(print (linalg:sum #d((1.0 2.0 3.0) (4.0 5.0 6.0)) 0))")).isEqualTo("#d(5.0 7.0 9.0)");
		assertThat(accel("(print (linalg:sum #d((1.0 2.0 3.0) (4.0 5.0 6.0)) 1 t))")).isEqualTo("#d((6.0) (15.0))");
		assertMatchesScalarReference("(print (linalg:mean (linalg:reshape (linalg:arange 6) '(2 3)) 0))");
		assertMatchesScalarReference("(print (linalg:amax (linalg:reshape (linalg:arange 6) '(2 3)) 1))");
		assertMatchesScalarReference("(print (linalg:argmax (linalg:reshape (linalg:arange 6) '(2 3)) 1))");
		assertMatchesScalarReference("(print (linalg:sum (linalg:reshape (linalg:arange 24) '(2 3 4)) -1))");
		assertMatchesScalarReference("(print (linalg:amin (linalg:reshape (linalg:arange 6) '(2 3)) 0 t))");
		assertMatchesScalarReference(
				"(print (linalg:sum (linalg:from-list '((0.5 0.25) (0.125 2.0)) 'single-float) 0))");
		assertMatchesScalarReference(
				"(print (linalg:amax (linalg:from-list '((0.5 0.25) (0.125 2.0)) 'single-float) 1))");
		assertMatchesScalarReference("(print (linalg:argmin (linalg:from-list '(3.0 9.0 2.0)) 0))");
		// The fold's strict comparison: the accumulator wins ties (first element).
		assertThat(accel("(print (linalg:amax #d((-0.0 0.0)) 1))")).isEqualTo("#d(-0.0)");
		assertThat(accel("(print (linalg:amax #d((-3.0 -1.0) (-5.0 -2.0)) 1))")).isEqualTo("#d(-1.0 -2.0)");
		// 1-arg calls over a general (boxed) array exercise the decline branch itself.
		assertThat(accel("(print (linalg:sum #(1 2 3)))")).isEqualTo("6");
		assertThat(accel("(print (linalg:argmax #(1 9 3)))")).isEqualTo("1");
		assertThat(accel("(print (linalg:amin #(4 2 9)))")).isEqualTo("2");
		// reshape keeps its fixed arity 2; a -1 extent declines inside the kernel.
		assertMatchesScalarReference("(print (linalg:reshape (linalg:arange 12) '(3 -1)))");
	}

	@Test
	void transposeAxesMatchesTheScalarReference() throws Exception {
		// The 2-argument axes form routes to laTransposeAxes; a bad permutation and a
		// nil axes argument decline to the defun (rest-packaged into the variadic
		// call), which errors or runs its plain-transpose branch.
		assertMatchesScalarReference(
				"(print (linalg:transpose (linalg:reshape (linalg:arange 24) '(2 3 2 2)) '(0 3 1 2)))");
		assertMatchesScalarReference(
				"(print (linalg:transpose (linalg:reshape (linalg:arange 24) '(2 3 2 2)) '(0 2 3 1)))");
		assertMatchesScalarReference("(print (linalg:transpose (linalg:reshape (linalg:arange 6) '(2 3)) '(1 0)))");
		assertMatchesScalarReference("(print (linalg:transpose (linalg:arange 3) '(0)))");
		assertMatchesScalarReference(
				"(print (linalg:transpose (linalg:reshape (linalg:from-list '(1.0 2.0 3.0 4.0 5.0 6.0 7.0 8.0)"
						+ " 'single-float) '(2 2 2)) '(2 0 1)))");
		assertMatchesScalarReference("(print (linalg:transpose (linalg:reshape (linalg:arange 6) '(2 3)) nil))");
		assertThatThrownBy(() -> accel("(print (linalg:transpose #d((1.0 2.0)) '(0 0)))")).rootCause()
			.hasMessageContaining("permutation");
	}

	@Test
	void anAxisArgumentFormIsEvaluatedExactlyOnceEvenWhenTheExtendedKernelDeclines() throws Exception {
		// The extended call site shares the evaluate-once temps: a declined axis call
		// (a general boxed array) reloads them into the rest list rather than
		// recompiling the argument forms.
		String declined = """
				(defparameter *n* 0)
				(defun bump () (setq *n* (+ *n* 1)) 0)
				(print (linalg:sum #2A((1 2) (3 4)) (bump)))
				(print *n*)
				""";
		assertThat(accel(declined)).isEqualTo(scalar(declined));
		String accepted = """
				(defparameter *n* 0)
				(defun bump () (setq *n* (+ *n* 1)) 1)
				(print (linalg:amax #d((1.0 9.0) (7.0 2.0)) (bump)))
				(print *n*)
				""";
		assertThat(accel(accepted)).isEqualTo(scalar(accepted));
	}

	@Test
	void aShapeMismatchStillSignalsTheLibraryError() throws Exception {
		// The kernel declines, the defun signals -- so the message is not duplicated.
		// (1) vs (2) broadcasts since the numpy rules landed, so the non-broadcastable
		// (2) vs (3) is the mismatch case now.
		assertThatThrownBy(() -> accel("(print (linalg:add #d(1.0 2.0) #d(1.0 2.0 3.0)))")).rootCause()
			.hasMessageContaining("linalg: shape mismatch");
		assertThatThrownBy(() -> accel("(print (linalg:trace #d((1.0 2.0 3.0) (4.0 5.0 6.0))))")).rootCause()
			.hasMessageContaining("square matrix");
		assertThatThrownBy(() -> accel("(print (linalg:reshape #d(1.0 2.0) '(3 3)))")).rootCause()
			.hasMessageContaining("reshape size mismatch");
	}

	// --- reductions ----------------------------------------------------------------

	@Test
	void reductionsMatchTheScalarReference() throws Exception {
		for (String member : List.of("sum", "mean", "amax", "amin", "norm", "argmax", "argmin")) {
			assertMatchesScalarReference("(print (linalg:%s #d(3.0 1.0 4.0 1.0 5.0)))".formatted(member));
			assertMatchesScalarReference("(print (linalg:%s #f(3.0 1.0 4.0 1.0 5.0)))".formatted(member));
			assertMatchesScalarReference("(print (linalg:%s (linalg:arange 200)))".formatted(member));
		}
		assertMatchesScalarReference("(print (linalg:sum #d((1.0 2.0) (3.0 4.0))))");
		assertMatchesScalarReference("(print (linalg:amin (linalg:reshape (linalg:arange 200) '(10 20))))");
		assertMatchesScalarReference("(print (linalg:trace (linalg:reshape (linalg:arange 100) '(10 10))))");
		assertMatchesScalarReference("(print (linalg:trace (linalg:eye 5 'single-float)))");
		// An all-negative array is the trap a max-reduce over zero-padded lanes falls
		// into.
		assertThat(accel("(print (linalg:amax (linalg:mul (linalg:arange 1 201) -1.0)))")).isEqualTo("-1.0");
	}

	// --- products -------------------------------------------------------------------

	@Test
	void dotDispatchesLikeNumpyAndMatchesTheScalarReference() throws Exception {
		assertMatchesScalarReference("(print (linalg:dot #d(1.0 2.0) #d(3.0 4.0)))");
		assertMatchesScalarReference("(print (linalg:dot #d((1.0 2.0) (3.0 4.0)) #d(1.0 1.0)))");
		assertMatchesScalarReference("(print (linalg:dot #d(1.0 1.0) #d((1.0 2.0) (3.0 4.0))))");
		assertMatchesScalarReference("(print (linalg:dot #d((1.0 2.0) (3.0 4.0)) #d((5.0 6.0) (7.0 8.0))))");
		assertMatchesScalarReference(
				"(print (linalg:matmul (linalg:eye 3) (linalg:reshape (linalg:arange 9) '(3 3))))");
		assertMatchesScalarReference("(print (linalg:dot #d(1.0 2.0) 3.0))");
		assertMatchesScalarReference("(print (linalg:dot #f((1.0 2.0)) #f((1.0) (2.0))))");
		// Rows longer than THRESHOLD, so the matmul lane loop over the output row runs.
		// (ikj keeps the oracle's summation order, so even this product is
		// bit-identical.)
		assertMatchesScalarReference("(print (linalg:dot (linalg:reshape (linalg:arange 600) '(3 200)) "
				+ "(linalg:reshape (linalg:arange 600) '(200 3))))");
	}

	@Test
	void doubleFloatMatrixProductsAreBitIdenticalBecauseIkjKeepsTheOracleSummationOrder() throws Exception {
		// The kernel rewrites the oracle's ijk triple loop as ikj so b's rows are read
		// contiguously; that visits k in the same increasing order into the same
		// accumulator cell, so the f64 result is identical, not merely close.
		assertThat(accel("(print (linalg:matmul (linalg:reshape (linalg:arange 1 10) '(3 3)) "
				+ "(linalg:reshape (linalg:arange 1 10) '(3 3))))"))
			.isEqualTo("#d((30.0 36.0 42.0) (66.0 81.0 96.0) (102.0 126.0 150.0))");
		assertMatchesScalarReference("(print (linalg:matmul (linalg:linspace 0.1 9.9 99) "
				+ "(linalg:reshape (linalg:linspace 0.01 9.9 99) '(99 1))))");
	}

	@Test
	void outerAndProductsPreserveTheOperandWidth() throws Exception {
		assertThat(accel("(print (linalg:outer #f(1.0 2.0) #f(3.0 4.0)))")).isEqualTo("#f((3.0 4.0) (6.0 8.0))");
		assertThat(accel("(print (linalg:dot (linalg:eye 2 'single-float) (linalg:ones 2 'single-float)))"))
			.isEqualTo("#f(1.0 1.0)");
		assertMatchesScalarReference("(print (linalg:outer (linalg:arange 200) (linalg:arange 200)))");
		assertMatchesScalarReference("(print (linalg:outer (linalg:reshape (linalg:arange 6) '(2 3)) #d(1.0 2.0)))");
	}

	// --- shape ------------------------------------------------------------------------

	@Test
	void transposeReshapeAndFlattenMatchTheScalarReference() throws Exception {
		assertMatchesScalarReference("(print (linalg:transpose #d((1.0 2.0 3.0) (4.0 5.0 6.0))))");
		assertMatchesScalarReference("(print (linalg:transpose #f((1.0 2.0) (3.0 4.0))))");
		assertMatchesScalarReference("(print (linalg:reshape (linalg:arange 12) '(3 4)))");
		assertMatchesScalarReference("(print (linalg:reshape (linalg:arange 12) '(2 3 2)))");
		assertMatchesScalarReference("(print (linalg:reshape (linalg:arange 0 12 'single-float) 12))");
		assertMatchesScalarReference("(print (linalg:flatten #d((1.0 2.0) (3.0 4.0))))");
		// A vector transposes to itself, the very same object.
		assertThat(accel("(let ((v #d(1.0 2.0))) (print (eq v (linalg:transpose v))))")).isEqualTo("t");
	}

	// --- element-wise unary ufuncs (todo 109) ------------------------------------------

	@Test
	void unaryUfuncsMatchTheScalarReferenceAtBothSizesWidthsAndRanks() throws Exception {
		for (String op : new String[] { "sqrt", "abs", "square", "negative", "sign", "reciprocal" }) {
			String inner = op.equals("sqrt") ? "(linalg:add %v 1)" : "(linalg:sub %v 100)";
			assertMatchesScalarReference(
					"(print (linalg:" + op + " " + inner.replace("%v", "(linalg:arange 7)") + "))");
			assertMatchesScalarReference(
					"(print (linalg:" + op + " " + inner.replace("%v", "(linalg:arange 200)") + "))");
			assertMatchesScalarReference("(print (linalg:" + op + " (linalg:reshape (linalg:arange 12) '(3 4))))");
			assertMatchesScalarReference(
					"(print (linalg:" + op + " (linalg:add (linalg:arange 0 200 'single-float) 1)))");
		}
		// exp over reciprocal's (0, 1] range so the values stay bounded; round because
		// exp's low digits are not print-stable across sizes.
		assertMatchesScalarReference(
				"(print (round (* 1000000 (linalg:sum (linalg:exp (linalg:reciprocal (linalg:add (linalg:arange 200) 1)))))))");
		assertMatchesScalarReference(
				"(print (round (* 1000000 (linalg:sum (linalg:exp (linalg:reciprocal (linalg:add (linalg:arange 7) 1)))))))");
		// log over strictly positive inputs, tanh over a sign-mixed range (todo 109
		// Phase 2 -- Math.log / Math.tanh scalar loops on this backend).
		for (String n : new String[] { "7", "200" }) {
			assertMatchesScalarReference("(print (linalg:log (linalg:add (linalg:arange " + n + ") 1)))");
			assertMatchesScalarReference(
					"(print (linalg:tanh (linalg:mul (linalg:sub (linalg:arange " + n + ") 100) 0.03)))");
		}
		assertMatchesScalarReference("(print (linalg:log (linalg:reshape (linalg:add (linalg:arange 12) 1) '(3 4))))");
		assertMatchesScalarReference("(print (linalg:log (linalg:add (linalg:arange 0 200 'single-float) 1)))");
		assertMatchesScalarReference("(print (linalg:tanh (linalg:arange 0 200 'single-float)))");
		// sin / cos / tan over a sign-mixed range (todo 109 Phase 2 second release --
		// Math.sin / Math.cos / Math.tan scalar loops on this backend).
		for (String op : new String[] { "sin", "cos", "tan" }) {
			for (String n : new String[] { "7", "200" }) {
				assertMatchesScalarReference("(print (linalg:" + op + " (linalg:sub (linalg:arange " + n + ") 100)))");
			}
			assertMatchesScalarReference("(print (linalg:" + op + " (linalg:reshape (linalg:arange 12) '(3 4))))");
			assertMatchesScalarReference("(print (linalg:" + op + " (linalg:arange 0 200 'single-float)))");
		}
		// asin / acos over the scaled [-0.5, 0.5) domain, atan / sinh / cosh over the
		// sign-mixed range (todo 109 Phase 2 third release).
		for (String op : new String[] { "asin", "acos" }) {
			assertMatchesScalarReference(
					"(print (linalg:" + op + " (linalg:mul (linalg:sub (linalg:arange 200) 100) 0.005)))");
			assertMatchesScalarReference(
					"(print (linalg:" + op + " (linalg:mul (linalg:arange 0 200 'single-float) 0.005)))");
		}
		for (String op : new String[] { "atan", "sinh", "cosh" }) {
			assertMatchesScalarReference(
					"(print (linalg:" + op + " (linalg:mul (linalg:sub (linalg:arange 200) 100) 0.05)))");
			assertMatchesScalarReference(
					"(print (linalg:" + op + " (linalg:reshape (linalg:mul (linalg:arange 12) 0.05) '(3 4))))");
			assertMatchesScalarReference(
					"(print (linalg:" + op + " (linalg:mul (linalg:arange 0 200 'single-float) 0.05)))");
		}
		assertMatchesScalarReference("(print (linalg:asin #d(0.0 -0.0 1.0 -1.0 0.5)))");
		assertMatchesScalarReference("(print (linalg:acos #d(1.0 -1.0 0.0 0.5)))");
		assertMatchesScalarReference("(print (linalg:sinh #d(0.0 -0.0 0.25 -0.25 0.3)))");
		assertMatchesScalarReference("(print (linalg:cosh #d(0.0 -0.0 1.0)))");
	}

	@Test
	void unaryUfuncsDeclineGeneralBoxedArraysToTheScalarDefun() throws Exception {
		for (String op : new String[] { "exp", "log", "tanh", "sin", "cos", "tan", "sqrt", "abs", "square", "negative",
				"sign", "reciprocal" }) {
			assertMatchesScalarReference("(print (linalg:" + op + " #(1 4 9)))");
		}
		for (String op : new String[] { "asin", "acos", "atan", "sinh", "cosh" }) {
			assertMatchesScalarReference("(print (linalg:" + op + " #(0 1)))");
		}
		assertThat(accel("(print (linalg:sqrt #(4 9)))")).isEqualTo("#d(2.0 3.0)");
		assertThat(accel("(print (linalg:square 3))")).isEqualTo("9");
	}

	// --- comparison-select ufuncs (todo 109 Phase 3) -----------------------------------

	@Test
	void comparisonSelectsMatchTheScalarReferenceAtBothSizesWidthsAndShapes() throws Exception {
		for (String op : new String[] { "maximum", "minimum" }) {
			// a ascends through zero, its negation descends: the winner flips
			// mid-array; both sizes, both widths, a rank-2 shape, and both broadcast
			// sides (integer-valued inputs, exact at either width).
			for (String n : new String[] { "8", "201" }) {
				assertMatchesScalarReference(
						"(let ((a (linalg:sub (linalg:arange 1 %1$s) 100.0))) (print (linalg:%2$s a (linalg:negative a))))"
							.formatted(n, op));
			}
			assertMatchesScalarReference(
					"(let ((a (linalg:arange 1 201 1 'single-float))) (print (linalg:%s a (linalg:negative a))))"
						.formatted(op));
			assertMatchesScalarReference(
					"(print (linalg:%s (linalg:reshape (linalg:arange 12) '(3 4)) (linalg:negative (linalg:reshape (linalg:arange 12) '(3 4)))))"
						.formatted(op));
			assertMatchesScalarReference(
					"(print (linalg:%s (linalg:sub (linalg:arange 1 201) 100.0) 3.0))".formatted(op));
			assertMatchesScalarReference(
					"(print (linalg:%s 3.0 (linalg:sub (linalg:arange 1 201) 100.0)))".formatted(op));
			// The f32-vs-scalar broadcast compares the widened element against the
			// FULL double scalar (an inexact bound), like the arithmetic broadcasts.
			assertMatchesScalarReference(
					"(print (linalg:%s (linalg:arange 1 201 1 'single-float) 100.3))".formatted(op));
			assertMatchesScalarReference(
					"(print (linalg:%s 100.3 (linalg:arange 1 201 1 'single-float)))".formatted(op));
		}
		// clip / relu ride the maximum/minimum call sites inside their own spliced
		// defuns (the square/reciprocal pattern -- no laClip/laRelu bridge entry).
		assertMatchesScalarReference("(print (linalg:clip (linalg:sub (linalg:arange 1 201) 100.0) -50.0 50.0))");
		assertMatchesScalarReference("(print (linalg:relu (linalg:sub (linalg:arange 1 201) 100.0)))");
		assertMatchesScalarReference(
				"(print (linalg:relu (linalg:reshape (linalg:sub (linalg:arange 12) 6.0) '(3 4))))");
	}

	@Test
	void comparisonSelectsFollowTheStrictComparisonNotMathMax() throws Exception {
		// The second operand (or the bound) wins any false comparison: the -0.0/0.0
		// tie and NaN -- the bridge mirrors the %la-bcast lambda, never Math.max.
		assertMatchesScalarReference("(print (linalg:maximum #d(-0.0 0.0) #d(0.0 -0.0)))");
		assertMatchesScalarReference("(print (linalg:minimum #d(-0.0 0.0) #d(0.0 -0.0)))");
		assertThat(accel("(print (linalg:maximum #d(-0.0) #d(0.0)))")).isEqualTo("#d(0.0)");
		assertThat(accel("(print (linalg:maximum #d(0.0) #d(-0.0)))")).isEqualTo("#d(-0.0)");
		assertThat(accel("(print (linalg:maximum #d(-0.0) 0.0))")).isEqualTo("#d(0.0)");
		assertThat(accel("(print (linalg:relu #d(-0.0)))")).isEqualTo("#d(0.0)");
		assertThat(accel("(print (linalg:clip (linalg:mul (linalg:ones 1) (/ 0.0 0.0)) -1.0 1.0))"))
			.isEqualTo("#d(-1.0)");
	}

	@Test
	void comparisonSelectsDeclineTheSameInputsAsTheArithmeticKernels() throws Exception {
		assertThat(accel("(print (linalg:maximum #(1 5 3) #(4 2 3)))")).isEqualTo("#d(4.0 5.0 3.0)");
		assertMatchesScalarReference("(print (linalg:minimum #d(1.0 5.0) #f(4.0 2.0)))");
		assertThat(accel("(print (linalg:maximum 2 3))")).isEqualTo("3");
		// A broadcastable pair declines to the defun, which broadcasts it.
		assertThat(accel("(print (linalg:maximum #d(1.0) #d(1.0 2.0)))")).isEqualTo("#d(1.0 2.0)");
		assertThatThrownBy(() -> accel("(print (linalg:maximum #d(1.0 2.0) #d(1.0 2.0 3.0)))"))
			.hasStackTraceContaining("linalg: shape mismatch");
	}

	@Test
	void theAcceleratedProgramStillInteroperatesWithThePackedArraySurface() throws Exception {
		assertMatchesScalarReference("""
				(defparameter *m* (linalg:add (linalg:reshape (linalg:arange 6) '(2 3)) 1.0))
				(print (aref *m* 1 2))
				(print (array-dimensions *m*))
				(print (linalg:size *m*))
				(setf (aref *m* 0 0) 99.0)
				(print *m*)
				""");
	}

	// --- CNN window unfolding: %la-im2col / %la-col2im (todo 117) ----------------------

	@Test
	void im2colAndCol2imMatchTheScalarReferenceAtBothWidths() throws Exception {
		// Batch > 1, channels > 1, stride > 1 and pad > 0, so the skipped padding rows
		// and clipped filter columns are all exercised. im2col only copies elements;
		// col2im's overlapping windows (stride < filter) accumulate exactly as the
		// defun's widen-add-narrow round trip does, at both widths.
		String x = "(linalg:reshape (linalg:arange 96) '(2 3 4 4))";
		String xf = "(linalg:reshape (linalg:arange 0 96 1 'single-float) '(2 3 4 4))";
		assertMatchesScalarReference("(print (linalg::%la-im2col " + x + " 2 2 1 0))");
		assertMatchesScalarReference("(print (linalg::%la-im2col " + x + " 3 3 2 1))");
		assertMatchesScalarReference("(print (linalg::%la-im2col " + xf + " 3 3 2 1))");
		assertMatchesScalarReference(
				"(print (linalg::%la-col2im (linalg::%la-im2col " + x + " 3 3 1 1) '(2 3 4 4) 3 3 1 1))");
		assertMatchesScalarReference(
				"(print (linalg::%la-col2im (linalg::%la-im2col " + xf + " 3 3 1 1) '(2 3 4 4) 3 3 1 1))");
		assertThat(accel("(print (linalg::%la-im2col (linalg:reshape (linalg:arange 4) '(1 1 2 2)) 2 2 1 0))"))
			.isEqualTo("#d((0.0 1.0 2.0 3.0))");
	}

	@Test
	void im2colDeclinedInputsRunTheScalarDefunExactlyOnce() throws Exception {
		// A general boxed rank-4 array declines to the defun (which answers a packed
		// double result on both paths)...
		assertMatchesScalarReference("(print (linalg::%la-im2col (make-array '(1 1 2 2) :initial-element 1) 2 2 1 0))");
		// ... and the fallback reloads the temps of the FIVE-argument call rather than
		// recompiling the argument forms (the widest intercepted call shape).
		String declined = """
				(defparameter *n* 0)
				(defun bump () (setq *n* (+ *n* 1)) (make-array '(1 1 2 2) :initial-element 1))
				(linalg::%la-im2col (bump) 2 2 1 0)
				(print *n*)
				""";
		assertThat(accel(declined)).isEqualTo("1");
		String accepted = """
				(defparameter *n* 0)
				(defun bump () (setq *n* (+ *n* 1)) 0)
				(linalg::%la-im2col (linalg:reshape (linalg:arange 4) '(1 1 2 2)) 2 2 1 (bump))
				(print *n*)
				""";
		assertThat(accel(accepted)).isEqualTo("1");
	}

}
