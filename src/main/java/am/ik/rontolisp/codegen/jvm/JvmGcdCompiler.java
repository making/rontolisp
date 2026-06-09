package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.jvm.Opcode;

/**
 * Compiles the {@code gcd} built-in: the greatest common divisor of two integers via
 * {@code BigInteger.gcd()} (always non-negative).
 */
final class JvmGcdCompiler {

	private JvmGcdCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		JvmEmitHelper.toBigInteger(ctx);
		JvmExprCompiler.compileExpr(args.get(2), ctx, className);
		JvmEmitHelper.toBigInteger(ctx);
		ctx.emit(Opcode.INVOKEVIRTUAL);
		ctx.emitU2(
				JvmEmitHelper.bigIntegerMethod(ctx, "gcd", "(Ljava/math/BigInteger;)Ljava/math/BigInteger;").index());
		JvmEmitHelper.normalizeBigInteger(ctx);
	}

}
