package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispMacroExpander;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;

/**
 * Compiles the {@code (function name)} special form ({@code #'name} reader syntax) and
 * {@code (symbol-function 'name)}. Under the Lisp-2 model these are the only ways to
 * obtain a function as a first-class value: a named function resolves against the
 * compile-time function registry (user defuns and built-in wrappers) and compiles to a
 * closure struct {@code {funcId, null env}}; {@code (function (lambda ...))} compiles the
 * lambda value directly. In dynamic mode an unresolved name defers to the runtime via
 * {@code _eval('(function name), null)}.
 */
final class WasmFunctionFormCompiler {

	private WasmFunctionFormCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> parts = cons.toList();
		if (parts.size() != 2) {
			throw new UnsupportedOperationException(LispNames.FUNCTION + " expects exactly one argument");
		}
		LispVal designator = parts.get(1);
		if (designator instanceof LispCons lambdaForm && lambdaForm.car() instanceof LispSymbol op
				&& LispNames.LAMBDA.equals(op.name())) {
			WasmLambdaCompiler.compileValue(lambdaForm, ctx);
			return;
		}
		LispSymbol setfPlace = LispMacroExpander.setfFunctionPlaceName(designator);
		if (setfPlace != null) {
			// #'(setf name): the writer defun installed under the mangled internal name.
			compileNamed(LispMacroExpander.setfFunctionName(setfPlace.name()), ctx);
			return;
		}
		if (designator instanceof LispSymbol sym) {
			compileNamed(sym.name(), ctx);
			return;
		}
		throw new UnsupportedOperationException("Cannot compile: " + cons.print());
	}

	static void compileSymbolFunction(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> parts = cons.toList();
		if (parts.size() == 2 && parts.get(1) instanceof LispCons quoteForm && quoteForm.car() instanceof LispSymbol op
				&& LispNames.QUOTE.equals(op.name()) && ((LispCons) quoteForm.cdr()).car() instanceof LispSymbol sym) {
			compileNamed(sym.name(), ctx);
			return;
		}
		// A non-literal designator becomes a cold-path runtime signal (no runtime
		// name-to-function table without --dynamic).
		WasmExprCompiler.compileExpr(LispMacroExpander.expandRuntimeSymbolFunctionError(cons), ctx);
	}

	static void compileNamed(String name, WasmLispCompiler.Ctx ctx) {
		if (!ctx.functions.containsKey(name) && LispMacroExpander.isCarCdrComposition(name)) {
			// Synthesize (lambda (x) (cadr x)) so car/cdr compositions are first-class
			WasmLambdaCompiler.compileValue(carCdrLambda(name), ctx);
			return;
		}
		WasmLispCompiler.WasmFunctionInfo fi = ctx.functions.get(name);
		if (fi != null) {
			// Create closure struct {funcId, null env}
			ctx.writer.write(Instruction.I32_CONST);
			ctx.writer.writeSignedLeb128(fi.funcId());
			ctx.writer.write(Instruction.REF_NULL);
			ctx.writer.writeHeapType(Type.EQ.code());
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
			ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_CLOSURE);
		}
		else if (ctx.dynamic) {
			WasmDynamicCallCompiler.compileFunctionRef(name, ctx);
		}
		else if (ctx.globalIndices.containsKey(name)) {
			// A defun nested inside a top-level let compiles to (setq name
			// (lambda ...)): the global variable already HOLDS the function value.
			WasmExprCompiler.compileExpr(new am.ik.rontolisp.LispSymbol(name), ctx);
		}
		else {
			throw new UnsupportedOperationException("Cannot compile: " + name);
		}
	}

	private static LispCons carCdrLambda(String name) {
		LispSymbol param = new LispSymbol("x");
		LispVal call = new LispCons(new LispSymbol(name), new LispCons(param, LispNil.INSTANCE));
		LispVal params = new LispCons(param, LispNil.INSTANCE);
		return new LispCons(new LispSymbol(LispNames.LAMBDA),
				new LispCons(params, new LispCons(call, LispNil.INSTANCE)));
	}

}
