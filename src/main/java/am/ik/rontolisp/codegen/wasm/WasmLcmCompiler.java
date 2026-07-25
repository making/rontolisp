package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;

/**
 * Compiles the {@code lcm} built-in: the least common multiple of two integers, computed
 * as {@code abs((a / gcd(a, b)) * b)}. Returns 0 when either argument is 0. Computes in
 * i64, so the full exact-integer range (i31 or boxed) crosses exactly; a product past
 * 2^63 wraps, like the arithmetic built-ins.
 */
final class WasmLcmCompiler {

	private WasmLcmCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		int aSlot = ctx.allocTemp();
		int bSlot = ctx.allocTemp();
		int gaSlot = ctx.allocTemp();
		int gbSlot = ctx.allocTemp();
		int scratch = ctx.allocTemp();
		int prodSlot = ctx.allocTemp();

		WasmExprCompiler.compileExpr(args.get(1), ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeSignedLeb128(aSlot);
		WasmExprCompiler.compileExpr(args.get(2), ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeSignedLeb128(bSlot);

		// if (a == 0 || b == 0) result is 0
		WasmMathHelper.getI64(ctx, aSlot);
		ctx.writer.write(Instruction.I64_EQZ);
		WasmMathHelper.getI64(ctx, bSlot);
		ctx.writer.write(Instruction.I64_EQZ);
		ctx.writer.write(Instruction.I32_OR);
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(Type.REFNULL.code());
		ctx.writer.writeHeapType(Type.EQ.code());
		WasmMathHelper.constI32(ctx, 0);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);

		ctx.writer.write(Instruction.ELSE);
		// Copy a, b into working locals; Euclid mutates them.
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(aSlot);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeSignedLeb128(gaSlot);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(bSlot);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeSignedLeb128(gbSlot);
		WasmMathHelper.emitEuclid(ctx, gaSlot, gbSlot, scratch);
		// prod = (a / gcd) * b, then take abs and normalize through _int_new.
		WasmMathHelper.getI64(ctx, aSlot);
		WasmMathHelper.getI64(ctx, gaSlot);
		ctx.writer.write(Instruction.I64_DIV_S);
		WasmMathHelper.getI64(ctx, bSlot);
		ctx.writer.write(Instruction.I64_MUL);
		WasmMathHelper.setI64(ctx, prodSlot);
		WasmMathHelper.emitAbs64FromLocal(ctx, prodSlot);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.FUNC_INT_NEW);

		ctx.writer.write(Instruction.END);
	}

}
