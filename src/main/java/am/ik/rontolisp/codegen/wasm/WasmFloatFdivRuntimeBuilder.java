package am.ik.rontolisp.codegen.wasm;

import java.io.ByteArrayOutputStream;

import am.ik.wasm.Instruction;
import am.ik.wasm.Type;
import am.ik.wasm.WasmWriter;

/**
 * Builds {@code _f64_fdiv (a, b, mode) -> quotient | null}: the
 * {@code floor}/{@code ceiling}/{@code round}/{@code truncate} quotient of a division
 * with a FLOAT operand, computed exactly.
 *
 * <p>
 * A finite double is an exact binary rational, so {@code a/b} over two of them has one
 * mathematical value and one correctly rounded integer -- which is a bignum past the
 * {@code long} range, exactly as every other numeric operator in rontolisp promotes
 * rather than saturating. Dividing in f64 and narrowing with {@code i64.trunc_sat_f64_s}
 * instead rounds twice and then clamps: {@code (truncate
 * 1d300 7.0)} answered {@code Long.MAX_VALUE} and {@code (truncate 1d18 7.0)} answered a
 * quotient seven too high, whose remainder no longer satisfied
 * {@code quotient*divisor + remainder = number}. See {@code .kb/linalg-simd.md},
 * "mod/rem", and {@code .kb/wasm-bignum.md}.
 *
 * <p>
 * Both operands become the exact rational they are -- a float as
 * {@code mantissa * 2^exponent} with the mantissa's trailing zeros stripped (so the
 * numerator and denominator stay as small as the value allows), an exact integer as
 * itself over one -- and the cross-multiplied pair goes through {@code _big_fdiv}, the
 * limb-tier exact divider the two-exact-integer arm already uses. Nothing new decides how
 * a quotient rounds; the four modes stay in one place.
 *
 * <p>
 * It DECLINES (answers a null, which the call site reads as "keep the ordinary route")
 * for a ratio operand, a non-finite float and a zero divisor, so the f64 division's
 * non-trapping policy for those is untouched.
 */
final class WasmFloatFdivRuntimeBuilder {

	/** Locals of the emitted body, after the three parameters. */
	private static final int D = 3, BITS = 4, MANT = 5, EXP = 6, TMP = 7, NUM_A = 8, DEN_A = 9, NUM_B = 10, DEN_B = 11,
			A = 12, B = 13;

	private WasmFloatFdivRuntimeBuilder() {
	}

