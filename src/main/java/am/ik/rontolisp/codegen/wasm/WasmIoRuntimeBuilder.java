package am.ik.rontolisp.codegen.wasm;

import java.io.ByteArrayOutputStream;

import am.ik.wasm.Instruction;
import am.ik.wasm.Type;
import am.ik.wasm.WasmWriter;

/**
 * Builds the WASM bodies of the file-stream runtime used by the {@code open},
 * {@code close}, {@code write-line}, {@code read-byte}, {@code write-byte} and
 * stream-taking {@code read-line} built-ins (and therefore by the {@code with-open-file}
 * macro).
 *
 * <p>
 * A stream value is the WASI file descriptor returned by {@code path_open}, boxed as an
 * i31 integer (mirroring the JVM backend, where the handle indexes a stream table). Like
 * {@code load}, {@code open} resolves the path relative to the first preopened directory
 * (fd 3), so the module must run with {@code --dir}. {@code _write_line} writes the
 * string bytes plus a newline straight through {@code fd_write} (fd 1 = stdout when no
 * stream is given), and {@code _close} delegates to {@code fd_close}.
 */
final class WasmIoRuntimeBuilder {

	private WasmIoRuntimeBuilder() {
	}

	/**
	 * Builds the _open(path, mode) function body. Opens the file named by the path string
	 * via WASI path_open (mode 0 = read, 1 = create/truncate for write) and returns the
	 * file descriptor as an i31 integer. Traps on a failed open.
	 * @return the function body bytes
	 */
	static byte[] buildOpenBody() {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		// params: PATH=0 (ref), MODE=1 (i32) ; i32 locals: OFF=2, PLEN=3
		w.write(1);
		w.write(2);
		w.write(Type.I32);
		final int PATH = 0, MODE = 1, OFF = 2, PLEN = 3;

		// The path bytes live on the GC heap, so copy them into linear scratch at
		// HEAP_PTR
		// (not advanced -- path_open consumes them immediately) and derive the pointer +
		// length it needs. off = HEAP_PTR ; plen = _str_to_mem(path, off) - 2 (strip the
		// surrounding quotes).
		loadMem32(w, WasmLispCompiler.HEAP_PTR_ADDR);
		setLocal(w, OFF);
		getLocal(w, PATH);
		getLocal(w, OFF);
		WasmEmitHelper.emitStrToMemCall(w);
		i32(w, 2);
		w.write(Instruction.I32_SUB);
		setLocal(w, PLEN);
		// path_open(dirfd=3, dirflags=0, path_ptr=off+1, path_len=plen,
		// oflags=(read: 0, write: CREAT|TRUNC=9),
		// fs_rights_base=(read: FD_READ=2, write: FD_WRITE|FD_SEEK|FD_TELL=100),
		// fs_rights_inheriting=0, fdflags=0, fd_out=OPEN_FD_ADDR)
		i32(w, 3);
		i32(w, 0);
		getLocal(w, OFF);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		getLocal(w, PLEN);
		getLocal(w, MODE);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF);
		w.write(Type.I32);
		i32(w, 0);
		w.write(Instruction.ELSE);
		i32(w, 9);
		w.write(Instruction.END);
		getLocal(w, MODE);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF);
		w.write(Type.I64);
		w.write(Instruction.I64_CONST);
		w.writeSignedLeb128(2);
		w.write(Instruction.ELSE);
		w.write(Instruction.I64_CONST);
		w.writeSignedLeb128(100);
		w.write(Instruction.END);
		w.write(Instruction.I64_CONST);
		w.writeSignedLeb128(0);
		i32(w, 0);
		i32(w, WasmLispCompiler.OPEN_FD_ADDR);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_PATH_OPEN);
		// if errno != 0: trap
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.UNREACHABLE);
		w.write(Instruction.END);
		// return ref.i31(mem[OPEN_FD_ADDR])
		loadMem32(w, WasmLispCompiler.OPEN_FD_ADDR);
		w.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	/**
	 * Builds the _close(stream) function body. Closes the file descriptor via WASI
	 * fd_close and returns the symbol {@code t}.
	 * @param st the string table (for the {@code t} symbol)
	 * @return the function body bytes
	 */
	static byte[] buildCloseBody(WasmLispCompiler.StringTable st) {
		WasmLispCompiler.StringTable.StringEntry t = st.addString("t");
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		// param: FD_VAL=0 (ref) ; i32 local: FD=1
		w.write(1);
		w.write(1);
		w.write(Type.I32);
		final int FD = 1;
		// fd = i31.get_s(fd_val)
		getLocal(w, 0);
		refCast(w, Type.I31.code());
		w.write(Instruction.GC_PREFIX, Instruction.I31_GET_S);
		setLocal(w, FD);
		// fd_close only for a real fd -- a negative handle is a string stream whose
		// record just becomes garbage (the bump allocator never frees).
		getLocal(w, FD);
		i32(w, 0);
		w.write(Instruction.I32_GE_S);
		w.write(Instruction.IF, 0x40);
		getLocal(w, FD);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_FD_CLOSE);
		w.write(Instruction.DROP);
		w.write(Instruction.END);
		// return t
		i32(w, t.offset());
		i32(w, t.length());
		WasmEmitHelper.emitStrBuildCall(w);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	/**
	 * Builds the _write_line(str, stream) function body. Writes the string content
	 * (without the surrounding quotes) plus a newline to the stream's file descriptor (1
	 * = stdout when the stream is nil) via fd_write, and returns the string.
	 * @param st the string table (for the newline byte)
	 * @return the function body bytes
	 */
	static byte[] buildWriteLineBody(WasmLispCompiler.StringTable st) {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		// params: STR=0 (ref), FD_VAL=1 (ref) ; i32 locals: OFF=2, LEN=3, FD=4, REC=5,
		// CHUNK=6, TAIL=7 (the last three only for the string-stream branch)
		w.write(1);
		w.write(6);
		w.write(Type.I32);
		final int STR = 0, FD_VAL = 1, OFF = 2, LEN = 3, FD = 4, REC = 5, CHUNK = 6, TAIL = 7;
		final int IOV = WasmLispCompiler.IOV_OFFSET;
		final int NWRITTEN = WasmLispCompiler.NWRITTEN_OFFSET;

		// The string's bytes live on the GC heap: copy them into linear scratch at
		// HEAP_PTR
		// so OFF/LEN name a real linear range (off = base, len = total incl. quotes).
		// HEAP_PTR is NOT advanced yet -- the fd_write branch consumes the copy
		// immediately,
		// while the string-output-stream branch below PERSISTS it before linking a chunk.
		loadMem32(w, WasmLispCompiler.HEAP_PTR_ADDR);
		setLocal(w, OFF);
		getLocal(w, STR);
		getLocal(w, OFF);
		WasmEmitHelper.emitStrToMemCall(w);
		setLocal(w, LEN);
		// fd = stream is nil ? 1 (stdout) : i31.get_s(stream)
		getLocal(w, FD_VAL);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.IF);
		w.write(Type.I32);
		i32(w, 1);
		w.write(Instruction.ELSE);
		getLocal(w, FD_VAL);
		refCast(w, Type.I31.code());
		w.write(Instruction.GC_PREFIX, Instruction.I31_GET_S);
		w.write(Instruction.END);
		setLocal(w, FD);
		// A negative handle is a string output stream: append the content and a newline
		// as chunks (see WasmStringStreamRuntimeBuilder) and return the string.
		getLocal(w, FD);
		i32(w, 0);
		w.write(Instruction.I32_LT_S);
		w.write(Instruction.IF, 0x40);
		i32(w, 0);
		getLocal(w, FD);
		w.write(Instruction.I32_SUB);
		setLocal(w, REC);
		// Persist the content copy: HEAP_PTR = OFF + LEN (past the copy), so the chunk's
		// referenced bytes survive the next write.
		i32(w, WasmLispCompiler.HEAP_PTR_ADDR);
		getLocal(w, OFF);
		getLocal(w, LEN);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		WasmStringStreamRuntimeBuilder.emitAppendChunk(w, REC, CHUNK, TAIL, () -> {
			getLocal(w, OFF);
			i32(w, 1);
			w.write(Instruction.I32_ADD);
		}, () -> {
			getLocal(w, LEN);
			i32(w, 2);
			w.write(Instruction.I32_SUB);
		});
		WasmStringStreamRuntimeBuilder.emitAppendChunk(w, REC, CHUNK, TAIL, () -> i32(w, st.newline.offset()),
				() -> i32(w, st.newline.length()));
		getLocal(w, STR);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
		// iov.ptr = off + 1 ; iov.len = len - 2 (strip surrounding quotes)
		i32(w, IOV);
		getLocal(w, OFF);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		i32(w, IOV + 4);
		getLocal(w, LEN);
		i32(w, 2);
		w.write(Instruction.I32_SUB);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		// fd_write(fd, IOV, 1, NWRITTEN)
		getLocal(w, FD);
		i32(w, IOV);
		i32(w, 1);
		i32(w, NWRITTEN);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_FD_WRITE);
		w.write(Instruction.DROP);
		// iov.ptr = newline ; iov.len = 1
		i32(w, IOV);
		i32(w, st.newline.offset());
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		i32(w, IOV + 4);
		i32(w, st.newline.length());
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		// fd_write(fd, IOV, 1, NWRITTEN)
		getLocal(w, FD);
		i32(w, IOV);
		i32(w, 1);
		i32(w, NWRITTEN);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_FD_WRITE);
		w.write(Instruction.DROP);
		// return the string
		getLocal(w, STR);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	/**
	 * Builds the _read_byte(stream, eof-error-p, eof-value) function body. Reads one raw
	 * byte from the stream's file descriptor via fd_read into the BYTE_SCRATCH_ADDR
	 * scratch cell -- no quote framing, no newline scan -- and returns it as an i31
	 * integer. On EOF returns eof-value when eof-error-p is nil, otherwise traps.
	 * @return the function body bytes
	 */
	static byte[] buildReadByteBody() {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		// params: STREAM=0 (ref), EOF_ERROR_P=1 (ref), EOF_VALUE=2 (ref) ; i32 local:
		// FD=3
		w.write(1);
		w.write(1);
		w.write(Type.I32);
		final int STREAM = 0, EOF_ERROR_P = 1, EOF_VALUE = 2, FD = 3;
		final int IOV = WasmLispCompiler.IOV_OFFSET;
		final int NWRITTEN = WasmLispCompiler.NWRITTEN_OFFSET;
		final int SCRATCH = WasmLispCompiler.BYTE_SCRATCH_ADDR;

		// fd = i31.get_s(stream)
		getLocal(w, STREAM);
		refCast(w, Type.I31.code());
		w.write(Instruction.GC_PREFIX, Instruction.I31_GET_S);
		setLocal(w, FD);
		// iov.ptr = BYTE_SCRATCH_ADDR ; iov.len = 1
		i32(w, IOV);
		i32(w, SCRATCH);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		i32(w, IOV + 4);
		i32(w, 1);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		// fd_read(fd, IOV, 1, NWRITTEN) ; drop errno
		getLocal(w, FD);
		i32(w, IOV);
		i32(w, 1);
		i32(w, NWRITTEN);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_FD_READ);
		w.write(Instruction.DROP);
		// if (mem[NWRITTEN] == 0): EOF -- return eof-value when eof-error-p is nil,
		// trap otherwise
		loadMem32(w, NWRITTEN);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF, 0x40);
		getLocal(w, EOF_ERROR_P);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.IF, 0x40);
		getLocal(w, EOF_VALUE);
		w.write(Instruction.RETURN);
		w.write(Instruction.ELSE);
		w.write(Instruction.UNREACHABLE);
		w.write(Instruction.END);
		w.write(Instruction.END);
		// return ref.i31(mem_u8[BYTE_SCRATCH_ADDR])
		i32(w, SCRATCH);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);
		w.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	/**
	 * Builds the _read_char(stream, eof-error-p, eof-value) function body. Reads one BYTE
	 * from the stream and returns it as a character struct -- WASM strings are
	 * byte-indexed (like {@code char}/{@code schar}), so a character read is a byte read.
	 * A nil stream reads from standard input (fd 0); a negative i31 handle is a string
	 * input stream whose {@code [kind][cursor][end]} record is consumed one byte at a
	 * time; a non-negative handle is a WASI fd read via fd_read through the
	 * BYTE_SCRATCH_ADDR scratch cell. On EOF returns eof-value when eof-error-p is nil,
	 * otherwise traps (like _read_byte).
	 * @return the function body bytes
	 */
	static byte[] buildReadCharBody() {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		// params: STREAM=0 (ref), EOF_ERROR_P=1 (ref), EOF_VALUE=2 (ref) ; i32 locals:
		// FD=3, REC=4, CUR=5
		w.write(1);
		w.write(3);
		w.write(Type.I32);
		final int STREAM = 0, EOF_ERROR_P = 1, EOF_VALUE = 2, FD = 3, REC = 4, CUR = 5;
		final int IOV = WasmLispCompiler.IOV_OFFSET;
		final int NWRITTEN = WasmLispCompiler.NWRITTEN_OFFSET;
		final int SCRATCH = WasmLispCompiler.BYTE_SCRATCH_ADDR;

		// fd = stream is nil ? 0 (stdin) : i31.get_s(stream)
		getLocal(w, STREAM);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.IF);
		w.write(Type.I32);
		i32(w, 0);
		w.write(Instruction.ELSE);
		getLocal(w, STREAM);
		refCast(w, Type.I31.code());
		w.write(Instruction.GC_PREFIX, Instruction.I31_GET_S);
		w.write(Instruction.END);
		setLocal(w, FD);
		// A negative handle is a string input stream: consume one byte of the record's
		// [cursor, end) range.
		getLocal(w, FD);
		i32(w, 0);
		w.write(Instruction.I32_LT_S);
		w.write(Instruction.IF, 0x40);
		i32(w, 0);
		getLocal(w, FD);
		w.write(Instruction.I32_SUB);
		setLocal(w, REC);
		// cur = mem[rec + 4]
		getLocal(w, REC);
		i32(w, 4);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		setLocal(w, CUR);
		// if (cur >= mem[rec + 8]): EOF
		getLocal(w, CUR);
		getLocal(w, REC);
		i32(w, 8);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		w.write(Instruction.I32_GE_S);
		w.write(Instruction.IF, 0x40);
		emitReadCharEof(w, EOF_ERROR_P, EOF_VALUE);
		w.write(Instruction.END);
		// mem[rec + 4] = cur + 1
		getLocal(w, REC);
		i32(w, 4);
		w.write(Instruction.I32_ADD);
		getLocal(w, CUR);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		// return char(mem_u8[cur])
		getLocal(w, CUR);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_CHAR);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
		// WASI fd: read one byte through the scratch cell, like _read_byte.
		// iov.ptr = BYTE_SCRATCH_ADDR ; iov.len = 1
		i32(w, IOV);
		i32(w, SCRATCH);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		i32(w, IOV + 4);
		i32(w, 1);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		// fd_read(fd, IOV, 1, NWRITTEN) ; drop errno
		getLocal(w, FD);
		i32(w, IOV);
		i32(w, 1);
		i32(w, NWRITTEN);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_FD_READ);
		w.write(Instruction.DROP);
		// if (mem[NWRITTEN] == 0): EOF
		loadMem32(w, NWRITTEN);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF, 0x40);
		emitReadCharEof(w, EOF_ERROR_P, EOF_VALUE);
		w.write(Instruction.END);
		// return char(mem_u8[BYTE_SCRATCH_ADDR])
		i32(w, SCRATCH);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_CHAR);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	// EOF: return eof-value when eof-error-p is nil, trap otherwise (like _read_byte).
	private static void emitReadCharEof(WasmWriter w, int eofErrorP, int eofValue) {
		getLocal(w, eofErrorP);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.IF, 0x40);
		getLocal(w, eofValue);
		w.write(Instruction.RETURN);
		w.write(Instruction.ELSE);
		w.write(Instruction.UNREACHABLE);
		w.write(Instruction.END);
	}

	/**
	 * Builds the _write_byte(byte, stream) function body. Writes the byte's low 8 bits as
	 * one raw byte to the stream's file descriptor via fd_write -- no quote framing, no
	 * newline -- and returns the byte.
	 * @return the function body bytes
	 */
	static byte[] buildWriteByteBody() {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		// params: BYTE=0 (ref), STREAM=1 (ref) ; no locals
		w.write(0);
		final int BYTE = 0, STREAM = 1;
		final int IOV = WasmLispCompiler.IOV_OFFSET;
		final int NWRITTEN = WasmLispCompiler.NWRITTEN_OFFSET;
		final int SCRATCH = WasmLispCompiler.BYTE_SCRATCH_ADDR;

		// mem_u8[BYTE_SCRATCH_ADDR] = i31.get_s(byte)
		i32(w, SCRATCH);
		getLocal(w, BYTE);
		refCast(w, Type.I31.code());
		w.write(Instruction.GC_PREFIX, Instruction.I31_GET_S);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		// iov.ptr = BYTE_SCRATCH_ADDR ; iov.len = 1
		i32(w, IOV);
		i32(w, SCRATCH);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		i32(w, IOV + 4);
		i32(w, 1);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		// fd_write(i31.get_s(stream), IOV, 1, NWRITTEN) ; drop errno
		getLocal(w, STREAM);
		refCast(w, Type.I31.code());
		w.write(Instruction.GC_PREFIX, Instruction.I31_GET_S);
		i32(w, IOV);
		i32(w, 1);
		i32(w, NWRITTEN);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_FD_WRITE);
		w.write(Instruction.DROP);
		// return the byte
		getLocal(w, BYTE);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	// === low-level emit helpers ===

	private static void getLocal(WasmWriter w, int slot) {
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(slot);
	}

	private static void setLocal(WasmWriter w, int slot) {
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(slot);
	}

	private static void i32(WasmWriter w, int value) {
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(value);
	}

	private static void structGet(WasmWriter w, int type, int field) {
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeSignedLeb128(type);
		w.writeSignedLeb128(field);
	}

	private static void refCast(WasmWriter w, int heapType) {
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(heapType);
	}

	private static void loadMem32(WasmWriter w, int addr) {
		i32(w, addr);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
	}

}
