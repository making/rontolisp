package am.ik.rontolisp.codegen.wasm;

import java.io.ByteArrayOutputStream;

import am.ik.wasm.Instruction;
import am.ik.wasm.Type;
import am.ik.wasm.WasmWriter;

/**
 * Builds the four WASM runtime helpers used by {@code rontolisp:fetch} (component mode):
 * <ul>
 * <li>{@code _fetch_str(ptr, len) -> string}: copies {@code len} raw bytes into the
 * rontolisp heap wrapped in the surrounding quotes a rontolisp string carries, and
 * returns a string struct (the response body and each response-header name/value are
 * built this way).</li>
 * <li>{@code _fetch_plist_get(plist, keyOffset) -> value}: walks an options property
 * list, comparing each keyword key's interned string offset against {@code keyOffset},
 * and returns the following value (or nil).</li>
 * <li>{@code _fetch_ser_headers(alist) -> length}: serializes a request-header alist of
 * {@code (name . value)} string pairs into {@code REQ_HDR_BUF} as a count-prefixed buffer
 * ({@code count:u32, then per header name_len:u32, name, value_len:u32, value}),
 * stripping the rontolisp string quotes, and returns the total byte length.</li>
 * <li>{@code _fetch_deser_headers(ptr) -> alist}: rebuilds a {@code (name . value)} alist
 * from a buffer in the same wire format that the adapter writes the response headers
 * into.</li>
 * </ul>
 */
final class WasmFetchRuntimeBuilder {

	private static final int QUOTE = 0x22;

	private WasmFetchRuntimeBuilder() {
	}

