package am.ik.rontolisp.eval;

import java.lang.foreign.AddressLayout;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.jspecify.annotations.Nullable;

/**
 * The CBLAS binding behind {@code --blas}: finds a TUNED CBLAS in the operating system,
 * verifies that it is one, and calls its {@code gemm} / {@code gemv} straight over the
 * packed float arrays. Reached only through {@link LinalgBlas}, which is what makes
 * {@code src/web/java/.../Target_LinalgBlas.java} enough to keep
 * {@code java.lang.foreign} out of the browser Web Image build.
 *
 * <h2>Finding one is not the hard part; refusing the wrong one is</h2>
 *
 * CBLAS is a single C ABI, so the same two downcall handles reach Apple Accelerate,
 * OpenBLAS, MKL, NVPL and Arm Performance Libraries alike, and a library is either in the
 * OS (macOS) or installed by the user (Linux). But "a CBLAS is present" is not the useful
 * predicate: the netlib REFERENCE implementation exports the same symbols and runs at 7-8
 * GFLOP/s, which is SLOWER than the {@code --simd} kernel rontolisp already has, so
 * binding whatever was found would be a silent regression. Nor does the soname decide it:
 * Debian's {@code libblas.so.3} is an {@code update-alternatives} symlink that points at
 * OpenBLAS when one is installed and at the reference when not.
 *
 * <p>
 * So a candidate is accepted only when it also exports a marker symbol that a tuned
 * implementation has and the reference does not ({@code openblas_get_config},
 * {@code mkl_get_version}, ...), or when it is Accelerate, identified by its framework
 * path. The marker is cheap, deterministic and needs no startup benchmark; the throughput
 * measurement that established the rule is recorded in {@code .kb/linalg-blas.md}, which
 * also names the standalone probe it came from -- kept runnable so the rule can be
 * re-checked on new hardware. {@code RONTOLISP_BLAS} overrides both the search and the
 * marker check for a tuned build this list cannot name.
 *
 * <h2>No copy</h2>
 *
 * The arrays are Java heap {@code double[]} / {@code float[]}, and
 * {@link Linker.Option#critical(boolean) critical} downcalls take heap segments directly,
 * so an intercepted product copies NOTHING -- measured, that is 1.7x on top of the
 * library at n=512 and 10x at n=32 against staging both operands in a native arena. The
 * cost is that a critical call does not transition the thread to native, so the VM cannot
 * reach a safepoint while it runs; above {@link #CRITICAL_FLOP_CEILING} flops the call is
 * long enough for that to matter and cheap enough to absorb a copy (5% at n=2048), so
 * those go through a confined arena instead.
 *
 * <h2>The thread barrier, and the one thing said about it</h2>
 *
 * A tuned library sizes its own thread pool and rontolisp does not touch it -- but a
 * SHORT call cannot amortize the barrier that pool costs, so a program made of small
 * products runs several times slower with the threads than without them, while a program
 * made of large ones runs several times faster with them. Neither the flag nor the
 * machine tells those two apart; only the calls do. So the binding asks the library how
 * many threads it will use ({@link #THREAD_QUERIES}, optional symbols) and
 * {@link #barrierNote} says one thing, once, after the program has actually issued a LOOP
 * of products too small to pay for the barrier. A library with no such symbol, or one
 * already capped, says nothing at all. See {@code .kb/linalg-blas.md}.
 *
 * @see LinalgBlas
 */
final class LinalgBlasKernels {

	private LinalgBlasKernels() {
	}

	/**
	 * The libraries probed, in preference order. The first that loads AND exports
	 * {@code cblas_dgemm} AND identifies as tuned wins.
	 */
	private static final String[] CANDIDATES = {
			// macOS: in the OS, always present, no precondition of any kind.
			"/System/Library/Frameworks/Accelerate.framework/Accelerate",
			// NVIDIA Performance Libraries (Grace), then the usual Linux one, then Intel.
			"libnvpl_blas_lp64_gomp.so.0", "libnvpl_blas_lp64_seq.so.0", "libopenblas.so.0", "libopenblas.so",
			"libmkl_rt.so.2", "libmkl_rt.so",
			// The distro alternatives link, LAST: it points at the reference
			// implementation as often as at a tuned one, and the marker check below is
			// what tells those two apart.
			"libblas.so.3", "libcblas.so.3", "libblas.so" };

