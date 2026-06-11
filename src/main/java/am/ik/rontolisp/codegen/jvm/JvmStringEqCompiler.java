package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.jvm.Opcode;

/**
 * Compiles the string equality predicates {@code string=} (case-sensitive) and
 * {@code string-equal} (case-insensitive). Both runtime operands carry surrounding
 * quotes, so comparing the whole strings is equivalent to comparing their contents. The
 * boolean result is converted to the Lisp boolean ({@code t} / nil).
 */
final class JvmStringEqCompiler {

	private JvmStringEqCompiler() {
	}

	static void compileEq(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		ctx.emit(Opcode.CHECKCAST);
		ctx.emitU2(ctx.stringClass.index());
		JvmExprCompiler.compileExpr(args.get(2), ctx, className);
		ctx.emit(Opcode.INVOKEVIRTUAL);
		ctx.emitU2(ctx.objectEquals.index());
		JvmEmitHelper.emitBoolFromInt(ctx);
	}

	static void compileEqual(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		int equalsIgnoreCase = JvmEmitHelper.stringMethod(ctx, "equalsIgnoreCase", "(Ljava/lang/String;)Z").index();
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		ctx.emit(Opcode.CHECKCAST);
		ctx.emitU2(ctx.stringClass.index());
		JvmExprCompiler.compileExpr(args.get(2), ctx, className);
		ctx.emit(Opcode.CHECKCAST);
		ctx.emitU2(ctx.stringClass.index());
		ctx.emit(Opcode.INVOKEVIRTUAL);
		ctx.emitU2(equalsIgnoreCase);
		JvmEmitHelper.emitBoolFromInt(ctx);
	}

}
