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
 * {@code [kind=1][slot][len]}: {@code slot} indexes the module-global table of per-stream
 * byte BUFFERS, and {@code len} counts the content bytes written so far. A buffer is a
 * {@code $str_bytes} GC array holding the same quote-framed bytes a string does -- the
 * leading {@code "} at index 0, the content at {@code [1, 1+len)} -- so
 * {@code _str_stream_contents} is one {@code array.copy} out and every append is one
 * {@code array.copy} in, into a buffer that DOUBLES when it runs out
 * ({@code _ostream_room}). Nothing a write produces lives in linear memory: what a stream
 * costs is GC-heap bytes the engine reclaims, and the arena sees only the 12-byte record.
 * An input record is {@code [kind=0][cursor][end]} over a persistent linear COPY of the
 * source string's content bytes (one copy per stream, not per read); {@code _read_line}
 * (and therefore {@code _read}, which loops over it) consumes it line by line.
 *
 * <p>
 * {@code _close} hands an output record's slot back to a free list threaded through the
 * table itself, so a resident reactor opening one stream per request keeps a bounded
 * number of them; the record's 12 bytes stay where the bump allocator put them (they are
 * NOT recycled -- a freed record would be handed out again by the arena reset a host
 * performs between calls, and the two allocators would then alias). A closed record's
 * slot is set to -1, so writing to a closed string stream traps at the table read rather
 * than corrupting whichever stream inherited its slot.
 */
final class WasmStringStreamRuntimeBuilder {

	/** The frame byte a string's -- and a stream buffer's -- content sits between. */
	private static final int QUOTE = 0x22;

	/** Capacity a stream's byte buffer starts at; it doubles from there. */
	private static final int INITIAL_CAPACITY = 16;

	/** Slots the output-buffer table starts with; it doubles from there. */
	private static final int INITIAL_SLOTS = 8;

	private WasmStringStreamRuntimeBuilder() {
	}

