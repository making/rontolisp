package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;

/**
 * Compiles the {@code equal} built-in function (structural equality). Cons cells are
 * compared recursively by car and cdr; everything else (numbers, symbols, strings, nil)
 * falls back to {@code eql} semantics. Delegates to the recursive {@code _equal} runtime
 * function ({@link WasmLispCompiler#FUNC_EQUAL}), which returns i32.
 */
final class WasmEqualCompiler {

	private WasmEqualCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		WasmExprCompiler.compileExpr(args.get(2), ctx);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_EQUAL);
		WasmEmitHelper.emitBoolFromI32(ctx);
	}

}