	/**
	 * Builds the {@code _f64_fdiv} body.
	 * @return the encoded function body
	 */
	static byte[] buildBody() {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		// 1 x f64, 2 x i64, 2 x i32, 6 x (ref null eq)
		w.write(4);
		w.write(1);
		w.write(Type.F64);
		w.write(2);
		w.write(Type.I64);
		w.write(2);
		w.write(Type.I32);
		w.write(6);
		w.writeRefType(true, Type.EQ.code());

		// An INFINITE divisor is settled by sign alone, before the general exact-rational
		// route below (which would decline: infinity is not a rational, and
		// emitRationalOf says so). With a finite nonzero dividend, a/b is an
		// infinitesimal whose magnitude is always under 1/2, so truncate/round are
		// always 0 and floor/ceiling read off whether the dividend and the divisor agree
		// in sign. See .kb/linalg-simd.md, "mod/rem".
		get(w, 1);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_FLOAT);
		ifVoid(w);
		get(w, 1);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_FLOAT);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_FLOAT);
		w.writeUnsignedLeb128(0);
		set(w, D);
		get(w, D);
		w.write(Instruction.I64_REINTERPRET_F64);
		set(w, BITS);
		get(w, BITS);
		i64Const(w, 52);
		w.write(Instruction.I64_SHR_U);
		w.write(Instruction.I32_WRAP_I64);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0x7ff);
		w.write(Instruction.I32_AND);
		set(w, TMP);
		get(w, TMP);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0x7ff);
		w.write(Instruction.I32_EQ);
		ifVoid(w);
		// biased exponent all-ones: NaN or infinity -- an infinity's mantissa is zero.
		get(w, BITS);
		i64Const(w, 0x000f_ffff_ffff_ffffL);
		w.write(Instruction.I64_AND);
		w.write(Instruction.I64_EQZ);
		ifVoid(w);
		get(w, BITS);
		i64Const(w, 0);
		w.write(Instruction.I64_LT_S);
		set(w, EXP);
		emitInfiniteDivisorQuotient(w);
		w.write(Instruction.END);
		w.write(Instruction.END);
		w.write(Instruction.END);

		emitRationalOf(w, 0, NUM_A, DEN_A);
		emitRationalOf(w, 1, NUM_B, DEN_B);
		// a/b = (na*db) / (da*nb), with the sign carried on the numerator.
		get(w, NUM_A);
		get(w, DEN_B);
		call(w, WasmLispCompiler.FUNC_BIG_MUL);
		set(w, A);
		get(w, DEN_A);
		get(w, NUM_B);
		call(w, WasmLispCompiler.FUNC_BIG_MUL);
		set(w, B);
		// A zero divisor declines: (/ x 0.0) is an infinity here, not a signal, and an
		// exact zero divisor signals through the route this one keeps.
		get(w, B);
		i31Const(w, 0);
		call(w, WasmLispCompiler.FUNC_BIG_CMP);
		set(w, TMP);
		get(w, TMP);
		w.write(Instruction.I32_EQZ);
		ifVoid(w);
		w.write(Instruction.REF_NULL);
		w.writeHeapType(Type.EQ.code());
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
		// _big_fdiv reads the sign off both operands, so a negative denominator is
		// moved onto the numerator first.
		get(w, TMP);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.I32_LT_S);
		ifVoid(w);
		get(w, A);
		call(w, WasmLispCompiler.FUNC_BIG_NEG);
		set(w, A);
		get(w, B);
		call(w, WasmLispCompiler.FUNC_BIG_NEG);
		set(w, B);
		w.write(Instruction.END);
		get(w, A);
		get(w, B);
		get(w, 2);
		call(w, WasmLispCompiler.FUNC_BIG_FDIV);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	/**
	 * Settles the quotient for a finite dividend over an infinite divisor: {@code a/b} is
	 * then an infinitesimal whose sign is the dividend's sign XOR the divisor's, and
	 * whose magnitude is always under 1/2. Truncate and round are 0 either way; floor and
	 * ceiling round the infinitesimal down or up, so they read off whether the two signs
	 * agree. Called with local {@link #EXP} already holding 1 iff the divisor (local 1)
	 * is negative; RETURNS a null (decline) for a ratio operand, a non-finite float
	 * dividend, or an EXACT-zero dividend ({@code 0/infinity} is genuinely zero, not an
	 * infinitesimal, and the old f64 route already answers that correctly), and RETURNS
	 * the boxed quotient in every other case, so the caller needs no further control flow
	 * after this call.
	 */
	private static void emitInfiniteDivisorQuotient(WasmWriter w) {
		// TMP ends up 1 iff the dividend (local 0) is negative.
		get(w, 0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_FLOAT);
		ifVoid(w);
		get(w, 0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_FLOAT);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_FLOAT);
		w.writeUnsignedLeb128(0);
		set(w, D);
		get(w, D);
		w.write(Instruction.I64_REINTERPRET_F64);
		set(w, BITS);
		get(w, BITS);
		i64Const(w, 52);
		w.write(Instruction.I64_SHR_U);
		w.write(Instruction.I32_WRAP_I64);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0x7ff);
		w.write(Instruction.I32_AND);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0x7ff);
		w.write(Instruction.I32_EQ);
		ifVoid(w);
		// A NaN or an infinite dividend declines.
		w.write(Instruction.REF_NULL);
		w.writeHeapType(Type.EQ.code());
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
		get(w, D);
		w.write(Instruction.F64_CONST);
		w.writeF64(0.0);
		w.write(Instruction.F64_EQ);
		ifVoid(w);
		// An exact-zero dividend declines.
		w.write(Instruction.REF_NULL);
		w.writeHeapType(Type.EQ.code());
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
		get(w, D);
		w.write(Instruction.F64_CONST);
		w.writeF64(0.0);
		w.write(Instruction.F64_LT);
		set(w, TMP);
		w.write(Instruction.ELSE);
		// Not a float: an exact integer (i31/bignum/biglimb) or a ratio.
		emitIsExactInt(w, 0);
		w.write(Instruction.I32_EQZ);
		ifVoid(w);
		// A ratio declines.
		w.write(Instruction.REF_NULL);
		w.writeHeapType(Type.EQ.code());
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
		get(w, 0);
		i31Const(w, 0);
		call(w, WasmLispCompiler.FUNC_BIG_CMP);
		set(w, TMP);
		get(w, TMP);
		w.write(Instruction.I32_EQZ);
		ifVoid(w);
		// An exact-zero dividend declines.
		w.write(Instruction.REF_NULL);
		w.writeHeapType(Type.EQ.code());
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
		get(w, TMP);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.I32_LT_S);
		set(w, TMP);
		w.write(Instruction.END);
		// Same sign iff EXP (divisor negative) equals TMP (dividend negative).
		get(w, EXP);
		get(w, TMP);
		w.write(Instruction.I32_EQ);
		ifVoid(w);
		// Same sign: ceiling (mode 2) is 1, everything else (truncate/floor/round) is 0.
		get(w, 2);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(2);
		w.write(Instruction.I32_EQ);
		ifVoid(w);
		i31Const(w, 1);
		w.write(Instruction.RETURN);
		w.write(Instruction.ELSE);
		i31Const(w, 0);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
		w.write(Instruction.ELSE);
		// Different sign: floor (mode 1) is -1, everything else (truncate/ceiling/round)
		// is 0.
		get(w, 2);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_EQ);
		ifVoid(w);
		i31Const(w, -1);
		w.write(Instruction.RETURN);
		w.write(Instruction.ELSE);
		i31Const(w, 0);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
		w.write(Instruction.END);
	}

	/**
	 * Emits {@code numerator/denominator} of the operand in {@code local[slot]} into the
	 * two given locals, RETURNING a null from the whole function for an operand the exact
	 * route declines (a ratio, a NaN, an infinity).
	 */
	private static void emitRationalOf(WasmWriter w, int slot, int numLocal, int denLocal) {
		get(w, slot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_FLOAT);
		ifVoid(w);
		get(w, slot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_FLOAT);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_FLOAT);
		w.writeUnsignedLeb128(0);
		set(w, D);
		get(w, D);
		w.write(Instruction.I64_REINTERPRET_F64);
		set(w, BITS);
		// biased exponent = (bits >> 52) & 0x7ff
		get(w, BITS);
		i64Const(w, 52);
		w.write(Instruction.I64_SHR_U);
		w.write(Instruction.I32_WRAP_I64);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0x7ff);
		w.write(Instruction.I32_AND);
		set(w, TMP);
		// A NaN or an infinity has no exact integer value: decline.
		get(w, TMP);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0x7ff);
		w.write(Instruction.I32_EQ);
		ifVoid(w);
		w.write(Instruction.REF_NULL);
		w.writeHeapType(Type.EQ.code());
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
		get(w, BITS);
		i64Const(w, 0x000f_ffff_ffff_ffffL);
		w.write(Instruction.I64_AND);
		set(w, MANT);
		get(w, TMP);
		w.write(Instruction.I32_EQZ);
		ifVoid(w);
		// subnormal (and zero): the mantissa is the fraction, scaled by 2^-1074
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(-1074);
		set(w, EXP);
		w.write(Instruction.ELSE);
		get(w, MANT);
		i64Const(w, 0x0010_0000_0000_0000L);
		w.write(Instruction.I64_OR);
		set(w, MANT);
		get(w, TMP);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1075);
		w.write(Instruction.I32_SUB);
		set(w, EXP);
		w.write(Instruction.END);
		// the sign bit, applied to the integer mantissa
		get(w, BITS);
		i64Const(w, 0);
		w.write(Instruction.I64_LT_S);
		ifVoid(w);
		i64Const(w, 0);
		get(w, MANT);
		w.write(Instruction.I64_SUB);
		set(w, MANT);
		w.write(Instruction.END);
		get(w, MANT);
		w.write(Instruction.I64_EQZ);
		ifVoid(w);
		i31Const(w, 0);
		set(w, numLocal);
		i31Const(w, 1);
		set(w, denLocal);
		w.write(Instruction.ELSE);
		// Strip the mantissa's trailing zeros into the exponent: 7.0 becomes 7*2^0
		// rather than 2^52-scaled, which keeps both cross products small and lets an
		// integral value divide by a denominator of one.
		get(w, MANT);
		w.write(Instruction.I64_CTZ);
		w.write(Instruction.I32_WRAP_I64);
		set(w, TMP);
		get(w, MANT);
		get(w, TMP);
		w.write(Instruction.I64_EXTEND_S_I32);
		w.write(Instruction.I64_SHR_S);
		set(w, MANT);
		get(w, EXP);
		get(w, TMP);
		w.write(Instruction.I32_ADD);
		set(w, EXP);
		get(w, EXP);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.I32_GE_S);
		ifVoid(w);
		get(w, MANT);
		call(w, WasmLispCompiler.FUNC_INT_NEW);
		get(w, EXP);
		w.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		call(w, WasmLispCompiler.FUNC_BIG_ASH);
		set(w, numLocal);
		i31Const(w, 1);
		set(w, denLocal);
		w.write(Instruction.ELSE);
		get(w, MANT);
		call(w, WasmLispCompiler.FUNC_INT_NEW);
		set(w, numLocal);
		i31Const(w, 1);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		get(w, EXP);
		w.write(Instruction.I32_SUB);
		w.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		call(w, WasmLispCompiler.FUNC_BIG_ASH);
		set(w, denLocal);
		w.write(Instruction.END);
		w.write(Instruction.END);
		w.write(Instruction.ELSE);
		// An exact integer is itself over one; a ratio (or anything else) declines.
		emitIsExactInt(w, slot);
		w.write(Instruction.I32_EQZ);
		ifVoid(w);
		w.write(Instruction.REF_NULL);
		w.writeHeapType(Type.EQ.code());
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
		get(w, slot);
		set(w, numLocal);
		i31Const(w, 1);
		set(w, denLocal);
		w.write(Instruction.END);
	}

	/** Pushes {@code local[slot] is (i31 | TYPE_BIGNUM | TYPE_BIGINT)} as an i32. */
	private static void emitIsExactInt(WasmWriter w, int slot) {
		get(w, slot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(Type.I31.code());
		get(w, slot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_BIGNUM);
		w.write(Instruction.I32_OR);
		get(w, slot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_BIGINT);
		w.write(Instruction.I32_OR);
	}

	private static void get(WasmWriter w, int slot) {
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(slot);
	}

	private static void set(WasmWriter w, int slot) {
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(slot);
	}

	private static void call(WasmWriter w, int funcIndex) {
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(funcIndex);
	}

	private static void i64Const(WasmWriter w, long value) {
		w.write(Instruction.I64_CONST);
		w.writeSignedLeb128(value);
	}

	private static void i31Const(WasmWriter w, int value) {
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(value);
		w.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
	}

	private static void ifVoid(WasmWriter w) {
		w.write(Instruction.IF, 0x40);
	}

}
