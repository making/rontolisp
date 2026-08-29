package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.jvm.Opcode;

/**
 * Compiles the {@code signum} built-in: the sign of a number as -1/0/1. A floating-point
 * literal argument yields a double (-1.0/0.0/1.0) via {@code Math.signum}; otherwise the
 * integer sign is returned via {@code BigInteger.signum()}.
 */
final class JvmSignumCompiler {

	private JvmSignumCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		if (JvmLispCompiler.hasDoubleLiteral(args, ctx)) {
			JvmEmitHelper.unboxDouble(ctx);
			ctx.emit(Opcode.INVOKESTATIC);
			ctx.emitU2(ctx.mathOp(JvmMathFnCompiler.SIGNUM_D).index());
			JvmEmitHelper.boxDouble(ctx);
		}
		else {
			// _signum dispatches on the runtime type: Math.signum for a Double (so a
			// float
			// reaching signum through a variable works), otherwise the integer sign as a
			// Long (the numerator's sign for a ratio).
			ctx.emit(Opcode.INVOKESTATIC);
			ctx.emitU2(ctx.numOp(JvmNumericRuntimeBuilder.SIGNUM).index());
		}
	}

}
