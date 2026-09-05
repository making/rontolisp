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
import am.ik.rontolisp.compiler.OptimizeLevel;
import am.ik.rontolisp.eval.LinalgLibrary;
import am.ik.rontolisp.eval.VecLibrary;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The {@code --parallel} JVM path: a {@code --simd} build whose GEMV / GEMM call sites
 * ({@code vec:matvec}, {@code vec:matvec-into}, {@code linalg:dot} over a matrix, the
 * stacked {@code linalg:matmul}) bind to the bridge entries that split their rows across
 * {@code RONTOLISP_THREADS} threads. The contract is bit-identity with the serial
 * {@code --simd} build -- the rows are independent chains, so which thread runs which row
 * cannot change a result -- so every case here compares the printed output of the same
 * program compiled both ways, at shapes ABOVE the work threshold (where the split really
 * happens, on this test JVM's processor count) and over inexact data (where a changed
 * fold would show). The reductions are never split and are not rebound.
 */
class JvmSimdParallelCompilerTest {

	@TempDir
	Path tempDir;

	private byte[] compile(String lispCode, boolean parallel, boolean gpu) {
		List<LispVal> program = VecLibrary.process(LinalgLibrary.process(LispReader.readAllFromString(lispCode)));
		return new JvmLispCompiler("Test", false, OptimizeLevel.NONE, true, false, gpu, parallel).compile(program);
	}

