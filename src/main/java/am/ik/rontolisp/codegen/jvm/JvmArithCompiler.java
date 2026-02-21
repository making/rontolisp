package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.jvm.Opcode;

/**
 * Compiles arithmetic operations ({@code +}, {@code -}, {@code *}, {@code /},
 * {@code mod}).
 */
final class JvmArithCompiler {

	private JvmArithCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, int longOpcode, int doubleOpcode, String className) {
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
		}
		else {
			JvmExprCompiler.compileExpr(args.get(1), ctx, className);
			JvmEmitHelper.unboxLong(ctx);
			for (int i = 2; i < args.size(); i++) {
				JvmExprCompiler.compileExpr(args.get(i), ctx, className);
				JvmEmitHelper.unboxLong(ctx);
				ctx.emit(longOpcode);
			}
			JvmEmitHelper.boxLong(ctx);
		}
	}

}
