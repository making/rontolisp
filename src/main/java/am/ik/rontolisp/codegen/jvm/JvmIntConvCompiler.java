package am.ik.rontolisp.codegen.jvm;

import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.MethodRefEntry;
import java.util.List;

import am.ik.jvm.MethodCode;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;

import org.jspecify.annotations.Nullable;

/**
 * Compiles integer conversion functions ({@code truncate}, {@code floor},
 * {@code ceiling}, {@code round}). All convert a number to an integer: truncate toward
 * zero, floor toward negative infinity, ceiling toward positive infinity, round to
 * nearest even.
 */
final class JvmIntConvCompiler {

	/** 2^63 as a double: the first magnitude a {@code long} cannot hold. */
	private static final double LONG_LIMIT = 9.223372036854776E18;

	private JvmIntConvCompiler() {
	}

	static void compileTruncate(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		compile(cons, ctx, className, null, JvmNumericRuntimeBuilder.RAT_TRUNC, 0);
	}

	static void compileFloor(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		compile(cons, ctx, className, ctx.mathFloor, JvmNumericRuntimeBuilder.RAT_FLOOR, 1);
	}

	static void compileCeiling(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		compile(cons, ctx, className, ctx.mathCeil, JvmNumericRuntimeBuilder.RAT_CEIL, 2);
	}

	static void compileRound(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		compile(cons, ctx, className, ctx.mathRint, JvmNumericRuntimeBuilder.RAT_ROUND, 3);
	}

	private static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className,
			@Nullable MethodRefEntry mathMethod, String ratioOpKey, int mode) {
		List<LispVal> args = cons.toList();
		ClassEntry bigClass = ctx.cp.classEntry("java/math/BigInteger");
		int temp = ctx.allocTemp();
		// (op (/ a b)) -- which is what both the single-value and the multiple-value
		// lowerings of the two-argument (op a b) leave behind: a float operand divides
		// EXACTLY through _fdiv rather than through the rounded double (/ a b), so the
		// quotient is the mathematical integer CLHS asks for at any magnitude (a bignum
		// past the long range, like every other numeric operator) and the remainder
		// beside it stays rem/mod. _fdiv declines with a null for every operand pair it
		// does not improve on, which falls through to the ordinary division below.
		MethodCode.Label fusedEnd = ctx.body.newLabel();
		List<LispVal> divArgs = divisionOperands(args);
		if (divArgs != null) {
			int aSlot = ctx.allocTemp();
			int bSlot = ctx.allocTemp();
			JvmExprCompiler.compileExpr(divArgs.get(0), ctx, className);
			ctx.body.astore(aSlot);
			JvmExprCompiler.compileExpr(divArgs.get(1), ctx, className);
			ctx.body.astore(bSlot).aload(aSlot).aload(bSlot);
			JvmEmitHelper.emitIntConst(ctx, mode);
			ctx.body.invokestatic(ctx.numOp(JvmNumericRuntimeBuilder.FDIV)).astore(temp);
			ctx.body.aload(temp);
			MethodCode.Label ifDeclined = ctx.body.newLabel();
			ctx.body.ifnull(ifDeclined);
			ctx.body.aload(temp).goto_(fusedEnd);
			ctx.body.labelBinding(ifDeclined);
			ctx.body.aload(aSlot).aload(bSlot);
			ctx.body.invokestatic(ctx.numOp(JvmNumericRuntimeBuilder.DIV));
		}
		else {
			JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		}
		// An integer argument (Long or BigInteger) is already an integer: return it as-is
		// to avoid truncating a BigInteger through a double.
		ctx.body.astore(temp).aload(temp).instanceOf(ctx.longClass).aload(temp);
		ctx.body.instanceOf(bigClass).ior();
		MethodCode.Label ifIntPos = ctx.body.newLabel();
		ctx.body.ifne(ifIntPos);
		// Ratio path: exact integer conversion via the rational runtime helper.
		ctx.body.aload(temp).instanceOf(JvmEmitHelper.ratioArrayClass(ctx));
		MethodCode.Label ifNotRatioPos = ctx.body.newLabel();
		ctx.body.ifeq(ifNotRatioPos);
		ctx.body.aload(temp).invokestatic(ctx.numOp(ratioOpKey));
		MethodCode.Label gotoEnd2Pos = ctx.body.newLabel();
		ctx.body.goto_(gotoEnd2Pos);
		// Float path: convert through a double, which is exact inside the long range --
		// a finite double past 2^52 IS a mathematical integer, so out there the answer is
		// a bignum and _fdiv (over a divisor of 1) is what widens it exactly instead of
		// clamping at Long.MAX_VALUE. The magnitude guard keeps the ordinary rounding a
		// pair of instructions; a NaN fails it (DCMPG answers 1 for an unordered pair --
		// DCMPL's -1 would pass it into D2L's 0) and reaches _fdiv with the infinities,
		// where all three signal -- so _fdiv never answers null here.
		ctx.body.labelBinding(ifNotRatioPos);
		ctx.body.aload(temp);
		JvmEmitHelper.unboxDouble(ctx);
		if (mathMethod != null) {
			ctx.body.invokestatic(mathMethod);
		}
		ctx.body.dup2();
		ctx.body.invokestatic(ctx.cp.methodRef(ctx.cp.classEntry("java/lang/Math"), "abs", "(D)D"));
		JvmEmitHelper.emitRawDouble(LONG_LIMIT, ctx);
		ctx.body.dcmpg();
		MethodCode.Label ifInLongRange = ctx.body.newLabel();
		ctx.body.iflt(ifInLongRange);
		ctx.body.pop2().aload(temp).lconst_1().invokestatic(ctx.longValueOf);
		JvmEmitHelper.emitIntConst(ctx, mode);
		ctx.body.invokestatic(ctx.numOp(JvmNumericRuntimeBuilder.FDIV));
		MethodCode.Label gotoEndWidened = ctx.body.newLabel();
		ctx.body.goto_(gotoEndWidened);
		ctx.body.labelBinding(ifInLongRange);
		ctx.body.d2l();
		JvmEmitHelper.boxLong(ctx);
		MethodCode.Label gotoEndPos = ctx.body.newLabel();
		ctx.body.goto_(gotoEndPos);
		// Integer path: leave the original value on the stack.
		ctx.body.labelBinding(ifIntPos);
		ctx.body.aload(temp);
		ctx.body.labelBinding(gotoEndPos);
		ctx.body.labelBinding(gotoEndWidened);
		ctx.body.labelBinding(gotoEnd2Pos);
		ctx.body.labelBinding(fusedEnd);
	}

	/**
	 * The two operands of the {@code (/ a b)} a floor-family call rounds, or {@code null}
	 * when the argument is anything else.
	 */
	private static @Nullable List<LispVal> divisionOperands(List<LispVal> args) {
		if (args.size() != 2 || !(args.get(1) instanceof LispCons inner) || !inner.isProperList()
				|| !(inner.car() instanceof LispSymbol op) || !LispNames.DIV.equals(op.name())) {
			return null;
		}
		List<LispVal> parts = inner.toList();
		return parts.size() == 3 ? List.of(parts.get(1), parts.get(2)) : null;
	}

}
