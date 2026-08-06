package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;

/**
 * Compiles the {@code write-line} built-in. The string and the optional stream (nil =
 * stdout) are passed to the {@code _write_line} stream runtime, which returns the string
 * -- unless a string LITERAL goes to standard output, which folds to static bytes
 * ({@link WasmLiteralPrint}).
 */
final class WasmWriteLineCompiler {

	private WasmWriteLineCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> parts = cons.toList();
		if (parts.size() != 2 && parts.size() != 3) {
			throw new UnsupportedOperationException("write-line expects 1 or 2 arguments, got " + (parts.size() - 1));
		}
		// The destination, under CL's stream designator rule: an explicit stream, or --
		// for an omitted argument AND for an explicit nil -- the current
		// *standard-output* (WasmEmitHelper.streamArg).
		LispVal stream = WasmEmitHelper.streamArg(ctx, parts.size() == 3 ? parts.get(2) : null);
		if (stream == null && parts.get(1) instanceof LispString literal) {
			// A string literal to standard output: the text and the newline are static
			// bytes, so the whole _write_line runtime stays shakeable (WasmLiteralPrint).
			int objSlot = ctx.allocTemp();
			WasmExprCompiler.compileExpr(literal, ctx);
			ctx.writer.write(Instruction.SET_LOCAL);
			ctx.writer.writeSignedLeb128(objSlot);
			WasmLiteralPrint.emitStaticWrite(literal.value(), literal, false, ctx);
			WasmLiteralPrint.emitNewline(ctx);
			// write-line returns its string argument.
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeSignedLeb128(objSlot);
			return;
		}
		WasmExprCompiler.compileExpr(parts.get(1), ctx);
		if (stream != null) {
			WasmExprCompiler.compileExpr(stream, ctx);
		}
		else {
			ctx.writer.write(Instruction.REF_NULL);
			ctx.writer.writeHeapType(Type.EQ.code());
		}
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.FUNC_WRITE_LINE);
	}

}
