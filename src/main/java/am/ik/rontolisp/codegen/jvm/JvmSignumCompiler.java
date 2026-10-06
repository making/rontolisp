package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;

/**
 * Compiles the {@code signum} built-in: the sign of a number as -1/0/1. A floating-point
 * literal argument yields a double (-1.0/0.0/1.0) via {@code Math.signum}; otherwise the
 * integer sign is returned via {@code BigInteger.signum()}. A syntactic complex (a
 * {@code #C} literal, a {@code complex}/{@code conjugate} form) steers off the unboxed
 * path onto the object path first, so it never reaches an unboxing (the {@code abs}
 * steering pattern, `.kb/jvm-complex.md`).
 */
final class JvmSignumCompiler {

	private JvmSignumCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		if (!JvmLispCompiler.hasComplexOperand(args) && JvmLispCompiler.hasDoubleLiteral(args, ctx)
				&& JvmFloatOperands.guards(args.subList(1, 2), ctx)) {
			// The argument may hold a complex the form does not spell: _signum answers
			// its unit vector.
			JvmFloatOperands.compileCall(args.subList(1, 2), ctx.mathOp(JvmMathFnCompiler.SIGNUM_D),
					ctx.numOp(JvmNumericRuntimeBuilder.SIGNUM), ctx, className);
			return;
		}
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		if (!JvmLispCompiler.hasComplexOperand(args) && JvmLispCompiler.hasDoubleLiteral(args, ctx)) {
			JvmEmitHelper.unboxDouble(ctx);
			ctx.body.invokestatic(ctx.mathOp(JvmMathFnCompiler.SIGNUM_D));
			JvmEmitHelper.boxDouble(ctx);
		}
		else {
			// _signum dispatches on the runtime type: Math.signum for a Double (so a
			// float
			// reaching signum through a variable works), otherwise the integer sign as a
			// Long (the numerator's sign for a ratio).
			ctx.body.invokestatic(ctx.numOp(JvmNumericRuntimeBuilder.SIGNUM));
		}
	}

}
