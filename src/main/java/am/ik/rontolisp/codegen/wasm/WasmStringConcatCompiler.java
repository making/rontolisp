package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;

/**
 * Compiles the internal {@code %string-concat} built-in function: both arguments are
 * rendered back to back into a new heap string by the {@code _string_concat} runtime
 * function.
 */
final class WasmStringConcatCompiler {

	private WasmStringConcatCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		// Mutable character vectors normalize to strings first (both operands).
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		WasmEmitHelper.emitCharvecToStrCall(ctx);
		WasmExprCompiler.compileExpr(args.get(2), ctx);
		WasmEmitHelper.emitCharvecToStrCall(ctx);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.FUNC_STRING_CONCAT);
	}

}
