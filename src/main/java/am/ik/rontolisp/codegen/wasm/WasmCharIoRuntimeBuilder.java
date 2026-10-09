package am.ik.rontolisp.codegen.wasm;

import java.io.ByteArrayOutputStream;

import am.ik.wasm.Instruction;
import am.ik.wasm.Type;
import am.ik.wasm.WasmWriter;

/**
 * Builds the {@code _read_seq_chars} runtime helper body -- the bulk CHARACTER transfer
 * behind {@code read-sequence} over a character buffer on the wasm-GC backends
 * ({@code .kb/character-sequence-io.md}).
 *
 * <p>
 * {@code _read_seq_chars(seq, stream, start, end) -> value} fills code points
 * {@code [start, end)} of a mutable character vector off a WASI fd and answers the fill
 * position as an i31. Each round asks {@code fd_read} for exactly as many BYTES as there
 * are code points still wanted, staged in up to 64 KiB at {@code HEAP_PTR} (reserved for
 * the call and popped after it, the {@code _open} discipline). That can never overshoot
 * by more than one byte: a character is one to four bytes, so N bytes hold at most N of
 * them, and the buffer fills before the bytes run out only when every one of them was a
 * character of its own. Each character goes through {@link WasmUtf8StreamDecoder}, so
 * malformed input decodes as {@code _read_char} decodes it. A sequence SPLIT by the end
 * of the block is completed one byte at a time into the four spare bytes the block is
 * over-allocated by; the byte that shows such a sequence malformed starts the next
 * character, and when the buffer is full by then it goes into the fd's pushback -- the
 * one byte this can read past what it consumes. Bytes the pushback already holds (a peek,
 * or such a byte) are read first, through {@code _read_char}.
 *
 * <p>
 * It answers {@code null} -- "declined" -- for a buffer that is not a rank-1 character
 * vector holding its own elements (a string view, a displaced view and an immutable
 * string all decline) or a stream that is not a WASI fd (a negative i31 is a string
 * stream), which sends the expansion down its per-character loop exactly as before.
 * {@code start} / {@code end} are i31s or nil (0 / the buffer's length); a range outside
 * the buffer declines rather than trapping, so the loop signals exactly as it did.
 */
final class WasmCharIoRuntimeBuilder {

	/** The staging block; four spare bytes carry a sequence split by its end. */
	private static final int CHUNK_BYTES = 65536;

	private WasmCharIoRuntimeBuilder() {
	}

