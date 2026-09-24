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
 * the call and popped after it, the {@code _open} discipline). That can never overshoot:
 * a code point is one to four bytes, so N bytes hold at most N of them, and the buffer
 * fills before the bytes run out only when every one of them was an ASCII character. A
 * sequence SPLIT by the end of the block is completed by a follow-up read of its missing
 * bytes -- the block is over-allocated by those four bytes -- and a sequence truncated by
 * end of file yields its lead byte as a bare character, which is the answer
 * {@code _read_char} gives for the same input.
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
		// E=7, I=8, BUF=9, SAVED_HP=10, WANT=11, GOT=12, P=13, B0=14, NEEDED=15, B1=16,
		// B2=17, B3=18 ; ref locals: HEADER=19, DATA=20
		w.write(2);
		w.write(15);
		w.write(Type.I32);
		w.write(2);
		w.writeRefType(true, Type.EQ.code());
		final int SEQ = 0, STREAM = 1, START = 2, END = 3, FD = 4, LEN = 5, S = 6, E = 7, I = 8, BUF = 9, SAVED_HP = 10,
				WANT = 11, GOT = 12, P = 13, B0 = 14, NEEDED = 15, B1 = 16, B2 = 17, B3 = 18, HEADER = 19, DATA = 20;
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

		// --- a peek parked a whole code point on this fd: it is the first character,
		// exactly as _read_char drains it --------------------------------------------
		loadMem32(w, WasmLispCompiler.PEEK_FD_ADDR);
		getLocal(w, FD);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_EQ);
		getLocal(w, I);
		getLocal(w, E);
		w.write(Instruction.I32_LT_S);
		w.write(Instruction.I32_AND);
		w.write(Instruction.IF, 0x40);
		i32(w, WasmLispCompiler.PEEK_FD_ADDR);
		i32(w, 0);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		storeChar(w, DATA, I, () -> loadMem32(w, WasmLispCompiler.PEEK_CP_ADDR));
		w.write(Instruction.END);

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
		w.write(Instruction.DROP);
		loadMem32(w, NWRITTEN);
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
		WasmIoRuntimeBuilder.emitUtf8ByteCount(w, B0);
		setLocal(w, NEEDED);
		// A sequence split by the end of the block: read its missing bytes in, which the
		// four spare bytes of the block leave room for. Nothing is put back, so nothing
		// can be read twice.
		getLocal(w, P);
		getLocal(w, NEEDED);
		w.write(Instruction.I32_ADD);
		getLocal(w, GOT);
		w.write(Instruction.I32_GT_S);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.BLOCK, 0x40); // $completed
		w.write(Instruction.LOOP, 0x40); // $complete
		getLocal(w, P);
		getLocal(w, NEEDED);
		w.write(Instruction.I32_ADD);
		getLocal(w, GOT);
		w.write(Instruction.I32_LE_S);
		w.write(Instruction.BR_IF, 1);
		// iov = (buf + got, p + needed - got)
		i32(w, IOV);
		getLocal(w, BUF);
		getLocal(w, GOT);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		i32(w, IOV + 4);
		getLocal(w, P);
		getLocal(w, NEEDED);
		w.write(Instruction.I32_ADD);
		getLocal(w, GOT);
		w.write(Instruction.I32_SUB);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		getLocal(w, FD);
		i32(w, IOV);
		i32(w, 1);
		i32(w, NWRITTEN);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_FD_READ);
		w.write(Instruction.DROP);
		loadMem32(w, NWRITTEN);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.BR_IF, 1);
		getLocal(w, GOT);
		loadMem32(w, NWRITTEN);
		w.write(Instruction.I32_ADD);
		setLocal(w, GOT);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END); // $complete
		w.write(Instruction.END); // $completed
		// still short: end of file mid-sequence, so the lead byte is its own character.
		getLocal(w, P);
		getLocal(w, NEEDED);
		w.write(Instruction.I32_ADD);
		getLocal(w, GOT);
		w.write(Instruction.I32_GT_S);
		w.write(Instruction.IF, 0x40);
		i32(w, 1);
		setLocal(w, NEEDED);
		w.write(Instruction.END);
		w.write(Instruction.END);
		// b1..b3 as the lead byte asks for
		continuationByte(w, BUF, P, NEEDED, 2, 1, B1);
		continuationByte(w, BUF, P, NEEDED, 3, 2, B2);
		continuationByte(w, BUF, P, NEEDED, 4, 3, B3);
		storeChar(w, DATA, I, () -> WasmIoRuntimeBuilder.emitUtf8DecodeFromLocals(w, NEEDED, B0, B1, B2, B3));
		getLocal(w, P);
		getLocal(w, NEEDED);
		w.write(Instruction.I32_ADD);
		setLocal(w, P);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END); // $decode
		w.write(Instruction.END); // $decoded
		w.write(Instruction.BR, 0); // -> $next
		w.write(Instruction.END); // $next
		w.write(Instruction.END); // $done

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

	// if (needed >= atLeast) bN = mem_u8[buf + p + offset]
	private static void continuationByte(WasmWriter w, int bufSlot, int pSlot, int neededSlot, int atLeast, int offset,
			int target) {
		getLocal(w, neededSlot);
		i32(w, atLeast);
		w.write(Instruction.I32_GE_S);
		w.write(Instruction.IF, 0x40);
		getLocal(w, bufSlot);
		getLocal(w, pSlot);
		w.write(Instruction.I32_ADD);
		i32(w, offset);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);
		setLocal(w, target);
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
