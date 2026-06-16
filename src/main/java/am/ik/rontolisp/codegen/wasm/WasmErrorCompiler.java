package am.ik.rontolisp.codegen.wasm;

import am.ik.rontolisp.LispCons;
import am.ik.wasm.Instruction;

/**
 * Compiles the internal {@code %error} primitive: it aborts execution with a WASM trap.
 * {@code unreachable} is stack-polymorphic, so it type-checks in any context (including a
 * typed {@code if} branch that expects a result value). The message argument is not
 * rendered (a WASM trap carries no message), so it is not evaluated.
 */
final class WasmErrorCompiler {

	private WasmErrorCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		ctx.writer.write(Instruction.UNREACHABLE);
	}

}