	private String run(byte[] classBytes) throws Exception {
		Path classFile = this.tempDir.resolve("Test.class");
		Files.write(classFile, classBytes);
		try (URLClassLoader loader = new URLClassLoader(new URL[] { this.tempDir.toUri().toURL() },
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

	/**
	 * Asserts the --simd --parallel class prints exactly what the --simd class prints.
	 */
	private void assertMatchesSerial(String lispCode) throws Exception {
		String serial = run(compile(lispCode, false, false));
		assertThat(serial).as("the program prints something").isNotEmpty();
		assertThat(run(compile(lispCode, true, false))).as(lispCode).isEqualTo(serial);
	}

	/**
	 * A {@code rows x cols} matrix of inexact single or double floats ({@code sqrt} of
	 * the index, so every lane fold rounds) and a matching vector, bound to {@code *w*}
	 * and {@code *x*}. 600 x 300 is 180000 multiply-adds, above the 2^17 threshold.
	 */
	private static String inexact(int rows, int cols, String option) {
		return """
				(defparameter *w* (linalg:reshape (linalg:sqrt (linalg:arange 1 %d%s)) '(%d %d)))
				(defparameter *x* (linalg:sqrt (linalg:arange 2 %d%s)))
				""".formatted(rows * cols + 1, option, rows, cols, cols + 2, option);
	}

	private static final String DOUBLE = "";

	private static final String SINGLE = " :element-type 'single-float";

	@Test
	void theMatrixByVectorProductIsBitIdenticalToTheSerialKernelAtBothWidths() throws Exception {
		for (String option : new String[] { DOUBLE, SINGLE }) {
			assertMatchesSerial(inexact(600, 300, option) + "(print (linalg:to-list (vec:matvec *w* *x*)))");
			// Many short rows: the row grain is the bound, and the leaves are numerous.
			assertMatchesSerial(inexact(4000, 130, option) + "(print (linalg:to-list (vec:matvec *w* *x*)))");
			// Few long rows: fewer leaves than threads, and the 2^17 bound is just met.
			assertMatchesSerial(inexact(4, 40000, option) + "(print (linalg:to-list (vec:matvec *w* *x*)))");
		}
	}

	@Test
	void theDestinationPassingProductWritesTheCallersRowsBitIdentically() throws Exception {
		for (String option : new String[] { DOUBLE, SINGLE }) {
			assertMatchesSerial(inexact(600, 300, option) + """
					(defparameter *out* (linalg:zeros '(600)%s))
					(vec:matvec-into *out* *w* *x*)
					(print (linalg:to-list *out*))
					""".formatted(option));
		}
	}

	/**
	 * A 600x300 bfloat16 matrix, its EXACT f32 widening and an f32 activation vector, all
	 * from a deterministic LCG so no value is exact at either width. Above the 2^17 work
	 * threshold, so the rows really are split.
	 */
	private static final String BF16_FIXTURE = """
			(defparameter *wb* (make-array '(600 300) :element-type 'bfloat16 :initial-element 0.0))
			(defparameter *wf* (make-array '(600 300) :element-type 'single-float :initial-element 0.0))
			(defparameter *x* (make-array 300 :element-type 'single-float :initial-element 0.0))
			(let ((s 1))
			  (dotimes (i 600)
			    (dotimes (j 300)
			      (setq s (mod (+ (* s 1103515245) 12345) 2147483648))
			      (setf (aref *wb* i j) (- (/ s 1073741824.0) 1.0))
			      (setf (aref *wf* i j) (aref *wb* i j))))
			  (dotimes (j 300)
			    (setq s (mod (+ (* s 1103515245) 12345) 2147483648))
			    (setf (aref *x* j) (- (/ s 1073741824.0) 1.0))))
			""";

	@Test
	void theFusedBf16ProductIsBitIdenticalSerialParallelAndAgainstTheWidenedF32() throws Exception {
		// The bf16 arm inherits the row split unchanged: it is the same row chains, so
		// the same bits, whichever thread runs which row. And it stays the f32 kernel's
		// answer over the widened matrix, which is the fused kernels' whole contract.
		String product = BF16_FIXTURE + "(print (linalg:to-list (vec:matvec *wb* *x*)))";
		assertMatchesSerial(product);
		String into = BF16_FIXTURE + """
				(defparameter *out* (make-array 600 :element-type 'single-float :initial-element 0.0))
				(vec:matvec-into *out* *wb* *x*)
				(print (linalg:to-list *out*))
				""";
		assertMatchesSerial(into);
		assertThat(run(compile(product, true, false)))
			.isEqualTo(run(compile(BF16_FIXTURE + "(print (linalg:to-list (vec:matvec *wf* *x*)))", true, false)));
		// The --gpu chain (compileMatvecChain) emits the same width guard: no device
		// carries a bfloat16 kernel, so the weights fall past it onto the fused lanes.
		assertThat(run(compile(product, true, true))).isEqualTo(run(compile(product, false, false)));
	}

	@Test
	void theLinalgProductsAreBitIdenticalToTheSerialKernelsAtBothWidths() throws Exception {
		for (String option : new String[] { DOUBLE, SINGLE }) {
			// linalg:dot, matrix by vector: vec:matvec's kernel through the linalg: seam.
			assertMatchesSerial(inexact(600, 300, option) + "(print (linalg:to-list (linalg:dot *w* *x*)))");
			// linalg:dot, matrix by matrix: the ikj GEMM, output rows split. 300x600 by
			// 600x40 is 7.2M multiply-adds over 300 rows.
			assertMatchesSerial(inexact(600, 300, option) + """
					(defparameter *b* (linalg:reshape (linalg:sqrt (linalg:arange 3 %d%s)) '(600 40)))
					(print (linalg:to-list (linalg:dot (linalg:transpose *w*) *b*)))
					""".formatted(600 * 40 + 3, option));
			// The stacked product: rows counted across the whole stack, a batch boundary
			// inside a leaf. 8 batches of 50x300 by 300x40.
			assertMatchesSerial(inexact(400, 300, option) + """
					(defparameter *b* (linalg:reshape (linalg:sqrt (linalg:arange 3 %d%s)) '(300 40)))
					(print (linalg:to-list (linalg:flatten (linalg:matmul (linalg:reshape *w* '(8 50 300)) *b*))))
					""".formatted(300 * 40 + 3, option));
		}
	}

	@Test
	void belowTheThresholdAndOnOneRowNothingIsSplitAndTheOutputIsUnchanged() throws Exception {
		// The small shapes every other --simd test pins stay serial under the flag -- and
		// a one-row matrix, however long, has nothing to split.
		assertMatchesSerial("(print (vec:matvec #d((1 2 3) (4 5 6)) #d(1 2 3)))");
		assertMatchesSerial(inexact(1, 200000, DOUBLE) + "(print (linalg:to-list (vec:matvec *w* *x*)))");
		assertMatchesSerial("(print (linalg:dot (linalg:reshape (linalg:arange 600) '(3 200)) "
				+ "(linalg:reshape (linalg:arange 600) '(200 3))))");
	}

	@Test
	void theFlagBindsTheParallelEntriesAndOnlyThose() throws Exception {
		// The emitted bytes differ by method names alone: the --parallel build calls the
		// *Parallel entries for the four members, the --simd build never names them, and
		// neither touches a reduction's binding.
		String program = "(print (vec:matvec #d((1 2) (3 4)) #d(5 6))) (print (vec:sum #d(1 2)))"
				+ " (print (linalg:dot (linalg:eye 2) (linalg:eye 2)))"
				+ " (print (linalg:matmul (linalg:ones '(2 2 2)) (linalg:ones '(2 2 2))))";
		String parallel = new String(compile(program, true, false), StandardCharsets.ISO_8859_1);
		String serial = new String(compile(program, false, false), StandardCharsets.ISO_8859_1);
		assertThat(parallel).contains("simdMatvecParallel").contains("laDotParallel").contains("laMatmulNdParallel");
		assertThat(serial).doesNotContain("Parallel");
		assertThat(parallel).doesNotContain("simdSumParallel");
		assertThat(run(compile(program, true, false))).isEqualTo(run(compile(program, false, false)));
	}

	@Test
	void parallelWithoutSimdIsRefusedBecauseThereIsNothingToSplit() {
		assertThatThrownBy(() -> new JvmLispCompiler("Test", false, OptimizeLevel.NONE, false, false, false, true))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("--parallel splits the --simd kernels across threads, so it needs --simd");
	}

	@Test
	void underGpuTheParallelLanesSitBelowTheDeviceDecision() throws Exception {
		// The --gpu chain is device -> lane kernel; with --parallel the lane kernel is
		// the
		// row-parallel one, and the device decision stays on the calling thread (the
		// residency library is not thread-safe). A matrix the device sees for the first
		// time declines on every machine, so this output is the parallel lanes' on a box
		// with a device and without one, and equal to the serial --simd output.
		String program = inexact(600, 300, DOUBLE) + "(print (linalg:to-list (vec:matvec *w* *x*)))";
		byte[] chained = compile(program, true, true);
		assertThat(new String(chained, StandardCharsets.ISO_8859_1)).contains("simdMatvecParallel")
			.contains("RontoLispGpuBridge");
		assertThat(run(chained)).isEqualTo(run(compile(program, false, false)));
	}

}
