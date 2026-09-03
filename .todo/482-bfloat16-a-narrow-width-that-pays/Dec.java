import jdk.incubator.vector.*;

public class Dec {
	static final VectorSpecies<Integer> IS = IntVector.SPECIES_PREFERRED;
	static final VectorSpecies<Float> FS = FloatVector.SPECIES_PREFERRED;
	static final VectorSpecies<Short> SSH = VectorSpecies.of(short.class, VectorShape.forBitSize(IS.length() * 16));

	// A: scalar intrinsic
	static void a(short[] h, float[] o) { for (int i = 0; i < h.length; i++) o[i] = Float.float16ToFloat(h[i]); }

	// B: exact bit trick (branchy fixups via masks)
	static void b(short[] h, float[] o) {
		final int se = 0x7c00 << 13; final float magic = Float.intBitsToFloat(113 << 23);
		int i = 0, bd = IS.loopBound(h.length);
		for (; i < bd; i += IS.length()) {
			IntVector hv = (IntVector) ShortVector.fromArray(SSH, h, i).convertShape(VectorOperators.S2I, IS, 0);
			IntVector u = hv.and(0x7fff).lanewise(VectorOperators.LSHL, 13);
			IntVector ex = u.and(se);
			IntVector bs = u.add((127 - 15) << 23);
			VectorMask<Integer> inf = ex.compare(VectorOperators.EQ, se), sub = ex.compare(VectorOperators.EQ, 0);
			FloatVector f = bs.add(IntVector.broadcast(IS, (128 - 16) << 23), inf).add(IntVector.broadcast(IS, 1 << 23), sub)
					.reinterpretAsFloats().sub(FloatVector.broadcast(FS, magic), sub.cast(FS));
			f.reinterpretAsInts().or(hv.and(0x8000).lanewise(VectorOperators.LSHL, 16)).reinterpretAsFloats().intoArray(o, i);
		}
		for (; i < h.length; i++) o[i] = Float.float16ToFloat(h[i]);
	}

	// C: magic-multiply (exact for normals+subnormals+zero, WRONG for inf/nan)
	static void c(short[] h, float[] o) {
		int i = 0, bd = IS.loopBound(h.length);
		FloatVector scale = FloatVector.broadcast(FS, 0x1.0p112f);
		for (; i < bd; i += IS.length()) {
			IntVector hv = (IntVector) ShortVector.fromArray(SSH, h, i).convertShape(VectorOperators.S2I, IS, 0);
			FloatVector f = hv.and(0x7fff).lanewise(VectorOperators.LSHL, 13).reinterpretAsFloats().mul(scale);
			f.reinterpretAsInts().or(hv.and(0x8000).lanewise(VectorOperators.LSHL, 16)).reinterpretAsFloats().intoArray(o, i);
		}
		for (; i < h.length; i++) o[i] = Float.float16ToFloat(h[i]);
	}

	// D: magic-multiply + exact inf/nan fixup (one extra masked add). FIXED 2026-09-03
	// (.todo/671): the fixup mask below is `u.and(se).compare(EQ, se)` -- "exponent field
	// is all-ones" -- not a bare `u.compare(EQ, se)` ("u equals se exactly", true
	// infinity only). The bare compare is what this method originally had; it is exact
	// for infinity but WRONG for every NaN pattern (a nonzero f16 mantissa keeps u > se,
	// so the fixup never fires and a NaN decodes to a large FINITE float instead).
	// Verified exhaustively over all 65536 patterns: the original bare-compare form
	// mismatches 2046/65536 (every NaN); the masked form below is exact (NaN payload
	// aside), matching Load.java's f16ToF32Vector, which had the mask all along -- see
	// README section 4's re-evaluation note.
	static void d(short[] h, float[] o) {
		final int se = 0x7c00 << 13;
		int i = 0, bd = IS.loopBound(h.length);
		FloatVector scale = FloatVector.broadcast(FS, 0x1.0p112f);
		for (; i < bd; i += IS.length()) {
			IntVector hv = (IntVector) ShortVector.fromArray(SSH, h, i).convertShape(VectorOperators.S2I, IS, 0);
			IntVector u = hv.and(0x7fff).lanewise(VectorOperators.LSHL, 13);
			FloatVector f = u.reinterpretAsFloats().mul(scale);
			IntVector r = f.reinterpretAsInts();
			r = r.add(IntVector.broadcast(IS, 0x7f800000 - 0x47800000), u.and(se).compare(VectorOperators.EQ, se));
			r.or(hv.and(0x8000).lanewise(VectorOperators.LSHL, 16)).reinterpretAsFloats().intoArray(o, i);
		}
		for (; i < h.length; i++) o[i] = Float.float16ToFloat(h[i]);
	}

