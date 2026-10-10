package am.ik.rontolisp.codegen.jvm;

import java.lang.classfile.constantpool.MethodRefEntry;
import java.lang.classfile.constantpool.Utf8Entry;
import java.util.ArrayList;
import java.util.List;

import am.ik.rontolisp.ClosRegistry;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.compiler.CompileWarnings;
import am.ik.rontolisp.compiler.DefinedCallArity;
import am.ik.rontolisp.macro.LispMacroExpander;
import am.ik.rontolisp.LispVal;

/**
 * Compiles function calls: direct calls, indirect calls, general indirect calls, and
 * {@code funcall}.
 */
final class JvmFunctionCallCompiler {

	private JvmFunctionCallCompiler() {
	}

	/**
	 * Compiles the default case in dispatch. Under the Lisp-2 model a symbol in call
	 * position resolves in the function namespace only, so variable bindings never shadow
	 * it: this is always a direct call against the function registry.
	 */
	static void compileDefault(String name, LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		compileDirectCall(name, cons, ctx, className);
	}

	/**
	 * Compiles the {@code funcall} built-in.
	 */
	static void compileFuncall(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		if (JvmSelfTailCall.tryFuncall(cons, ctx, className)) {
			// A labels function's (or a defun's #'name) own tail call: a jump.
			return;
		}
		List<LispVal> parts = cons.toList();
		int arity = parts.size() - 2;
		if (ctx.tailBounce && ctx.tailMark == cons
				&& JvmDesignatorCall.directTarget(parts.get(1), arity, ctx) == null) {
			// The method's true tail through a value: a call while the stack holds few
			// such frames, else a bounce the trampoline loop above drives in its own
			// frame (JvmTailBounce). A literal designator names its target, so that call
			// stays the direct one below.
			JvmTailBounce.emitValueTail(JvmFunctionFormCompiler.designator(parts.get(1), ctx), parts, 2, ctx,
					className);
			return;
		}
		// A literal designator is called directly, anything else goes through the arity
		// dispatcher.
		JvmDesignatorCall call = JvmDesignatorCall.prepare(parts.get(1), arity, ctx, className);
		List<Runnable> args = new ArrayList<>();
		for (int i = 2; i < parts.size(); i++) {
			LispVal arg = parts.get(i);
			args.add(() -> JvmExprCompiler.compileExpr(arg, ctx, className));
		}
		call.emitCall(ctx, className, args, cons);
	}

	/**
	 * Compiles a general indirect call where the head is an expression (not a symbol).
	 */
	static void compileGeneralIndirect(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		int arity = args.size() - 1;
		if (ctx.tailBounce && ctx.tailMark == cons) {
			// The method's true tail through a value (JvmTailBounce).
			JvmTailBounce.emitValueTail(args.get(0), args, 1, ctx, className);
			return;
		}
		ctx.indirectCallArities.add(arity);
		JvmExprCompiler.compileExpr(args.get(0), ctx, className);
		for (int i = 1; i < args.size(); i++) {
			JvmExprCompiler.compileExpr(args.get(i), ctx, className);
		}
		emitDispatchCall(arity, ctx, className);
	}

