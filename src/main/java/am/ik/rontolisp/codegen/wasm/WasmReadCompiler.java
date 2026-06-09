package am.ik.rontolisp.codegen.wasm;

import am.ik.rontolisp.LispCons;
import am.ik.wasm.Instruction;

/**
 * Compiles the {@code read} built-in. Invokes the {@code _read} runtime helper, which
 * parses one S-expression from a line of stdin into the runtime value representation.
 */
final class WasmReadCompiler {

	private WasmReadCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.FUNC_READ);
	}

}
