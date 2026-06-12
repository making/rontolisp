package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;

/**
 * Compiles the {@code write-line} built-in. The string and the optional stream (nil =
 * stdout) are passed to the {@code _write_line} stream runtime, which returns the string.
 */
final class WasmWriteLineCompiler {

	private WasmWriteLineCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> parts = cons.toList();
		if (parts.size() != 2 && parts.size() != 3) {
			throw new UnsupportedOperationException("write-line expects 1 or 2 arguments, got " + (parts.size() - 1));
		}
		WasmExprCompiler.compileExpr(parts.get(1), ctx);
		if (parts.size() == 3) {
			WasmExprCompiler.compileExpr(parts.get(2), ctx);
		}
		else {
			ctx.writer.write(Instruction.REF_NULL);
			ctx.writer.writeHeapType(Type.EQ.code());
		}
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.FUNC_WRITE_LINE);
	}

}
