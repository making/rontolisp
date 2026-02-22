package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;

/**
 * Compiles the {@code append} built-in function.
 */
final class WasmAppendCompiler {

	private WasmAppendCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		int argCount = args.size() - 1; // exclude 'append' itself
		if (argCount == 0) {
			ctx.writer.write(Instruction.REF_NULL);
			ctx.writer.writeHeapType(Type.EQ.code());
			return;
		}
		if (argCount == 1) {
			WasmExprCompiler.compileExpr(args.get(1), ctx);
			return;
		}
		// Compile all arguments
		for (int i = 1; i <= argCount; i++) {
			WasmExprCompiler.compileExpr(args.get(i), ctx);
		}
		// Right-fold: call _append N-1 times from right to left
		for (int i = 0; i < argCount - 1; i++) {
			ctx.writer.write(Instruction.CALL);
			ctx.writer.writeSignedLeb128(WasmLispCompiler.FUNC_APPEND);
		}
	}

}
