package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;

/**
 * Compiles the {@code rontolisp:promisep} predicate: a single {@code ref.test} against
 * the {@code TYPE_PROMISE} struct (the runtime representation of both a
 * {@code rontolisp:fetch} root and a {@code rontolisp:then} chain).
 */
final class WasmPromisepCompiler {

	private WasmPromisepCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		if (args.size() != 2) {
			throw new UnsupportedOperationException("promisep expects 1 argument, got " + (args.size() - 1));
		}
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_PROMISE);
		WasmEmitHelper.emitBoolFromI32(ctx);
	}

}
