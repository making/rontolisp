package am.ik.rontolisp.codegen.wasm;

import java.util.ArrayList;
import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.SourceProvenance;
import am.ik.rontolisp.compiler.CompileWarnings;
import am.ik.rontolisp.compiler.DefinedCallArity;
import am.ik.rontolisp.macro.LispMacroExpander;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.FunctionDesignators;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;

/**
 * Compiles function calls: direct calls, indirect calls, and {@code funcall}.
 */
final class WasmFunctionCallCompiler {

	private WasmFunctionCallCompiler() {
	}

	/**
	 * Compiles the default case in dispatch. Under the Lisp-2 model a symbol in call
	 * position resolves in the function namespace only, so variable bindings never shadow
	 * it: this is always a direct call against the function registry.
	 */
	static void compileDefault(String name, LispCons cons, WasmLispCompiler.Ctx ctx) {
		compileDirectCall(name, cons, ctx, false);
	}

	/**
	 * As {@link #compileDefault(String, LispCons, WasmLispCompiler.Ctx)}; with
	 * {@code tail}, a call that resolves to a compiled function is a {@code return_call}
	 * ({@code Ctx.tailPosition}).
	 */
	static void compileDefault(String name, LispCons cons, WasmLispCompiler.Ctx ctx, boolean tail) {
		compileDirectCall(name, cons, ctx, tail);
	}

	/**
	 * Compiles the {@code funcall} built-in.
	 */
	static void compileFuncall(LispCons cons, WasmLispCompiler.Ctx ctx) {
		compileFuncall(cons, ctx, false);
	}

	/**
	 * As {@link #compileFuncall(LispCons, WasmLispCompiler.Ctx)}; with {@code tail}, the
	 * dispatch (or the direct call a literal designator gets) is a {@code return_call}:
	 * every function value is reached in constant stack ({@code Ctx.tailPosition}).
	 */
	static void compileFuncall(LispCons cons, WasmLispCompiler.Ctx ctx, boolean tail) {
		List<LispVal> parts = cons.toList();
		int arity = parts.size() - 2; // (funcall f arg0 ...) -> arity = num_args
		List<Runnable> args = new ArrayList<>();
		for (int i = 2; i < parts.size(); i++) {
			LispVal arg = parts.get(i);
			args.add(() -> WasmExprCompiler.compileExpr(arg, ctx));
		}
		// A literal designator is called directly, whatever the arity: the ceiling below
		// is the DISPATCHERS' and no dispatcher is involved.
		WasmDesignatorCall direct = WasmDesignatorCall.direct(parts.get(1), arity, ctx);
		if (direct != null) {
			direct.emitCall(ctx, args, tail);
			return;
		}
		if (arity > ctx.callArityCeiling) {
			// The dispatch functions occupy a FIXED index range (FUNC_DISPATCH_BASE +
			// 0..MAX_CALLABLE_ARITY) plus the extra tier this module sized from its own
			// widest funcall; an over-ceiling index would silently call the NEXT runtime
			// helper (cl-postgres' 9-argument make-ssl-stream funcall produced an invalid
			// module this way). A source-level site past the ceiling was already
			// rewritten
			// into apply by WasmArityBundler, so what reaches here is one a macro
			// synthesized during Pass 2, after that scan. The site stays compilable as a
			// call-time signal, so it never blocks a build.
			WasmExprCompiler.compileExpr(LispMacroExpander.overArityFuncallStub(arity), ctx);
			return;
		}
		ctx.indirectCallArities.add(arity);
		// See Ctx.runtimeDesignatorDispatch: a designator the compiler cannot read may
		// be a SYMBOL at run time, which only the name registry resolves. This is the
		// seam the sequence operators arrive at -- (every f l) and its family expand
		// into a loop over (funcall #pred elem) during Pass 2.
		if (!ctx.injectedRuntimeBody && !LispMacroExpander.isStaticFunctionDesignator(parts.get(1))) {
			ctx.runtimeDesignatorDispatch[0] = true;
		}
		int dispatchFuncIdx = WasmLispCompiler.dispatchFuncIndex(arity, ctx.extraDispatchFuncBase);

		// Push funcval
		WasmExprCompiler.compileExpr(FunctionDesignators.normalize(parts.get(1)), ctx);
		// Push args
		args.forEach(Runnable::run);
		// Call dispatch
		WasmUncaughtLocations.emitValueCall(ctx, tail, arity + 1, dispatchFuncIdx);
	}

