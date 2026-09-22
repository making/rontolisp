package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.OpenModes;
import am.ik.wasm.Instruction;

/**
 * Compiles the {@code open} built-in. The direction must be one of the literal tokens
 * {@code OpenModes.directionMode} resolves ({@code :input}, {@code :output}, {@code :io}
 * and the four normalized {@code :if-exists} spellings) and the optional element type the
 * literal {@code 'character} (default) or {@code '(unsigned-byte 8)} (binary) so the file
 * mode is known at compile time; the path argument is compiled to a runtime string and
 * passed to the {@code _open} stream runtime, which returns the WASI file descriptor
 * boxed as an i31 integer. A WASI file descriptor is element-type-agnostic, so the binary
 * bit of the mode is dropped here ({@link #wasmMode}) and {@code _open} only sees the
 * direction and disposition -- passing the raw mode would mis-select the write
 * oflags/rights.
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
		// An APPENDING stream starts at the end of its file (sbcl): a descriptor opened
		// for append sits at 0 until its first write, so a program that calls
		// file-position moves it there -- _file_position_set(fd, _file_length(fd)).
		// Every other stream's descriptor offset already IS its position, character or
		// binary: Preview 1 reads one byte per fd_read and writes straight through, and
		// the adapter tracks what was read and written.
		int disposition = wasmMode(OpenModes.staticMode(parts));
		if (ctx.filePosition && (disposition == 2 || disposition == 5)) {
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeUnsignedLeb128(fd);
			ctx.writer.write(Instruction.REF_IS_NULL);
			ctx.writer.write(Instruction.I32_EQZ);
			ctx.writer.write(Instruction.IF, 0x40);
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeUnsignedLeb128(fd);
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeUnsignedLeb128(fd);
			ctx.writer.write(Instruction.CALL);
			ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_FILE_LENGTH);
			ctx.writer.write(Instruction.CALL);
			ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_FILE_POSITION_SET);
			ctx.writer.write(Instruction.DROP);
			ctx.writer.write(Instruction.END);
		}
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(fd);
	}

	/**
	 * The {@code _open} mode a WASI descriptor actually distinguishes: {@code 0} = read,
	 * {@code 1} = write (CREAT|TRUNC), {@code 2} = APPEND (CREAT, fdflags APPEND),
	 * {@code 3} = OVERWRITE (neither CREAT nor TRUNC), and {@code 4}/{@code 5}/{@code 6}
	 * the same three dispositions for a BIDIRECTIONAL {@code :io} descriptor, which asks
	 * for FD_READ on top of the write rights. The element type is dropped -- a WASI fd is
	 * element-type-agnostic, and passing the raw {@link OpenModes#BINARY_BIT} would
	 * mis-select the write oflags/rights.
	 * @param staticMode the {@link OpenModes} mode
	 * @return 0 to 6
	 */
	static int wasmMode(int staticMode) {
		if ((staticMode & OpenModes.OUTPUT_BIT) == 0) {
			return 0;
		}
		int disposition = (staticMode & OpenModes.APPEND_BIT) != 0 ? 2
				: (staticMode & OpenModes.OVERWRITE_BIT) != 0 ? 3 : 1;
		return (staticMode & OpenModes.IO_BIT) != 0 ? disposition + 3 : disposition;
	}

}
