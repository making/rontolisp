package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;

/**
 * Compiles function calls: direct calls, indirect calls, and {@code funcall}.
 */
final class WasmFunctionCallCompiler {

	private WasmFunctionCallCompiler() {
	}

	/**
	 * Compiles the default case in dispatch: direct or indirect call based on symbol
	 * resolution.
	 */
	static void compileDefault(String name, LispCons cons, WasmLispCompiler.Ctx ctx) {
		if (ctx.locals.containsKey(name) || ctx.captures.containsKey(name)) {
			compileIndirectCall(cons, ctx);
		}
		else {
			compileDirectCall(name, cons, ctx);
		}
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
		WasmExprCompiler.compileExpr(parts.get(1), ctx);
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
			// Push null env (defun functions ignore it)
			ctx.writer.write(Instruction.REF_NULL);
			ctx.writer.writeHeapType(Type.EQ.code());
			for (int i = 1; i < args.size(); i++) {
				WasmExprCompiler.compileExpr(args.get(i), ctx);
			}
			ctx.writer.write(Instruction.CALL);
			ctx.writer.writeSignedLeb128(fi.funcIndex());
		}
		else {
			throw new UnsupportedOperationException("Cannot compile: " + name);
		}
	}

	private static void compileIndirectCall(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		LispSymbol headSym = (LispSymbol) cons.car();
		int arity = args.size() - 1;
		ctx.indirectCallArities.add(arity);
		int dispatchFuncIdx = WasmLispCompiler.FUNC_DISPATCH_BASE + arity;

		// Push funcval as first arg to dispatch
		WasmExprCompiler.compileSymbolRef(headSym, ctx);

		// Push remaining args
		for (int i = 1; i < args.size(); i++) {
			WasmExprCompiler.compileExpr(args.get(i), ctx);
		}
		// Call dispatch function
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeSignedLeb128(dispatchFuncIdx);
	}

}
