package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispMacroExpander;
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
		ctx.indirectCallArities.add(arity);
		int dispatchFuncIdx = WasmLispCompiler.FUNC_DISPATCH_BASE + arity;

		// Push funcval
		WasmExprCompiler.compileExpr(FunctionDesignators.normalize(parts.get(1)), ctx);
		// Push args
		for (int i = 2; i < parts.size(); i++) {
			WasmExprCompiler.compileExpr(parts.get(i), ctx);
		}
		// Call dispatch
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeSignedLeb128(dispatchFuncIdx);
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
					ctx.writer.writeSignedLeb128(s);
					extraSlots.add(s);
				}
				int restSlot = ctx.allocTemp();
				ctx.writer.write(Instruction.REF_NULL);
				ctx.writer.writeHeapType(Type.EQ.code());
				ctx.writer.write(Instruction.SET_LOCAL);
				ctx.writer.writeSignedLeb128(restSlot);
				for (int k = extraSlots.size() - 1; k >= 0; k--) {
					ctx.writer.write(Instruction.GET_LOCAL);
					ctx.writer.writeSignedLeb128(extraSlots.get(k));
					ctx.writer.write(Instruction.GET_LOCAL);
					ctx.writer.writeSignedLeb128(restSlot);
					ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
					ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
					ctx.writer.write(Instruction.SET_LOCAL);
					ctx.writer.writeSignedLeb128(restSlot);
				}
				ctx.writer.write(Instruction.GET_LOCAL);
				ctx.writer.writeSignedLeb128(restSlot);
			}
			ctx.writer.write(Instruction.CALL);
			ctx.writer.writeSignedLeb128(fi.funcIndex());
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
				// A defun nested inside a top-level let compiles to (setq name
				// (lambda ...)) and the assigned name is a global variable holding the
				// closure: dispatch the call through it.
				WasmExprCompiler.compileExpr(LispMacroExpander.expandCallThroughVariable(cons), ctx);
				return;
			}
			throw new UnsupportedOperationException("Cannot compile: " + name);
		}
	}

}
