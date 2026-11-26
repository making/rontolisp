package am.ik.rontolisp;

/**
 * The single authority for the {@code bfloat16} conversion pair, shared by every backend.
 * A bfloat16 is the TOP SIXTEEN BITS of an IEEE 754 binary32: one sign bit, the same
 * eight exponent bits as an f32, and seven mantissa bits. There is no bfloat16 scalar --
 * a Lisp float is always a {@code double-float} -- so both directions cross a
 * {@code double} here, and {@code rontolisp:bfloat16-bits} /
 * {@code rontolisp:bits-bfloat16} are these two methods.
 *
 * <p>
 * {@link #value(int)} is EXACT and TOTAL: every one of the 65536 patterns names a
 * {@code double}, and {@link #bits(double)} takes it back unchanged, NaN payloads
 * included. {@link #bits(double)} rounds to NEAREST EVEN -- a truncating {@code >>> 16}
 * would pass a casual test and bias every sum downward, which shows up as drift in a
 * model's output rather than as a failure.
 *
 * <p>
 * Two properties are worth stating because they are choices, not consequences.
 * <b>Ordinary values round twice</b>: a {@code double} is narrowed to an f32 and the f32
 * to a bfloat16, so a value sitting between two f32s either side of a bfloat16 midpoint
 * can land on the other neighbour than a single direct rounding would choose. That is
 * deliberate -- the packed array stores the top half of an f32, and the scalar pair must
 * answer what storing into it and reading it back would answer. <b>NaN does not go
 * through the f32 at all</b>: the hardware quiets a signalling NaN on the way down (126
 * of the 65536 patterns, measured), and WASM's {@code f32.demote_f64} is free to invent
 * any payload it likes, so the payload's top seven bits are carried across by hand
 * instead. A NaN whose top seven payload bits are all zero would read back as an
 * infinity, so it becomes the smallest NaN rather than changing class.
 *
 * <p>
 * The compile backends emit this arithmetic inline rather than calling in
 * ({@code codegen.jvm.JvmBFloat16Compiler}, {@code codegen.wasm.WasmBFloat16Compiler});
 * all four are pinned against each other by the {@code bfloat16-bits} case in
 * {@code ci-spec.yaml}. See {@code .kb/bfloat16.md}.
 */
public final class BFloat16 {

	/** The binary64 exponent field, all ones: the NaN/infinity discriminator. */
	private static final long EXPONENT_MASK = 0x7ff0000000000000L;

	/** The binary64 mantissa field. */
	private static final long MANTISSA_MASK = 0x000fffffffffffffL;

	private BFloat16() {
	}

	/**
	 * The bfloat16 bit pattern of a value, rounded to nearest even.
	 * @param value the value
	 * @return the pattern, an integer 0..65535
	 */
	public static int bits(double value) {
		long b = Double.doubleToRawLongBits(value);
		if ((b & EXPONENT_MASK) == EXPONENT_MASK && (b & MANTISSA_MASK) != 0L) {
			int payload = (int) ((b >>> 45) & 0x7f);
			// payload | ((payload - 1) >>> 31): a zero payload becomes one, so a NaN
			// never comes back as an infinity. Branch-free because the two compile
			// backends emit this arithmetic instruction for instruction.
			return ((int) (b >>> 63) << 15) | 0x7f80 | (payload | ((payload - 1) >>> 31));
		}
		return bits((float) value);
	}

	/**
	 * The bfloat16 bit pattern of a single-precision value, rounded to nearest even. The
	 * arm a packed single-float array's bulk narrowing wants: no {@code double} in the
	 * way, so a signalling NaN keeps its payload without depending on what the hardware's
	 * own widening does to one.
	 * @param value the value
	 * @return the pattern, an integer 0..65535
	 */
	public static int bits(float value) {
		int f = Float.floatToRawIntBits(value);
		if ((f & 0x7f800000) == 0x7f800000 && (f & 0x007fffff) != 0) {
			// NaN, handled before the bias-add below: a heavy payload's low bits would
			// carry all the way into the sign there and answer a signed zero's pattern.
			int payload = (f >>> 16) & 0x7f;
			return (f >>> 16) & 0x8000 | 0x7f80 | (payload | ((payload - 1) >>> 31));
		}
		// Round to nearest even: add half an ulp, plus one more when the surviving low
		// bit is odd, then drop the sixteen bits below. An infinity survives it (the
		// carry cannot leave the exponent field) and an overflowing finite becomes one.
		return ((f + 0x7fff + ((f >>> 16) & 1)) >>> 16) & 0xffff;
	}

	/**
	 * The value a bfloat16 bit pattern encodes. Exact for every pattern; only the low
	 * sixteen bits of the argument are read.
	 * @param bits the pattern
	 * @return the value
	 */
	public static double value(int bits) {
		int b = bits & 0xffff;
		if ((b & 0x7f80) == 0x7f80 && (b & 0x7f) != 0) {
			return Double.longBitsToDouble(((long) (b & 0x8000) << 48) | EXPONENT_MASK | ((long) (b & 0x7f) << 45));
		}
		return Float.intBitsToFloat(b << 16);
	}

}