	/** Symbols a tuned implementation exports and the netlib reference does not. */
	private static final String[][] MARKERS = { { "openblas_get_config", "OpenBLAS" },
			{ "mkl_get_version", "Intel MKL" }, { "MKL_Get_Version", "Intel MKL" },
			{ "bli_info_get_version_str", "BLIS" }, { "ATL_buildinfo", "ATLAS" },
			{ "nvpl_blas_get_version", "NVIDIA NVPL" }, { "armpl_get_version", "Arm Performance Libraries" } };

	/**
	 * How to ask a tuned library how many threads it will use, and what to set to cap it:
	 * {@code {symbol, library, variable}}. All three symbols are OPTIONAL -- a library
	 * that exports none (Apple Accelerate, BLIS) leaves the thread count unknown and
	 * {@link #barrierNote} then says nothing, which is the behaviour every library had
	 * before this table existed.
	 */
	private static final String[][] THREAD_QUERIES = {
			{ "openblas_get_num_threads", "OpenBLAS", "OPENBLAS_NUM_THREADS" },
			{ "mkl_get_max_threads", "Intel MKL", "MKL_NUM_THREADS" },
			{ "MKL_Get_Max_Threads", "Intel MKL", "MKL_NUM_THREADS" } };

	/** Names the library to bind, overriding both the search and the marker check. */
	static final String LIBRARY_ENV = "RONTOLISP_BLAS";

	/** Prints the bound library to standard error when set to a non-empty value. */
	static final String VERBOSE_ENV = "RONTOLISP_BLAS_VERBOSE";

	private static final String ACCELERATE_MARK = "Accelerate.framework";

	/**
	 * Below this many multiply-adds a product declines: the fixed cost of a critical
	 * downcall is ~30 ns, which a plain scalar triple loop beats up to about 4x4x4.
	 */
	private static final long MIN_WORK = 64;

	/**
	 * Above this many flops ({@code 2*n*m*p}) the operands are staged in a confined arena
	 * instead of being passed as heap segments, so the call transitions to native and the
	 * VM keeps reaching safepoints. 2^32 flops is ~5 ms on a tuned library, and from
	 * there up the staging copy is a few percent of the call.
	 */
	private static final long CRITICAL_FLOP_CEILING = 1L << 32;

	/**
	 * A product of at most this many flops is SHORT: too small for a threaded library to
	 * amortize the barrier it costs per call. Measured on a 64-core Xeon with OpenBLAS,
	 * one call at a time with unrelated work in between (the shape a real loop has, and
	 * the one a back-to-back microbenchmark hides -- a pool that never idles never pays
	 * its wake-up): a 166 Kflop GEMV is 6.8x SLOWER threaded, a 442 Kflop GEMV 1.6x
	 * slower, a 4.2 Mflop GEMM 1.24x faster and a 2.1 Gflop GEMM 6.2x faster. The
	 * crossover sits between 0.4 and 4 Mflop and is the same for both kernels, so it is
	 * the SIZE of the call and not which product it is.
	 */
	private static final long BARRIER_WORK = 1L << 21;

	/**
	 * Short products to see before saying anything: enough that they are a loop rather
	 * than a handful, and below the smallest shipped program that measurably loses to
	 * this ({@code examples/ml/simd-gemv.lisp}, 100 GEMVs, 371 ms threaded against 131 ms
	 * capped).
	 */
	private static final int BARRIER_CALLS = 64;

	private static final ValueLayout.OfInt I = ValueLayout.JAVA_INT;

	private static final ValueLayout.OfFloat F = ValueLayout.JAVA_FLOAT;

