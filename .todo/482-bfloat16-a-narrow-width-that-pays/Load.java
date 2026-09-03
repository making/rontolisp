import jdk.incubator.vector.*;

/** What does it cost to CONVERT at load time -- f16 file -> f32/bf16 array, f32 -> bf16, Q8_0 -> bf16 -- for a 1B-element checkpoint? */
public class Load {
	static final VectorSpecies<Integer> IS = IntVector.SPECIES_PREFERRED;
	static final VectorSpecies<Float> FS = FloatVector.SPECIES_PREFERRED;
	static final VectorSpecies<Short> SSH = VectorSpecies.of(short.class, VectorShape.forBitSize(IS.length() * 16));
	static final int SE = 0x7c00 << 13;
	static final FloatVector SCALE = FloatVector.broadcast(FS, 0x1.0p112f);
	static final IntVector INF_FIX = IntVector.broadcast(IS, 0x7f800000 - 0x47800000);

	static short toBf16(float f) { int b = Float.floatToRawIntBits(f); return (short) ((b + 0x7fff + ((b >>> 16) & 1)) >>> 16); }

	static void f16ToF32Scalar(short[] h, float[] o) { for (int i = 0; i < h.length; i++) o[i] = Float.float16ToFloat(h[i]); }
	static void f16ToF32Vector(short[] h, float[] o) {
		int i = 0, bd = IS.loopBound(h.length);
		for (; i < bd; i += IS.length()) {
			IntVector hv = (IntVector) ShortVector.fromArray(SSH, h, i).convertShape(VectorOperators.S2I, IS, 0);
			IntVector u = hv.and(0x7fff).lanewise(VectorOperators.LSHL, 13);
			IntVector r = u.reinterpretAsFloats().mul(SCALE).reinterpretAsInts().add(INF_FIX, u.and(SE).compare(VectorOperators.EQ, SE));
			r.or(hv.and(0x8000).lanewise(VectorOperators.LSHL, 16)).reinterpretAsFloats().intoArray(o, i);
		}
		for (; i < h.length; i++) o[i] = Float.float16ToFloat(h[i]);
	}
	static void f16ToBf16Scalar(short[] h, short[] o) { for (int i = 0; i < h.length; i++) o[i] = toBf16(Float.float16ToFloat(h[i])); }
	static void f32ToBf16Scalar(float[] f, short[] o) { for (int i = 0; i < f.length; i++) o[i] = toBf16(f[i]); }
	static void f32ToBf16Vector(float[] f, short[] o) {
		int i = 0, bd = IS.loopBound(f.length);
		for (; i < bd; i += IS.length()) {
			IntVector b = FloatVector.fromArray(FS, f, i).reinterpretAsInts();
			IntVector r = b.add(0x7fff).add(b.lanewise(VectorOperators.LSHR, 16).and(1)).lanewise(VectorOperators.LSHR, 16);
			((ShortVector) r.convertShape(VectorOperators.I2S, SSH, 0)).intoArray(o, i);
		}
		for (; i < f.length; i++) o[i] = toBf16(f[i]);
	}
	static void q8ToBf16Scalar(byte[] q, float[] s, short[] o) { for (int i = 0; i < q.length; i++) o[i] = toBf16(q[i] * s[i >> 5]); }

	static long t(Runnable r, int it) {
		for (int i = 0; i < 4; i++) r.run();
		long best = Long.MAX_VALUE;
		for (int k = 0; k < 5; k++) { long st = System.nanoTime(); for (int i = 0; i < it; i++) r.run(); best = Math.min(best, (System.nanoTime() - st) / it); }
		return best;
	}

	public static void main(String[] a) {
		int N = 1 << 26;   // 64 Mi elements
		short[] h = new short[N], ob = new short[N]; float[] f = new float[N], of = new float[N]; byte[] q = new byte[N]; float[] s = new float[N / 32];
		java.util.Random rn = new java.util.Random(3);
		for (int i = 0; i < N; i++) { float v = (float) (rn.nextGaussian() * 0.02); f[i] = v; h[i] = Float.floatToFloat16(v); q[i] = (byte) (rn.nextInt(255) - 127); }
		for (int i = 0; i < s.length; i++) s[i] = 0.001f;
		// exactness: vector f16 decode == scalar intrinsic, all patterns (non-NaN)
		short[] all = new short[65536]; for (int i = 0; i < 65536; i++) all[i] = (short) i;
		float[] ra = new float[65536], rv = new float[65536]; f16ToF32Scalar(all, ra); f16ToF32Vector(all, rv);
		int bad = 0; for (int i = 0; i < 65536; i++) if (Float.floatToRawIntBits(ra[i]) != Float.floatToRawIntBits(rv[i]) && !(Float.isNaN(ra[i]) && Float.isNaN(rv[i]))) bad++;
		short[] sb = new short[N], vb = new short[N]; f32ToBf16Scalar(f, sb); f32ToBf16Vector(f, vb);
		int badb = 0; for (int i = 0; i < N; i++) if (sb[i] != vb[i]) badb++;
		System.out.println("f16 vector decode mismatches: " + bad + "   bf16 vector narrow mismatches: " + badb);
		record V(String n, Runnable r) {}
		System.out.printf("%-34s %9s %9s %12s%n", "conversion (64 Mi elements)", "ms", "Gelem/s", "1.1B elems s");
		for (V v : new V[] { new V("f16 -> f32, scalar intrinsic loop", () -> f16ToF32Scalar(h, of)), new V("f16 -> f32, exact vector", () -> f16ToF32Vector(h, of)),
				new V("f16 -> bf16, scalar (via f32, RNE)", () -> f16ToBf16Scalar(h, ob)), new V("f32 -> bf16, scalar RNE", () -> f32ToBf16Scalar(f, ob)),
				new V("f32 -> bf16, vector RNE", () -> f32ToBf16Vector(f, ob)), new V("Q8_0 -> bf16, scalar", () -> q8ToBf16Scalar(q, s, ob)) }) {
			long ns = t(v.r(), 5);
			System.out.printf("%-34s %9.1f %9.2f %12.2f%n", v.n(), ns / 1e6, N / (double) ns, 1.1e9 / (N / (double) ns) / 1e9);
		}
	}
}