	/** {@code _fetch_str(i32 ptr, i32 len) -> (ref null eq)} (type TYPE_RAT_NEW). */
	static byte[] buildStr() {
		final ByteArrayOutputStream body = new ByteArrayOutputStream();
		final WasmWriter w = new WasmWriter(body);
		final int PTR = 0, LEN = 1, HEAP = 2, K = 3;
		// two i32 locals: heap cursor, copy index
		w.write(1);
		w.writeUnsignedLeb128(2);
		w.write(Type.I32);
		// heap = memory[HEAP_PTR_ADDR]
		i32(w, WasmLispCompiler.HEAP_PTR_ADDR);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		setLocal(w, HEAP);
		// heap[0] = '"'
		getLocal(w, HEAP);
		i32(w, QUOTE);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		// for k in 0..len: heap[1+k] = ptr[k]
		i32(w, 0);
		setLocal(w, K);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		getLocal(w, K);
		getLocal(w, LEN);
		w.write(Instruction.I32_GE_U);
		w.write(Instruction.BR_IF);
		w.writeSignedLeb128(1);
		getLocal(w, HEAP);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		getLocal(w, K);
		w.write(Instruction.I32_ADD);
		getLocal(w, PTR);
		getLocal(w, K);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		getLocal(w, K);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		setLocal(w, K);
		w.write(Instruction.BR);
		w.writeSignedLeb128(0);
		w.write(Instruction.END); // loop
		w.write(Instruction.END); // block
		// heap[1+len] = '"'
		getLocal(w, HEAP);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		getLocal(w, LEN);
		w.write(Instruction.I32_ADD);
		i32(w, QUOTE);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		// memory[HEAP_PTR_ADDR] = heap + len + 2
		i32(w, WasmLispCompiler.HEAP_PTR_ADDR);
		getLocal(w, HEAP);
		getLocal(w, LEN);
		w.write(Instruction.I32_ADD);
		i32(w, 2);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		// return struct.new string(heap, len + 2)
		getLocal(w, HEAP);
		getLocal(w, LEN);
		i32(w, 2);
		w.write(Instruction.I32_ADD);
		structNew(w, WasmLispCompiler.TYPE_STRING);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	/**
	 * {@code _fetch_plist_get((ref null eq) plist, i32 keyOffset) -> (ref null eq)} (type
	 * TYPE_OPEN).
	 */
	static byte[] buildPlistGet() {
		final ByteArrayOutputStream body = new ByteArrayOutputStream();
		final WasmWriter w = new WasmWriter(body);
		final int CUR = 0, KEY_OFF = 1; // reuse the plist param as the cursor
		w.write(0); // no extra locals
		w.write(Instruction.LOOP, 0x40);
		// if cursor is null: return nil
		getLocal(w, CUR);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.REF_NULL);
		w.writeHeapType(Type.EQ.code());
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
		// if car(cursor).offset == keyOffset: return car(cdr(cursor))
		car(w, CUR);
		refCast(w, WasmLispCompiler.TYPE_STRING);
		structGet(w, WasmLispCompiler.TYPE_STRING, 0);
		getLocal(w, KEY_OFF);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.IF, 0x40);
		getLocal(w, CUR);
		refCast(w, WasmLispCompiler.TYPE_CONS);
		structGet(w, WasmLispCompiler.TYPE_CONS, 1); // cdr
		refCast(w, WasmLispCompiler.TYPE_CONS);
		structGet(w, WasmLispCompiler.TYPE_CONS, 0); // car(cdr) = value
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
		// cursor = cdr(cdr(cursor))
		getLocal(w, CUR);
		refCast(w, WasmLispCompiler.TYPE_CONS);
		structGet(w, WasmLispCompiler.TYPE_CONS, 1);
		refCast(w, WasmLispCompiler.TYPE_CONS);
		structGet(w, WasmLispCompiler.TYPE_CONS, 1);
		setLocal(w, CUR);
		w.write(Instruction.BR);
		w.writeSignedLeb128(0);
		w.write(Instruction.END); // loop (never falls through)
		w.write(0x00); // unreachable
		w.write(Instruction.END);
		return body.toByteArray();
	}

	/** {@code _fetch_ser_headers((ref null eq) alist) -> i32} (type TYPE_RAT_GET). */
	static byte[] buildSerHeaders() {
		final ByteArrayOutputStream body = new ByteArrayOutputStream();
		final WasmWriter w = new WasmWriter(body);
		// param 0 = alist (ref, reused as cursor). i32 locals 1-5, ref locals 6-7.
		final int CUR = 0, P = 1, COUNT = 2, OFF = 3, LEN = 4, K = 5, PAIR = 6, STR = 7;
		w.write(2);
		w.writeUnsignedLeb128(5);
		w.write(Type.I32);
		w.writeUnsignedLeb128(2);
		w.write(Type.REFNULL.code());
		w.writeHeapType(Type.EQ.code());
		// p = REQ_HDR_BUF + 4 ; count = 0
		i32(w, WasmLispCompiler.REQ_HDR_BUF + 4);
		setLocal(w, P);
		i32(w, 0);
		setLocal(w, COUNT);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		getLocal(w, CUR);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.BR_IF);
		w.writeSignedLeb128(1);
		// pair = car(cursor)
		car(w, CUR);
		setLocal(w, PAIR);
		// name string = car(pair) -> STR ; write field
		getLocal(w, PAIR);
		refCast(w, WasmLispCompiler.TYPE_CONS);
		structGet(w, WasmLispCompiler.TYPE_CONS, 0);
		setLocal(w, STR);
		emitWriteField(w, STR, OFF, LEN, K, P);
		// value string = cdr(pair) -> STR ; write field
		getLocal(w, PAIR);
		refCast(w, WasmLispCompiler.TYPE_CONS);
		structGet(w, WasmLispCompiler.TYPE_CONS, 1);
		setLocal(w, STR);
		emitWriteField(w, STR, OFF, LEN, K, P);
		// count++ ; cursor = cdr(cursor)
		getLocal(w, COUNT);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		setLocal(w, COUNT);
		getLocal(w, CUR);
		refCast(w, WasmLispCompiler.TYPE_CONS);
		structGet(w, WasmLispCompiler.TYPE_CONS, 1);
		setLocal(w, CUR);
		w.write(Instruction.BR);
		w.writeSignedLeb128(0);
		w.write(Instruction.END); // loop
		w.write(Instruction.END); // block
		// memory[REQ_HDR_BUF] = count
		i32(w, WasmLispCompiler.REQ_HDR_BUF);
		getLocal(w, COUNT);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		// return p - REQ_HDR_BUF
		getLocal(w, P);
		i32(w, WasmLispCompiler.REQ_HDR_BUF);
		w.write(Instruction.I32_SUB);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	// Writes the string in ref local STR (a rontolisp string with surrounding quotes) to
	// memory at local P as len:u32 followed by the unquoted bytes, advancing P. OFF/LEN/K
	// are i32 scratch locals.
	private static void emitWriteField(WasmWriter w, int STR, int OFF, int LEN, int K, int P) {
		// off = STR.offset + 1 ; len = STR.length - 2 (strip the quotes)
		getLocal(w, STR);
		refCast(w, WasmLispCompiler.TYPE_STRING);
		structGet(w, WasmLispCompiler.TYPE_STRING, 0);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		setLocal(w, OFF);
		getLocal(w, STR);
		refCast(w, WasmLispCompiler.TYPE_STRING);
		structGet(w, WasmLispCompiler.TYPE_STRING, 1);
		i32(w, 2);
		w.write(Instruction.I32_SUB);
		setLocal(w, LEN);
		// memory[p] = len ; p += 4
		getLocal(w, P);
		getLocal(w, LEN);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		getLocal(w, P);
		i32(w, 4);
		w.write(Instruction.I32_ADD);
		setLocal(w, P);
		// for k in 0..len: memory[p + k] = memory[off + k]
		i32(w, 0);
		setLocal(w, K);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		getLocal(w, K);
		getLocal(w, LEN);
		w.write(Instruction.I32_GE_U);
		w.write(Instruction.BR_IF);
		w.writeSignedLeb128(1);
		getLocal(w, P);
		getLocal(w, K);
		w.write(Instruction.I32_ADD);
		getLocal(w, OFF);
		getLocal(w, K);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		getLocal(w, K);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		setLocal(w, K);
		w.write(Instruction.BR);
		w.writeSignedLeb128(0);
		w.write(Instruction.END); // loop
		w.write(Instruction.END); // block
		// p += len
		getLocal(w, P);
		getLocal(w, LEN);
		w.write(Instruction.I32_ADD);
		setLocal(w, P);
	}

	/**
	 * {@code _fetch_deser_headers(i32 ptr) -> (ref null eq)} (type TYPE_READ_LINE_FD).
	 */
	static byte[] buildDeserHeaders() {
		final ByteArrayOutputStream body = new ByteArrayOutputStream();
		final WasmWriter w = new WasmWriter(body);
		// param 0 = ptr (i32). i32 locals 1-4 (count,i,nl,vl), ref locals 5-7
		// (name,value,result)
		final int PTR = 0, COUNT = 1, I = 2, NL = 3, VL = 4, NAME = 5, VALUE = 6, RESULT = 7;
		w.write(2);
		w.writeUnsignedLeb128(4);
		w.write(Type.I32);
		w.writeUnsignedLeb128(3);
		w.write(Type.REFNULL.code());
		w.writeHeapType(Type.EQ.code());
		// count = memory[ptr] ; ptr += 4 ; result = nil
		getLocal(w, PTR);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		setLocal(w, COUNT);
		getLocal(w, PTR);
		i32(w, 4);
		w.write(Instruction.I32_ADD);
		setLocal(w, PTR);
		w.write(Instruction.REF_NULL);
		w.writeHeapType(Type.EQ.code());
		setLocal(w, RESULT);
		i32(w, 0);
		setLocal(w, I);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		getLocal(w, I);
		getLocal(w, COUNT);
		w.write(Instruction.I32_GE_U);
		w.write(Instruction.BR_IF);
		w.writeSignedLeb128(1);
		// nl = memory[ptr]; ptr += 4 ; name = _fetch_str(ptr, nl) ; ptr += nl
		getLocal(w, PTR);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		setLocal(w, NL);
		getLocal(w, PTR);
		i32(w, 4);
		w.write(Instruction.I32_ADD);
		setLocal(w, PTR);
		getLocal(w, PTR);
		getLocal(w, NL);
		call(w, WasmLispCompiler.FUNC_FETCH_STR);
		setLocal(w, NAME);
		getLocal(w, PTR);
		getLocal(w, NL);
		w.write(Instruction.I32_ADD);
		setLocal(w, PTR);
		// vl = memory[ptr]; ptr += 4 ; value = _fetch_str(ptr, vl) ; ptr += vl
		getLocal(w, PTR);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		setLocal(w, VL);
		getLocal(w, PTR);
		i32(w, 4);
		w.write(Instruction.I32_ADD);
		setLocal(w, PTR);
		getLocal(w, PTR);
		getLocal(w, VL);
		call(w, WasmLispCompiler.FUNC_FETCH_STR);
		setLocal(w, VALUE);
		getLocal(w, PTR);
		getLocal(w, VL);
		w.write(Instruction.I32_ADD);
		setLocal(w, PTR);
		// result = cons(cons(name, value), result)
		getLocal(w, NAME);
		getLocal(w, VALUE);
		structNew(w, WasmLispCompiler.TYPE_CONS);
		getLocal(w, RESULT);
		structNew(w, WasmLispCompiler.TYPE_CONS);
		setLocal(w, RESULT);
		// i++
		getLocal(w, I);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		setLocal(w, I);
		w.write(Instruction.BR);
		w.writeSignedLeb128(0);
		w.write(Instruction.END); // loop
		w.write(Instruction.END); // block
		getLocal(w, RESULT);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	private static void car(WasmWriter w, int slot) {
		getLocal(w, slot);
		refCast(w, WasmLispCompiler.TYPE_CONS);
		structGet(w, WasmLispCompiler.TYPE_CONS, 0);
	}

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

	private static void call(WasmWriter w, int func) {
		w.write(Instruction.CALL);
		w.writeSignedLeb128(func);
	}

	private static void refCast(WasmWriter w, int heapType) {
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(heapType);
	}

	private static void structGet(WasmWriter w, int type, int field) {
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeSignedLeb128(type);
		w.writeSignedLeb128(field);
	}

	private static void structNew(WasmWriter w, int type) {
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeSignedLeb128(type);
	}

}
