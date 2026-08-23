package am.ik.gpu;

/**
 * The one device {@link Gpu} probed, whichever kind it turned out to be: an NVIDIA card
 * through {@link CudaGemm} or an Apple GPU through {@link MetalGemm}. Package-private and
 * sealed -- this is a dispatch seam inside the library, not a plug-in point.
 *
 * <h2>What the two backends do NOT have in common</h2>
 *
 * Almost nothing above this interface. The CUDA half carries the checked-in PTX, a
 * primary context, a status table with a sticky half, and a driver-owned allocation pool;
 * the Metal half compiles MSL from a string at run time, owns its own buffer pool because
 * the platform has none, and has no {@code double} at all. So every difference the
 * interceptors could care about is asked here rather than assumed:
 * {@link #supportsDouble()} says whether a {@code #d} operand is a hard decline, and
 * {@link #thresholds()} says where THIS device's crossovers are, because a per-call floor
 * of 16 us and one of 130 us do not accept the same shapes.
 *
 * <h2>The invariant crosses the seam unchanged</h2>
 *
 * No method here throws and none signals: every failure of any kind answers {@code false}
 * (or {@code 0} for a size), and the caller runs whatever it would have run anyway. See
 * {@link Gpu}'s contract, which is this one stated for the outside.
 */
sealed interface GpuDevice permits CudaGemm, MetalGemm {

	/**
	 * The size thresholds a device applies, in the units each one counts.
	 *
	 * @param work the minimum {@code batch * n * m * p} a matrix product is accepted at
	 * @param map the minimum element count an element-wise map is accepted at
	 * @param strided the minimum OUTPUT element count a broadcast or gather is accepted
	 * at
	 * @param fold the minimum INPUT element count an axis fold is accepted at
	 * @param rng the minimum element count a generator fill is accepted at
	 * @param matvec the minimum {@code rows * cols} a matrix-by-vector product is
	 * accepted at, once its matrix is resident
	 */
	record Thresholds(long work, long map, long strided, long fold, long rng, long matvec) {
	}

	/** What was found -- model, architecture, driver -- for {@code description()}. */
	String description();

	/**
	 * Whether this device is still worth asking. A CUDA context killed by a sticky status
	 * answers {@code false} for the rest of the process; Metal has no such state and
	 * answers {@code true} until the binding itself is retired.
	 * @return {@code true} while calls may still be offered
	 */
	boolean usable();

	/**
	 * Whether a {@code double} operand can run here at all. {@code false} on Metal, where
	 * MSL rejects {@code double} outright -- so a {@code #d} array is a HARD decline on
	 * that backend rather than a slower path, and {@code linalg}'s default width reaches
	 * the device only after {@code torch:}'s single-float default or an explicit
	 * {@code #f}.
	 * @return {@code true} when the double-taking overloads may be offered
	 */
	boolean supportsDouble();

	/** Where this device's crossovers against the fastest CPU path sit. */
	Thresholds thresholds();

	/**
	 * Free device memory in bytes, or {@code 0} when the platform cannot say. For the
	 * leak tests, which assert that a run of calls does not move it.
	 * @return free device memory, in bytes
	 */
	long freeDeviceMemory();

	boolean gemm(double[] a, int oa, double[] b, int ob, double[] c, int oc, int n, int m, int p);

	boolean gemm(double[] a, int oa, int sa, double[] b, int ob, int sb, double[] c, int oc, int batch, int n, int m,
			int p);

	boolean gemmF(float[] a, int oa, float[] b, int ob, float[] c, int oc, int n, int m, int p);

	boolean gemmF(float[] a, int oa, int sa, float[] b, int ob, int sb, float[] c, int oc, int batch, int n, int m,
			int p);

	boolean map(int op, double[] a, int oa, double[] c, int oc, int n);

	boolean mapF(int op, float[] a, int oa, float[] c, int oc, int n);

