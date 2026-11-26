package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;

/**
 * Compiles the {@code cdr} built-in function.
 */
final class WasmCdrCompiler {

	private WasmCdrCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		// (cdr nil) => nil: the shared nil-passing shape (WasmEmitHelper).
		WasmEmitHelper.compileConsField(args.get(1), 1, ctx);
	}

}