	private static final ValueLayout.OfDouble D = ValueLayout.JAVA_DOUBLE;

	private static final AddressLayout P = ValueLayout.ADDRESS;

	/** CBLAS enum constants: row-major storage, and the two transpose modes. */
	private static final int ROW_MAJOR = 101, NO_TRANS = 111, TRANS = 112;

	/** {@code cblas_dgemm} and {@code cblas_sgemm}: the same shape at the two widths. */
	private static final FunctionDescriptor GEMM_D = FunctionDescriptor.ofVoid(I, I, I, I, I, I, D, P, I, P, I, D, P,
			I), GEMM_F = FunctionDescriptor.ofVoid(I, I, I, I, I, I, F, P, I, P, I, F, P, I);

	/** {@code cblas_dgemv} and {@code cblas_sgemv}. */
	private static final FunctionDescriptor GEMV_D = FunctionDescriptor.ofVoid(I, I, I, I, D, P, I, P, I, D, P, I),
			GEMV_F = FunctionDescriptor.ofVoid(I, I, I, I, F, P, I, P, I, F, P, I);

	/** Every {@link #THREAD_QUERIES} entry: {@code int f(void)}. */
	private static final FunctionDescriptor THREAD_COUNT = FunctionDescriptor.of(I);

	/**
	 * Every downcall SHAPE bound below, and the same for the
	 * {@linkplain Linker.Option#critical(boolean) critical} ones -- a product above the
	 * flop ceiling is issued through a plain handle, so a gemm shape is in both sets.
	 * Native Image builds a downcall stub only for a signature registered at BUILD time
	 * and refuses the handle for any other, and refusing one sends the whole block below
	 * down its catch: a shape missing from {@code reachability-metadata.json} makes the
	 * binary report the foreign function API as unavailable on a machine whose tuned
	 * CBLAS is right there. So the shapes are recorded as they are bound, and
	 * {@code LinalgBlasDeclineTest} pins the checked-in metadata against what is actually
	 * asked for rather than against a list someone remembered to update.
	 */
	private static final Set<FunctionDescriptor> SIGNATURES = ConcurrentHashMap.newKeySet(),
			CRITICAL_SIGNATURES = ConcurrentHashMap.newKeySet();

	private static final @Nullable MethodHandle DGEMM, SGEMM, DGEMV, SGEMV, DGEMM_STAGED, SGEMM_STAGED;

	/** What was bound, or why nothing was: the text the CLI reports. */
	private static final String DESCRIPTION;

	/**
	 * How many threads the bound library said it would use, or 0 when it exports no way
	 * to ask. Read once, at bind time, and never written: a 0 or 1 here makes
	 * {@link #note} a constant-folded no-op for the life of the process.
	 */
	private static final int THREADS;

	/** The library and the variable {@link #barrierNote} names, empty when unknown. */
	private static final String THREAD_LIBRARY, THREAD_VARIABLE;

	/** Short products seen so far, until the note is earned. */
	private static final AtomicInteger SHORT_PRODUCTS = new AtomicInteger();

	/** Set once the note has been printed; nothing is counted after that. */
	private static volatile boolean barrierReported;

