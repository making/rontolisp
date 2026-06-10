package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.rontolisp.LispCons;
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

	private JvmIntConvCompiler() {
	}

	static void compileTruncate(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		compile(cons, ctx, className, null, JvmNumericRuntimeBuilder.RAT_TRUNC);
	}

	static void compileFloor(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		compile(cons, ctx, className, ctx.mathFloor, JvmNumericRuntimeBuilder.RAT_FLOOR);
	}

	static void compileCeiling(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		compile(cons, ctx, className, ctx.mathCeil, JvmNumericRuntimeBuilder.RAT_CEIL);
	}

	static void compileRound(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		compile(cons, ctx, className, ctx.mathRint, JvmNumericRuntimeBuilder.RAT_ROUND);
	}

	private static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className,
			@Nullable MethodrefConstant mathMethod, String ratioOpKey) {
		List<LispVal> args = cons.toList();
		ClassConstant bigClass = ctx.cp.addClass(ctx.cp.addUtf8("java/math/BigInteger"));
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		// An integer argument (Long or BigInteger) is already an integer: return it as-is
		// to avoid truncating a BigInteger through a double.
		int temp = ctx.allocTemp();
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
		// Float path: convert through a double.
		JvmEmitHelper.patchBranch(ctx, ifNotRatioPos, ctx.code.size());
		ctx.emit(Opcode.ALOAD);
		ctx.emit(temp);
		JvmEmitHelper.unboxDouble(ctx);
		if (mathMethod != null) {
			ctx.emit(Opcode.INVOKESTATIC);
			ctx.emitU2(mathMethod.index());
		}
		ctx.emit(Opcode.D2L);
		JvmEmitHelper.boxLong(ctx);
		int gotoEndPos = ctx.code.size();
		ctx.emit(Opcode.GOTO);
		ctx.emitU2(0);
		// Integer path: leave the original value on the stack.
		JvmEmitHelper.patchBranch(ctx, ifIntPos, ctx.code.size());
		ctx.emit(Opcode.ALOAD);
		ctx.emit(temp);
		JvmEmitHelper.patchBranch(ctx, gotoEndPos, ctx.code.size());
		JvmEmitHelper.patchBranch(ctx, gotoEnd2Pos, ctx.code.size());
	}

}
