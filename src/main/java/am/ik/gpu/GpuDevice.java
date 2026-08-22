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
	 * The four size thresholds a device applies, in the units each one counts.
	 *
	 * @param work the minimum {@code batch * n * m * p} a matrix product is accepted at
	 * @param map the minimum element count an element-wise map is accepted at
	 * @param strided the minimum OUTPUT element count a broadcast or gather is accepted
	 * at
	 * @param fold the minimum INPUT element count an axis fold is accepted at
	 * @param rng the minimum element count a generator fill is accepted at
	 */
	record Thresholds(long work, long map, long strided, long fold, long rng) {
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

}
