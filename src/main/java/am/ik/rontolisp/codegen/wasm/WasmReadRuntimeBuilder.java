package am.ik.rontolisp.codegen.wasm;

import java.io.ByteArrayOutputStream;

import am.ik.wasm.Instruction;
import am.ik.wasm.Type;
import am.ik.wasm.WasmWriter;

/**
 * Builds the WASM bytecode for the runtime Lisp reader used by the {@code read} and
 * {@code load} built-ins. The generated module is standalone, so a combined lexer/parser
 * is emitted directly into it (mirroring {@link WasmEvalRuntimeBuilder}).
 *
 * <p>
 * The reader walks UTF-8 bytes in linear memory between a cursor
 * ({@code READ_CURSOR_ADDR}) and an end offset ({@code READ_END_ADDR}), producing values
 * in the shared runtime representation: {@code ref.null eq} for nil, an i31ref for
 * integers, a {@code TYPE_STRING} struct {@code {offset,length}} for symbols and string
 * literals, {@code i31(1)} for {@code t}, and a {@code TYPE_CONS} struct for cons cells.
 * Symbols are interned via {@code _intern} so their string-table offset matches what the
 * eval runtime compares against.
 *
 * <p>
 * Numeric support is limited to signed 31-bit integers (no floats or big integers),
 * matching the documented compiled-eval limitations.
 */
final class WasmReadRuntimeBuilder {

	private static final int CURSOR = WasmLispCompiler.READ_CURSOR_ADDR;

	private static final int END_ADDR = WasmLispCompiler.READ_END_ADDR;

	private static final int HEAP = WasmLispCompiler.HEAP_PTR_ADDR;

	private static final int QUOTE_LEN = 5; // "quote"

	private static final int FUNCTION_LEN = 8; // "function"

