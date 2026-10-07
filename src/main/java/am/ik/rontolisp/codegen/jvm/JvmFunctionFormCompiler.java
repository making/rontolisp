package am.ik.rontolisp.codegen.jvm;

import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.FieldRefEntry;
import java.lang.classfile.constantpool.MethodRefEntry;
import java.util.List;

import am.ik.jvm.MethodCode;
import am.ik.rontolisp.ClosRegistry;
import am.ik.rontolisp.LambdaLists;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.macro.LispMacroExpander;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;
import am.ik.rontolisp.compiler.BuiltinFunctionWrappers;
import am.ik.rontolisp.compiler.CompileWarnings;
import am.ik.rontolisp.compiler.FunctionDesignators;

/**
 * Compiles the {@code (function name)} special form ({@code #'name} reader syntax) and
 * {@code (symbol-function 'name)}. Under the Lisp-2 model these are the only ways to
 * obtain a function as a first-class value: a named function resolves against the
 * compile-time function registry (user defuns and built-in wrappers) and compiles to a
 * closure {@code Object[]{Integer funcId}}; {@code (function (lambda ...))} compiles the
 * lambda value directly. In dynamic mode an unresolved name defers to the runtime via
 * {@code _eval('(function name), null)}; otherwise a name no definition has compiles to
 * the undefined-function signal a direct call of it reaches, where the reference runs.
 */
final class JvmFunctionFormCompiler {

