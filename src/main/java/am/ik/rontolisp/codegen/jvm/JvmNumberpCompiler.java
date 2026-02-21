package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.jvm.Opcode;

/**
 * Compiles the {@code numberp} predicate.
 */
final class JvmNumberpCompiler {

	private JvmNumberpCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		ctx.emit(Opcode.INSTANCEOF);
		ctx.emitU2(ctx.numberClass.index());
		JvmEmitHelper.emitBoolFromInt(ctx);
	}

}
