package am.ik.rontolisp.eval;

import java.util.Random;

import am.ik.rontolisp.BFloat16;
import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.ShortVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorShape;
import jdk.incubator.vector.VectorSpecies;

/**
 * The measurement that has to come BEFORE any element-wise {@code bfloat16} kernel is
 * written: <b>does the narrowing vectorize?</b> A {@code main}, not a test (surefire's
 * naming patterns skip it), and both JITs, for the reason {@link Bf16GemvBench} states.
 *
 * <p>
 * The asymmetry that motivates it: widening a bf16 pattern is one left shift and is
 * already a lane loop ({@link VecSimdKernels#widenBf16Into}); narrowing is
 * round-to-nearest-ties-to-even with a guarded NaN arm and is a SCALAR loop in both
 * kernel files ({@link VecSimdKernels#narrowBf16Into}). A reduction (GEMV, dot, sum)
 * decodes many elements and stores one, so the scalar narrow never appears in it -- which
 * is why the fused GEMV/dot/sum kernels could ship without answering this. An
 * ELEMENT-WISE kernel stores every element, so the narrow is on its critical path, and
 * ~40 new kernels mirrored across two files are worth writing only if a lane form of the
 * narrow exists and is faster than the scalar one.
 *
 * <p>
 * The lane form under test, {@link #narrowLanes}, is the scalar
 * {@link VecSimdKernels#floatToBf16} operation for operation: the bias-add and the
 * odd-bit carry as int lanes, the NaN arm as a second int expression, and a
 * {@link VectorMask} choosing between them -- no branch, so every lane pays for both
 * arms. The narrowing store itself is an {@code I2S} shape conversion.
 *
 * <p>
 * The variants, per element count:
 * <ul>
 * <li>{@code widen scalar} / {@code widen lanes (shipped)} -- the direction that is known
 * to vectorize, as the scale to read the narrow's ratio against.
 * <li>{@code narrow scalar (shipped)} -- {@link VecSimdKernels#narrowBf16Into}, the
 * baseline.
 * <li>{@code narrow lanes (probe)} -- the question.
 * <li>{@code add bf16 scalar} / {@code add bf16 lane+scalar store} /
 * {@code add bf16 all-lane} -- the composite an element-wise kernel would actually be:
 * widen both operands, add in f32, narrow on store. The middle arm is the shape to expect
 * if the narrow does NOT vectorize: a scalar store loop wearing a vector load.
 * <li>{@code add f32 (shipped)} -- {@link VecSimdKernels#addIntoF} over the same element
 * count, the ceiling any bf16 element-wise kernel is trying to approach.
 * </ul>
 *
 * <p>
 * A speed number is worthless if the lane form is not the same function, so {@code main}
 * first sweeps <b>all 2^32 f32 patterns</b> through both forms and prints the mismatch
 * count. It must be zero.
 *
 * <pre>{@code
 * ./mvnw -o test-compile
 * CP=target/classes:target/test-classes
 * java --add-modules jdk.incubator.vector -cp $CP am.ik.rontolisp.eval.Bf16NarrowBench
 * java -XX:-UseJVMCICompiler --add-modules jdk.incubator.vector -cp $CP am.ik.rontolisp.eval.Bf16NarrowBench
 * }</pre>
 *
 * The numbers this produced, and what they decided, are in {@code .kb/bfloat16.md}.
 */
public final class Bf16NarrowBench {

	private Bf16NarrowBench() {
	}

	/**
	 * The float species the ELEMENT-WISE kernels run at ({@code SPECIES_PREFERRED} --
	 * they are bit-exact at any lane count, unlike the reductions, which are pinned to
	 * four).
	 */
	private static final VectorSpecies<Float> FS = FloatVector.SPECIES_PREFERRED;

	/** The int species of the same shape: where the bit arithmetic happens. */
	private static final VectorSpecies<Integer> IS = VectorSpecies.of(int.class, FS.vectorShape());

	/** The short species of HALF the shape: the same lane count at half the width. */
	private static final VectorSpecies<Short> SS = VectorSpecies.of(short.class,
			VectorShape.forBitSize(FS.vectorBitSize() / 2));

	/** Mirrors {@link VecSimdKernels}' gate, so the arms compare. */
	private static final int THRESHOLD = 128;

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

	// --- the two lane forms, one small method each (the C2 inlining-cliff rule) -------

