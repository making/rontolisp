import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.util.Random;

/**
 * What does a threaded CBLAS cost a call that is too short to amortize its barrier?
 *
 * Two things a back-to-back microbenchmark cannot tell you, and this one can:
 *
 *   1. what the library says about its own thread pool, through the OPTIONAL symbols
 *      --blas asks (openblas_get_num_threads, mkl_get_max_threads);
 *   2. what ONE call costs when the caller does other work in between -- the shape every
 *      real loop has. A pthread pool that never idles never pays its wake-up, so a
 *      hot loop flatters the threaded column by an order of magnitude.
 *
 * Run it twice, as launched and with the library capped, and read the two tables against
 * each other:
 *
 *   java --enable-native-access=ALL-UNNAMED ThreadBarrierProbe.java
 *   OPENBLAS_NUM_THREADS=1 java --enable-native-access=ALL-UNNAMED ThreadBarrierProbe.java
 *
 * PROBE_BLAS (or RONTOLISP_BLAS) names a library other than the platform default.
 */
public class ThreadBarrierProbe {
	static final Linker L = Linker.nativeLinker();
	static final ValueLayout.OfInt I = ValueLayout.JAVA_INT;
	static final ValueLayout.OfFloat F = ValueLayout.JAVA_FLOAT;
	static final ValueLayout.OfDouble D = ValueLayout.JAVA_DOUBLE;
	static final AddressLayout P = ValueLayout.ADDRESS;
	static final int ROW = 101, NT = 111;
	static MethodHandle sgemv, dgemv, sgemm;
	static double sink;

	/** The library to time: see the class comment. */
	static String library() {
		for (String name : new String[] { "PROBE_BLAS", "RONTOLISP_BLAS" }) {
			String value = System.getenv(name);
			if (value != null && !value.isEmpty()) {
				return value;
			}
		}
		return System.getProperty("os.name", "").contains("Mac")
				? "/System/Library/Frameworks/Accelerate.framework/Accelerate" : "libopenblas.so.0";
	}

	public static void main(String[] args) throws Throwable {
		var lookup = SymbolLookup.libraryLookup(library(), Arena.global());
		sgemv = L.downcallHandle(lookup.find("cblas_sgemv").orElseThrow(),
				FunctionDescriptor.ofVoid(I, I, I, I, F, P, I, P, I, F, P, I), Linker.Option.critical(true));
		dgemv = L.downcallHandle(lookup.find("cblas_dgemv").orElseThrow(),
				FunctionDescriptor.ofVoid(I, I, I, I, D, P, I, P, I, D, P, I), Linker.Option.critical(true));
		sgemm = L.downcallHandle(lookup.find("cblas_sgemm").orElseThrow(),
				FunctionDescriptor.ofVoid(I, I, I, I, I, I, F, P, I, P, I, F, P, I), Linker.Option.critical(true));
		System.out.println("library: " + library());
		// The thread queries --blas looks for, and the setters the flag that was NOT
		// built would have needed. All optional: a library that exports none says nothing.
		for (String symbol : new String[] { "openblas_get_num_threads", "openblas_set_num_threads",
				"openblas_get_parallel", "mkl_get_max_threads", "MKL_Get_Max_Threads", "mkl_set_num_threads",
				"bli_thread_get_num_threads" }) {
			System.out.println("  " + (lookup.find(symbol).isPresent() ? "yes " : "no  ") + symbol);
		}
		for (String symbol : new String[] { "openblas_get_num_threads", "mkl_get_max_threads", "MKL_Get_Max_Threads" }) {
			MemorySegment found = lookup.find(symbol).orElse(null);
			if (found != null) {
				MethodHandle query = L.downcallHandle(found, FunctionDescriptor.of(I));
				System.out.println("threads: " + (int) query.invokeExact() + "  (" + symbol + ")");
				break;
			}
		}
		System.out.printf("%n%-30s %14s %14s%n", "one call, work in between", "flops", "per call");
		for (int[] shape : new int[][] { { 288, 288 }, { 768, 288 }, { 4096, 288 }, { 32000, 288 } }) {
			gemv32(shape[0], shape[1]);
		}
		gemv64(256, 256);
		for (int n : new int[] { 64, 128, 256, 512, 1024 }) {
			gemm32(n);
		}
		System.out.println("(sink " + sink + ")");
	}

