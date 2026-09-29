package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.jvm.MethodCode;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;

/**
 * Compiles the {@code random} built-in function. Returns a non-negative random number
 * below the limit, of the same type as the limit. A float-literal argument compiles
 * straight to {@code tlr.nextDouble() * limit} (a double); otherwise the {@code _random}
 * runtime helper dispatches on the limit's runtime type (so a float limit reaching
 * {@code random} through a variable yields a float, not a trap). Both draw from
 * {@code java.util.concurrent.ThreadLocalRandom}, one per-thread generator seeded from
 * the process's entropy -- not {@code Math.random}, whose single shared
 * {@code java.util.Random} advances its seed by a contended {@code compareAndSet}
 * ({@code .kb/random.md}).
 */
final class JvmRandomCompiler {

	private JvmRandomCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		if (args.size() != 2) {
			throw new UnsupportedOperationException("random expects 1 argument, got " + (args.size() - 1));
		}
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		if (JvmLispCompiler.hasDoubleLiteral(args, ctx)) {
			// Float limit: tlr.nextDouble() * limit, kept as a double -- but check the
			// limit's sign first (.todo/981): a non-positive float is _random's domain
			// violation too, and this path never reaches _random to catch it. unboxDouble
			// is a pure coercion (no draw), so calling it twice on the positive path
			// costs
			// nothing observable; the non-positive path bails to the wrapped _random call
			// instead of drawing, which throws before any draw happens either.
			ctx.body.dup();
			JvmEmitHelper.unboxDouble(ctx);
			ctx.body.dconst_0().dcmpl();
			MethodCode.Label ifPositive = ctx.body.newLabel();
			ctx.body.ifgt(ifPositive);
			ctx.body.invokestatic(ctx.numOp(JvmNumericRuntimeBuilder.RANDOM));
			MethodCode.Label done = ctx.body.newLabel();
			ctx.body.goto_(done);
			ctx.body.labelBinding(ifPositive);
			JvmEmitHelper.unboxDouble(ctx);
			ctx.body.invokestatic(ctx.mathOp(JvmMathFnCompiler.TLR_CURRENT));
			ctx.body.invokevirtual(ctx.mathOp(JvmMathFnCompiler.TLR_NEXT_DOUBLE)).dmul();
			JvmEmitHelper.boxDouble(ctx);
			ctx.body.labelBinding(done);
		}
		else {
			// Non-literal limit: _random dispatches on the runtime type (a Double limit
			// returns a Double, otherwise the truncated Long), so a float limit through a
			// variable works, and rejects a non-positive or ratio limit (.todo/981).
			ctx.body.invokestatic(ctx.numOp(JvmNumericRuntimeBuilder.RANDOM));
		}
	}

}
