package am.ik.rontolisp.eval;

import java.util.Random;

import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.ShortVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * The both-JIT benchmark harness for the fused {@code bfloat16} GEMV kernels of
 * {@link VecSimdKernels} -- a {@code main}, not a test (surefire's naming patterns skip
 * it), because a number is only worth reading beside the JIT that produced it.
 *
 * <p>
 * <b>Why both JITs.</b> The {@code .todo/482} spike's own fused kernel carried a bf16 and
 * an f16 decoder in one method behind a boolean. Under Graal it ran at 1.51x of f32;
 * under C2 the same source ran at <b>0.20x</b> -- the method overran C2's inlining budget
 * for the Vector API call chain, every vector was boxed, and nothing warned. The cliff is
 * silent: same bits, five times slower. So every kernel number here is taken under Graal
 * (this box's default, CI's, and the native image's) and under C2
 * ({@code -XX:-UseJVMCICompiler}, what a stock OpenJDK runs a compiled {@code .class}
 * under), and a shape that is fast under one and boxed under the other is not done.
 *
 * <p>
 * The variants, per shape:
 * <ul>
 * <li>{@code f32 lanes} -- the shipped f32 GEMV, the baseline every ratio is against.
 * <li>{@code bf16 fused} -- the shipped fused kernel: the decode inside the lane loop.
 * <li>{@code bf16 widen+f32} -- the route the fused kernel replaces: widen the whole
 * matrix into an f32 scratch, then run the f32 kernel over it. This is reuse 1, a decode
 * step's regime, and it is what the fused kernel has to beat to exist.
 * </ul>
 * The parallel rows are measured too, since {@code --parallel} inherits the serial kernel
 * unchanged and the arm has to stay bandwidth-bound rather than dispatch-bound.
 *
 * <p>
 * Both accumulate with ONE f32 accumulator and a two-rounding mul-then-add, because that
 * is what the shipped f32 kernel does and the equivalence contract (fused ==
 * widen-then-f32-kernel, bit for bit) is what makes these kernels safe. {@code .todo/480}
 * proposes four accumulators + FMA for the f32 kernels; when it lands, both arms gain
 * them together. Never compare a four-accumulator bf16 kernel with a one-accumulator f32
 * baseline -- that flatters bf16 and the ratio will not reproduce.
 *
 * <pre>{@code
 * ./mvnw -o test-compile
 * CP=target/classes:target/test-classes
 * java --add-modules jdk.incubator.vector -cp $CP am.ik.rontolisp.eval.Bf16GemvBench
 * java -XX:-UseJVMCICompiler --add-modules jdk.incubator.vector -cp $CP am.ik.rontolisp.eval.Bf16GemvBench
 * }</pre>
 *
 * {@code .todo/488-the-fused-bfloat16-gemv-kernels/bench.sh} runs both and labels the
 * output; the numbers it produced are recorded in that directory's {@code README.md}.
 */
public final class Bf16GemvBench {

	private Bf16GemvBench() {
	}

	/**
	 * Which JIT is actually compiling: "graal" when {@code UseJVMCICompiler} is on (this
	 * box's default, CI's and the native image's), "c2" under
	 * {@code -XX:-UseJVMCICompiler} or a stock OpenJDK. Read from the VM flag rather than
	 * a system property, which is set on neither.
	 */
	private static String jit() {
		try {
			return "true".equals(java.lang.management.ManagementFactory
				.getPlatformMXBean(com.sun.management.HotSpotDiagnosticMXBean.class)
				.getVMOption("UseJVMCICompiler")
				.getValue()) ? "graal" : "c2";
		}
		catch (RuntimeException ex) {
			return "unknown";
		}
	}

	// --- the .todo/480 shape, measured but NOT shipped -----------------------------
	// Four accumulators and a single-rounding fma, the shape .todo/482's probes used and
	// the shape its 1.60x assumed. It is NOT what the kernels above compute: four
	// accumulators change the fold order and fma removes a rounding, so a bf16 kernel in
	// this shape is not bit-equal to the shipped f32 one and the equivalence contract
	// would not hold. Both arms are here so the pair can be measured together -- the
	// number .todo/480 would unlock for .todo/488, and the reason the ratio in the table
	// above is not the spike's. Never compare one of these against a single-accumulator
	// baseline.

	private static final VectorSpecies<Float> FS = FloatVector.SPECIES_128;

	private static final VectorSpecies<Short> SS = ShortVector.SPECIES_64;

	private static final VectorSpecies<Integer> IS = IntVector.SPECIES_128;

	private static final int L = 4;

	private static float sumLanes(FloatVector v) {
		float s = 0.0f;
		for (int lane = 0; lane < v.length(); lane++) {
			s += v.lane(lane);
		}
		return s;
	}

	private static void gemvF32Acc4(float[] r, float[] w, int rows, int cols, float[] x) {
		for (int row = 0; row < rows; row++) {
			int base = row * cols;
			FloatVector a0 = FloatVector.zero(FS), a1 = a0, a2 = a0, a3 = a0;
			for (int i = 0; i < cols; i += 4 * L) {
				a0 = FloatVector.fromArray(FS, w, base + i).fma(FloatVector.fromArray(FS, x, i), a0);
				a1 = FloatVector.fromArray(FS, w, base + i + L).fma(FloatVector.fromArray(FS, x, i + L), a1);
				a2 = FloatVector.fromArray(FS, w, base + i + 2 * L).fma(FloatVector.fromArray(FS, x, i + 2 * L), a2);
				a3 = FloatVector.fromArray(FS, w, base + i + 3 * L).fma(FloatVector.fromArray(FS, x, i + 3 * L), a3);
			}
			r[row] = sumLanes(a0.add(a1).add(a2.add(a3)));
		}
	}

	/** The one-line decode, in a method of its own -- the C2 inlining-cliff rule. */
	private static FloatVector widen(short[] w, int off) {
		return ((IntVector) ShortVector.fromArray(SS, w, off).convertShape(VectorOperators.S2I, IS, 0))
			.lanewise(VectorOperators.LSHL, 16)
			.reinterpretAsFloats();
	}

	private static void gemvBf16Acc4(float[] r, short[] w, int rows, int cols, float[] x) {
		for (int row = 0; row < rows; row++) {
			int base = row * cols;
			FloatVector a0 = FloatVector.zero(FS), a1 = a0, a2 = a0, a3 = a0;
			for (int i = 0; i < cols; i += 4 * L) {
				a0 = widen(w, base + i).fma(FloatVector.fromArray(FS, x, i), a0);
				a1 = widen(w, base + i + L).fma(FloatVector.fromArray(FS, x, i + L), a1);
				a2 = widen(w, base + i + 2 * L).fma(FloatVector.fromArray(FS, x, i + 2 * L), a2);
				a3 = widen(w, base + i + 3 * L).fma(FloatVector.fromArray(FS, x, i + 3 * L), a3);
			}
			r[row] = sumLanes(a0.add(a1).add(a2.add(a3)));
		}
	}

	/** One timed variant: a name and the GEMV it runs into the shared result array. */
	private interface Variant {

		void run();

	}

	/**
	 * The best of five rounds of {@code iterations} calls each, after eight warm-up calls
	 * -- the shape {@code .todo/482}'s probes used, so the numbers compare.
	 * @return nanoseconds per call
	 */
	private static long time(Variant v, int iterations) {
		for (int i = 0; i < 8; i++) {
			v.run();
		}
		long best = Long.MAX_VALUE;
		for (int round = 0; round < 5; round++) {
			long start = System.nanoTime();
			for (int i = 0; i < iterations; i++) {
				v.run();
			}
			best = Math.min(best, (System.nanoTime() - start) / iterations);
		}
		return best;
	}

	public static void main(String[] args) {
		System.out.printf("jit=%s java=%s threads=%s%n", jit(), System.getProperty("java.version"),
				System.getenv().getOrDefault("RONTOLISP_THREADS", "default"));
		for (int[] shape : new int[][] { { 288, 288 }, { 1024, 1024 }, { 4096, 4096 } }) {
			int rows = shape[0];
			int cols = shape[1];
			int elements = rows * cols;
			Random random = new Random(11);
			float[] wf = new float[elements];
			short[] wb = new short[elements];
			for (int i = 0; i < elements; i++) {
				wf[i] = (float) (random.nextGaussian() * 0.02);
				wb[i] = VecSimdKernels.floatToBf16(wf[i]);
				// The baseline must run over the SAME values, or the ratio is a
				// comparison of two different matrices.
				wf[i] = VecSimdKernels.bf16ToFloat(wb[i]);
			}
			float[] x = new float[cols];
			for (int i = 0; i < cols; i++) {
				x[i] = (float) random.nextGaussian();
			}
			float[] r = new float[rows];
			float[] scratch = new float[elements];
			int iterations = elements > (1 << 22) ? 10 : 40;

			System.out.printf("%n=== %dx%d (%.1f MB f32 / %.1f MB bf16)%n%-26s %9s %9s %8s%n", rows, cols,
					elements * 4 / 1e6, elements * 2 / 1e6, "variant", "ms", "Gelem/s", "vs f32");
			long baseline = 0;
			long acc4Baseline = 0;
			// The 4-accumulator arms need whole 4 * L groups per row; every shape here
			// has them, but a future one might not.
			boolean acc4 = cols % (4 * L) == 0;
			String[] names = { "f32 lanes", "bf16 fused", "bf16 widen+f32", "f32 lanes --parallel",
					"bf16 fused --parallel", "f32 4acc+fma (todo-480)", "bf16 4acc+fma (todo-480)" };
			Variant[] variants = { () -> VecSimdKernels.matvecIntoF(r, wf, rows, cols, x, false),
					() -> VecSimdKernels.matvecIntoBf16(r, wb, rows, cols, x, false), () -> {
						VecSimdKernels.widenBf16Into(scratch, wb);
						VecSimdKernels.matvecIntoF(r, scratch, rows, cols, x, false);
					}, () -> VecSimdKernels.matvecIntoF(r, wf, rows, cols, x, true),
					() -> VecSimdKernels.matvecIntoBf16(r, wb, rows, cols, x, true),
					() -> gemvF32Acc4(r, wf, rows, cols, x), () -> gemvBf16Acc4(r, wb, rows, cols, x) };
			for (int i = 0; i < names.length; i++) {
				if (i >= 5 && !acc4) {
					continue;
				}
				long ns = time(variants[i], iterations);
				if (i == 0) {
					baseline = ns;
				}
				// The 4-accumulator arms are read against each other, so print the
				// ratio against the f32 arm of the same shape.
				long against = i >= 5 ? acc4Baseline : baseline;
				if (i == 5) {
					acc4Baseline = ns;
					against = ns;
				}
				System.out.printf("%-26s %9.3f %9.2f %7.2fx%n", names[i], ns / 1e6, elements / (double) ns,
						against / (double) ns);
			}
			// Printed so the JIT cannot drop the loops, and so the two JITs can be seen
			// to have computed the same thing.
			VecSimdKernels.matvecIntoBf16(r, wb, rows, cols, x, false);
			double fused = 0.0;
			for (float v : r) {
				fused += v;
			}
			VecSimdKernels.matvecIntoF(r, wf, rows, cols, x, false);
			double widened = 0.0;
			for (float v : r) {
				widened += v;
			}
			System.out.printf("checksum fused=%a widen-then-f32=%a identical=%b%n", fused, widened, fused == widened);
		}
	}

}
