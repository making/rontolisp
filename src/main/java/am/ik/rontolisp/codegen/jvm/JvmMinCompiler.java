package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.jvm.Opcode;

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
			JvmArithCompiler.compileUnboxedOperand(args.get(1), ctx, className);
			JvmArithCompiler.compileUnboxedOperand(args.get(2), ctx, className);
			ctx.emit(Opcode.INVOKESTATIC);
			ctx.emitU2(ctx.numOp(JvmNumericRuntimeBuilder.FMIN).index());
			JvmEmitHelper.boxDouble(ctx);
		}
		else {
			JvmExprCompiler.compileExpr(args.get(1), ctx, className);
			JvmExprCompiler.compileExpr(args.get(2), ctx, className);
			ctx.emit(Opcode.INVOKESTATIC);
			ctx.emitU2(ctx.numOp(JvmNumericRuntimeBuilder.MIN).index());
		}
	}

}
