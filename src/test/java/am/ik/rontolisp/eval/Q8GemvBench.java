package am.ik.rontolisp.eval;

import java.util.Random;

/**
 * The both-JIT benchmark harness for the Q8_0 integer-dot GEMV of {@link VecSimdKernels}
 * ({@code .kb/quantized-matrix.md}) -- a {@code main}, not a test, run by
 * {@code .todo/artefacts/672-a-q8-0-quantized-weight-matrix-and-its-integer-dot-gemv/bench.sh}
 * under Graal and under C2 ({@code -XX:-UseJVMCICompiler}), because a Vector API kernel
 * that overruns C2's inlining budget runs boxed at 0.2x with no warning (measured on the
 * bfloat16 kernels), and a number without its JIT beside it is not a number.
 *
 * <p>
 * The variants, per shape, all over the SAME gaussian weights (the f32 and bf16 arms hold
 * the values the Q8_0 blocks were quantized from):
 * <ul>
 * <li>{@code f32 lanes} -- the shipped f32 GEMV, the baseline every ratio is against;
 * <li>{@code bf16 fused} -- the shipped fused bfloat16 GEMV;
 * <li>{@code q8 int-dot} -- the shipped Q8_0 kernel, the activation quantized per call as
 * the defun does it;
 * <li>{@code q8 sliced widens (probe)} -- the 2026-09-05 kernel, the same bits through
 * part-1 conversions, which C2 compiles as a {@code slice} each;
 * <li>the first three under {@code --parallel}.
 * </ul>
 * The checksum line asserts the Q8_0 kernel's answer equals a scalar transcription of the
 * defun bit for bit under whichever JIT ran, and prints the relative error of each arm
 * against the f32 GEMV so the quantization cost is on the record beside the speed.
 *
 * <pre>{@code
 * ./mvnw -o test-compile
 * CP=target/classes:target/test-classes
 * java --add-modules jdk.incubator.vector -cp $CP am.ik.rontolisp.eval.Q8GemvBench
 * java -XX:-UseJVMCICompiler --add-modules jdk.incubator.vector -cp $CP am.ik.rontolisp.eval.Q8GemvBench
 * }</pre>
 */
public final class Q8GemvBench {

	private Q8GemvBench() {
	}

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

	private interface Variant {

		void run();

	}

	/**
	 * Best of five rounds of {@code iterations} calls after eight warm-ups: ns per call.
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

	/** The defun, transcribed: what the kernel must equal bit for bit. */
	private static double[] oracle(byte[] blocks, int rows, int cols, float[] x) {
		int nb = cols / 32;
		int[] xq = new int[cols];
		double[] xs = new double[nb];
		for (int b = 0; b < nb; b++) {
			double amax = 0.0;
			for (int k = 0; k < 32; k++) {
				double v = Math.abs((double) x[b * 32 + k]);
				if (v > amax) {
					amax = v;
				}
			}
			double sx = amax / 127.0;
			xs[b] = sx;
			for (int k = 0; k < 32; k++) {
				xq[b * 32 + k] = sx == 0.0 ? 0 : (int) Math.rint(x[b * 32 + k] / sx);
			}
		}
		double[] r = new double[rows];
		for (int i = 0; i < rows; i++) {
			// Four accumulators, lane k over the columns j with j mod 4 = k, folded as
			// (acc0 + acc2) + (acc1 + acc3): the defun's four and its fold.
			// Every step a double operation narrowed to f32 (the defun's vec::%f32).
			double[] acc = new double[4];
			for (int b = 0; b < nb; b++) {
				int bo = (i * nb + b) * 34;
				long[] lane = new long[4];
				for (int k = 0; k < 32; k++) {
					lane[k % 4] += blocks[bo + 2 + k] * xq[b * 32 + k];
				}
				double sw = Float.float16ToFloat((short) ((blocks[bo] & 0xff) | (blocks[bo + 1] << 8)));
				double p = (float) (sw * xs[b]);
				for (int k = 0; k < 4; k++) {
					acc[k] = (float) (acc[k] + (float) (lane[k] * p));
				}
			}
			r[i] = (float) ((float) (acc[0] + acc[2]) + (float) (acc[1] + acc[3]));
		}
		return r;
	}

	private static double relativeError(float[] got, double[] reference) {
		double num = 0.0;
		double den = 0.0;
		for (int i = 0; i < got.length; i++) {
			num += (got[i] - reference[i]) * (got[i] - reference[i]);
			den += reference[i] * reference[i];
		}
		return Math.sqrt(num / den);
	}

