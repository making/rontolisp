package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;

/**
 * Compiles the {@code rationalp} predicate. A rational is an integer ({@code Long} or
 * {@code BigInteger}) or a ratio ({@code BigInteger[]}).
 */
final class JvmRationalpCompiler {

	private JvmRationalpCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		int temp = ctx.allocTemp();
		ctx.body.astore(temp).aload(temp).instanceOf(ctx.longClass.entry()).aload(temp);
		ctx.body.instanceOf(JvmEmitHelper.bigIntegerClass(ctx).entry()).ior().aload(temp);
		ctx.body.instanceOf(JvmEmitHelper.ratioArrayClass(ctx).entry()).ior();
		JvmEmitHelper.emitBoolFromInt(ctx);
	}

}
