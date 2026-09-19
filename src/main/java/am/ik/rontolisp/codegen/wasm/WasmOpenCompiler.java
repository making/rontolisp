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
 *
 * <p>
 * What this compiles is {@code %open-or-nil}, the nil-answering half of {@code open}:
 * {@code _open} answers nil when {@code path_open} failed and the nil is passed on, and
 * the shared lowering around it ({@code LispMacroExpander.expandOpenFileErrorSignal})
 * tests it and signals the {@code file-error}, identically on every backend. See
 * {@code .kb/read-load-streams.md}.
 */
final class WasmOpenCompiler {

	private WasmOpenCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		LispVal runtimeOptions = OpenModes.lowerRuntimeOptions(cons);
		if (runtimeOptions != null) {
			// A computed option value: the dispatch onto the literal shapes carries the
			// mode instead, so this compiler still only ever sees literals.
			WasmExprCompiler.compileExpr(runtimeOptions, ctx);
			return;
		}
		cons = OpenModes.normalizeKeywordForm(cons);
		List<LispVal> parts = cons.toList();
		if (parts.size() < 2 || parts.size() > 4) {
			throw new UnsupportedOperationException("open expects 1 to 3 arguments, got " + (parts.size() - 1));
		}
		WasmExprCompiler.compileExpr(parts.get(1), ctx);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(wasmMode(OpenModes.staticMode(parts)));
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_OPEN);
		int fd = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(fd);
		// A binary (unsigned-byte 8) file stream carries a per-fd flag the _file_position
		// runtime reads to answer nil for a character stream (mirroring the interpreter
		// and the JVM) -- set only for a descriptor that exists. Only when the program
		// both runs under --component and calls file-position at all, so every other
		// program keeps its bytes.
		if (ctx.componentFilePosition && (OpenModes.staticMode(parts) & OpenModes.BINARY_BIT) != 0) {
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeUnsignedLeb128(fd);
			ctx.writer.write(Instruction.REF_IS_NULL);
			ctx.writer.write(Instruction.I32_EQZ);
			ctx.writer.write(Instruction.IF, 0x40);
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeUnsignedLeb128(fd);
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
			ctx.writer.writeHeapType(am.ik.wasm.Type.I31.code());
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_GET_S);
			ctx.writer.write(Instruction.I32_CONST);
			ctx.writer.writeSignedLeb128(100);
			ctx.writer.write(Instruction.I32_SUB);
			ctx.writer.write(Instruction.I32_CONST);
			ctx.writer.writeSignedLeb128(WasmLispCompiler.STREAM_BINARY_FLAGS_ADDR);
			ctx.writer.write(Instruction.I32_ADD);
			ctx.writer.write(Instruction.I32_CONST);
			ctx.writer.writeSignedLeb128(1);
			ctx.writer.write(Instruction.I32_STORE8, 0x00, 0x00);
			ctx.writer.write(Instruction.END);
		}
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(fd);
	}

	/**
	 * The {@code _open} mode a WASI descriptor actually distinguishes: {@code 0} = read,
	 * {@code 1} = write (CREAT|TRUNC), {@code 2} = APPEND (CREAT, fdflags APPEND). The
	 * element type is dropped -- a WASI fd is element-type-agnostic, and passing the raw
	 * {@link OpenModes#BINARY_BIT} would mis-select the write oflags/rights.
	 * @param staticMode the {@link OpenModes} mode
	 * @return 0, 1 or 2
	 */
	static int wasmMode(int staticMode) {
		if ((staticMode & OpenModes.OUTPUT_BIT) == 0) {
			return 0;
		}
		return (staticMode & OpenModes.APPEND_BIT) != 0 ? 2 : 1;
	}

}
