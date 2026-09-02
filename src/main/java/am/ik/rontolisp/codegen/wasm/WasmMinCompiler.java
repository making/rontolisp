package am.ik.rontolisp.codegen.wasm;

import am.ik.rontolisp.LispCons;

/**
 * Compiles the {@code min} built-in function.
 */
final class WasmMinCompiler {

	private WasmMinCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		WasmMinMaxCompiler.compile(cons, ctx, true);
	}

}
