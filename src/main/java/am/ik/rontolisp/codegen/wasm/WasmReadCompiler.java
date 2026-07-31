package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.macro.LispMacroExpander;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;

/**
 * Compiles the {@code read} built-in. Invokes the {@code _read} runtime helper, passing
 * fd 0 (stdin) for {@code (read)} or the stream's WASI file descriptor for
 * {@code (read stream)}. It parses one S-expression into the runtime value
 * representation. The full CL tail
 * ({@code (read stream eof-error-p eof-value recursive-p)}) is rewritten first through
 * {@link LispMacroExpander#expandReadCompat}.
 */
final class WasmReadCompiler {

	private WasmReadCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		LispVal rewritten = LispMacroExpander.expandReadCompat(cons);
		if (rewritten != null) {
			WasmExprCompiler.compileExpr(rewritten, ctx);
			return;
		}
		List<LispVal> parts = cons.toList();
		if (parts.size() > 2) {
			throw new UnsupportedOperationException("read expects 0 or 1 arguments, got " + (parts.size() - 1));
		}
		// The source designator: an omitted argument and an explicit nil both mean the
		// current *standard-input* (WasmEmitHelper.inputStreamArg).
		LispVal stream = WasmEmitHelper.inputStreamArg(ctx, parts.size() == 2 ? parts.get(1) : null);
		if (stream == null) {
			ctx.writer.write(Instruction.I32_CONST);
			ctx.writer.writeSignedLeb128(0);
		}
		else {
			// The stream handle is the WASI fd, boxed as an i31 integer; unbox it (a
			// designator resolves to fd 0).
			WasmExprCompiler.compileExpr(stream, ctx);
			WasmEmitHelper.streamFdOrStdin(ctx);
		}
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.FUNC_READ);
	}

}
