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
		// HEAP_PTR and derive the pointer + length path_open needs. off = HEAP_PTR ;
		// plen = _str_to_mem(path, off) - 2 (strip the surrounding quotes). HEAP_PTR is
		// then ADVANCED over the staged bytes for the duration of the call (and popped
		// back right after): under --component the adapter's first open lifts the
		// preopen directory list through cabi_realloc, which allocates at HEAP_PTR --
		// an un-advanced staging would be overwritten before path_open reads it.
		loadMem32(w, WasmLispCompiler.HEAP_PTR_ADDR);
		setLocal(w, OFF);
		getLocal(w, PATH);
		getLocal(w, OFF);
		WasmEmitHelper.emitStrToMemCall(w);
		i32(w, 2);
		w.write(Instruction.I32_SUB);
		setLocal(w, PLEN);
		// HEAP_PTR = align8(off + plen + 2)
		i32(w, WasmLispCompiler.HEAP_PTR_ADDR);
		getLocal(w, OFF);
		getLocal(w, PLEN);
		w.write(Instruction.I32_ADD);
		i32(w, 2 + 7);
		w.write(Instruction.I32_ADD);
		i32(w, -8);
		w.write(Instruction.I32_AND);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
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
		// pop the staged path (PLEN is free now: reuse it for the errno)
		setLocal(w, PLEN);
		i32(w, WasmLispCompiler.HEAP_PTR_ADDR);
		getLocal(w, OFF);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		// if errno != 0: trap
		getLocal(w, PLEN);
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
	 * Builds the _probe_file(path) function body: the path when it names an existing
	 * file, {@code ref.null eq} (nil) otherwise. Same staging and {@code path_open} call
	 * as {@link #buildOpenBody()} in read mode, with the two differences that make it a
	 * probe rather than an open: a non-zero errno answers nil instead of trapping (a wasm
	 * trap is not catchable, which is exactly why {@code (handler-case (open ...))}
	 * cannot stand in for this on WASM), and a successful open is closed again via
	 * {@code fd_close} so probing leaks no descriptor.
	 * @return the function body bytes
	 */
	static byte[] buildProbeFileBody() {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		// param: PATH=0 (ref) ; i32 locals: OFF=1, PLEN=2
		w.write(1);
		w.write(2);
		w.write(Type.I32);
		final int PATH = 0, OFF = 1, PLEN = 2;

		// Stage the path bytes into linear scratch exactly as _open does (see there for
		// why HEAP_PTR is advanced over the staging for the duration of the call).
		loadMem32(w, WasmLispCompiler.HEAP_PTR_ADDR);
		setLocal(w, OFF);
		getLocal(w, PATH);
		getLocal(w, OFF);
		WasmEmitHelper.emitStrToMemCall(w);
		i32(w, 2);
		w.write(Instruction.I32_SUB);
		setLocal(w, PLEN);
		// HEAP_PTR = align8(off + plen + 2)
		i32(w, WasmLispCompiler.HEAP_PTR_ADDR);
		getLocal(w, OFF);
		getLocal(w, PLEN);
		w.write(Instruction.I32_ADD);
		i32(w, 2 + 7);
		w.write(Instruction.I32_ADD);
		i32(w, -8);
		w.write(Instruction.I32_AND);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		// path_open(dirfd=3, dirflags=0, path_ptr=off+1, path_len=plen, oflags=0,
		// fs_rights_base=FD_READ=2, fs_rights_inheriting=0, fdflags=0,
		// fd_out=OPEN_FD_ADDR)
		i32(w, 3);
		i32(w, 0);
		getLocal(w, OFF);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		getLocal(w, PLEN);
		i32(w, 0);
		w.write(Instruction.I64_CONST);
		w.writeSignedLeb128(2);
		w.write(Instruction.I64_CONST);
		w.writeSignedLeb128(0);
		i32(w, 0);
		i32(w, WasmLispCompiler.OPEN_FD_ADDR);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_PATH_OPEN);
		// pop the staged path (PLEN is free now: reuse it for the errno)
		setLocal(w, PLEN);
		i32(w, WasmLispCompiler.HEAP_PTR_ADDR);
		getLocal(w, OFF);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		// if errno != 0: return nil
		getLocal(w, PLEN);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.REF_NULL);
		w.writeHeapType(Type.EQ.code());
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
		// close the descriptor the probe just opened, then answer with the path itself
		loadMem32(w, WasmLispCompiler.OPEN_FD_ADDR);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_FD_CLOSE);
		w.write(Instruction.DROP);
		getLocal(w, PATH);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	/**
	 * Builds the _close(stream) function body. Closes the file descriptor via WASI
	 * fd_close and returns the symbol {@code T}.
	 * @param st the string table (for the {@code T} symbol)
	 * @return the function body bytes
	 */
	static byte[] buildCloseBody(WasmLispCompiler.StringTable st) {
		WasmLispCompiler.StringTable.StringEntry t = st.addString("T");
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
	 * Builds the _read_char(stream, eof-error-p, eof-value) function body. Reads one
	 * Unicode CODE POINT (1..4 UTF-8 bytes) from the stream and returns it as a character
	 * struct -- WASM strings are UTF-8 encoded on the byte model but a CHARACTER is a
	 * code point on every backend, so read-char decodes the sequence starting at the
	 * cursor. A nil stream reads from standard input (fd 0); a negative i31 handle is a
	 * string input stream whose {@code [kind][cursor][end]} record is consumed 1..4 bytes
	 * at a time (the sequence is clamped against the buffer end -- a truncated tail
	 * yields the lead byte as a bare CHARACTER); a non-negative handle is a WASI fd read
	 * via {@code fd_read} where the lead byte's continuation count drives per-byte
	 * follow-up reads into the {@code BYTE_SCRATCH_ADDR} scratch cell (a truncated tail
	 * from EOF mid-sequence also falls back to the lead byte). On EOF at the start
	 * returns eof-value when eof-error-p is nil, otherwise traps.
	 * @return the function body bytes
	 */
	static byte[] buildReadCharBody() {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		// params: STREAM=0 (ref), EOF_ERROR_P=1 (ref), EOF_VALUE=2 (ref) ; i32 locals:
		// FD=3, REC=4, CUR=5, END=6, NEEDED=7, B0=8, B1=9, B2=10, B3=11, I=12
		w.write(1);
		w.write(10);
		w.write(Type.I32);
		final int STREAM = 0, EOF_ERROR_P = 1, EOF_VALUE = 2, FD = 3, REC = 4, CUR = 5, END = 6, NEEDED = 7, B0 = 8,
				B1 = 9, B2 = 10, B3 = 11, I = 12;
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
		// A negative handle is a string input stream: decode a UTF-8 sequence within the
		// [cursor, end) range.
		getLocal(w, FD);
		i32(w, 0);
		w.write(Instruction.I32_LT_S);
		w.write(Instruction.IF, 0x40);
		i32(w, 0);
		getLocal(w, FD);
		w.write(Instruction.I32_SUB);
		setLocal(w, REC);
		// cur = mem[rec + 4]; end = mem[rec + 8];
		getLocal(w, REC);
		i32(w, 4);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		setLocal(w, CUR);
		getLocal(w, REC);
		i32(w, 8);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		setLocal(w, END);
		// if (cur >= end): EOF
		getLocal(w, CUR);
		getLocal(w, END);
		w.write(Instruction.I32_GE_S);
		w.write(Instruction.IF, 0x40);
		emitReadCharEof(w, EOF_ERROR_P, EOF_VALUE);
		w.write(Instruction.END);
		// b0 = mem_u8[cur]; needed = utf8ByteCount(b0);
		getLocal(w, CUR);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);
		setLocal(w, B0);
		emitUtf8ByteCount(w, B0);
		setLocal(w, NEEDED);
		// If cur + needed > end: needed = 1 (truncated tail -- return bare lead byte).
		getLocal(w, CUR);
		getLocal(w, NEEDED);
		w.write(Instruction.I32_ADD);
		getLocal(w, END);
		w.write(Instruction.I32_GT_S);
		w.write(Instruction.IF, 0x40);
		i32(w, 1);
		setLocal(w, NEEDED);
		w.write(Instruction.END);
		// Load b1..b3 as needed.
		getLocal(w, NEEDED);
		i32(w, 2);
		w.write(Instruction.I32_GE_S);
		w.write(Instruction.IF, 0x40);
		getLocal(w, CUR);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);
		setLocal(w, B1);
		w.write(Instruction.END);
		getLocal(w, NEEDED);
		i32(w, 3);
		w.write(Instruction.I32_GE_S);
		w.write(Instruction.IF, 0x40);
		getLocal(w, CUR);
		i32(w, 2);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);
		setLocal(w, B2);
		w.write(Instruction.END);
		getLocal(w, NEEDED);
		i32(w, 4);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.IF, 0x40);
		getLocal(w, CUR);
		i32(w, 3);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);
		setLocal(w, B3);
		w.write(Instruction.END);
		// mem[rec + 4] = cur + needed
		getLocal(w, REC);
		i32(w, 4);
		w.write(Instruction.I32_ADD);
		getLocal(w, CUR);
		getLocal(w, NEEDED);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		// return TYPE_CHAR(decodeUtf8(needed, b0, b1, b2, b3))
		emitUtf8DecodeFromLocals(w, NEEDED, B0, B1, B2, B3);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_CHAR);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
		// WASI fd branch: read the lead byte via fd_read, then per-byte follow-ups.
		// iov.ptr = SCRATCH ; iov.len = 1
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
		// b0 = mem_u8[SCRATCH]; needed = utf8ByteCount(b0);
		i32(w, SCRATCH);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);
		setLocal(w, B0);
		emitUtf8ByteCount(w, B0);
		setLocal(w, NEEDED);
		// i = 1; loop { if i >= needed break; fd_read(fd, IOV, 1, NWRITTEN); if nread==0
		// { needed = i; break; } mem_u8[SCRATCH+i] = mem_u8[SCRATCH]; i++; }
		i32(w, 1);
		setLocal(w, I);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		// if (i >= needed) break out of block.
		getLocal(w, I);
		getLocal(w, NEEDED);
		w.write(Instruction.I32_GE_S);
		w.write(Instruction.BR_IF);
		w.writeSignedLeb128(1);
		// iov points at SCRATCH already; we reuse it. Read one byte into SCRATCH.
		getLocal(w, FD);
		i32(w, IOV);
		i32(w, 1);
		i32(w, NWRITTEN);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_FD_READ);
		w.write(Instruction.DROP);
		// If nread == 0: needed = i; break.
		loadMem32(w, NWRITTEN);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF, 0x40);
		getLocal(w, I);
		setLocal(w, NEEDED);
		w.write(Instruction.BR);
		w.writeSignedLeb128(2);
		w.write(Instruction.END);
		// Stash the just-read byte into local B1/B2/B3 by index. Emitted as a small
		// switch on i to keep the loop body free of writable memory (SCRATCH is single-
		// byte, reused for the next read).
		getLocal(w, I);
		i32(w, 1);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.IF, 0x40);
		i32(w, SCRATCH);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);
		setLocal(w, B1);
		w.write(Instruction.END);
		getLocal(w, I);
		i32(w, 2);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.IF, 0x40);
		i32(w, SCRATCH);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);
		setLocal(w, B2);
		w.write(Instruction.END);
		getLocal(w, I);
		i32(w, 3);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.IF, 0x40);
		i32(w, SCRATCH);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);
		setLocal(w, B3);
		w.write(Instruction.END);
		// i = i + 1; continue.
		getLocal(w, I);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		setLocal(w, I);
		w.write(Instruction.BR);
		w.writeSignedLeb128(0);
		w.write(Instruction.END); // loop
		w.write(Instruction.END); // block
		// If needed collapsed to 0 mid-sequence (very first follow-up EOF), clamp to 1
		// so we return the lead byte as a bare CHARACTER rather than dispatching to a
		// zero-count decode.
		getLocal(w, NEEDED);
		i32(w, 1);
		w.write(Instruction.I32_LT_S);
		w.write(Instruction.IF, 0x40);
		i32(w, 1);
		setLocal(w, NEEDED);
		w.write(Instruction.END);
		// return TYPE_CHAR(decodeUtf8(needed, b0, b1, b2, b3))
		emitUtf8DecodeFromLocals(w, NEEDED, B0, B1, B2, B3);
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

	// Pushes the UTF-8 sequence length (1..4) implied by a lead byte, based on the
	// same high-bit ranges as _str_char_at: [0..0x80)=1, [0x80..0xE0)=2,
	// [0xE0..0xF0)=3, else 4.
	private static void emitUtf8ByteCount(WasmWriter w, int b0Local) {
		getLocal(w, b0Local);
		i32(w, 0x80);
		w.write(Instruction.I32_LT_U);
		w.write(Instruction.IF);
		w.write(Type.I32);
		i32(w, 1);
		w.write(Instruction.ELSE);
		getLocal(w, b0Local);
		i32(w, 0xE0);
		w.write(Instruction.I32_LT_U);
		w.write(Instruction.IF);
		w.write(Type.I32);
		i32(w, 2);
		w.write(Instruction.ELSE);
		getLocal(w, b0Local);
		i32(w, 0xF0);
		w.write(Instruction.I32_LT_U);
		w.write(Instruction.IF);
		w.write(Type.I32);
		i32(w, 3);
		w.write(Instruction.ELSE);
		i32(w, 4);
		w.write(Instruction.END);
		w.write(Instruction.END);
		w.write(Instruction.END);
	}

	// Pushes the decoded Unicode code point given the sequence length in {@code
	// neededLocal} and the 1..4 bytes in {@code b0Local}..{@code b3Local}. Follows the
	// same 6-bit continuation decoding as _str_char_at.
	private static void emitUtf8DecodeFromLocals(WasmWriter w, int neededLocal, int b0Local, int b1Local, int b2Local,
			int b3Local) {
		getLocal(w, neededLocal);
		i32(w, 1);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.IF);
		w.write(Type.I32);
		getLocal(w, b0Local);
		w.write(Instruction.ELSE);
		getLocal(w, neededLocal);
		i32(w, 2);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.IF);
		w.write(Type.I32);
		// ((b0 & 0x1F) << 6) | (b1 & 0x3F)
		getLocal(w, b0Local);
		i32(w, 0x1F);
		w.write(Instruction.I32_AND);
		i32(w, 6);
		w.write(Instruction.I32_SHL);
		getLocal(w, b1Local);
		i32(w, 0x3F);
		w.write(Instruction.I32_AND);
		w.write(Instruction.I32_OR);
		w.write(Instruction.ELSE);
		getLocal(w, neededLocal);
		i32(w, 3);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.IF);
		w.write(Type.I32);
		// ((b0 & 0x0F) << 12) | ((b1 & 0x3F) << 6) | (b2 & 0x3F)
		getLocal(w, b0Local);
		i32(w, 0x0F);
		w.write(Instruction.I32_AND);
		i32(w, 12);
		w.write(Instruction.I32_SHL);
		getLocal(w, b1Local);
		i32(w, 0x3F);
		w.write(Instruction.I32_AND);
		i32(w, 6);
		w.write(Instruction.I32_SHL);
		w.write(Instruction.I32_OR);
		getLocal(w, b2Local);
		i32(w, 0x3F);
		w.write(Instruction.I32_AND);
		w.write(Instruction.I32_OR);
		w.write(Instruction.ELSE);
		// 4-byte: ((b0 & 0x07) << 18) | ((b1 & 0x3F) << 12) | ((b2 & 0x3F) << 6) |
		// (b3 & 0x3F)
		getLocal(w, b0Local);
		i32(w, 0x07);
		w.write(Instruction.I32_AND);
		i32(w, 18);
		w.write(Instruction.I32_SHL);
		getLocal(w, b1Local);
		i32(w, 0x3F);
		w.write(Instruction.I32_AND);
		i32(w, 12);
		w.write(Instruction.I32_SHL);
		w.write(Instruction.I32_OR);
		getLocal(w, b2Local);
		i32(w, 0x3F);
		w.write(Instruction.I32_AND);
		i32(w, 6);
		w.write(Instruction.I32_SHL);
		w.write(Instruction.I32_OR);
		getLocal(w, b3Local);
		i32(w, 0x3F);
		w.write(Instruction.I32_AND);
		w.write(Instruction.I32_OR);
		w.write(Instruction.END);
		w.write(Instruction.END);
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
