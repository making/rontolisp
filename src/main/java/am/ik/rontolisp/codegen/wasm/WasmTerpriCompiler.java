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
		// The destination, under CL's stream designator rule: an explicit stream, or --
		// for an omitted argument AND for an explicit nil -- the current
		// *standard-output* (WasmEmitHelper.streamArg).
		am.ik.rontolisp.LispVal stream = WasmEmitHelper.streamArg(ctx, args.size() > 1 ? args.get(1) : null);
		if (stream != null) {
			// (terpri stream): route a newline via _write_stream_str.
			emitNewlineStringStruct(ctx);
			WasmExprCompiler.compileExpr(stream, ctx);
			ctx.writer.write(Instruction.CALL);
			ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_WRITE_STREAM_STR);
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
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);
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