	private static void compileDirectCall(String name, LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		JvmLispCompiler.FunctionInfo fi = ctx.functions.get(name);
		if (fi != null) {
			List<LispVal> args = cons.toList();
			// A count the lambda list rules out is the interpreter's program-error when
			// the call RUNS, its arguments evaluated first, with a compile-time warning
			// (compiler/DefinedCallArity): the call may sit in a branch never taken or
			// under a program-error handler.
			LispVal wrongCount = DefinedCallArity.wrongCountSignal(cons, name, fi.required(), fi.variadic(),
					ctx.arityOperators.declared(name));
			if (wrongCount != null) {
				JvmExprCompiler.compileExpr(wrongCount, ctx, className);
				return;
			}
			if (JvmSelfTailCall.tryDirect(fi, cons, ctx, className)) {
				// The defun's own tail call: a jump back to its first instruction.
				return;
			}
			// The arguments a parameter takes go straight onto the stack, the optionals
			// not passed are the UNSUPPLIED marker, and only a surplus past the physical
			// optionals is linked into the rest list (JvmPhysicalArgs).
			List<Runnable> emitters = new ArrayList<>();
			for (int i = 1; i < args.size(); i++) {
				LispVal arg = args.get(i);
				emitters.add(() -> JvmExprCompiler.compileExpr(arg, ctx, className));
			}
			JvmPhysicalArgs.emit(ctx, className, fi, emitters);
			// The callee may answer a bounce: driven here, or -- this method's tail --
			// handed on to callers that drive it (JvmTailBounce).
			JvmTailBounce.emitDirectCall(fi, cons, ctx, className);
		}
		else if (JvmFunctionFormCompiler.nestedDefun(name, ctx)) {
			// A defun nested inside a top-level let or a function body compiles to
			// (setq name (lambda ...)) and the assigned name is a global variable
			// holding the closure: dispatch the call through #'name, which reads it
			// (the undefined-function naming it before the definition ran, ahead of
			// the arguments). BEFORE the dynamic fallback below, which resolves the
			// runtime FUNCTION namespace -- a namespace this definition never enters,
			// so --dynamic answered nil.
			JvmExprCompiler.compileExpr(LispMacroExpander.expandCallThroughFunctionValue(cons), ctx, className);
		}
		else if (ctx.dynamic) {
			JvmDynamicCallCompiler.compileCall(name, cons, ctx, className);
		}
		else {
			LispVal uiopStub = LispMacroExpander.expandUiopStubCall(cons);
			if (uiopStub != null) {
				JvmExprCompiler.compileExpr(uiopStub, ctx, className);
				return;
			}
			if (ctx.globals.contains(name)) {
				// A top-level (setq name (lambda ...)) the same way.
				JvmExprCompiler.compileExpr(LispMacroExpander.expandCallThroughVariable(cons), ctx, className);
				return;
			}
			if (ctx.bindsRuntimeFunctionNames) {
				// A program that can bind the name at run time (eval's defun, load, a
				// write through a computed name): the call applies what _fenv holds
				// when it runs, and a miss is the undefined-function below.
				CompileWarnings.warn(cons, "the function " + ClosRegistry.functionNameForReport(name)
						+ " is undefined; looked up when the call runs");
				JvmExprCompiler.compileExpr(LispMacroExpander.runtimeFunctionNamespaceCall(name, cons), ctx, className);
				return;
			}
			// An undefined function: keep the interpreter's late binding -- signal
			// when the call is EXECUTED, so a library whose error path references a
			// function rontolisp does not provide stays compilable.
			CompileWarnings.warn(cons, "the function " + ClosRegistry.functionNameForReport(name)
					+ " is undefined; compiled as a call-time error");
			JvmFunctionFormCompiler.emitUndefinedFunctionThrow(name, ctx);
		}
	}

	static void emitDispatchCall(int arity, JvmLispCompiler.Ctx ctx, String className) {
		Utf8Entry nameUtf8 = ctx.cp.utf8Entry(JvmRuntimeBuilder.dispatcherName(arity, false));
		Utf8Entry descUtf8 = ctx.cp.utf8Entry(JvmRuntimeBuilder.dispatcherDesc(arity, false));
		MethodRefEntry methodref = ctx.cp.methodRef(ctx.cp.classEntry(className), nameUtf8, descUtf8);
		// An ordinary call: the callee's value tails count from 0 (JvmTailBounce).
		ctx.body.iconst_0();
		ctx.body.invokestatic(methodref);
		// The dispatcher answers the target's result, which is a trampoline bounce when
		// the target's own tail was through a value: the value the caller sees is the
		// loop's (JvmTailBounce).
		JvmTailBounce.emitUnwrap(ctx, className);
	}

}
