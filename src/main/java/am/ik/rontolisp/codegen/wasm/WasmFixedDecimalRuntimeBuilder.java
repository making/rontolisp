package am.ik.rontolisp.codegen.wasm;

import java.io.ByteArrayOutputStream;

import am.ik.rontolisp.compiler.FixedDecimal;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;
import am.ik.wasm.WasmWriter;

/**
 * Builds {@code _fixed_dec} (FUNC_FIXED_DEC), the {@code %fixed-decimal} primitive:
 * {@code (value, places, int-digits, plus) -> string}, a fixed-point decimal rendered
 * straight out of an unboxed {@code f64}.
 *
 * <p>
 * It is what {@code format}'s {@code ~F} and {@code ~$} lower to. The directive used to
 * expand INLINE into eight ordinary Lisp forms -- scale by {@code 10^places},
 * {@code round} to an integer, {@code princ-to-string} it, then punch in a decimal point
 * with {@code subseq} and {@code %string-concat} -- and every generic operation in that
 * chain was emitted with its full i31 / bignum / bigint / ratio / float ladder at every
 * site. One {@code ~,15F} was 7,616 bytes of caller body plus 22 runtime functions
 * nothing else in the program reached; this whole function is a fraction of that, is
 * shared by every site, and (crucially) the digits never travel through the integer
 * tower, so a program whose only number is a float no longer drags the bignum runtime in.
 *
 * <p>
 * The steps are {@link FixedDecimal}'s, instruction for instruction, because the four
 * backends have to print the same digits: {@code f64.nearest} IS round-half-to-even
 * ({@code Math.rint}), {@code i64.trunc_sat_f64_s} IS the saturating {@code (long)} cast,
 * and {@code 10^places} is the same repeated multiplication rather than a {@code pow}
 * (which WASM has no instruction for).
 *
 * <p>
 * The digits are laid down right to left into the transient heap scratch and handed to
 * {@code _str_fresh}, framed by the two storage quotes a compiled string carries
 * ({@code .kb/core-representation.md}). Dividing by ten as it goes means a magnitude
 * shorter than the requested field simply keeps yielding zeros, which is the left
 * zero-padding for free.
 */
final class WasmFixedDecimalRuntimeBuilder {

	// Params.
	private static final int P_VALUE = 0, P_PLACES = 1, P_INT_DIGITS = 2, P_PLUS = 3;

	// Locals, in declaration order: one eqref (the coercion ladder's scratch), two f64,
	// two i64, nine i32.
	private static final int L_TMP = 4;

	private static final int L_X = 5, L_SCALE = 6;

	private static final int L_M = 7, L_T = 8;

	private static final int L_D = 9, L_N = 10, L_K = 11, L_NEG = 12, L_START = 13, L_TOTAL = 14, L_POS = 15,
			L_LEN = 16, L_SIGN = 17;

	private WasmFixedDecimalRuntimeBuilder() {
	}

