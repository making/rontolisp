package am.ik.rontolisp.codegen.jvm;

import java.lang.foreign.AddressLayout;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

import org.jspecify.annotations.Nullable;

/**
 * The CBLAS bridge injected into a compiled {@code .class} when the {@code --blas} flag
 * is passed: {@code linalg:dot} -- and through it {@code linalg:matmul} at rank
 * {@code <= 2} -- is lowered to a call on {@link #blasDot}, which finds a TUNED CBLAS in
 * the operating system and runs the product through its {@code gemm} / {@code gemv}.
 *
 * <p>
 * The compiled sibling of {@code eval/LinalgBlasKernels}, and deliberately a COPY of it
 * rather than a call into it: like {@link JvmSimdVectorTemplate} and
 * {@link JavaBridgeTemplate} this class's bytecode is read from the classpath by
 * {@link JvmBlasRuntimeBuilder}, renamed into the default package, base64-embedded and
 * defined at first use, so the output stays a single self-contained {@code .class} and
 * the bytes must stand alone. The candidate list, the marker rule, the size threshold and
 * the two environment variables are therefore mirrored, and the reasoning behind each of
 * them lives once, in {@code eval/LinalgBlasKernels} and {@code .kb/linalg-blas.md};
 * change them together.
 *
 * <p>
 * A packed float array here is the compiled representation: a bare {@code double[]} or
 * {@code float[]} carrying the dimension header {@code [rank, dim_0, ..., dim_{rank-1}]}
 * ahead of the elements, so the elements start at {@code 1 + rank}. Heap segments go
 * straight into the library through a {@code critical} downcall, so nothing is copied and
 * the header simply is not part of the slice.
 *
 * <p>
 * {@link #blasDot} is PARTIAL, exactly like the {@code --simd} kernels: it returns
 * {@code null} for a product it does not handle -- no tuned library on the machine, a
 * general (boxed) array, mixed widths, a shape mismatch, a product too small to pay for
 * the call -- and the emitted call site then runs whatever is below it, the
 * {@code --simd} bridge when that was emitted too and the scalar {@code linalg.lisp}
 * defun otherwise.
 *
 * <p>
 * Design constraints (as for {@link JavaBridgeTemplate}): no nested classes or records
 * and no references to other rontolisp classes.
 */
final class JvmBlasTemplate {

	private JvmBlasTemplate() {
	}

	/** The libraries probed, in preference order (mirrors {@code LinalgBlasKernels}). */
	private static final String[] CANDIDATES = { "/System/Library/Frameworks/Accelerate.framework/Accelerate",
			"libnvpl_blas_lp64_gomp.so.0", "libnvpl_blas_lp64_seq.so.0", "libopenblas.so.0", "libopenblas.so",
			"libmkl_rt.so.2", "libmkl_rt.so", "libblas.so.3", "libcblas.so.3", "libblas.so" };

	/** Symbols a tuned implementation exports and the netlib reference does not. */
	private static final String[][] MARKERS = { { "openblas_get_config", "OpenBLAS" },
			{ "mkl_get_version", "Intel MKL" }, { "MKL_Get_Version", "Intel MKL" },
			{ "bli_info_get_version_str", "BLIS" }, { "ATL_buildinfo", "ATLAS" },
			{ "nvpl_blas_get_version", "NVIDIA NVPL" }, { "armpl_get_version", "Arm Performance Libraries" } };

	private static final long MIN_WORK = 64;

	private static final long CRITICAL_FLOP_CEILING = 1L << 32;

	private static final ValueLayout.OfInt I = ValueLayout.JAVA_INT;

	private static final ValueLayout.OfFloat F = ValueLayout.JAVA_FLOAT;

	private static final ValueLayout.OfDouble D = ValueLayout.JAVA_DOUBLE;

	private static final AddressLayout P = ValueLayout.ADDRESS;

	private static final int ROW_MAJOR = 101, NO_TRANS = 111, TRANS = 112;

	private static final @Nullable MethodHandle DGEMM, SGEMM, DGEMV, SGEMV, DGEMM_STAGED, SGEMM_STAGED;

