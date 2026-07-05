package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;

/**
 * Compiles the {@code functionp} predicate: a function value is a {@code TYPE_CLOSURE}
 * struct (funcId + capture environment), and nothing else uses that type.
 */
final class WasmFunctionpCompiler {

	private WasmFunctionpCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CLOSURE);
		WasmEmitHelper.emitBoolFromI32(ctx);
	}

}
