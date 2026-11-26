package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;

/**
 * Compiles the {@code min} built-in function.
 */
final class JvmMinCompiler {

	private JvmMinCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		// The unboxed fast path is gated on isDefinitelyDouble, NOT the broader
		// hasDoubleLiteral every sibling arithmetic compiler uses: hasDoubleLiteral
		// only asks whether a double literal occurs ANYWHERE in an operand's
		// subtree, which is sound for the force-coercing operators (a wrong guess
		// still lands on the right answer) but not for min/max, whose result is
		// exactly one of the two operands -- coercing the wrong one to double would
		// change its TYPE, not just its box, e.g. answering the double 1.0 for
		// (min 1 2.0) instead of the rational 1. isDefinitelyDouble requires EACH
		// operand to be independently PROVEN double (a literal, a declared/raw
		// double local, or a true-contagion +/-/*/mod/rem tree), so the winner is
		// double whichever one wins and reboxing it is exact.
		if (JvmLispCompiler.isDefinitelyDouble(args.get(1), ctx)
				&& JvmLispCompiler.isDefinitelyDouble(args.get(2), ctx)) {
			if (JvmFloatOperands.guards(args.subList(1, 3), ctx)) {
				// An operand may hold a complex the form does not spell, which the boxed
				// helper reports as a REAL operand-type error.
				JvmFloatOperands.compileCall(args.subList(1, 3), ctx.numOp(JvmNumericRuntimeBuilder.FMIN),
						ctx.numOp(JvmNumericRuntimeBuilder.MIN), ctx, className);
				return;
			}
			JvmArithCompiler.compileUnboxedOperands(args.subList(1, 3), ctx, className, i -> {
			});
			ctx.body.invokestatic(ctx.numOp(JvmNumericRuntimeBuilder.FMIN));
			JvmEmitHelper.boxDouble(ctx);
		}
		else {
			JvmExprCompiler.compileExpr(args.get(1), ctx, className);
			JvmExprCompiler.compileExpr(args.get(2), ctx, className);
			ctx.body.invokestatic(ctx.numOp(JvmNumericRuntimeBuilder.MIN));
		}
	}

}