	static {
		MethodHandle dgemm = null, sgemm = null, dgemv = null, sgemv = null, dgemmStaged = null, sgemmStaged = null;
		String description;
		int threads = 0;
		String threadLibrary = "", threadVariable = "";
		try {
			String forced = env(LIBRARY_ENV);
			String[] candidates = forced != null ? new String[] { forced } : CANDIDATES;
			String bound = null;
			String identity = null;
			for (String candidate : candidates) {
				SymbolLookup lookup;
				try {
					lookup = SymbolLookup.libraryLookup(candidate, Arena.global());
					lookup.find("cblas_dgemm").orElseThrow();
				}
				catch (RuntimeException ex) {
					continue;
				}
				identity = forced != null ? "named by " + LIBRARY_ENV : identify(candidate, lookup);
				if (identity == null) {
					// Present, but nothing says it is tuned: almost certainly the netlib
					// reference, which is slower than the kernel we already have.
					continue;
				}
				Bound handles = bind(lookup);
				dgemm = handles.dgemm();
				dgemmStaged = handles.dgemmStaged();
				sgemm = handles.sgemm();
				sgemmStaged = handles.sgemmStaged();
				dgemv = handles.dgemv();
				sgemv = handles.sgemv();
				ThreadQuery query = handles.threads();
				if (query != null) {
					threads = threadCount(query);
					threadLibrary = query.library();
					threadVariable = query.variable();
				}
				bound = candidate;
				break;
			}
			description = bound != null ? bound + " (" + identity + ")"
					: "no tuned CBLAS found (install one, or name it with " + LIBRARY_ENV + ")";
		}
		catch (Throwable ex) {
			// A runtime with no FFM at all, or one that refuses native access.
			dgemm = null;
			sgemm = null;
			dgemv = null;
			sgemv = null;
			dgemmStaged = null;
			sgemmStaged = null;
			threads = 0;
			threadLibrary = "";
			threadVariable = "";
			description = "the foreign function API is unavailable: " + ex;
		}
		DGEMM = dgemm;
		SGEMM = sgemm;
		DGEMV = dgemv;
		SGEMV = sgemv;
		DGEMM_STAGED = dgemmStaged;
		SGEMM_STAGED = sgemmStaged;
		DESCRIPTION = description;
		THREADS = threads;
		THREAD_LIBRARY = threadLibrary;
		THREAD_VARIABLE = threadVariable;
		if (dgemm != null && env(VERBOSE_ENV) != null) {
			System.err.println("rontolisp: --blas bound " + description + ", " + threads + " threads");
		}
	}

	/**
	 * Asks the library its thread count, or answers 0 when it will not say. Its own
	 * try/catch on purpose: a library that exports the symbol but refuses the call must
	 * cost the acceleration nothing, and the binding around it has already succeeded.
	 */
	private static int threadCount(ThreadQuery query) {
		try {
			return (int) query.count().invokeExact();
		}
		catch (Throwable ex) {
			return 0;
		}
	}

	/** The optional thread-count query of a library that has one. */
	record ThreadQuery(MethodHandle count, String library, String variable) {
	}

	/** The six handles one tuned CBLAS gives, in the order the fields above take them. */
	record Bound(MethodHandle dgemm, MethodHandle dgemmStaged, MethodHandle sgemm, MethodHandle sgemmStaged,
			MethodHandle dgemv, MethodHandle sgemv, @Nullable ThreadQuery threads) {
	}

	/**
	 * Binds the six handles out of one library. It takes the LOOKUP rather than reading
	 * the candidate list itself so that the registration test can bind the same six
	 * shapes on a machine with no CBLAS at all -- see {@link #SIGNATURES} for why that
	 * test exists.
	 * @param lookup the library to bind, which must export all four entry points
	 * @return the six handles
	 */
	static Bound bind(SymbolLookup lookup) {
		Linker linker = Linker.nativeLinker();
		Linker.Option critical = Linker.Option.critical(true);
		MemorySegment gemm = lookup.find("cblas_dgemm").orElseThrow();
		MemorySegment sgemm = lookup.find("cblas_sgemm").orElseThrow();
		return new Bound(handle(linker, gemm, GEMM_D, critical), handle(linker, gemm, GEMM_D),
				handle(linker, sgemm, GEMM_F, critical), handle(linker, sgemm, GEMM_F),
				handle(linker, lookup.find("cblas_dgemv").orElseThrow(), GEMV_D, critical),
				handle(linker, lookup.find("cblas_sgemv").orElseThrow(), GEMV_F, critical),
				threadQuery(linker, lookup));
	}

