package am.ik.rontolisp.codegen.wasm;

import java.io.ByteArrayOutputStream;

import am.ik.wasm.Instruction;
import am.ik.wasm.Type;
import am.ik.wasm.WasmWriter;

/**
 * Builds the WASM bodies of the string-stream runtime behind
 * {@code with-output-to-string} / {@code with-input-from-string} (and the print-family
 * optional stream argument).
 *
 * <p>
 * A string stream is a NEGATIVE i31 handle whose absolute value is the address of a
 * 12-byte record bump-allocated in linear memory -- a real WASI file descriptor is never
 * negative, so every stream-taking built-in can dispatch on the sign. An output record is
 * {@code [kind=1][head][tail]} heading a linked list of {@code [off][len][next]} chunks;
 * since a string's bytes now live on the GC heap (no stable linear address), appending
 * COPIES the content into a persistent linear buffer and the chunk references that copy,
 * so {@code _str_stream_contents} concatenates the chunks into one fresh heap string. An
 * input record is {@code [kind=0][cursor][end]} over a persistent linear COPY of the
 * source string's content bytes; {@code _read_line} (and therefore {@code _read}, which
 * loops over it) consumes it line by line. (These per-stream copies + records are
 * bump-allocated and not reclaimed -- a string stream is short-lived and comparatively
 * rare, unlike the reclaimable runtime strings the GC-array representation targets.)
 */
final class WasmStringStreamRuntimeBuilder {

	private WasmStringStreamRuntimeBuilder() {
	}

