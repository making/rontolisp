package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;

/**
 * Compiles the {@code floatp} predicate.
 */
final class JvmFloatpCompiler {

	private JvmFloatpCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		ctx.body.instanceOf(ctx.doubleClass);
		JvmEmitHelper.emitBoolFromInt(ctx);
	}

}