	// --- the shape that lost under C2: the 2026-09-05 kernel, kept as a probe
	// ---
	// The first shipped kernel, verbatim: 128-bit byte loads, the activation quantized
	// to a byte[] and widened in the lane loop, and the upper halves reached by PART-1
	// conversions -- each a `slice` (two rearranges and a blend) before the widen, which
	// C2 compiles as written. 0.7x of the f32 GEMV under C2, 1.45x under Graal; the
	// shipped kernel is the same bits at 1.9x / 1.45x. Timed only: its answer
	// equals the shipped kernel's, but the checksum line reads the shipped one. The
	// reduce-per-block shape this row replaced (5-6 Gelem/s on both JITs) is recorded in
	// .todo/artefacts/672-.../README.md.

	private static final jdk.incubator.vector.VectorSpecies<Byte> B128 = jdk.incubator.vector.ByteVector.SPECIES_128;

	private static final jdk.incubator.vector.VectorSpecies<Float> F128 = jdk.incubator.vector.FloatVector.SPECIES_128;

	private static final jdk.incubator.vector.VectorSpecies<Short> S128 = jdk.incubator.vector.ShortVector.SPECIES_128;

	private static final jdk.incubator.vector.VectorSpecies<Integer> I128 = jdk.incubator.vector.IntVector.SPECIES_128;

	private static jdk.incubator.vector.IntVector probeBlockDot(byte[] w, int wo, byte[] xq, int xo) {
		jdk.incubator.vector.ByteVector w0 = jdk.incubator.vector.ByteVector.fromArray(B128, w, wo);
		jdk.incubator.vector.ByteVector w1 = jdk.incubator.vector.ByteVector.fromArray(B128, w, wo + 16);
		jdk.incubator.vector.ByteVector x0 = jdk.incubator.vector.ByteVector.fromArray(B128, xq, xo);
		jdk.incubator.vector.ByteVector x1 = jdk.incubator.vector.ByteVector.fromArray(B128, xq, xo + 16);
		jdk.incubator.vector.ShortVector p = ((jdk.incubator.vector.ShortVector) w0
			.convertShape(jdk.incubator.vector.VectorOperators.B2S, S128, 0))
			.mul((jdk.incubator.vector.ShortVector) x0.convertShape(jdk.incubator.vector.VectorOperators.B2S, S128, 0))
			.add(((jdk.incubator.vector.ShortVector) w0.convertShape(jdk.incubator.vector.VectorOperators.B2S, S128, 1))
				.mul((jdk.incubator.vector.ShortVector) x0.convertShape(jdk.incubator.vector.VectorOperators.B2S, S128,
						1)));
		jdk.incubator.vector.ShortVector q = ((jdk.incubator.vector.ShortVector) w1
			.convertShape(jdk.incubator.vector.VectorOperators.B2S, S128, 0))
			.mul((jdk.incubator.vector.ShortVector) x1.convertShape(jdk.incubator.vector.VectorOperators.B2S, S128, 0))
			.add(((jdk.incubator.vector.ShortVector) w1.convertShape(jdk.incubator.vector.VectorOperators.B2S, S128, 1))
				.mul((jdk.incubator.vector.ShortVector) x1.convertShape(jdk.incubator.vector.VectorOperators.B2S, S128,
						1)));
		return ((jdk.incubator.vector.IntVector) p.convertShape(jdk.incubator.vector.VectorOperators.S2I, I128, 0))
			.add((jdk.incubator.vector.IntVector) p.convertShape(jdk.incubator.vector.VectorOperators.S2I, I128, 1))
			.add((jdk.incubator.vector.IntVector) q.convertShape(jdk.incubator.vector.VectorOperators.S2I, I128, 0))
			.add((jdk.incubator.vector.IntVector) q.convertShape(jdk.incubator.vector.VectorOperators.S2I, I128, 1));
	}

	private static float probeRow(byte[] w, int base, int nb, byte[] xq, double[] xs) {
		jdk.incubator.vector.FloatVector acc = jdk.incubator.vector.FloatVector.zero(F128);
		for (int b = 0; b < nb; b++) {
			int bo = base + b * 34;
			jdk.incubator.vector.IntVector isum = probeBlockDot(w, bo + 2, xq, b * 32);
			jdk.incubator.vector.FloatVector p = jdk.incubator.vector.FloatVector.broadcast(F128,
					(float) (VecSimdKernels.q8Scale(w, bo) * xs[b]));
			acc = acc.add(((jdk.incubator.vector.FloatVector) isum.convert(jdk.incubator.vector.VectorOperators.I2F, 0))
				.mul(p));
		}
		return (acc.lane(0) + acc.lane(2)) + (acc.lane(1) + acc.lane(3));
	}

