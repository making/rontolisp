package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;

/**
 * Compiles the {@code %read-sequence-packed} / {@code %write-sequence-packed} primitives
 * the {@code read-sequence} / {@code write-sequence} expansions call first
 * ({@code .kb/binary-sequence-io.md}): a call to the {@code _read_packed} /
 * {@code _write_packed} runtime helper ({@link WasmPackedIoRuntimeBuilder}), which
 * answers the fill position / the sequence, or {@code null} -- "declined" -- for a buffer
 * or stream it does not handle, in which case the expansion runs its element loop.
 */
final class WasmSequencePackedCompiler {

	private WasmSequencePackedCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> parts = cons.toList();
		boolean read = LispNames.READ_SEQUENCE_PACKED.equals(((LispSymbol) parts.get(0)).name());
		if (parts.size() != 5) {
			throw new UnsupportedOperationException(
					parts.get(0).print() + " expects (seq stream start end), got " + (parts.size() - 1) + " arguments");
		}
		// The stream designator, like read-byte / write-byte: an explicit nil means the
		// current *standard-input* / *standard-output*, whose default the runtime reads
		// as fd 0 / fd 1.
		LispVal stream = read ? WasmEmitHelper.inputStreamArg(ctx, parts.get(2))
				: WasmEmitHelper.streamArg(ctx, parts.get(2));
		WasmExprCompiler.compileExpr(parts.get(1), ctx);
		WasmExprCompiler.compileExpr(stream != null ? stream : parts.get(2), ctx);
		WasmExprCompiler.compileExpr(parts.get(3), ctx);
		WasmExprCompiler.compileExpr(parts.get(4), ctx);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeUnsignedLeb128(read ? WasmLispCompiler.FUNC_READ_PACKED : WasmLispCompiler.FUNC_WRITE_PACKED);
	}

}
