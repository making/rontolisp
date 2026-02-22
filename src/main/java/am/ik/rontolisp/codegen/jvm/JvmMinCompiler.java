package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.jvm.Opcode;

/**
 * Compiles the {@code min} built-in function.
 */
final class JvmMinCompiler {

	private JvmMinCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		if (JvmLispCompiler.hasDoubleLiteral(args)) {
			JvmExprCompiler.compileExpr(args.get(1), ctx, className);
			JvmEmitHelper.unboxDouble(ctx);
			JvmExprCompiler.compileExpr(args.get(2), ctx, className);
			JvmEmitHelper.unboxDouble(ctx);
			ctx.emit(Opcode.INVOKESTATIC);
			ctx.emitU2(ctx.mathMinDouble.index());
			JvmEmitHelper.boxDouble(ctx);
		}
		else {
			JvmExprCompiler.compileExpr(args.get(1), ctx, className);
			JvmEmitHelper.unboxLong(ctx);
			JvmExprCompiler.compileExpr(args.get(2), ctx, className);
			JvmEmitHelper.unboxLong(ctx);
			ctx.emit(Opcode.INVOKESTATIC);
			ctx.emitU2(ctx.mathMinLong.index());
			JvmEmitHelper.boxLong(ctx);
		}
	}

}
