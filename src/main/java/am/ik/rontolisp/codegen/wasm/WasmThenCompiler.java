package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.FunctionDesignators;
import am.ik.wasm.Instruction;

/**
 * Compiles {@code rontolisp:then}: derives a new promise from a base value (usually a
 * promise) and a callback. The derived promise is a {@code TYPE_PROMISE} struct of kind 1
 * holding the base and the callback; {@code _promise_await}
 * ({@link WasmPromiseRuntimeBuilder}) resolves the base, applies the callback through the
 * arity-1 dispatch function at first await, and memoizes the result into the struct.
 * Compiles in every mode (Preview 1 included), like await.
 */
final class WasmThenCompiler {

	private WasmThenCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		if (args.size() != 3) {
			throw new UnsupportedOperationException("then expects 2 arguments, got " + (args.size() - 1));
		}
		// _promise_await applies the callback through dispatch_1, so its body must be
		// emitted whenever a chain can exist.
		ctx.indirectCallArities.add(1);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(1); // kind 1: then chain
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		WasmExprCompiler.compileExpr(FunctionDesignators.normalize(args.get(2)), ctx);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_PROMISE);
	}

}
