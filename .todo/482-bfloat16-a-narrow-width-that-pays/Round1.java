import jdk.incubator.vector.*;

public class Round1 {

	static final VectorSpecies<Float> FS = FloatVector.SPECIES_PREFERRED;
	static final VectorSpecies<Double> DS = DoubleVector.SPECIES_PREFERRED;
	static final VectorSpecies<Short> SS = ShortVector.SPECIES_PREFERRED;

	// ---- decode kernels -------------------------------------------------

	static void decodeScalar(short[] h, float[] out) {
		for (int i = 0; i < h.length; i++) out[i] = Float.float16ToFloat(h[i]);
	}

	// Giesen's exact bit-trick, vectorized over IntVector lanes.
	static final VectorSpecies<Integer> IS = IntVector.SPECIES_PREFERRED;
	static final VectorSpecies<Short> SSH = VectorSpecies.of(short.class, VectorShape.forBitSize(IS.length() * 16));
	static void decodeVector(short[] h, float[] out) {
		int n = h.length;
		int i = 0;
		int bound = IS.loopBound(n);
		final int shiftedExp = 0x7c00 << 13;
		final float magic = Float.intBitsToFloat(113 << 23);
		for (; i < bound; i += IS.length()) {
			// widen short -> int (sign-extends; mask below drops it)
			IntVector hv = (IntVector) ShortVector.fromArray(SSH, h, i)
					.convertShape(VectorOperators.S2I, IS, 0);
			IntVector u = hv.and(0x7fff).lanewise(VectorOperators.LSHL, 13);
			IntVector exp = u.and(shiftedExp);
			IntVector base = u.add((127 - 15) << 23);
			VectorMask<Integer> isInf = exp.compare(VectorOperators.EQ, shiftedExp);
			VectorMask<Integer> isSub = exp.compare(VectorOperators.EQ, 0);
			IntVector fixed = base.add(IntVector.broadcast(IS, (128 - 16) << 23), isInf);
			FloatVector f = fixed.add(IntVector.broadcast(IS, 1 << 23), isSub).reinterpretAsFloats();
			f = f.sub(FloatVector.broadcast(FS, magic), isSub.cast(FS));
			IntVector sign = hv.and(0x8000).lanewise(VectorOperators.LSHL, 16);
			f = f.reinterpretAsInts().or(sign).reinterpretAsFloats();
			f.intoArray(out, i);
		}
		for (; i < n; i++) out[i] = Float.float16ToFloat(h[i]);
	}

	// ---- gemv kernels ---------------------------------------------------

	static void gemvF64(double[] w, int rows, int cols, double[] x, double[] r) {
		for (int row = 0; row < rows; row++) {
			int base = row * cols;
			DoubleVector acc = DoubleVector.zero(DS);
			int i = 0, b = DS.loopBound(cols);
			for (; i < b; i += DS.length())
				acc = acc.add(DoubleVector.fromArray(DS, w, base + i).mul(DoubleVector.fromArray(DS, x, i)));
			double s = acc.reduceLanes(VectorOperators.ADD);
			for (; i < cols; i++) s += w[base + i] * x[i];
			r[row] = s;
		}
	}

	static void gemvF32(float[] w, int rows, int cols, float[] x, float[] r) {
		for (int row = 0; row < rows; row++) {
			int base = row * cols;
			FloatVector acc = FloatVector.zero(FS);
			int i = 0, b = FS.loopBound(cols);
			for (; i < b; i += FS.length())
				acc = acc.add(FloatVector.fromArray(FS, w, base + i).mul(FloatVector.fromArray(FS, x, i)));
			float s = acc.reduceLanes(VectorOperators.ADD);
			for (; i < cols; i++) s += w[base + i] * x[i];
			r[row] = s;
		}
	}