	private static void probeSlicedWidens(float[] r, byte[] w, int rows, int cols, float[] x) {
		int nb = cols / 32;
		short[] xh = new short[cols];
		double[] xs = new double[nb];
		VecSimdKernels.quantizeActivationF(x, 0, cols, xh, xs);
		byte[] xq = new byte[cols];
		for (int i = 0; i < cols; i++) {
			xq[i] = (byte) xh[i];
		}
		for (int row = 0; row < rows; row++) {
			r[row] = probeRow(w, row * nb * 34, nb, xq, xs);
		}
	}

	public static void main(String[] args) {
		System.out.printf("jit=%s java=%s threads=%s%n", jit(), System.getProperty("java.version"),
				System.getenv().getOrDefault("RONTOLISP_THREADS", "default"));
		for (int[] shape : new int[][] { { 288, 288 }, { 1024, 1024 }, { 4096, 4096 }, { 5632, 2048 } }) {
			int rows = shape[0];
			int cols = shape[1];
			int elements = rows * cols;
			Random random = new Random(11);
			float[] wf = new float[elements];
			for (int i = 0; i < elements; i++) {
				wf[i] = (float) (random.nextGaussian() * 0.02);
			}
			short[] wb = new short[elements];
			for (int i = 0; i < elements; i++) {
				wb[i] = VecSimdKernels.floatToBf16(wf[i]);
			}
			byte[] q8 = new byte[elements / 32 * 34];
			for (int b = 0; b < elements / 32; b++) {
				QuantizedMatrices.quantizeRowQ8_0(wf, b * 32, q8, b * 34);
			}
			float[] x = new float[cols];
			for (int i = 0; i < cols; i++) {
				x[i] = (float) random.nextGaussian();
			}
			float[] r = new float[rows];
			int iterations = elements > (1 << 22) ? 10 : 40;
			System.out.printf("%n=== %dx%d (%.1f MB f32 / %.1f MB bf16 / %.1f MB q8_0)%n%-24s %9s %9s %8s%n", rows,
					cols, elements * 4 / 1e6, elements * 2 / 1e6, q8.length / 1e6, "variant", "ms", "Gelem/s",
					"vs f32");
			String[] names = { "f32 lanes", "bf16 fused", "q8 int-dot", "q8 sliced widens (probe)",
					"f32 lanes --parallel", "bf16 fused --parallel", "q8 int-dot --parallel" };
			Variant[] variants = { () -> VecSimdKernels.matvecIntoF(r, wf, rows, cols, x, false),
					() -> VecSimdKernels.matvecIntoBf16(r, wb, rows, cols, x, false),
					() -> VecSimdKernels.matvecIntoQ8F(r, q8, rows, cols, x, false),
					() -> probeSlicedWidens(r, q8, rows, cols, x),
					() -> VecSimdKernels.matvecIntoF(r, wf, rows, cols, x, true),
					() -> VecSimdKernels.matvecIntoBf16(r, wb, rows, cols, x, true),
					() -> VecSimdKernels.matvecIntoQ8F(r, q8, rows, cols, x, true) };
			long baseline = 0;
			long parallelBaseline = 0;
			for (int i = 0; i < names.length; i++) {
				long ns = time(variants[i], iterations);
				if (i == 0) {
					baseline = ns;
				}
				if (i == 4) {
					parallelBaseline = ns;
				}
				long against = i >= 4 ? parallelBaseline : baseline;
				System.out.printf("%-24s %9.3f %9.2f %7.2fx%n", names[i], ns / 1e6, elements / (double) ns,
						against / (double) ns);
			}
			// Correctness beside the speed: the kernel is the defun bit for bit under
			// this
			// JIT, and each arm's distance from the f32 GEMV is the record's error
			// column.
			double[] f64 = new double[rows];
			for (int i = 0; i < rows; i++) {
				double acc = 0.0;
				for (int j = 0; j < cols; j++) {
					acc += (double) wf[i * cols + j] * x[j];
				}
				f64[i] = acc;
			}
			VecSimdKernels.matvecIntoF(r, wf, rows, cols, x, false);
			double f32Error = relativeError(r, f64);
			VecSimdKernels.matvecIntoBf16(r, wb, rows, cols, x, false);
			double bf16Error = relativeError(r, f64);
			VecSimdKernels.matvecIntoQ8F(r, q8, rows, cols, x, false);
			double q8Error = relativeError(r, f64);
			double[] expected = oracle(q8, rows, cols, x);
			boolean identical = true;
			for (int i = 0; i < rows; i++) {
				identical &= Float.floatToRawIntBits(r[i]) == Float.floatToRawIntBits((float) expected[i]);
			}
			System.out.printf("rel.err vs f64: f32 %.1e  bf16 %.1e  q8 %.1e   q8 kernel == defun: %b%n", f32Error,
					bf16Error, q8Error, identical);
		}
	}

}
