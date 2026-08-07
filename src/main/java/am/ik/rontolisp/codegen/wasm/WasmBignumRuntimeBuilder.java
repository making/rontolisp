package am.ik.rontolisp.codegen.wasm;

import java.io.ByteArrayOutputStream;

import am.ik.wasm.Instruction;
import am.ik.wasm.Type;
import am.ik.wasm.WasmWriter;

/**
 * Builds the boxed exact-integer (bignum) runtime helpers. An exact integer outside the
 * i31 fixnum range [-2^30, 2^30-1] is a {@code TYPE_BIGNUM} struct holding one i64 field;
 * {@code _int_new} enforces the normalization invariant (an in-range value is ALWAYS a
 * plain i31, so the existing ref.eq/eql fast paths stay valid and a boxed value only ever
 * compares against another boxed value), {@code _int_val} widens either integer
 * representation to i64 (and TRAPS on the limb tier's {@code TYPE_BIGINT}, keeping every
 * boundary exact-or-trap), and {@code _print_i64_no_nl} is the i64 counterpart of the
 * {@code _print_i32_no_nl} digit renderer. Arithmetic beyond the i64 range promotes into
 * the limb tier ({@link WasmBigIntRuntimeBuilder}), so exact integers carry any
 * magnitude.
 */
final class WasmBignumRuntimeBuilder {

	/** Smallest i31 fixnum value. */
	static final long I31_MIN = -0x40000000L;

	/** Largest i31 fixnum value. */
	static final long I31_MAX = 0x3FFFFFFFL;

	private WasmBignumRuntimeBuilder() {
	}

	// _int_new(i64 v) -> (ref null eq): ref.i31 when v fits the 31-bit fixnum range,
	// else a fresh TYPE_BIGNUM box.
	static byte[] buildIntNewBody() {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);

		w.write(0); // no extra locals

		// (v >= I31_MIN) & (v <= I31_MAX)
		getLocal(w, 0);
		w.write(Instruction.I64_CONST);
		w.writeSignedLeb128(I31_MIN);
		w.write(Instruction.I64_GE_S);
		getLocal(w, 0);
		w.write(Instruction.I64_CONST);
		w.writeSignedLeb128(I31_MAX);
		w.write(Instruction.I64_LE_S);
		w.write(Instruction.I32_AND);
		w.write(Instruction.IF);
		w.writeRefType(true, Type.EQ.code());
		getLocal(w, 0);
		w.write(Instruction.I32_WRAP_I64);
		w.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		w.write(Instruction.ELSE);
		getLocal(w, 0);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_BIGNUM);
		w.write(Instruction.END);

		w.write(Instruction.END);
		return body.toByteArray();
	}

	// _int_val((ref null eq) x) -> i64: an i31's value sign-extended, or a TYPE_BIGNUM's
	// field. Any other value traps on the cast (callers guarantee an exact integer).
	static byte[] buildIntValBody() {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);

		w.write(0); // no extra locals

		getLocal(w, 0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(Type.I31.code());
		w.write(Instruction.IF);
		w.write(Type.I64);
		getLocal(w, 0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(Type.I31.code());
		w.write(Instruction.GC_PREFIX, Instruction.I31_GET_S);
		w.write(Instruction.I64_EXTEND_S_I32);
		w.write(Instruction.ELSE);
		getLocal(w, 0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_BIGNUM);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_BIGNUM);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.END);

		w.write(Instruction.END);
		return body.toByteArray();
	}

	// _print_i64_no_nl(i64 v) -> (): the i64 digit renderer, the same
	// digits-into-PRINT_BUF-then-reverse-into-OUT_BUF shape as _print_i32_no_nl
	// (WasmRuntimeBuilder.buildPrintI32Core) so capture mode sees the output through
	// _write_str too. An i64 has at most 19 digits plus the sign, well within the
	// 32-byte print buffer. i64.min_value negates to itself, but _int_new-produced
	// values reach it only through deliberate wraparound; its digits then print with
	// the unsigned remainder loop (rem_u/div_u after the flip), matching two's
	// complement magnitude for every representable magnitude.
	static byte[] buildPrintI64NoNlBody() {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);

		// locals: 1 = i32 negative flag, 2 = i32 digit count, 3 = i32 reverse cursor
		w.write(1);
		w.write(3);
		w.write(Type.I32);

		// neg = v < 0; if (neg) v = 0 - v
		getLocal(w, 0);
		w.write(Instruction.I64_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.I64_LT_S);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(1);
		getLocal(w, 1);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.I64_CONST);
		w.writeSignedLeb128(0);
		getLocal(w, 0);
		w.write(Instruction.I64_SUB);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.END);

		// if (v == 0) { buf[0] = '0'; len = 1 } else digit loop
		getLocal(w, 0);
		w.write(Instruction.I64_EQZ);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.PRINT_BUF_OFFSET);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(48);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(2);
		w.write(Instruction.ELSE);

		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		getLocal(w, 0);
		w.write(Instruction.I64_EQZ);
		w.write(Instruction.BR_IF, 1);

		// buf[PRINT_BUF + len] = '0' + (v % 10)
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.PRINT_BUF_OFFSET);
		getLocal(w, 2);
		w.write(Instruction.I32_ADD);
		getLocal(w, 0);
		w.write(Instruction.I64_CONST);
		w.writeSignedLeb128(10);
		w.write(Instruction.I64_REM_U);
		w.write(Instruction.I32_WRAP_I64);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(48);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);

		getLocal(w, 2);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(2);

		getLocal(w, 0);
		w.write(Instruction.I64_CONST);
		w.writeSignedLeb128(10);
		w.write(Instruction.I64_DIV_U);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(0);

		w.write(Instruction.BR, 0);
		w.write(Instruction.END);
		w.write(Instruction.END);

		w.write(Instruction.END);

		// if (neg) out[0] = '-'
		getLocal(w, 1);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.OUT_BUF_OFFSET);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(45);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		w.write(Instruction.END);

		// reverse the digits into OUT_BUF (after the optional sign)
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(3);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		getLocal(w, 3);
		getLocal(w, 2);
		w.write(Instruction.I32_GE_U);
		w.write(Instruction.BR_IF, 1);

		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.OUT_BUF_OFFSET);
		getLocal(w, 1);
		w.write(Instruction.I32_ADD);
		getLocal(w, 3);
		w.write(Instruction.I32_ADD);

		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.PRINT_BUF_OFFSET);
		getLocal(w, 2);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_SUB);
		getLocal(w, 3);
		w.write(Instruction.I32_SUB);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);

		w.write(Instruction.I32_STORE8, 0x00, 0x00);

		getLocal(w, 3);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(3);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END);
		w.write(Instruction.END);

		// _write_str(OUT_BUF, sign + digits) so capture mode also sees the output
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.OUT_BUF_OFFSET);
		getLocal(w, 1);
		getLocal(w, 2);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);

		w.write(Instruction.END);
		return body.toByteArray();
	}

	private static void getLocal(WasmWriter w, int slot) {
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(slot);
	}

}
