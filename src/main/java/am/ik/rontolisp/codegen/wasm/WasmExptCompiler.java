package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;

/**
 * Compiles the {@code expt} built-in: repeated rational multiplication of the base by
 * itself {@code power} times, so a ratio base stays exact, an integer base promotes to
 * big integers at any magnitude (the loop runs through {@code _rat_mul}'s tier-aware fast
 * path), and a negative integer exponent yields the reciprocal (e.g. {@code (expt 2 -1)}
 * is {@code 1/2}). Fractional powers require the interpreter or JVM backend.
 */
final class WasmExptCompiler {

	private WasmExptCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		int baseSlot = ctx.allocTemp();
		int pSlot = ctx.allocTemp();
		int rSlot = ctx.allocTemp();

		WasmExprCompiler.compileExpr(args.get(1), ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeSignedLeb128(baseSlot);
		WasmExprCompiler.compileExpr(args.get(2), ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeSignedLeb128(pSlot);

		// Negative exponent: base = (/ 1 base), power = -power.
		WasmMathHelper.getI32(ctx, pSlot);
		WasmMathHelper.constI32(ctx, 0);
		ctx.writer.write(Instruction.I32_LT_S);
		ctx.writer.write(Instruction.IF, 0x40);
		WasmMathHelper.constI32(ctx, 1);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(baseSlot);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.FUNC_RAT_DIV);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeSignedLeb128(baseSlot);
		WasmMathHelper.constI32(ctx, 0);
		WasmMathHelper.getI32(ctx, pSlot);
		ctx.writer.write(Instruction.I32_SUB);
		WasmMathHelper.setI32(ctx, pSlot);
		ctx.writer.write(Instruction.END);

		// r = 1
		WasmMathHelper.constI32(ctx, 1);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeSignedLeb128(rSlot);

		ctx.writer.write(Instruction.BLOCK, 0x40);
		ctx.writer.write(Instruction.LOOP, 0x40);
		// if power <= 0, exit
		WasmMathHelper.getI32(ctx, pSlot);
		WasmMathHelper.constI32(ctx, 0);
		ctx.writer.write(Instruction.I32_LE_S);
		ctx.writer.write(Instruction.BR_IF, 1);
		// r = r * base (rational multiplication keeps ratio bases exact)
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(rSlot);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(baseSlot);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.FUNC_RAT_MUL);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeSignedLeb128(rSlot);
		// power = power - 1
		WasmMathHelper.getI32(ctx, pSlot);
		WasmMathHelper.constI32(ctx, 1);
		ctx.writer.write(Instruction.I32_SUB);
		WasmMathHelper.setI32(ctx, pSlot);
		ctx.writer.write(Instruction.BR, 0);
		ctx.writer.write(Instruction.END); // loop
		ctx.writer.write(Instruction.END); // block

		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(rSlot);
	}

}