	/** The decode at the element-wise lane count. Exact, so the width is free to vary. */
	private static FloatVector widenLanes(short[] w, int off) {
		return ((IntVector) ShortVector.fromArray(SS, w, off).convertShape(VectorOperators.S2I, IS, 0))
			.lanewise(VectorOperators.LSHL, 16)
			.reinterpretAsFloats();
	}

	/**
	 * {@link VecSimdKernels#floatToBf16} as lanes: both arms computed, the NaN one
	 * blended in under a mask, then an {@code I2S} narrowing store.
	 */
	private static ShortVector narrowLanes(FloatVector v) {
		IntVector bits = v.reinterpretAsInts();
		IntVector hi = bits.lanewise(VectorOperators.LSHR, 16);
		IntVector rounded = bits.add(0x7fff).add(hi.and(1)).lanewise(VectorOperators.LSHR, 16);
		IntVector u = hi.and(0xffff);
		IntVector nan = u.or(u.and(0x7f).sub(1).lanewise(VectorOperators.LSHR, 31));
		VectorMask<Integer> isNan = bits.and(0x7f800000)
			.compare(VectorOperators.EQ, 0x7f800000)
			.and(bits.and(0x007fffff).compare(VectorOperators.NE, 0));
		return (ShortVector) rounded.blend(nan, isNan).convertShape(VectorOperators.I2S, SS, 0);
	}

	// --- the loops under test --------------------------------------------------------

	/** The probe: {@link VecSimdKernels#narrowBf16Into} with a lane body. */
	private static void narrowLanesInto(short[] r, float[] x) {
		int n = Math.min(r.length, x.length);
		int i = 0;
		if (n >= THRESHOLD) {
			int bound = FS.loopBound(n);
			for (; i < bound; i += FS.length()) {
				narrowLanes(FloatVector.fromArray(FS, x, i)).intoArray(r, i);
			}
		}
		for (; i < n; i++) {
			r[i] = VecSimdKernels.floatToBf16(x[i]);
		}
	}

	/** The direction that is known to vectorize, scalar, as the scale to read against. */
	private static void widenScalarInto(float[] r, short[] w) {
		int n = Math.min(r.length, w.length);
		for (int i = 0; i < n; i++) {
			r[i] = VecSimdKernels.bf16ToFloat(w[i]);
		}
	}

	/** bf16 + bf16 -> bf16, wholly scalar: what the {@code vec.lisp} defun computes. */
	private static void addBf16Scalar(short[] r, short[] a, short[] b) {
		for (int i = 0; i < r.length; i++) {
			r[i] = VecSimdKernels.floatToBf16(VecSimdKernels.bf16ToFloat(a[i]) + VecSimdKernels.bf16ToFloat(b[i]));
		}
	}

	/**
	 * bf16 + bf16 -> bf16 with a vector load and a SCALAR store loop -- the shape to
	 * expect if the narrow does not vectorize.
	 */
	private static void addBf16LaneLoadScalarStore(short[] r, short[] a, short[] b) {
		int n = r.length;
		int i = 0;
		float[] lane = new float[FS.length()];
		if (n >= THRESHOLD) {
			int bound = FS.loopBound(n);
			for (; i < bound; i += FS.length()) {
				widenLanes(a, i).add(widenLanes(b, i)).intoArray(lane, 0);
				for (int k = 0; k < lane.length; k++) {
					r[i + k] = VecSimdKernels.floatToBf16(lane[k]);
				}
			}
		}
		for (; i < n; i++) {
			r[i] = VecSimdKernels.floatToBf16(VecSimdKernels.bf16ToFloat(a[i]) + VecSimdKernels.bf16ToFloat(b[i]));
		}
	}

	/** bf16 + bf16 -> bf16, all lanes: widen, add in f32, narrow on store. */
	private static void addBf16AllLane(short[] r, short[] a, short[] b) {
		int n = r.length;
		int i = 0;
		if (n >= THRESHOLD) {
			int bound = FS.loopBound(n);
			for (; i < bound; i += FS.length()) {
				narrowLanes(widenLanes(a, i).add(widenLanes(b, i))).intoArray(r, i);
			}
		}
		for (; i < n; i++) {
			r[i] = VecSimdKernels.floatToBf16(VecSimdKernels.bf16ToFloat(a[i]) + VecSimdKernels.bf16ToFloat(b[i]));
		}
	}