	/**
	 * Builds the {@code _read_seq_chars} body.
	 * @return the function body bytes
	 */
	static byte[] buildReadSeqCharsBody() {
		ByteArrayOutputStream body = new am.ik.wasm.UnsynchronizedByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		// params: SEQ=0, STREAM=1, START=2, END=3 (ref) ; i32 locals: FD=4, LEN=5, S=6,
		// E=7, I=8, BUF=9, SAVED_HP=10, WANT=11, GOT=12, P=13, B0=14, then the decode's
		// B=15, CP=16, NEED=17, K=18, N=19, OFF=20, LO=21, RANGE=22 ; ref locals:
		// HEADER=23, DATA=24
		w.write(2);
		w.write(19);
		w.write(Type.I32);
		w.write(2);
		w.writeRefType(true, Type.EQ.code());
		final int SEQ = 0, STREAM = 1, START = 2, END = 3, FD = 4, LEN = 5, S = 6, E = 7, I = 8, BUF = 9, SAVED_HP = 10,
				WANT = 11, GOT = 12, P = 13, B0 = 14, HEADER = 23, DATA = 24;
		final WasmUtf8StreamDecoder.Locals decode = new WasmUtf8StreamDecoder.Locals(B0, 15, 16, 17, 18, 19, 20, 21,
				22);
		final int IOV = WasmLispCompiler.IOV_OFFSET;
		final int NWRITTEN = WasmLispCompiler.NWRITTEN_OFFSET;

		w.write(Instruction.BLOCK, 0x40); // $declined

		// --- the buffer: a character vector holding its own elements ----------------
		// The marker invariant has one owner, so the element type is _charvec_p's answer
		// and never a second reading of the meta word here.
		getLocal(w, SEQ);
		WasmEmitHelper.emitCharvecPCall(w);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.BR_IF, 0);
		getLocal(w, SEQ);
		refCast(w, WasmLispCompiler.TYPE_CELL);
		structGet(w, WasmLispCompiler.TYPE_CELL, 0);
		setLocal(w, HEADER);
		// data = header.cdr.cdr: the element buckets, or the target of a view (declined).
		consField(w, HEADER, 1);
		refCast(w, WasmLispCompiler.TYPE_CONS);
		structGet(w, WasmLispCompiler.TYPE_CONS, 1);
		setLocal(w, DATA);
		getLocal(w, DATA);
		refTest(w, WasmLispCompiler.TYPE_HASH_BUCKETS);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.BR_IF, 0);
		// rank 1 only: dims = header.car, one dimension.
		consField(w, HEADER, 0);
		refCast(w, WasmLispCompiler.TYPE_HASH_BUCKETS);
		arrayLen(w);
		i32(w, 1);
		w.write(Instruction.I32_NE);
		w.write(Instruction.BR_IF, 0);
		// len = the fill pointer (meta.car) when there is one, else dims[0] -- what
		// (length seq) answers, which is the bound the loop this replaces reads.
		consField(w, HEADER, 1);
		refCast(w, WasmLispCompiler.TYPE_CONS);
		structGet(w, WasmLispCompiler.TYPE_CONS, 0);
		refCast(w, WasmLispCompiler.TYPE_CONS);
		structGet(w, WasmLispCompiler.TYPE_CONS, 0);
		refTest(w, Type.I31.code());
		w.write(Instruction.IF, 0x40);
		consField(w, HEADER, 1);
		refCast(w, WasmLispCompiler.TYPE_CONS);
		structGet(w, WasmLispCompiler.TYPE_CONS, 0);
		refCast(w, WasmLispCompiler.TYPE_CONS);
		structGet(w, WasmLispCompiler.TYPE_CONS, 0);
		refCast(w, Type.I31.code());
		w.write(Instruction.GC_PREFIX, Instruction.I31_GET_S);
		setLocal(w, LEN);
		w.write(Instruction.ELSE);
		consField(w, HEADER, 0);
		refCast(w, WasmLispCompiler.TYPE_HASH_BUCKETS);
		i32(w, 0);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
		refCast(w, Type.I31.code());
		w.write(Instruction.GC_PREFIX, Instruction.I31_GET_S);
		setLocal(w, LEN);
		w.write(Instruction.END);

		// --- the bounds -------------------------------------------------------------
		bound(w, START, S, () -> i32(w, 0));
		bound(w, END, E, () -> getLocal(w, LEN));
		getLocal(w, S);
		i32(w, 0);
		w.write(Instruction.I32_LT_S);
		getLocal(w, E);
		getLocal(w, LEN);
		w.write(Instruction.I32_GT_S);
		w.write(Instruction.I32_OR);
		getLocal(w, S);
		getLocal(w, E);
		w.write(Instruction.I32_GT_S);
		w.write(Instruction.I32_OR);
		w.write(Instruction.BR_IF, 0);

		// --- the stream: an i31 fd (a negative one is a string stream: declined), or
		// the standard-stream designator (fd 0) --------------------------------------
		getLocal(w, STREAM);
		refTest(w, Type.I31.code());
		w.write(Instruction.IF, 0x40);
		getLocal(w, STREAM);
		refCast(w, Type.I31.code());
		w.write(Instruction.GC_PREFIX, Instruction.I31_GET_S);
		setLocal(w, FD);
		w.write(Instruction.ELSE);
		i32(w, 0);
		setLocal(w, FD);
		w.write(Instruction.END);
		getLocal(w, FD);
		i32(w, 0);
		w.write(Instruction.I32_LT_S);
		w.write(Instruction.BR_IF, 0);

		getLocal(w, S);
		setLocal(w, I);

		// --- bytes the fd's pushback holds come first, a character at a time through
		// _read_char (which takes them before the fd): while they last and i < e,
		// data[i] = _read_char(stream, nil, nil). A held byte always yields a character.
		w.write(Instruction.BLOCK, 0x40); // $drained
		w.write(Instruction.LOOP, 0x40); // $drain
		WasmUtf8StreamDecoder.emitHeld(w, FD);
		w.write(Instruction.I32_EQZ);
		getLocal(w, I);
		getLocal(w, E);
		w.write(Instruction.I32_GE_S);
		w.write(Instruction.I32_OR);
		w.write(Instruction.BR_IF, 1);
		getLocal(w, DATA);
		refCast(w, WasmLispCompiler.TYPE_HASH_BUCKETS);
		getLocal(w, I);
		getLocal(w, STREAM);
		w.write(Instruction.REF_NULL);
		w.writeHeapType(Type.EQ.code());
		w.write(Instruction.REF_NULL);
		w.writeHeapType(Type.EQ.code());
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_READ_CHAR);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_SET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
		getLocal(w, I);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		setLocal(w, I);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END); // $drain
		w.write(Instruction.END); // $drained

		// --- the staging block at HEAP_PTR, reserved over the transfer ---------------
		loadMem32(w, WasmLispCompiler.HEAP_PTR_ADDR);
		setLocal(w, SAVED_HP);
		getLocal(w, SAVED_HP);
		i32(w, 7);
		w.write(Instruction.I32_ADD);
		i32(w, -8);
		w.write(Instruction.I32_AND);
		setLocal(w, BUF);
		WasmEmitHelper.emitGrowHeapTo(w, () -> {
			getLocal(w, BUF);
			i32(w, CHUNK_BYTES + 8);
			w.write(Instruction.I32_ADD);
		});
		i32(w, WasmLispCompiler.HEAP_PTR_ADDR);
		getLocal(w, BUF);
		i32(w, CHUNK_BYTES + 8);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_STORE, 0x02, 0x00);

		// --- the transfer loop: i walks the code points from s to e ------------------
		w.write(Instruction.BLOCK, 0x40); // $done
		w.write(Instruction.LOOP, 0x40); // $next
		getLocal(w, I);
		getLocal(w, E);
		w.write(Instruction.I32_GE_S);
		w.write(Instruction.BR_IF, 1); // -> $done
		// want = min(e - i, CHUNK) bytes: one byte per code point still wanted.
		getLocal(w, E);
		getLocal(w, I);
		w.write(Instruction.I32_SUB);
		setLocal(w, WANT);
		getLocal(w, WANT);
		i32(w, CHUNK_BYTES);
		w.write(Instruction.I32_GT_S);
		w.write(Instruction.IF, 0x40);
		i32(w, CHUNK_BYTES);
		setLocal(w, WANT);
		w.write(Instruction.END);
		// got = fd_read(fd, buf, want)
		iovec(w, IOV, BUF, WANT);
		getLocal(w, FD);
		i32(w, IOV);
		i32(w, 1);
		i32(w, NWRITTEN);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_FD_READ);
		// got = errno == 0 ? nread : 0 -- a failed read ends the transfer like end of
		// file
		setLocal(w, GOT);
		loadMem32(w, NWRITTEN);
		i32(w, 0);
		getLocal(w, GOT);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.SELECT);
		setLocal(w, GOT);
		getLocal(w, GOT);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.BR_IF, 1); // end of file -> $done
		i32(w, 0);
		setLocal(w, P);
		// decode the block: while (p < got && i < e) store one code point
		w.write(Instruction.BLOCK, 0x40); // $decoded
		w.write(Instruction.LOOP, 0x40); // $decode
		getLocal(w, P);
		getLocal(w, GOT);
		w.write(Instruction.I32_GE_S);
		w.write(Instruction.BR_IF, 1);
		getLocal(w, I);
		getLocal(w, E);
		w.write(Instruction.I32_GE_S);
		w.write(Instruction.BR_IF, 1);
		getLocal(w, BUF);
		getLocal(w, P);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);
		setLocal(w, B0);
		WasmUtf8StreamDecoder.emitDecode(w, decode, () -> fetchBlockByte(w, decode, FD, BUF, P, GOT));
		storeChar(w, DATA, I, () -> getLocal(w, decode.cp()));
		getLocal(w, P);
		getLocal(w, decode.n());
		w.write(Instruction.I32_ADD);
		setLocal(w, P);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END); // $decode
		w.write(Instruction.END); // $decoded
		w.write(Instruction.BR, 0); // -> $next
		w.write(Instruction.END); // $next
		w.write(Instruction.END); // $done

		// --- what the block holds past the last character consumed (the byte that
		// showed the last sequence malformed, at most) is the stream's next: push it
		// back, the last byte first, so the pushback holds them in order -------------
		w.write(Instruction.BLOCK, 0x40); // $kept
		w.write(Instruction.LOOP, 0x40); // $keep
		getLocal(w, GOT);
		getLocal(w, P);
		w.write(Instruction.I32_LE_S);
		w.write(Instruction.BR_IF, 1);
		getLocal(w, GOT);
		i32(w, 1);
		w.write(Instruction.I32_SUB);
		setLocal(w, GOT);
		WasmUtf8StreamDecoder.emitPushFront(w, FD, () -> {
			getLocal(w, BUF);
			getLocal(w, GOT);
			w.write(Instruction.I32_ADD);
			w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);
		}, () -> i32(w, 1));
		w.write(Instruction.BR, 0);
		w.write(Instruction.END); // $keep
		w.write(Instruction.END); // $kept

		// --- pop the block, answer the fill position --------------------------------
		i32(w, WasmLispCompiler.HEAP_PTR_ADDR);
		getLocal(w, SAVED_HP);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		getLocal(w, I);
		w.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		w.write(Instruction.RETURN);
		w.write(Instruction.END); // $declined
		w.write(Instruction.REF_NULL);
		w.writeHeapType(Type.EQ.code());
		w.write(Instruction.END);
		return body.toByteArray();
	}

	/**
	 * Builds the stub {@code _read_seq_chars} body: the declined answer, for a program
	 * whose {@code read-sequence} sites can never be handed a character buffer.
	 * @return the function body bytes
	 */
	static byte[] buildStub() {
		ByteArrayOutputStream body = new am.ik.wasm.UnsynchronizedByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		w.write(0);
		w.write(Instruction.REF_NULL);
		w.writeHeapType(Type.EQ.code());
		w.write(Instruction.END);
		return body.toByteArray();
	}

	// data[i] = TYPE_CHAR(codePoint) ; i += 1
	private static void storeChar(WasmWriter w, int dataSlot, int iSlot, Runnable codePoint) {
		getLocal(w, dataSlot);
		refCast(w, WasmLispCompiler.TYPE_HASH_BUCKETS);
		getLocal(w, iSlot);
		codePoint.run();
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CHAR);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_SET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_HASH_BUCKETS);
		getLocal(w, iSlot);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		setLocal(w, iSlot);
	}

	// b = byte k of the sequence at p: in the block while p + k < got, else (the
	// sequence runs past the block) ONE more byte read off the fd into the spare bytes
	// after it, got += 1 -- or -1 at end of file.
	private static void fetchBlockByte(WasmWriter w, WasmUtf8StreamDecoder.Locals decode, int fdSlot, int bufSlot,
			int pSlot, int gotSlot) {
		getLocal(w, pSlot);
		getLocal(w, decode.k());
		w.write(Instruction.I32_ADD);
		getLocal(w, gotSlot);
		w.write(Instruction.I32_LT_S);
		w.write(Instruction.IF, 0x40);
		getLocal(w, bufSlot);
		getLocal(w, pSlot);
		w.write(Instruction.I32_ADD);
		getLocal(w, decode.k());
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);
		setLocal(w, decode.b());
		w.write(Instruction.ELSE);
		i32(w, WasmLispCompiler.IOV_OFFSET);
		getLocal(w, bufSlot);
		getLocal(w, gotSlot);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		i32(w, WasmLispCompiler.IOV_OFFSET + 4);
		i32(w, 1);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		getLocal(w, fdSlot);
		i32(w, WasmLispCompiler.IOV_OFFSET);
		i32(w, 1);
		i32(w, WasmLispCompiler.NWRITTEN_OFFSET);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_FD_READ);
		// b = errno == 0 && nread != 0 ? mem_u8[buf + got] : -1
		w.write(Instruction.I32_EQZ);
		loadMem32(w, WasmLispCompiler.NWRITTEN_OFFSET);
		i32(w, 0);
		w.write(Instruction.I32_NE);
		w.write(Instruction.I32_AND);
		w.write(Instruction.IF, 0x40);
		getLocal(w, bufSlot);
		getLocal(w, gotSlot);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);
		setLocal(w, decode.b());
		getLocal(w, gotSlot);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		setLocal(w, gotSlot);
		w.write(Instruction.ELSE);
		i32(w, -1);
		setLocal(w, decode.b());
		w.write(Instruction.END);
		w.write(Instruction.END);
	}

	// target = arg is null ? dflt : i31.get_s(arg)
	private static void bound(WasmWriter w, int argSlot, int targetSlot, Runnable dflt) {
		getLocal(w, argSlot);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.IF, Type.I32.code());
		dflt.run();
		w.write(Instruction.ELSE);
		getLocal(w, argSlot);
		refCast(w, Type.I31.code());
		w.write(Instruction.GC_PREFIX, Instruction.I31_GET_S);
		w.write(Instruction.END);
		setLocal(w, targetSlot);
	}

	// iov.ptr = buf ; iov.len = len
	private static void iovec(WasmWriter w, int iov, int bufSlot, int lenSlot) {
		i32(w, iov);
		getLocal(w, bufSlot);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		i32(w, iov + 4);
		getLocal(w, lenSlot);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
	}

	// Pushes field 0 (car) or 1 (cdr) of the cons in the given slot.
	private static void consField(WasmWriter w, int slot, int field) {
		getLocal(w, slot);
		refCast(w, WasmLispCompiler.TYPE_CONS);
		structGet(w, WasmLispCompiler.TYPE_CONS, field);
	}

	// === low-level emit helpers ===

	private static void getLocal(WasmWriter w, int slot) {
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(slot);
	}

	private static void setLocal(WasmWriter w, int slot) {
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(slot);
	}

	private static void i32(WasmWriter w, int value) {
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(value);
	}

	private static void loadMem32(WasmWriter w, int addr) {
		i32(w, addr);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
	}

	private static void refTest(WasmWriter w, int heapType) {
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(heapType);
	}

	private static void refCast(WasmWriter w, int heapType) {
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(heapType);
	}

	private static void structGet(WasmWriter w, int type, int field) {
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(type);
		w.writeUnsignedLeb128(field);
	}

	private static void arrayLen(WasmWriter w) {
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_LEN);
	}

}
