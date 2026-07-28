package am.ik.rontolisp.codegen.wasm;

import am.ik.rontolisp.LispCons;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;

/**
 * Compiles the {@code terpri} built-in function. Prints a newline only, to standard
 * output or to the optional stream argument.
 */
final class WasmTerpriCompiler {

	private WasmTerpriCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		java.util.List<am.ik.rontolisp.LispVal> args = cons.toList();
		// An explicit stream argument, or the current *standard-output* value when the
		// program redirects it (WasmEmitHelper.defaultStreamArg).
		am.ik.rontolisp.LispVal stream = args.size() > 1 ? args.get(1) : WasmEmitHelper.defaultStreamArg(ctx);
		if (stream != null) {
			// (terpri stream): route a newline via _write_stream_str.
			emitNewlineStringStruct(ctx);
			WasmExprCompiler.compileExpr(stream, ctx);
			ctx.writer.write(Instruction.CALL);
			ctx.writer.writeSignedLeb128(WasmLispCompiler.FUNC_WRITE_STREAM_STR);
			ctx.writer.write(Instruction.DROP);
			ctx.writer.write(Instruction.REF_NULL);
			ctx.writer.writeHeapType(Type.EQ.code());
			return;
		}
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(ctx.stringTable.newline.offset());
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(ctx.stringTable.newline.length());
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);
		// Return nil
		ctx.writer.write(Instruction.REF_NULL);
		ctx.writer.writeHeapType(Type.EQ.code());
	}

	/**
	 * Pushes a quote-framed {@code "\n"} string struct -- the newline value routed to a
	 * stream by the terpri/print stream paths.
	 */
	static void emitNewlineStringStruct(WasmLispCompiler.Ctx ctx) {
		WasmEmitHelper.compileStringLiteral("\"\n\"", ctx);
	}

}
