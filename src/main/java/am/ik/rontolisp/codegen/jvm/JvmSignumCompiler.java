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
		if (JvmLispCompiler.hasDoubleLiteral(args)) {
			JvmEmitHelper.unboxDouble(ctx);
			ctx.emit(Opcode.INVOKESTATIC);
			ctx.emitU2(ctx.mathOp(JvmMathFnCompiler.SIGNUM_D).index());
			JvmEmitHelper.boxDouble(ctx);
		}
		else {
			// _ratnum coerces Long/BigInteger via _big and a ratio to its numerator;
			// the sign of a ratio is the sign of its numerator.
			ctx.emit(Opcode.INVOKESTATIC);
			ctx.emitU2(ctx.numOp(JvmNumericRuntimeBuilder.RAT_NUM).index());
			ctx.emit(Opcode.INVOKEVIRTUAL);
			ctx.emitU2(JvmEmitHelper.bigIntegerMethod(ctx, "signum", "()I").index());
			ctx.emit(Opcode.I2L);
			JvmEmitHelper.boxLong(ctx);
		}
	}

}