	boolean bcast(int op, double[] a, int oa, int[] sa, double[] b, int ob, int[] sb, double[] c, int oc, int[] dims);

	boolean bcastF(int op, float[] a, int oa, int[] sa, float[] b, int ob, int[] sb, float[] c, int oc, int[] dims);

	boolean gather(double[] a, int oa, int[] sa, double[] c, int oc, int[] dims);

	boolean gatherF(float[] a, int oa, int[] sa, float[] c, int oc, int[] dims);

	boolean fold(int op, double[] a, int oa, double[] c, int oc, int outer, int len, int inner);

	boolean foldF(int op, float[] a, int oa, float[] c, int oc, int outer, int len, int inner);

	boolean rngFill(double[] c, int oc, int n, int mode, double lo, double span, int s1, int s2, int s3);

	boolean rngFillF(float[] c, int oc, int n, int mode, double lo, double span, int s1, int s2, int s3);

	/**
	 * {@code y = W x} over a row-major {@code rows x cols} matrix -- the GEMV behind
	 * {@code vec:matvec}, and the one member whose worth depends on RESIDENCY rather than
	 * on size: a matrix-by-vector product is one pass over {@code W}, so the device wins
	 * only when {@code W} is already there. Both halves therefore take it only once the
	 * matrix has been offered twice without being written ({@link CudaGemm#gemv},
	 * {@link MetalGemm#gemvF}) -- the Metal one at {@code float} only, like everything
	 * else there.
	 * @return {@code true} when {@code y} was filled
	 */
	boolean gemv(double[] w, int ow, double[] x, int ox, double[] y, int oy, int rows, int cols);

	boolean gemvF(float[] w, int ow, float[] x, int ox, float[] y, int oy, int rows, int cols);

	/**
	 * {@code c[i] = op(a[i], b[i])} over two operands of the SAME shape -- the resident
	 * tier ({@code .todo/491}): offered by {@link Gpu} only once an operand is resident,
	 * because as a round trip the CPU's lane loop wins. Bit-identical to it.
	 * @return {@code true} when {@code c} was filled
	 */
	boolean zip(int op, double[] a, int oa, double[] b, int ob, double[] c, int oc, int n);

	boolean zipF(int op, float[] a, int oa, float[] b, int ob, float[] c, int oc, int n);

	/**
	 * {@code c[i] = op(a[i], s)}, or {@code op(s, a[i])} when {@code swap}, over a double
	 * scalar -- the resident tier's array-with-scalar form. Bit-identical to the CPU's.
	 * @return {@code true} when {@code c} was filled
	 */
	boolean scale(int op, double[] a, int oa, double s, boolean swap, double[] c, int oc, int n);

	boolean scaleF(int op, float[] a, int oa, double s, boolean swap, float[] c, int oc, int n);

	/**
	 * {@code c = where(m, x, y)} over three operands broadcast to {@code dims}, any of
	 * which may be a scalar (a {@code null} array and its double); the mask is a
	 * {@code double[]} / {@code float[]} of either width, or {@code null} for a scalar.
	 * The resident tier's three-way select. Bit-identical to the CPU's.
	 * @return {@code true} when {@code c} was filled
	 */
	boolean where(@org.jspecify.annotations.Nullable Object m, int om, int[] sm, double ms,
			double @org.jspecify.annotations.Nullable [] x, int ox, int[] sx, double xs,
			double @org.jspecify.annotations.Nullable [] y, int oy, int[] sy, double ys, double[] c, int oc,
			int[] dims);

	boolean whereF(@org.jspecify.annotations.Nullable Object m, int om, int[] sm, double ms,
			float @org.jspecify.annotations.Nullable [] x, int ox, int[] sx, double xs,
			float @org.jspecify.annotations.Nullable [] y, int oy, int[] sy, double ys, float[] c, int oc, int[] dims);

