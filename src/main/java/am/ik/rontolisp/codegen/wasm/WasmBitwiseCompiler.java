package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;

/**
 * Compiles the bitwise integer built-ins ({@code logand}, {@code logior}, {@code logxor},
 * {@code lognot}, {@code ash}, {@code integer-length}, {@code logbitp}). Operands are
 * exact integers (i31 fixnums or boxed {@code TYPE_BIGNUM} i64 values), unboxed to i64
 * through {@code _int_val} and re-normalized through {@code _int_new}, so results are
 * exact through the full i64 range (an i31 overflow promotes to a bignum box; e.g.
 * {@code (ash 1 32)} is 4294967296). Results past 64 bits wrap, matching the arithmetic
 * fast paths. {@code ash} shifts left for a non-negative count and right (arithmetic)
 * otherwise; a right shift's magnitude clamps at 63 so a large negative count yields the
 * sign word (0 or -1) rather than a mod-64 artifact. {@code logbitp} clamps its index the
 * same way.
 */
final class WasmBitwiseCompiler {

	private WasmBitwiseCompiler() {
	}

	static void compileLogand(LispCons cons, WasmLispCompiler.Ctx ctx) {
		compileBinary(cons, ctx, Instruction.I64_AND);
	}

	static void compileLogior(LispCons cons, WasmLispCompiler.Ctx ctx) {
		compileBinary(cons, ctx, Instruction.I64_OR);
	}

	static void compileLogxor(LispCons cons, WasmLispCompiler.Ctx ctx) {
		compileBinary(cons, ctx, Instruction.I64_XOR);
	}

	private static void compileBinary(LispCons cons, WasmLispCompiler.Ctx ctx, int op) {
		List<LispVal> args = cons.toList();
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		emitIntVal(ctx);
		WasmExprCompiler.compileExpr(args.get(2), ctx);
		emitIntVal(ctx);
		ctx.writer.write(op);
		emitIntNew(ctx);
	}

	static void compileLognot(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		// (lognot x) == x XOR -1 (WASM has no integer not instruction).
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		emitIntVal(ctx);
		ctx.writer.write(Instruction.I64_CONST);
		ctx.writer.writeSignedLeb128(-1);
		ctx.writer.write(Instruction.I64_XOR);
		emitIntNew(ctx);
	}

	static void compileAsh(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		// allocTemp() yields eqref-typed locals, so store the boxed values and unbox to
		// i64 on each read.
		int vSlot = ctx.allocTemp();
		int cSlot = ctx.allocTemp();
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeSignedLeb128(vSlot);
		WasmExprCompiler.compileExpr(args.get(2), ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeSignedLeb128(cSlot);
		// val1 = value << count
		emitGetI64(ctx, vSlot);
		emitGetI64(ctx, cSlot);
		ctx.writer.write(Instruction.I64_SHL);
		// val2 = value >> min(0 - count, 63): i64.shr_s masks its count mod 64, so a
		// magnitude past 63 must saturate to keep (ash x -100) at the sign word.
		emitGetI64(ctx, vSlot);
		emitClampedShiftCount(ctx, () -> {
			ctx.writer.write(Instruction.I64_CONST);
			ctx.writer.writeSignedLeb128(0);
			emitGetI64(ctx, cSlot);
			ctx.writer.write(Instruction.I64_SUB);
		});
		ctx.writer.write(Instruction.I64_SHR_S);
		// cond = count >= 0; select the left shift when true, otherwise the right shift
		emitGetI64(ctx, cSlot);
		ctx.writer.write(Instruction.I64_CONST);
		ctx.writer.writeSignedLeb128(0);
		ctx.writer.write(Instruction.I64_GE_S);
		ctx.writer.write(Instruction.SELECT);
		emitIntNew(ctx);
	}

	static void compileIntegerLength(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		// integer-length = 64 - clz(x ^ (x >> 63)): the arithmetic-shift term flips a
		// negative x to its ones' complement (so -5 and 4 share a length), leaves a
		// non-negative x unchanged. Store the boxed value once, unbox twice.
		int slot = ctx.allocTemp();
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeSignedLeb128(slot);
		ctx.writer.write(Instruction.I64_CONST);
		ctx.writer.writeSignedLeb128(64);
		emitGetI64(ctx, slot);
		emitGetI64(ctx, slot);
		ctx.writer.write(Instruction.I64_CONST);
		ctx.writer.writeSignedLeb128(63);
		ctx.writer.write(Instruction.I64_SHR_S);
		ctx.writer.write(Instruction.I64_XOR);
		ctx.writer.write(Instruction.I64_CLZ);
		ctx.writer.write(Instruction.I64_SUB);
		emitIntNew(ctx);
	}

	static void compileLogbitp(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		// (logbitp index n) == (n >> min(index, 63)) & 1 (arithmetic shift, so a
		// negative n reads 1 for every index at or above its sign bit). The boxed index
		// is stored once so the clamp can read it twice.
		int idxSlot = ctx.allocTemp();
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeSignedLeb128(idxSlot);
		WasmExprCompiler.compileExpr(args.get(2), ctx);
		emitIntVal(ctx);
		emitClampedShiftCount(ctx, () -> emitGetI64(ctx, idxSlot));
		ctx.writer.write(Instruction.I64_SHR_S);
		ctx.writer.write(Instruction.I64_CONST);
		ctx.writer.writeSignedLeb128(1);
		ctx.writer.write(Instruction.I64_AND);
		ctx.writer.write(Instruction.I32_WRAP_I64);
		WasmEmitHelper.emitBoolFromI32(ctx);
	}

	// Pushes min(count, 63) as an i64. allocTemp() hands out eqref locals only, so
	// instead of stashing the i64 in a local the count expression is emitted twice
	// (once for the value, once for the comparison); pushCount must therefore be
	// side-effect-free (a local read or constant arithmetic over one).
	private static void emitClampedShiftCount(WasmLispCompiler.Ctx ctx, Runnable pushCount) {
		pushCount.run();
		ctx.writer.write(Instruction.I64_CONST);
		ctx.writer.writeSignedLeb128(63);
		pushCount.run();
		ctx.writer.write(Instruction.I64_CONST);
		ctx.writer.writeSignedLeb128(63);
		ctx.writer.write(Instruction.I64_LT_S);
		ctx.writer.write(Instruction.SELECT);
	}

	private static void emitGetI64(WasmLispCompiler.Ctx ctx, int slot) {
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(slot);
		emitIntVal(ctx);
	}

	private static void emitIntVal(WasmLispCompiler.Ctx ctx) {
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.FUNC_INT_VAL);
	}

	private static void emitIntNew(WasmLispCompiler.Ctx ctx) {
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.FUNC_INT_NEW);
	}

}
