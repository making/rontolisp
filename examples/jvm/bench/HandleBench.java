import am.ik.rontolisp.runtime.RontoFloatArray;

import com.example.Norm2Kernels;

/**
 * What the packed float array costs at the Java boundary -- the measurement that picked
 * the shape of the {@code :float-vector} designator (doc/en/guides/jvm-library.md).
 *
 * <p>Four ways to compute the same 2-norm over the same 2^20 doubles:
 *
 * <ol>
 * <li>a plain Java loop, which C2 auto-vectorizes -- the thing a kernel has to beat;
 * <li>the compiled kernel on a pre-packed array through the untyped method -- the floor,
 *     no boundary at all;
 * <li>the same kernel behind the {@code RontoFloatArray} handle -- the shipped design;
 * <li>the same kernel behind a facade that packs a {@code double[]} on every call -- the
 *     naive API, measured in order to rule it out.
 * </ol>
 */
public class HandleBench {

	private static final int N = 1 << 20;

	private static final int WARMUP = 3000;

	private static final int ITERS = 300;

	public static void main(String[] args) {
		double[] plain = new double[N];
		for (int i = 0; i < N; i++) {
			plain[i] = (i % 1000) * 1e-3;
		}
		// The two representations a caller can hold: a plain array, and the packed one
		// (built ONCE -- that is the whole point).
		RontoFloatArray handle = RontoFloatArray.of(plain);
		Object packed = handle.packed();

		double check = javaLoop(plain);
		expect(check, (Double) Norm2Kernels.NORM2(packed));
		expect(check, Norm2Kernels.norm2(handle));
		expect(check, copyingFacade(plain));

		System.out.printf("n = %d, %d iterations after %d warm-up calls%n", N, ITERS, WARMUP);
		double java = measure("plain Java loop", () -> javaLoop(plain), 0.0);
		measure("kernel on a pre-packed array (the floor)", () -> (Double) Norm2Kernels.NORM2(packed), java);
		measure("kernel behind the handle", () -> Norm2Kernels.norm2(handle), java);
		measure("kernel behind a copying facade", () -> copyingFacade(plain), java);
	}

	/** The 2-norm, written the way a Java programmer would write it. */
	private static double javaLoop(double[] x) {
		double sum = 0.0;
		for (int i = 0; i < x.length; i++) {
			sum += x[i] * x[i];
		}
		return Math.sqrt(sum);
	}

	/** The API this designator exists to avoid: a fresh packed copy on every call. */
	private static double copyingFacade(double[] x) {
		return (Double) Norm2Kernels.NORM2(RontoFloatArray.of(x).packed());
	}

	private static double measure(String label, java.util.function.DoubleSupplier body, double baseline) {
		for (int i = 0; i < WARMUP; i++) {
			body.getAsDouble();
		}
		long start = System.nanoTime();
		double sink = 0.0;
		for (int i = 0; i < ITERS; i++) {
			sink += body.getAsDouble();
		}
		double msPerCall = (System.nanoTime() - start) / 1e6 / ITERS;
		if (sink == Double.MIN_VALUE) {
			throw new AssertionError("unreachable, and the sink is why the loop is not dead");
		}
		if (baseline == 0.0) {
			System.out.printf("  %-40s %7.3f ms/call   1.00x%n", label, msPerCall);
		}
		else {
			System.out.printf("  %-40s %7.3f ms/call  %5.2fx%n", label, msPerCall, baseline / msPerCall);
		}
		return msPerCall;
	}

	private static void expect(double expected, double actual) {
		if (Math.abs(expected - actual) > 1e-9 * Math.abs(expected)) {
			throw new AssertionError("the four paths must compute the same number: " + expected + " vs " + actual);
		}
	}

}
