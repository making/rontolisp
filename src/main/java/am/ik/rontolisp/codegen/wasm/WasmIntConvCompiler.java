package am.ik.rontolisp.codegen.wasm;

import java.math.BigInteger;
import java.util.List;

import am.ik.rontolisp.ClosRegistry;
import am.ik.rontolisp.LispBigInteger;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispRatio;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;

import org.jspecify.annotations.Nullable;

/**
 * Compiles integer conversion functions ({@code truncate}, {@code floor},
 * {@code ceiling}, {@code round}). All convert a number to an integer using runtime
 * dispatch: an exact integer is its own conversion, a ratio goes through the rational
 * runtime helper, and a float applies the f64 rounding then truncates to i64. A direct
 * {@code (op (/ a b))} shape -- which is also what the two-argument
 * {@code (truncate a b)} family lowers to -- divides inline when both operands are i31
 * and fuses into {@code _big_fdiv} when both are exact integers, so the division stays
 * exact at any magnitude without allocating the ratio intermediate; a ratio literal
 * argument, or two literal integers, is that division with both operands known, and calls
 * {@code _big_fdiv} directly.
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
		BigInteger[] known = knownIntegerOperands(args);
		if (known != null) {
			// A ratio literal -- what the literal fold leaves of (op (/ a b)) over two
			// literal integers -- or the two literal integers of (op a b) themselves:
			// the quotient of two exact integers the compiler already holds, so
			// _big_fdiv over them is the whole conversion, with no ratio built and no
			// type dispatch.
			WasmEmitHelper.compileExactIntegerLiteral(known[0], ctx);
			WasmEmitHelper.compileExactIntegerLiteral(known[1], ctx);
			ctx.writer.write(Instruction.I32_CONST);
			ctx.writer.writeSignedLeb128(fdivMode);
			WasmOperandTypes.emitCall(ctx, WasmLispCompiler.FUNC_BIG_FDIV);
			return;
		}
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
			// The inline arm is a speed trade, and an async resume body has no i64
			// scratch run (as for the fused trees).
			boolean fixnumArm = WasmIntFusionCompiler.speedTradesEnabled(ctx) && ctx.asyncResume == null;
			if (fixnumArm) {
				emitFixnumQuotient(ctx, aSlot, bSlot, fdivMode);
			}
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
			WasmOperandTypes.emitCall(ctx, WasmLispCompiler.FUNC_BIG_FDIV);
			ctx.writer.write(Instruction.ELSE);
			// A FLOAT operand divides exactly too: _f64_fdiv reads both operands as the
			// exact rationals they are, so the quotient is the mathematical integer at
			// any magnitude rather than the rounded double (/ a b) narrowed into an i64,
			// and the remainder beside it stays rem/mod (.kb/linalg-simd.md, "mod / rem
			// and the floor family").
			// It answers a null for the pairs it does not improve on -- a ratio operand,
			// a non-finite float, a zero divisor outside EH mode -- which fall through to
			// the ordinary division below.
			int exactSlot = ctx.allocTemp();
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeUnsignedLeb128(aSlot);
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeUnsignedLeb128(bSlot);
			ctx.writer.write(Instruction.I32_CONST);
			ctx.writer.writeSignedLeb128(fdivMode);
			WasmOperandTypes.emitCall(ctx, WasmLispCompiler.FUNC_F64_FDIV);
			ctx.writer.write(Instruction.TEE_LOCAL);
			ctx.writer.writeUnsignedLeb128(exactSlot);
			ctx.writer.write(Instruction.REF_IS_NULL);
			ctx.writer.write(Instruction.IF);
			ctx.writer.writeRefType(true, am.ik.wasm.Type.EQ.code());
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeUnsignedLeb128(aSlot);
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeUnsignedLeb128(bSlot);
			WasmOperandTypes.emitCall(ctx, WasmLispCompiler.FUNC_RAT_DIV);
			ctx.writer.write(Instruction.SET_LOCAL);
			ctx.writer.writeUnsignedLeb128(tmpSlot);
			emitGenericFromSlot(ctx, tmpSlot, f64RoundingOp, ratioFunc, fdivMode);
			ctx.writer.write(Instruction.ELSE);
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeUnsignedLeb128(exactSlot);
			ctx.writer.write(Instruction.END);
			ctx.writer.write(Instruction.END);
			if (fixnumArm) {
				ctx.writer.write(Instruction.END);
			}
			return;
		}
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(tmpSlot);
		emitGenericFromSlot(ctx, tmpSlot, f64RoundingOp, ratioFunc, fdivMode);
	}

	/**
	 * The dividend and divisor of a ratio literal argument, or of {@code (/ a b)} over
	 * two literal integers with a divisor that is not zero; {@code null} otherwise.
	 */
	private static BigInteger @Nullable [] knownIntegerOperands(List<LispVal> args) {
		if (args.size() != 2) {
			return null;
		}
		if (args.get(1) instanceof LispRatio ratio) {
			return new BigInteger[] { ratio.numerator(), ratio.denominator() };
		}
		if (args.get(1) instanceof LispCons inner && inner.car() instanceof LispSymbol innerOp
				&& LispNames.DIV.equals(innerOp.name()) && inner.isProperList() && inner.toList().size() == 3) {
			BigInteger a = integerLiteral(inner.toList().get(1));
			BigInteger b = integerLiteral(inner.toList().get(2));
			return a != null && b != null && b.signum() != 0 ? new BigInteger[] { a, b } : null;
		}
		return null;
	}

	private static @Nullable BigInteger integerLiteral(LispVal v) {
		return v instanceof LispInteger i ? BigInteger.valueOf(i.value())
				: v instanceof LispBigInteger b ? b.value() : null;
	}

	/**
	 * Opens the i31 arm of {@code (op (/ a b))}: two i31 operands, the divisor not zero,
	 * divide inline -- one {@code i64.div_s}, the truncating quotient stepped one toward
	 * the mode's side when it left a remainder (the exact quotient is q + r/y, r/y
	 * strictly inside (-1, 1) with the sign of r^y) -- with no call. Leaves an open
	 * {@code if (result eqref)} whose {@code else} the caller fills and closes; a zero
	 * divisor, and any wider operand, take that else, where {@code _big_fdiv} signals or
	 * divides.
	 */
	private static void emitFixnumQuotient(WasmLispCompiler.Ctx ctx, int aSlot, int bSlot, int mode) {
		int savedI64Locals = ctx.nextI64Local;
		int x = ctx.allocI64Temp();
		int y = ctx.allocI64Temp();
		int q = ctx.allocI64Temp();
		int r = ctx.allocI64Temp();
		emitIsI31(ctx, aSlot);
		emitIsI31(ctx, bSlot);
		ctx.writer.write(Instruction.I32_AND);
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(am.ik.wasm.Type.I32);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(bSlot);
		WasmEmitHelper.castI31GetS(ctx);
		ctx.writer.write(Instruction.ELSE);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(0);
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.IF);
		ctx.writer.writeRefType(true, am.ik.wasm.Type.EQ.code());
		for (int[] operand : new int[][] { { aSlot, x }, { bSlot, y } }) {
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeUnsignedLeb128(operand[0]);
			WasmEmitHelper.castI31GetS(ctx);
			ctx.writer.write(Instruction.I64_EXTEND_S_I32);
			setI64(ctx, operand[1]);
		}
		getI64(ctx, x);
		getI64(ctx, y);
		ctx.writer.write(Instruction.I64_DIV_S);
		setI64(ctx, q);
		if (mode != 0) {
			// r = x - q*y
			getI64(ctx, x);
			getI64(ctx, q);
			getI64(ctx, y);
			ctx.writer.write(Instruction.I64_MUL);
			ctx.writer.write(Instruction.I64_SUB);
			setI64(ctx, r);
			getI64(ctx, q);
			if (mode == 3) {
				// round: q + (2|r| > |y|, or a tie and q odd ? the step's direction : 0);
				// within the i31 range 2|r| cannot overflow, and r = 0 never steps
				emitStepDirection(ctx, r, y);
				ctx.writer.write(Instruction.I64_CONST);
				ctx.writer.writeSignedLeb128(0);
				emitAbs(ctx, r);
				ctx.writer.write(Instruction.I64_CONST);
				ctx.writer.writeSignedLeb128(1);
				ctx.writer.write(Instruction.I64_SHL);
				emitAbs(ctx, y);
				ctx.writer.write(Instruction.I64_SUB);
				setI64(ctx, r);
				getI64(ctx, r);
				ctx.writer.write(Instruction.I64_CONST);
				ctx.writer.writeSignedLeb128(0);
				ctx.writer.write(Instruction.I64_GT_S);
				getI64(ctx, r);
				ctx.writer.write(Instruction.I64_EQZ);
				getI64(ctx, q);
				ctx.writer.write(Instruction.I32_WRAP_I64);
				ctx.writer.write(Instruction.I32_AND);
				ctx.writer.write(Instruction.I32_OR);
				ctx.writer.write(Instruction.I64_EXTEND_U_I32);
				ctx.writer.write(Instruction.I64_SUB);
				ctx.writer.write(Instruction.I64_AND);
				ctx.writer.write(Instruction.I64_ADD);
			}
			else {
				// floor steps down when a remainder's sign differs from the divisor's,
				// ceiling up when it agrees
				getI64(ctx, r);
				ctx.writer.write(Instruction.I64_CONST);
				ctx.writer.writeSignedLeb128(0);
				ctx.writer.write(Instruction.I64_NE);
				getI64(ctx, r);
				getI64(ctx, y);
				ctx.writer.write(Instruction.I64_XOR);
				ctx.writer.write(Instruction.I64_CONST);
				ctx.writer.writeSignedLeb128(0);
				ctx.writer.write(mode == 1 ? Instruction.I64_LT_S : Instruction.I64_GE_S);
				ctx.writer.write(Instruction.I32_AND);
				ctx.writer.write(Instruction.I64_EXTEND_U_I32);
				ctx.writer.write(mode == 1 ? Instruction.I64_SUB : Instruction.I64_ADD);
			}
			setI64(ctx, q);
		}
		getI64(ctx, q);
		WasmOperandTypes.emitCall(ctx, WasmLispCompiler.FUNC_INT_NEW);
		ctx.writer.write(Instruction.ELSE);
		ctx.nextI64Local = savedI64Locals;
	}

	// Pushes ((r ^ y) >> 63) | 1: the direction a step from the truncating quotient
	// takes.
	private static void emitStepDirection(WasmLispCompiler.Ctx ctx, int r, int y) {
		getI64(ctx, r);
		getI64(ctx, y);
		ctx.writer.write(Instruction.I64_XOR);
		ctx.writer.write(Instruction.I64_CONST);
		ctx.writer.writeSignedLeb128(63);
		ctx.writer.write(Instruction.I64_SHR_S);
		ctx.writer.write(Instruction.I64_CONST);
		ctx.writer.writeSignedLeb128(1);
		ctx.writer.write(Instruction.I64_OR);
	}

	// Pushes |slot|: (v ^ (v >> 63)) - (v >> 63).
	private static void emitAbs(WasmLispCompiler.Ctx ctx, int slot) {
		getI64(ctx, slot);
		getI64(ctx, slot);
		ctx.writer.write(Instruction.I64_CONST);
		ctx.writer.writeSignedLeb128(63);
		ctx.writer.write(Instruction.I64_SHR_S);
		ctx.writer.write(Instruction.I64_XOR);
		getI64(ctx, slot);
		ctx.writer.write(Instruction.I64_CONST);
		ctx.writer.writeSignedLeb128(63);
		ctx.writer.write(Instruction.I64_SHR_S);
		ctx.writer.write(Instruction.I64_SUB);
	}

	private static void emitIsI31(WasmLispCompiler.Ctx ctx, int slot) {
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(am.ik.wasm.Type.I31.code());
	}

	private static void setI64(WasmLispCompiler.Ctx ctx, int slot) {
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writeI64LocalIndex(slot);
	}

	private static void getI64(WasmLispCompiler.Ctx ctx, int slot) {
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writeI64LocalIndex(slot);
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
		WasmOperandTypes.emitCall(ctx, ratioFunc);
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
		// exactly instead of clamping at i64.max. A NaN fails the magnitude test
		// (f64.lt is false for it) and, like an infinity, is declined by _f64_fdiv:
		// neither has an integer to answer, so that null signals (the interpreter's
		// text in EH mode, a bare trap outside it).
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
		WasmOperandTypes.emitCall(ctx, WasmLispCompiler.FUNC_F64_FDIV);
		ctx.writer.write(Instruction.TEE_LOCAL);
		ctx.writer.writeUnsignedLeb128(wideSlot);
		ctx.writer.write(Instruction.REF_IS_NULL);
		ctx.writer.write(Instruction.IF);
		ctx.writer.writeRefType(true, am.ik.wasm.Type.EQ.code());
		WasmErrorCompiler.compile(new LispCons(new LispSymbol(LispNames.ERROR_INTERNAL),
				new LispCons(new LispString(ClosRegistry.NON_FINITE_ROUNDING_MESSAGE), LispNil.INSTANCE)), ctx);
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
		WasmOperandTypes.emitCall(ctx, WasmLispCompiler.FUNC_INT_NEW);
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
