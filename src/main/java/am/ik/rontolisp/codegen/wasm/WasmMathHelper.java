package am.ik.rontolisp.codegen.wasm;

import am.ik.wasm.Instruction;
import am.ik.wasm.Type;

/**
 * Shared bytecode-emission helpers for the integer math built-ins ({@code gcd},
 * {@code lcm}, {@code signum}, {@code expt}). All operate on boxed integers held in
 * {@code (ref null eq)} locals, since the WASM backend has no scratch i32/i64/f64 locals:
 * the i32 helpers read/write i31 boxes, the i64 helpers go through
 * {@code _int_val}/{@code _int_new} and so accept the full exact-integer range (an i31 or
 * a {@code TYPE_BIGNUM} box, {@code .kb/wasm-bignum.md}).
 */
final class WasmMathHelper {

	private WasmMathHelper() {
	}

	/** Pushes the signed i32 value held (i31-boxed) in {@code slot}. */
	static void getI32(WasmLispCompiler.Ctx ctx, int slot) {
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(slot);
		WasmEmitHelper.castI31GetS(ctx);
	}

	/** Pops an i32 from the stack and stores it (i31-boxed) into {@code slot}. */
	static void setI32(WasmLispCompiler.Ctx ctx, int slot) {
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeSignedLeb128(slot);
	}

	/** Pushes an i32 constant. */
	static void constI32(WasmLispCompiler.Ctx ctx, int value) {
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(value);
	}

	/**
	 * Pushes the signed i64 value of the exact integer (an i31 or a {@code TYPE_BIGNUM}
	 * box) held in {@code slot}, via {@code _int_val}.
	 */
	static void getI64(WasmLispCompiler.Ctx ctx, int slot) {
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(slot);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.FUNC_INT_VAL);
	}

	/**
	 * Pops an i64 from the stack and stores it into {@code slot}, normalized through
	 * {@code _int_new} (an i31 when it fits, a {@code TYPE_BIGNUM} box otherwise).
	 */
	static void setI64(WasmLispCompiler.Ctx ctx, int slot) {
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.FUNC_INT_NEW);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeSignedLeb128(slot);
	}

	/**
	 * Leaves {@code abs(x)} as an i64 on the stack, where {@code x} is the exact integer
	 * held in {@code slot}.
	 */
	static void emitAbs64FromLocal(WasmLispCompiler.Ctx ctx, int slot) {
		getI64(ctx, slot);
		ctx.writer.write(Instruction.I64_CONST);
		ctx.writer.writeSignedLeb128(0);
		ctx.writer.write(Instruction.I64_LT_S);
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(Type.I64);
		ctx.writer.write(Instruction.I64_CONST);
		ctx.writer.writeSignedLeb128(0);
		getI64(ctx, slot);
		ctx.writer.write(Instruction.I64_SUB);
		ctx.writer.write(Instruction.ELSE);
		getI64(ctx, slot);
		ctx.writer.write(Instruction.END);
	}

	/**
	 * Emits the Euclidean algorithm in i64 over two exact-integer working locals. On exit
	 * {@code aSlot} holds the (possibly negative) gcd and {@code bSlot} holds 0;
	 * {@code scratchSlot} is used as the swap temporary. Callers typically take the
	 * absolute value of {@code aSlot} afterwards.
	 */
	static void emitEuclid(WasmLispCompiler.Ctx ctx, int aSlot, int bSlot, int scratchSlot) {
		ctx.writer.write(Instruction.BLOCK, 0x40);
		ctx.writer.write(Instruction.LOOP, 0x40);
		// if b == 0, exit the block
		getI64(ctx, bSlot);
		ctx.writer.write(Instruction.I64_EQZ);
		ctx.writer.write(Instruction.BR_IF, 1);
		// scratch = a % b
		getI64(ctx, aSlot);
		getI64(ctx, bSlot);
		ctx.writer.write(Instruction.I64_REM_S);
		setI64(ctx, scratchSlot);
		// a = b
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(bSlot);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeSignedLeb128(aSlot);
		// b = scratch
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(scratchSlot);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeSignedLeb128(bSlot);
		ctx.writer.write(Instruction.BR, 0);
		ctx.writer.write(Instruction.END); // loop
		ctx.writer.write(Instruction.END); // block
	}

}
