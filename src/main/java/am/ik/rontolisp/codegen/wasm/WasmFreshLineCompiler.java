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
		ctx.writer.writeSignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);
		ctx.writer.write(Instruction.END);
		// Return nil
		ctx.writer.write(Instruction.REF_NULL);
		ctx.writer.writeHeapType(Type.EQ.code());
	}

}
