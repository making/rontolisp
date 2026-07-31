package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;

/**
 * Compiles the {@code close} built-in. The stream argument is compiled normally and
 * passed to the {@code _close} stream runtime, which closes the WASI file descriptor.
 */
final class WasmCloseCompiler {

	private WasmCloseCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		LispVal stripped = am.ik.rontolisp.macro.LispMacroExpander.stripCloseAbort(cons);
		if (stripped instanceof LispCons strippedCons) {
			cons = strippedCons;
		}
		List<LispVal> parts = cons.toList();
		if (parts.size() != 2) {
			throw new UnsupportedOperationException("close expects 1 argument, got " + (parts.size() - 1));
		}
		WasmExprCompiler.compileExpr(parts.get(1), ctx);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.FUNC_CLOSE);
	}

}
