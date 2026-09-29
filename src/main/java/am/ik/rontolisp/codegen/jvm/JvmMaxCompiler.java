package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;

/**
 * Compiles the {@code max} built-in function.
 */
final class JvmMaxCompiler {

	private JvmMaxCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		// See JvmMinCompiler for why this is isDefinitelyDouble, not hasDoubleLiteral.
		if (JvmLispCompiler.isDefinitelyDouble(args.get(1), ctx)
				&& JvmLispCompiler.isDefinitelyDouble(args.get(2), ctx)) {
			JvmArithCompiler.compileUnboxedOperand(args.get(1), ctx, className);
			JvmArithCompiler.compileUnboxedOperand(args.get(2), ctx, className);
			ctx.body.invokestatic(ctx.numOp(JvmNumericRuntimeBuilder.FMAX).entry());
			JvmEmitHelper.boxDouble(ctx);
		}
		else {
			JvmExprCompiler.compileExpr(args.get(1), ctx, className);
			JvmExprCompiler.compileExpr(args.get(2), ctx, className);
			ctx.body.invokestatic(ctx.numOp(JvmNumericRuntimeBuilder.MAX).entry());
		}
	}

}
