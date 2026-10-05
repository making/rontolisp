package am.ik.rontolisp.codegen.jvm;

import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.FieldRefEntry;
import java.lang.classfile.constantpool.MethodRefEntry;
import java.util.List;

import am.ik.jvm.MethodCode;
import am.ik.rontolisp.LambdaLists;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.macro.LispMacroExpander;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;

/**
 * Compiles the {@code (function name)} special form ({@code #'name} reader syntax) and
 * {@code (symbol-function 'name)}. Under the Lisp-2 model these are the only ways to
 * obtain a function as a first-class value: a named function resolves against the
 * compile-time function registry (user defuns and built-in wrappers) and compiles to a
 * closure {@code Object[]{Integer funcId}}; {@code (function (lambda ...))} compiles the
 * lambda value directly. In dynamic mode an unresolved name defers to the runtime via
 * {@code _eval('(function name), null)}.
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
		else if (ctx.nestedDefunNames.contains(name) && ctx.globals.contains(name)) {
			// A defun nested inside a top-level let or a function body compiles to
			// (setq name (lambda ...)): the global variable already HOLDS the function
			// value. Before the dynamic fallback for the same reason the call site
			// checks it first (JvmFunctionCallCompiler).
			JvmExprCompiler.compileExpr(new am.ik.rontolisp.LispSymbol(name), ctx, className);
		}
		else if (ctx.dynamic) {
			JvmDynamicCallCompiler.compileFunctionRef(name, ctx, className);
		}
		else if (ctx.globals.contains(name)) {
			// A top-level (setq name (lambda ...)) the same way.
			JvmExprCompiler.compileExpr(new am.ik.rontolisp.LispSymbol(name), ctx, className);
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

	// throw new RuntimeException("The function " + name + " is undefined") -- the
	// same late-binding failure the _invoke_N dispatchers raise for a symbol no
	// registry row answers.
	private static void emitUndefinedFunctionThrow(int nameSlot, JvmLispCompiler.Ctx ctx) {
		ClassEntry runtimeEx = ctx.cp.classEntry("java/lang/RuntimeException");
		MethodRefEntry exCtor = ctx.cp.methodRef(runtimeEx, "<init>", "(Ljava/lang/String;)V");
		MethodRefEntry concat = ctx.cp.methodRef(ctx.stringClass, "concat", "(Ljava/lang/String;)Ljava/lang/String;");
		ctx.body.new_(runtimeEx).dup();
		JvmEmitHelper.compileStringLiteral("The function ", ctx);
		ctx.body.aload(nameSlot).checkcast(ctx.stringClass).invokevirtual(concat);
		JvmEmitHelper.compileStringLiteral(" is undefined", ctx);
		ctx.body.invokevirtual(concat).invokespecial(exCtor).athrow();
	}

}
