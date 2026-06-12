package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;

/**
 * Compiles the {@code open} built-in. The direction must be the literal {@code :input}
 * (default) or {@code :output} keyword so the file mode is known at compile time; the
 * path argument is compiled to a runtime string and passed to the {@code _open} stream
 * runtime, which returns the WASI file descriptor boxed as an i31 integer.
 */
final class WasmOpenCompiler {

	private WasmOpenCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> parts = cons.toList();
		if (parts.size() != 2 && parts.size() != 3) {
			throw new UnsupportedOperationException("open expects 1 or 2 arguments, got " + (parts.size() - 1));
		}
		WasmExprCompiler.compileExpr(parts.get(1), ctx);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(staticMode(parts));
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.FUNC_OPEN);
	}

	/**
	 * Resolves the literal direction keyword to the file mode (0 = input, 1 = output).
	 * @param parts the open form parts
	 * @return the file mode
	 */
	static int staticMode(List<LispVal> parts) {
		if (parts.size() < 3) {
			return 0;
		}
		if (parts.get(2) instanceof LispSymbol dir) {
			if (LispNames.INPUT_KEYWORD.equals(dir.name())) {
				return 0;
			}
			if (LispNames.OUTPUT_KEYWORD.equals(dir.name())) {
				return 1;
			}
		}
		throw new UnsupportedOperationException("open requires a literal :input or :output direction");
	}

}