	/**
	 * Binds the first thread-count query the library exports, or answers {@code null}
	 * when it exports none -- which is not a failure and is what Accelerate and BLIS do.
	 * Bound here rather than in the static block so that the shape is recorded for the
	 * native-image registration test along with the six products.
	 */
	private static @Nullable ThreadQuery threadQuery(Linker linker, SymbolLookup lookup) {
		for (String[] query : THREAD_QUERIES) {
			MemorySegment symbol = lookup.find(query[0]).orElse(null);
			if (symbol != null) {
				return new ThreadQuery(handle(linker, symbol, THREAD_COUNT), query[1], query[2]);
			}
		}
		return null;
	}

	private static MethodHandle handle(Linker linker, MemorySegment symbol, FunctionDescriptor descriptor,
			Linker.Option... options) {
		// critical(true) is the only option bound here; anything else would be a
		// different registration and would have to be recorded as one.
		(options.length == 0 ? SIGNATURES : CRITICAL_SIGNATURES).add(descriptor);
		return linker.downcallHandle(symbol, descriptor, options);
	}

	/**
	 * The shapes bound WITHOUT {@code critical}, for the native-image registration test.
	 * @return the plain downcall shapes this binding asked the linker for
	 */
	static Set<FunctionDescriptor> signatures() {
		return Set.copyOf(SIGNATURES);
	}

	/**
	 * The shapes bound WITH {@code critical(true)}.
	 * @return the critical downcall shapes this binding asked the linker for
	 */
	static Set<FunctionDescriptor> criticalSignatures() {
		return Set.copyOf(CRITICAL_SIGNATURES);
	}

	/** The name a tuned candidate identifies itself by, or {@code null} if none does. */
	private static @Nullable String identify(String candidate, SymbolLookup lookup) {
		if (candidate.contains(ACCELERATE_MARK)) {
			return "Apple Accelerate";
		}
		for (String[] marker : MARKERS) {
			if (lookup.find(marker[0]).isPresent()) {
				return marker[1] + ", exports " + marker[0];
			}
		}
		return null;
	}

	private static @Nullable String env(String name) {
		String value = System.getenv(name);
		return value == null || value.isEmpty() ? null : value;
	}

	/** Whether a tuned CBLAS was found and bound. */
	static boolean available() {
		return DGEMM != null;
	}

	/** What was bound, or why nothing was. */
	static String description() {
		return DESCRIPTION;
	}

	/**
	 * Whether an {@code n x m} by {@code m x p} product is big enough to be worth a
	 * library call at all.
	 */
	static boolean worth(long n, long m, long p) {
		return n * m * p >= MIN_WORK;
	}

	/** How many threads the bound library reported, or 0 when it would not say. */
	static int threads() {
		return THREADS;
	}

	/**
	 * Counts one intercepted product against the thread barrier and answers the one-time
	 * note it earns, or {@code null} -- which is the answer for every product on a
	 * library that is capped, that exports no thread query, or that has already been
	 * reported, and for every product big enough to pay for its own barrier.
	 *
	 * <p>
	 * Everything it decides on is a PARAMETER, the running count included, so the policy
	 * is exercised on a machine with no CBLAS at all: what earns the note is the shape of
	 * the program, and no machine the tests run on can be relied on to have that shape.
	 * The counter and the once-only flag stay at the call site, in {@link #note}.
	 * @param threads what the library said it would use
	 * @param flops the work of this one product, {@code 2 * n * m * p}
	 * @param shortProducts short products seen so far, this one included
	 * @param library the library's name, for the message
	 * @param variable the environment variable that caps it, for the message
	 * @return the note to print, or {@code null}
	 */
	static @Nullable String barrierNote(int threads, long flops, int shortProducts, String library, String variable) {
		if (threads <= 1 || flops > BARRIER_WORK || shortProducts != BARRIER_CALLS) {
			return null;
		}
		return "rontolisp: warning: --blas: " + BARRIER_CALLS + " matrix products too small to pay for " + library
				+ "'s per-call thread barrier, with the library set to " + threads
				+ " threads. A loop of small products -- an LLM decode is one -- can run several times SLOWER"
				+ " threaded than capped; set " + variable
				+ "=1 for this program. A large product wants those threads, so the count is left to you.";
	}

