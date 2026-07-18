package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;

/**
 * Compiles the string equality predicates {@code string=} (case-sensitive) and
 * {@code string-equal} (case-insensitive). The runtime helper returns the symbol
 * {@code t} or nil.
 */
final class WasmStringEqCompiler {

	private WasmStringEqCompiler() {
	}

	static void compileEq(LispCons cons, WasmLispCompiler.Ctx ctx) {
		compile(cons, ctx, WasmLispCompiler.FUNC_STRING_EQ);
	}

	static void compileEqual(LispCons cons, WasmLispCompiler.Ctx ctx) {
		compile(cons, ctx, WasmLispCompiler.FUNC_STRING_EQUAL);
	}

	private static void compile(LispCons cons, WasmLispCompiler.Ctx ctx, int func) {
		List<LispVal> args = cons.toList();
		// Mutable character vectors normalize to strings first (both operands).
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		WasmEmitHelper.emitCharvecToStrCall(ctx);
		WasmExprCompiler.compileExpr(args.get(2), ctx);
		WasmEmitHelper.emitCharvecToStrCall(ctx);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeSignedLeb128(func);
	}

}
