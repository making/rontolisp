package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.jvm.MethodCode;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;

/**
 * Compiles the {@code consp} predicate, through the same one-per-class helper shape as
 * {@link JvmAtomCompiler} ({@code _pConsp}).
 */
final class JvmConspCompiler {

	private JvmConspCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		JvmEmitHelper.emitSharedCall(ctx, className, "_pConsp", 1, JvmConspCompiler::emitCheck);
	}

	/** Emits the predicate over the value in local slot 0. */
	private static void emitCheck(JvmLispCompiler.Ctx ctx) {
		int tempSlot = 0;
		ctx.body.aload(tempSlot).instanceOf(ctx.objectArrayClass);
		MethodCode.Label ifNotArrayPos = ctx.body.newLabel();
		ctx.body.ifeq(ifNotArrayPos);
		// A ratio (BigInteger[]) is also an Object[] but is not a cons cell.
		ctx.body.aload(tempSlot).instanceOf(JvmEmitHelper.ratioArrayClass(ctx));
		MethodCode.Label ifRatioPos = ctx.body.newLabel();
		ctx.body.ifne(ifRatioPos);
		ctx.body.aload(tempSlot).checkcast(ctx.objectArrayClass).iconst_0().aaload();
		ctx.body.instanceOf(ctx.integerClass);
		MethodCode.Label ifFuncRefPos = ctx.body.newLabel();
		ctx.body.ifne(ifFuncRefPos);
		MethodCode.Label notCons = ctx.body.newLabel();
		JvmEmitHelper.emitInstanceExclusion(ctx, tempSlot, notCons);
		JvmEmitHelper.emitAsyncValueExclusion(ctx, tempSlot, notCons);
		JvmEmitHelper.compileTrue(ctx);
		MethodCode.Label gotoEndPos = ctx.body.newLabel();
		ctx.body.goto_(gotoEndPos);
		ctx.body.labelBinding(ifNotArrayPos);
		ctx.body.labelBinding(ifRatioPos);
		ctx.body.labelBinding(ifFuncRefPos);
		ctx.body.labelBinding(notCons);
		ctx.body.aconst_null();
		ctx.body.labelBinding(gotoEndPos);
	}

}