	/** {@link #barrierNote} over the bound library, printed once to standard error. */
	private static void note(long flops) {
		if (THREADS <= 1 || flops > BARRIER_WORK || barrierReported) {
			return;
		}
		String note = barrierNote(THREADS, flops, SHORT_PRODUCTS.incrementAndGet(), THREAD_LIBRARY, THREAD_VARIABLE);
		if (note != null) {
			barrierReported = true;
			System.err.println(note);
		}
	}

	// --- the products -----------------------------------------------------------------

	/**
	 * {@code c = a x b} for a row-major {@code n x m} by {@code m x p} pair -- CBLAS's
	 * {@code (M, N, K) = (n, p, m)} with the row-major leading dimensions -- each array
	 * read from its own element offset (0 in the interpreter's packed arrays, past the
	 * dimension header in the compiled ones).
	 */
	static void gemm(double[] a, int oa, double[] b, int ob, double[] c, int oc, int n, int m, int p) {
		MethodHandle handle = java.util.Objects.requireNonNull(DGEMM);
		note(2L * n * m * p);
		try {
			if (2L * n * m * p <= CRITICAL_FLOP_CEILING) {
				handle.invokeExact(ROW_MAJOR, NO_TRANS, NO_TRANS, n, p, m, 1.0, slice(a, oa), m, slice(b, ob), p, 0.0,
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
			throw sneak(ex);
		}
	}

	/** The single-float sibling of {@link #gemm}. */
	static void gemmF(float[] a, int oa, float[] b, int ob, float[] c, int oc, int n, int m, int p) {
		MethodHandle handle = java.util.Objects.requireNonNull(SGEMM);
		note(2L * n * m * p);
		try {
			if (2L * n * m * p <= CRITICAL_FLOP_CEILING) {
				handle.invokeExact(ROW_MAJOR, NO_TRANS, NO_TRANS, n, p, m, 1.0f, slice(a, oa), m, slice(b, ob), p, 0.0f,
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
			throw sneak(ex);
		}
	}

	/**
	 * {@code y = a x} (when {@code transposed}, {@code y = a^T x}) for a row-major
	 * {@code rows x cols} matrix. A gemv is always passed as a critical call: it is
	 * memory-bound, so its duration is bounded by the operand it was handed.
	 */
	static void gemv(double[] a, int oa, int rows, int cols, double[] x, int ox, double[] y, int oy,
			boolean transposed) {
		// Hoisted rather than written inline: a conditional in an invokeExact argument
		// list crashes the build's NullAway generics check.
		int trans = transposed ? TRANS : NO_TRANS;
		note(2L * rows * cols);
		try {
			java.util.Objects.requireNonNull(DGEMV)
				.invokeExact(ROW_MAJOR, trans, rows, cols, 1.0, slice(a, oa), cols, slice(x, ox), 1, 0.0, slice(y, oy),
						1);
		}
		catch (Throwable ex) {
			throw sneak(ex);
		}
	}

	/** The single-float sibling of {@link #gemv}. */
	static void gemvF(float[] a, int oa, int rows, int cols, float[] x, int ox, float[] y, int oy, boolean transposed) {
		int trans = transposed ? TRANS : NO_TRANS;
		note(2L * rows * cols);
		try {
			java.util.Objects.requireNonNull(SGEMV)
				.invokeExact(ROW_MAJOR, trans, rows, cols, 1.0f, slice(a, oa), cols, slice(x, ox), 1, 0.0f,
						slice(y, oy), 1);
		}
		catch (Throwable ex) {
			throw sneak(ex);
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

	/**
	 * {@code MethodHandle.invokeExact} is declared to throw {@link Throwable}; a CBLAS
	 * call throws nothing, so any exception here is a genuine defect and is rethrown
	 * unwrapped.
	 */
	private static RuntimeException sneak(Throwable ex) {
		if (ex instanceof RuntimeException runtime) {
			return runtime;
		}
		if (ex instanceof Error error) {
			throw error;
		}
		return new IllegalStateException(ex);
	}

}
