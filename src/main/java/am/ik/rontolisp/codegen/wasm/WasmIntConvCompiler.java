package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;

/**
 * Compiles integer conversion functions ({@code truncate}, {@code floor},
 * {@code ceiling}, {@code round}). All convert a number to an integer using runtime
 * dispatch: extracts f64 value via {@link WasmEmitHelper#castFloatGetF64}, applies the
 * appropriate f64 rounding operation, then truncates to i32 and wraps in i31ref.
 */
final class WasmIntConvCompiler {

	private WasmIntConvCompiler() {
	}

	static void compileTruncate(LispCons cons, WasmLispCompiler.Ctx ctx) {
		compile(cons, ctx, -1, WasmLispCompiler.FUNC_RAT_TRUNC);
	}

	static void compileFloor(LispCons cons, WasmLispCompiler.Ctx ctx) {
		compile(cons, ctx, Instruction.F64_FLOOR, WasmLispCompiler.FUNC_RAT_FLOOR);
	}

	static void compileCeiling(LispCons cons, WasmLispCompiler.Ctx ctx) {
		compile(cons, ctx, Instruction.F64_CEIL, WasmLispCompiler.FUNC_RAT_CEIL);
	}

	static void compileRound(LispCons cons, WasmLispCompiler.Ctx ctx) {
		compile(cons, ctx, Instruction.F64_NEAREST, WasmLispCompiler.FUNC_RAT_ROUND);
	}

	private static void compile(LispCons cons, WasmLispCompiler.Ctx ctx, int f64RoundingOp, int ratioFunc) {
		List<LispVal> args = cons.toList();
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		int tmpSlot = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeSignedLeb128(tmpSlot);
		// Ratio path: exact integer conversion via the rational runtime helper.
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(tmpSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_RATIO);
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(am.ik.wasm.Type.REFNULL.code());
		ctx.writer.writeHeapType(am.ik.wasm.Type.EQ.code());
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(tmpSlot);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeSignedLeb128(ratioFunc);
		ctx.writer.write(Instruction.ELSE);
		// Integer/float path: convert through a f64.
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(tmpSlot);
		WasmEmitHelper.castFloatGetF64(ctx);
		if (f64RoundingOp >= 0) {
			ctx.writer.write(f64RoundingOp);
		}
		ctx.writer.write(Instruction.I32_TRUNC_S_F64);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		ctx.writer.write(Instruction.END);
	}

}
