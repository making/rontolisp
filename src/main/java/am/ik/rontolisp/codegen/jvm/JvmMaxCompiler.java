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
			if (JvmFloatOperands.guards(args.subList(1, 3), ctx)) {
				// An operand may hold a complex the form does not spell, which the boxed
				// helper reports as a REAL operand-type error.
				JvmFloatOperands.compileCall(args.subList(1, 3), ctx.numOp(JvmNumericRuntimeBuilder.FMAX),
						ctx.numOp(JvmNumericRuntimeBuilder.MAX), ctx, className);
				return;
			}
			JvmArithCompiler.compileUnboxedOperands(args.subList(1, 3), ctx, className, i -> {
			});
			ctx.body.invokestatic(ctx.numOp(JvmNumericRuntimeBuilder.FMAX));
			JvmEmitHelper.boxDouble(ctx);
		}
		else {
			JvmExprCompiler.compileExpr(args.get(1), ctx, className);
			JvmExprCompiler.compileExpr(args.get(2), ctx, className);
			ctx.body.invokestatic(ctx.numOp(JvmNumericRuntimeBuilder.MAX));
		}
	}

}
