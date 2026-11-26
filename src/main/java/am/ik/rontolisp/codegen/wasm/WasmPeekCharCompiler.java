package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispTrue;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;

/**
 * Compiles the internal {@code %peek-char} primitive:
 * {@code (%peek-char [stream [eof-error-p [eof-value]]])}. Same argument shape as
 * {@code read-char} -- the {@code peek-type} skipping forms of the public
 * {@code peek-char} are lowered onto this one by
 * {@code LispMacroExpander.expandPeekChar}, so only "the next character, left in place"
 * needs a runtime body ({@code _peek_char}).
 */
final class WasmPeekCharCompiler {

	private WasmPeekCharCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> parts = cons.toList();
		if (parts.size() > 4) {
			throw new UnsupportedOperationException("peek-char expects 0 to 3 arguments, got " + (parts.size() - 1));
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
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_PEEK_CHAR);
	}

}
