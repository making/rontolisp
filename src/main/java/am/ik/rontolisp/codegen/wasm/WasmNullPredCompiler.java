package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;

/**
 * Compiles the {@code null} / {@code not} predicate in VALUE position: the argument as a
 * test answering its complement ({@link WasmConditionCompiler}, so {@code (not (consp
 * x))} is one {@code ref.test} and one box rather than two boxes), then the box.
 */
final class WasmNullPredCompiler {

	private WasmNullPredCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		WasmConditionCompiler.compile(args.get(1), ctx, true);
		WasmEmitHelper.emitBoolFromI32(ctx);
	}

}
