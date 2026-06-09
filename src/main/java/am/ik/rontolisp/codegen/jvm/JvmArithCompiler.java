package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.jvm.Opcode;

/**
 * Compiles arithmetic operations ({@code +}, {@code -}, {@code *}, {@code /},
 * {@code mod}). The integer path is dispatched to the numeric runtime helpers, which keep
 * values as {@code Long} and promote to {@code BigInteger} on overflow.
 */
final class JvmArithCompiler {

	private JvmArithCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String opKey, int doubleOpcode, String className) {
		List<LispVal> args = cons.toList();
		if (JvmLispCompiler.hasDoubleLiteral(args)) {
			JvmExprCompiler.compileExpr(args.get(1), ctx, className);
			JvmEmitHelper.unboxDouble(ctx);
			for (int i = 2; i < args.size(); i++) {
				JvmExprCompiler.compileExpr(args.get(i), ctx, className);
				JvmEmitHelper.unboxDouble(ctx);
				ctx.emit(doubleOpcode);
			}
			JvmEmitHelper.boxDouble(ctx);
			return;
		}
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		// Unary subtraction is negation; the other operators leave a single argument
		// as-is.
		if (JvmNumericRuntimeBuilder.SUB.equals(opKey) && args.size() == 2) {
			ctx.emit(Opcode.INVOKESTATIC);
			ctx.emitU2(ctx.numOp(JvmNumericRuntimeBuilder.NEG).index());
			return;
		}
		for (int i = 2; i < args.size(); i++) {
			JvmExprCompiler.compileExpr(args.get(i), ctx, className);
			ctx.emit(Opcode.INVOKESTATIC);
			ctx.emitU2(ctx.numOp(opKey).index());
		}
	}

}