	/**
	 * Builds the _write_stream_str(str, stream) function body -- the routing sink of the
	 * print-family optional stream argument and the write-string built-in. Writes the
	 * string content (without the surrounding quotes): a negative handle appends a chunk
	 * to the output record, a non-negative i31 handle is a WASI fd written via fd_write,
	 * and anything else (nil, the symbol t) goes to standard output through _write_str
	 * (which keeps the fresh-line tracking). Returns the string.
	 * @return the function body bytes
	 */
	static byte[] buildWriteStreamStrBody() {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		// params: STR=0 (ref), STREAM=1 (ref) ; i32 locals: OFF=2, LEN=3, H=4, REC=5,
		// CHUNK=6, TAIL=7
		w.write(1);
		w.write(6);
		w.write(Type.I32);
		final int STR = 0, STREAM = 1, OFF = 2, LEN = 3, H = 4, REC = 5, CHUNK = 6, TAIL = 7;
		final int IOV = WasmLispCompiler.IOV_OFFSET;
		final int NWRITTEN = WasmLispCompiler.NWRITTEN_OFFSET;

		// The string's bytes live on the GC heap: copy them into linear scratch at
		// HEAP_PTR
		// so OFF/LEN name a real linear range (the content, without the quotes). HEAP_PTR
		// is
		// NOT advanced yet -- the fd_write / stdout branches consume the copy
		// immediately,
		// while the string-output-stream branch PERSISTS it (advances HEAP_PTR) before
		// linking a chunk that references it. off = HEAP_PTR + 1 ; len = _str_to_mem - 2.
		loadMem32(w, WasmLispCompiler.HEAP_PTR_ADDR);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		setLocal(w, OFF);
		getLocal(w, STR);
		loadMem32(w, WasmLispCompiler.HEAP_PTR_ADDR);
		WasmEmitHelper.emitStrToMemCall(w);
		i32(w, 2);
		w.write(Instruction.I32_SUB);
		setLocal(w, LEN);
		// if (stream is a non-null i31)
		getLocal(w, STREAM);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF, 0x40);
		getLocal(w, STREAM);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(Type.I31.code());
		w.write(Instruction.IF, 0x40);
		getLocal(w, STREAM);
		refCast(w, Type.I31.code());
		w.write(Instruction.GC_PREFIX, Instruction.I31_GET_S);
		setLocal(w, H);
		// negative handle: append a chunk to the output record
		getLocal(w, H);
		i32(w, 0);
		w.write(Instruction.I32_LT_S);
		w.write(Instruction.IF, 0x40);
		i32(w, 0);
		getLocal(w, H);
		w.write(Instruction.I32_SUB);
		setLocal(w, REC);
		// Persist the content copy: HEAP_PTR = OFF + LEN + 1 (past the copy's closing
		// quote position), so the chunk's referenced bytes survive the next write.
		i32(w, WasmLispCompiler.HEAP_PTR_ADDR);
		getLocal(w, OFF);
		getLocal(w, LEN);
		w.write(Instruction.I32_ADD);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		emitAppendChunk(w, REC, CHUNK, TAIL, () -> getLocal(w, OFF), () -> getLocal(w, LEN));
		getLocal(w, STR);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
		// non-negative handle: a real WASI fd -- fd_write(h, iov(off, len))
		i32(w, IOV);
		getLocal(w, OFF);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		i32(w, IOV + 4);
		getLocal(w, LEN);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		getLocal(w, H);
		i32(w, IOV);
		i32(w, 1);
		i32(w, NWRITTEN);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_FD_WRITE);
		w.write(Instruction.DROP);
		getLocal(w, STR);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
		w.write(Instruction.END);
		// nil or the symbol t: standard output via _write_str (fresh-line tracking)
		getLocal(w, OFF);
		getLocal(w, LEN);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);
		getLocal(w, STR);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	/**
	 * Builds the _make_str_ostream() function body: bump-allocates an output record
	 * {@code [kind=1][head=0][tail=0]} and returns the negative i31 handle.
	 * @return the function body bytes
	 */
	static byte[] buildMakeOutputStreamBody() {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		// no params ; i32 local: REC=0
		w.write(1);
		w.write(1);
		w.write(Type.I32);
		final int REC = 0;

		emitAllocRecord(w, REC);
		// kind = 1 (output), head = 0, tail = 0
		getLocal(w, REC);
		i32(w, 1);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		getLocal(w, REC);
		i32(w, 4);
		w.write(Instruction.I32_ADD);
		i32(w, 0);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		getLocal(w, REC);
		i32(w, 8);
		w.write(Instruction.I32_ADD);
		i32(w, 0);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		emitReturnHandle(w, REC);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	/**
	 * Builds the _make_str_istream(str) function body: bump-allocates an input record
	 * {@code [kind=0][cursor][end]} over the string's content bytes (without the
	 * surrounding quotes) and returns the negative i31 handle.
	 * @return the function body bytes
	 */
	static byte[] buildMakeInputStreamBody() {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		// param: STR=0 (ref) ; i32 locals: REC=1, OFF=2, LEN=3
		w.write(1);
		w.write(3);
		w.write(Type.I32);
		final int STR = 0, REC = 1, OFF = 2, LEN = 3;

		// The source string's bytes live on the GC heap: copy them into a PERSISTENT
		// linear
		// buffer at HEAP_PTR (the input record's cursor/end are read incrementally over
		// the
		// stream's lifetime, so the bytes must outlive this call), then point cursor/end
		// into the copy. off = HEAP_PTR ; len = _str_to_mem(str, off) ; HEAP_PTR = off +
		// len.
		loadMem32(w, WasmLispCompiler.HEAP_PTR_ADDR);
		setLocal(w, OFF);
		getLocal(w, STR);
		getLocal(w, OFF);
		WasmEmitHelper.emitStrToMemCall(w);
		setLocal(w, LEN);
		i32(w, WasmLispCompiler.HEAP_PTR_ADDR);
		getLocal(w, OFF);
		getLocal(w, LEN);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		emitAllocRecord(w, REC);
		// kind = 0 (input), cursor = off + 1, end = off + len - 1
		getLocal(w, REC);
		i32(w, 0);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		getLocal(w, REC);
		i32(w, 4);
		w.write(Instruction.I32_ADD);
		getLocal(w, OFF);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		getLocal(w, REC);
		i32(w, 8);
		w.write(Instruction.I32_ADD);
		getLocal(w, OFF);
		getLocal(w, LEN);
		w.write(Instruction.I32_ADD);
		i32(w, 1);
		w.write(Instruction.I32_SUB);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		emitReturnHandle(w, REC);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	/**
	 * Builds the _str_stream_contents(stream) function body: concatenates the output
	 * record's chunks into one fresh quote-framed heap string.
	 * @return the function body bytes
	 */
	static byte[] buildContentsBody() {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		// param: STREAM=0 (ref) ; i32 locals: REC=1, TOTAL=2, P=3, START=4, CUR=5, I=6
		w.write(1);
		w.write(6);
		w.write(Type.I32);
		final int STREAM = 0, REC = 1, TOTAL = 2, P = 3, START = 4, CUR = 5, I = 6;

		// rec = -i31.get_s(stream)
		i32(w, 0);
		getLocal(w, STREAM);
		refCast(w, Type.I31.code());
		w.write(Instruction.GC_PREFIX, Instruction.I31_GET_S);
		w.write(Instruction.I32_SUB);
		setLocal(w, REC);
		// total = 2 (the surrounding quotes); p = head
		i32(w, 2);
		setLocal(w, TOTAL);
		getLocal(w, REC);
		i32(w, 4);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		setLocal(w, P);
		// while (p != 0) { total += chunk.len; p = chunk.next; }
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		getLocal(w, P);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.BR_IF, 1);
		getLocal(w, TOTAL);
		getLocal(w, P);
		i32(w, 4);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		w.write(Instruction.I32_ADD);
		setLocal(w, TOTAL);
		getLocal(w, P);
		i32(w, 8);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		setLocal(w, P);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END);
		w.write(Instruction.END);
		// start = heap ; grow(start + total) ; heap = start + total
		loadMem32(w, WasmLispCompiler.HEAP_PTR_ADDR);
		setLocal(w, START);
		WasmEmitHelper.emitGrowHeapTo(w, () -> {
			getLocal(w, START);
			getLocal(w, TOTAL);
			w.write(Instruction.I32_ADD);
		});
		// HEAP_PTR is NOT advanced (a stack pop): _str_fresh copies the concatenated
		// result
		// into a fresh GC array below, so the assembly scratch at `start` is reused.
		// memory[start] = '"' ; cur = start + 1
		getLocal(w, START);
		i32(w, 0x22);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		getLocal(w, START);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		setLocal(w, CUR);
		// for each chunk: copy its bytes at cur
		getLocal(w, REC);
		i32(w, 4);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		setLocal(w, P);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		getLocal(w, P);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.BR_IF, 1);
		// i = 0 ; while (i < chunk.len) { memory[cur + i] = memory[chunk.off + i]; i++ }
		i32(w, 0);
		setLocal(w, I);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		getLocal(w, I);
		getLocal(w, P);
		i32(w, 4);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		w.write(Instruction.I32_GE_U);
		w.write(Instruction.BR_IF, 1);
		getLocal(w, CUR);
		getLocal(w, I);
		w.write(Instruction.I32_ADD);
		getLocal(w, P);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		getLocal(w, I);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		getLocal(w, I);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		setLocal(w, I);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END);
		w.write(Instruction.END);
		// cur += chunk.len ; p = chunk.next
		getLocal(w, CUR);
		getLocal(w, P);
		i32(w, 4);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		w.write(Instruction.I32_ADD);
		setLocal(w, CUR);
		getLocal(w, P);
		i32(w, 8);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		setLocal(w, P);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END);
		w.write(Instruction.END);
		// memory[cur] = '"'
		getLocal(w, CUR);
		i32(w, 0x22);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		// CLEAR the record (head = tail = 0): CL's get-output-stream-string answers what
		// was accumulated AND empties the stream, so a second call sees only what was
		// written after the first. The chunk bytes stay where the bump allocator put
		// them -- nothing references them once the chain head is gone.
		getLocal(w, REC);
		i32(w, 4);
		w.write(Instruction.I32_ADD);
		i32(w, 0);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		getLocal(w, REC);
		i32(w, 8);
		w.write(Instruction.I32_ADD);
		i32(w, 0);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		// return _str_fresh(start, total) -- the contents is a runtime string, so it
		// gets a fresh counter id.
		getLocal(w, START);
		getLocal(w, TOTAL);
		WasmEmitHelper.emitStrFreshCall(w);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	/**
	 * Emits {@code rec = heap; grow(rec + 12); heap = rec + 12} -- the shared 12-byte
	 * record allocation.
	 */
	private static void emitAllocRecord(WasmWriter w, int recLocal) {
		loadMem32(w, WasmLispCompiler.HEAP_PTR_ADDR);
		setLocal(w, recLocal);
		WasmEmitHelper.emitGrowHeapTo(w, () -> {
			getLocal(w, recLocal);
			i32(w, 12);
			w.write(Instruction.I32_ADD);
		});
		i32(w, WasmLispCompiler.HEAP_PTR_ADDR);
		getLocal(w, recLocal);
		i32(w, 12);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
	}

	/** Emits {@code return ref.i31(0 - rec)} -- the negative string-stream handle. */
	private static void emitReturnHandle(WasmWriter w, int recLocal) {
		i32(w, 0);
		getLocal(w, recLocal);
		w.write(Instruction.I32_SUB);
		w.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
	}

	/**
	 * Emits the chunk append: bump-allocates a 12-byte {@code [off][len][next=0]} chunk
	 * referencing the given bytes and links it at the output record's tail. Also used by
	 * the {@code _write_line} string-stream branch.
	 * @param w the writer for the function body being emitted
	 * @param recLocal the i32 local holding the record address
	 * @param chunkLocal a free i32 local for the new chunk address
	 * @param tailLocal a free i32 local for the current tail address
	 * @param pushOff emits the i32 byte offset of the chunk content
	 * @param pushLen emits the i32 byte length of the chunk content
	 */
	static void emitAppendChunk(WasmWriter w, int recLocal, int chunkLocal, int tailLocal, Runnable pushOff,
			Runnable pushLen) {
		// chunk = heap ; grow(chunk + 12) ; heap = chunk + 12
		loadMem32(w, WasmLispCompiler.HEAP_PTR_ADDR);
		setLocal(w, chunkLocal);
		WasmEmitHelper.emitGrowHeapTo(w, () -> {
			getLocal(w, chunkLocal);
			i32(w, 12);
			w.write(Instruction.I32_ADD);
		});
		i32(w, WasmLispCompiler.HEAP_PTR_ADDR);
		getLocal(w, chunkLocal);
		i32(w, 12);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		// chunk.off = off ; chunk.len = len ; chunk.next = 0
		getLocal(w, chunkLocal);
		pushOff.run();
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		getLocal(w, chunkLocal);
		i32(w, 4);
		w.write(Instruction.I32_ADD);
		pushLen.run();
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		getLocal(w, chunkLocal);
		i32(w, 8);
		w.write(Instruction.I32_ADD);
		i32(w, 0);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		// tail = rec.tail ; (tail == 0 ? rec.head : tail.next) = chunk ; rec.tail = chunk
		getLocal(w, recLocal);
		i32(w, 8);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		setLocal(w, tailLocal);
		getLocal(w, tailLocal);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF, 0x40);
		getLocal(w, recLocal);
		i32(w, 4);
		w.write(Instruction.I32_ADD);
		getLocal(w, chunkLocal);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		w.write(Instruction.ELSE);
		getLocal(w, tailLocal);
		i32(w, 8);
		w.write(Instruction.I32_ADD);
		getLocal(w, chunkLocal);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		w.write(Instruction.END);
		getLocal(w, recLocal);
		i32(w, 8);
		w.write(Instruction.I32_ADD);
		getLocal(w, chunkLocal);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
	}

	/**
	 * Builds the {@code _fresh_line_stream(dest)} body: fresh-line for an explicit or
	 * redirected destination. nil / the symbol t is standard output (the
	 * {@code LINE_START_ADDR} tracking); a negative i31 handle (a string-stream record)
	 * writes a newline chunk only when the last written byte is not one, walking the
	 * chunk chain so empty writes are skipped (an empty record is at a line start); a
	 * non-negative i31 handle (a WASI fd) always writes a newline -- its column is
	 * unknown (the same rule on every backend). Returns nil.
	 * @param newlineOff the linear-memory offset of the static {@code "\n"} literal
	 * @return the function body bytes
	 */
	static byte[] buildFreshLineStreamBody(int newlineOff) {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		// params: DEST=0 (ref) ; i32 locals: H=1, REC=2, CUR=3, LASTB=4, CHUNK=5, TAIL=6
		w.write(1);
		w.write(6);
		w.write(Type.I32);
		final int DEST = 0, H = 1, REC = 2, CUR = 3, LASTB = 4, CHUNK = 5, TAIL = 6;
		final int IOV = WasmLispCompiler.IOV_OFFSET;
		final int NWRITTEN = WasmLispCompiler.NWRITTEN_OFFSET;
		// if (dest is a non-null i31)
		getLocal(w, DEST);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF, 0x40);
		getLocal(w, DEST);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(Type.I31.code());
		w.write(Instruction.IF, 0x40);
		getLocal(w, DEST);
		refCast(w, Type.I31.code());
		w.write(Instruction.GC_PREFIX, Instruction.I31_GET_S);
		setLocal(w, H);
		// negative handle: the string-stream record
		getLocal(w, H);
		i32(w, 0);
		w.write(Instruction.I32_LT_S);
		w.write(Instruction.IF, 0x40);
		i32(w, 0);
		getLocal(w, H);
		w.write(Instruction.I32_SUB);
		setLocal(w, REC);
		// Walk the chunk chain for the last byte of the last non-empty chunk.
		getLocal(w, REC);
		i32(w, 4);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		setLocal(w, CUR);
		i32(w, -1);
		setLocal(w, LASTB);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		getLocal(w, CUR);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.BR_IF);
		w.writeSignedLeb128(1);
		// len = chunk.len ; if (len != 0) LASTB = mem8[chunk.off + len - 1]
		getLocal(w, CUR);
		i32(w, 4);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		setLocal(w, TAIL);
		getLocal(w, TAIL);
		w.write(Instruction.IF, 0x40);
		getLocal(w, CUR);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		getLocal(w, TAIL);
		w.write(Instruction.I32_ADD);
		i32(w, 1);
		w.write(Instruction.I32_SUB);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);
		setLocal(w, LASTB);
		w.write(Instruction.END);
		// cur = chunk.next
		getLocal(w, CUR);
		i32(w, 8);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		setLocal(w, CUR);
		w.write(Instruction.BR);
		w.writeSignedLeb128(0);
		w.write(Instruction.END);
		w.write(Instruction.END);
		// if (LASTB >= 0 && LASTB != '\n') append a "\n" chunk
		getLocal(w, LASTB);
		i32(w, 0);
		w.write(Instruction.I32_GE_S);
		w.write(Instruction.IF, 0x40);
		getLocal(w, LASTB);
		i32(w, 10);
		w.write(Instruction.I32_NE);
		w.write(Instruction.IF, 0x40);
		emitAppendChunk(w, REC, CHUNK, TAIL, () -> i32(w, newlineOff), () -> i32(w, 1));
		w.write(Instruction.END);
		w.write(Instruction.END);
		w.write(Instruction.REF_NULL);
		w.writeHeapType(Type.EQ.code());
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
		// non-negative handle: a real WASI fd -- its column is unknown, always newline
		i32(w, IOV);
		i32(w, newlineOff);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		i32(w, IOV + 4);
		i32(w, 1);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		getLocal(w, H);
		i32(w, IOV);
		i32(w, 1);
		i32(w, NWRITTEN);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_FD_WRITE);
		w.write(Instruction.DROP);
		w.write(Instruction.REF_NULL);
		w.writeHeapType(Type.EQ.code());
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
		w.write(Instruction.END);
		// nil or the symbol t: standard output via the LINE_START tracking
		i32(w, WasmLispCompiler.LINE_START_ADDR);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		w.write(Instruction.IF, 0x40);
		i32(w, newlineOff);
		i32(w, 1);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);
		w.write(Instruction.END);
		w.write(Instruction.REF_NULL);
		w.writeHeapType(Type.EQ.code());
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
