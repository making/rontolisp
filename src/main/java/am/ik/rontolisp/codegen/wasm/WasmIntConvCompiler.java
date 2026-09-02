package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;

/**
 * Compiles integer conversion functions ({@code truncate}, {@code floor},
 * {@code ceiling}, {@code round}). All convert a number to an integer using runtime
 * dispatch: an exact integer is its own conversion, a ratio goes through the rational
 * runtime helper, and a float applies the f64 rounding then truncates to i64. A direct
 * {@code (op (/ a b))} shape -- which is also what the two-argument
 * {@code (truncate a b)} family lowers to -- fuses into {@code _big_fdiv} when both
 * operands are exact integers, so the division stays exact at any magnitude (the ratio
 * intermediate cannot hold limb components).
 */
final class WasmIntConvCompiler {

	/** 2^63 as a double: the first magnitude an i64 cannot hold. */
	private static final double LONG_LIMIT = 9.223372036854776E18;

	private WasmIntConvCompiler() {
	}

	static void compileTruncate(LispCons cons, WasmLispCompiler.Ctx ctx) {
		compile(cons, ctx, -1, WasmLispCompiler.FUNC_RAT_TRUNC, 0);
	}

	static void compileFloor(LispCons cons, WasmLispCompiler.Ctx ctx) {
		compile(cons, ctx, Instruction.F64_FLOOR, WasmLispCompiler.FUNC_RAT_FLOOR, 1);
	}

	static void compileCeiling(LispCons cons, WasmLispCompiler.Ctx ctx) {
		compile(cons, ctx, Instruction.F64_CEIL, WasmLispCompiler.FUNC_RAT_CEIL, 2);
	}

	static void compileRound(LispCons cons, WasmLispCompiler.Ctx ctx) {
		compile(cons, ctx, Instruction.F64_NEAREST, WasmLispCompiler.FUNC_RAT_ROUND, 3);
	}

