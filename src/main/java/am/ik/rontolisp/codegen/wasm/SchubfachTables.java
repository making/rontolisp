package am.ik.rontolisp.codegen.wasm;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * The Schubfach shortest-decimal machinery shared by the two WASM backends: the powers
 * table both emit into linear memory, and a Java mirror of the exact u64 arithmetic the
 * emitted wasm performs, so tests can pin the algorithm against
 * {@code am.ik.rontolisp.FloatText} (i.e. {@code Double.toString} /
 * {@code Float.toString}) without running a wasm engine.
 *
 * <p>
 * The algorithm is Schubfach (Giulietti, "The Schubfach way to render doubles"), the same
 * selection {@code Double.toString} performs: the decimal with the fewest digits that
 * rounds back to the value, refined to two digits minimum, closest-to-value, ties to even
 * significand. The full table in that formulation is 617 entries of 126 bits (~9.9 KB).
 * What is emitted instead is a SPARSE table of every 27th entry plus a 5^j multiplier
 * table and a 2-bit correction per k -- ~0.75 KB -- and {@code g(k)} is recomposed at
 * runtime as {@code round(gSparse * 5^j)} normalized to 126 bits plus the correction. The
 * corrections are derived here at emit time by comparing the exact BigInteger value with
 * a bit-exact replay of the runtime recomposition, and asserted to fit two bits, so the
 * compressed table cannot silently disagree with the exact one.
 *
 * <p>
 * Blob layout (little-endian):
 * <ul>
 * <li>{@code [SPARSE_OFF, POW5_OFF)}: {@code SPARSE_COUNT} entries of 16 bytes, the
 * 126-bit {@code g} for {@code k = K_MIN + m*PERIOD} as (low i64, high i64);</li>
 * <li>{@code [POW5_OFF, CORR_OFF)}: {@code 5^j} for {@code j} in {@code [0, PERIOD)} as
 * i64;</li>
 * <li>{@code [CORR_OFF, BLOB_SIZE)}: the per-k corrections, {@code corr + 1} in 2 bits,
 * packed four per byte, indexed by {@code k - K_MIN}.</li>
 * </ul>
 */
final class SchubfachTables {

	private SchubfachTables() {
	}

	/** Smallest table exponent: {@code flog10pow2(Q_MIN)} for double = -324. */
	static final int K_MIN = -324;

	/** Largest table exponent for double. */
	static final int K_MAX = 292;

	/** Sparse-table stride; 5^(PERIOD-1) must fit in an unsigned i64. */
	static final int PERIOD = 27;

	/** Sparse entries, covering k up to K_MIN + (SPARSE_COUNT-1)*PERIOD >= K_MAX. */
	static final int SPARSE_COUNT = (K_MAX - K_MIN + PERIOD - 1) / PERIOD + 1;

	static final int SPARSE_OFF = 0;

	static final int POW5_OFF = SPARSE_COUNT * 16;

	static final int CORR_OFF = POW5_OFF + PERIOD * 8;

	static final int BLOB_SIZE = CORR_OFF + (K_MAX - K_MIN + 4) / 4;

	private static final long MASK_63 = (1L << 63) - 1;

