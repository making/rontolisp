package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.jvm.Opcode;

/**
 * Compiles the {@code numerator} and {@code denominator} accessors. For a ratio
 * ({@code BigInteger[]}) the requested component is returned (normalized back to a
 * {@code Long} when it fits); an integer is its own numerator and has denominator one.
 */
final class JvmRatioAccessorCompiler {

	private JvmRatioAccessorCompiler() {
	}

	static void compileNumerator(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		compile(cons, ctx, className, 0);
	}

	static void compileDenominator(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		compile(cons, ctx, className, 1);
	}

	private static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className, int index) {
		List<LispVal> args = cons.toList();
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		int temp = ctx.allocTemp();
		ctx.emit(Opcode.ASTORE);
		ctx.emit(temp);
		ctx.emit(Opcode.ALOAD);
		ctx.emit(temp);
		ctx.emit(Opcode.INSTANCEOF);
		ctx.emitU2(JvmEmitHelper.ratioArrayClass(ctx).index());
		int ifNotRatioPos = ctx.code.size();
		ctx.emit(Opcode.IFEQ);
		ctx.emitU2(0);
		ctx.emit(Opcode.ALOAD);
		ctx.emit(temp);
		ctx.emit(Opcode.CHECKCAST);
		ctx.emitU2(JvmEmitHelper.ratioArrayClass(ctx).index());
		ctx.emit(index == 0 ? Opcode.ICONST_0 : Opcode.ICONST_1);
		ctx.emit(Opcode.AALOAD);
		JvmEmitHelper.normalizeBigInteger(ctx);
		int gotoEndPos = ctx.code.size();
		ctx.emit(Opcode.GOTO);
		ctx.emitU2(0);
		JvmEmitHelper.patchBranch(ctx, ifNotRatioPos, ctx.code.size());
		if (index == 0) {
			ctx.emit(Opcode.ALOAD);
			ctx.emit(temp);
		}
		else {
			JvmEmitHelper.compileLong(1, ctx);
		}
		JvmEmitHelper.patchBranch(ctx, gotoEndPos, ctx.code.size());
	}

}
