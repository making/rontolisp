package am.ik.rontolisp.codegen.wasm;

import java.io.ByteArrayOutputStream;

import am.ik.wasm.Instruction;
import am.ik.wasm.Type;
import am.ik.wasm.WasmWriter;

/**
 * Builds the WASM bodies of the file-stream runtime used by the {@code open},
 * {@code close}, {@code write-line} and stream-taking {@code read-line} built-ins (and
 * therefore by the {@code with-open-file} macro).
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

		// off = string.offset ; plen = string.length - 2 (strip surrounding quotes)
		getLocal(w, PATH);
		refCast(w, WasmLispCompiler.TYPE_STRING);
		structGet(w, WasmLispCompiler.TYPE_STRING, 0);
		setLocal(w, OFF);
		getLocal(w, PATH);
		refCast(w, WasmLispCompiler.TYPE_STRING);
		structGet(w, WasmLispCompiler.TYPE_STRING, 1);
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
		// param: FD_VAL=0 (ref) ; no locals
		w.write(0);
		// fd_close(i31.get_s(fd_val)) ; drop errno
		getLocal(w, 0);
		refCast(w, Type.I31.code());
		w.write(Instruction.GC_PREFIX, Instruction.I31_GET_S);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_FD_CLOSE);
		w.write(Instruction.DROP);
		// return t
		i32(w, t.offset());
		i32(w, t.length());
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_STRING);
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
		// params: STR=0 (ref), FD_VAL=1 (ref) ; i32 locals: OFF=2, LEN=3, FD=4
		w.write(1);
		w.write(3);
		w.write(Type.I32);
		final int STR = 0, FD_VAL = 1, OFF = 2, LEN = 3, FD = 4;
		final int IOV = WasmLispCompiler.IOV_OFFSET;
		final int NWRITTEN = WasmLispCompiler.NWRITTEN_OFFSET;

		// off = string.offset ; len = string.length
		getLocal(w, STR);
		refCast(w, WasmLispCompiler.TYPE_STRING);
		structGet(w, WasmLispCompiler.TYPE_STRING, 0);
		setLocal(w, OFF);
		getLocal(w, STR);
		refCast(w, WasmLispCompiler.TYPE_STRING);
		structGet(w, WasmLispCompiler.TYPE_STRING, 1);
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
