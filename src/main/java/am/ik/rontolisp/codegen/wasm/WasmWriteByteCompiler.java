package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;

/**
 * Compiles the {@code write-byte} built-in: {@code (write-byte byte stream)}. The byte
 * and the stream are passed to the {@code _write_byte} stream runtime, which writes one
 * raw byte via {@code fd_write} and returns the byte -- to fd 1 for a non-handle
 * designator.
 */
final class WasmWriteByteCompiler {

	private WasmWriteByteCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> parts = cons.toList();
		if (parts.size() != 3) {
			throw new UnsupportedOperationException("write-byte expects 2 arguments, got " + (parts.size() - 1));
		}
		// The destination designator, like the print family: an explicit nil means the
		// current *standard-output*, whose default t the stream runtime writes fd 1 for.
		LispVal stream = WasmEmitHelper.streamArg(ctx, parts.get(2));
		WasmExprCompiler.compileExpr(parts.get(1), ctx);
		WasmExprCompiler.compileExpr(stream != null ? stream : parts.get(2), ctx);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_WRITE_BYTE);
	}

}
