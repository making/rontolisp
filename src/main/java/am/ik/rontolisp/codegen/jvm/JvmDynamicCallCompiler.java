package am.ik.rontolisp.codegen.jvm;

import java.lang.classfile.constantpool.MethodRefEntry;
import java.lang.classfile.constantpool.Utf8Entry;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;

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
		ctx.body.aconst_null();
		emitInvoke("_eval", ctx, className);
	}

	private static void emitInvoke(String method, JvmLispCompiler.Ctx ctx, String className) {
		Utf8Entry nameUtf8 = ctx.cp.utf8Entry(method);
		Utf8Entry descUtf8 = ctx.cp.utf8Entry("(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
		MethodRefEntry ref = ctx.cp.methodRef(ctx.cp.classEntry(className), nameUtf8, descUtf8);
		ctx.body.invokestatic(ref);
	}

}
