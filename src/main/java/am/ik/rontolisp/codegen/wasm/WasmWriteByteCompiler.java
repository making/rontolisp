package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;

/**
 * Compiles the {@code write-byte} built-in: {@code (write-byte byte stream)}. The byte
 * and the stream are passed to the {@code _write_byte} stream runtime, which writes one
 * raw byte via {@code fd_write} and returns the byte.
 */
final class WasmWriteByteCompiler {

	private WasmWriteByteCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> parts = cons.toList();
		if (parts.size() != 3) {
			throw new UnsupportedOperationException("write-byte expects 2 arguments, got " + (parts.size() - 1));
		}
		WasmExprCompiler.compileExpr(parts.get(1), ctx);
		WasmExprCompiler.compileExpr(parts.get(2), ctx);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.FUNC_WRITE_BYTE);
	}

}
