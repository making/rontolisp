package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.jvm.MethodCode;

/**
 * Compiles the {@code lcm} built-in: the least common multiple of two integers, computed
 * as {@code abs((a / gcd(a, b)) * b)}. Returns 0 when either argument is 0.
 */
final class JvmLcmCompiler {

	private static final String BIG = "Ljava/math/BigInteger;";

	private JvmLcmCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		int savedNextLocal = ctx.nextLocal;
		int slotA = ctx.allocLocal("%lcm$a" + savedNextLocal);
		int slotB = ctx.allocLocal("%lcm$b" + savedNextLocal);
		int slotG = ctx.allocLocal("%lcm$g" + savedNextLocal);

		// A = _big(a); B = _big(b); G = A.gcd(B)
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		JvmEmitHelper.toBigInteger(ctx);
		ctx.body.astore(slotA);
		JvmExprCompiler.compileExpr(args.get(2), ctx, className);
		JvmEmitHelper.toBigInteger(ctx);
		ctx.body.astore(slotB).aload(slotA).aload(slotB);
		ctx.body.invokevirtual(JvmEmitHelper.bigIntegerMethod(ctx, "gcd", "(" + BIG + ")" + BIG));
		ctx.body.astore(slotG);

		// if (G.signum() != 0) goto notZero
		ctx.body.aload(slotG);
		ctx.body.invokevirtual(JvmEmitHelper.bigIntegerMethod(ctx, "signum", "()I"));
		MethodCode.Label notZero = ctx.body.newLabel();
		MethodCode.Label end = ctx.body.newLabel();
		ctx.body.ifne(notZero);

		// zero case: result is 0
		ctx.body.lconst_0();
		JvmEmitHelper.boxLong(ctx);
		ctx.body.goto_(end);

		// notZero: abs((A / G) * B)
		ctx.body.labelBinding(notZero);
		ctx.body.aload(slotA).aload(slotG);
		ctx.body.invokevirtual(JvmEmitHelper.bigIntegerMethod(ctx, "divide", "(" + BIG + ")" + BIG));
		ctx.body.aload(slotB);
		ctx.body.invokevirtual(JvmEmitHelper.bigIntegerMethod(ctx, "multiply", "(" + BIG + ")" + BIG));
		ctx.body.invokevirtual(JvmEmitHelper.bigIntegerMethod(ctx, "abs", "()" + BIG));
		JvmEmitHelper.normalizeBigInteger(ctx);

		ctx.body.labelBinding(end);
		ctx.nextLocal = savedNextLocal;
	}

}