	private static void compile(LispCons cons, WasmLispCompiler.Ctx ctx, int f64RoundingOp, int ratioFunc,
			int fdivMode) {
		List<LispVal> args = cons.toList();
		int tmpSlot = ctx.allocTemp();
		// (op (/ a b)): evaluate a and b once; two exact integers divide exactly
		// through _big_fdiv, anything else recreates the plain (/ a b) value and
		// falls through to the generic conversion below.
		if (args.get(1) instanceof LispCons inner && inner.car() instanceof LispSymbol innerOp
				&& LispNames.DIV.equals(innerOp.name()) && inner.isProperList() && inner.toList().size() == 3) {
			List<LispVal> divArgs = inner.toList();
			int aSlot = ctx.allocTemp();
			int bSlot = ctx.allocTemp();
			WasmExprCompiler.compileExpr(divArgs.get(1), ctx);
			ctx.writer.write(Instruction.SET_LOCAL);
			ctx.writer.writeUnsignedLeb128(aSlot);
			WasmExprCompiler.compileExpr(divArgs.get(2), ctx);
			ctx.writer.write(Instruction.SET_LOCAL);
			ctx.writer.writeUnsignedLeb128(bSlot);
			emitIsExactInt(ctx, aSlot);
			emitIsExactInt(ctx, bSlot);
			ctx.writer.write(Instruction.I32_AND);
			ctx.writer.write(Instruction.IF);
			ctx.writer.writeRefType(true, am.ik.wasm.Type.EQ.code());
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeUnsignedLeb128(aSlot);
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeUnsignedLeb128(bSlot);
			ctx.writer.write(Instruction.I32_CONST);
			ctx.writer.writeSignedLeb128(fdivMode);
			ctx.writer.write(Instruction.CALL);
			ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_BIG_FDIV);
			ctx.writer.write(Instruction.ELSE);
			// A FLOAT operand divides exactly too: _f64_fdiv reads both operands as the
			// exact rationals they are, so the quotient is the mathematical integer at
			// any magnitude rather than the rounded double (/ a b) narrowed into an i64,
			// and the remainder beside it stays rem/mod (.kb/linalg-simd.md, "mod/rem").
			// It answers a null for the pairs it does not improve on -- a ratio operand,
			// a non-finite float, a zero divisor -- which fall through to the ordinary
			// division below.
			int exactSlot = ctx.allocTemp();
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeUnsignedLeb128(aSlot);
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeUnsignedLeb128(bSlot);
			ctx.writer.write(Instruction.I32_CONST);
			ctx.writer.writeSignedLeb128(fdivMode);
			ctx.writer.write(Instruction.CALL);
			ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_F64_FDIV);
			ctx.writer.write(Instruction.TEE_LOCAL);
			ctx.writer.writeUnsignedLeb128(exactSlot);
			ctx.writer.write(Instruction.REF_IS_NULL);
			ctx.writer.write(Instruction.IF);
			ctx.writer.writeRefType(true, am.ik.wasm.Type.EQ.code());
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeUnsignedLeb128(aSlot);
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeUnsignedLeb128(bSlot);
			ctx.writer.write(Instruction.CALL);
			ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_RAT_DIV);
			ctx.writer.write(Instruction.SET_LOCAL);
			ctx.writer.writeUnsignedLeb128(tmpSlot);
			emitGenericFromSlot(ctx, tmpSlot, f64RoundingOp, ratioFunc, fdivMode);
			ctx.writer.write(Instruction.ELSE);
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeUnsignedLeb128(exactSlot);
			ctx.writer.write(Instruction.END);
			ctx.writer.write(Instruction.END);
			return;
		}
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(tmpSlot);
		emitGenericFromSlot(ctx, tmpSlot, f64RoundingOp, ratioFunc, fdivMode);
	}

	// The generic conversion over the value stored in tmpSlot: ratio -> the rational
	// runtime helper; exact integer -> identity; float -> f64 rounding + i64 trunc.
	private static void emitGenericFromSlot(WasmLispCompiler.Ctx ctx, int tmpSlot, int f64RoundingOp, int ratioFunc,
			int fdivMode) {
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(tmpSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_RATIO);
		ctx.writer.write(Instruction.IF);
		ctx.writer.writeRefType(true, am.ik.wasm.Type.EQ.code());
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(tmpSlot);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeUnsignedLeb128(ratioFunc);
		ctx.writer.write(Instruction.ELSE);
		// Exact-integer path: an i31, boxed or limb integer is already its own
		// conversion (the f64 route below would trap on a value past the i64 range).
		emitIsExactInt(ctx, tmpSlot);
		ctx.writer.write(Instruction.IF);
		ctx.writer.writeRefType(true, am.ik.wasm.Type.EQ.code());
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(tmpSlot);
		ctx.writer.write(Instruction.ELSE);
		// Float path: apply the f64 rounding, then truncate to i64 and re-normalize
		// (so a float past the i31 range converts to a boxed integer). Inside the i64
		// range that narrowing is exact -- every double past 2^52 is already an
		// integer, so rounding cannot carry a value across the boundary. PAST it the
		// answer is a bignum, and _f64_fdiv over a divisor of one is what widens it
		// exactly instead of clamping at i64.max; a NaN fails the magnitude test
		// (f64.lt is false for it) and an infinity is declined by _f64_fdiv, so both
		// keep the saturating narrowing they always had.
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(tmpSlot);
		WasmEmitHelper.castFloatGetF64(ctx);
		ctx.writer.write(Instruction.F64_ABS);
		ctx.writer.write(Instruction.F64_CONST);
		ctx.writer.writeF64(LONG_LIMIT);
		ctx.writer.write(Instruction.F64_LT);
		ctx.writer.write(Instruction.IF);
		ctx.writer.writeRefType(true, am.ik.wasm.Type.EQ.code());
		emitNarrowedFromSlot(ctx, tmpSlot, f64RoundingOp);
		ctx.writer.write(Instruction.ELSE);
		int wideSlot = ctx.allocTemp();
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(tmpSlot);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(1);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(fdivMode);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_F64_FDIV);
		ctx.writer.write(Instruction.TEE_LOCAL);
		ctx.writer.writeUnsignedLeb128(wideSlot);
		ctx.writer.write(Instruction.REF_IS_NULL);
		ctx.writer.write(Instruction.IF);
		ctx.writer.writeRefType(true, am.ik.wasm.Type.EQ.code());
		emitNarrowedFromSlot(ctx, tmpSlot, f64RoundingOp);
		ctx.writer.write(Instruction.ELSE);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(wideSlot);
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.END);
	}

	/** The f64 rounding of the float in the slot, saturating into an i64 integer. */
	private static void emitNarrowedFromSlot(WasmLispCompiler.Ctx ctx, int tmpSlot, int f64RoundingOp) {
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(tmpSlot);
		WasmEmitHelper.castFloatGetF64(ctx);
		if (f64RoundingOp >= 0) {
			ctx.writer.write(f64RoundingOp);
		}
		ctx.writer.write(Instruction.MISC_PREFIX);
		ctx.writer.writeUnsignedLeb128(Instruction.I64_TRUNC_SAT_F64_S);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_INT_NEW);
	}

	// Pushes `local[slot] is (i31 | TYPE_BIGNUM | TYPE_BIGINT)` as an i32.
	private static void emitIsExactInt(WasmLispCompiler.Ctx ctx, int slot) {
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(am.ik.wasm.Type.I31.code());
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_BIGNUM);
		ctx.writer.write(Instruction.I32_OR);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_BIGINT);
		ctx.writer.write(Instruction.I32_OR);
	}

}
