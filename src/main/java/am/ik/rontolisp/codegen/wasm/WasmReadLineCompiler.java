package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.macro.LispMacroExpander;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;

/**
 * Compiles the {@code read-line} built-in function. Without an argument it reads from
 * stdin (fd 0); with a stream argument the stream's i31-boxed file descriptor is unboxed
 * and read from instead. A CL 3-arg {@code (read-line stream eof-error-p eof-value)} is
 * lowered first through {@link LispMacroExpander#expandReadLineCompat} so the standard
 * "swallow EOF" idiom real libraries drive their per-line loops with works on WASM too.
 */
final class WasmReadLineCompiler {

	private WasmReadLineCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		LispVal rewritten = LispMacroExpander.expandReadLineCompat(cons);
		if (rewritten != null) {
			WasmExprCompiler.compileExpr(rewritten, ctx);
			return;
		}
		List<LispVal> parts = cons.toList();
		if (parts.size() > 2) {
			throw new UnsupportedOperationException("read-line expects 0 or 1 arguments, got " + (parts.size() - 1));
		}
		// The source, under CL's stream designator rule: an explicit stream, or -- for an
		// omitted argument AND for an explicit nil -- the current *standard-input*
		// (WasmEmitHelper.inputStreamArg). A program that never binds it keeps fd 0.
		LispVal stream = WasmEmitHelper.inputStreamArg(ctx, parts.size() == 2 ? parts.get(1) : null);
		if (stream == null) {
			ctx.writer.write(Instruction.I32_CONST);
			ctx.writer.writeSignedLeb128(0); // fd = 0 (stdin)
		}
		else {
			WasmExprCompiler.compileExpr(stream, ctx);
			WasmEmitHelper.streamFdOrStdin(ctx);
		}
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.FUNC_READ_LINE);
	}

}
