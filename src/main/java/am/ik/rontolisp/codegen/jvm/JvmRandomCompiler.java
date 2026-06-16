package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.jvm.Opcode;

/**
 * Compiles the {@code random} built-in function. Returns a non-negative random number
 * below the limit, of the same type as the limit. Like {@link JvmAbsCompiler}, the
 * integer and float paths are selected at compile time from the literal shape of the
 * argument: a float literal yields {@code Math.random() * limit} (a double), otherwise
 * the integer path yields {@code (long) (Math.random() * limit)}. Both draw from
 * {@code java.lang.Math.random}.
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
			// Float limit: Math.random() * limit, kept as a double.
			JvmEmitHelper.unboxDouble(ctx);
			ctx.emit(Opcode.INVOKESTATIC);
			ctx.emitU2(ctx.mathOp(JvmMathFnCompiler.RANDOM).index());
			ctx.emit(Opcode.DMUL);
			JvmEmitHelper.boxDouble(ctx);
		}
		else {
			// Integer limit: (long) (Math.random() * limit).
			JvmEmitHelper.unboxLong(ctx);
			ctx.emit(Opcode.L2D);
			ctx.emit(Opcode.INVOKESTATIC);
			ctx.emitU2(ctx.mathOp(JvmMathFnCompiler.RANDOM).index());
			ctx.emit(Opcode.DMUL);
			ctx.emit(Opcode.D2L);
			JvmEmitHelper.boxLong(ctx);
		}
	}

}