	private WasmReadRuntimeBuilder() {
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

	private static void structNew(WasmWriter w, int type) {
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeSignedLeb128(type);
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

	private static void emitNull(WasmWriter w) {
		w.write(Instruction.REF_NULL);
		w.writeHeapType(Type.EQ.code());
	}

	private static void i31New(WasmWriter w) {
		w.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
	}

	private static void loadMem32(WasmWriter w, int addr) {
		i32(w, addr);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
	}

	/** Loads the byte at the current cursor onto the stack. */
	private static void curByte(WasmWriter w) {
		loadMem32(w, CURSOR);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);
	}

	/** Emits {@code cursor += 1}. */
	private static void advanceCursor(WasmWriter w) {
		i32(w, CURSOR);
		loadMem32(w, CURSOR);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
	}

	private static void block(WasmWriter w) {
		w.write(Instruction.BLOCK, 0x40);
	}

	private static void loop(WasmWriter w) {
		w.write(Instruction.LOOP, 0x40);
	}

	private static void ifVoid(WasmWriter w) {
		w.write(Instruction.IF, 0x40);
	}

	private static void end(WasmWriter w) {
		w.write(Instruction.END);
	}

	private static void br(WasmWriter w, int depth) {
		w.write(Instruction.BR, depth);
	}

	private static void brIf(WasmWriter w, int depth) {
		w.write(Instruction.BR_IF, depth);
	}

	/**
	 * Emits an inline whitespace/comment skipper advancing the cursor. Uses no locals (it
	 * re-reads the byte at the cursor) so it can be inlined into multiple functions.
	 */
	private static void emitSkipWs(WasmWriter w) {
		block(w);
		loop(w);
		// if cursor >= end: break
		loadMem32(w, CURSOR);
		loadMem32(w, END_ADDR);
		w.write(Instruction.I32_GE_S);
		brIf(w, 1);
		// if byte <= 32 (whitespace): advance, continue
		curByte(w);
		i32(w, 32);
		w.write(Instruction.I32_LE_S);
		ifVoid(w);
		advanceCursor(w);
		br(w, 1); // continue loop
		end(w);
		// if byte == ';' : skip to end of line
		curByte(w);
		i32(w, ';');
		w.write(Instruction.I32_EQ);
		ifVoid(w);
		block(w);
		loop(w);
		loadMem32(w, CURSOR);
		loadMem32(w, END_ADDR);
		w.write(Instruction.I32_GE_S);
		brIf(w, 1);
		curByte(w);
		i32(w, '\n');
		w.write(Instruction.I32_EQ);
		brIf(w, 1);
		advanceCursor(w);
		br(w, 0);
		end(w); // inner loop
		end(w); // inner block
		br(w, 1); // continue outer loop
		end(w);
		// neither whitespace nor comment: stop
		br(w, 1); // break outer block
		end(w); // loop
		end(w); // block
	}

	// === stubs (emitted when the program uses neither read nor load) ===

	/** {@code _intern} stub: {@code (i32, i32) -> i32}, returns its offset argument. */
	static byte[] buildInternStub() {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		w.write(0);
		getLocal(w, 0);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	static byte[] buildReadExprStub() {
		return refNullStub();
	}

	static byte[] buildReadListStub() {
		return refNullStub();
	}

	static byte[] buildReadStub() {
		return refNullStub();
	}

	static byte[] buildLoadStub() {
		return refNullStub();
	}

	private static byte[] refNullStub() {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		w.write(0);
		emitNull(w);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	// === _intern(off, len) -> canonical offset ===

	/**
	 * Returns the canonical offset for the token at {@code (off, len)}. First scans the
	 * compile-time intern table (entries {@code (offset i32, length i32)}); on a miss
	 * scans a growable runtime table so that symbols absent at compile time (e.g. lambda
	 * parameters in loaded files) get a stable offset across occurrences. A first-seen
	 * token is appended to the runtime table with its own offset as the canonical one.
	 */
	static byte[] buildInternBody(int internBase, int internCount) {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		// params: off=0, len=1 ; locals: IDX=2, EOFF=3, ELEN=4, K=5, COUNT=6
		w.write(1);
		w.write(5);
		w.write(Type.I32);
		final int OFF = 0, LEN = 1, IDX = 2, EOFF = 3, ELEN = 4, K = 5, COUNT = 6;

		// 1. compile-time table (constant count)
		i32(w, internCount);
		setLocal(w, COUNT);
		emitInternScan(w, internBase, OFF, LEN, IDX, EOFF, ELEN, K, COUNT);
		// 2. runtime table (count from memory)
		loadMem32(w, WasmLispCompiler.RT_INTERN_COUNT_ADDR);
		setLocal(w, COUNT);
		emitInternScan(w, WasmLispCompiler.RT_INTERN_BASE, OFF, LEN, IDX, EOFF, ELEN, K, COUNT);
		// 3. miss: append (off, len) and return off
		// mem[RT_BASE + count*8] = off
		i32(w, WasmLispCompiler.RT_INTERN_BASE);
		getLocal(w, COUNT);
		i32(w, 8);
		w.write(Instruction.I32_MUL);
		w.write(Instruction.I32_ADD);
		getLocal(w, OFF);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		// mem[RT_BASE + count*8 + 4] = len
		i32(w, WasmLispCompiler.RT_INTERN_BASE + 4);
		getLocal(w, COUNT);
		i32(w, 8);
		w.write(Instruction.I32_MUL);
		w.write(Instruction.I32_ADD);
		getLocal(w, LEN);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		// mem[RT_COUNT] = count + 1
		i32(w, WasmLispCompiler.RT_INTERN_COUNT_ADDR);
		getLocal(w, COUNT);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		getLocal(w, OFF);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	/**
	 * Emits a scan of {@code COUNT} {@code (offset,length)} entries starting at
	 * {@code baseAddr}; on the first byte-equal entry returns its offset from the
	 * function.
	 */
	private static void emitInternScan(WasmWriter w, int baseAddr, int OFF, int LEN, int IDX, int EOFF, int ELEN, int K,
			int COUNT) {
		i32(w, 0);
		setLocal(w, IDX);
		block(w);
		loop(w);
		// if IDX >= COUNT: break
		getLocal(w, IDX);
		getLocal(w, COUNT);
		w.write(Instruction.I32_GE_S);
		brIf(w, 1);
		// EOFF = mem[baseAddr + IDX*8]
		i32(w, baseAddr);
		getLocal(w, IDX);
		i32(w, 8);
		w.write(Instruction.I32_MUL);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		setLocal(w, EOFF);
		// ELEN = mem[baseAddr + IDX*8 + 4]
		i32(w, baseAddr + 4);
		getLocal(w, IDX);
		i32(w, 8);
		w.write(Instruction.I32_MUL);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		setLocal(w, ELEN);
		// if ELEN == len: compare bytes
		getLocal(w, ELEN);
		getLocal(w, LEN);
		w.write(Instruction.I32_EQ);
		ifVoid(w);
		i32(w, 0);
		setLocal(w, K);
		block(w);
		loop(w);
		// if K >= len: matched -> return EOFF
		getLocal(w, K);
		getLocal(w, LEN);
		w.write(Instruction.I32_GE_S);
		ifVoid(w);
		getLocal(w, EOFF);
		w.write(Instruction.RETURN);
		end(w);
		// if mem[off+K] != mem[eOff+K]: break (not matched)
		getLocal(w, OFF);
		getLocal(w, K);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);
		getLocal(w, EOFF);
		getLocal(w, K);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);
		w.write(Instruction.I32_NE);
		brIf(w, 1);
		// K++
		getLocal(w, K);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		setLocal(w, K);
		br(w, 0);
		end(w); // compare loop
		end(w); // compare block
		end(w); // if ELEN==len
		// IDX++
		getLocal(w, IDX);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		setLocal(w, IDX);
		br(w, 0);
		end(w); // outer loop
		end(w); // outer block
	}

	// === _read_expr() -> value ===

	static byte[] buildReadExprBody(int nilOffset, int tOffset, int quoteOffset, int functionOffset) {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		// ref locals: CAR=0, CDR=1 ; i32 locals: BYTE=2, START=3, LEN=4, OFF=5, POS=6,
		// ESC=7, HP=8, NEG=9, ACC=10, VALID=11
		w.write(2);
		w.write(2);
		w.write(Type.REFNULL.code());
		w.writeHeapType(Type.EQ.code());
		w.write(10);
		w.write(Type.I32);
		final int CAR = 0, CDR = 1, BYTE = 2, START = 3, LEN = 4, OFF = 5, POS = 6, ESC = 7, HP = 8, NEG = 9, ACC = 10,
				VALID = 11;

		emitSkipWs(w);
		// if cursor >= end: return null
		loadMem32(w, CURSOR);
		loadMem32(w, END_ADDR);
		w.write(Instruction.I32_GE_S);
		ifVoid(w);
		emitNull(w);
		w.write(Instruction.RETURN);
		end(w);

		curByte(w);
		setLocal(w, BYTE);

		// '(' -> read list
		getLocal(w, BYTE);
		i32(w, '(');
		w.write(Instruction.I32_EQ);
		ifVoid(w);
		advanceCursor(w);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_READ_LIST);
		w.write(Instruction.RETURN);
		end(w);

		// '\'' -> (quote inner)
		getLocal(w, BYTE);
		i32(w, '\'');
		w.write(Instruction.I32_EQ);
		ifVoid(w);
		advanceCursor(w);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_READ_EXPR);
		setLocal(w, CAR); // inner
		// cdr = cons(inner, null)
		getLocal(w, CAR);
		emitNull(w);
		structNew(w, WasmLispCompiler.TYPE_CONS);
		setLocal(w, CDR);
		// return cons(quoteSym, cdr)
		i32(w, quoteOffset);
		i32(w, QUOTE_LEN);
		structNew(w, WasmLispCompiler.TYPE_STRING);
		getLocal(w, CDR);
		structNew(w, WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.RETURN);
		end(w);

		// "#'" -> (function inner)
		getLocal(w, BYTE);
		i32(w, '#');
		w.write(Instruction.I32_EQ);
		// and cursor+1 < end
		loadMem32(w, CURSOR);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		loadMem32(w, END_ADDR);
		w.write(Instruction.I32_LT_S);
		w.write(Instruction.I32_AND);
		// and byte(cursor+1) == '\''
		loadMem32(w, CURSOR);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);
		i32(w, '\'');
		w.write(Instruction.I32_EQ);
		w.write(Instruction.I32_AND);
		ifVoid(w);
		advanceCursor(w); // consume '#'
		advanceCursor(w); // consume '\''
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_READ_EXPR);
		setLocal(w, CAR); // inner
		// cdr = cons(inner, null)
		getLocal(w, CAR);
		emitNull(w);
		structNew(w, WasmLispCompiler.TYPE_CONS);
		setLocal(w, CDR);
		// return cons(functionSym, cdr)
		i32(w, functionOffset);
		i32(w, FUNCTION_LEN);
		structNew(w, WasmLispCompiler.TYPE_STRING);
		getLocal(w, CDR);
		structNew(w, WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.RETURN);
		end(w);

		// '"' -> string literal
		getLocal(w, BYTE);
		i32(w, '"');
		w.write(Instruction.I32_EQ);
		ifVoid(w);
		emitReadString(w, BYTE, POS, HP, ESC);
		w.write(Instruction.RETURN);
		end(w);

		// ')' -> unexpected, consume and return null
		getLocal(w, BYTE);
		i32(w, ')');
		w.write(Instruction.I32_EQ);
		ifVoid(w);
		advanceCursor(w);
		emitNull(w);
		w.write(Instruction.RETURN);
		end(w);

		// atom: scan maximal symbol-char run [START, cursor)
		loadMem32(w, CURSOR);
		setLocal(w, START);
		block(w);
		loop(w);
		loadMem32(w, CURSOR);
		loadMem32(w, END_ADDR);
		w.write(Instruction.I32_GE_S);
		brIf(w, 1);
		curByte(w);
		setLocal(w, BYTE);
		// stop if not a symbol char
		getLocal(w, BYTE);
		i32(w, 32);
		w.write(Instruction.I32_LE_S);
		brIf(w, 1);
		emitStopIf(w, BYTE, '(');
		emitStopIf(w, BYTE, ')');
		emitStopIf(w, BYTE, '\'');
		emitStopIf(w, BYTE, '"');
		emitStopIf(w, BYTE, ';');
		advanceCursor(w);
		br(w, 0);
		end(w); // loop
		end(w); // block
		// len = cursor - start
		loadMem32(w, CURSOR);
		getLocal(w, START);
		w.write(Instruction.I32_SUB);
		setLocal(w, LEN);

		// classify: integer?
		emitTryInteger(w, BYTE, START, LEN, POS, NEG, ACC, VALID, ESC);

		// symbol: off = _intern(start, len)
		getLocal(w, START);
		getLocal(w, LEN);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_INTERN);
		setLocal(w, OFF);
		// nil?
		getLocal(w, OFF);
		i32(w, nilOffset);
		w.write(Instruction.I32_EQ);
		ifVoid(w);
		emitNull(w);
		w.write(Instruction.RETURN);
		end(w);
		// t?
		getLocal(w, OFF);
		i32(w, tOffset);
		w.write(Instruction.I32_EQ);
		ifVoid(w);
		i32(w, 1);
		i31New(w);
		w.write(Instruction.RETURN);
		end(w);
		// symbol struct
		getLocal(w, OFF);
		getLocal(w, LEN);
		structNew(w, WasmLispCompiler.TYPE_STRING);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	private static void emitStopIf(WasmWriter w, int byteSlot, char ch) {
		getLocal(w, byteSlot);
		i32(w, ch);
		w.write(Instruction.I32_EQ);
		brIf(w, 1);
	}

	/**
	 * Emits the integer classifier/parser for the token at {@code [START, START+LEN)}. If
	 * the token is a valid signed integer (optional leading {@code -}, digits, grouping
	 * commas), pushes the i31 value and returns from the function; otherwise falls
	 * through.
	 */
	private static void emitTryInteger(WasmWriter w, int BYTE, int START, int LEN, int POS, int NEG, int ACC, int VALID,
			int SAWDIGIT) {
		// POS = START ; VALID = 1 ; SAWDIGIT = 0 ; NEG = 0 ; ACC = 0
		getLocal(w, START);
		setLocal(w, POS);
		i32(w, 1);
		setLocal(w, VALID);
		i32(w, 0);
		setLocal(w, SAWDIGIT);
		i32(w, 0);
		setLocal(w, NEG);
		i32(w, 0);
		setLocal(w, ACC);
		// if len > 1 and byte[START]=='-': NEG=1; POS++
		getLocal(w, LEN);
		i32(w, 1);
		w.write(Instruction.I32_GT_S);
		ifVoid(w);
		getLocal(w, START);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);
		i32(w, '-');
		w.write(Instruction.I32_EQ);
		ifVoid(w);
		i32(w, 1);
		setLocal(w, NEG);
		getLocal(w, POS);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		setLocal(w, POS);
		end(w);
		end(w);
		// loop over [POS, START+LEN)
		block(w);
		loop(w);
		getLocal(w, POS);
		getLocal(w, START);
		getLocal(w, LEN);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_GE_S);
		brIf(w, 1);
		// b = mem[POS]
		getLocal(w, POS);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);
		setLocal(w, BYTE);
		// if b < '0'
		getLocal(w, BYTE);
		i32(w, '0');
		w.write(Instruction.I32_LT_S);
		ifVoid(w);
		// not a digit; only ',' is allowed
		getLocal(w, BYTE);
		i32(w, ',');
		w.write(Instruction.I32_NE);
		ifVoid(w);
		i32(w, 0);
		setLocal(w, VALID);
		end(w);
		w.write(Instruction.ELSE);
		// b >= '0'
		getLocal(w, BYTE);
		i32(w, '9');
		w.write(Instruction.I32_GT_S);
		ifVoid(w);
		i32(w, 0);
		setLocal(w, VALID);
		w.write(Instruction.ELSE);
		// digit
		i32(w, 1);
		setLocal(w, SAWDIGIT);
		getLocal(w, ACC);
		i32(w, 10);
		w.write(Instruction.I32_MUL);
		getLocal(w, BYTE);
		i32(w, '0');
		w.write(Instruction.I32_SUB);
		w.write(Instruction.I32_ADD);
		setLocal(w, ACC);
		end(w);
		end(w);
		// POS++
		getLocal(w, POS);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		setLocal(w, POS);
		br(w, 0);
		end(w); // loop
		end(w); // block
		// if VALID and SAWDIGIT: return i31(neg ? -acc : acc)
		getLocal(w, VALID);
		getLocal(w, SAWDIGIT);
		w.write(Instruction.I32_AND);
		ifVoid(w);
		getLocal(w, NEG);
		ifVoid(w);
		i32(w, 0);
		getLocal(w, ACC);
		w.write(Instruction.I32_SUB);
		setLocal(w, ACC);
		end(w);
		getLocal(w, ACC);
		i31New(w);
		w.write(Instruction.RETURN);
		end(w);
	}

	/**
	 * Emits an inline reader for a {@code "..."} string literal (cursor at the opening
	 * quote). Allocates a fresh string in the heap with surrounding {@code "} markers and
	 * processed {@code \n \t \\ \"} escapes, advances the heap pointer, and leaves the
	 * resulting {@code TYPE_STRING} struct on the stack.
	 */
	private static void emitReadString(WasmWriter w, int BYTE, int POS, int HPL, int ESC) {
		advanceCursor(w); // skip opening quote
		loadMem32(w, HEAP);
		setLocal(w, HPL);
		// mem[hp] = '"'
		getLocal(w, HPL);
		i32(w, 0x22);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		i32(w, 1);
		setLocal(w, POS);
		block(w);
		loop(w);
		// if cursor >= end: stop (unterminated)
		loadMem32(w, CURSOR);
		loadMem32(w, END_ADDR);
		w.write(Instruction.I32_GE_S);
		brIf(w, 1);
		curByte(w);
		setLocal(w, BYTE);
		// closing quote
		getLocal(w, BYTE);
		i32(w, '"');
		w.write(Instruction.I32_EQ);
		ifVoid(w);
		advanceCursor(w);
		br(w, 2); // break out of block
		end(w);
		// escape
		getLocal(w, BYTE);
		i32(w, '\\');
		w.write(Instruction.I32_EQ);
		ifVoid(w);
		// if cursor+1 < end: process escape
		loadMem32(w, CURSOR);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		loadMem32(w, END_ADDR);
		w.write(Instruction.I32_LT_S);
		ifVoid(w);
		advanceCursor(w); // consume backslash
		curByte(w);
		setLocal(w, ESC);
		advanceCursor(w); // consume escaped char
		emitEscapeWrite(w, ESC, POS, HPL);
		br(w, 2); // continue outer loop
		end(w);
		end(w);
		// plain byte
		getLocal(w, HPL);
		getLocal(w, POS);
		w.write(Instruction.I32_ADD);
		getLocal(w, BYTE);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		getLocal(w, POS);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		setLocal(w, POS);
		advanceCursor(w);
		br(w, 0);
		end(w); // loop
		end(w); // block
		// closing quote marker
		getLocal(w, HPL);
		getLocal(w, POS);
		w.write(Instruction.I32_ADD);
		i32(w, 0x22);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		getLocal(w, POS);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		setLocal(w, POS);
		// advance heap pointer: mem[HEAP] = hp + pos
		i32(w, HEAP);
		getLocal(w, HPL);
		getLocal(w, POS);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		// push struct STRING(hp, pos)
		getLocal(w, HPL);
		getLocal(w, POS);
		structNew(w, WasmLispCompiler.TYPE_STRING);
	}

	/**
	 * Writes the translated escape byte(s) at {@code hp+pos} and advances {@code pos}.
	 */
	private static void emitEscapeWrite(WasmWriter w, int ESC, int POS, int HPL) {
		// 'n' -> 0x0A
		emitEscapeCase(w, ESC, POS, HPL, 'n', 0x0A);
		emitEscapeCase(w, ESC, POS, HPL, 't', 0x09);
		emitEscapeCase(w, ESC, POS, HPL, '\\', 0x5C);
		emitEscapeCase(w, ESC, POS, HPL, '"', 0x22);
		// default: write backslash then the escaped char
		getLocal(w, ESC);
		i32(w, 'n');
		w.write(Instruction.I32_EQ);
		getLocal(w, ESC);
		i32(w, 't');
		w.write(Instruction.I32_EQ);
		w.write(Instruction.I32_OR);
		getLocal(w, ESC);
		i32(w, '\\');
		w.write(Instruction.I32_EQ);
		w.write(Instruction.I32_OR);
		getLocal(w, ESC);
		i32(w, '"');
		w.write(Instruction.I32_EQ);
		w.write(Instruction.I32_OR);
		w.write(Instruction.I32_EQZ); // none of the known escapes
		ifVoid(w);
		emitStoreByteConst(w, HPL, POS, 0x5C);
		emitStoreByteLocal(w, HPL, POS, ESC);
		end(w);
	}

	private static void emitEscapeCase(WasmWriter w, int ESC, int POS, int HPL, char esc, int translated) {
		getLocal(w, ESC);
		i32(w, esc);
		w.write(Instruction.I32_EQ);
		ifVoid(w);
		emitStoreByteConst(w, HPL, POS, translated);
		end(w);
	}

	private static void emitStoreByteConst(WasmWriter w, int HPL, int POS, int value) {
		getLocal(w, HPL);
		getLocal(w, POS);
		w.write(Instruction.I32_ADD);
		i32(w, value);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		getLocal(w, POS);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		setLocal(w, POS);
	}

	private static void emitStoreByteLocal(WasmWriter w, int HPL, int POS, int byteSlot) {
		getLocal(w, HPL);
		getLocal(w, POS);
		w.write(Instruction.I32_ADD);
		getLocal(w, byteSlot);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		getLocal(w, POS);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		setLocal(w, POS);
	}

	// === _read_list() -> value ===

	static byte[] buildReadListBody() {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		// ref locals: CAR=0, CDR=1
		w.write(1);
		w.write(2);
		w.write(Type.REFNULL.code());
		w.writeHeapType(Type.EQ.code());
		final int CAR = 0, CDR = 1;

		emitSkipWs(w);
		// if cursor >= end: return null
		loadMem32(w, CURSOR);
		loadMem32(w, END_ADDR);
		w.write(Instruction.I32_GE_S);
		ifVoid(w);
		emitNull(w);
		w.write(Instruction.RETURN);
		end(w);
		// if byte == ')': consume, return null
		curByte(w);
		i32(w, ')');
		w.write(Instruction.I32_EQ);
		ifVoid(w);
		advanceCursor(w);
		emitNull(w);
		w.write(Instruction.RETURN);
		end(w);
		// car = _read_expr
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_READ_EXPR);
		setLocal(w, CAR);
		// cdr = _read_list
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_READ_LIST);
		setLocal(w, CDR);
		// cons(car, cdr)
		getLocal(w, CAR);
		getLocal(w, CDR);
		structNew(w, WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	// === _read() -> value (builtin) ===

	static byte[] buildReadBody() {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		// ref local: V=0 ; i32 locals: OFF=1, LEN=2
		w.write(2);
		w.write(1);
		w.write(Type.REFNULL.code());
		w.writeHeapType(Type.EQ.code());
		w.write(2);
		w.write(Type.I32);
		final int V = 0, OFF = 1, LEN = 2;

		// Keep reading lines until one contains a datum (blank and comment-only lines
		// are skipped) or stdin is exhausted (EOF -> nil).
		block(w);
		loop(w);
		i32(w, 0); // fd = 0 (stdin)
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_READ_LINE);
		setLocal(w, V);
		// if V is null: return null
		getLocal(w, V);
		w.write(Instruction.REF_IS_NULL);
		ifVoid(w);
		emitNull(w);
		w.write(Instruction.RETURN);
		end(w);
		// off = string.offset, len = string.length
		getLocal(w, V);
		refCast(w, WasmLispCompiler.TYPE_STRING);
		structGet(w, WasmLispCompiler.TYPE_STRING, 0);
		setLocal(w, OFF);
		getLocal(w, V);
		refCast(w, WasmLispCompiler.TYPE_STRING);
		structGet(w, WasmLispCompiler.TYPE_STRING, 1);
		setLocal(w, LEN);
		// cursor = off + 1 (skip opening quote)
		i32(w, CURSOR);
		getLocal(w, OFF);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		// end = off + len - 1 (before closing quote)
		i32(w, END_ADDR);
		getLocal(w, OFF);
		getLocal(w, LEN);
		w.write(Instruction.I32_ADD);
		i32(w, 1);
		w.write(Instruction.I32_SUB);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		// retry with the next line if nothing remains after whitespace/comments
		emitSkipWs(w);
		loadMem32(w, CURSOR);
		loadMem32(w, END_ADDR);
		w.write(Instruction.I32_GE_S);
		brIf(w, 0);
		// parse one datum
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_READ_EXPR);
		w.write(Instruction.RETURN);
		end(w); // loop
		end(w); // block
		emitNull(w);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	// === _load(path) -> t (builtin) ===

	/**
	 * Opens the file named by the {@code path} string value via WASI {@code path_open}
	 * (relative to the first preopened directory, fd 3), reads it into the heap, then
	 * parses and evaluates each top-level datum in the global environment. Returns
	 * {@code t}; on a failed open returns nil.
	 */
	static byte[] buildLoadBody() {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		// param: PATH=0 (ref) ; i32 locals: OFF=1, PLEN=2, FD=3, BUF=4, TOTAL=5, NREAD=6
		w.write(1);
		w.write(6);
		w.write(Type.I32);
		final int PATH = 0, OFF = 1, PLEN = 2, FD = 3, BUF = 4, TOTAL = 5, NREAD = 6;
		final int IOV = WasmLispCompiler.IOV_OFFSET;
		final int NWRITTEN = WasmLispCompiler.NWRITTEN_OFFSET;
		final int FD_ADDR = WasmLispCompiler.READ_FD_ADDR;

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
		// path_open(dirfd=3, dirflags=0, path_ptr=off+1, path_len=plen, oflags=0,
		// fs_rights_base=FD_READ(2), fs_rights_inheriting=0, fdflags=0, fd_out=FD_ADDR)
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
		i32(w, FD_ADDR);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_PATH_OPEN);
		// if errno != 0: return nil
		ifVoid(w);
		emitNull(w);
		w.write(Instruction.RETURN);
		end(w);
		// fd = mem[FD_ADDR]
		loadMem32(w, FD_ADDR);
		setLocal(w, FD);
		// buf = mem[HEAP_PTR_ADDR]; total = 0
		loadMem32(w, HEAP);
		setLocal(w, BUF);
		i32(w, 0);
		setLocal(w, TOTAL);
		// read the whole file in chunks
		block(w);
		loop(w);
		// iov.ptr = buf + total ; iov.len = 4096
		i32(w, IOV);
		getLocal(w, BUF);
		getLocal(w, TOTAL);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		i32(w, IOV + 4);
		i32(w, 4096);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		// fd_read(fd, IOV, 1, NWRITTEN)
		getLocal(w, FD);
		i32(w, IOV);
		i32(w, 1);
		i32(w, NWRITTEN);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_FD_READ);
		w.write(Instruction.DROP);
		// nread = mem[NWRITTEN]
		loadMem32(w, NWRITTEN);
		setLocal(w, NREAD);
		// if nread == 0: break
		getLocal(w, NREAD);
		w.write(Instruction.I32_EQZ);
		brIf(w, 1);
		// total += nread
		getLocal(w, TOTAL);
		getLocal(w, NREAD);
		w.write(Instruction.I32_ADD);
		setLocal(w, TOTAL);
		br(w, 0);
		end(w); // loop
		end(w); // block
		// cursor = buf ; end = buf + total
		i32(w, CURSOR);
		getLocal(w, BUF);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		i32(w, END_ADDR);
		getLocal(w, BUF);
		getLocal(w, TOTAL);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		// advance heap pointer past the file content so parsed strings allocate after it
		i32(w, HEAP);
		getLocal(w, BUF);
		getLocal(w, TOTAL);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		// parse + eval each top-level datum
		block(w);
		loop(w);
		emitSkipWs(w);
		loadMem32(w, CURSOR);
		loadMem32(w, END_ADDR);
		w.write(Instruction.I32_GE_S);
		brIf(w, 1);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_READ_EXPR);
		emitNull(w); // env = null (global)
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_EVAL);
		w.write(Instruction.DROP);
		br(w, 0);
		end(w); // loop
		end(w); // block
		// return t
		i32(w, 1);
		i31New(w);
		w.write(Instruction.END);
		return body.toByteArray();
	}

}