	static {
		MethodHandle dgemm = null, sgemm = null, dgemv = null, sgemv = null, dgemmStaged = null, sgemmStaged = null;
		String description = "no tuned CBLAS found";
		try {
			Linker linker = Linker.nativeLinker();
			FunctionDescriptor gemmD = FunctionDescriptor.ofVoid(I, I, I, I, I, I, D, P, I, P, I, D, P, I);
			FunctionDescriptor gemmF = FunctionDescriptor.ofVoid(I, I, I, I, I, I, F, P, I, P, I, F, P, I);
			FunctionDescriptor gemvD = FunctionDescriptor.ofVoid(I, I, I, I, D, P, I, P, I, D, P, I);
			FunctionDescriptor gemvF = FunctionDescriptor.ofVoid(I, I, I, I, F, P, I, P, I, F, P, I);
			Linker.Option critical = Linker.Option.critical(true);
			String forced = System.getenv("RONTOLISP_BLAS");
			if (forced != null && forced.isEmpty()) {
				forced = null;
			}
			String[] candidates = forced != null ? new String[] { forced } : CANDIDATES;
			for (String candidate : candidates) {
				SymbolLookup lookup;
				MemorySegment gemm;
				try {
					lookup = SymbolLookup.libraryLookup(candidate, Arena.global());
					gemm = lookup.find("cblas_dgemm").orElseThrow();
				}
				catch (RuntimeException ex) {
					continue;
				}
				String identity = forced != null ? "named by RONTOLISP_BLAS" : identify(candidate, lookup);
				if (identity == null) {
					continue;
				}
				dgemm = linker.downcallHandle(gemm, gemmD, critical);
				dgemmStaged = linker.downcallHandle(gemm, gemmD);
				MemorySegment sgemmSym = lookup.find("cblas_sgemm").orElseThrow();
				sgemm = linker.downcallHandle(sgemmSym, gemmF, critical);
				sgemmStaged = linker.downcallHandle(sgemmSym, gemmF);
				dgemv = linker.downcallHandle(lookup.find("cblas_dgemv").orElseThrow(), gemvD, critical);
				sgemv = linker.downcallHandle(lookup.find("cblas_sgemv").orElseThrow(), gemvF, critical);
				description = candidate + " (" + identity + ")";
				break;
			}
		}
		catch (Throwable ex) {
			dgemm = null;
			sgemm = null;
			dgemv = null;
			sgemv = null;
			dgemmStaged = null;
			sgemmStaged = null;
			description = "the foreign function API is unavailable: " + ex;
		}
		DGEMM = dgemm;
		SGEMM = sgemm;
		DGEMV = dgemv;
		SGEMV = sgemv;
		DGEMM_STAGED = dgemmStaged;
		SGEMM_STAGED = sgemmStaged;
		String verbose = System.getenv("RONTOLISP_BLAS_VERBOSE");
		if (verbose != null && !verbose.isEmpty()) {
			System.err.println("rontolisp: --blas " + (dgemm != null ? "bound " : "declined: ") + description);
		}
	}

	private static @Nullable String identify(String candidate, SymbolLookup lookup) {
		if (candidate.contains("Accelerate.framework")) {
			return "Apple Accelerate";
		}
		for (String[] marker : MARKERS) {
			if (lookup.find(marker[0]).isPresent()) {
				return marker[1] + ", exports " + marker[0];
			}
		}
		return null;
	}

	/**
	 * {@code (linalg:dot a b)} over two packed operands: matrix by matrix (gemm), matrix
	 * by vector and vector by matrix (gemv, the second transposed). Anything else -- a
	 * vector-by-vector dot, which is memory-bound, included -- returns {@code null} and
	 * declines.
	 */
	static @Nullable Object blasDot(@Nullable Object a, @Nullable Object b) {
		if (DGEMM == null || !packed(a) || !packed(b)) {
			return null;
		}
		boolean single = a instanceof float[];
		if (single != (b instanceof float[])) {
			return null;
		}
		int ra = rank(a);
		int rb = rank(b);
		if (ra == 2 && rb == 2) {
			int n = dim(a, 0);
			int m = dim(a, 1);
			int p = dim(b, 1);
			if (m != dim(b, 0) || (long) n * m * p < MIN_WORK) {
				return null;
			}
			if (single) {
				float[] c = newMatF(n, p);
				gemmF(floats(a), 3, floats(b), 3, c, 3, n, m, p);
				return c;
			}
			double[] c = newMat(n, p);
			gemm(doubles(a), 3, doubles(b), 3, c, 3, n, m, p);
			return c;
		}
		if (ra == 2 && rb == 1) {
			int rows = dim(a, 0);
			int cols = dim(a, 1);
			if (cols != dim(b, 0) || (long) rows * cols < MIN_WORK) {
				return null;
			}
			return matvec(a, b, rows, cols, rows, false, single);
		}
		if (ra == 1 && rb == 2) {
			// A row vector times a matrix contracts b's FIRST axis, which is b^T x.
			int rows = dim(b, 0);
			int cols = dim(b, 1);
			if (dim(a, 0) != rows || (long) rows * cols < MIN_WORK) {
				return null;
			}
			return matvec(b, a, rows, cols, cols, true, single);
		}
		return null;
	}