	/**
	 * Builds the {@code _fixed_dec} body.
	 * @return the function body (signature {@code (eqref, eqref, eqref, eqref) -> eqref},
	 * {@code TYPE_CALLABLE_BASE + 3})
	 */
	static byte[] build() {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		w.write(4); // four local groups
		w.write(1);
		w.writeRefType(true, Type.EQ.code());
		w.write(2);
		w.write(Type.F64);
		w.write(2);
		w.write(Type.I64);
		w.write(9);
		w.write(Type.I32);

		// x = <value as f64>
		get(w, P_VALUE);
		WasmEmitHelper.castFloatGetF64(w);
		set(w, L_X);
		// d = clamp(places), n = clamp(int-digits) -- the bound is what keeps the digit
		// buffer below finite for a computed ~v,vF parameter.
		unboxClamped(w, P_PLACES, L_D);
		unboxClamped(w, P_INT_DIGITS, L_N);
		// neg = x < 0.0 (strictly, so -0.0 prints unsigned)
		get(w, L_X);
		w.write(Instruction.F64_CONST);
		w.writeF64(0.0);
		w.write(Instruction.F64_LT);
		set(w, L_NEG);
		// scale = 1.0; k = 0; while (k < d) { scale *= 10.0; k++ }
		w.write(Instruction.F64_CONST);
		w.writeF64(1.0);
		set(w, L_SCALE);
		i32(w, 0);
		set(w, L_K);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		get(w, L_K);
		get(w, L_D);
		w.write(Instruction.I32_GE_S);
		w.write(Instruction.BR_IF, 1);
		get(w, L_SCALE);
		w.write(Instruction.F64_CONST);
		w.writeF64(10.0);
		w.write(Instruction.F64_MUL);
		set(w, L_SCALE);
		bump(w, L_K, 1);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END);
		w.write(Instruction.END);
		// m = |nearest(x * scale)| as a saturating i64
		get(w, L_X);
		get(w, L_SCALE);
		w.write(Instruction.F64_MUL);
		w.write(Instruction.F64_NEAREST);
		w.write(Instruction.F64_ABS);
		w.write(Instruction.MISC_PREFIX);
		w.writeUnsignedLeb128(Instruction.I64_TRUNC_SAT_F64_S);
		set(w, L_M);
		// total = number of digits in m (at least one)
		i32(w, 0);
		set(w, L_TOTAL);
		get(w, L_M);
		set(w, L_T);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		bump(w, L_TOTAL, 1);
		get(w, L_T);
		w.write(Instruction.I64_CONST);
		w.writeSignedLeb128(10L);
		w.write(Instruction.I64_DIV_U);
		set(w, L_T);
		get(w, L_T);
		w.write(Instruction.I64_CONST);
		w.writeSignedLeb128(0L);
		w.write(Instruction.I64_NE);
		w.write(Instruction.BR_IF, 0);
		w.write(Instruction.END);
		w.write(Instruction.END);
		// k = max(d + 1, n + d), the minimum field width; then total = max(total, k).
		// `n + d > d + 1` is exactly `n > 1`, which is the cheaper test to select on.
		get(w, L_N);
		get(w, L_D);
		w.write(Instruction.I32_ADD);
		get(w, L_D);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		get(w, L_N);
		i32(w, 1);
		w.write(Instruction.I32_GT_S);
		w.write(Instruction.SELECT);
		set(w, L_K);
		get(w, L_K);
		get(w, L_TOTAL);
		get(w, L_TOTAL);
		get(w, L_K);
		w.write(Instruction.I32_LT_S);
		w.write(Instruction.SELECT);
		set(w, L_TOTAL);
		// sign = neg ? '-' : (plus ? '+' : 0 for none)
		i32(w, '-');
		i32(w, 0);
		i32(w, '+');
		get(w, P_PLUS);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.SELECT);
		get(w, L_NEG);
		w.write(Instruction.SELECT);
		set(w, L_SIGN);
		// len = 2 (the storage quotes) + sign + total + a decimal point when d > 0
		i32(w, 2);
		get(w, L_TOTAL);
		w.write(Instruction.I32_ADD);
		get(w, L_SIGN);
		i32(w, 0);
		w.write(Instruction.I32_NE);
		w.write(Instruction.I32_ADD);
		hasPoint(w);
		w.write(Instruction.I32_ADD);
		set(w, L_LEN);
		// start = heap scratch base, grown to hold the whole string
		i32(w, WasmLispCompiler.HEAP_PTR_ADDR);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		set(w, L_START);
		WasmEmitHelper.emitGrowHeapTo(w, () -> {
			get(w, L_START);
			get(w, L_LEN);
			w.write(Instruction.I32_ADD);
		});
		// mem[start] = '"'; pos = start + 1
		get(w, L_START);
		i32(w, '"');
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		get(w, L_START);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		set(w, L_POS);
		// if (sign) { mem[pos] = sign; pos++ }
		get(w, L_SIGN);
		w.write(Instruction.IF, 0x40);
		get(w, L_POS);
		get(w, L_SIGN);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		bump(w, L_POS, 1);
		w.write(Instruction.END);
		// pos = the LAST byte of the numeric field; the digits are written backwards from
		// there, so the decimal point lands after exactly d of them.
		get(w, L_POS);
		get(w, L_TOTAL);
		w.write(Instruction.I32_ADD);
		hasPoint(w);
		w.write(Instruction.I32_ADD);
		i32(w, 1);
		w.write(Instruction.I32_SUB);
		set(w, L_POS);
		// k = 0; while (k < total) { mem[pos--] = '0' + m % 10; m /= 10; if (++k == d)
		// mem[pos--] = '.' }
		i32(w, 0);
		set(w, L_K);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		get(w, L_K);
		get(w, L_TOTAL);
		w.write(Instruction.I32_GE_S);
		w.write(Instruction.BR_IF, 1);
		get(w, L_POS);
		get(w, L_M);
		w.write(Instruction.I64_CONST);
		w.writeSignedLeb128(10L);
		w.write(Instruction.I64_REM_U);
		w.write(Instruction.I32_WRAP_I64);
		i32(w, '0');
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		get(w, L_M);
		w.write(Instruction.I64_CONST);
		w.writeSignedLeb128(10L);
		w.write(Instruction.I64_DIV_U);
		set(w, L_M);
		bump(w, L_POS, -1);
		bump(w, L_K, 1);
		get(w, L_D);
		i32(w, 0);
		w.write(Instruction.I32_GT_S);
		get(w, L_K);
		get(w, L_D);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.I32_AND);
		w.write(Instruction.IF, 0x40);
		get(w, L_POS);
		i32(w, '.');
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		bump(w, L_POS, -1);
		w.write(Instruction.END);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END);
		w.write(Instruction.END);
		// mem[start + len - 1] = '"'; return _str_fresh(start, len)
		get(w, L_START);
		get(w, L_LEN);
		w.write(Instruction.I32_ADD);
		i32(w, 1);
		w.write(Instruction.I32_SUB);
		i32(w, '"');
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		get(w, L_START);
		get(w, L_LEN);
		WasmEmitHelper.emitStrFreshCall(w);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	// Pushes 1 when the rendering carries a decimal point (places > 0), else 0.
	private static void hasPoint(WasmWriter w) {
		get(w, L_D);
		i32(w, 0);
		w.write(Instruction.I32_GT_S);
	}

	// slot = min(max(i31_value(param), 0), MAX_DIGITS)
	private static void unboxClamped(WasmWriter w, int param, int slot) {
		get(w, param);
		WasmEmitHelper.castI31GetS(w);
		set(w, slot);
		get(w, slot);
		i32(w, 0);
		get(w, slot);
		i32(w, 0);
		w.write(Instruction.I32_GT_S);
		w.write(Instruction.SELECT);
		set(w, slot);
		get(w, slot);
		i32(w, FixedDecimal.MAX_DIGITS);
		get(w, slot);
		i32(w, FixedDecimal.MAX_DIGITS);
		w.write(Instruction.I32_LT_S);
		w.write(Instruction.SELECT);
		set(w, slot);
	}

	private static void bump(WasmWriter w, int slot, int delta) {
		get(w, slot);
		i32(w, Math.abs(delta));
		w.write(delta < 0 ? Instruction.I32_SUB : Instruction.I32_ADD);
		set(w, slot);
	}

	private static void get(WasmWriter w, int slot) {
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(slot);
	}

	private static void set(WasmWriter w, int slot) {
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(slot);
	}

	private static void i32(WasmWriter w, int value) {
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(value);
	}

}
