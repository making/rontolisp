package am.ik.rontolisp.codegen.wasm;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;

/**
 * Compiles function calls and variable references that cannot be resolved statically,
 * deferring resolution to runtime via the embedded {@code eval} runtime (late binding).
 * <p>
 * This path is only taken when the compiler runs in dynamic mode
 * ({@link WasmLispCompiler.Ctx#dynamic}). It lets a program that defines functions or
 * variables at runtime (for example through {@code load}) compile unchanged: instead of
 * rejecting an unknown symbol at compile time, the generated code looks it up in the
 * global environment when it actually runs.
 * <p>
 * A call {@code (f a b)} compiles to
 * {@code _apply(_eval('(function f), null), (list a b))}: the operator symbol is resolved
 * in the runtime function namespace through {@code _eval}, the arguments are compiled
 * normally (so compiled locals remain visible) and collected into a runtime list, and
 * {@code _apply} applies the function value to them. A bare reference {@code x} compiles
 * to {@code _eval('x, null)}, which resolves the variable namespace only.
 */
final class WasmDynamicCallCompiler {

	private WasmDynamicCallCompiler() {
	}

	/**
	 * Compiles an unresolved function call {@code (name arg...)} as a late-bound apply.
	 */
	static void compileCall(String name, LispCons cons, WasmLispCompiler.Ctx ctx) {
		// fn = _eval('(function name), null)
		compileFunctionRef(name, ctx);
		// args = (list arg...)
		LispVal listForm = new LispCons(new LispSymbol(LispNames.LIST), cons.cdr());
		WasmExprCompiler.compileExpr(listForm, ctx);
		// _apply(fn, args)
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.FUNC_APPLY);
	}

	/** Compiles an unresolved variable reference {@code name} as a late-bound lookup. */
	static void compileVarRef(String name, WasmLispCompiler.Ctx ctx) {
		// (quote name): the runtime _eval resolves the variable namespace only
		LispVal quoteForm = new LispCons(new LispSymbol(LispNames.QUOTE),
				new LispCons(new LispSymbol(name), LispNil.INSTANCE));
		compileEvalForm(quoteForm, ctx);
	}

	/**
	 * Compiles an unresolved function reference {@code (function name)} as a late-bound
	 * lookup in the runtime function namespace.
	 */
	static void compileFunctionRef(String name, WasmLispCompiler.Ctx ctx) {
		// '(function name) is passed unevaluated, so quote the whole form
		LispVal functionForm = new LispCons(new LispSymbol(LispNames.FUNCTION),
				new LispCons(new LispSymbol(name), LispNil.INSTANCE));
		LispVal quoteForm = new LispCons(new LispSymbol(LispNames.QUOTE), new LispCons(functionForm, LispNil.INSTANCE));
		compileEvalForm(quoteForm, ctx);
	}

	// Pushes the result of _eval(form, null) onto the stack.
	private static void compileEvalForm(LispVal quotedForm, WasmLispCompiler.Ctx ctx) {
		WasmExprCompiler.compileExpr(quotedForm, ctx);
		// env = ref.null eq (empty/global lexical environment)
		ctx.writer.write(Instruction.REF_NULL);
		ctx.writer.writeHeapType(Type.EQ.code());
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.FUNC_EVAL);
	}

}
