package am.ik.rontolisp.codegen.wasm;

import am.ik.rontolisp.LispCons;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;

/**
 * Compiles the {@code fresh-line} built-in function. Writes a newline only when stdout is
 * not already at the start of a line, as tracked by {@code LINE_START_ADDR} (which
 * {@code _write_str} updates on every stdout write). Always returns nil.
 */
final class WasmFreshLineCompiler {

	private WasmFreshLineCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		java.util.List<am.ik.rontolisp.LispVal> args = cons.toList();
		// The destination, under CL's stream designator rule: an explicit stream, or --
		// for an omitted argument AND for an explicit nil -- the current
		// *standard-output* (WasmEmitHelper.streamArg); either goes through the
		// handle-aware _fresh_line_stream runtime helper.
		am.ik.rontolisp.LispVal stream = WasmEmitHelper.streamArg(ctx, args.size() > 1 ? args.get(1) : null);
		if (stream != null) {
			WasmExprCompiler.compileExpr(stream, ctx);
			ctx.writer.write(Instruction.CALL);
			ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_FRESH_LINE_STREAM);
			return;
		}
		// if (memory[LINE_START_ADDR] != 0) write "\n"
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.LINE_START_ADDR);
		ctx.writer.write(Instruction.I32_LOAD, 0x02, 0x00);
		ctx.writer.write(Instruction.IF, 0x40);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(ctx.stringTable.newline.offset());
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(ctx.stringTable.newline.length());
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);
		ctx.writer.write(Instruction.END);
		// Return nil
		ctx.writer.write(Instruction.REF_NULL);
		ctx.writer.writeHeapType(Type.EQ.code());
	}

}
