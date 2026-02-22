package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.jvm.Opcode;

/**
 * Compiles the {@code abs} built-in function.
 */
final class JvmAbsCompiler {

	private JvmAbsCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		if (JvmLispCompiler.hasDoubleLiteral(args)) {
			JvmEmitHelper.unboxDouble(ctx);
			ctx.emit(Opcode.INVOKESTATIC);
			ctx.emitU2(ctx.mathAbsDouble.index());
			JvmEmitHelper.boxDouble(ctx);
		}
		else {
			JvmEmitHelper.unboxLong(ctx);
			ctx.emit(Opcode.INVOKESTATIC);
			ctx.emitU2(ctx.mathAbsLong.index());
			JvmEmitHelper.boxLong(ctx);
		}
	}

}
