package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;

/**
 * Compiles the {@code %read-sequence-chars} primitive the {@code read-sequence} expansion
 * calls after the packed one: a call to the {@code _read_seq_chars} runtime helper
 * ({@code .kb/character-sequence-io.md}), which answers the fill position for a character
 * buffer off a WASI fd and a null reference -- "declined" -- for anything else, sending
 * the expansion down its per-character loop exactly as before the primitive existed.
 */
final class WasmSequenceCharsCompiler {

	private WasmSequenceCharsCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> parts = cons.toList();
		if (parts.size() != 5) {
			throw new UnsupportedOperationException(
					parts.get(0).print() + " expects (seq stream start end), got " + (parts.size() - 1) + " arguments");
		}
		// The stream designator, like read-byte: an explicit nil means the current
		// *standard-input*, whose default the helper reads as fd 0.
		LispVal stream = WasmEmitHelper.inputStreamArg(ctx, parts.get(2));
		WasmExprCompiler.compileExpr(parts.get(1), ctx);
		WasmExprCompiler.compileExpr(stream != null ? stream : parts.get(2), ctx);
		WasmExprCompiler.compileExpr(parts.get(3), ctx);
		WasmExprCompiler.compileExpr(parts.get(4), ctx);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_READ_SEQ_CHARS);
	}

}
