package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.jvm.Opcode;

/**
 * Compiles the {@code isqrt} built-in: the exact integer square root (floor of the real
 * square root) computed via {@code BigInteger.sqrt()}.
 */
final class JvmIsqrtCompiler {

	private JvmIsqrtCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		JvmEmitHelper.toBigInteger(ctx);
		ctx.emit(Opcode.INVOKEVIRTUAL);
		ctx.emitU2(JvmEmitHelper.bigIntegerMethod(ctx, "sqrt", "()Ljava/math/BigInteger;").index());
		JvmEmitHelper.normalizeBigInteger(ctx);
	}

}
