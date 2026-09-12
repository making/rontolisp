package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;

/**
 * Compiles the {@code car} built-in function.
 */
final class WasmCarCompiler {

	private WasmCarCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		// (car nil) => nil: the shared nil-passing shape (WasmEmitHelper).
		WasmEmitHelper.compileConsField(args.get(1), 0, ctx);
	}

}
