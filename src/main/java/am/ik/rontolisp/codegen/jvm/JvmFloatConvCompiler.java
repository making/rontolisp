package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;

/**
 * Compiles the {@code float} type conversion function. Converts any number to a
 * floating-point value.
 */
final class JvmFloatConvCompiler {

	private JvmFloatConvCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		// _dbl handles Long, BigInteger, Double and ratios (BigInteger[]).
		ctx.emit(am.ik.jvm.Opcode.INVOKESTATIC);
		ctx.emitU2(ctx.numOp(JvmNumericRuntimeBuilder.DBL).index());
	}

}
