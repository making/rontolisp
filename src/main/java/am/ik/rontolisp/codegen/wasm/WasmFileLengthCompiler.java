package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;

/**
 * Compiles the {@code file-length} built-in. The stream argument is resolved down to its
 * raw handle (a stream value is unwrapped, a synonym stream followed) and handed to the
 * {@code _file_length} runtime, which stats the WASI file descriptor through
 * {@code fd_filestat_get}.
 */
final class WasmFileLengthCompiler {

	private WasmFileLengthCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> parts = cons.toList();
		if (parts.size() != 2) {
			throw new UnsupportedOperationException("file-length expects 1 argument, got " + (parts.size() - 1));
		}
		WasmExprCompiler
			.compileExpr(java.util.Objects.requireNonNull(WasmEmitHelper.streamDesignator(ctx, parts.get(1))), ctx);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_FILE_LENGTH);
	}

}
