package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.jvm.Opcode;

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
		if (JvmLispCompiler.hasDoubleLiteral(args)) {
			// Float limit: tlr.nextDouble() * limit, kept as a double.
			JvmEmitHelper.unboxDouble(ctx);
			ctx.emit(Opcode.INVOKESTATIC);
			ctx.emitU2(ctx.mathOp(JvmMathFnCompiler.TLR_CURRENT).index());
			ctx.emit(Opcode.INVOKEVIRTUAL);
			ctx.emitU2(ctx.mathOp(JvmMathFnCompiler.TLR_NEXT_DOUBLE).index());
			ctx.emit(Opcode.DMUL);
			JvmEmitHelper.boxDouble(ctx);
		}
		else {
			// Non-literal limit: _random dispatches on the runtime type (a Double limit
			// returns a Double, otherwise the truncated Long), so a float limit through a
			// variable works.
			ctx.emit(Opcode.INVOKESTATIC);
			ctx.emitU2(ctx.numOp(JvmNumericRuntimeBuilder.RANDOM).index());
		}
	}

}
