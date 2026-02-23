package am.ik.rontolisp.codegen.wasm;

import am.ik.rontolisp.LispCons;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;

/**
 * Compiles the {@code terpri} built-in function. Prints a newline only.
 */
final class WasmTerpriCompiler {

	private WasmTerpriCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
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

}
