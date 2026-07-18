package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.OpenModes;
import am.ik.wasm.Instruction;

/**
 * Compiles the {@code open} built-in. The direction must be the literal {@code :input}
 * (default) or {@code :output} keyword and the optional element type the literal
 * {@code 'character} (default) or {@code '(unsigned-byte 8)} (binary) so the file mode is
 * known at compile time; the path argument is compiled to a runtime string and passed to
 * the {@code _open} stream runtime, which returns the WASI file descriptor boxed as an
 * i31 integer. A WASI file descriptor is element-type-agnostic, so the binary bit of the
 * mode is dropped here ({@code & 1}) and {@code _open} only sees the direction -- passing
 * the raw binary modes 2/3 would mis-select the write oflags/rights.
 */
final class WasmOpenCompiler {

	private WasmOpenCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		cons = OpenModes.normalizeKeywordForm(cons);
		List<LispVal> parts = cons.toList();
		if (parts.size() < 2 || parts.size() > 4) {
			throw new UnsupportedOperationException("open expects 1 to 3 arguments, got " + (parts.size() - 1));
		}
		WasmExprCompiler.compileExpr(parts.get(1), ctx);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(OpenModes.staticMode(parts) & OpenModes.OUTPUT_BIT);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.FUNC_OPEN);
	}

}
