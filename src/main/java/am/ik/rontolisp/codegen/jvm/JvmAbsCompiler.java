package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;

/**
 * Compiles the {@code abs} built-in function.
 */
final class JvmAbsCompiler {

	private JvmAbsCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		if (!JvmLispCompiler.hasComplexOperand(args) && JvmLispCompiler.hasDoubleLiteral(args, ctx)) {
			JvmArithCompiler.compileUnboxedOperand(args.get(1), ctx, className);
			ctx.body.invokestatic(ctx.mathAbsDouble);
			JvmEmitHelper.boxDouble(ctx);
		}
		else {
			JvmExprCompiler.compileExpr(args.get(1), ctx, className);
			ctx.body.invokestatic(ctx.numOp(JvmNumericRuntimeBuilder.ABS));
		}
	}

}