	// f16 weights, f32 activations, decode on the fly (scalar intrinsic, let SuperWord try)
	static void gemvF16Scalar(short[] w, int rows, int cols, float[] x, float[] r) {
		for (int row = 0; row < rows; row++) {
			int base = row * cols;
			float s0 = 0, s1 = 0, s2 = 0, s3 = 0;
			int i = 0;
			for (; i + 3 < cols; i += 4) {
				s0 += Float.float16ToFloat(w[base + i]) * x[i];
				s1 += Float.float16ToFloat(w[base + i + 1]) * x[i + 1];
				s2 += Float.float16ToFloat(w[base + i + 2]) * x[i + 2];
				s3 += Float.float16ToFloat(w[base + i + 3]) * x[i + 3];
			}
			float s = s0 + s1 + s2 + s3;
			for (; i < cols; i++) s += Float.float16ToFloat(w[base + i]) * x[i];
			r[row] = s;
		}
	}

	// f16 weights decoded through the vectorized bit-trick into f32 lanes
	static void gemvF16Vector(short[] w, int rows, int cols, float[] x, float[] r) {
		final int shiftedExp = 0x7c00 << 13;
		final float magic = Float.intBitsToFloat(113 << 23);
		for (int row = 0; row < rows; row++) {
			int base = row * cols;
			FloatVector acc = FloatVector.zero(FS);
			int i = 0, b = IS.loopBound(cols);
			for (; i < b; i += IS.length()) {
				IntVector hv = (IntVector) ShortVector.fromArray(SSH, w, base + i)
						.convertShape(VectorOperators.S2I, IS, 0);
				IntVector u = hv.and(0x7fff).lanewise(VectorOperators.LSHL, 13);
				IntVector exp = u.and(shiftedExp);
				IntVector bs = u.add((127 - 15) << 23);
				VectorMask<Integer> isInf = exp.compare(VectorOperators.EQ, shiftedExp);
				VectorMask<Integer> isSub = exp.compare(VectorOperators.EQ, 0);
				IntVector fx = bs.add(IntVector.broadcast(IS, (128 - 16) << 23), isInf)
						.add(IntVector.broadcast(IS, 1 << 23), isSub);
				FloatVector f = fx.reinterpretAsFloats().sub(FloatVector.broadcast(FS, magic), isSub.cast(FS));
				f = f.reinterpretAsInts().or(hv.and(0x8000).lanewise(VectorOperators.LSHL, 16)).reinterpretAsFloats();
				acc = acc.add(f.mul(FloatVector.fromArray(FS, x, i)));
			}
			float s = acc.reduceLanes(VectorOperators.ADD);
			for (; i < cols; i++) s += Float.float16ToFloat(w[base + i]) * x[i];
			r[row] = s;
		}
	}

	// ---- harness --------------------------------------------------------

	static long time(Runnable r, int iters) {
		for (int i = 0; i < 5; i++) r.run();      // warm
		long best = Long.MAX_VALUE;
		for (int k = 0; k < 5; k++) {
			long t = System.nanoTime();
			for (int i = 0; i < iters; i++) r.run();
			best = Math.min(best, (System.nanoTime() - t) / iters);
		}
		return best;
	}

