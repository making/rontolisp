package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;

/**
 * Compiles the bitwise integer built-ins ({@code logand}, {@code logior}, {@code logxor},
 * {@code lognot}, {@code ash}). Operates on the i31 integer range; results that overflow
 * 31 bits are truncated, matching the other integer fast paths ({@code gcd},
 * {@code mod}). {@code ash} shifts left for a non-negative count and right (arithmetic)
 * otherwise.
 */
final class WasmBitwiseCompiler {

	private WasmBitwiseCompiler() {
	}

	static void compileLogand(LispCons cons, WasmLispCompiler.Ctx ctx) {
		compileBinary(cons, ctx, Instruction.I32_AND);
	}

	static void compileLogior(LispCons cons, WasmLispCompiler.Ctx ctx) {
		compileBinary(cons, ctx, Instruction.I32_OR);
	}

	static void compileLogxor(LispCons cons, WasmLispCompiler.Ctx ctx) {
		compileBinary(cons, ctx, Instruction.I32_XOR);
	}

	private static void compileBinary(LispCons cons, WasmLispCompiler.Ctx ctx, int op) {
		List<LispVal> args = cons.toList();
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		WasmEmitHelper.castI31GetS(ctx);
		WasmExprCompiler.compileExpr(args.get(2), ctx);
		WasmEmitHelper.castI31GetS(ctx);
		ctx.writer.write(op);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
	}

	static void compileLognot(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		// (lognot x) == x XOR -1 (WASM has no i32 not instruction).
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		WasmEmitHelper.castI31GetS(ctx);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(-1);
		ctx.writer.write(Instruction.I32_XOR);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
	}

	static void compileAsh(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		// allocTemp() yields eqref-typed locals, so store the boxed values and unbox to
		// i32
		// on each read.
		int vSlot = ctx.allocTemp();
		int cSlot = ctx.allocTemp();
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeSignedLeb128(vSlot);
		WasmExprCompiler.compileExpr(args.get(2), ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeSignedLeb128(cSlot);
		// val1 = value << count
		emitGetI32(ctx, vSlot);
		emitGetI32(ctx, cSlot);
		ctx.writer.write(Instruction.I32_SHL);
		// val2 = value >> (0 - count)
		emitGetI32(ctx, vSlot);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(0);
		emitGetI32(ctx, cSlot);
		ctx.writer.write(Instruction.I32_SUB);
		ctx.writer.write(Instruction.I32_SHR_S);
		// cond = count >= 0; select the left shift when true, otherwise the right shift
		emitGetI32(ctx, cSlot);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(0);
		ctx.writer.write(Instruction.I32_GE_S);
		ctx.writer.write(Instruction.SELECT);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
	}

	private static void emitGetI32(WasmLispCompiler.Ctx ctx, int slot) {
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(slot);
		WasmEmitHelper.castI31GetS(ctx);
	}

}
