package am.ik.rontolisp.codegen.wasm;

import am.ik.rontolisp.LispCons;

/**
 * Compiles the {@code max} built-in function.
 */
final class WasmMaxCompiler {

	private WasmMaxCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		WasmMinMaxCompiler.compile(cons, ctx, false);
	}

}