	/**
	 * {@code (vec:matvec w x)} over the packed representation: a rank-2 matrix by a
	 * rank-1 vector of the same width, answered as a fresh rank-1 array. The {@code vec:}
	 * sibling of {@link #blasDot}'s matrix-by-vector case, and PARTIAL for the same
	 * reasons -- but over a call site whose lower rungs are TOTAL, so a decline here is
	 * not "run the library instead", it is "run the lane kernel or the scalar defun,
	 * which will produce the answer or the error".
	 */
	static @Nullable Object blasMatvec(@Nullable Object w, @Nullable Object x) {
		if (!vecShape(w, x)) {
			return null;
		}
		int rows = dim(w, 0);
		int cols = dim(w, 1);
		if (w instanceof float[]) {
			float[] y = newVecF(rows);
			gemvF(floats(w), 3, rows, cols, floats(x), 2, y, 2, false);
			return y;
		}
		double[] y = newVec(rows);
		gemv(doubles(w), 3, rows, cols, doubles(x), 2, y, 2, false);
		return y;
	}

	/**
	 * {@code (vec:matvec-into out w x)}: the same product written into a caller-supplied
	 * destination, which is what {@code cblas_?gemv} does natively -- so this form drops
	 * the result allocation as well as the loop, and answers {@code out} itself. An
	 * {@code out} that shares storage with {@code w} or {@code x} declines: each output
	 * element folds over all of {@code x}, and the lower rung signals that for us.
	 */
	static @Nullable Object blasMatvecInto(@Nullable Object out, @Nullable Object w, @Nullable Object x) {
		if (!vecShape(w, x) || !packed(out) || (out instanceof float[]) != (w instanceof float[]) || rank(out) != 1) {
			return null;
		}
		int rows = dim(w, 0);
		int cols = dim(w, 1);
		if (dim(out, 0) != rows || out == w || out == x) {
			return null;
		}
		if (out instanceof float[] y) {
			gemvF(floats(w), 3, rows, cols, floats(x), 2, y, 2, false);
			return y;
		}
		double[] y = doubles(out);
		gemv(doubles(w), 3, rows, cols, doubles(x), 2, y, 2, false);
		return y;
	}

	/**
	 * The operand shape both {@code vec:} entry points require: a packed rank-2 matrix
	 * and a packed rank-1 vector of the same width whose extent matches, over a library
	 * that was bound, and big enough to pay for the downcall.
	 */
	private static boolean vecShape(@Nullable Object w, @Nullable Object x) {
		if (DGEMV == null || !packed(w) || !packed(x) || (w instanceof float[]) != (x instanceof float[])
				|| rank(w) != 2 || rank(x) != 1) {
			return false;
		}
		int rows = dim(w, 0);
		int cols = dim(w, 1);
		return dim(x, 0) == cols && (long) rows * cols >= MIN_WORK;
	}

	private static Object matvec(@Nullable Object matrix, @Nullable Object vector, int rows, int cols, int out,
			boolean transposed, boolean single) {
		if (single) {
			float[] y = newVecF(out);
			gemvF(floats(matrix), 3, rows, cols, floats(vector), 2, y, 2, transposed);
			return y;
		}
		double[] y = newVec(out);
		gemv(doubles(matrix), 3, rows, cols, doubles(vector), 2, y, 2, transposed);
		return y;
	}

	// --- the library calls ------------------------------------------------------------

	private static void gemm(double[] a, int oa, double[] b, int ob, double[] c, int oc, int n, int m, int p) {
		try {
			if (2L * n * m * p <= CRITICAL_FLOP_CEILING) {
				java.util.Objects.requireNonNull(DGEMM)
					.invokeExact(ROW_MAJOR, NO_TRANS, NO_TRANS, n, p, m, 1.0, slice(a, oa), m, slice(b, ob), p, 0.0,
							slice(c, oc), p);
				return;
			}
			try (Arena arena = Arena.ofConfined()) {
				MemorySegment sa = stage(arena, a, oa, (long) n * m);
				MemorySegment sb = stage(arena, b, ob, (long) m * p);
				MemorySegment sc = arena.allocate(D, (long) n * p);
				java.util.Objects.requireNonNull(DGEMM_STAGED)
					.invokeExact(ROW_MAJOR, NO_TRANS, NO_TRANS, n, p, m, 1.0, sa, m, sb, p, 0.0, sc, p);
				MemorySegment.copy(sc, D, 0, c, oc, n * p);
			}
		}
		catch (Throwable ex) {
			throw rethrow(ex);
		}
	}