	private JvmFunctionFormCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> parts = cons.toList();
		if (parts.size() != 2) {
			throw new UnsupportedOperationException(LispNames.FUNCTION + " expects exactly one argument");
		}
		LispVal designator = parts.get(1);
		if (designator instanceof LispCons lambdaForm && lambdaForm.car() instanceof LispSymbol op
				&& LispNames.LAMBDA.equals(op.name())) {
			JvmLambdaCompiler.compileValue(lambdaForm, ctx, className);
			return;
		}
		LispSymbol setfPlace = LambdaLists.setfFunctionPlaceName(designator);
		if (setfPlace != null) {
			// #'(setf name): the writer defun installed under the mangled internal name.
			compileNamed(LispMacroExpander.setfFunctionName(setfPlace.name()), ctx, className);
			return;
		}
		if (designator instanceof LispSymbol sym) {
			compileNamed(sym.name(), ctx, className);
			return;
		}
		throw new UnsupportedOperationException("Cannot compile: " + cons.print());
	}

	static void compileSymbolFunction(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> parts = cons.toList();
		if (parts.size() == 2 && parts.get(1) instanceof LispCons quoteForm && quoteForm.car() instanceof LispSymbol op
				&& LispNames.QUOTE.equals(op.name()) && ((LispCons) quoteForm.cdr()).car() instanceof LispSymbol sym) {
			compileNamed(sym.name(), ctx, className);
			return;
		}
		if (parts.size() != 2) {
			throw new IllegalArgumentException(
					LispNames.SYMBOL_FUNCTION + " expects exactly one argument: " + cons.print());
		}
		// A run-time name resolution BOXES the resolved funcId as a function value
		// (Object[]{Integer funcId}), so functionp answers t and the value prints
		// its registered name (.todo/750). When the eval runtime exists
		// (Ctx.evalStoreRef != null) its function namespace (_fenv, where
		// (setf (symbol-function ...)) installs and fmakunbound leaves its tombstone)
		// is probed first and decides on its own; without it _fenv is necessarily
		// empty (every writer forces the runtime), so the registry alone answers.
		// Otherwise the compiled-function registry (_lookup) answers, and a miss
		// signals exactly like the dispatchers' late binding. Only the function
		// namespace is read: a global VARIABLE holding a lambda is not a function
		// binding (the interpreter and SBCL signal for it).
		JvmExprCompiler.compileExpr(parts.get(1), ctx, className);
		int symSlot = ctx.allocTemp();
		ctx.body.astore(symSlot);
		// nil is a symbol that names no function: it reports as "NIL" like any other
		// undefined name instead of reaching the registry as a null.
		MethodCode.Label named = ctx.body.newLabel();
		ctx.body.aload(symSlot).ifnonnull(named);
		JvmEmitHelper.compileStringLiteral("NIL", ctx);
		ctx.body.astore(symSlot);
		ctx.body.labelBinding(named);
		MethodCode.Label done = ctx.body.newLabel();
		if (ctx.evalStoreRef != null) {
			ctx.body.aload(symSlot).getstatic(fenvField(ctx, className));
			ctx.body.invokestatic(envLookupRef(ctx, className)).dup();
			MethodCode.Label fenvMiss = ctx.body.newLabel();
			ctx.body.ifnull(fenvMiss);
			ctx.body.checkcast(ctx.objectArrayClass).iconst_1().aaload().dup();
			MethodCode.Label tombstone = ctx.body.newLabel();
			ctx.body.ifnull(tombstone);
			ctx.body.goto_(done);
			ctx.body.labelBinding(tombstone);
			ctx.body.pop();
			emitUndefinedFunctionThrow(symSlot, ctx);
			ctx.body.labelBinding(fenvMiss);
			ctx.body.pop();
		}
		ctx.body.aload(symSlot).invokestatic(lookupRef(ctx, className)).dup();
		MethodCode.Label registryMiss = ctx.body.newLabel();
		ctx.body.ifnull(registryMiss);
		ctx.body.iconst_0().aaload();
		int idSlot = ctx.allocTemp();
		ctx.body.astore(idSlot).iconst_1().anewarray(ctx.objectClass).dup().iconst_0();
		ctx.body.aload(idSlot).aastore();
		MethodCode.Label boxed = ctx.body.newLabel();
		ctx.body.goto_(boxed);
		ctx.body.labelBinding(registryMiss);
		ctx.body.pop();
		emitUndefinedFunctionThrow(symSlot, ctx);
		ctx.body.labelBinding(done);
		ctx.body.labelBinding(boxed);
	}

	static void compileNamed(String name, JvmLispCompiler.Ctx ctx, String className) {
		if (ctx.fenvForwarders.contains(name)) {
			// A name only (setf (symbol-function 'name) ...) binds, or a nested defun
			// the namespace holds: its value is what the setf or the definition
			// installed, read from _fenv when the reference runs -- an
			// undefined-function before that or after fmakunbound -- never the
			// forwarder defun.
			JvmSymbolApiCompiler.compileFenvFunction(name, ctx, className);
			return;
		}
		if (!ctx.functions.containsKey(name) && LispNames.isCarCdrComposition(name)) {
			// Synthesize (lambda (x) (cadr x)) so car/cdr compositions are first-class
			JvmLambdaCompiler.compileValue(carCdrLambda(name), ctx, className);
			return;
		}
		JvmLispCompiler.FunctionInfo fi = ctx.functions.get(name);
		if (fi != null) {
			// One of the two places a funcId becomes a callable VALUE, so it is where
			// the _invoke_N dispatchers learn they must carry a case for it.
			ctx.valueFuncIds.add(fi.funcId());
			// ... and the edge the shake keeps that case by: the case is live while this
			// body is (JvmClassSplitter).
			ctx.body.makesValueOf(fi.nameUtf8().stringValue(), fi.descUtf8().stringValue());
			ctx.body.iconst_1().anewarray(ctx.objectClass).dup().iconst_0();
			JvmEmitHelper.emitIntConst(ctx, fi.funcId());
			ctx.body.invokestatic(ctx.integerValueOf).aastore();
		}
		else if (nestedDefun(name, ctx)) {
			// A defun nested inside a top-level let or a function body compiles to
			// (setq name (lambda ...)): the global variable HOLDS the function value
			// once the definition ran. Before the dynamic fallback for the same reason
			// the call site checks it first (JvmFunctionCallCompiler).
			emitNestedDefunValue(name, ctx);
		}
		else if (ctx.dynamic) {
			JvmDynamicCallCompiler.compileFunctionRef(name, ctx, className);
		}
		else if (ctx.globals.contains(name)) {
			// A top-level (setq name (lambda ...)) the same way.
			JvmExprCompiler.compileExpr(new am.ik.rontolisp.LispSymbol(name), ctx, className);
		}
		else if (undefined(name, ctx) && ctx.bindsRuntimeFunctionNames) {
			// ... and in a program that can bind the name at run time, what _fenv holds
			// when the reference runs, the same signal on a miss.
			CompileWarnings.warn(null, "the function " + ClosRegistry.functionNameForReport(name)
					+ " is undefined; looked up when the reference runs");
			JvmSymbolApiCompiler.compileFenvFunction(name, ctx, className);
		}
		else if (undefined(name, ctx)) {
			// A name no definition has: the interpreter's late binding, as for a direct
			// call (JvmFunctionCallCompiler) -- the undefined-function is signalled where
			// the reference is EVALUATED, so a branch never taken still compiles.
			CompileWarnings.warn(null, "the function " + ClosRegistry.functionNameForReport(name)
					+ " is undefined; compiled as a run-time error");
			emitUndefinedFunctionThrow(name, ctx);
		}
		else {
			// A standard function with no function value here (require/provide, which
			// the compile path has only as literal top-level forms): it HAS a
			// definition, so the late-binding signal above would misreport it.
			throw new UnsupportedOperationException(BuiltinFunctionWrappers.noFunctionValueMessage(name));
		}
	}

	/**
	 * {@return whether {@code name} is a function only a {@code defun} below the top
	 * level defines: a global variable that holds the function once that definition ran}
	 * @param name the function name
	 * @param ctx the method context
	 */
	static boolean nestedDefun(String name, JvmLispCompiler.Ctx ctx) {
		return ctx.nestedDefunNames.contains(name) && ctx.globals.contains(name);
	}

	/**
	 * Pushes the function a {@link #nestedDefun} name holds, read from its global and
	 * never from a lexical variable of the same spelling; before the definition ran the
	 * global is still nil, and the reference signals the {@code undefined-function}
	 * naming the function, as a direct call of an undefined name does.
	 * @param name the function name
	 * @param ctx the method context
	 */
	static void emitNestedDefunValue(String name, JvmLispCompiler.Ctx ctx) {
		JvmExprCompiler.compileSpecialRead(name, ctx);
		ctx.body.dup();
		MethodCode.Label defined = ctx.body.newLabel();
		ctx.body.ifnonnull(defined);
		ctx.body.pop();
		emitUndefinedFunctionThrow(name, ctx);
		ctx.body.labelBinding(defined);
	}

	/**
	 * {@return whether {@code name} has no definition at all -- no function this backend
	 * registered, no car/cdr composition, no variable holding the function, no standard
	 * function or wrapped built-in, and no {@code --dynamic} runtime to ask}
	 * @param name the function name
	 * @param ctx the method context
	 */
	static boolean undefined(String name, JvmLispCompiler.Ctx ctx) {
		return !ctx.functions.containsKey(name) && !LispNames.isCarCdrComposition(name) && !ctx.dynamic
				&& !ctx.globals.contains(name) && !PackageRegistry.isClFunctionName(name)
				&& !BuiltinFunctionWrappers.isWrappedBuiltin(name);
	}

	/**
	 * The expression a function-designator ARGUMENT compiles to
	 * ({@link FunctionDesignators#normalize(LispVal, java.util.function.Predicate)}): a
	 * quoted name this backend resolves becomes {@code (function name)}, one no
	 * definition has stays the symbol the dispatcher looks up when the call runs.
	 * @param fnForm the expression in function-designator position
	 * @param ctx the method context
	 * @return the expression to compile
	 */
	static LispVal designator(LispVal fnForm, JvmLispCompiler.Ctx ctx) {
		return FunctionDesignators.normalize(fnForm, name -> !undefined(name, ctx));
	}

	private static LispCons carCdrLambda(String name) {
		LispSymbol param = new LispSymbol("x");
		LispVal call = new LispCons(new LispSymbol(name), new LispCons(param, LispNil.INSTANCE));
		LispVal params = new LispCons(param, LispNil.INSTANCE);
		return new LispCons(new LispSymbol(LispNames.LAMBDA),
				new LispCons(params, new LispCons(call, LispNil.INSTANCE)));
	}

	private static FieldRefEntry fenvField(JvmLispCompiler.Ctx ctx, String className) {
		return ctx.cp.fieldRef(ctx.cp.classEntry(className), "_fenv", "Ljava/lang/Object;");
	}

	private static MethodRefEntry envLookupRef(JvmLispCompiler.Ctx ctx, String className) {
		return ctx.cp.methodRef(ctx.cp.classEntry(className), "_envLookup",
				"(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
	}

	private static MethodRefEntry lookupRef(JvmLispCompiler.Ctx ctx, String className) {
		return ctx.cp.methodRef(ctx.cp.classEntry(className), "_lookup", "(Ljava/lang/Object;)[Ljava/lang/Object;");
	}

	/**
	 * Emits {@code throw new RuntimeException("The function " + name + " is undefined")}
	 * for the symbol in {@code nameSlot} -- the same late-binding failure the
	 * {@code _invoke_N} dispatchers raise for a symbol no registry row answers
	 * ({@code JvmRuntimeBuilder.buildNotFnBody}), whose text the landing pad recovers the
	 * class and the name from ({@code JvmHandlerCaseCompiler}).
	 * @param nameSlot the local holding the symbol, never null
	 * @param ctx the method context
	 */
	static void emitUndefinedFunctionThrow(int nameSlot, JvmLispCompiler.Ctx ctx) {
		ClassEntry runtimeEx = ctx.cp.classEntry("java/lang/RuntimeException");
		MethodRefEntry exCtor = ctx.cp.methodRef(runtimeEx, "<init>", "(Ljava/lang/String;)V");
		MethodRefEntry concat = ctx.cp.methodRef(ctx.stringClass, "concat", "(Ljava/lang/String;)Ljava/lang/String;");
		ctx.body.new_(runtimeEx).dup();
		JvmEmitHelper.compileStringLiteral(ClosRegistry.UNDEFINED_FUNCTION_MESSAGE_PREFIX, ctx);
		ctx.body.aload(nameSlot).checkcast(ctx.stringClass).invokevirtual(concat);
		JvmEmitHelper.compileStringLiteral(ClosRegistry.UNDEFINED_FUNCTION_MESSAGE_SUFFIX, ctx);
		ctx.body.invokevirtual(concat).invokespecial(exCtor).athrow();
	}

	/**
	 * {@link #emitUndefinedFunctionThrow(int, JvmLispCompiler.Ctx)} for a name known at
	 * compile time: the call-time stub of a direct call of a name with no definition. Raw
	 * like every other site, rather than an {@code (error "...")}, so restart mode --
	 * which builds a string datum's {@code simple-error} at its signal point -- still
	 * classifies it at the landing pad.
	 * @param name the undefined function's name
	 * @param ctx the method context
	 */
	static void emitUndefinedFunctionThrow(String name, JvmLispCompiler.Ctx ctx) {
		ClassEntry runtimeEx = ctx.cp.classEntry("java/lang/RuntimeException");
		MethodRefEntry exCtor = ctx.cp.methodRef(runtimeEx, "<init>", "(Ljava/lang/String;)V");
		ctx.body.new_(runtimeEx).dup();
		JvmEmitHelper.compileStringLiteral(ClosRegistry.UNDEFINED_FUNCTION_MESSAGE_PREFIX
				+ ClosRegistry.functionNameForReport(name) + ClosRegistry.UNDEFINED_FUNCTION_MESSAGE_SUFFIX, ctx);
		ctx.body.invokespecial(exCtor).athrow();
	}

}