	// --- correctness: all 2^32 f32 patterns through both narrows ----------------------

	/**
	 * Sweeps every one of the 2^32 f32 bit patterns through the scalar narrow and the
	 * lane narrow and counts the disagreements. The lane form is only a candidate if this
	 * is zero: a faster function that is a DIFFERENT function is not a kernel.
	 * @return the mismatch count
	 */
	private static long verifyEveryF32Pattern() {
		int chunk = 1 << 20;
		float[] x = new float[chunk];
		short[] a = new short[chunk];
		short[] b = new short[chunk];
		long mismatches = 0;
		for (long base = 0; base < (1L << 32); base += chunk) {
			for (int k = 0; k < chunk; k++) {
				x[k] = Float.intBitsToFloat((int) (base + k));
			}
			VecSimdKernels.narrowBf16Into(a, x);
			narrowLanesInto(b, x);
			for (int k = 0; k < chunk; k++) {
				if (a[k] != b[k]) {
					if (mismatches == 0) {
						System.out.printf("first mismatch: f32 %08x -> scalar %04x lanes %04x%n", (int) (base + k),
								a[k] & 0xffff, b[k] & 0xffff);
					}
					mismatches++;
				}
			}
		}
		return mismatches;
	}

	/**
	 * The question a FUSED REDUCTION never had to ask, and an element-wise kernel cannot
	 * avoid: <b>may the intermediate be an f32 at all?</b>
	 *
	 * <p>
	 * The fused GEMV/dot/sum kernels answer a {@code double} and are pinned to the f32
	 * kernel over the widened operand, so the contract is an equivalence between two
	 * kernels. An element-wise kernel STORES at the narrow width, so what it must equal
	 * is the {@code vec.lisp} DEFUN, and the defun reads each element as a {@code double}
	 * ({@code aref} over a packed bf16 array), computes in {@code double}, and narrows on
	 * {@code setf aref} through {@link BFloat16#bits(double)}. A kernel that widens to
	 * f32, computes in f32 and narrows rounds a THIRD time in between.
	 *
	 * <p>
	 * The theory says the extra rounding is invisible: {@code BFloat16.bits(double)}
	 * itself falls through to {@code bits((float) value)}, so both routes end in the same
	 * f32 -> bf16 step, and binary64 carries 53 &gt;= 2 * 24 + 2 bits, which is the
	 * classical condition for a binary64 intermediate to round harmlessly to binary32 for
	 * {@code + - * /}. Theory is not a pin, so this sweeps <b>all 65536 x 65536 operand
	 * pairs</b> per operation.
	 * @return the mismatch count, summed over the operations
	 */
	private static long verifyF32IntermediateEqualsTheDefun() {
		float[] wf = new float[65536];
		double[] wd = new double[65536];
		for (int i = 0; i < 65536; i++) {
			wf[i] = VecSimdKernels.bf16ToFloat((short) i);
			wd[i] = BFloat16.value(i);
		}
		long total = 0;
		for (int op = 0; op < 4; op++) {
			long start = System.nanoTime();
			long mismatches = 0;
			int firstA = -1;
			int firstB = -1;
			for (int a = 0; a < 65536; a++) {
				float fa = wf[a];
				double da = wd[a];
				for (int b = 0; b < 65536; b++) {
					float fb = wf[b];
					double db = wd[b];
					int viaF32 = switch (op) {
						case 0 -> VecSimdKernels.floatToBf16(fa + fb) & 0xffff;
						case 1 -> VecSimdKernels.floatToBf16(fa - fb) & 0xffff;
						case 2 -> VecSimdKernels.floatToBf16(fa * fb) & 0xffff;
						default -> VecSimdKernels.floatToBf16(fa / fb) & 0xffff;
					};
					int viaF64 = switch (op) {
						case 0 -> BFloat16.bits(da + db);
						case 1 -> BFloat16.bits(da - db);
						case 2 -> BFloat16.bits(da * db);
						default -> BFloat16.bits(da / db);
					};
					if (viaF32 != viaF64) {
						if (mismatches == 0) {
							firstA = a;
							firstB = b;
						}
						mismatches++;
					}
				}
			}
			String name = switch (op) {
				case 0 -> "add";
				case 1 -> "sub";
				case 2 -> "mul";
				default -> "div";
			};
			System.out.printf(
					"verify pairs: %s, all 65536x65536 -- f32 intermediate vs the defun's f64: "
							+ "%d mismatches%s (%.1f s)%n",
					name, mismatches, mismatches == 0 ? "" : " first at %04x,%04x".formatted(firstA, firstB),
					(System.nanoTime() - start) / 1e9);
			total += mismatches;
		}
		return total;
	}

