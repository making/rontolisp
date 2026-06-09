package am.ik.rontolisp.codegen.wasm;

import am.ik.wasm.Instruction;
import am.ik.wasm.Type;

/**
 * Shared bytecode-emission helpers for the integer math built-ins ({@code gcd},
 * {@code lcm}, {@code signum}, {@code expt}). All operate on i31-boxed integers held in
 * {@code (ref null eq)} locals, since the WASM backend has no scratch i32/f64 locals.
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
	 * Leaves {@code abs(x)} as an i32 on the stack, where {@code x} is the i31-boxed
	 * integer held in {@code slot}.
	 */
	static void emitAbsFromLocal(WasmLispCompiler.Ctx ctx, int slot) {
		getI32(ctx, slot);
		constI32(ctx, 0);
		ctx.writer.write(Instruction.I32_LT_S);
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(Type.I32);
		constI32(ctx, 0);
		getI32(ctx, slot);
		ctx.writer.write(Instruction.I32_SUB);
		ctx.writer.write(Instruction.ELSE);
		getI32(ctx, slot);
		ctx.writer.write(Instruction.END);
	}

	/**
	 * Emits the Euclidean algorithm over two i31-boxed working locals. On exit
	 * {@code aSlot} holds the (possibly negative) gcd and {@code bSlot} holds 0;
	 * {@code scratchSlot} is used as the swap temporary. Callers typically take the
	 * absolute value of {@code aSlot} afterwards.
	 */
	static void emitEuclid(WasmLispCompiler.Ctx ctx, int aSlot, int bSlot, int scratchSlot) {
		ctx.writer.write(Instruction.BLOCK, 0x40);
		ctx.writer.write(Instruction.LOOP, 0x40);
		// if b == 0, exit the block
		getI32(ctx, bSlot);
		ctx.writer.write(Instruction.I32_EQZ);
		ctx.writer.write(Instruction.BR_IF, 1);
		// scratch = a % b
		getI32(ctx, aSlot);
		getI32(ctx, bSlot);
		ctx.writer.write(Instruction.I32_REM_S);
		setI32(ctx, scratchSlot);
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
