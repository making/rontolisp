package am.ik.rontolisp.codegen.wasm;

import java.io.ByteArrayOutputStream;

import am.ik.wasm.Instruction;
import am.ik.wasm.Type;
import am.ik.wasm.WasmWriter;

/**
 * Builds WASM bytecode for the string runtime helpers that produce or compare strings:
 * case conversion ({@code string-upcase} / {@code string-downcase} /
 * {@code string-capitalize}), {@code subseq}, the equality predicates ({@code string=} /
 * {@code string-equal}) and the trim family ({@code string-trim} /
 * {@code string-left-trim} / {@code string-right-trim}).
 *
 * <p>
 * Runtime strings are {@code TYPE_STRING} structs whose bytes in linear memory are
 * wrapped in surrounding quotes ({@code "abc"}). Producing functions copy the content
 * between the quotes into a fresh heap string (allocated at {@code HEAP_PTR_ADDR}) and
 * re-wrap it in quotes. Case handling is ASCII-only.
 */
final class WasmStringRuntimeBuilder {

	private static final int QUOTE = 0x22;

	private static final int COLON = 0x3A;

	// Transform modes for the shared build core.
	private static final int COPY = 0;

	private static final int UPCASE = 1;

	private static final int DOWNCASE = 2;

	private static final int CAPITALIZE = 3;

	private WasmStringRuntimeBuilder() {
	}

	/**
	 * Builds {@code _string_upcase} (when {@code upcase} is true) or
	 * {@code _string_downcase} (param 0 = string).
	 * @param upcase whether to upcase (otherwise downcase)
	 * @return the function body
	 */
	static byte[] buildCaseConvertBody(boolean upcase) {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		// Locals 1..6: pos, end, start, cur, b, fb (all i32).
		declareI32Locals(w, 6);
		int pos = 1, end = 2, start = 3, cur = 4, b = 5, fb = 6;
		emitDesignatorContentRange(w, 0, pos, end, fb);
		emitBuildCore(w, pos, end, start, cur, b, -1, upcase ? UPCASE : DOWNCASE);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	/**
	 * Builds {@code _string_capitalize} (param 0 = string).
	 * @return the function body
	 */
	static byte[] buildCapitalizeBody() {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		// Locals 1..7: pos, end, start, cur, b, atWordStart, fb.
		declareI32Locals(w, 7);
		int pos = 1, end = 2, start = 3, cur = 4, b = 5, ws = 6, fb = 7;
		emitDesignatorContentRange(w, 0, pos, end, fb);
		emitBuildCore(w, pos, end, start, cur, b, ws, CAPITALIZE);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	/**
	 * Builds {@code _subseq} (param 0 = sequence, param 1 = start, param 2 = end or nil).
	 * The sequence is either a string struct or a cons chain (nil = null); the runtime
	 * type is tested with {@code ref.test} to select the branch. For a string the content
	 * range is copied into a fresh quoted heap string; for a list the elements from
	 * {@code start} up to {@code end} are copied into a fresh cons chain. A nil
	 * {@code end} defaults to the sequence length.
	 * @return the function body
	 */
	static byte[] buildSubseqBody() {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		// ref locals 3..6: node, head, tail, newc.
		// i32 locals 7..14: pos, end, start, cur, b, startIdx, endIdx, ii.
		w.write(2);
		w.write(4);
		w.write(Type.REFNULL.code());
		w.writeHeapType(Type.EQ.code());
		w.write(8);
		w.write(Type.I32);
		int node = 3, head = 4, tail = 5, newc = 6;
		int pos = 7, end = 8, start = 9, cur = 10, b = 11, startIdx = 12, endIdx = 13, ii = 14;
		// startIdx = i31(startArg); endIdx = (endArg nil) ? -1 : i31(endArg)
		emitI31GetS(w, 1);
		set(w, startIdx);
		get(w, 2);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.IF);
		w.write(Type.I32);
		i32(w, -1);
		w.write(Instruction.ELSE);
		emitI31GetS(w, 2);
		w.write(Instruction.END);
		set(w, endIdx);
		// Dispatch: string struct vs list.
		get(w, 0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_STRING);
		w.write(Instruction.IF);
		w.write(Type.REFNULL.code());
		w.writeHeapType(Type.EQ.code());
		// --- String branch ---
		// pos = string.offset + 1 + startIdx
		emitStrOffset(w, 0);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		get(w, startIdx);
		w.write(Instruction.I32_ADD);
		set(w, pos);
		// end = (endIdx < 0) ? content end : string.offset + 1 + endIdx
		get(w, endIdx);
		i32(w, 0);
		w.write(Instruction.I32_LT_S);
		w.write(Instruction.IF);
		w.write(Type.I32);
		emitStrOffset(w, 0);
		emitStrLen(w, 0);
		w.write(Instruction.I32_ADD);
		i32(w, 1);
		w.write(Instruction.I32_SUB);
		w.write(Instruction.ELSE);
		emitStrOffset(w, 0);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		get(w, endIdx);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.END);
		set(w, end);
		emitBuildCore(w, pos, end, start, cur, b, -1, COPY);
		w.write(Instruction.ELSE);
		// --- List branch ---
		emitSubseqList(w, node, head, tail, newc, startIdx, endIdx, ii);
		w.write(Instruction.END); // dispatch if
		w.write(Instruction.END); // function
		return body.toByteArray();
	}