	/**
	 * The strided copy {@code c[oc + sc.i] = a[oa + sa.i]} over {@code dims} -- reshape,
	 * the rank-2 transpose, a slice, a slab of a concatenation -- over a resident operand
	 * only. {@code spanOa / spanNa} and {@code spanOc / spanNc} are each array's whole
	 * data part (element offset and count), which is the span residency keys on.
	 * @return {@code true} when {@code c} was filled
	 */
	boolean copy(double[] a, int oa, int[] sa, int spanOa, int spanNa, double[] c, int oc, int[] sc, int spanOc,
			int spanNc, int[] dims);

	boolean copyF(float[] a, int oa, int[] sa, int spanOa, int spanNa, float[] c, int oc, int[] sc, int spanOc,
			int spanNc, int[] dims);

	/**
	 * Adam's fused update IN PLACE over the parameter, its gradient and the two moments
	 * -- the resident tier's one writing member, which leaves the three written arrays
	 * resident. Bit-identical to the CPU's.
	 * @return {@code true} when the update ran
	 */
	boolean adamStep(double[] x, int ox, double[] g, int og, double[] m, int om, double[] v, int ov, int n,
			double[] rule);

	boolean adamStepF(float[] x, int ox, float[] g, int og, float[] m, int om, float[] v, int ov, int n, double[] rule);

	/**
	 * A host array this device may hold a resident copy of is about to be written, so
	 * that copy is stale -- and, if it was the authoritative one ({@link #lazyResults}),
	 * it is brought home first. Both halves keep such copies ({@link DeviceResidency}: a
	 * CUDA buffer on one, a pooled {@code MTLBuffer} on the other), and every in-place
	 * write on either interceptor reaches here through {@link Gpu#written}.
	 * @param host the host array that is being written
	 */
	void written(Object host);

	/**
	 * A host array is about to be READ: if the device holds its only bytes (a result left
	 * there under {@link #lazyResults}), they come home now. Every host read of
	 * packed-array storage on either interceptor reaches here through
	 * {@link Gpu#materialize}. A download on CUDA, a memcpy out of the slab on Metal.
	 * @param host the host array about to be read
	 */
	void materialize(Object host);

	/**
	 * Whether a copy of {@code host} is resident, at any span -- the question the
	 * resident tier asks before offering a member whose CPU twin is a lane loop.
	 * @param host the host array
	 * @return {@code true} when a device buffer holds a copy of it
	 */
	boolean resident(Object host);

	/**
	 * Whether a member's result STAYS on the device until the host first reads it, rather
	 * than being downloaded before the call returns. Both halves honour it: CUDA since
	 * {@code .todo/491}, Metal since {@code .todo/494}, each measured on its own hardware
	 * ({@code .kb/gpu.md}).
	 * @param on whether to keep results on the device
	 */
	void lazyResults(boolean on);

	/**
	 * Whether lazy results PAY on this backend -- the measured answer that decides
	 * whether the interceptors switch them on ({@link Gpu#lazyResultsIfWorthwhile}).
	 * {@code true} on CUDA (a fifth off the training step, then half); {@code false} on
	 * Metal, where the same design measured a tie at small shapes and a loss at large
	 * ones ({@code .kb/gpu.md}, "Lazy results and the resident tier on Metal").
	 * Independent of {@link #lazyResults}: an embedder that asks gets the mode on either
	 * backend.
	 * @return {@code true} when the interceptors should run with lazy results
	 */
	boolean lazyResultsPay();

	/**
	 * Whether lazy results are in force right now.
	 * @return {@code true} while results stay on the device until read
	 */
	boolean lazyResultsOn();

	/**
	 * Bytes held by resident copies right now.
	 * @return the resident total, in bytes
	 */
	long residentBytes();

	/** Drops and frees every resident copy. */
	void releaseResident();

	/** The cache behind {@link #written}, for the tests' hit and miss counts. */
	DeviceResidency residency();

}
