package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;

/**
 * Compiles the {@code expt} built-in for the i31 integer range: repeated multiplication
 * of the base by itself {@code power} times. The exponent must be a non-negative integer
 * (a non-positive exponent yields 1); fractional or negative powers require the
 * interpreter or JVM backend. There is no overflow promotion (i31 wraps).
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

		// r = 1
		WasmMathHelper.constI32(ctx, 1);
		WasmMathHelper.setI32(ctx, rSlot);

		ctx.writer.write(Instruction.BLOCK, 0x40);
		ctx.writer.write(Instruction.LOOP, 0x40);
		// if power <= 0, exit
		WasmMathHelper.getI32(ctx, pSlot);
		WasmMathHelper.constI32(ctx, 0);
		ctx.writer.write(Instruction.I32_LE_S);
		ctx.writer.write(Instruction.BR_IF, 1);
		// r = r * base
		WasmMathHelper.getI32(ctx, rSlot);
		WasmMathHelper.getI32(ctx, baseSlot);
		ctx.writer.write(Instruction.I32_MUL);
		WasmMathHelper.setI32(ctx, rSlot);
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