	// Copies the elements of the list in param 0 from index startIdxLocal up to
	// endIdxLocal (or to the end when endIdxLocal < 0) into a fresh cons chain, left on
	// the stack.
	private static void emitSubseqList(WasmWriter w, int node, int head, int tail, int newc, int startIdx, int endIdx,
			int ii) {
		// node = seq
		get(w, 0);
		set(w, node);
		// Skip the first startIdx cells: ii = 0; while (ii < startIdx && node is cons)
		i32(w, 0);
		set(w, ii);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		get(w, ii);
		get(w, startIdx);
		w.write(Instruction.I32_GE_S);
		w.write(Instruction.BR_IF, 1);
		get(w, node);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.BR_IF, 1);
		emitCdr(w, node);
		set(w, node);
		get(w, ii);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		set(w, ii);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END);
		w.write(Instruction.END);
		// head = null; tail = null; ii = startIdx
		w.write(Instruction.REF_NULL);
		w.writeHeapType(Type.EQ.code());
		set(w, head);
		w.write(Instruction.REF_NULL);
		w.writeHeapType(Type.EQ.code());
		set(w, tail);
		get(w, startIdx);
		set(w, ii);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		// stop when node is not a cons
		get(w, node);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.BR_IF, 1);
		// stop when endIdx >= 0 && ii >= endIdx
		get(w, endIdx);
		i32(w, 0);
		w.write(Instruction.I32_GE_S);
		w.write(Instruction.IF, 0x40);
		get(w, ii);
		get(w, endIdx);
		w.write(Instruction.I32_GE_S);
		w.write(Instruction.BR_IF, 2);
		w.write(Instruction.END);
		// newc = cons(car(node), nil)
		get(w, node);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.writeSignedLeb128(0);
		w.write(Instruction.REF_NULL);
		w.writeHeapType(Type.EQ.code());
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
		set(w, newc);
		// if (head is null) head = tail = newc; else tail.cdr = newc; tail = newc
		get(w, head);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.IF, 0x40);
		get(w, newc);
		set(w, head);
		get(w, newc);
		set(w, tail);
		w.write(Instruction.ELSE);
		get(w, tail);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		get(w, newc);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_SET);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.writeSignedLeb128(1);
		get(w, newc);
		set(w, tail);
		w.write(Instruction.END);
		// node = cdr(node); ii++
		emitCdr(w, node);
		set(w, node);
		get(w, ii);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		set(w, ii);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END);
		w.write(Instruction.END);
		// result = head
		get(w, head);
	}

	// Pushes cdr (field 1) of the cons held in the given local.
	private static void emitCdr(WasmWriter w, int local) {
		get(w, local);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.writeSignedLeb128(1);
	}

	/**
	 * Builds {@code _string_eq} ({@code ignoreCase} false) or {@code _string_equal}
	 * ({@code ignoreCase} true). Params 0 and 1 are strings. Returns the symbol {@code t}
	 * on equality, otherwise nil.
	 * @param ignoreCase whether the comparison is case-insensitive (ASCII)
	 * @param st the string table (to intern the {@code t} symbol)
	 * @return the function body
	 */
	static byte[] buildStringEqBody(boolean ignoreCase, WasmLispCompiler.StringTable st) {
		WasmLispCompiler.StringTable.StringEntry t = st.addString("t");
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		// Locals 2..7: i, aOff, bOff, n, ca, cb.
		declareI32Locals(w, 6);
		int i = 2, aOff = 3, bOff = 4, n = 5, ca = 6, cb = 7;
		// Result block: a string struct (t) or null (nil).
		w.write(Instruction.BLOCK);
		w.write(Type.REFNULL.code());
		w.writeHeapType(Type.EQ.code());
		// if (a.length != b.length) return nil
		emitStrLen(w, 0);
		emitStrLen(w, 1);
		w.write(Instruction.I32_NE);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.REF_NULL);
		w.writeHeapType(Type.EQ.code());
		w.write(Instruction.BR, 1);
		w.write(Instruction.END);
		// aOff = a.offset; bOff = b.offset; n = a.length; i = 0
		emitStrOffset(w, 0);
		set(w, aOff);
		emitStrOffset(w, 1);
		set(w, bOff);
		emitStrLen(w, 0);
		set(w, n);
		i32(w, 0);
		set(w, i);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		get(w, i);
		get(w, n);
		w.write(Instruction.I32_GE_U);
		w.write(Instruction.BR_IF, 1);
		// ca = mem[aOff + i]; cb = mem[bOff + i]
		get(w, aOff);
		get(w, i);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);
		set(w, ca);
		get(w, bOff);
		get(w, i);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);
		set(w, cb);
		// if (norm(ca) != norm(cb)) return nil
		emitMaybeLower(w, ca, ignoreCase);
		emitMaybeLower(w, cb, ignoreCase);
		w.write(Instruction.I32_NE);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.REF_NULL);
		w.writeHeapType(Type.EQ.code());
		w.write(Instruction.BR, 3);
		w.write(Instruction.END);
		get(w, i);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		set(w, i);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END);
		w.write(Instruction.END);
		// all bytes equal -> t
		i32(w, t.offset());
		i32(w, t.length());
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_STRING);
		w.write(Instruction.END); // result block
		w.write(Instruction.END); // function
		return body.toByteArray();
	}

	/**
	 * Builds {@code _string_trim} (param 0 = char bag, param 1 = string, param 2 = mode:
	 * 0 = both ends, 1 = left only, 2 = right only).
	 * @return the function body
	 */
	static byte[] buildTrimBody() {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		// Locals 3..13: bagStart, bagEnd, lo, hi, mode, c, found, scan, start, cur, b.
		declareI32Locals(w, 11);
		int bagStart = 3, bagEnd = 4, lo = 5, hi = 6, mode = 7, c = 8, found = 9, scan = 10, start = 11, cur = 12,
				b = 13;
		// bagStart = bag.offset + 1; bagEnd = bag.offset + bag.length - 1
		emitStrOffset(w, 0);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		set(w, bagStart);
		emitStrOffset(w, 0);
		emitStrLen(w, 0);
		w.write(Instruction.I32_ADD);
		i32(w, 1);
		w.write(Instruction.I32_SUB);
		set(w, bagEnd);
		// lo = string.offset + 1; hi = string.offset + string.length - 1
		emitStrOffset(w, 1);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		set(w, lo);
		emitStrOffset(w, 1);
		emitStrLen(w, 1);
		w.write(Instruction.I32_ADD);
		i32(w, 1);
		w.write(Instruction.I32_SUB);
		set(w, hi);
		// mode = i31(modeArg)
		emitI31GetS(w, 2);
		set(w, mode);
		// Left trim (mode != 2): advance lo while in bag and lo < hi.
		get(w, mode);
		i32(w, 2);
		w.write(Instruction.I32_NE);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		get(w, lo);
		get(w, hi);
		w.write(Instruction.I32_GE_U);
		w.write(Instruction.BR_IF, 1);
		get(w, lo);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);
		set(w, c);
		emitInBag(w, c, bagStart, bagEnd, found, scan);
		get(w, found);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.BR_IF, 1);
		get(w, lo);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		set(w, lo);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END);
		w.write(Instruction.END);
		w.write(Instruction.END); // if
		// Right trim (mode != 1): retreat hi while in bag and hi > lo.
		get(w, mode);
		i32(w, 1);
		w.write(Instruction.I32_NE);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		get(w, hi);
		get(w, lo);
		w.write(Instruction.I32_LE_U);
		w.write(Instruction.BR_IF, 1);
		get(w, hi);
		i32(w, 1);
		w.write(Instruction.I32_SUB);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);
		set(w, c);
		emitInBag(w, c, bagStart, bagEnd, found, scan);
		get(w, found);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.BR_IF, 1);
		get(w, hi);
		i32(w, 1);
		w.write(Instruction.I32_SUB);
		set(w, hi);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END);
		w.write(Instruction.END);
		w.write(Instruction.END); // if
		emitBuildCore(w, lo, hi, start, cur, b, -1, COPY);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	// --- Shared emit helpers ---

	// Declares a single group of n locals, all i32.
	private static void declareI32Locals(WasmWriter w, int n) {
		w.write(1);
		w.write(n);
		w.write(Type.I32);
	}

	private static void get(WasmWriter w, int local) {
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(local);
	}

	private static void set(WasmWriter w, int local) {
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(local);
	}

	private static void i32(WasmWriter w, int value) {
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(value);
	}

	// Pushes the offset field (0) of the string at the given param local.
	private static void emitStrOffset(WasmWriter w, int paramLocal) {
		get(w, paramLocal);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_STRING);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_STRING);
		w.writeSignedLeb128(0);
	}

	// Pushes the length field (1) of the string at the given param local.
	private static void emitStrLen(WasmWriter w, int paramLocal) {
		get(w, paramLocal);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_STRING);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_STRING);
		w.writeSignedLeb128(1);
	}

	// Unboxes the i31 integer at the given param local to an i32 on the stack.
	private static void emitI31GetS(WasmWriter w, int paramLocal) {
		get(w, paramLocal);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(Type.I31.code());
		w.write(Instruction.GC_PREFIX, Instruction.I31_GET_S);
	}

	// Sets posLocal/endLocal to the content byte range (between the surrounding quotes)
	// of
	// the string at the given param local.
	private static void emitContentRange(WasmWriter w, int paramLocal, int posLocal, int endLocal) {
		emitStrOffset(w, paramLocal);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		set(w, posLocal);
		emitStrOffset(w, paramLocal);
		emitStrLen(w, paramLocal);
		w.write(Instruction.I32_ADD);
		i32(w, 1);
		w.write(Instruction.I32_SUB);
		set(w, endLocal);
	}

	// Sets posLocal/endLocal to the string-designator content byte range of the value at
	// the given param local, so the case functions accept a symbol/keyword as well as a
	// string (CL string-designator coercion). A real string ({@code "abc"}) uses the
	// range
	// between its surrounding quotes; a symbol (bare name, no quotes) uses its whole name
	// minus a leading keyword colon. Either way the build core re-wraps the copied
	// content
	// in quotes, yielding a proper string. fbLocal is scratch for the first content byte.
	private static void emitDesignatorContentRange(WasmWriter w, int paramLocal, int posLocal, int endLocal,
			int fbLocal) {
		// posLocal := offset; fbLocal := mem[offset] (the first byte)
		emitStrOffset(w, paramLocal);
		set(w, posLocal);
		get(w, posLocal);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);
		set(w, fbLocal);
		// A leading quote marks a real string; anything else is a symbol name.
		get(w, fbLocal);
		i32(w, QUOTE);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.IF, 0x40);
		// String: pos = offset + 1; end = offset + len - 1 (strip the surrounding
		// quotes).
		get(w, posLocal);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		set(w, posLocal);
		emitStrOffset(w, paramLocal);
		emitStrLen(w, paramLocal);
		w.write(Instruction.I32_ADD);
		i32(w, 1);
		w.write(Instruction.I32_SUB);
		set(w, endLocal);
		w.write(Instruction.ELSE);
		// Symbol: drop a leading keyword colon; content runs to the full length.
		get(w, fbLocal);
		i32(w, COLON);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.IF, 0x40);
		get(w, posLocal);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		set(w, posLocal);
		w.write(Instruction.END);
		emitStrOffset(w, paramLocal);
		emitStrLen(w, paramLocal);
		w.write(Instruction.I32_ADD);
		set(w, endLocal);
		w.write(Instruction.END);
	}

	// Copies bytes [posLocal, endLocal) into a fresh heap string (wrapped in quotes),
	// applying the given transform per byte, and leaves the new string struct on the
	// stack.
	private static void emitBuildCore(WasmWriter w, int posL, int endL, int startL, int curL, int bL, int wsL,
			int mode) {
		// start = HEAP_PTR; mem[start] = '"'; cur = start + 1
		i32(w, WasmLispCompiler.HEAP_PTR_ADDR);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		set(w, startL);
		// Ensure the whole output [start, start + (end-pos) + 2) fits before writing.
		WasmEmitHelper.emitGrowHeapTo(w, () -> {
			get(w, startL);
			get(w, endL);
			get(w, posL);
			w.write(Instruction.I32_SUB);
			w.write(Instruction.I32_ADD);
			i32(w, 2);
			w.write(Instruction.I32_ADD);
		});
		get(w, startL);
		i32(w, QUOTE);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		get(w, startL);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		set(w, curL);
		if (mode == CAPITALIZE) {
			i32(w, 1);
			set(w, wsL);
		}
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		get(w, posL);
		get(w, endL);
		w.write(Instruction.I32_GE_U);
		w.write(Instruction.BR_IF, 1);
		get(w, posL);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);
		set(w, bL);
		emitTransform(w, bL, wsL, mode);
		get(w, curL);
		get(w, bL);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		get(w, curL);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		set(w, curL);
		get(w, posL);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		set(w, posL);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END);
		w.write(Instruction.END);
		// mem[cur] = '"'; HEAP_PTR = cur + 1
		get(w, curL);
		i32(w, QUOTE);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		i32(w, WasmLispCompiler.HEAP_PTR_ADDR);
		get(w, curL);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		// struct.new string(start, cur + 1 - start)
		get(w, startL);
		get(w, curL);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		get(w, startL);
		w.write(Instruction.I32_SUB);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_STRING);
	}

	// Applies the case transform to the byte held in bL (in place).
	private static void emitTransform(WasmWriter w, int bL, int wsL, int mode) {
		switch (mode) {
			case COPY -> {
			}
			case UPCASE -> emitToUpper(w, bL);
			case DOWNCASE -> emitToLower(w, bL);
			case CAPITALIZE -> {
				emitIsAlnum(w, bL);
				w.write(Instruction.IF, 0x40);
				get(w, wsL);
				w.write(Instruction.IF, 0x40);
				emitToUpper(w, bL);
				w.write(Instruction.ELSE);
				emitToLower(w, bL);
				w.write(Instruction.END);
				i32(w, 0);
				set(w, wsL);
				w.write(Instruction.ELSE);
				i32(w, 1);
				set(w, wsL);
				w.write(Instruction.END);
			}
			default -> throw new IllegalArgumentException("Unknown transform mode: " + mode);
		}
	}

	// if (b in 'a'..'z') b -= 32
	private static void emitToUpper(WasmWriter w, int bL) {
		emitInRange(w, bL, 97, 122);
		w.write(Instruction.IF, 0x40);
		get(w, bL);
		i32(w, 32);
		w.write(Instruction.I32_SUB);
		set(w, bL);
		w.write(Instruction.END);
	}

	// if (b in 'A'..'Z') b += 32
	private static void emitToLower(WasmWriter w, int bL) {
		emitInRange(w, bL, 65, 90);
		w.write(Instruction.IF, 0x40);
		get(w, bL);
		i32(w, 32);
		w.write(Instruction.I32_ADD);
		set(w, bL);
		w.write(Instruction.END);
	}

	// Pushes 1 if the byte in bL is in [lo, hi] (unsigned), else 0.
	private static void emitInRange(WasmWriter w, int bL, int lo, int hi) {
		get(w, bL);
		i32(w, lo);
		w.write(Instruction.I32_GE_U);
		get(w, bL);
		i32(w, hi);
		w.write(Instruction.I32_LE_U);
		w.write(Instruction.I32_AND);
	}

	// Pushes 1 if the byte in bL is ASCII alphanumeric, else 0.
	private static void emitIsAlnum(WasmWriter w, int bL) {
		emitInRange(w, bL, 48, 57);
		emitInRange(w, bL, 65, 90);
		w.write(Instruction.I32_OR);
		emitInRange(w, bL, 97, 122);
		w.write(Instruction.I32_OR);
	}

	// Pushes the byte in the given local, ASCII-lowercased when ignoreCase is set.
	private static void emitMaybeLower(WasmWriter w, int local, boolean ignoreCase) {
		if (!ignoreCase) {
			get(w, local);
			return;
		}
		// b + ((b >= 'A' && b <= 'Z') ? 32 : 0)
		get(w, local);
		emitInRange(w, local, 65, 90);
		i32(w, 32);
		w.write(Instruction.I32_MUL);
		w.write(Instruction.I32_ADD);
	}

	// Sets foundLocal to 1 if the byte in cLocal occurs in the bag [bagStart, bagEnd),
	// else 0. scanLocal is a scratch cursor.
	private static void emitInBag(WasmWriter w, int cLocal, int bagStartLocal, int bagEndLocal, int foundLocal,
			int scanLocal) {
		i32(w, 0);
		set(w, foundLocal);
		get(w, bagStartLocal);
		set(w, scanLocal);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		get(w, scanLocal);
		get(w, bagEndLocal);
		w.write(Instruction.I32_GE_U);
		w.write(Instruction.BR_IF, 1);
		get(w, scanLocal);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);
		get(w, cLocal);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.IF, 0x40);
		i32(w, 1);
		set(w, foundLocal);
		w.write(Instruction.BR, 2);
		w.write(Instruction.END);
		get(w, scanLocal);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		set(w, scanLocal);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END);
		w.write(Instruction.END);
	}

}