	public static void main(String[] args) {
		System.out.println("FloatVector.SPECIES_PREFERRED = " + FS + " (" + FS.length() + " lanes)");
		System.out.println("ShortVector.SPECIES_PREFERRED = " + ShortVector.SPECIES_PREFERRED.length() + " lanes");
		System.out.println("IntVector.SPECIES_PREFERRED   = " + IS.length() + " lanes");

		// --- correctness of the vector decode against the intrinsic
		int N = 65536;
		short[] hs = new short[N];
		for (int i = 0; i < N; i++) hs[i] = (short) i;   // covers 0..0xffff patterns
		float[] a = new float[N], b = new float[N];
		decodeScalar(hs, a);
		decodeVector(hs, b);
		int bad = 0;
		for (int i = 0; i < N; i++) {
			if (Float.floatToRawIntBits(a[i]) != Float.floatToRawIntBits(b[i])
					&& !(Float.isNaN(a[i]) && Float.isNaN(b[i]))) {
				if (bad < 5) System.out.printf("  MISMATCH h=%04x scalar=%s vector=%s%n", i, a[i], b[i]);
				bad++;
			}
		}
		System.out.println("decode bit-trick mismatches over all 65536 patterns: " + bad);

		// --- round-trip loss
		double maxRel = 0;
		java.util.Random rnd = new java.util.Random(42);
		for (int i = 0; i < 1_000_000; i++) {
			float v = (float) (rnd.nextGaussian() * 0.02);   // llm weight scale
			float rt = Float.float16ToFloat(Float.floatToFloat16(v));
			if (v != 0) maxRel = Math.max(maxRel, Math.abs((rt - v) / v));
		}
		System.out.printf("max relative round-trip error over 1e6 N(0,0.02) samples: %.3e (~2^-11 = %.3e)%n",
				maxRel, Math.pow(2, -11));

		// --- decode throughput
		int DN = 1 << 22;
		short[] dh = new short[DN];
		for (int i = 0; i < DN; i++) dh[i] = Float.floatToFloat16((float) rnd.nextGaussian());
		float[] dout = new float[DN];
		long ts = time(() -> decodeScalar(dh, dout), 20);
		long tv = time(() -> decodeVector(dh, dout), 20);
		System.out.printf("%ndecode %d elems: scalar-intrinsic %.2f ms (%.1f Gelem/s), bit-trick-vector %.2f ms (%.1f Gelem/s)%n",
				DN, ts / 1e6, DN / (double) ts, tv / 1e6, DN / (double) tv);

		// --- gemv
		for (int[] sz : new int[][] { { 1024, 1024 }, { 4096, 4096 }, { 8192, 8192 } }) {
			int rows = sz[0], cols = sz[1];
			long elems = (long) rows * cols;
			double[] wd = new double[(int) elems];
			float[] wf = new float[(int) elems];
			short[] wh = new short[(int) elems];
			for (int i = 0; i < elems; i++) {
				float v = (float) (rnd.nextGaussian() * 0.02);
				wd[i] = v; wf[i] = v; wh[i] = Float.floatToFloat16(v);
			}
			double[] xd = new double[cols]; float[] xf = new float[cols];
			for (int i = 0; i < cols; i++) { float v = (float) rnd.nextGaussian(); xd[i] = v; xf[i] = v; }
			double[] rd = new double[rows]; float[] rf = new float[rows];
			float[] rf2 = new float[rows]; float[] rf3 = new float[rows];

			int iters = elems > (1 << 22) ? 3 : 20;
			long t64 = time(() -> gemvF64(wd, rows, cols, xd, rd), iters);
			long t32 = time(() -> gemvF32(wf, rows, cols, xf, rf), iters);
			long t16s = time(() -> gemvF16Scalar(wh, rows, cols, xf, rf2), iters);
			long t16v = time(() -> gemvF16Vector(wh, rows, cols, xf, rf3), iters);
			double gbF16 = elems * 2 / 1e9, gbF32 = elems * 4 / 1e9, gbF64 = elems * 8 / 1e9;
			System.out.printf("%ngemv %dx%d  (weights: f64 %.0f MB / f32 %.0f MB / f16 %.0f MB)%n",
					rows, cols, gbF64 * 1000, gbF32 * 1000, gbF16 * 1000);
			System.out.printf("  f64 vector     %7.2f ms   %6.1f GB/s%n", t64 / 1e6, gbF64 / (t64 / 1e9));
			System.out.printf("  f32 vector     %7.2f ms   %6.1f GB/s   %.2fx vs f64%n", t32 / 1e6, gbF32 / (t32 / 1e9), t64 / (double) t32);
			System.out.printf("  f16 scalar-dec %7.2f ms   %6.1f GB/s   %.2fx vs f32%n", t16s / 1e6, gbF16 / (t16s / 1e9), t32 / (double) t16s);
			System.out.printf("  f16 vec-dec    %7.2f ms   %6.1f GB/s   %.2fx vs f32%n", t16v / 1e6, gbF16 / (t16v / 1e9), t32 / (double) t16v);
			// accuracy of the f16 gemv against the f64 reference
			double err = 0, ref = 0;
			for (int i = 0; i < rows; i++) { err = Math.max(err, Math.abs(rf3[i] - rd[i])); ref = Math.max(ref, Math.abs(rd[i])); }
			System.out.printf("  f16 gemv max abs err vs f64 %.3e (max |ref| %.3e -> %.2f%% )%n", err, ref, 100 * err / ref);
		}
	}
}
