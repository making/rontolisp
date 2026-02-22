package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;

/**
 * Compiles the {@code eq} built-in function (general equality). Uses {@code ref.eq} for
 * identity comparison, falling back to string offset comparison for TYPE_STRING values
 * (since the StringTable deduplicates identical strings to the same offset).
 */
final class WasmEqGeneralCompiler {

	private WasmEqGeneralCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		// Evaluate both args (push onto stack)
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		WasmExprCompiler.compileExpr(args.get(2), ctx);
		// Compare and produce i32 result
		WasmEmitHelper.emitEqComparison(ctx);
		// Convert i32 to Lisp boolean
		WasmEmitHelper.emitBoolFromI32(ctx);
	}

}
