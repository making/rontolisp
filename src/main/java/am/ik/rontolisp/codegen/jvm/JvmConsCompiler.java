package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;

/**
 * Compiles the {@code cons} built-in function.
 */
final class JvmConsCompiler {

	private JvmConsCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		ctx.body.iconst_2().anewarray(ctx.objectClass.entry()).dup().iconst_0();
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		ctx.body.aastore().dup().iconst_1();
		JvmExprCompiler.compileExpr(args.get(2), ctx, className);
		ctx.body.aastore();
	}

}
