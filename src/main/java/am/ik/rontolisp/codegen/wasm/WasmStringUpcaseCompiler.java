package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;

/**
 * Compiles {@code string-upcase} / {@code string-downcase}: the argument is rendered into
 * a new heap string with ASCII case conversion by the corresponding runtime helper.
 */
final class WasmStringUpcaseCompiler {

	private WasmStringUpcaseCompiler() {
	}

	static void compileUpcase(LispCons cons, WasmLispCompiler.Ctx ctx) {
		compile(cons, ctx, WasmLispCompiler.FUNC_STRING_UPCASE);
	}

	static void compileDowncase(LispCons cons, WasmLispCompiler.Ctx ctx) {
		compile(cons, ctx, WasmLispCompiler.FUNC_STRING_DOWNCASE);
	}

	private static void compile(LispCons cons, WasmLispCompiler.Ctx ctx, int func) {
		List<LispVal> args = cons.toList();
		// A mutable character vector normalizes to a string first.
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		WasmEmitHelper.emitCharvecToStrCall(ctx);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeSignedLeb128(func);
	}

}
