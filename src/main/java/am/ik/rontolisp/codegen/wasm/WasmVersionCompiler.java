package am.ik.rontolisp.codegen.wasm;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.VersionInfo;

/**
 * Compiles {@code (rontolisp:version)} by emitting the constant version property list
 * through the existing quote machinery. The version values are compile-time constants, so
 * no new runtime function is added (the fixed WASM function indices stay stable).
 */
final class WasmVersionCompiler {

	private WasmVersionCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		LispVal quoteForm = new LispCons(new LispSymbol(LispNames.QUOTE),
				new LispCons(VersionInfo.plist(), LispNil.INSTANCE));
		WasmExprCompiler.compileExpr(quoteForm, ctx);
	}

}
