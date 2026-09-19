package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;

/**
 * Compiles the {@code eq} and {@code eql} built-in functions, which are one predicate
 * ({@code .kb/eq-numbers.md}): {@code ref.eq} inline, then {@code _eql_tail} for
 * characters, numbers by type and value, and symbols/strings by interned offset
 * ({@link WasmEmitHelper#emitEqlComparison}).
 */
final class WasmEqGeneralCompiler {

	private WasmEqGeneralCompiler() {
	}

	/**
	 * Compiles {@code eq} or {@code eql}.
	 * @param cons the call form
	 * @param ctx the compilation context
	 */
	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		WasmExprCompiler.compileExpr(args.get(2), ctx);
		WasmEmitHelper.emitEqlComparison(ctx);
		WasmEmitHelper.emitBoolFromI32(ctx);
	}

}
