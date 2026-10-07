package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LambdaLists;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.macro.LispMacroExpander;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;
import am.ik.rontolisp.compiler.BuiltinFunctionWrappers;
import am.ik.rontolisp.compiler.CompileWarnings;
import am.ik.rontolisp.compiler.FunctionDesignators;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;

/**
 * Compiles the {@code (function name)} special form ({@code #'name} reader syntax) and
 * {@code (symbol-function 'name)}. Under the Lisp-2 model these are the only ways to
 * obtain a function as a first-class value: a named function resolves against the
 * compile-time function registry (user defuns and built-in wrappers) and compiles to a
 * closure struct {@code {funcId, null env}}; {@code (function (lambda ...))} compiles the
 * lambda value directly. In dynamic mode an unresolved name defers to the runtime via
 * {@code _eval('(function name), null)}; otherwise a name no definition has compiles to
 * the undefined-function signal a direct call of it reaches, where the reference runs.
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
		LispSymbol setfPlace = LambdaLists.setfFunctionPlaceName(designator);
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
		if (parts.size() != 2) {
			throw new IllegalArgumentException(
					LispNames.SYMBOL_FUNCTION + " expects exactly one argument: " + cons.print());
		}
		// A run-time name resolution BOXES the resolved funcId as a closure struct
		// {funcId, null env} -- exactly the value #'name would have produced -- so
		// functionp answers t and the value prints its registered name (.todo/750).
		// The runtime function namespace (GLOBAL_FENV, where (setf
		// (symbol-function ...)) installs and fmakunbound leaves its tombstone) is
		// probed first and decides on its own; otherwise the compiled-function
		// registry (_lookup, live through usesRuntimeFunctionBox) answers, and a miss
		// traps exactly like the dispatchers' late binding. Only the function
		// namespace is read: a global VARIABLE holding a lambda is not a function
		// binding (the interpreter and SBCL signal for it).
		WasmExprCompiler.compileExpr(parts.get(1), ctx);
		int symTemp = ctx.allocTemp();
		int bindTemp = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(symTemp);
		// nil or a non-string names no function: the arity-0 dispatcher, whose late
		// binding this is, reports each as it reports a funcall of the same value.
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(symTemp);
		ctx.writer.write(Instruction.REF_IS_NULL);
		ctx.writer.write(Instruction.IF, 0x40);
		emitUnboundSignal(ctx, symTemp);
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(symTemp);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_STRING);
		ctx.writer.write(Instruction.I32_EQZ);
		ctx.writer.write(Instruction.IF, 0x40);
		emitUnboundSignal(ctx, symTemp);
		ctx.writer.write(Instruction.END);
		// bind = _env_lookup(off, GLOBAL_FENV).
		emitStringOffset(ctx, symTemp);
		ctx.writer.write(Instruction.GET_GLOBAL);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.GLOBAL_FENV);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_ENV_LOOKUP);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(bindTemp);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(bindTemp);
		ctx.writer.write(Instruction.REF_IS_NULL);
		ctx.writer.write(Instruction.IF);
		ctx.writer.writeRefType(true, Type.EQ.code());
		// No binding: resolve through the registry and box.
		emitRegistryBox(ctx, symTemp);
		ctx.writer.write(Instruction.ELSE);
		// A binding decides on its own: the value cell, or -- for fmakunbound's
		// tombstone (cdr nil), which shadows the registry -- an undefined-function.
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(bindTemp);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CONS);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		ctx.writer.writeUnsignedLeb128(1);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(bindTemp);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(bindTemp);
		ctx.writer.write(Instruction.REF_IS_NULL);
		ctx.writer.write(Instruction.IF);
		ctx.writer.writeRefType(true, Type.EQ.code());
		emitRetiredSignal(ctx, symTemp);
		ctx.writer.write(Instruction.ELSE);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(bindTemp);
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.END);
	}

	/** Pushes the interned string-table offset of the symbol in {@code symTemp}. */
	private static void emitStringOffset(WasmLispCompiler.Ctx ctx, int symTemp) {
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(symTemp);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_STRING);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_STRING);
		ctx.writer.writeUnsignedLeb128(0);
	}

	/**
	 * Resolves the symbol in {@code symTemp} through the compiled-function registry and
	 * boxes the hit as a closure struct, trapping on a miss. Leaves the closure on the
	 * stack.
	 */
	private static void emitRegistryBox(WasmLispCompiler.Ctx ctx, int symTemp) {
		// found = _lookup(off) != -1?
		emitStringOffset(ctx, symTemp);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_LOOKUP);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(-1);
		ctx.writer.write(Instruction.I32_NE);
		ctx.writer.write(Instruction.IF);
		ctx.writer.writeRefType(true, Type.EQ.code());
		// Reload (no i32 temp exists inline) and box {funcId, null env}.
		emitStringOffset(ctx, symTemp);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_LOOKUP);
		ctx.writer.write(Instruction.I32_LOAD, 0x02, 0x04);
		ctx.writer.write(Instruction.REF_NULL);
		ctx.writer.writeHeapType(Type.EQ.code());
		WasmEmitHelper.emitNewClosure(ctx);
		ctx.writer.write(Instruction.ELSE);
		emitUnboundSignal(ctx, symTemp);
		ctx.writer.write(Instruction.END);
	}

	/**
	 * Reports the value in {@code symTemp}, which names no function, through the arity-0
	 * dispatcher: an {@code undefined-function} for a symbol (nil and {@code t} included)
	 * and a {@code type-error} for anything else where the program can catch one, the
	 * trap it always was where it cannot. Never returns.
	 */
	private static void emitUnboundSignal(WasmLispCompiler.Ctx ctx, int symTemp) {
		ctx.indirectCallArities.add(0);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(symTemp);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.dispatchFuncIndex(0, ctx.extraDispatchFuncBase));
		ctx.writer.write(Instruction.DROP);
		ctx.writer.write(Instruction.UNREACHABLE);
	}

	/**
	 * Reports the symbol in {@code symTemp}, a name {@code fmakunbound} retired, as an
	 * {@code undefined-function} through {@code _undefined_function}: not through the
	 * dispatcher {@link #emitUnboundSignal} uses, whose registry lookup may still answer
	 * the retired name and call it. A trap where the module carries no report (outside EH
	 * mode, where nothing could catch one). Never returns.
	 */
	private static void emitRetiredSignal(WasmLispCompiler.Ctx ctx, int symTemp) {
		if (ctx.undefinedFunctionFuncIndex < 0) {
			ctx.writer.write(Instruction.UNREACHABLE);
			return;
		}
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(symTemp);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeUnsignedLeb128(ctx.undefinedFunctionFuncIndex);
		ctx.writer.write(Instruction.DROP);
		ctx.writer.write(Instruction.UNREACHABLE);
	}

	/**
	 * The {@code GLOBAL_FENV} binding value of {@code name}, or -- with no binding, or
	 * {@code fmakunbound}'s tombstone -- the undefined-function a direct call of an
	 * undefined name signals
	 * ({@link WasmFunctionCallCompiler#emitUndefinedFunctionSignal}):
	 * {@code (%fenv-function 'name)}, the body of a symbol-function forwarder, and
	 * {@code #'name} of a forwarder's name. The compiled-function registry is not probed:
	 * it would answer the forwarder itself.
	 */
	static void emitFenvRead(String name, WasmLispCompiler.Ctx ctx) {
		WasmExprCompiler.compileExpr(
				new LispCons(new LispSymbol(LispNames.QUOTE), new LispCons(new LispSymbol(name), LispNil.INSTANCE)),
				ctx);
		int symTemp = ctx.allocTemp();
		int valueTemp = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(symTemp);
		emitStringOffset(ctx, symTemp);
		ctx.writer.write(Instruction.GET_GLOBAL);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.GLOBAL_FENV);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_ENV_LOOKUP);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(valueTemp);
		// value = binding ? binding.cdr : null -- a tombstone's cdr is null as well
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(valueTemp);
		ctx.writer.write(Instruction.REF_IS_NULL);
		ctx.writer.write(Instruction.IF);
		ctx.writer.writeRefType(true, Type.EQ.code());
		ctx.writer.write(Instruction.REF_NULL);
		ctx.writer.writeHeapType(Type.EQ.code());
		ctx.writer.write(Instruction.ELSE);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(valueTemp);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CONS);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		ctx.writer.writeUnsignedLeb128(1);
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(valueTemp);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(valueTemp);
		ctx.writer.write(Instruction.REF_IS_NULL);
		ctx.writer.write(Instruction.IF);
		ctx.writer.writeRefType(true, Type.EQ.code());
		WasmFunctionCallCompiler.emitUndefinedFunctionSignal(name, ctx);
		ctx.writer.write(Instruction.ELSE);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(valueTemp);
		ctx.writer.write(Instruction.END);
	}

	static void compileNamed(String name, WasmLispCompiler.Ctx ctx) {
		if (ctx.fenvForwarders.contains(name)) {
			// A name only (setf (symbol-function 'name) ...) binds: its value is what the
			// setf installed, read when the reference runs -- an undefined-function
			// before that -- never the forwarder defun.
			emitFenvRead(name, ctx);
			return;
		}
		if (!ctx.functions.containsKey(name) && LispNames.isCarCdrComposition(name)) {
			// Synthesize (lambda (x) (cadr x)) so car/cdr compositions are first-class
			WasmLambdaCompiler.compileValue(carCdrLambda(name), ctx);
			return;
		}
		WasmLispCompiler.WasmFunctionInfo fi = ctx.functions.get(name);
		if (fi != null) {
			// Create closure struct {funcId, null env}
			// This is one of the two places a funcId becomes a callable VALUE, so it is
			// where the dispatch ladders learn they must carry a case for it.
			ctx.noteFunctionValue(fi.funcId());
			ctx.writer.write(Instruction.I32_CONST);
			ctx.writer.writeSignedLeb128(fi.funcId());
			ctx.writer.write(Instruction.REF_NULL);
			ctx.writer.writeHeapType(Type.EQ.code());
			WasmEmitHelper.emitNewClosure(ctx);
		}
		else if (ctx.nestedDefunNames.contains(name) && ctx.globalIndices.containsKey(name)) {
			// A defun nested inside a top-level let or a function body compiles to
			// (setq name (lambda ...)): the global variable already HOLDS the function
			// value. Before the dynamic fallback for the same reason the call site
			// checks it first (WasmFunctionCallCompiler).
			WasmExprCompiler.compileExpr(new am.ik.rontolisp.LispSymbol(name), ctx);
		}
		else if (ctx.dynamic) {
			WasmDynamicCallCompiler.compileFunctionRef(name, ctx);
		}
		else if (ctx.globalIndices.containsKey(name)) {
			// A top-level (setq name (lambda ...)) the same way.
			WasmExprCompiler.compileExpr(new am.ik.rontolisp.LispSymbol(name), ctx);
		}
		else if (undefined(name, ctx)) {
			// A name no definition has: the interpreter's late binding, as for a direct
			// call (WasmFunctionCallCompiler) -- the undefined-function is signalled
			// where
			// the reference is EVALUATED, so a branch never taken still compiles.
			CompileWarnings.warn(null, "the function " + name + " is undefined; compiled as a run-time error");
			WasmFunctionCallCompiler.emitUndefinedFunctionSignal(name, ctx);
		}
		else {
			// A standard function with no function value here (require/provide, which
			// the compile path has only as literal top-level forms): it HAS a
			// definition, so the late-binding signal above would misreport it.
			throw new UnsupportedOperationException(BuiltinFunctionWrappers.noFunctionValueMessage(name));
		}
	}

	/**
	 * {@return whether {@code name} has no definition at all -- no function this backend
	 * registered, no car/cdr composition, no variable holding the function, no standard
	 * function or wrapped built-in, and no {@code --dynamic} runtime to ask}
	 * @param name the function name
	 * @param ctx the compilation context
	 */
	static boolean undefined(String name, WasmLispCompiler.Ctx ctx) {
		return !ctx.functions.containsKey(name) && !LispNames.isCarCdrComposition(name) && !ctx.dynamic
				&& !ctx.globalIndices.containsKey(name) && !PackageRegistry.isClFunctionName(name)
				&& !BuiltinFunctionWrappers.isWrappedBuiltin(name);
	}

	/**
	 * The expression a function-designator ARGUMENT compiles to
	 * ({@link FunctionDesignators#normalize(LispVal, java.util.function.Predicate)}): a
	 * quoted name this backend resolves becomes {@code (function name)}, one no
	 * definition has stays the symbol the dispatcher looks up when the call runs.
	 * @param fnForm the expression in function-designator position
	 * @param ctx the compilation context
	 * @return the expression to compile
	 */
	static LispVal designator(LispVal fnForm, WasmLispCompiler.Ctx ctx) {
		return FunctionDesignators.normalize(fnForm, name -> !undefined(name, ctx));
	}

	private static LispCons carCdrLambda(String name) {
		LispSymbol param = new LispSymbol("x");
		LispVal call = new LispCons(new LispSymbol(name), new LispCons(param, LispNil.INSTANCE));
		LispVal params = new LispCons(param, LispNil.INSTANCE);
		return new LispCons(new LispSymbol(LispNames.LAMBDA),
				new LispCons(params, new LispCons(call, LispNil.INSTANCE)));
	}

}
