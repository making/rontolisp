package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;

/**
 * Compiles the {@code expt} built-in. When a floating-point literal is involved it
 * delegates to {@code Math.pow} (returning a double); otherwise it calls the {@code _pow}
 * runtime helper, which keeps an exact rational result for an integer exponent (a
 * negative one yields the reciprocal) and falls over to {@code Math.pow} when the
 * exponent turns out at run time to be a float or a ratio. A complex operand routes to
 * the gated {@code _cpow} instead (`.kb/jvm-complex.md`).
 */
final class JvmExptCompiler {

	private JvmExptCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		if (JvmLispCompiler.hasComplexOperand(args)) {
			JvmExprCompiler.compileExpr(args.get(1), ctx, className);
			JvmExprCompiler.compileExpr(args.get(2), ctx, className);
			ctx.body.invokestatic(JvmComplexCompiler.complexOp(ctx, className, JvmComplexRuntimeBuilder.POW));
			return;
		}
		if (am.ik.rontolisp.macro.LispMacroExpander.escapesToComplex(am.ik.rontolisp.LispNames.EXPT, args)) {
			// Both operands are real as far as the source shows, but a negative base
			// to a non-integer power leaves the real line -- _cpowr answers the plane
			// there and delegates everything else to the _pow below.
			JvmExprCompiler.compileExpr(args.get(1), ctx, className);
			JvmExprCompiler.compileExpr(args.get(2), ctx, className);
			ctx.body.invokestatic(JvmComplexCompiler.complexOp(ctx, className, JvmComplexRuntimeBuilder.POW_REAL));
			return;
		}
		if (JvmLispCompiler.hasDoubleLiteral(args, ctx)) {
			JvmArithCompiler.compileUnboxedOperand(args.get(1), ctx, className);
			JvmArithCompiler.compileUnboxedOperand(args.get(2), ctx, className);
			ctx.body.invokestatic(ctx.mathOp(JvmMathFnCompiler.POW));
			JvmEmitHelper.boxDouble(ctx);
		}
		else {
			// _pow keeps an exact rational result for an integer exponent: a ratio base
			// raises numerator and denominator, and a negative exponent yields the
			// reciprocal (e.g. (expt 2 -1) -> 1/2). It dispatches on the RUNTIME
			// exponent: a double or ratio that the literal scan above could not see
			// (a variable, a call, a float coercion) takes Math.pow there.
			JvmExprCompiler.compileExpr(args.get(1), ctx, className);
			JvmExprCompiler.compileExpr(args.get(2), ctx, className);
			ctx.body.invokestatic(ctx.numOp(JvmNumericRuntimeBuilder.POW));
		}
	}

}
