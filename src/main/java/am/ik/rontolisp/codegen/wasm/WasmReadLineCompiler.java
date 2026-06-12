package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;

/**
 * Compiles the {@code read-line} built-in function. Without an argument it reads from
 * stdin (fd 0); with a stream argument the stream's i31-boxed file descriptor is unboxed
 * and read from instead.
 */
final class WasmReadLineCompiler {

	private WasmReadLineCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> parts = cons.toList();
		if (parts.size() == 1) {
			ctx.writer.write(Instruction.I32_CONST);
			ctx.writer.writeSignedLeb128(0); // fd = 0 (stdin)
		}
		else if (parts.size() == 2) {
			WasmExprCompiler.compileExpr(parts.get(1), ctx);
			WasmEmitHelper.castI31GetS(ctx);
		}
		else {
			throw new UnsupportedOperationException("read-line expects 0 or 1 arguments, got " + (parts.size() - 1));
		}
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.FUNC_READ_LINE);
	}

}
