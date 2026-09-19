package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;

/**
 * Compiles the {@code file-position} built-in on the {@code --component} backend: the
 * one-argument query and the two-argument set. The stream argument is resolved down to
 * its raw handle and handed to the {@code _file_position} / {@code _file_position_set}
 * runtime pair, which talk to the adapter's tracked per-fd byte offset through the
 * injected {@code file_position_get} / {@code file_position_set} imports -- the Preview 1
 * backend has no {@code fd_seek} import (todo 876) and keeps the constant-nil answer.
 */
final class WasmFilePositionCompiler {

	private WasmFilePositionCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> parts = cons.toList();
		if (parts.size() == 2) {
			WasmExprCompiler
				.compileExpr(java.util.Objects.requireNonNull(WasmEmitHelper.streamDesignator(ctx, parts.get(1))), ctx);
			ctx.writer.write(Instruction.CALL);
			ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_FILE_POSITION);
		}
		else if (parts.size() == 3) {
			WasmExprCompiler
				.compileExpr(java.util.Objects.requireNonNull(WasmEmitHelper.streamDesignator(ctx, parts.get(1))), ctx);
			WasmExprCompiler.compileExpr(parts.get(2), ctx);
			ctx.writer.write(Instruction.CALL);
			ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_FILE_POSITION_SET);
		}
		else {
			throw new UnsupportedOperationException(
					"file-position expects 1 or 2 arguments, got " + (parts.size() - 1));
		}
	}

}
