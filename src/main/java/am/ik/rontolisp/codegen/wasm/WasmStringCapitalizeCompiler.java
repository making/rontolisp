package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;

/**
 * Compiles {@code string-capitalize}: the runtime helper capitalizes the first letter of
 * each ASCII-alphanumeric word and lowercases the rest.
 */
final class WasmStringCapitalizeCompiler {

	private WasmStringCapitalizeCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		// A mutable character vector normalizes to a string first.
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		WasmEmitHelper.emitCharvecToStrCall(ctx);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_STRING_CAPITALIZE);
	}

}
