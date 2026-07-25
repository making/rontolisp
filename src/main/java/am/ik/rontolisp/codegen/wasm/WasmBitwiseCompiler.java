package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;

/**
 * Compiles the bitwise integer built-ins ({@code logand}, {@code logior}, {@code logxor},
 * {@code lognot}, {@code ash}, {@code integer-length}, {@code logbitp}). Every operator
 * calls its {@code _big_*} runtime helper ({@link WasmBigIntRuntimeBuilder}), which keeps
 * an i64 fast path for i31 / boxed-i64 operands and switches to the two's-complement limb
 * representation for anything wider -- so results are exact at any magnitude ({@code (ash
 * 1 256)} is the full 78-digit integer, and the {@code ldb}/{@code dpb}/
 * {@code logandc1}-family macro lowerings that arrive here inherit the same range).
 */
final class WasmBitwiseCompiler {

	private WasmBitwiseCompiler() {
	}

	static void compileLogand(LispCons cons, WasmLispCompiler.Ctx ctx) {
		compileBinary(cons, ctx, WasmLispCompiler.FUNC_BIG_AND);
	}

	static void compileLogior(LispCons cons, WasmLispCompiler.Ctx ctx) {
		compileBinary(cons, ctx, WasmLispCompiler.FUNC_BIG_OR);
	}

	static void compileLogxor(LispCons cons, WasmLispCompiler.Ctx ctx) {
		compileBinary(cons, ctx, WasmLispCompiler.FUNC_BIG_XOR);
	}

	static void compileAsh(LispCons cons, WasmLispCompiler.Ctx ctx) {
		compileBinary(cons, ctx, WasmLispCompiler.FUNC_BIG_ASH);
	}

	private static void compileBinary(LispCons cons, WasmLispCompiler.Ctx ctx, int func) {
		List<LispVal> args = cons.toList();
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		WasmExprCompiler.compileExpr(args.get(2), ctx);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeSignedLeb128(func);
	}

	static void compileLognot(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.FUNC_BIG_NOT);
	}

	static void compileIntegerLength(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.FUNC_BIG_INTLEN);
	}

	static void compileLogbitp(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		WasmExprCompiler.compileExpr(args.get(2), ctx);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.FUNC_BIG_LOGBITP);
		WasmEmitHelper.emitBoolFromI32(ctx);
	}

}