	/** ~200 us of unrelated work: what a real loop does between two library calls. */
	static void other() {
		double s = 0;
		for (int i = 1; i < 120_000; i++) {
			s += 1.0 / i;
		}
		sink += s;
	}

	static void gemv32(int rows, int cols) throws Throwable {
		Random r = new Random(3);
		float[] w = new float[rows * cols], x = new float[cols], y = new float[rows];
		for (int i = 0; i < w.length; i++) {
			w[i] = (float) r.nextGaussian();
		}
		for (int i = 0; i < cols; i++) {
			x[i] = (float) r.nextGaussian();
		}
		var sw = MemorySegment.ofArray(w);
		var sx = MemorySegment.ofArray(x);
		var sy = MemorySegment.ofArray(y);
		for (int i = 0; i < 200; i++) {
			sgemv.invokeExact(ROW, NT, rows, cols, 1.0f, sw, cols, sx, 1, 0.0f, sy, 1);
			other();
		}
		long total = 0;
		int reps = 400;
		for (int i = 0; i < reps; i++) {
			other();
			long t0 = System.nanoTime();
			sgemv.invokeExact(ROW, NT, rows, cols, 1.0f, sw, cols, sx, 1, 0.0f, sy, 1);
			total += System.nanoTime() - t0;
		}
		System.out.printf("sgemv %-24s %14d %11.1f us%n", rows + "x" + cols, 2L * rows * cols, total / 1e3 / reps);
	}

	static void gemv64(int rows, int cols) throws Throwable {
		Random r = new Random(3);
		double[] w = new double[rows * cols], x = new double[cols], y = new double[rows];
		for (int i = 0; i < w.length; i++) {
			w[i] = r.nextGaussian();
		}
		for (int i = 0; i < cols; i++) {
			x[i] = r.nextGaussian();
		}
		var sw = MemorySegment.ofArray(w);
		var sx = MemorySegment.ofArray(x);
		var sy = MemorySegment.ofArray(y);
		for (int i = 0; i < 200; i++) {
			dgemv.invokeExact(ROW, NT, rows, cols, 1.0, sw, cols, sx, 1, 0.0, sy, 1);
			other();
		}
		long total = 0;
		int reps = 400;
		for (int i = 0; i < reps; i++) {
			other();
			long t0 = System.nanoTime();
			dgemv.invokeExact(ROW, NT, rows, cols, 1.0, sw, cols, sx, 1, 0.0, sy, 1);
			total += System.nanoTime() - t0;
		}
		System.out.printf("dgemv %-24s %14d %11.1f us%n", rows + "x" + cols, 2L * rows * cols, total / 1e3 / reps);
	}

	static void gemm32(int n) throws Throwable {
		Random r = new Random(3);
		float[] a = new float[n * n], b = new float[n * n], c = new float[n * n];
		for (int i = 0; i < a.length; i++) {
			a[i] = (float) r.nextGaussian();
			b[i] = (float) r.nextGaussian();
		}
		var sa = MemorySegment.ofArray(a);
		var sb = MemorySegment.ofArray(b);
		var sc = MemorySegment.ofArray(c);
		for (int i = 0; i < 20; i++) {
			sgemm.invokeExact(ROW, NT, NT, n, n, n, 1.0f, sa, n, sb, n, 0.0f, sc, n);
			other();
		}
		int reps = Math.max(20, (int) (400_000_000L / (2L * n * n * n)));
		long total = 0;
		for (int i = 0; i < reps; i++) {
			other();
			long t0 = System.nanoTime();
			sgemm.invokeExact(ROW, NT, NT, n, n, n, 1.0f, sa, n, sb, n, 0.0f, sc, n);
			total += System.nanoTime() - t0;
		}
		System.out.printf("sgemm %-24s %14d %11.1f us%n", n + "x" + n + "x" + n, 2L * n * n * n, total / 1e3 / reps);
	}
}
