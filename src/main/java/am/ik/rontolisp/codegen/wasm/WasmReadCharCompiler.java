package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispTrue;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;

/**
 * Compiles the {@code read-char} built-in: {@code (read-char [stream [eof-error-p
 * [eof-value]]])}. The stream (default nil = standard input), eof-error-p (default
 * {@code t}) and eof-value (default {@code nil}) are passed to the {@code _read_char}
 * stream runtime, which reads one byte (WASM strings are byte-indexed, so a character
 * read is a byte read) and returns it as a character struct.
 */
final class WasmReadCharCompiler {

	private WasmReadCharCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> parts = cons.toList();
		if (parts.size() > 4) {
			throw new UnsupportedOperationException("read-char expects 0 to 3 arguments, got " + (parts.size() - 1));
		}
		// The source designator: an omitted argument and an explicit nil both mean the
		// current *standard-input* (WasmEmitHelper.inputStreamArg); the runtime helper
		// reads standard input for any non-handle value.
		LispVal stream = WasmEmitHelper.inputStreamArg(ctx, parts.size() > 1 ? parts.get(1) : null);
		WasmExprCompiler.compileExpr(stream != null ? stream : LispNil.INSTANCE, ctx);
		WasmExprCompiler.compileExpr(parts.size() > 2 ? parts.get(2) : LispTrue.INSTANCE, ctx);
		if (parts.size() > 3) {
			WasmExprCompiler.compileExpr(parts.get(3), ctx);
		}
		else {
			ctx.writer.write(Instruction.REF_NULL);
			ctx.writer.writeHeapType(Type.EQ.code());
		}
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_READ_CHAR);
	}

}
