package am.ik.rontolisp.codegen.wasm;

import java.util.ArrayList;
import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.SourceProvenance;
import am.ik.rontolisp.compiler.CompileWarnings;
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
		compileDirectCall(name, cons, ctx);
	}

	/**
	 * Compiles the {@code funcall} built-in.
	 */
	static void compileFuncall(LispCons cons, WasmLispCompiler.Ctx ctx) {
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
			direct.emitCall(ctx, args);
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
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeUnsignedLeb128(dispatchFuncIdx);
	}

	private static void compileDirectCall(String name, LispCons cons, WasmLispCompiler.Ctx ctx) {
		WasmLispCompiler.WasmFunctionInfo fi = ctx.functions.get(name);
		if (fi != null) {
			List<LispVal> args = cons.toList();
			int supplied = args.size() - 1;
			int required = fi.variadic() ? fi.paramCount() - 1 : fi.paramCount();
			if (supplied < required || (!fi.variadic() && supplied > required)) {
				throw new UnsupportedOperationException(name + " expects " + (fi.variadic() ? "at least " : "")
						+ required + " argument" + (required == 1 ? "" : "s") + ", got " + supplied);
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
					ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
					ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
					ctx.writer.write(Instruction.SET_LOCAL);
					ctx.writer.writeUnsignedLeb128(restSlot);
				}
				ctx.writer.write(Instruction.GET_LOCAL);
				ctx.writer.writeUnsignedLeb128(restSlot);
			}
			ctx.writer.write(Instruction.CALL);
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
