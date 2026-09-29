package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.jvm.MethodCode;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;

/**
 * Compiles the {@code functionp} predicate. A function reference is an {@code Object[]}
 * whose slot 0 is a boxed {@code Integer} funcId (integers are {@code Long} at runtime,
 * so the {@code Integer} marker distinguishes it from a cons cell -- the mirror image of
 * {@link JvmConspCompiler}).
 */
final class JvmFunctionpCompiler {

	private JvmFunctionpCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		int tempSlot = ctx.allocTemp();
		ctx.body.astore(tempSlot).aload(tempSlot).instanceOf(ctx.objectArrayClass);
		MethodCode.Label ifNotArrayPos = ctx.body.newLabel();
		ctx.body.ifeq(ifNotArrayPos);
		// A ratio (BigInteger[]) is also an Object[] but is not a function reference.
		ctx.body.aload(tempSlot).instanceOf(JvmEmitHelper.ratioArrayClass(ctx));
		MethodCode.Label ifRatioPos = ctx.body.newLabel();
		ctx.body.ifne(ifRatioPos);
		ctx.body.aload(tempSlot).checkcast(ctx.objectArrayClass).iconst_0().aaload();
		ctx.body.instanceOf(ctx.integerClass);
		MethodCode.Label ifNotFuncRefPos = ctx.body.newLabel();
		ctx.body.ifeq(ifNotFuncRefPos);
		JvmEmitHelper.compileTrue(ctx);
		MethodCode.Label gotoEndPos = ctx.body.newLabel();
		ctx.body.goto_(gotoEndPos);
		ctx.body.labelBinding(ifNotArrayPos);
		ctx.body.labelBinding(ifRatioPos);
		ctx.body.labelBinding(ifNotFuncRefPos);
		ctx.body.aconst_null();
		ctx.body.labelBinding(gotoEndPos);
	}

}