	// E: bf16 for reference -- one shift
	static void e(short[] h, float[] o) {
		int i = 0, bd = IS.loopBound(h.length);
		for (; i < bd; i += IS.length()) {
			IntVector hv = (IntVector) ShortVector.fromArray(SSH, h, i).convertShape(VectorOperators.S2I, IS, 0);
			hv.lanewise(VectorOperators.LSHL, 16).reinterpretAsFloats().intoArray(o, i);
		}
		for (; i < h.length; i++) o[i] = Float.intBitsToFloat(h[i] << 16);
	}

	// F: plain float[] copy -- the bandwidth ceiling for this shape
	static void f(float[] src, float[] o) {
		int i = 0, bd = FS.loopBound(src.length);
		for (; i < bd; i += FS.length()) FloatVector.fromArray(FS, src, i).intoArray(o, i);
	}

	static long t(Runnable r, int it) {
		for (int i = 0; i < 10; i++) r.run();
		long best = Long.MAX_VALUE;
		for (int k = 0; k < 7; k++) { long s = System.nanoTime(); for (int i = 0; i < it; i++) r.run(); best = Math.min(best, (System.nanoTime() - s) / it); }
		return best;
	}

	public static void main(String[] x) {
		int N = 1 << 22;
		short[] h = new short[N]; float[] o = new float[N], src = new float[N];
		java.util.Random rn = new java.util.Random(1);
		for (int i = 0; i < N; i++) { float v = (float) (rn.nextGaussian() * 0.02); h[i] = Float.floatToFloat16(v); src[i] = v; }
		// exactness sweep over all 65536 bit patterns for C and D
		short[] all = new short[65536]; for (int i = 0; i < 65536; i++) all[i] = (short) i;
		float[] ra = new float[65536], rc = new float[65536], rd = new float[65536];
		a(all, ra); c(all, rc); d(all, rd);
		int badc = 0, badd = 0;
		for (int i = 0; i < 65536; i++) {
			if (Float.floatToRawIntBits(ra[i]) != Float.floatToRawIntBits(rc[i]) && !(Float.isNaN(ra[i]) && Float.isNaN(rc[i]))) badc++;
			if (Float.floatToRawIntBits(ra[i]) != Float.floatToRawIntBits(rd[i]) && !(Float.isNaN(ra[i]) && Float.isNaN(rd[i]))) badd++;
		}
		System.out.println("exactness over all 65536 patterns: C(magic) mismatches=" + badc + "  D(magic+inf fixup) mismatches=" + badd);
		System.out.printf("%-28s %8s %10s%n", "decode variant", "ms", "Gelem/s");
		record V(String n, Runnable r) {}
		for (V v : new V[] { new V("A scalar Float.float16ToFloat", () -> a(h, o)), new V("B exact bit-trick vector", () -> b(h, o)),
				new V("C magic-mul (no inf/nan)", () -> c(h, o)), new V("D magic-mul + inf fixup", () -> d(h, o)),
				new V("E bf16 shift (reference)", () -> e(h, o)), new V("F f32->f32 copy (ceiling)", () -> f(src, o)) }) {
			long ns = t(v.r(), 30);
			System.out.printf("%-28s %8.2f %10.2f%n", v.n(), ns / 1e6, N / (double) ns);
		}
	}
}
