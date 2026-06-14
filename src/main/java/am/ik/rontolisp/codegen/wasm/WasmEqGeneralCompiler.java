package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;

/**
 * Compiles the {@code eq} and {@code eql} built-in functions. Both use {@code ref.eq} for
 * identity comparison, falling back to string offset comparison for TYPE_STRING values
 * (since the StringTable deduplicates identical symbols/strings to the same offset). They
 * differ only on floats and ratios, which are {@code eql} (by value) but not {@code eq}.
 */
final class WasmEqGeneralCompiler {

	private WasmEqGeneralCompiler() {
	}

	/** Compiles {@code eq} (floats and ratios are never equal). */
	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		compileArgs(cons, ctx);
		WasmEmitHelper.emitEqComparison(ctx);
		WasmEmitHelper.emitBoolFromI32(ctx);
	}

	/** Compiles {@code eql} (floats and ratios compare by value). */
	static void compileEql(LispCons cons, WasmLispCompiler.Ctx ctx) {
		compileArgs(cons, ctx);
		WasmEmitHelper.emitEqlComparison(ctx);
		WasmEmitHelper.emitBoolFromI32(ctx);
	}

	private static void compileArgs(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		// Evaluate both args (push onto stack)
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		WasmExprCompiler.compileExpr(args.get(2), ctx);
	}

}
