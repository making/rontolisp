/**
 * f32 -> f16 bits (round-to-nearest-even) candidates, and the exhaustive check that
 * settled which one .todo/671 actually uses. No incubator Vector API here -- this is
 * about correctness of the SCALAR bit-trick, not throughput (671's WASM decoder has no
 * access to java.lang.Float at all, so the encoder has to be a from-scratch integer-bit
 * algorithm it can transliterate one instruction at a time).
 *
 * <p>
 * Two branchless "magic multiply" tricks (variants B and C) are the classic "fast
 * half-float" encode: rescale by 2^-112 in HARDWARE float multiply (which is
 * correctly-rounded, including subnormal results, by IEEE 754) and read the exponent
 * back out. Both get every NORMAL-target rounding right but round DENORMAL-target ties
 * the wrong way -- see main() below, run with `java Enc.java`.
 *
 * <p>
 * Variant D is a literal port of java.lang.Float.floatToFloat16 (JDK 20+,
 * java.base/java/lang/Float.java) rewritten with integer bit ops only (no
 * Math.getExponent/Math.abs, so it transliterates directly to WASM instructions -- no
 * branches this codebase's WASM writer cannot already express). It is the only one of
 * the three verified exact over EVERY float32 bit pattern (2^32 of them, ~10 s on this
 * box), NaN payload included. 671 uses it for the WASM float16-bits encoder.
 */
public class Enc {

	// B: plain rescale, no pre-mask.
	static short encodeB(float fv) {
		final int F32_INFTY = 255 << 23;
		final int F16_INFTY = 31 << 23;
		final int MAGIC = 15 << 23;
		int f = Float.floatToRawIntBits(fv);
		int sign = f & 0x80000000;
		f ^= sign;
		int o;
		if (f >= F32_INFTY) {
			o = (f > F32_INFTY) ? 0x7e00 : 0x7c00;
		}
		else {
			f = Float.floatToRawIntBits(Float.intBitsToFloat(f) * Float.intBitsToFloat(MAGIC));
			if (f > F16_INFTY)
				f = F16_INFTY;
			o = f >>> 13;
		}
		return (short) (o | (sign >>> 16));
	}

	// C: rescale with a 12-bit pre-mask (fixes the NORMAL-range rounding the plain
	// rescale gets wrong, per the classic "fast half float" writeups) -- still wrong at
	// DENORMAL-target ties.
	static short encodeC(float fv) {
		final int F32_INFTY = 255 << 23;
		final int F16_INFTY = 31 << 23;
		final int MAGIC = 15 << 23;
		final int ROUND_MASK = ~0xfff;
		int f = Float.floatToRawIntBits(fv);
		int sign = f & 0x80000000;
		f ^= sign;
		int o;
		if (f >= F32_INFTY) {
			o = (f > F32_INFTY) ? 0x7e00 : 0x7c00;
		}
		else {
			f &= ROUND_MASK;
			f = Float.floatToRawIntBits(Float.intBitsToFloat(f) * Float.intBitsToFloat(MAGIC));
			f -= ROUND_MASK;
			if (f > F16_INFTY)
				f = F16_INFTY;
			o = f >>> 13;
		}
		return (short) (o | (sign >>> 16));
	}

	// D: literal branchy port of java.lang.Float.floatToFloat16, integer bit ops only.
	static short encodeD(float fv) {
		int doppel = Float.floatToRawIntBits(fv);
		int sign_bit = (doppel & 0x80000000) >>> 16;

		boolean isNaN = ((doppel >>> 23) & 0xff) == 0xff && (doppel & 0x7fffff) != 0;
		if (isNaN) {
			return (short) (sign_bit | 0x7c00 | (doppel & 0x007fe000) >>> 13 | (doppel & 0x00001ff0) >>> 4
					| (doppel & 0x0000000f));
		}

		int absBits = doppel & 0x7fffffff;

		final int OVERFLOW_THRESHOLD_BITS = 0x477ff000; // bits of (0x1.ffcp15f + 0x0.002p15f)
		if (absBits >= OVERFLOW_THRESHOLD_BITS) {
			return (short) (sign_bit | 0x7c00);
		}

		final int ZERO_THRESHOLD_BITS = 0x33000000; // bits of (0x1.0p-24f * 0.5f)
		if (absBits <= ZERO_THRESHOLD_BITS) {
			return (short) sign_bit;
		}

		// absBits is now a NORMAL float32 (the zero-threshold check above already
		// absorbed every float32 subnormal and near-zero value).
		int exp = ((absBits >>> 23) & 0xff) - 127;

		int expdelta = 0;
		int msb = 0;
		if (exp < -14) {
			expdelta = -14 - exp;
			exp = -15;
			msb = 0x00800000;
		}
		int f_signif_bits = (absBits & 0x007fffff) | msb;

		int shift = 13 + expdelta;
		int signif_bits = f_signif_bits >>> shift;

		int lsb = f_signif_bits & (1 << shift);
		int round = f_signif_bits & (1 << (shift - 1));
		int sticky = f_signif_bits & ((1 << (shift - 1)) - 1);

		if (round != 0 && ((lsb | sticky) != 0)) {
			signif_bits++;
		}

		return (short) (sign_bit | (((exp + 15) << 10) + signif_bits));
	}

	private static long sweepAll32(java.util.function.IntUnaryOperator encodeBits, boolean requireNanPayloadExact) {
		long bad = 0;
		for (long li = 0; li <= 0xFFFFFFFFL; li++) {
			int bits = (int) li;
			float f = Float.intBitsToFloat(bits);
			short mine = (short) encodeBits.applyAsInt(bits);
			short jdk = Float.floatToFloat16(f);
			if (mine != jdk) {
				boolean nanOk = !requireNanPayloadExact && Float.isNaN(f) && Float.isNaN(Float.float16ToFloat(mine))
						&& Float.isNaN(Float.float16ToFloat(jdk));
				if (!nanOk) {
					bad++;
				}
			}
		}
		return bad;
	}

	public static void main(String[] args) {
		long badB = sweepAll32(bits -> encodeB(Float.intBitsToFloat(bits)) & 0xffff, false);
		long badC = sweepAll32(bits -> encodeC(Float.intBitsToFloat(bits)) & 0xffff, false);
		long badD = sweepAll32(bits -> encodeD(Float.intBitsToFloat(bits)) & 0xffff, true);
		System.out.println("B (plain rescale)           mismatches / 2^32 (NaN payload aside): " + badB);
		System.out.println("C (rescale + 12-bit premask) mismatches / 2^32 (NaN payload aside): " + badC);
		System.out.println("D (literal JDK port)         mismatches / 2^32 (EXACT incl. NaN payload): " + badD);
	}

}
