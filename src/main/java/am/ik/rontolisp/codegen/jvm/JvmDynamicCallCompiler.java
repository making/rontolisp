package am.ik.rontolisp.codegen.jvm;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.ConstantPool.Utf8Constant;
import am.ik.jvm.Opcode;

/**
 * Compiles function calls and variable references that cannot be resolved statically,
 * deferring resolution to runtime via the embedded {@code eval} runtime (late binding).
 * <p>
 * This path is only taken when the compiler runs in dynamic mode
 * ({@link JvmLispCompiler.Ctx#dynamic}). It lets a program that defines functions or
 * variables at runtime (for example through {@code load}) compile unchanged: instead of
 * rejecting an unknown symbol at compile time, the generated code looks it up in the
 * {@code _genv} global environment when it actually runs.
 * <p>
 * A call {@code (f a b)} compiles to
 * {@code _apply(_eval('(function f), null), (list a b))}: the operator symbol is resolved
 * in the runtime function namespace through {@code _eval}, the arguments are compiled
 * normally (so compiled locals remain visible) and collected into a runtime list, and
 * {@code _apply} applies the function value to them. A bare reference {@code x} compiles
 * to {@code _eval('x, null)}, which resolves the variable namespace only.
 */
final class JvmDynamicCallCompiler {

	private JvmDynamicCallCompiler() {
	}

	/**
	 * Compiles an unresolved function call {@code (name arg...)} as a late-bound apply.
	 */
	static void compileCall(String name, LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		// fn = _eval('(function name), null)
		compileFunctionRef(name, ctx, className);
		// args = (list arg...)
		LispVal listForm = new LispCons(new LispSymbol(LispNames.LIST), cons.cdr());
		JvmExprCompiler.compileExpr(listForm, ctx, className);
		// _apply(fn, args)
		emitInvoke("_apply", ctx, className);
	}

	/** Compiles an unresolved variable reference {@code name} as a late-bound lookup. */
	static void compileVarRef(String name, JvmLispCompiler.Ctx ctx) {
		// (quote name): the runtime _eval resolves the variable namespace only
		LispVal quoteForm = new LispCons(new LispSymbol(LispNames.QUOTE),
				new LispCons(new LispSymbol(name), LispNil.INSTANCE));
		compileEvalForm(quoteForm, ctx, ctx.className);
	}

	/**
	 * Compiles an unresolved function reference {@code (function name)} as a late-bound
	 * lookup in the runtime function namespace.
	 */
	static void compileFunctionRef(String name, JvmLispCompiler.Ctx ctx, String className) {
		// '(function name) is passed unevaluated, so quote the whole form
		LispVal functionForm = new LispCons(new LispSymbol(LispNames.FUNCTION),
				new LispCons(new LispSymbol(name), LispNil.INSTANCE));
		LispVal quoteForm = new LispCons(new LispSymbol(LispNames.QUOTE), new LispCons(functionForm, LispNil.INSTANCE));
		compileEvalForm(quoteForm, ctx, className);
	}

	// Pushes the result of _eval(form, null) onto the stack.
	private static void compileEvalForm(LispVal quotedForm, JvmLispCompiler.Ctx ctx, String className) {
		JvmExprCompiler.compileExpr(quotedForm, ctx, className);
		// env = null (empty/global lexical environment)
		ctx.emit(Opcode.ACONST_NULL);
		emitInvoke("_eval", ctx, className);
	}

	private static void emitInvoke(String method, JvmLispCompiler.Ctx ctx, String className) {
		Utf8Constant nameUtf8 = ctx.cp.addUtf8(method);
		Utf8Constant descUtf8 = ctx.cp.addUtf8("(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
		MethodrefConstant ref = ctx.cp.addMethodref(ctx.cp.addClass(ctx.cp.addUtf8(className)),
				ctx.cp.addNameAndType(nameUtf8, descUtf8));
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(ref.index());
	}

}
