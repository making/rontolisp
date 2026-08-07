package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;

/**
 * Compiles the {@code rontolisp:futurep} predicate: a {@code ref.test} against
 * {@code TYPE_P1_FUTURE} (the internal degenerate-future struct a Preview-1
 * {@code %async-run} produces), plus {@code TYPE_FUTURE} under the asyncMode state
 * machines.
 */
final class WasmFuturepCompiler {

	private WasmFuturepCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		if (args.size() != 2) {
			throw new UnsupportedOperationException("futurep expects 1 argument, got " + (args.size() - 1));
		}
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		if (ctx.futureTypeIndex >= 0) {
			// asyncMode: a future is a first-class TYPE_FUTURE (the state machines) OR
			// the degenerate TYPE_P1_FUTURE a non-async-mode %async-run produced.
			int tmp = ctx.allocTemp();
			ctx.writer.write(Instruction.TEE_LOCAL);
			ctx.writer.writeUnsignedLeb128(tmp);
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
			ctx.writer.writeHeapType(WasmLispCompiler.TYPE_P1_FUTURE);
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeUnsignedLeb128(tmp);
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
			ctx.writer.writeHeapType(ctx.futureTypeIndex);
			ctx.writer.write(Instruction.I32_OR);
			WasmEmitHelper.emitBoolFromI32(ctx);
			return;
		}
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_P1_FUTURE);
		WasmEmitHelper.emitBoolFromI32(ctx);
	}

}