	private static final BigInteger MASK64 = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);

	// sparse[m] = {lo, hi} of g(K_MIN + m*PERIOD)
	private static final long[][] SPARSE = new long[SPARSE_COUNT][2];

	private static final long[] POW5 = new long[PERIOD];

	// corr[k - K_MIN] in {-1, 0, 1}
	private static final byte[] CORR = new byte[K_MAX - K_MIN + 1];

	static {
		for (int m = 0; m < SPARSE_COUNT; m++) {
			BigInteger g = gExact(K_MIN + m * PERIOD);
			SPARSE[m][0] = g.longValue();
			SPARSE[m][1] = g.shiftRight(64).longValue();
		}
		POW5[0] = 1L;
		for (int j = 1; j < PERIOD; j++) {
			POW5[j] = POW5[j - 1] * 5L;
		}
		for (int k = K_MIN; k <= K_MAX; k++) {
			long[] approx = composeNoCorrection(k);
			BigInteger approxBig = BigInteger.valueOf(approx[1])
				.and(MASK64)
				.shiftLeft(64)
				.or(BigInteger.valueOf(approx[0]).and(MASK64));
			BigInteger exact = gExact(k);
			int corr = exact.subtract(approxBig).intValueExact();
			if (corr < -1 || corr > 1) {
				throw new AssertionError("Schubfach correction out of range at k=" + k + ": " + corr);
			}
			CORR[k - K_MIN] = (byte) corr;
		}
	}

	/**
	 * The exact 126-bit {@code g} for one exponent: {@code 10^-k = beta * 2^r} with
	 * {@code 2^125 <= beta < 2^126}; {@code g = floor(beta) + 1}.
	 * @param k the exponent of 10
	 * @return the exact g
	 */
	static BigInteger gExact(int k) {
		int r = flog2pow10(-k) - 125;
		BigInteger num = BigInteger.TEN.pow(Math.max(0, -k)).shiftLeft(Math.max(0, -r));
		BigInteger den = BigInteger.TEN.pow(Math.max(0, k)).shiftLeft(Math.max(0, r));
		BigInteger g = num.divide(den).add(BigInteger.ONE);
		if (g.bitLength() != 126) {
			throw new AssertionError("g bit length " + g.bitLength() + " at k=" + k);
		}
		return g;
	}

	// Bit-exact replay of the runtime recomposition, without the correction: multiply
	// the sparse 126-bit entry by 5^j (192-bit product) and truncate to 126 bits.
	private static long[] composeNoCorrection(int k) {
		int idx = k - K_MIN;
		int m = (idx + PERIOD - 1) / PERIOD;
		int j = m * PERIOD - idx;
		long gsLo = SPARSE[m][0];
		long gsHi = SPARSE[m][1];
		long p5 = POW5[j];
		long lo = gsLo * p5;
		long mid0 = Math.unsignedMultiplyHigh(gsLo, p5);
		long t1 = gsHi * p5;
		long hi = Math.unsignedMultiplyHigh(gsHi, p5);
		long mid = mid0 + t1;
		if (Long.compareUnsigned(mid, mid0) < 0) {
			hi++;
		}
		int bitLen = hi != 0 ? 192 - Long.numberOfLeadingZeros(hi) : 128 - Long.numberOfLeadingZeros(mid);
		int shift = bitLen - 126;
		long gLo;
		long gHi;
		if (shift == 0) {
			gLo = lo;
			gHi = mid;
		}
		else {
			gLo = (lo >>> shift) | (mid << (64 - shift));
			gHi = (mid >>> shift) | (hi << (64 - shift));
		}
		return new long[] { gLo, gHi };
	}

	/**
	 * The runtime {@code g(k)} as the emitted wasm computes it (recomposition plus the
	 * 2-bit correction), split for the rop multiplication.
	 * @param k the exponent of 10, in {@code [K_MIN, K_MAX]}
	 * @return {@code {g1, g0}}: the higher and lower 63 bits of g
	 */
	static long[] g(int k) {
		long[] a = composeNoCorrection(k);
		long gLo = a[0];
		long gHi = a[1];
		int corr = CORR[k - K_MIN];
		long newLo = gLo + corr;
		if (corr == 1 && newLo == 0) {
			gHi++;
		}
		if (corr == -1 && gLo == 0) {
			gHi--;
		}
		gLo = newLo;
		long g1 = (gHi << 1) | (gLo >>> 63);
		long g0 = gLo & MASK_63;
		return new long[] { g1, g0 };
	}

	/**
	 * The table bytes both WASM backends place in linear memory.
	 * @return the blob (see the class comment for the layout)
	 */
	static byte[] blob() {
		ByteBuffer buf = ByteBuffer.allocate(BLOB_SIZE).order(ByteOrder.LITTLE_ENDIAN);
		for (long[] entry : SPARSE) {
			buf.putLong(entry[0]);
			buf.putLong(entry[1]);
		}
		for (long p : POW5) {
			buf.putLong(p);
		}
		byte[] out = buf.array();
		for (int idx = 0; idx < CORR.length; idx++) {
			int stored = CORR[idx] + 1; // {-1,0,1} -> {0,1,2}
			out[CORR_OFF + idx / 4] |= (byte) (stored << ((idx & 3) * 2));
		}
		return out;
	}

	// ---- Java mirror of the emitted algorithm (used by the pinning tests) ----

	/** floor(log10(2^e)); exact for the |e| <= 1074 range used here. */
	static int flog10pow2(int e) {
		return (int) (e * 661_971_961_083L >> 41);
	}

	/** floor(log10(3/4 * 2^e)). */
	static int flog10threeQuartersPow2(int e) {
		return (int) ((e * 661_971_961_083L - 274_743_187_321L) >> 41);
	}

	/** floor(log2(10^e)). */
	static int flog2pow10(int e) {
		return (int) (e * 913_124_641_741L >> 38);
	}

	static long rop(long g1, long g0, long cp) {
		long x1 = Math.unsignedMultiplyHigh(g0, cp);
		long y0 = g1 * cp;
		long y1 = Math.unsignedMultiplyHigh(g1, cp);
		long z = (y0 >>> 1) + x1;
		long vbp = y1 + (z >>> 63);
		return vbp | ((z & MASK_63) + MASK_63) >>> 63;
	}

	/**
	 * The shortest-decimal digits and exponent of a finite positive double:
	 * {@code v = digits * 10^k} with no trailing zeros on digits.
	 *
	 * @param digits the decimal significand
	 * @param k the decimal exponent
	 */
	record Dec(long digits, int k) {
	}

	/**
	 * Mirror of the emitted {@code _f64_dec}.
	 * @param v a finite positive nonzero double
	 * @return digits and exponent
	 */
	static Dec f64Dec(double v) {
		long bits = Double.doubleToRawLongBits(v);
		long t = bits & ((1L << 52) - 1);
		int bq = (int) (bits >>> 52) & 0x7FF;
		long f;
		int k;
		int dk = 0;
		long c;
		int q;
		toDecimal: {
			if (bq != 0) {
				int mq = 1075 - bq;
				c = (1L << 52) | t;
				if (0 < mq && mq < 53 && (c >> mq << mq) == c) {
					f = c >> mq;
					k = 0;
					break toDecimal;
				}
				q = -mq;
			}
			else {
				q = -1074;
				if (Long.compareUnsigned(t, 3) < 0) {
					c = 10 * t;
					dk = -1;
				}
				else {
					c = t;
				}
			}
			int out = (int) c & 1;
			long cb = c << 2;
			long cbl;
			if (c != (1L << 52) || q == -1074) {
				cbl = cb - 2;
				k = flog10pow2(q);
			}
			else {
				cbl = cb - 1;
				k = flog10threeQuartersPow2(q);
			}
			int h = q + flog2pow10(-k) + 2;
			long[] g = g(k);
			long vb = rop(g[0], g[1], cb << h);
			long vbl = rop(g[0], g[1], cbl << h);
			long vbr = rop(g[0], g[1], (cb + 2) << h);
			long s = vb >> 2;
			if (Long.compareUnsigned(s, 100) >= 0) {
				long sp10 = 10 * Long.divideUnsigned(s, 10);
				long tp10 = sp10 + 10;
				boolean upin = Long.compareUnsigned(vbl + out, sp10 << 2) <= 0;
				boolean wpin = Long.compareUnsigned((tp10 << 2) + out, vbr) <= 0;
				if (upin != wpin) {
					f = upin ? sp10 : tp10;
					break toDecimal;
				}
			}
			long tt = s + 1;
			boolean uin = Long.compareUnsigned(vbl + out, s << 2) <= 0;
			boolean win = Long.compareUnsigned((tt << 2) + out, vbr) <= 0;
			if (uin != win) {
				f = uin ? s : tt;
				k += dk;
				break toDecimal;
			}
			long cmp = vb - ((s + tt) << 1);
			f = cmp < 0 || (cmp == 0 && (s & 1) == 0) ? s : tt;
			k += dk;
		}
		while (Long.remainderUnsigned(f, 10) == 0) {
			f = Long.divideUnsigned(f, 10);
			k++;
		}
		return new Dec(f, k);
	}

	/**
	 * Mirror of the emitted {@code _f32_dec}: the single-float width, same g table
	 * ({@code g = g1(k) + 1}).
	 * @param v a finite positive nonzero float
	 * @return digits and exponent
	 */
	static Dec f32Dec(float v) {
		int bits = Float.floatToRawIntBits(v);
		int t = bits & ((1 << 23) - 1);
		int bq = (bits >>> 23) & 0xFF;
		long f;
		int k;
		int dk = 0;
		long c;
		int q;
		toDecimal: {
			if (bq != 0) {
				int mq = 150 - bq;
				c = (1 << 23) | t;
				if (0 < mq && mq < 24 && (c >> mq << mq) == c) {
					f = c >> mq;
					k = 0;
					break toDecimal;
				}
				q = -mq;
			}
			else {
				q = -149;
				if (t < 8) {
					c = 10L * t;
					dk = -1;
				}
				else {
					c = t;
				}
			}
			int out = (int) c & 1;
			long cb = c << 2;
			long cbl;
			if (c != (1 << 23) || q == -149) {
				cbl = cb - 2;
				k = flog10pow2(q);
			}
			else {
				cbl = cb - 1;
				k = flog10threeQuartersPow2(q);
			}
			int h = q + flog2pow10(-k) + 33;
			long g = g(k)[0] + 1;
			long vb = ropF(g, cb << h);
			long vbl = ropF(g, cbl << h);
			long vbr = ropF(g, (cb + 2) << h);
			long s = vb >> 2;
			if (s >= 100) {
				long sp10 = 10 * Long.divideUnsigned(s, 10);
				long tp10 = sp10 + 10;
				boolean upin = vbl + out <= sp10 << 2;
				boolean wpin = (tp10 << 2) + out <= vbr;
				if (upin != wpin) {
					f = upin ? sp10 : tp10;
					break toDecimal;
				}
			}
			long tt = s + 1;
			boolean uin = vbl + out <= s << 2;
			boolean win = (tt << 2) + out <= vbr;
			if (uin != win) {
				f = uin ? s : tt;
				k += dk;
				break toDecimal;
			}
			long cmp = vb - ((s + tt) << 1);
			f = cmp < 0 || (cmp == 0 && (s & 1) == 0) ? s : tt;
			k += dk;
		}
		while (f % 10 == 0) {
			f /= 10;
			k++;
		}
		return new Dec(f, k);
	}

	private static long ropF(long g, long cp) {
		long x1 = Math.unsignedMultiplyHigh(g, cp);
		long vbp = x1 >>> 31;
		long mask32 = (1L << 32) - 1;
		return vbp | ((x1 & mask32) + mask32) >>> 32;
	}

}
