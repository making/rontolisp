package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;

import am.ik.jvm.ConstantPool.ClassConstant;
import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.Opcode;
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
			@Nullable MethodrefConstant mathMethod, String ratioOpKey, int mode) {
		List<LispVal> args = cons.toList();
		ClassConstant bigClass = ctx.cp.addClass(ctx.cp.addUtf8("java/math/BigInteger"));
		int temp = ctx.allocTemp();
		// (op (/ a b)) -- which is what both the single-value and the multiple-value
		// lowerings of the two-argument (op a b) leave behind: a float operand divides
		// EXACTLY through _fdiv rather than through the rounded double (/ a b), so the
		// quotient is the mathematical integer CLHS asks for at any magnitude (a bignum
		// past the long range, like every other numeric operator) and the remainder
		// beside it stays rem/mod. _fdiv declines with a null for every operand pair it
		// does not improve on, which falls through to the ordinary division below.
		int fusedEnd = -1;
		List<LispVal> divArgs = divisionOperands(args);
		if (divArgs != null) {
			int aSlot = ctx.allocTemp();
			int bSlot = ctx.allocTemp();
			JvmExprCompiler.compileExpr(divArgs.get(0), ctx, className);
			ctx.emit(Opcode.ASTORE);
			ctx.emit(aSlot);
			JvmExprCompiler.compileExpr(divArgs.get(1), ctx, className);
			ctx.emit(Opcode.ASTORE);
			ctx.emit(bSlot);
			ctx.emit(Opcode.ALOAD);
			ctx.emit(aSlot);
			ctx.emit(Opcode.ALOAD);
			ctx.emit(bSlot);
			JvmEmitHelper.emitIntConst(ctx, mode);
			ctx.emit(Opcode.INVOKESTATIC);
			ctx.emitU2(ctx.numOp(JvmNumericRuntimeBuilder.FDIV).index());
			ctx.emit(Opcode.ASTORE);
			ctx.emit(temp);
			ctx.emit(Opcode.ALOAD);
			ctx.emit(temp);
			int ifDeclined = ctx.code.size();
			ctx.emit(Opcode.IFNULL);
			ctx.emitU2(0);
			ctx.emit(Opcode.ALOAD);
			ctx.emit(temp);
			fusedEnd = ctx.code.size();
			ctx.emit(Opcode.GOTO);
			ctx.emitU2(0);
			JvmEmitHelper.patchBranch(ctx, ifDeclined, ctx.code.size());
			ctx.emit(Opcode.ALOAD);
			ctx.emit(aSlot);
			ctx.emit(Opcode.ALOAD);
			ctx.emit(bSlot);
			ctx.emit(Opcode.INVOKESTATIC);
			ctx.emitU2(ctx.numOp(JvmNumericRuntimeBuilder.DIV).index());
		}
		else {
			JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		}
		// An integer argument (Long or BigInteger) is already an integer: return it as-is
		// to avoid truncating a BigInteger through a double.
		ctx.emit(Opcode.ASTORE);
		ctx.emit(temp);
		ctx.emit(Opcode.ALOAD);
		ctx.emit(temp);
		ctx.emit(Opcode.INSTANCEOF);
		ctx.emitU2(ctx.longClass.index());
		ctx.emit(Opcode.ALOAD);
		ctx.emit(temp);
		ctx.emit(Opcode.INSTANCEOF);
		ctx.emitU2(bigClass.index());
		ctx.emit(Opcode.IOR);
		int ifIntPos = ctx.code.size();
		ctx.emit(Opcode.IFNE);
		ctx.emitU2(0);
		// Ratio path: exact integer conversion via the rational runtime helper.
		ctx.emit(Opcode.ALOAD);
		ctx.emit(temp);
		ctx.emit(Opcode.INSTANCEOF);
		ctx.emitU2(JvmEmitHelper.ratioArrayClass(ctx).index());
		int ifNotRatioPos = ctx.code.size();
		ctx.emit(Opcode.IFEQ);
		ctx.emitU2(0);
		ctx.emit(Opcode.ALOAD);
		ctx.emit(temp);
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(ctx.numOp(ratioOpKey).index());
		int gotoEnd2Pos = ctx.code.size();
		ctx.emit(Opcode.GOTO);
		ctx.emitU2(0);
		// Float path: convert through a double, which is exact inside the long range --
		// a finite double past 2^52 IS a mathematical integer, so out there the answer is
		// a bignum and _fdiv (over a divisor of 1) is what widens it exactly instead of
		// clamping at Long.MAX_VALUE. The magnitude guard keeps the ordinary rounding a
		// pair of instructions; a NaN fails it (DCMPL) and an infinity is declined by
		// _fdiv, so both keep the narrowing they always had.
		JvmEmitHelper.patchBranch(ctx, ifNotRatioPos, ctx.code.size());
		ctx.emit(Opcode.ALOAD);
		ctx.emit(temp);
		JvmEmitHelper.unboxDouble(ctx);
		if (mathMethod != null) {
			ctx.emit(Opcode.INVOKESTATIC);
			ctx.emitU2(mathMethod.index());
		}
		ctx.emit(Opcode.DUP2);
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(ctx.cp
			.addMethodref(ctx.cp.addClass(ctx.cp.addUtf8("java/lang/Math")),
					ctx.cp.addNameAndType(ctx.cp.addUtf8("abs"), ctx.cp.addUtf8("(D)D")))
			.index());
		JvmEmitHelper.emitRawDouble(LONG_LIMIT, ctx);
		ctx.emit(Opcode.DCMPL);
		int ifInLongRange = ctx.code.size();
		ctx.emit(Opcode.IFLT);
		ctx.emitU2(0);
		ctx.emit(Opcode.POP2);
		ctx.emit(Opcode.ALOAD);
		ctx.emit(temp);
		ctx.emit(Opcode.LCONST_1);
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(ctx.longValueOf.index());
		JvmEmitHelper.emitIntConst(ctx, mode);
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(ctx.numOp(JvmNumericRuntimeBuilder.FDIV).index());
		ctx.emit(Opcode.DUP);
		int ifExactWidening = ctx.code.size();
		ctx.emit(Opcode.IFNONNULL);
		ctx.emitU2(0);
		ctx.emit(Opcode.POP);
		ctx.emit(Opcode.ALOAD);
		ctx.emit(temp);
		JvmEmitHelper.unboxDouble(ctx);
		if (mathMethod != null) {
			ctx.emit(Opcode.INVOKESTATIC);
			ctx.emitU2(mathMethod.index());
		}
		JvmEmitHelper.patchBranch(ctx, ifInLongRange, ctx.code.size());
		ctx.emit(Opcode.D2L);
		JvmEmitHelper.boxLong(ctx);
		JvmEmitHelper.patchBranch(ctx, ifExactWidening, ctx.code.size());
		int gotoEndPos = ctx.code.size();
		ctx.emit(Opcode.GOTO);
		ctx.emitU2(0);
		// Integer path: leave the original value on the stack.
		JvmEmitHelper.patchBranch(ctx, ifIntPos, ctx.code.size());
		ctx.emit(Opcode.ALOAD);
		ctx.emit(temp);
		JvmEmitHelper.patchBranch(ctx, gotoEndPos, ctx.code.size());
		JvmEmitHelper.patchBranch(ctx, gotoEnd2Pos, ctx.code.size());
		if (fusedEnd >= 0) {
			JvmEmitHelper.patchBranch(ctx, fusedEnd, ctx.code.size());
		}
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