	/**
	 * Builds the _write_stream_str(str, stream) function body -- the routing sink of the
	 * print-family optional stream argument and the write-string built-in. Writes the
	 * string content (without the surrounding quotes): a negative handle appends to the
	 * output record's byte buffer, a non-negative i31 handle is a WASI fd written via
	 * fd_write, and anything else (nil, the symbol t) goes to standard output through
	 * _write_str (which keeps the fresh-line tracking). Returns the string.
	 * @return the function body bytes
	 */
	static byte[] buildWriteStreamStrBody() {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		// params: STR=0 (ref), STREAM=1 (ref) ; i32 locals: OFF=2, LEN=3, H=4, REC=5,
		// ISFD=6
		w.write(1);
		w.write(5);
		w.write(Type.I32);
		final int STR = 0, STREAM = 1, OFF = 2, LEN = 3, H = 4, REC = 5, ISFD = 6;
		final int IOV = WasmLispCompiler.IOV_OFFSET;
		final int NWRITTEN = WasmLispCompiler.NWRITTEN_OFFSET;

		// The DESTINATION is decided before the string is staged anywhere: a string
		// output stream copies GC array to GC array and must not touch linear memory at
		// all, while the two fd sinks below share one staging copy at HEAP_PTR (which is
		// never advanced past -- they consume it immediately).
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
		// negative handle: append the content to the output record's buffer
		getLocal(w, H);
		i32(w, 0);
		w.write(Instruction.I32_LT_S);
		w.write(Instruction.IF, 0x40);
		i32(w, 0);
		getLocal(w, H);
		w.write(Instruction.I32_SUB);
		setLocal(w, REC);
		emitContentLength(w, STR, LEN);
		emitAppend(w, REC, LEN, () -> {
			getLocal(w, STR);
			WasmEmitHelper.emitStrBytesArray(w);
		}, () -> i32(w, 1));
		getLocal(w, STR);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
		// non-negative handle: a real WASI fd
		i32(w, 1);
		setLocal(w, ISFD);
		w.write(Instruction.END);
		w.write(Instruction.END);
		// off = HEAP_PTR + 1 ; len = _str_to_mem - 2 (the content, without the quotes)
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
		getLocal(w, ISFD);
		w.write(Instruction.IF, 0x40);
		// fd_write(h, iov(off, len))
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
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_FD_WRITE);
		w.write(Instruction.DROP);
		w.write(Instruction.ELSE);
		// nil or the symbol t: standard output via _write_str (fresh-line tracking)
		getLocal(w, OFF);
		getLocal(w, LEN);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);
		w.write(Instruction.END);
		getLocal(w, STR);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	/**
	 * Builds the _make_str_ostream() function body: bump-allocates an output record
	 * {@code [kind=1][slot][len=0]}, claims a table slot (the free list first, a fresh
	 * one second -- growing the table when it runs out) and puts a quote-framed byte
	 * buffer in it, then returns the negative i31 handle.
	 * @param tableGlobal the module global holding the output-buffer table
	 * @return the function body bytes
	 */
	static byte[] buildMakeOutputStreamBody(int tableGlobal) {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		// no params ; i32 locals: REC=0, SLOT=1, HEAD=2 ; TBL=3, NEWTBL=4 (the table) ;
		// BUF=5 (the stream's bytes)
		w.write(3);
		w.write(3);
		w.write(Type.I32);
		w.write(2);
		w.writeRefType(true, WasmLispCompiler.TYPE_HASH_BUCKETS);
		w.write(1);
		w.writeRefType(true, WasmLispCompiler.TYPE_STR_BYTES);
		final int REC = 0, SLOT = 1, HEAD = 2, TBL = 3, NEWTBL = 4, BUF = 5;

		emitAllocRecord(w, REC);
		// kind = 1 (output), len = 0 (the slot is stored last, once it is known)
		getLocal(w, REC);
		i32(w, 1);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		getLocal(w, REC);
		i32(w, 8);
		w.write(Instruction.I32_ADD);
		i32(w, 0);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		// The table exists from the first stream on, never before it.
		getGlobal(w, tableGlobal);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.IF, 0x40);
		i32(w, INITIAL_SLOTS);
		arrayNewDefault(w, WasmLispCompiler.TYPE_HASH_BUCKETS);
		setGlobal(w, tableGlobal);
		w.write(Instruction.END);
		getGlobal(w, tableGlobal);
		refCast(w, WasmLispCompiler.TYPE_HASH_BUCKETS);
		setLocal(w, TBL);
		// head = the free list (a slot index + 1; 0 is the empty list)
		loadMem32(w, WasmLispCompiler.OSTREAM_FREE_ADDR);
		setLocal(w, HEAD);
		getLocal(w, HEAD);
		w.write(Instruction.IF, 0x40);
		// pop it: the freed slot's own entry holds the rest of the list
		getLocal(w, HEAD);
		i32(w, 1);
		w.write(Instruction.I32_SUB);
		setLocal(w, SLOT);
		i32(w, WasmLispCompiler.OSTREAM_FREE_ADDR);
		getLocal(w, TBL);
		getLocal(w, SLOT);
		arrayGet(w, WasmLispCompiler.TYPE_HASH_BUCKETS);
		refCast(w, Type.I31.code());
		w.write(Instruction.GC_PREFIX, Instruction.I31_GET_S);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		w.write(Instruction.ELSE);
		// a fresh slot off the high-water mark
		loadMem32(w, WasmLispCompiler.OSTREAM_SLOT_ADDR);
		setLocal(w, SLOT);
		i32(w, WasmLispCompiler.OSTREAM_SLOT_ADDR);
		getLocal(w, SLOT);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		// ... doubling the table when the fresh slot is past its end
		getLocal(w, SLOT);
		getLocal(w, TBL);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_LEN);
		w.write(Instruction.I32_GE_U);
		w.write(Instruction.IF, 0x40);
		getLocal(w, TBL);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_LEN);
		i32(w, 1);
		w.write(Instruction.I32_SHL);
		arrayNewDefault(w, WasmLispCompiler.TYPE_HASH_BUCKETS);
		setLocal(w, NEWTBL);
		getLocal(w, NEWTBL);
		i32(w, 0);
		getLocal(w, TBL);
		i32(w, 0);
		getLocal(w, TBL);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_LEN);
		arrayCopy(w, WasmLispCompiler.TYPE_HASH_BUCKETS, WasmLispCompiler.TYPE_HASH_BUCKETS);
		getLocal(w, NEWTBL);
		setGlobal(w, tableGlobal);
		getLocal(w, NEWTBL);
		setLocal(w, TBL);
		w.write(Instruction.END);
		w.write(Instruction.END);
		// table[slot] = a fresh buffer carrying the leading frame quote
		i32(w, INITIAL_CAPACITY);
		arrayNewDefault(w, WasmLispCompiler.TYPE_STR_BYTES);
		setLocal(w, BUF);
		getLocal(w, BUF);
		i32(w, 0);
		i32(w, QUOTE);
		arraySet(w, WasmLispCompiler.TYPE_STR_BYTES);
		getLocal(w, TBL);
		getLocal(w, SLOT);
		getLocal(w, BUF);
		arraySet(w, WasmLispCompiler.TYPE_HASH_BUCKETS);
		// rec.slot = slot
		getLocal(w, REC);
		i32(w, 4);
		w.write(Instruction.I32_ADD);
		getLocal(w, SLOT);
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
	 * Builds the _str_stream_contents(stream) function body: copies the output record's
	 * buffer into one fresh quote-framed heap string and CLEARS the record (CL's
	 * {@code get-output-stream-string} answers what was accumulated AND empties the
	 * stream, so a second call sees only what was written after the first).
	 * @return the function body bytes
	 */
	static byte[] buildContentsBody() {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		// param: STREAM=0 (ref) ; i32 locals: REC=1, LEN=2, ID=3 ; BUF=4, OUT=5
		w.write(2);
		w.write(3);
		w.write(Type.I32);
		w.write(2);
		w.writeRefType(true, WasmLispCompiler.TYPE_STR_BYTES);
		final int STREAM = 0, REC = 1, LEN = 2, ID = 3, BUF = 4, OUT = 5;

		// rec = -i31.get_s(stream) ; len = rec.len
		i32(w, 0);
		getLocal(w, STREAM);
		refCast(w, Type.I31.code());
		w.write(Instruction.GC_PREFIX, Instruction.I31_GET_S);
		w.write(Instruction.I32_SUB);
		setLocal(w, REC);
		loadRecLen(w, REC);
		setLocal(w, LEN);
		// The buffer, asked for room for nothing -- which is room for the CLOSING quote
		// (_ostream_room's reserve is len + n + 2, the two frame quotes included), so
		// the result is the buffer's first len + 2 bytes verbatim.
		getLocal(w, REC);
		i32(w, 0);
		emitRoomCall(w);
		refCast(w, WasmLispCompiler.TYPE_STR_BYTES);
		setLocal(w, BUF);
		getLocal(w, BUF);
		getLocal(w, LEN);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		i32(w, QUOTE);
		arraySet(w, WasmLispCompiler.TYPE_STR_BYTES);
		getLocal(w, LEN);
		i32(w, 2);
		w.write(Instruction.I32_ADD);
		arrayNewDefault(w, WasmLispCompiler.TYPE_STR_BYTES);
		setLocal(w, OUT);
		getLocal(w, OUT);
		i32(w, 0);
		getLocal(w, BUF);
		i32(w, 0);
		getLocal(w, LEN);
		i32(w, 2);
		w.write(Instruction.I32_ADD);
		arrayCopy(w, WasmLispCompiler.TYPE_STR_BYTES, WasmLispCompiler.TYPE_STR_BYTES);
		// CLEAR the record; the buffer stays, so the writes after this one reuse it.
		getLocal(w, REC);
		i32(w, 8);
		w.write(Instruction.I32_ADD);
		i32(w, 0);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		// id = STRING_ID_CTR++ -- the contents is a runtime string, so it gets a fresh
		// counter id (what _str_fresh would have stamped, without its linear detour).
		loadMem32(w, WasmLispCompiler.STRING_ID_CTR_ADDR);
		setLocal(w, ID);
		i32(w, WasmLispCompiler.STRING_ID_CTR_ADDR);
		getLocal(w, ID);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		// return struct.new TYPE_STRING (id, len + 2, out, ci = 0, cb = 1)
		getLocal(w, ID);
		getLocal(w, LEN);
		i32(w, 2);
		w.write(Instruction.I32_ADD);
		getLocal(w, OUT);
		i32(w, 0);
		i32(w, 1);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_STRING);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	/**
	 * Builds the {@code _ostream_room(rec, n)} function body: answers the output record's
	 * byte buffer, guaranteed to hold {@code rec.len + n + 2} bytes -- the content
	 * written so far, {@code n} bytes about to be appended, and the two frame quotes.
	 * Doubling from {@link #INITIAL_CAPACITY}, so appending {@code k} bytes one at a time
	 * copies {@code O(k)} bytes in total rather than allocating per write.
	 * @param tableGlobal the module global holding the output-buffer table
	 * @return the function body bytes (signature {@code (i32,i32)->(ref null eq)},
	 * TYPE_RAT_NEW)
	 */
	static byte[] buildOstreamRoomBody(int tableGlobal) {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		// params: REC=0, N=1 ; i32 locals: NEED=2, CAP=3 ; BUF=4, NEW=5
		w.write(2);
		w.write(2);
		w.write(Type.I32);
		w.write(2);
		w.writeRefType(true, WasmLispCompiler.TYPE_STR_BYTES);
		final int REC = 0, N = 1, NEED = 2, CAP = 3, BUF = 4, NEW = 5;

		// buf = table[rec.slot] (a closed record's slot is -1, and traps here)
		emitTable(w, tableGlobal);
		loadRecSlot(w, REC);
		arrayGet(w, WasmLispCompiler.TYPE_HASH_BUCKETS);
		refCast(w, WasmLispCompiler.TYPE_STR_BYTES);
		setLocal(w, BUF);
		// need = rec.len + n + 2
		loadRecLen(w, REC);
		getLocal(w, N);
		w.write(Instruction.I32_ADD);
		i32(w, 2);
		w.write(Instruction.I32_ADD);
		setLocal(w, NEED);
		getLocal(w, BUF);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_LEN);
		getLocal(w, NEED);
		w.write(Instruction.I32_LT_U);
		w.write(Instruction.IF, 0x40);
		// cap = the smallest power of two >= max(INITIAL_CAPACITY, need); because the
		// old capacity is one too, that is always at least a doubling.
		i32(w, INITIAL_CAPACITY);
		setLocal(w, CAP);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		getLocal(w, CAP);
		getLocal(w, NEED);
		w.write(Instruction.I32_GE_U);
		w.write(Instruction.BR_IF, 1);
		getLocal(w, CAP);
		i32(w, 1);
		w.write(Instruction.I32_SHL);
		setLocal(w, CAP);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END);
		w.write(Instruction.END);
		getLocal(w, CAP);
		arrayNewDefault(w, WasmLispCompiler.TYPE_STR_BYTES);
		setLocal(w, NEW);
		// carry over the leading quote plus the content written so far
		getLocal(w, NEW);
		i32(w, 0);
		getLocal(w, BUF);
		i32(w, 0);
		loadRecLen(w, REC);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		arrayCopy(w, WasmLispCompiler.TYPE_STR_BYTES, WasmLispCompiler.TYPE_STR_BYTES);
		emitTable(w, tableGlobal);
		loadRecSlot(w, REC);
		getLocal(w, NEW);
		arraySet(w, WasmLispCompiler.TYPE_HASH_BUCKETS);
		getLocal(w, NEW);
		setLocal(w, BUF);
		w.write(Instruction.END);
		getLocal(w, BUF);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	/**
	 * Emits the append of {@code count} bytes of a {@code $str_bytes} array to an output
	 * record's buffer. Also used by the {@code _write_line} string-stream branch.
	 * @param w the writer for the function body being emitted
	 * @param recLocal the i32 local holding the record address
	 * @param countLocal the i32 local holding the byte count (read twice, so a local)
	 * @param pushSrc emits the source {@code $str_bytes} array
	 * @param pushSrcFrom emits the source byte offset
	 */
	static void emitAppend(WasmWriter w, int recLocal, int countLocal, Runnable pushSrc, Runnable pushSrcFrom) {
		getLocal(w, recLocal);
		getLocal(w, countLocal);
		emitRoomCall(w);
		refCast(w, WasmLispCompiler.TYPE_STR_BYTES);
		i32(w, 1);
		loadRecLen(w, recLocal);
		w.write(Instruction.I32_ADD);
		pushSrc.run();
		pushSrcFrom.run();
		getLocal(w, countLocal);
		arrayCopy(w, WasmLispCompiler.TYPE_STR_BYTES, WasmLispCompiler.TYPE_STR_BYTES);
		emitAdvanceLen(w, recLocal, () -> getLocal(w, countLocal));
	}

	/**
	 * Emits the append of ONE literal byte to an output record's buffer -- the newline a
	 * {@code write-line} / {@code fresh-line} owes the stream.
	 * @param w the writer for the function body being emitted
	 * @param recLocal the i32 local holding the record address
	 * @param value the byte to append
	 */
	static void emitAppendByte(WasmWriter w, int recLocal, int value) {
		getLocal(w, recLocal);
		i32(w, 1);
		emitRoomCall(w);
		refCast(w, WasmLispCompiler.TYPE_STR_BYTES);
		i32(w, 1);
		loadRecLen(w, recLocal);
		w.write(Instruction.I32_ADD);
		i32(w, value);
		arraySet(w, WasmLispCompiler.TYPE_STR_BYTES);
		emitAdvanceLen(w, recLocal, () -> i32(w, 1));
	}

	/**
	 * Emits {@code _close}'s output-record branch: hands the record's table slot back to
	 * the free list (threaded through the table's own entries as i31s) so a resident
	 * reactor's stream count stays bounded, and marks the record closed -- kind 2, slot
	 * -1, so a double close is a no-op and a write after one traps at the table read
	 * instead of landing in whichever stream inherited the slot.
	 * @param w the writer for the function body being emitted
	 * @param recLocal the i32 local holding the record address (the handle, negated)
	 * @param slotLocal a free i32 local for the record's slot
	 * @param tableGlobal the module global holding the output-buffer table
	 */
	static void emitCloseOutputRecord(WasmWriter w, int recLocal, int slotLocal, int tableGlobal) {
		getLocal(w, recLocal);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		i32(w, 1);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.IF, 0x40);
		loadRecSlot(w, recLocal);
		setLocal(w, slotLocal);
		// table[slot] = the rest of the free list ; the free list = this slot
		emitTable(w, tableGlobal);
		getLocal(w, slotLocal);
		loadMem32(w, WasmLispCompiler.OSTREAM_FREE_ADDR);
		w.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		arraySet(w, WasmLispCompiler.TYPE_HASH_BUCKETS);
		i32(w, WasmLispCompiler.OSTREAM_FREE_ADDR);
		getLocal(w, slotLocal);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		getLocal(w, recLocal);
		i32(w, 2);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		getLocal(w, recLocal);
		i32(w, 4);
		w.write(Instruction.I32_ADD);
		i32(w, -1);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		w.write(Instruction.END);
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
	 * Builds the {@code _fresh_line_stream(dest)} body: fresh-line for an explicit or
	 * redirected destination. nil / the symbol t is standard output (the
	 * {@code LINE_START_ADDR} tracking); a negative i31 handle (a string-stream record)
	 * appends a newline only when the last byte written is not one (an empty record is at
	 * a line start); a non-negative i31 handle (a WASI fd) always writes a newline -- its
	 * column is unknown (the same rule on every backend). Returns nil.
	 * @param newlineOff the linear-memory offset of the static {@code "\n"} literal
	 * @return the function body bytes
	 */
	static byte[] buildFreshLineStreamBody(int newlineOff) {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		// params: DEST=0 (ref) ; i32 locals: H=1, REC=2, LEN=3
		w.write(1);
		w.write(3);
		w.write(Type.I32);
		final int DEST = 0, H = 1, REC = 2, LEN = 3;
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
		loadRecLen(w, REC);
		setLocal(w, LEN);
		// A record nothing was written to is AT a line start; otherwise the last content
		// byte sits at buffer index len (index 0 is the leading frame quote).
		getLocal(w, LEN);
		w.write(Instruction.IF, 0x40);
		getLocal(w, REC);
		i32(w, 0);
		emitRoomCall(w);
		refCast(w, WasmLispCompiler.TYPE_STR_BYTES);
		getLocal(w, LEN);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_GET_U);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_STR_BYTES);
		i32(w, 10);
		w.write(Instruction.I32_NE);
		w.write(Instruction.IF, 0x40);
		emitAppendByte(w, REC, 10);
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
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_FD_WRITE);
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
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);
		w.write(Instruction.END);
		w.write(Instruction.REF_NULL);
		w.writeHeapType(Type.EQ.code());
		w.write(Instruction.END);
		return body.toByteArray();
	}

	// === low-level emit helpers ===

	/** Emits the CONTENT byte count of a quote-framed string into {@code lenLocal}. */
	private static void emitContentLength(WasmWriter w, int strLocal, int lenLocal) {
		getLocal(w, strLocal);
		WasmEmitHelper.emitStrBytesArray(w);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_LEN);
		i32(w, 2);
		w.write(Instruction.I32_SUB);
		setLocal(w, lenLocal);
	}

	/** Emits {@code rec.len += <delta>}. */
	private static void emitAdvanceLen(WasmWriter w, int recLocal, Runnable pushDelta) {
		getLocal(w, recLocal);
		i32(w, 8);
		w.write(Instruction.I32_ADD);
		loadRecLen(w, recLocal);
		pushDelta.run();
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
	}

	/** Pushes the output-buffer table, cast to its array type. */
	private static void emitTable(WasmWriter w, int tableGlobal) {
		getGlobal(w, tableGlobal);
		refCast(w, WasmLispCompiler.TYPE_HASH_BUCKETS);
	}

	private static void emitRoomCall(WasmWriter w) {
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_OSTREAM_ROOM);
	}

	/** Pushes {@code rec.slot} (output record field 1). */
	private static void loadRecSlot(WasmWriter w, int recLocal) {
		getLocal(w, recLocal);
		i32(w, 4);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
	}

	/** Pushes {@code rec.len} (output record field 2). */
	private static void loadRecLen(WasmWriter w, int recLocal) {
		getLocal(w, recLocal);
		i32(w, 8);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
	}

	private static void getLocal(WasmWriter w, int slot) {
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(slot);
	}

	private static void setLocal(WasmWriter w, int slot) {
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(slot);
	}

	private static void getGlobal(WasmWriter w, int index) {
		w.write(Instruction.GET_GLOBAL);
		w.writeUnsignedLeb128(index);
	}

	private static void setGlobal(WasmWriter w, int index) {
		w.write(Instruction.SET_GLOBAL);
		w.writeUnsignedLeb128(index);
	}

	private static void i32(WasmWriter w, int value) {
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(value);
	}

	private static void arrayGet(WasmWriter w, int type) {
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_GET);
		w.writeUnsignedLeb128(type);
	}

	private static void arraySet(WasmWriter w, int type) {
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_SET);
		w.writeUnsignedLeb128(type);
	}

	private static void arrayNewDefault(WasmWriter w, int type) {
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_NEW_DEFAULT);
		w.writeUnsignedLeb128(type);
	}

	private static void arrayCopy(WasmWriter w, int dstType, int srcType) {
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_COPY);
		w.writeUnsignedLeb128(dstType);
		w.writeUnsignedLeb128(srcType);
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
