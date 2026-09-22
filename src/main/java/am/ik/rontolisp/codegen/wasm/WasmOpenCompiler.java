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
		// A file stream carries a per-fd flag the _file_position runtime reads to answer
		// nil for a CHARACTER stream (mirroring the interpreter and the JVM) -- written
		// only for a descriptor that exists, and only when the program calls
		// file-position at all under WASI, so every other program keeps its bytes.
		//
		// The two backends differ in who OWNS the table. Under --component the adapter
		// does: it resets a slot on every path_open, so only a POSITIONED open writes
		// here, and the index is the adapter's own numbering (100 + slot). Under
		// Preview 1 the module owns it and the host reuses descriptor numbers freely, so
		// EVERY open writes -- 1 positioned, 0 not -- and the index is the raw fd,
		// bounded by the table's slot count so an unexpectedly high descriptor cannot
		// write past it.
		//
		// The flag says "this descriptor's file-position is REAL", which a binary stream
		// and a BIDIRECTIONAL one both are: a bidirectional character stream reads and
		// writes the same cursor byte for byte, so the descriptor offset IS the logical
		// position (an ordinary character stream's is not -- .kb/read-load-streams.md).
		int mode = OpenModes.staticMode(parts);
		boolean binary = (mode & OpenModes.BINARY_BIT) != 0;
		boolean positioned = binary || (mode & (OpenModes.IO_BIT | OpenModes.OVERWRITE_BIT)) != 0;
		boolean preview1 = ctx.binaryFlagsAddr >= 0;
		if (ctx.filePosition && (positioned || preview1)) {
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeUnsignedLeb128(fd);
			ctx.writer.write(Instruction.REF_IS_NULL);
			ctx.writer.write(Instruction.I32_EQZ);
			ctx.writer.write(Instruction.IF, 0x40);
			if (preview1) {
				// Nested rather than and-ed: the cast that reads the descriptor is only
				// legal once nil is ruled out.
				emitRawFd(ctx, fd);
				ctx.writer.write(Instruction.I32_CONST);
				ctx.writer.writeSignedLeb128(WasmLispCompiler.STREAM_BINARY_FLAGS_SLOTS);
				ctx.writer.write(Instruction.I32_LT_U);
				ctx.writer.write(Instruction.IF, 0x40);
			}
			emitRawFd(ctx, fd);
			if (!preview1) {
				ctx.writer.write(Instruction.I32_CONST);
				ctx.writer.writeSignedLeb128(100);
				ctx.writer.write(Instruction.I32_SUB);
			}
			ctx.writer.write(Instruction.I32_CONST);
			ctx.writer.writeSignedLeb128(preview1 ? ctx.binaryFlagsAddr : WasmLispCompiler.STREAM_BINARY_FLAGS_ADDR);
			ctx.writer.write(Instruction.I32_ADD);
			ctx.writer.write(Instruction.I32_CONST);
			ctx.writer.writeSignedLeb128(positioned ? 1 : 0);
			ctx.writer.write(Instruction.I32_STORE8, 0x00, 0x00);
			if (preview1) {
				ctx.writer.write(Instruction.END);
			}
			ctx.writer.write(Instruction.END);
		}
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(fd);
	}

	/**
	 * Pushes the raw i32 descriptor held in the {@code fd} temp (nil already ruled out).
	 */
	private static void emitRawFd(WasmLispCompiler.Ctx ctx, int fd) {
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(fd);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(am.ik.wasm.Type.I31.code());
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_GET_S);
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