	private static void compileDirectCall(String name, LispCons cons, WasmLispCompiler.Ctx ctx, boolean tail) {
		// A host import whose every :string argument is a literal reaches the host
		// without the wrapper, and without the GC byte array the wrapper would have
		// unbuilt one instruction later (WasmImportCompiler.compileLiteralImportCall).
		// The wrapper stays where anything else needs it -- #'name, funcall, dispatch,
		// a runtime string argument -- and the tree shaker drops it when nothing does.
		if (WasmImportCompiler.compileLiteralImportCall(name, cons, ctx)) {
			return;
		}
		WasmLispCompiler.WasmFunctionInfo fi = ctx.functions.get(name);
		if (fi != null) {
			WasmEmitHelper.requireNoCharvecHelper(ctx, name);
			List<LispVal> args = cons.toList();
			int required = fi.variadic() ? fi.paramCount() - 1 : fi.paramCount();
			// A count the lambda list rules out is the interpreter's program-error when
			// the call RUNS, its arguments evaluated first, with a compile-time warning
			// (compiler/DefinedCallArity): the call may sit in a branch never taken or
			// under a program-error handler.
			LispVal wrongCount = DefinedCallArity.wrongCountSignal(cons, name, required, fi.variadic());
			if (wrongCount != null) {
				WasmExprCompiler.compileExpr(wrongCount, ctx);
				return;
			}
			// Push null env (defun functions ignore it)
			ctx.writer.write(Instruction.REF_NULL);
			ctx.writer.writeHeapType(Type.EQ.code());
			for (int i = 1; i <= required; i++) {
				WasmExprCompiler.compileExpr(args.get(i), ctx);
			}
			if (fi.variadic()) {
				// Evaluate the surplus arguments left to right into temps, then link
				// them into a cons list passed as the trailing rest parameter.
				List<Integer> extraSlots = new java.util.ArrayList<>();
				for (int i = required + 1; i < args.size(); i++) {
					WasmExprCompiler.compileExpr(args.get(i), ctx);
					int s = ctx.allocTemp();
					ctx.writer.write(Instruction.SET_LOCAL);
					ctx.writer.writeUnsignedLeb128(s);
					extraSlots.add(s);
				}
				int restSlot = ctx.allocTemp();
				ctx.writer.write(Instruction.REF_NULL);
				ctx.writer.writeHeapType(Type.EQ.code());
				ctx.writer.write(Instruction.SET_LOCAL);
				ctx.writer.writeUnsignedLeb128(restSlot);
				for (int k = extraSlots.size() - 1; k >= 0; k--) {
					ctx.writer.write(Instruction.GET_LOCAL);
					ctx.writer.writeUnsignedLeb128(extraSlots.get(k));
					ctx.writer.write(Instruction.GET_LOCAL);
					ctx.writer.writeUnsignedLeb128(restSlot);
					WasmEmitHelper.emitNewCons(ctx);
					ctx.writer.write(Instruction.SET_LOCAL);
					ctx.writer.writeUnsignedLeb128(restSlot);
				}
				ctx.writer.write(Instruction.GET_LOCAL);
				ctx.writer.writeUnsignedLeb128(restSlot);
			}
			// Every compiled Lisp function answers one (ref null eq), so a tail call
			// to any of them is a return_call from any of them.
			ctx.writer.write(WasmUncaughtLocations.tailCallOp(ctx, tail, name));
			ctx.writer.writeUnsignedLeb128(fi.funcIndex());
		}
		else if (ctx.nestedDefunNames.contains(name) && ctx.globalIndices.containsKey(name)) {
			// A defun nested inside a top-level let or a function body compiles to
			// (setq name (lambda ...)) and the assigned name is a global variable
			// holding the closure: dispatch the call through it. BEFORE the dynamic
			// fallback below, which resolves the runtime FUNCTION namespace -- a
			// namespace this definition never enters.
			WasmExprCompiler.compileExpr(LispMacroExpander.expandCallThroughVariable(cons), ctx);
		}
		else if (ctx.dynamic) {
			WasmDynamicCallCompiler.compileCall(name, cons, ctx);
		}
		else {
			LispVal uiopStub = LispMacroExpander.expandUiopStubCall(cons);
			if (uiopStub != null) {
				WasmExprCompiler.compileExpr(uiopStub, ctx);
				return;
			}
			if (ctx.globalIndices.containsKey(name)) {
				// A top-level (setq name (lambda ...)) the same way.
				WasmExprCompiler.compileExpr(LispMacroExpander.expandCallThroughVariable(cons), ctx);
				return;
			}
			// An undefined function: keep the interpreter's late binding -- signal
			// when the call is EXECUTED, so a library whose error path references a
			// function rontolisp does not provide stays compilable.
			CompileWarnings.warn(SourceProvenance.prefix(cons) + "warning: the function " + name
					+ " is undefined; compiled as a call-time error");
			WasmExprCompiler.compileExpr(LispMacroExpander.undefinedFunctionCallStub(name), ctx);
		}
	}

}
