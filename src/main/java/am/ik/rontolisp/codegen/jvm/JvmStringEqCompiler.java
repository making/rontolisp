package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;

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
		JvmArrayCompiler.emitStrvNormalize(ctx, className);
		ctx.body.checkcast(ctx.stringClass.entry());
		JvmExprCompiler.compileExpr(args.get(2), ctx, className);
		JvmArrayCompiler.emitStrvNormalize(ctx, className);
		ctx.body.invokevirtual(ctx.objectEquals.methodRefEntry());
		JvmEmitHelper.emitBoolFromInt(ctx);
	}

	static void compileEqual(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		MethodrefConstant equalsIgnoreCase = JvmEmitHelper.stringMethod(ctx, "equalsIgnoreCase",
				"(Ljava/lang/String;)Z");
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		JvmArrayCompiler.emitStrvNormalize(ctx, className);
		ctx.body.checkcast(ctx.stringClass.entry());
		JvmExprCompiler.compileExpr(args.get(2), ctx, className);
		JvmArrayCompiler.emitStrvNormalize(ctx, className);
		ctx.body.checkcast(ctx.stringClass.entry()).invokevirtual(equalsIgnoreCase.methodRefEntry());
		JvmEmitHelper.emitBoolFromInt(ctx);
	}

}