	private static void gemmF(float[] a, int oa, float[] b, int ob, float[] c, int oc, int n, int m, int p) {
		try {
			if (2L * n * m * p <= CRITICAL_FLOP_CEILING) {
				java.util.Objects.requireNonNull(SGEMM)
					.invokeExact(ROW_MAJOR, NO_TRANS, NO_TRANS, n, p, m, 1.0f, slice(a, oa), m, slice(b, ob), p, 0.0f,
							slice(c, oc), p);
				return;
			}
			try (Arena arena = Arena.ofConfined()) {
				MemorySegment sa = stage(arena, a, oa, (long) n * m);
				MemorySegment sb = stage(arena, b, ob, (long) m * p);
				MemorySegment sc = arena.allocate(F, (long) n * p);
				java.util.Objects.requireNonNull(SGEMM_STAGED)
					.invokeExact(ROW_MAJOR, NO_TRANS, NO_TRANS, n, p, m, 1.0f, sa, m, sb, p, 0.0f, sc, p);
				MemorySegment.copy(sc, F, 0, c, oc, n * p);
			}
		}
		catch (Throwable ex) {
			throw rethrow(ex);
		}
	}

	private static void gemv(double[] a, int oa, int rows, int cols, double[] x, int ox, double[] y, int oy,
			boolean transposed) {
		// Hoisted rather than written inline: a conditional in an invokeExact argument
		// list crashes the build's NullAway generics check.
		int trans = transposed ? TRANS : NO_TRANS;
		try {
			java.util.Objects.requireNonNull(DGEMV)
				.invokeExact(ROW_MAJOR, trans, rows, cols, 1.0, slice(a, oa), cols, slice(x, ox), 1, 0.0, slice(y, oy),
						1);
		}
		catch (Throwable ex) {
			throw rethrow(ex);
		}
	}

	private static void gemvF(float[] a, int oa, int rows, int cols, float[] x, int ox, float[] y, int oy,
			boolean transposed) {
		int trans = transposed ? TRANS : NO_TRANS;
		try {
			java.util.Objects.requireNonNull(SGEMV)
				.invokeExact(ROW_MAJOR, trans, rows, cols, 1.0f, slice(a, oa), cols, slice(x, ox), 1, 0.0f,
						slice(y, oy), 1);
		}
		catch (Throwable ex) {
			throw rethrow(ex);
		}
	}

	private static MemorySegment slice(double[] array, int offset) {
		return MemorySegment.ofArray(array).asSlice((long) offset * Double.BYTES);
	}

	private static MemorySegment slice(float[] array, int offset) {
		return MemorySegment.ofArray(array).asSlice((long) offset * Float.BYTES);
	}

	private static MemorySegment stage(Arena arena, double[] array, int offset, long count) {
		MemorySegment segment = arena.allocate(D, count);
		MemorySegment.copy(array, offset, segment, D, 0, (int) count);
		return segment;
	}

	private static MemorySegment stage(Arena arena, float[] array, int offset, long count) {
		MemorySegment segment = arena.allocate(F, count);
		MemorySegment.copy(array, offset, segment, F, 0, (int) count);
		return segment;
	}

	private static RuntimeException rethrow(Throwable ex) {
		if (ex instanceof RuntimeException runtime) {
			return runtime;
		}
		if (ex instanceof Error error) {
			throw error;
		}
		return new IllegalStateException(ex);
	}

	// --- the compiled packed float-array representation --------------------------------

	private static boolean packed(@Nullable Object o) {
		return o instanceof double[] || o instanceof float[];
	}

	private static int rank(@Nullable Object o) {
		return o instanceof float[] f ? (int) f[0] : (int) ((double[]) java.util.Objects.requireNonNull(o))[0];
	}

	private static int dim(@Nullable Object o, int i) {
		return o instanceof float[] f ? (int) f[1 + i] : (int) ((double[]) java.util.Objects.requireNonNull(o))[1 + i];
	}

	private static double[] doubles(@Nullable Object o) {
		return (double[]) java.util.Objects.requireNonNull(o);
	}

	private static float[] floats(@Nullable Object o) {
		return (float[]) java.util.Objects.requireNonNull(o);
	}

	private static double[] newMat(int rows, int cols) {
		double[] m = new double[3 + rows * cols];
		m[0] = 2.0;
		m[1] = rows;
		m[2] = cols;
		return m;
	}

	private static float[] newMatF(int rows, int cols) {
		float[] m = new float[3 + rows * cols];
		m[0] = 2.0f;
		m[1] = rows;
		m[2] = cols;
		return m;
	}

	private static double[] newVec(int n) {
		double[] r = new double[2 + n];
		r[0] = 1.0;
		r[1] = n;
		return r;
	}

	private static float[] newVecF(int n) {
		float[] r = new float[2 + n];
		r[0] = 1.0f;
		r[1] = n;
		return r;
	}

}