	private interface Variant {

		void run();

	}

	/** Best of five rounds after eight warm-up calls, as {@link Bf16GemvBench} times. */
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
		System.out.printf("jit=%s java=%s float lanes=%d short lanes=%d%n", jit(), System.getProperty("java.version"),
				FS.length(), SS.length());
		long start = System.nanoTime();
		long mismatches = verifyEveryF32Pattern();
		System.out.printf("verify: all 2^32 f32 patterns, lane narrow vs scalar narrow: %d mismatches (%.1f s)%n",
				mismatches, (System.nanoTime() - start) / 1e9);
		// The operand-pair sweep takes about a minute and is JIT-independent, so it runs
		// only when asked for: `bench.sh` asks once, before the two timing runs.
		if (args.length > 0 && "pairs".equals(args[0])) {
			System.out.printf("verify pairs: total %d mismatches%n", verifyF32IntermediateEqualsTheDefun());
		}

		for (int n : new int[] { 1024, 1 << 16, 1 << 20, 1 << 24 }) {
			Random random = new Random(11);
			short[] xb = new short[n];
			short[] yb = new short[n];
			float[] xf = new float[n];
			float[] yf = new float[n];
			for (int i = 0; i < n; i++) {
				xb[i] = VecSimdKernels.floatToBf16((float) (random.nextGaussian() * 0.02));
				yb[i] = VecSimdKernels.floatToBf16((float) (random.nextGaussian() * 0.02));
				xf[i] = VecSimdKernels.bf16ToFloat(xb[i]);
				yf[i] = VecSimdKernels.bf16ToFloat(yb[i]);
			}
			short[] rb = new short[n];
			float[] rf = new float[n];
			// Enough calls per round that the megamorphic `Variant.run()` call site and
			// the timer itself amortize: at n=1024 a single call is a few hundred ns.
			int iterations = n > (1 << 22) ? 20 : Math.max(200, (1 << 22) / n);

			System.out.printf("%n=== n=%d (%.2f MB f32 / %.2f MB bf16)%n%-30s %9s %9s %8s%n", n, n * 4 / 1e6,
					n * 2 / 1e6, "variant", "ms", "Gelem/s", "vs scalar");
			String[] names = { "widen scalar", "widen lanes (shipped)", "narrow scalar (shipped)",
					"narrow lanes (probe)", "add bf16 scalar", "add bf16 lane+scalar store", "add bf16 all-lane",
					"add f32 (shipped)" };
			Variant[] variants = { () -> widenScalarInto(rf, xb), () -> VecSimdKernels.widenBf16Into(rf, xb),
					() -> VecSimdKernels.narrowBf16Into(rb, xf), () -> narrowLanesInto(rb, xf),
					() -> addBf16Scalar(rb, xb, yb), () -> addBf16LaneLoadScalarStore(rb, xb, yb),
					() -> addBf16AllLane(rb, xb, yb), () -> VecSimdKernels.addIntoF(rf, xf, yf) };
			// Each arm's ratio is against the SCALAR arm of its own group: widen, narrow,
			// add. Reading a narrow against a widen would compare two functions.
			int[] against = { 0, 0, 2, 2, 4, 4, 4, 4 };
			long[] ns = new long[names.length];
			for (int i = 0; i < names.length; i++) {
				ns[i] = time(variants[i], iterations);
				System.out.printf("%-30s %9.3f %9.2f %7.2fx%n", names[i], ns[i] / 1e6, n / (double) ns[i],
						ns[against[i]] / (double) ns[i]);
			}
			// Printed so the JIT cannot drop the loops, and so the three add arms can be
			// seen to have computed the same thing.
			short[] s1 = new short[n];
			short[] s2 = new short[n];
			short[] s3 = new short[n];
			addBf16Scalar(s1, xb, yb);
			addBf16LaneLoadScalarStore(s2, xb, yb);
			addBf16AllLane(s3, xb, yb);
			System.out.printf("checksum add: scalar==lane+scalar-store %b, scalar==all-lane %b%n",
					java.util.Arrays.equals(s1, s2), java.util.Arrays.equals(s1, s3));
		}
	}

}
