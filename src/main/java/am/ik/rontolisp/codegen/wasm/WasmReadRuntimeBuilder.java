package am.ik.rontolisp.codegen.wasm;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import am.ik.rontolisp.ClosRegistry;
import am.ik.rontolisp.EmittedReaderInitforms;
import am.ik.rontolisp.LispLayout;
import am.ik.rontolisp.PackageRegistry;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;
import am.ik.wasm.WasmWriter;
import org.jspecify.annotations.Nullable;

/**
 * Builds the WASM bytecode for the runtime Lisp reader used by the {@code read} and
 * {@code load} built-ins. The generated module is standalone, so a combined lexer/parser
 * is emitted directly into it (mirroring {@link WasmEvalRuntimeBuilder}).
 *
 * <p>
 * The reader walks UTF-8 bytes in linear memory between a cursor
 * ({@code READ_CURSOR_ADDR}) and an end offset ({@code READ_END_ADDR}), producing values
 * in the shared runtime representation: {@code ref.null eq} for nil, an i31ref for
 * integers, a {@code TYPE_STRING} struct {@code {offset,length}} for symbols (including
 * {@code t}, an ordinary interned symbol {@code T}) and string literals, and a
 * {@code TYPE_CONS} struct for cons cells. The token bytes are upcased in place before
 * interning (uppercase-canonical: the reader upcases every unescaped symbol character
 * like CL's {@code :upcase} readtable case, with no fold back to a lowercase form).
 * Symbols are interned via {@code _intern} so their string-table offset matches what the
 * eval runtime compares against.
 *
 * <p>
 * Integers are signed 31-bit (no big integers). Decimal floats (optional leading
 * {@code -}, digits and one {@code .}) parse to a {@code TYPE_FLOAT} struct; there is no
 * exponent support, matching the documented compiled-eval limitations.
 */
final class WasmReadRuntimeBuilder {

	private static final int CURSOR = WasmLispCompiler.READ_CURSOR_ADDR;

	private static final int END_ADDR = WasmLispCompiler.READ_END_ADDR;

	private static final int HEAP = WasmLispCompiler.HEAP_PTR_ADDR;

	private static final int QUOTE_LEN = 5; // "quote"

	private static final int FUNCTION_LEN = 8; // "function"

	private WasmReadRuntimeBuilder() {
	}

	/** A static message's (offset, length) in the interned string data. */
	record Msg(int off, int len) {
	}

	/**
	 * Everything the reader bodies need beyond the function indices: the canonical symbol
	 * offsets, the error mode (EH mode throws a catchable {@code $lisp-cond} with a
	 * static message; otherwise every reader error is an {@code unreachable} trap, the
	 * runtime-helper convention), the {@code --simd} packed-array layout switch, the
	 * instance type (or -1 when instances cannot exist), and the baked blobs: the
	 * character-name table and the {@code #S} struct directory.
	 */
	record ReadCtx(int nilOffset, int quoteOffset, int functionOffset, boolean ehMode, boolean simd,
			int instanceTypeIndex, int structDirBase, int structDirCount, int charNamesBase, int charNamesCount,
			Msg msgEof, Msg msgCharEof, Msg msgCharName, Msg msgRadix, Msg msgRank, Msg msgRagged, Msg msgNested,
			Msg msgProper, Msg msgPackedNum, Msg msgReadEval, Msg msgFeature, Msg msgLabels, Msg msgBlockComment,
			Msg msgStructType, Msg msgStructClassHint, Msg msgStructName, Msg msgStructEmpty, Msg msgStructOdd,
			Msg msgStructNoSlot, Msg msgStructInit, Msg msgDivZero) {
	}

	/**
	 * The frontend lexer's named characters, reader spelling to code point. Matching is
	 * case-insensitive at run time; the printer emits the capitalized spellings
	 * (Space...), which are all here, so print -> read round-trips.
	 */
	private static final Object[][] CHAR_NAMES = { { "space", 32 }, { "newline", 10 }, { "linefeed", 10 }, { "lf", 10 },
			{ "tab", 9 }, { "return", 13 }, { "cr", 13 }, { "page", 12 }, { "backspace", 8 }, { "nul", 0 },
			{ "null", 0 }, { "rubout", 127 }, { "delete", 127 }, { "del", 127 }, { "escape", 27 }, { "altmode", 27 },
			{ "esc", 27 } };

	/**
	 * Interns every blob and message the reader bodies reference and returns the bundle.
	 * Must run BEFORE the string table is snapshotted (like the intern blob and the
	 * case-fold tables).
	 * @param st the open string table
	 * @param nilOffset canonical NIL offset
	 * @param quoteOffset canonical QUOTE offset
	 * @param functionOffset canonical FUNCTION offset
	 * @param ehMode whether reader errors throw on the {@code $lisp-cond} tag
	 * @param simd whether packed float arrays use the {@code TYPE_VBLOCK} layout
	 * @param instanceTypeIndex {@code TYPE_INSTANCE}'s index, or -1 when instances off
	 * @param registry the layout registry (for the {@code #S} directory)
	 * @param layoutAddresses baked layout record addresses by tag
	 * @return the reader context
	 */
	static ReadCtx buildReadCtx(WasmLispCompiler.StringTable st, int nilOffset, int quoteOffset, int functionOffset,
			boolean ehMode, boolean simd, int instanceTypeIndex, ClosRegistry registry,
			Map<String, Integer> layoutAddresses) {
		// character-name table: {nameOff, nameLen, code} triples
		int[][] nameRefs = new int[CHAR_NAMES.length][3];
		for (int i = 0; i < CHAR_NAMES.length; i++) {
			String name = (String) CHAR_NAMES[i][0];
			nameRefs[i][0] = st.addString(name).offset();
			nameRefs[i][1] = name.length();
			nameRefs[i][2] = (Integer) CHAR_NAMES[i][1];
		}
		ByteArrayOutputStream names = new ByteArrayOutputStream();
		for (int[] ref : nameRefs) {
			writeI32(names, ref[0]);
			writeI32(names, ref[1]);
			writeI32(names, ref[2]);
		}
		int charNamesBase = st.appendBlob(names.toByteArray());
		// #S struct directory (instances on only): count, then 28-byte entries
		// {layoutAddr, pkgOff, pkgLen, memberOff, memberLen, kind, initsRel}, then the
		// per-struct initform tables {initOff, initLen} (initLen: -1 = signal, 0 = nil,
		// >0 = re-readable constant text at initOff). initsRel is relative to the
		// directory base.
		int structDirBase = 0;
		int structDirCount = 0;
		if (instanceTypeIndex >= 0) {
			List<LispLayout> layouts = new ArrayList<>(registry.layouts().values());
			structDirCount = layouts.size();
			int headerSize = 4 + 28 * layouts.size();
			List<int[]> entries = new ArrayList<>();
			ByteArrayOutputStream inits = new ByteArrayOutputStream();
			for (LispLayout layout : layouts) {
				boolean isStruct = layout.kind() == LispLayout.Kind.STRUCT;
				String prefix = isStruct ? LispLayout.STRUCT_TAG_PREFIX : LispLayout.CLASS_TAG_PREFIX;
				String registered = layout.tag().substring(prefix.length());
				PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(registered);
				String pkg = qn == null ? "" : qn.pkg();
				String member = qn == null ? registered : qn.member();
				int pkgOff = pkg.isEmpty() ? 0 : st.addString(pkg).offset();
				int memberOff = st.addString(member).offset();
				Integer layoutAddr = layoutAddresses.get(layout.tag());
				if (layoutAddr == null) {
					throw new IllegalStateException("Layout not baked for the reader directory: " + layout.tag());
				}
				int initsRel = 0;
				if (isStruct) {
					initsRel = headerSize + inits.size();
					@Nullable String[] texts = EmittedReaderInitforms.initTexts(layout, true);
					for (@Nullable
					String text : texts) {
						if (text == null) {
							writeI32(inits, 0);
							writeI32(inits, 0);
						}
						else if (text.charAt(0) == EmittedReaderInitforms.SIGNAL_MARKER) {
							writeI32(inits, 0);
							writeI32(inits, -1);
						}
						else {
							byte[] textBytes = text.getBytes(StandardCharsets.UTF_8);
							writeI32(inits, st.addString(text).offset());
							writeI32(inits, textBytes.length);
						}
					}
				}
				entries.add(new int[] { layoutAddr, pkgOff, utf8Len(pkg), memberOff, utf8Len(member), isStruct ? 0 : 1,
						initsRel });
			}
			ByteArrayOutputStream dir = new ByteArrayOutputStream();
			writeI32(dir, structDirCount);
			for (int[] entry : entries) {
				for (int v : entry) {
					writeI32(dir, v);
				}
			}
			dir.writeBytes(inits.toByteArray());
			structDirBase = st.appendBlob(dir.toByteArray());
		}
		return new ReadCtx(nilOffset, quoteOffset, functionOffset, ehMode, simd, instanceTypeIndex, structDirBase,
				structDirCount, charNamesBase, CHAR_NAMES.length, msg(st, "Unexpected end of input, expected ')'"),
				msg(st, "Unexpected end of input after #\\"), msg(st, "Unknown character name after #\\"),
				msg(st, "Invalid digits after #x/#o/#b"), msg(st, "Invalid array rank"),
				msg(st, "ragged array contents"), msg(st, "expected a nested list in array contents"),
				msg(st, "array contents must be proper lists"), msg(st, "packed float array: expected a number"),
				msg(st, "#. read-time evaluation is not supported"),
				msg(st, "#+/#- feature conditionals are not supported by the compiled runtime reader"),
				msg(st, "reader labels (#N=/#N#) are not supported by the compiled runtime reader"),
				msg(st, "Unterminated block comment"), msg(st, "#S: not a defined structure type"),
				msg(st, "#S: the name names a class; #S reads defstruct types only"),
				msg(st, "#S: expected a structure type name"), msg(st, "#S(): a structure literal needs a type name"),
				msg(st, "#S: odd number of slot name/value items in a structure literal"),
				msg(st, "#S: no slot with that name"),
				msg(st, "#S: an omitted slot's initform is not readable at run time"),
				msg(st, "Division by zero in ratio literal"));
	}

	private static Msg msg(WasmLispCompiler.StringTable st, String text) {
		// The runtime string representation is quote-framed, so an EH-mode throw builds
		// the message through _str_build over framed bytes (like a compiled %error's
		// string literal argument).
		String framed = "\"" + text + "\"";
		return new Msg(st.addString(framed).offset(), framed.getBytes(StandardCharsets.UTF_8).length);
	}

	private static int utf8Len(String s) {
		return s.getBytes(StandardCharsets.UTF_8).length;
	}

	private static void writeI32(ByteArrayOutputStream out, int v) {
		out.write(v & 0xFF);
		out.write((v >>> 8) & 0xFF);
		out.write((v >>> 16) & 0xFF);
		out.write((v >>> 24) & 0xFF);
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

	private static void structNew(WasmWriter w, int type) {
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeUnsignedLeb128(type);
	}

	private static void structGet(WasmWriter w, int type, int field) {
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(type);
		w.writeUnsignedLeb128(field);
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

	private static void call(WasmWriter w, int func) {
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(func);
	}

	private static void refTest(WasmWriter w, int heapType) {
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(heapType);
	}

	private static void i31GetS(WasmWriter w) {
		w.write(Instruction.GC_PREFIX, Instruction.I31_GET_S);
	}

	private static void arrayGet(WasmWriter w, int type) {
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_GET);
		w.writeUnsignedLeb128(type);
	}

	private static void arraySet(WasmWriter w, int type) {
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_SET);
		w.writeUnsignedLeb128(type);
	}

	private static void arrayNew(WasmWriter w, int type) {
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_NEW);
		w.writeUnsignedLeb128(type);
	}

	private static void arrayLen(WasmWriter w) {
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_LEN);
	}

	/** Stores the i32 pushed by {@code pushValue} into linear memory at {@code addr}. */
	private static void storeMem32(WasmWriter w, int addr, Runnable pushValue) {
		i32(w, addr);
		pushValue.run();
		w.write(Instruction.I32_STORE, 0x02, 0x00);
	}

	/**
	 * Emits a reader error: in EH mode a catchable {@code throw $lisp-cond (nil .
	 * message)} with the baked static message (WASM cannot interpolate, so unlike the
	 * JVM's messages these carry no names); otherwise the runtime-helper convention, a
	 * bare {@code unreachable} trap. Both are stack-polymorphic terminators, so this can
	 * be emitted in any stack context.
	 */
	private static void emitErr(WasmWriter w, ReadCtx ctx, Msg msg) {
		if (!ctx.ehMode()) {
			w.write(Instruction.UNREACHABLE);
			return;
		}
		emitNull(w);
		i32(w, msg.off());
		i32(w, msg.len());
		WasmEmitHelper.emitStrBuildCall(w);
		structNew(w, WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.THROW);
		w.writeUnsignedLeb128(WasmLispCompiler.TAG_LISP_COND);
	}

	/** Pushes the byte at {@code cursor + offset} (caller guarantees bounds). */
	private static void byteAtCursorPlus(WasmWriter w, int offset) {
		loadMem32(w, CURSOR);
		i32(w, offset);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);
	}

	/** Pushes {@code cursor + offset < end} as i32. */
	private static void cursorPlusLtEnd(WasmWriter w, int offset) {
		loadMem32(w, CURSOR);
		i32(w, offset);
		w.write(Instruction.I32_ADD);
		loadMem32(w, END_ADDR);
		w.write(Instruction.I32_LT_S);
	}

	/**
	 * Emits an inline whitespace/comment skipper advancing the cursor: whitespace,
	 * {@code ;} line comments and (nesting) {@code #| ... |#} block comments, mirroring
	 * the frontend lexer. Uses no locals (the nesting depth counts through the
	 * {@code RD_DEPTH_ADDR} cell) so it can be inlined into multiple functions.
	 */
	private static void emitSkipWs(WasmWriter w, ReadCtx ctx) {
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
		// if "#|": nesting block comment
		curByte(w);
		i32(w, '#');
		w.write(Instruction.I32_EQ);
		cursorPlusLtEnd(w, 1);
		w.write(Instruction.I32_AND);
		byteAtCursorPlus(w, 1);
		i32(w, '|');
		w.write(Instruction.I32_EQ);
		w.write(Instruction.I32_AND);
		ifVoid(w);
		advanceCursor(w); // consume '#'
		advanceCursor(w); // consume '|'
		storeMem32(w, WasmLispCompiler.RD_DEPTH_ADDR, () -> i32(w, 1));
		loop(w); // comment loop: depths here 0=this loop, 1=the if, 2=outer loop
		// input exhausted inside the comment: unterminated
		loadMem32(w, CURSOR);
		loadMem32(w, END_ADDR);
		w.write(Instruction.I32_GE_S);
		ifVoid(w);
		emitErr(w, ctx, ctx.msgBlockComment());
		end(w);
		// "|#" -> depth--, back to whitespace skipping at 0
		curByte(w);
		i32(w, '|');
		w.write(Instruction.I32_EQ);
		cursorPlusLtEnd(w, 1);
		w.write(Instruction.I32_AND);
		byteAtCursorPlus(w, 1);
		i32(w, '#');
		w.write(Instruction.I32_EQ);
		w.write(Instruction.I32_AND);
		ifVoid(w);
		advanceCursor(w);
		advanceCursor(w);
		storeMem32(w, WasmLispCompiler.RD_DEPTH_ADDR, () -> {
			loadMem32(w, WasmLispCompiler.RD_DEPTH_ADDR);
			i32(w, 1);
			w.write(Instruction.I32_SUB);
		});
		loadMem32(w, WasmLispCompiler.RD_DEPTH_ADDR);
		w.write(Instruction.I32_EQZ);
		brIf(w, 3); // depth 0: continue the outer whitespace loop
		br(w, 1); // continue the comment loop
		end(w);
		// "#|" -> depth++
		curByte(w);
		i32(w, '#');
		w.write(Instruction.I32_EQ);
		cursorPlusLtEnd(w, 1);
		w.write(Instruction.I32_AND);
		byteAtCursorPlus(w, 1);
		i32(w, '|');
		w.write(Instruction.I32_EQ);
		w.write(Instruction.I32_AND);
		ifVoid(w);
		advanceCursor(w);
		advanceCursor(w);
		storeMem32(w, WasmLispCompiler.RD_DEPTH_ADDR, () -> {
			loadMem32(w, WasmLispCompiler.RD_DEPTH_ADDR);
			i32(w, 1);
			w.write(Instruction.I32_ADD);
		});
		br(w, 1); // continue the comment loop
		end(w);
		advanceCursor(w);
		br(w, 0); // continue the comment loop
		end(w); // comment loop
		end(w); // if "#|"
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
	 * @param internBase the compile-time intern table's base address
	 * @param internCount the compile-time intern table's entry count
	 * @param recordHighWater when true, also record the pool's new top in
	 * {@code RT_INTERN_HEAP_ADDR}, which is what {@code __ronto_alloc_reset} refuses to
	 * pop below. Only the host-arena modules need it, so every other module's body stays
	 * byte-identical.
	 */
	static byte[] buildInternBody(int internBase, int internCount, boolean recordHighWater) {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		// params: off=0, len=1 ; locals: IDX=2, EOFF=3, ELEN=4, K=5, COUNT=6, POOL=7,
		// CK=8
		w.write(1);
		w.write(7);
		w.write(Type.I32);
		final int OFF = 0, LEN = 1, IDX = 2, EOFF = 3, ELEN = 4, K = 5, COUNT = 6, POOL = 7, CK = 8;

		// The runtime table's base is not a constant: it is seeded at instantiation
		// (RT_INTERN_BASE_ADDR cell) from the program's actual static-data size, so the
		// records can never overwrite the interned-string segment.
		Runnable emitRtBase = () -> loadMem32(w, WasmLispCompiler.RT_INTERN_BASE_ADDR);
		// 1. compile-time table (constant count)
		i32(w, internCount);
		setLocal(w, COUNT);
		emitInternScan(w, () -> i32(w, internBase), OFF, LEN, IDX, EOFF, ELEN, K, COUNT);
		// 2. runtime table (count from memory)
		loadMem32(w, WasmLispCompiler.RT_INTERN_COUNT_ADDR);
		setLocal(w, COUNT);
		emitInternScan(w, emitRtBase, OFF, LEN, IDX, EOFF, ELEN, K, COUNT);
		// 3. miss: copy the token into stable heap storage at HEAP_PTR (advanced
		// PERMANENTLY -- an interned symbol's bytes legitimately persist across calls,
		// unlike a transient string build which stack-pops HEAP_PTR). This makes the
		// record and the returned canonical offset independent of the caller's source
		// bytes (which for a runtime string / a reused reader input buffer no longer
		// persist once the string heap is a stack). Append (poolOff, len), return
		// poolOff.
		// pool = HEAP_PTR
		loadMem32(w, WasmLispCompiler.HEAP_PTR_ADDR);
		setLocal(w, POOL);
		// Ensure [pool, pool+len) is within linear memory.
		WasmEmitHelper.emitGrowHeapTo(w, () -> {
			getLocal(w, POOL);
			getLocal(w, LEN);
			w.write(Instruction.I32_ADD);
		});
		// ck = 0 ; while (ck < len) { mem[pool+ck] = mem[off+ck]; ck++ }
		i32(w, 0);
		setLocal(w, CK);
		block(w);
		loop(w);
		getLocal(w, CK);
		getLocal(w, LEN);
		w.write(Instruction.I32_GE_S);
		brIf(w, 1);
		getLocal(w, POOL);
		getLocal(w, CK);
		w.write(Instruction.I32_ADD);
		getLocal(w, OFF);
		getLocal(w, CK);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		getLocal(w, CK);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		setLocal(w, CK);
		br(w, 0);
		end(w); // loop
		end(w); // block
		// HEAP_PTR = pool + len (permanent -- the pooled token is the symbol's stable id)
		i32(w, WasmLispCompiler.HEAP_PTR_ADDR);
		getLocal(w, POOL);
		getLocal(w, LEN);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		if (recordHighWater) {
			// RT_INTERN_HEAP = pool + len: the floor a host arena reset may not pop
			// below.
			i32(w, WasmLispCompiler.RT_INTERN_HEAP_ADDR);
			getLocal(w, POOL);
			getLocal(w, LEN);
			w.write(Instruction.I32_ADD);
			w.write(Instruction.I32_STORE, 0x02, 0x00);
		}
		// mem[rtBase + count*8] = pool
		emitRtBase.run();
		getLocal(w, COUNT);
		i32(w, 8);
		w.write(Instruction.I32_MUL);
		w.write(Instruction.I32_ADD);
		getLocal(w, POOL);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		// mem[rtBase + count*8 + 4] = len
		emitRtBase.run();
		getLocal(w, COUNT);
		i32(w, 8);
		w.write(Instruction.I32_MUL);
		w.write(Instruction.I32_ADD);
		i32(w, 4);
		w.write(Instruction.I32_ADD);
		getLocal(w, LEN);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		// mem[RT_COUNT] = count + 1
		i32(w, WasmLispCompiler.RT_INTERN_COUNT_ADDR);
		getLocal(w, COUNT);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		getLocal(w, POOL);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	/**
	 * Emits a scan of {@code COUNT} {@code (offset,length)} entries starting at the base
	 * address pushed by {@code emitBase}; on the first byte-equal entry returns its
	 * offset from the function.
	 */
	private static void emitInternScan(WasmWriter w, Runnable emitBase, int OFF, int LEN, int IDX, int EOFF, int ELEN,
			int K, int COUNT) {
		i32(w, 0);
		setLocal(w, IDX);
		block(w);
		loop(w);
		// if IDX >= COUNT: break
		getLocal(w, IDX);
		getLocal(w, COUNT);
		w.write(Instruction.I32_GE_S);
		brIf(w, 1);
		// EOFF = mem[base + IDX*8]
		emitBase.run();
		getLocal(w, IDX);
		i32(w, 8);
		w.write(Instruction.I32_MUL);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		setLocal(w, EOFF);
		// ELEN = mem[base + IDX*8 + 4]
		emitBase.run();
		getLocal(w, IDX);
		i32(w, 8);
		w.write(Instruction.I32_MUL);
		w.write(Instruction.I32_ADD);
		i32(w, 4);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		setLocal(w, ELEN);
		// if ELEN == len AND EOFF != 0: compare bytes. No real entry sits at address 0
		// (static entries start at the data base, runtime ones in the heap), so a zero
		// EOFF is the hole a tree-shaken row reads as -- skipping it keeps a cut entry
		// from matching a zero-length probe.
		getLocal(w, ELEN);
		getLocal(w, LEN);
		w.write(Instruction.I32_EQ);
		getLocal(w, EOFF);
		i32(w, 0);
		w.write(Instruction.I32_NE);
		w.write(Instruction.I32_AND);
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

	static byte[] buildReadExprBody(ReadCtx ctx) {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		// ref locals: CAR=0, CDR=1 ; i32 locals: BYTE=2, START=3, LEN=4, OFF=5, POS=6,
		// ESC=7, HP=8, NEG=9, ACC=10, VALID=11, SAWDOT=12, C2=13 ; f64 locals: FVAL=14,
		// FPLACE=15 ; ref local: ACC64=16 (the decimal-integer accumulator, a
		// tier-aware exact integer stepped through _big_grow so a token past the i31
		// range reads as a boxed or limb integer like the frontend). The classifiers
		// reuse the i32 slots freely between attempts.
		w.write(4);
		w.write(2);
		w.writeRefType(true, Type.EQ.code());
		w.write(12);
		w.write(Type.I32);
		w.write(2);
		w.write(Type.F64);
		w.write(1);
		w.writeRefType(true, Type.EQ.code());
		final int CAR = 0, CDR = 1, BYTE = 2, START = 3, LEN = 4, OFF = 5, POS = 6, ESC = 7, HP = 8, NEG = 9, ACC = 10,
				VALID = 11, SAWDOT = 12, C2 = 13, FVAL = 14, FPLACE = 15, ACC64 = 16;

		emitSkipWs(w, ctx);
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
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_READ_LIST);
		w.write(Instruction.RETURN);
		end(w);

		// '\'' -> (quote inner)
		getLocal(w, BYTE);
		i32(w, '\'');
		w.write(Instruction.I32_EQ);
		ifVoid(w);
		advanceCursor(w);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_READ_EXPR);
		setLocal(w, CAR); // inner
		// cdr = cons(inner, null)
		getLocal(w, CAR);
		emitNull(w);
		structNew(w, WasmLispCompiler.TYPE_CONS);
		setLocal(w, CDR);
		// return cons(quoteSym, cdr)
		i32(w, ctx.quoteOffset());
		i32(w, QUOTE_LEN);
		WasmEmitHelper.emitStrBuildCall(w);
		getLocal(w, CDR);
		structNew(w, WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.RETURN);
		end(w);

		// '#' -> the frontend lexer's dispatch set. A token no dispatch claims falls
		// through to the atom path below, so #foo / #:g / #16r1f read as the symbols the
		// frontend reads them as.
		getLocal(w, BYTE);
		i32(w, '#');
		w.write(Instruction.I32_EQ);
		cursorPlusLtEnd(w, 1);
		w.write(Instruction.I32_AND);
		ifVoid(w);
		emitHashDispatch(w, ctx, CAR, CDR, POS, ACC, C2);
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

		// atom: scan + upcase the maximal symbol-char run through _rd_token (the token
		// bytes become their canonical uppercase spelling in place; non-ASCII bytes are
		// left as-is, a documented WASM runtime-read limitation)
		call(w, WasmLispCompiler.FUNC_RD_TOKEN);
		setLocal(w, START);
		// len = cursor - start
		loadMem32(w, CURSOR);
		getLocal(w, START);
		w.write(Instruction.I32_SUB);
		setLocal(w, LEN);

		// Leading '+': an explicitly positive number literal (+347, +2.5, +1/3) drops
		// the sign when a digit follows, like the frontend tokenizer; any other '+'
		// token stays a symbol.
		getLocal(w, LEN);
		i32(w, 1);
		w.write(Instruction.I32_GT_S);
		ifVoid(w);
		getLocal(w, START);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);
		i32(w, '+');
		w.write(Instruction.I32_EQ);
		ifVoid(w);
		getLocal(w, START);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);
		setLocal(w, BYTE);
		getLocal(w, BYTE);
		i32(w, '0');
		w.write(Instruction.I32_GE_S);
		getLocal(w, BYTE);
		i32(w, '9');
		w.write(Instruction.I32_LE_S);
		w.write(Instruction.I32_AND);
		ifVoid(w);
		getLocal(w, START);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		setLocal(w, START);
		getLocal(w, LEN);
		i32(w, 1);
		w.write(Instruction.I32_SUB);
		setLocal(w, LEN);
		end(w);
		end(w);
		end(w);

		// classify: integer?
		emitTryInteger(w, BYTE, START, LEN, POS, NEG, ACC64, VALID, ESC);

		// classify: ratio? (N/D with grouping commas; a token the pattern rejects stays
		// on the symbol path, and a literal /0 denominator signals like the frontend)
		emitTryRatio(w, ctx, BYTE, START, LEN, POS, NEG, ACC, VALID, SAWDOT, HP, C2);

		// classify: float? (a token with a '.' falls through the integer parser)
		emitTryFloat(w, BYTE, START, LEN, POS, NEG, VALID, ESC, SAWDOT, FVAL, FPLACE);

		// symbol: off = _intern(start, len)
		getLocal(w, START);
		getLocal(w, LEN);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_INTERN);
		setLocal(w, OFF);
		// nil? -> the null ref (nil is the unique null value, not a symbol struct)
		getLocal(w, OFF);
		i32(w, ctx.nilOffset());
		w.write(Instruction.I32_EQ);
		ifVoid(w);
		emitNull(w);
		w.write(Instruction.RETURN);
		end(w);
		// symbol struct (t is an ordinary interned symbol "T": the token upcased to "T",
		// interned to the same offset a compiled t literal uses, so they compare eq)
		getLocal(w, OFF);
		getLocal(w, LEN);
		WasmEmitHelper.emitStrBuildCall(w);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	/**
	 * Emits the '#' dispatcher inside {@code _read_expr} (the cursor is AT the '#',
	 * {@code cursor+1 < end} already checked). Every recognized form consumes its prefix
	 * and RETURNS; an unclaimed token falls out of the surrounding {@code if} to the atom
	 * path, like the frontend's readSymbol fallthrough.
	 */
	private static void emitHashDispatch(WasmWriter w, ReadCtx ctx, int CAR, int CDR, int PROBE, int RANK, int C2) {
		byteAtCursorPlus(w, 1);
		setLocal(w, C2);

		// "#'" -> (function inner)
		getLocal(w, C2);
		i32(w, '\'');
		w.write(Instruction.I32_EQ);
		ifVoid(w);
		advanceCursor(w);
		advanceCursor(w);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_READ_EXPR);
		setLocal(w, CAR);
		getLocal(w, CAR);
		emitNull(w);
		structNew(w, WasmLispCompiler.TYPE_CONS);
		setLocal(w, CDR);
		i32(w, ctx.functionOffset());
		i32(w, FUNCTION_LEN);
		WasmEmitHelper.emitStrBuildCall(w);
		getLocal(w, CDR);
		structNew(w, WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.RETURN);
		end(w);

		// "#\" -> character literal
		getLocal(w, C2);
		i32(w, '\\');
		w.write(Instruction.I32_EQ);
		ifVoid(w);
		advanceCursor(w);
		advanceCursor(w);
		call(w, WasmLispCompiler.FUNC_RD_CHARLIT);
		w.write(Instruction.RETURN);
		end(w);

		// "#(" -> rank-1 vector
		getLocal(w, C2);
		i32(w, '(');
		w.write(Instruction.I32_EQ);
		ifVoid(w);
		advanceCursor(w);
		advanceCursor(w);
		i32(w, 1);
		call(w, WasmLispCompiler.FUNC_RD_ARRAYN);
		w.write(Instruction.RETURN);
		end(w);

		// "#S(" / "#s(" -> structure literal ("#S" without the paren is a symbol)
		getLocal(w, C2);
		i32(w, 'S');
		w.write(Instruction.I32_EQ);
		getLocal(w, C2);
		i32(w, 's');
		w.write(Instruction.I32_EQ);
		w.write(Instruction.I32_OR);
		cursorPlusLtEnd(w, 2);
		w.write(Instruction.I32_AND);
		ifVoid(w);
		byteAtCursorPlus(w, 2);
		i32(w, '(');
		w.write(Instruction.I32_EQ);
		ifVoid(w);
		advanceCursor(w);
		advanceCursor(w);
		advanceCursor(w);
		call(w, WasmLispCompiler.FUNC_RD_STRUCT);
		w.write(Instruction.RETURN);
		end(w);
		end(w);

		// "#*" -> bit vector (a general vector of 0/1, like the frontend)
		getLocal(w, C2);
		i32(w, '*');
		w.write(Instruction.I32_EQ);
		ifVoid(w);
		advanceCursor(w);
		advanceCursor(w);
		call(w, WasmLispCompiler.FUNC_RD_BITS);
		w.write(Instruction.RETURN);
		end(w);

		// "#f(" / "#F(" / "#d(" / "#D(" -> packed float arrays
		emitPackedDispatch(w, 'f', 'F', 1, C2);
		emitPackedDispatch(w, 'd', 'D', 0, C2);

		// "#<digits>" -> #nA( array, #n=/#n# labels (signaled), or a symbol
		getLocal(w, C2);
		i32(w, '0');
		w.write(Instruction.I32_GE_S);
		getLocal(w, C2);
		i32(w, '9');
		w.write(Instruction.I32_LE_S);
		w.write(Instruction.I32_AND);
		ifVoid(w);
		loadMem32(w, CURSOR);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		setLocal(w, PROBE);
		i32(w, 0);
		setLocal(w, RANK);
		block(w);
		loop(w);
		getLocal(w, PROBE);
		loadMem32(w, END_ADDR);
		w.write(Instruction.I32_GE_S);
		brIf(w, 1);
		getLocal(w, PROBE);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);
		setLocal(w, C2);
		getLocal(w, C2);
		i32(w, '0');
		w.write(Instruction.I32_LT_S);
		brIf(w, 1);
		getLocal(w, C2);
		i32(w, '9');
		w.write(Instruction.I32_GT_S);
		brIf(w, 1);
		getLocal(w, RANK);
		i32(w, 10);
		w.write(Instruction.I32_MUL);
		getLocal(w, C2);
		i32(w, '0');
		w.write(Instruction.I32_SUB);
		w.write(Instruction.I32_ADD);
		setLocal(w, RANK);
		// a rank this large cannot denote a real array; stopping keeps the i32
		// accumulator from wrapping into a small (wrong) rank
		getLocal(w, RANK);
		i32(w, 20000);
		w.write(Instruction.I32_GT_S);
		ifVoid(w);
		emitErr(w, ctx, ctx.msgRank());
		end(w);
		getLocal(w, PROBE);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		setLocal(w, PROBE);
		br(w, 0);
		end(w); // loop
		end(w); // block
		// probe at end -> symbol fallthrough
		getLocal(w, PROBE);
		loadMem32(w, END_ADDR);
		w.write(Instruction.I32_LT_S);
		ifVoid(w);
		getLocal(w, PROBE);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);
		setLocal(w, C2);
		// 'A'/'a' + '('
		getLocal(w, C2);
		i32(w, 'A');
		w.write(Instruction.I32_EQ);
		getLocal(w, C2);
		i32(w, 'a');
		w.write(Instruction.I32_EQ);
		w.write(Instruction.I32_OR);
		getLocal(w, PROBE);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		loadMem32(w, END_ADDR);
		w.write(Instruction.I32_LT_S);
		w.write(Instruction.I32_AND);
		ifVoid(w);
		getLocal(w, PROBE);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);
		i32(w, '(');
		w.write(Instruction.I32_EQ);
		ifVoid(w);
		// cursor = probe + 2 (past "A(")
		storeMem32(w, CURSOR, () -> {
			getLocal(w, PROBE);
			i32(w, 2);
			w.write(Instruction.I32_ADD);
		});
		getLocal(w, RANK);
		call(w, WasmLispCompiler.FUNC_RD_ARRAYN);
		w.write(Instruction.RETURN);
		end(w);
		end(w);
		// '=' / '#' -> reader labels, decided out: signal instead of misreading
		getLocal(w, C2);
		i32(w, '=');
		w.write(Instruction.I32_EQ);
		getLocal(w, C2);
		i32(w, '#');
		w.write(Instruction.I32_EQ);
		w.write(Instruction.I32_OR);
		ifVoid(w);
		emitErr(w, ctx, ctx.msgLabels());
		end(w);
		end(w);
		// the scan clobbered C2; neutralize it so no later dispatch case can fire on a
		// byte that was never the character after '#'
		i32(w, 0);
		setLocal(w, C2);
		end(w);

		// "#x" / "#o" / "#b" -> radix integer
		emitRadixDispatch(w, 'x', 'X', 16, C2);
		emitRadixDispatch(w, 'o', 'O', 8, C2);
		emitRadixDispatch(w, 'b', 'B', 2, C2);

		// "#." needs an evaluator at read time; a compiled artifact has none, so it is a
		// permanent limit that SIGNALS instead of misreading (the frontend evaluates it)
		getLocal(w, C2);
		i32(w, '.');
		w.write(Instruction.I32_EQ);
		ifVoid(w);
		emitErr(w, ctx, ctx.msgReadEval());
		end(w);

		// "#+" / "#-" need the feature set at read time; same permanent limit
		getLocal(w, C2);
		i32(w, '+');
		w.write(Instruction.I32_EQ);
		getLocal(w, C2);
		i32(w, '-');
		w.write(Instruction.I32_EQ);
		w.write(Instruction.I32_OR);
		ifVoid(w);
		emitErr(w, ctx, ctx.msgFeature());
		end(w);
	}

	private static void emitPackedDispatch(WasmWriter w, char lower, char upper, int single, int C2) {
		getLocal(w, C2);
		i32(w, lower);
		w.write(Instruction.I32_EQ);
		getLocal(w, C2);
		i32(w, upper);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.I32_OR);
		cursorPlusLtEnd(w, 2);
		w.write(Instruction.I32_AND);
		ifVoid(w);
		byteAtCursorPlus(w, 2);
		i32(w, '(');
		w.write(Instruction.I32_EQ);
		ifVoid(w);
		advanceCursor(w);
		advanceCursor(w);
		advanceCursor(w);
		i32(w, single);
		call(w, WasmLispCompiler.FUNC_RD_PACKED);
		w.write(Instruction.RETURN);
		end(w);
		end(w);
	}

	private static void emitRadixDispatch(WasmWriter w, char lower, char upper, int radix, int C2) {
		getLocal(w, C2);
		i32(w, lower);
		w.write(Instruction.I32_EQ);
		getLocal(w, C2);
		i32(w, upper);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.I32_OR);
		ifVoid(w);
		advanceCursor(w);
		advanceCursor(w);
		i32(w, radix);
		call(w, WasmLispCompiler.FUNC_RD_RADIX);
		w.write(Instruction.RETURN);
		end(w);
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
	 * commas), pushes the value (an i31, boxed or limb integer -- {@code ACC} is the
	 * caller's tier-aware accumulator local, stepped through {@code _big_grow}) and
	 * returns from the function; otherwise falls through.
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
		w.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
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
		getLocal(w, BYTE);
		i32(w, '0');
		w.write(Instruction.I32_SUB);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_BIG_GROW);
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
		// if VALID and SAWDIGIT: return (neg ? _big_neg(acc) : acc)
		getLocal(w, VALID);
		getLocal(w, SAWDIGIT);
		w.write(Instruction.I32_AND);
		ifVoid(w);
		getLocal(w, NEG);
		ifVoid(w);
		getLocal(w, ACC);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_BIG_NEG);
		setLocal(w, ACC);
		end(w);
		getLocal(w, ACC);
		w.write(Instruction.RETURN);
		end(w);
	}

	/**
	 * Emits the float classifier/parser for the token at {@code [START, START+LEN)}. A
	 * decimal float is an optional leading {@code -}, digits, exactly one {@code .}, and
	 * at least one digit (e.g. {@code 1.0}, {@code -2.5}, {@code .5}, {@code 5.}). On a
	 * match, builds a {@link WasmLispCompiler#TYPE_FLOAT} struct and returns from the
	 * function; otherwise falls through. Integer tokens never reach here because the
	 * integer parser already returned for them, and a token with a {@code .} fails the
	 * integer parser (so it falls through to this classifier). No exponent support.
	 */
	private static void emitTryFloat(WasmWriter w, int BYTE, int START, int LEN, int POS, int NEG, int VALID,
			int SAWDIGIT, int SAWDOT, int FVAL, int FPLACE) {
		// POS = START ; VALID = 1 ; SAWDIGIT = 0 ; SAWDOT = 0 ; NEG = 0
		// FVAL = 0.0 ; FPLACE = 1.0 (fractional place, multiplied by 0.1 per frac digit)
		getLocal(w, START);
		setLocal(w, POS);
		i32(w, 1);
		setLocal(w, VALID);
		i32(w, 0);
		setLocal(w, SAWDIGIT);
		i32(w, 0);
		setLocal(w, SAWDOT);
		i32(w, 0);
		setLocal(w, NEG);
		w.write(Instruction.F64_CONST);
		w.writeF64(0.0);
		setLocal(w, FVAL);
		w.write(Instruction.F64_CONST);
		w.writeF64(1.0);
		setLocal(w, FPLACE);
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
		// if b == '.'
		getLocal(w, BYTE);
		i32(w, '.');
		w.write(Instruction.I32_EQ);
		ifVoid(w);
		// a second '.' invalidates the token
		getLocal(w, SAWDOT);
		ifVoid(w);
		i32(w, 0);
		setLocal(w, VALID);
		end(w);
		i32(w, 1);
		setLocal(w, SAWDOT);
		w.write(Instruction.ELSE);
		// if b < '0'
		getLocal(w, BYTE);
		i32(w, '0');
		w.write(Instruction.I32_LT_S);
		ifVoid(w);
		i32(w, 0);
		setLocal(w, VALID);
		w.write(Instruction.ELSE);
		// if b > '9'
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
		getLocal(w, SAWDOT);
		ifVoid(w);
		// fractional digit: FPLACE *= 0.1 ; FVAL += digit * FPLACE
		getLocal(w, FPLACE);
		w.write(Instruction.F64_CONST);
		w.writeF64(0.1);
		w.write(Instruction.F64_MUL);
		setLocal(w, FPLACE);
		getLocal(w, FVAL);
		getLocal(w, BYTE);
		i32(w, '0');
		w.write(Instruction.I32_SUB);
		w.write(Instruction.F64_CONVERT_S_I32);
		getLocal(w, FPLACE);
		w.write(Instruction.F64_MUL);
		w.write(Instruction.F64_ADD);
		setLocal(w, FVAL);
		w.write(Instruction.ELSE);
		// integer-part digit: FVAL = FVAL * 10 + digit
		getLocal(w, FVAL);
		w.write(Instruction.F64_CONST);
		w.writeF64(10.0);
		w.write(Instruction.F64_MUL);
		getLocal(w, BYTE);
		i32(w, '0');
		w.write(Instruction.I32_SUB);
		w.write(Instruction.F64_CONVERT_S_I32);
		w.write(Instruction.F64_ADD);
		setLocal(w, FVAL);
		end(w);
		end(w);
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
		// if VALID and SAWDIGIT and SAWDOT: return TYPE_FLOAT(neg ? -FVAL : FVAL)
		getLocal(w, VALID);
		getLocal(w, SAWDIGIT);
		w.write(Instruction.I32_AND);
		getLocal(w, SAWDOT);
		w.write(Instruction.I32_AND);
		ifVoid(w);
		getLocal(w, NEG);
		ifVoid(w);
		getLocal(w, FVAL);
		w.write(Instruction.F64_NEG);
		setLocal(w, FVAL);
		end(w);
		getLocal(w, FVAL);
		structNew(w, WasmLispCompiler.TYPE_FLOAT);
		w.write(Instruction.RETURN);
		end(w);
	}

	/**
	 * Emits an inline reader for a {@code "..."} string literal (cursor at the opening
	 * quote). Assembles the string with surrounding {@code "} markers and processed
	 * {@code \n \t \\ \"} escapes in the reused heap scratch (HEAP is NOT advanced -- a
	 * stack pop), finalizes it into a fresh {@code $str_bytes} GC array via
	 * {@code _str_fresh}, and leaves the resulting {@code TYPE_STRING} struct on the
	 * stack.
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
		// HEAP is NOT advanced (a stack pop): _str_fresh copies the literal into a fresh
		// GC
		// array with a counter id, so the scratch (which sits above the reserved reader
		// input) is reused for the next datum. A read string literal is a runtime string.
		// push _str_fresh(hp, pos)
		getLocal(w, HPL);
		getLocal(w, POS);
		WasmEmitHelper.emitStrFreshCall(w);
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

	static byte[] buildReadListBody(ReadCtx ctx) {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		// ref locals: CAR=0, CDR=1 ; i32 locals: ISDOT=2, CH2=3
		w.write(2);
		w.write(2);
		w.writeRefType(true, Type.EQ.code());
		w.write(2);
		w.write(Type.I32);
		final int CAR = 0, CDR = 1, ISDOT = 2, CH2 = 3;

		emitSkipWs(w, ctx);
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
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_READ_EXPR);
		setLocal(w, CAR);
		// Dotted pair: a standalone '.' token (followed by a delimiter or the end of
		// input) puts the next datum directly in the final cdr, mirroring the
		// compile-time reader: (a . b). Symbols and floats containing '.' are
		// untouched.
		emitSkipWs(w, ctx);
		loadMem32(w, CURSOR);
		loadMem32(w, END_ADDR);
		w.write(Instruction.I32_LT_S);
		ifVoid(w);
		curByte(w);
		i32(w, '.');
		w.write(Instruction.I32_EQ);
		ifVoid(w);
		// ch2 = the byte after '.', with end-of-input treated as a delimiter
		i32(w, 32);
		setLocal(w, CH2);
		loadMem32(w, CURSOR);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		loadMem32(w, END_ADDR);
		w.write(Instruction.I32_LT_S);
		ifVoid(w);
		loadMem32(w, CURSOR);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);
		setLocal(w, CH2);
		end(w);
		// isdot = ch2 <= 32 || ch2 in { ')' '(' '\'' '"' ';' }
		getLocal(w, CH2);
		i32(w, 32);
		w.write(Instruction.I32_LE_S);
		getLocal(w, CH2);
		i32(w, ')');
		w.write(Instruction.I32_EQ);
		w.write(Instruction.I32_OR);
		getLocal(w, CH2);
		i32(w, '(');
		w.write(Instruction.I32_EQ);
		w.write(Instruction.I32_OR);
		getLocal(w, CH2);
		i32(w, '\'');
		w.write(Instruction.I32_EQ);
		w.write(Instruction.I32_OR);
		getLocal(w, CH2);
		i32(w, '"');
		w.write(Instruction.I32_EQ);
		w.write(Instruction.I32_OR);
		getLocal(w, CH2);
		i32(w, ';');
		w.write(Instruction.I32_EQ);
		w.write(Instruction.I32_OR);
		setLocal(w, ISDOT);
		end(w);
		end(w);
		// if isdot: consume '.', cdr = _read_expr, then consume the closing ')'
		getLocal(w, ISDOT);
		ifVoid(w);
		advanceCursor(w);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_READ_EXPR);
		setLocal(w, CDR);
		emitSkipWs(w, ctx);
		loadMem32(w, CURSOR);
		loadMem32(w, END_ADDR);
		w.write(Instruction.I32_LT_S);
		ifVoid(w);
		curByte(w);
		i32(w, ')');
		w.write(Instruction.I32_EQ);
		ifVoid(w);
		advanceCursor(w);
		end(w);
		end(w);
		end(w);
		// else: cdr = _read_list
		getLocal(w, ISDOT);
		w.write(Instruction.I32_EQZ);
		ifVoid(w);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_READ_LIST);
		setLocal(w, CDR);
		end(w);
		// cons(car, cdr)
		getLocal(w, CAR);
		getLocal(w, CDR);
		structNew(w, WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	/**
	 * Emits the ratio classifier for the token at {@code [START, START+LEN)}: an optional
	 * leading {@code -}, digits (with grouping commas), exactly one {@code /}, then
	 * digits (with commas). On a match builds the ratio through {@code FUNC_RAT_NEW}
	 * (normalization and the den==1 integer demotion come free) and returns from the
	 * function; any token the pattern rejects falls through to the next classifier. A
	 * literal zero denominator signals like the frontend.
	 */
	private static void emitTryRatio(WasmWriter w, ReadCtx ctx, int BYTE, int START, int LEN, int POS, int NEG, int ACC,
			int NUMD, int DEND, int SI, int ACC2) {
		block(w); // the "not a ratio" bail-out target
		// find the single '/'
		i32(w, -1);
		setLocal(w, SI);
		getLocal(w, START);
		setLocal(w, POS);
		block(w);
		loop(w);
		getLocal(w, POS);
		getLocal(w, START);
		getLocal(w, LEN);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_GE_S);
		brIf(w, 1);
		getLocal(w, POS);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);
		setLocal(w, BYTE);
		getLocal(w, BYTE);
		i32(w, '/');
		w.write(Instruction.I32_EQ);
		ifVoid(w);
		// a second '/' invalidates the token
		getLocal(w, SI);
		i32(w, 0);
		w.write(Instruction.I32_GE_S);
		brIf(w, 3);
		getLocal(w, POS);
		setLocal(w, SI);
		end(w);
		getLocal(w, POS);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		setLocal(w, POS);
		br(w, 0);
		end(w); // scan loop
		end(w); // scan block
		getLocal(w, SI);
		i32(w, 0);
		w.write(Instruction.I32_LT_S);
		brIf(w, 0); // no '/': not a ratio
		// numerator [START(+1 for '-'), SI)
		i32(w, 0);
		setLocal(w, NEG);
		i32(w, 0);
		setLocal(w, NUMD);
		i32(w, 0);
		setLocal(w, ACC);
		getLocal(w, START);
		setLocal(w, POS);
		getLocal(w, START);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);
		i32(w, '-');
		w.write(Instruction.I32_EQ);
		ifVoid(w);
		i32(w, 1);
		setLocal(w, NEG);
		getLocal(w, START);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		setLocal(w, POS);
		end(w);
		getLocal(w, POS);
		getLocal(w, SI);
		w.write(Instruction.I32_GE_S);
		brIf(w, 0); // empty numerator
		block(w);
		loop(w);
		getLocal(w, POS);
		getLocal(w, SI);
		w.write(Instruction.I32_GE_S);
		brIf(w, 1);
		getLocal(w, POS);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);
		setLocal(w, BYTE);
		getLocal(w, BYTE);
		i32(w, ',');
		w.write(Instruction.I32_EQ);
		ifVoid(w);
		getLocal(w, POS);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		setLocal(w, POS);
		br(w, 1); // continue the numerator loop
		end(w);
		getLocal(w, BYTE);
		i32(w, '0');
		w.write(Instruction.I32_LT_S);
		brIf(w, 2); // not a digit: not a ratio
		getLocal(w, BYTE);
		i32(w, '9');
		w.write(Instruction.I32_GT_S);
		brIf(w, 2);
		i32(w, 1);
		setLocal(w, NUMD);
		getLocal(w, ACC);
		i32(w, 10);
		w.write(Instruction.I32_MUL);
		getLocal(w, BYTE);
		i32(w, '0');
		w.write(Instruction.I32_SUB);
		w.write(Instruction.I32_ADD);
		setLocal(w, ACC);
		getLocal(w, POS);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		setLocal(w, POS);
		br(w, 0);
		end(w); // numerator loop
		end(w); // numerator block
		getLocal(w, NUMD);
		w.write(Instruction.I32_EQZ);
		brIf(w, 0);
		// denominator (SI, START+LEN)
		getLocal(w, SI);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		setLocal(w, POS);
		i32(w, 0);
		setLocal(w, DEND);
		i32(w, 0);
		setLocal(w, ACC2);
		getLocal(w, POS);
		getLocal(w, START);
		getLocal(w, LEN);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_GE_S);
		brIf(w, 0); // empty denominator
		block(w);
		loop(w);
		getLocal(w, POS);
		getLocal(w, START);
		getLocal(w, LEN);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_GE_S);
		brIf(w, 1);
		getLocal(w, POS);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);
		setLocal(w, BYTE);
		getLocal(w, BYTE);
		i32(w, ',');
		w.write(Instruction.I32_EQ);
		ifVoid(w);
		getLocal(w, POS);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		setLocal(w, POS);
		br(w, 1);
		end(w);
		getLocal(w, BYTE);
		i32(w, '0');
		w.write(Instruction.I32_LT_S);
		brIf(w, 2);
		getLocal(w, BYTE);
		i32(w, '9');
		w.write(Instruction.I32_GT_S);
		brIf(w, 2);
		i32(w, 1);
		setLocal(w, DEND);
		getLocal(w, ACC2);
		i32(w, 10);
		w.write(Instruction.I32_MUL);
		getLocal(w, BYTE);
		i32(w, '0');
		w.write(Instruction.I32_SUB);
		w.write(Instruction.I32_ADD);
		setLocal(w, ACC2);
		getLocal(w, POS);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		setLocal(w, POS);
		br(w, 0);
		end(w); // denominator loop
		end(w); // denominator block
		getLocal(w, DEND);
		w.write(Instruction.I32_EQZ);
		brIf(w, 0);
		// a valid ratio token: a zero denominator signals, like the frontend
		getLocal(w, ACC2);
		w.write(Instruction.I32_EQZ);
		ifVoid(w);
		emitErr(w, ctx, ctx.msgDivZero());
		end(w);
		getLocal(w, NEG);
		ifVoid(w);
		i32(w, 0);
		getLocal(w, ACC);
		w.write(Instruction.I32_SUB);
		setLocal(w, ACC);
		end(w);
		getLocal(w, ACC);
		getLocal(w, ACC2);
		call(w, WasmLispCompiler.FUNC_RAT_NEW);
		w.write(Instruction.RETURN);
		end(w); // bail-out block
	}

	// === _read() -> value (builtin) ===

	static byte[] buildReadBody(ReadCtx ctx) {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		// param: FD=0 (i32) ; ref local: V=1 ; i32 locals: OFF=2, LEN=3
		w.write(2);
		w.write(1);
		w.writeRefType(true, Type.EQ.code());
		w.write(2);
		w.write(Type.I32);
		final int FD = 0, V = 1, OFF = 2, LEN = 3;

		// Keep reading lines until one contains a datum (blank and comment-only lines
		// are skipped) or the stream is exhausted (EOF -> nil). FD is 0 (stdin) for
		// (read); for (read stream) it is the stream's WASI file descriptor.
		block(w);
		loop(w);
		getLocal(w, FD); // fd
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_READ_LINE);
		setLocal(w, V);
		// if V is null: return null
		getLocal(w, V);
		w.write(Instruction.REF_IS_NULL);
		ifVoid(w);
		emitNull(w);
		w.write(Instruction.RETURN);
		end(w);
		// The line V's bytes live on the GC heap: copy them into the reader input scratch
		// at HEAP_PTR, point the cursor/end there, and RESERVE the scratch (HEAP_PTR =
		// off
		// + len) so symbols interned and strings built while parsing the line stack above
		// the still-unparsed input. off = HEAP_PTR ; len = _str_to_mem(V, off).
		loadMem32(w, HEAP);
		setLocal(w, OFF);
		getLocal(w, V);
		getLocal(w, OFF);
		WasmEmitHelper.emitStrToMemCall(w);
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
		// reserve HEAP_PTR = off + len
		i32(w, HEAP);
		getLocal(w, OFF);
		getLocal(w, LEN);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		// retry with the next line if nothing remains after whitespace/comments
		emitSkipWs(w, ctx);
		loadMem32(w, CURSOR);
		loadMem32(w, END_ADDR);
		w.write(Instruction.I32_GE_S);
		brIf(w, 0);
		// parse one datum
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_READ_EXPR);
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
	static byte[] buildLoadBody(ReadCtx ctx) {
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

		// The path bytes live on the GC heap: copy them into linear scratch at HEAP_PTR
		// and ADVANCE HEAP_PTR over them for the duration of path_open (popped back
		// right after, so the file read below reuses the same scratch base): under
		// --component the adapter's first open lifts the preopen directory list through
		// cabi_realloc, which allocates at HEAP_PTR -- an un-advanced staging would be
		// overwritten before path_open reads it. off = HEAP_PTR ; plen =
		// _str_to_mem(path, off) - 2.
		loadMem32(w, HEAP);
		setLocal(w, OFF);
		getLocal(w, PATH);
		getLocal(w, OFF);
		WasmEmitHelper.emitStrToMemCall(w);
		i32(w, 2);
		w.write(Instruction.I32_SUB);
		setLocal(w, PLEN);
		// HEAP_PTR = align8(off + plen + 2)
		i32(w, HEAP);
		getLocal(w, OFF);
		getLocal(w, PLEN);
		w.write(Instruction.I32_ADD);
		i32(w, 2 + 7);
		w.write(Instruction.I32_ADD);
		i32(w, -8);
		w.write(Instruction.I32_AND);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
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
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_PATH_OPEN);
		// pop the staged path on BOTH exits (PLEN is free now: reuse it for the errno)
		setLocal(w, PLEN);
		i32(w, HEAP);
		getLocal(w, OFF);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		// if errno != 0: return nil
		getLocal(w, PLEN);
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
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_FD_READ);
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
		emitSkipWs(w, ctx);
		loadMem32(w, CURSOR);
		loadMem32(w, END_ADDR);
		w.write(Instruction.I32_GE_S);
		brIf(w, 1);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_READ_EXPR);
		emitNull(w); // env = null (global)
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_EVAL);
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

	// === the # dispatch helper bodies ===

	/** {@code () -> eqref} stub for an unused reader helper. */
	static byte[] buildRdEqrefStub() {
		return refNullStub();
	}

	/** {@code ... -> i32} stub for an unused reader helper. */
	static byte[] buildRdI32Stub() {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		w.write(0);
		i32(w, 0);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	// _rd_token () -> i32: scans the maximal symbol-char run from the cursor, upcasing
	// a-z bytes in place (uppercase-canonical), and returns the start offset; the end is
	// the cursor.
	static byte[] buildRdTokenBody() {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		w.write(1);
		w.write(2);
		w.write(Type.I32);
		final int START = 0, BYTE = 1;
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
		getLocal(w, BYTE);
		i32(w, 32);
		w.write(Instruction.I32_LE_S);
		brIf(w, 1);
		emitStopIf(w, BYTE, '(');
		emitStopIf(w, BYTE, ')');
		emitStopIf(w, BYTE, '\'');
		emitStopIf(w, BYTE, '"');
		emitStopIf(w, BYTE, ';');
		// upcase a-z in place
		getLocal(w, BYTE);
		i32(w, 'a');
		w.write(Instruction.I32_GE_S);
		getLocal(w, BYTE);
		i32(w, 'z');
		w.write(Instruction.I32_LE_S);
		w.write(Instruction.I32_AND);
		ifVoid(w);
		loadMem32(w, CURSOR);
		getLocal(w, BYTE);
		i32(w, 32);
		w.write(Instruction.I32_SUB);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		end(w);
		advanceCursor(w);
		br(w, 0);
		end(w); // loop
		end(w); // block
		getLocal(w, START);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	// _rd_memeq (a, b, len) -> i32: byte-range equality.
	static byte[] buildRdMemeqBody() {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		w.write(1);
		w.write(1);
		w.write(Type.I32);
		final int A = 0, B = 1, LEN = 2, K = 3;
		i32(w, 0);
		setLocal(w, K);
		block(w);
		loop(w);
		getLocal(w, K);
		getLocal(w, LEN);
		w.write(Instruction.I32_GE_S);
		brIf(w, 1);
		getLocal(w, A);
		getLocal(w, K);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);
		getLocal(w, B);
		getLocal(w, K);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);
		w.write(Instruction.I32_NE);
		ifVoid(w);
		i32(w, 0);
		w.write(Instruction.RETURN);
		end(w);
		getLocal(w, K);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		setLocal(w, K);
		br(w, 0);
		end(w); // loop
		end(w); // block
		i32(w, 1);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	// _rd_charlit () -> value: cursor just past "#\". The first character (a full UTF-8
	// code point) is taken literally; a letter (or any non-ASCII lead) starts a name
	// scan; a token of exactly one character is that character verbatim; a longer token
	// resolves through the case-insensitive name table.
	static byte[] buildRdCharlitBody(ReadCtx ctx) {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		w.write(1);
		w.write(8);
		w.write(Type.I32);
		final int FIRST = 0, K = 1, START = 2, BYTE = 3, B0 = 4, IDX = 5, NOFF = 6, NLEN = 7;
		loadMem32(w, CURSOR);
		loadMem32(w, END_ADDR);
		w.write(Instruction.I32_GE_S);
		ifVoid(w);
		emitErr(w, ctx, ctx.msgCharEof());
		end(w);
		loadMem32(w, CURSOR);
		setLocal(w, START);
		curByte(w);
		setLocal(w, B0);
		// decode the first UTF-8 code point into FIRST, its byte length into K
		getLocal(w, B0);
		i32(w, 0x80);
		w.write(Instruction.I32_LT_S);
		ifVoid(w);
		getLocal(w, B0);
		setLocal(w, FIRST);
		i32(w, 1);
		setLocal(w, K);
		w.write(Instruction.ELSE);
		getLocal(w, B0);
		i32(w, 0xE0);
		w.write(Instruction.I32_AND);
		i32(w, 0xC0);
		w.write(Instruction.I32_EQ);
		ifVoid(w);
		getLocal(w, B0);
		i32(w, 0x1F);
		w.write(Instruction.I32_AND);
		i32(w, 6);
		w.write(Instruction.I32_SHL);
		byteAtCursorPlus(w, 1);
		i32(w, 0x3F);
		w.write(Instruction.I32_AND);
		w.write(Instruction.I32_OR);
		setLocal(w, FIRST);
		i32(w, 2);
		setLocal(w, K);
		w.write(Instruction.ELSE);
		getLocal(w, B0);
		i32(w, 0xF0);
		w.write(Instruction.I32_AND);
		i32(w, 0xE0);
		w.write(Instruction.I32_EQ);
		ifVoid(w);
		getLocal(w, B0);
		i32(w, 0x0F);
		w.write(Instruction.I32_AND);
		i32(w, 12);
		w.write(Instruction.I32_SHL);
		byteAtCursorPlus(w, 1);
		i32(w, 0x3F);
		w.write(Instruction.I32_AND);
		i32(w, 6);
		w.write(Instruction.I32_SHL);
		w.write(Instruction.I32_OR);
		byteAtCursorPlus(w, 2);
		i32(w, 0x3F);
		w.write(Instruction.I32_AND);
		w.write(Instruction.I32_OR);
		setLocal(w, FIRST);
		i32(w, 3);
		setLocal(w, K);
		w.write(Instruction.ELSE);
		getLocal(w, B0);
		i32(w, 0x07);
		w.write(Instruction.I32_AND);
		i32(w, 18);
		w.write(Instruction.I32_SHL);
		byteAtCursorPlus(w, 1);
		i32(w, 0x3F);
		w.write(Instruction.I32_AND);
		i32(w, 12);
		w.write(Instruction.I32_SHL);
		w.write(Instruction.I32_OR);
		byteAtCursorPlus(w, 2);
		i32(w, 0x3F);
		w.write(Instruction.I32_AND);
		i32(w, 6);
		w.write(Instruction.I32_SHL);
		w.write(Instruction.I32_OR);
		byteAtCursorPlus(w, 3);
		i32(w, 0x3F);
		w.write(Instruction.I32_AND);
		w.write(Instruction.I32_OR);
		setLocal(w, FIRST);
		i32(w, 4);
		setLocal(w, K);
		end(w);
		end(w);
		end(w);
		// cursor += K (the first character is consumed literally)
		storeMem32(w, CURSOR, () -> {
			loadMem32(w, CURSOR);
			getLocal(w, K);
			w.write(Instruction.I32_ADD);
		});
		// only a letter (or non-ASCII lead) starts a name scan
		getLocal(w, B0);
		i32(w, 'A');
		w.write(Instruction.I32_GE_S);
		getLocal(w, B0);
		i32(w, 'Z');
		w.write(Instruction.I32_LE_S);
		w.write(Instruction.I32_AND);
		getLocal(w, B0);
		i32(w, 'a');
		w.write(Instruction.I32_GE_S);
		getLocal(w, B0);
		i32(w, 'z');
		w.write(Instruction.I32_LE_S);
		w.write(Instruction.I32_AND);
		w.write(Instruction.I32_OR);
		getLocal(w, B0);
		i32(w, 0x80);
		w.write(Instruction.I32_GE_S);
		w.write(Instruction.I32_OR);
		w.write(Instruction.I32_EQZ);
		ifVoid(w);
		getLocal(w, FIRST);
		structNew(w, WasmLispCompiler.TYPE_CHAR);
		w.write(Instruction.RETURN);
		end(w);
		// scan the rest of the name (the frontend's isSymbolChar set)
		block(w);
		loop(w);
		loadMem32(w, CURSOR);
		loadMem32(w, END_ADDR);
		w.write(Instruction.I32_GE_S);
		brIf(w, 1);
		curByte(w);
		setLocal(w, BYTE);
		getLocal(w, BYTE);
		i32(w, 32);
		w.write(Instruction.I32_LE_S);
		brIf(w, 1);
		emitStopIf(w, BYTE, '(');
		emitStopIf(w, BYTE, ')');
		emitStopIf(w, BYTE, '\'');
		emitStopIf(w, BYTE, '"');
		emitStopIf(w, BYTE, ';');
		emitStopIf(w, BYTE, ',');
		emitStopIf(w, BYTE, '`');
		advanceCursor(w);
		br(w, 0);
		end(w); // loop
		end(w); // block
		// a single-character token is that character verbatim (B0 becomes the length)
		loadMem32(w, CURSOR);
		getLocal(w, START);
		w.write(Instruction.I32_SUB);
		setLocal(w, B0); // token length
		getLocal(w, B0);
		getLocal(w, K);
		w.write(Instruction.I32_EQ);
		ifVoid(w);
		getLocal(w, FIRST);
		structNew(w, WasmLispCompiler.TYPE_CHAR);
		w.write(Instruction.RETURN);
		end(w);
		// name table scan, case-insensitive (b | 0x20 folds ASCII letters)
		i32(w, 0);
		setLocal(w, IDX);
		block(w);
		loop(w);
		getLocal(w, IDX);
		i32(w, ctx.charNamesCount());
		w.write(Instruction.I32_GE_S);
		brIf(w, 1);
		// NOFF/NLEN from the table
		getLocal(w, IDX);
		i32(w, 12);
		w.write(Instruction.I32_MUL);
		i32(w, ctx.charNamesBase());
		w.write(Instruction.I32_ADD);
		setLocal(w, NOFF); // the entry address, fields read off it below
		getLocal(w, NOFF);
		i32(w, 4);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		setLocal(w, NLEN);
		getLocal(w, NLEN);
		getLocal(w, B0);
		w.write(Instruction.I32_EQ);
		ifVoid(w);
		// compare bytes case-insensitively
		i32(w, 0);
		setLocal(w, K);
		block(w);
		loop(w);
		getLocal(w, K);
		getLocal(w, NLEN);
		w.write(Instruction.I32_GE_S);
		ifVoid(w);
		// matched: box the code point
		getLocal(w, NOFF);
		i32(w, 8);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		structNew(w, WasmLispCompiler.TYPE_CHAR);
		w.write(Instruction.RETURN);
		end(w);
		getLocal(w, START);
		getLocal(w, K);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);
		i32(w, 0x20);
		w.write(Instruction.I32_OR);
		getLocal(w, NOFF);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		getLocal(w, K);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);
		i32(w, 0x20);
		w.write(Instruction.I32_OR);
		w.write(Instruction.I32_NE);
		brIf(w, 1); // not this name
		getLocal(w, K);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		setLocal(w, K);
		br(w, 0);
		end(w); // compare loop
		end(w); // compare block
		end(w); // if NLEN == token length
		getLocal(w, IDX);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		setLocal(w, IDX);
		br(w, 0);
		end(w); // name loop
		end(w); // name block
		emitErr(w, ctx, ctx.msgCharName());
		w.write(Instruction.END);
		return body.toByteArray();
	}

	// _rd_radix (radix) -> value: cursor just past "#x"/"#o"/"#b"; optional '-', digits
	// of the radix; bad digits (or a trailing symbol character) signal. The accumulator
	// is a tier-aware exact integer stepped through _big_grow, matching the frontend
	// (so #xEFCDAB89 reads as the boxed integer and a 256-bit constant as a limb
	// integer, not a wraparound).
	static byte[] buildRdRadixBody(ReadCtx ctx) {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		w.write(2);
		w.write(4);
		w.write(Type.I32);
		w.write(1);
		w.writeRefType(true, Type.EQ.code());
		final int RADIX = 0, NEG = 1, DSTART = 2, BYTE = 3, DV = 4, ACC = 5;
		i32(w, 0);
		setLocal(w, NEG);
		i32(w, 0);
		w.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		setLocal(w, ACC);
		loadMem32(w, CURSOR);
		loadMem32(w, END_ADDR);
		w.write(Instruction.I32_LT_S);
		ifVoid(w);
		curByte(w);
		i32(w, '-');
		w.write(Instruction.I32_EQ);
		ifVoid(w);
		i32(w, 1);
		setLocal(w, NEG);
		advanceCursor(w);
		end(w);
		end(w);
		loadMem32(w, CURSOR);
		setLocal(w, DSTART);
		block(w);
		loop(w);
		loadMem32(w, CURSOR);
		loadMem32(w, END_ADDR);
		w.write(Instruction.I32_GE_S);
		brIf(w, 1);
		curByte(w);
		setLocal(w, BYTE);
		// DV = digit value or -1
		i32(w, -1);
		setLocal(w, DV);
		getLocal(w, BYTE);
		i32(w, '0');
		w.write(Instruction.I32_GE_S);
		getLocal(w, BYTE);
		i32(w, '9');
		w.write(Instruction.I32_LE_S);
		w.write(Instruction.I32_AND);
		ifVoid(w);
		getLocal(w, BYTE);
		i32(w, '0');
		w.write(Instruction.I32_SUB);
		setLocal(w, DV);
		end(w);
		getLocal(w, BYTE);
		i32(w, 'a');
		w.write(Instruction.I32_GE_S);
		getLocal(w, BYTE);
		i32(w, 'f');
		w.write(Instruction.I32_LE_S);
		w.write(Instruction.I32_AND);
		ifVoid(w);
		getLocal(w, BYTE);
		i32(w, 87);
		w.write(Instruction.I32_SUB);
		setLocal(w, DV);
		end(w);
		getLocal(w, BYTE);
		i32(w, 'A');
		w.write(Instruction.I32_GE_S);
		getLocal(w, BYTE);
		i32(w, 'F');
		w.write(Instruction.I32_LE_S);
		w.write(Instruction.I32_AND);
		ifVoid(w);
		getLocal(w, BYTE);
		i32(w, 55);
		w.write(Instruction.I32_SUB);
		setLocal(w, DV);
		end(w);
		getLocal(w, DV);
		i32(w, 0);
		w.write(Instruction.I32_LT_S);
		brIf(w, 1);
		getLocal(w, DV);
		getLocal(w, RADIX);
		w.write(Instruction.I32_GE_S);
		brIf(w, 1);
		getLocal(w, ACC);
		getLocal(w, RADIX);
		getLocal(w, DV);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_BIG_GROW);
		setLocal(w, ACC);
		advanceCursor(w);
		br(w, 0);
		end(w); // loop
		end(w); // block
		// no digits at all
		loadMem32(w, CURSOR);
		getLocal(w, DSTART);
		w.write(Instruction.I32_EQ);
		ifVoid(w);
		emitErr(w, ctx, ctx.msgRadix());
		end(w);
		// a symbol character right after the digits invalidates the whole token
		loadMem32(w, CURSOR);
		loadMem32(w, END_ADDR);
		w.write(Instruction.I32_LT_S);
		ifVoid(w);
		curByte(w);
		setLocal(w, BYTE);
		getLocal(w, BYTE);
		i32(w, 32);
		w.write(Instruction.I32_GT_S);
		getLocal(w, BYTE);
		i32(w, '(');
		w.write(Instruction.I32_NE);
		w.write(Instruction.I32_AND);
		getLocal(w, BYTE);
		i32(w, ')');
		w.write(Instruction.I32_NE);
		w.write(Instruction.I32_AND);
		getLocal(w, BYTE);
		i32(w, '\'');
		w.write(Instruction.I32_NE);
		w.write(Instruction.I32_AND);
		getLocal(w, BYTE);
		i32(w, '"');
		w.write(Instruction.I32_NE);
		w.write(Instruction.I32_AND);
		getLocal(w, BYTE);
		i32(w, ';');
		w.write(Instruction.I32_NE);
		w.write(Instruction.I32_AND);
		ifVoid(w);
		emitErr(w, ctx, ctx.msgRadix());
		end(w);
		end(w);
		getLocal(w, NEG);
		ifVoid(w);
		getLocal(w, ACC);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_BIG_NEG);
		setLocal(w, ACC);
		end(w);
		getLocal(w, ACC);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	// _rd_bits () -> value: cursor just past "#*"; consumes 0/1 bytes into the general
	// runtime array shape (the frontend's bit-vector lowering -- no packed bits).
	static byte[] buildRdBitsBody() {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		w.write(2);
		w.write(2);
		w.writeRefType(true, Type.EQ.code());
		w.write(3);
		w.write(Type.I32);
		final int DATA = 0, DIMS = 1, START = 2, COUNT = 3, I = 4;
		loadMem32(w, CURSOR);
		setLocal(w, START);
		i32(w, 0);
		setLocal(w, COUNT);
		block(w);
		loop(w);
		loadMem32(w, CURSOR);
		loadMem32(w, END_ADDR);
		w.write(Instruction.I32_GE_S);
		brIf(w, 1);
		curByte(w);
		i32(w, '0');
		w.write(Instruction.I32_EQ);
		curByte(w);
		i32(w, '1');
		w.write(Instruction.I32_EQ);
		w.write(Instruction.I32_OR);
		w.write(Instruction.I32_EQZ);
		brIf(w, 1);
		getLocal(w, COUNT);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		setLocal(w, COUNT);
		advanceCursor(w);
		br(w, 0);
		end(w); // loop
		end(w); // block
		// data
		emitNull(w);
		getLocal(w, COUNT);
		arrayNew(w, WasmLispCompiler.TYPE_HASH_BUCKETS);
		setLocal(w, DATA);
		i32(w, 0);
		setLocal(w, I);
		block(w);
		loop(w);
		getLocal(w, I);
		getLocal(w, COUNT);
		w.write(Instruction.I32_GE_S);
		brIf(w, 1);
		getLocal(w, DATA);
		refCast(w, WasmLispCompiler.TYPE_HASH_BUCKETS);
		getLocal(w, I);
		getLocal(w, START);
		getLocal(w, I);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);
		i32(w, '0');
		w.write(Instruction.I32_SUB);
		i31New(w);
		arraySet(w, WasmLispCompiler.TYPE_HASH_BUCKETS);
		getLocal(w, I);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		setLocal(w, I);
		br(w, 0);
		end(w); // loop
		end(w); // block
		// dims = [count]
		emitNull(w);
		i32(w, 1);
		arrayNew(w, WasmLispCompiler.TYPE_HASH_BUCKETS);
		setLocal(w, DIMS);
		getLocal(w, DIMS);
		refCast(w, WasmLispCompiler.TYPE_HASH_BUCKETS);
		i32(w, 0);
		getLocal(w, COUNT);
		i31New(w);
		arraySet(w, WasmLispCompiler.TYPE_HASH_BUCKETS);
		emitGeneralArrayCell(w, DIMS, DATA);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	/**
	 * Emits the general-array cell over the dims/data buckets locals: {@code TYPE_CELL{
	 * cons(dims, cons(cons(nil, cons(nil, i31 0)), data)) }} -- the exact shape the quote
	 * path and make-array build (no fill pointer, not adjustable, offset 0).
	 */
	private static void emitGeneralArrayCell(WasmWriter w, int DIMS, int DATA) {
		getLocal(w, DIMS);
		emitNull(w);
		emitNull(w);
		i32(w, 0);
		i31New(w);
		structNew(w, WasmLispCompiler.TYPE_CONS);
		structNew(w, WasmLispCompiler.TYPE_CONS);
		getLocal(w, DATA);
		structNew(w, WasmLispCompiler.TYPE_CONS);
		structNew(w, WasmLispCompiler.TYPE_CONS);
		structNew(w, WasmLispCompiler.TYPE_CELL);
	}

	// _rd_len (v) -> i32: proper-list length; an improper tail signals.
	static byte[] buildRdLenBody(ReadCtx ctx) {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		w.write(2);
		w.write(1);
		w.write(Type.I32);
		w.write(1);
		w.writeRefType(true, Type.EQ.code());
		final int V = 0, N = 1, TAIL = 2;
		i32(w, 0);
		setLocal(w, N);
		getLocal(w, V);
		setLocal(w, TAIL);
		block(w);
		loop(w);
		getLocal(w, TAIL);
		w.write(Instruction.REF_IS_NULL);
		brIf(w, 1);
		getLocal(w, TAIL);
		refTest(w, WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.I32_EQZ);
		ifVoid(w);
		emitErr(w, ctx, ctx.msgProper());
		end(w);
		getLocal(w, N);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		setLocal(w, N);
		getLocal(w, TAIL);
		refCast(w, WasmLispCompiler.TYPE_CONS);
		structGet(w, WasmLispCompiler.TYPE_CONS, 1);
		setLocal(w, TAIL);
		br(w, 0);
		end(w); // loop
		end(w); // block
		getLocal(w, N);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	// _rd_level (v) -> value: one nested level of array contents -- nil or a cons;
	// anything else is the "expected a nested list" error.
	static byte[] buildRdLevelBody(ReadCtx ctx) {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		w.write(0);
		final int V = 0;
		getLocal(w, V);
		w.write(Instruction.REF_IS_NULL);
		ifVoid(w);
		emitNull(w);
		w.write(Instruction.RETURN);
		end(w);
		getLocal(w, V);
		refTest(w, WasmLispCompiler.TYPE_CONS);
		ifVoid(w);
		getLocal(w, V);
		w.write(Instruction.RETURN);
		end(w);
		emitErr(w, ctx, ctx.msgNested());
		w.write(Instruction.END);
		return body.toByteArray();
	}

	// _rd_dims (rows, rank) -> dims buckets: the dimension sizes from the first-element
	// chain, as the i31 buckets array every array header stores.
	static byte[] buildRdDimsBody(ReadCtx ctx) {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		w.write(2);
		w.write(2);
		w.writeRefType(true, Type.EQ.code());
		w.write(1);
		w.write(Type.I32);
		final int ROWS = 0, RANK = 1, DIMS = 2, LEVEL = 3, D = 4;
		emitNull(w);
		getLocal(w, RANK);
		arrayNew(w, WasmLispCompiler.TYPE_HASH_BUCKETS);
		setLocal(w, DIMS);
		getLocal(w, DIMS);
		refCast(w, WasmLispCompiler.TYPE_HASH_BUCKETS);
		i32(w, 0);
		getLocal(w, ROWS);
		call(w, WasmLispCompiler.FUNC_RD_LEN);
		i31New(w);
		arraySet(w, WasmLispCompiler.TYPE_HASH_BUCKETS);
		getLocal(w, ROWS);
		setLocal(w, LEVEL);
		i32(w, 1);
		setLocal(w, D);
		block(w);
		loop(w);
		getLocal(w, D);
		getLocal(w, RANK);
		w.write(Instruction.I32_GE_S);
		brIf(w, 1);
		getLocal(w, LEVEL);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.I32_EQZ);
		ifVoid(w);
		getLocal(w, LEVEL);
		refCast(w, WasmLispCompiler.TYPE_CONS);
		structGet(w, WasmLispCompiler.TYPE_CONS, 0);
		call(w, WasmLispCompiler.FUNC_RD_LEVEL);
		setLocal(w, LEVEL);
		end(w);
		getLocal(w, DIMS);
		refCast(w, WasmLispCompiler.TYPE_HASH_BUCKETS);
		getLocal(w, D);
		getLocal(w, LEVEL);
		call(w, WasmLispCompiler.FUNC_RD_LEN);
		i31New(w);
		arraySet(w, WasmLispCompiler.TYPE_HASH_BUCKETS);
		getLocal(w, D);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		setLocal(w, D);
		br(w, 0);
		end(w); // loop
		end(w); // block
		getLocal(w, DIMS);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	// _rd_flat (items, depth, dims, out, idx) -> idx: validates one level against dims
	// and stores the leaves into `out` in row-major order, recursing into nested levels.
	static byte[] buildRdFlatBody(ReadCtx ctx) {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		w.write(2);
		w.write(1);
		w.writeRefType(true, Type.EQ.code());
		w.write(1);
		w.write(Type.I32);
		final int ITEMS = 0, DEPTH = 1, DIMS = 2, OUT = 3, IDX = 4, TAIL = 5, N = 6;
		getLocal(w, ITEMS);
		call(w, WasmLispCompiler.FUNC_RD_LEN);
		setLocal(w, N);
		getLocal(w, N);
		getLocal(w, DIMS);
		refCast(w, WasmLispCompiler.TYPE_HASH_BUCKETS);
		getLocal(w, DEPTH);
		arrayGet(w, WasmLispCompiler.TYPE_HASH_BUCKETS);
		refCast(w, Type.I31.code());
		i31GetS(w);
		w.write(Instruction.I32_NE);
		ifVoid(w);
		emitErr(w, ctx, ctx.msgRagged());
		end(w);
		// last depth: append the leaves
		getLocal(w, DEPTH);
		getLocal(w, DIMS);
		refCast(w, WasmLispCompiler.TYPE_HASH_BUCKETS);
		arrayLen(w);
		i32(w, 1);
		w.write(Instruction.I32_SUB);
		w.write(Instruction.I32_EQ);
		ifVoid(w);
		getLocal(w, ITEMS);
		setLocal(w, TAIL);
		block(w);
		loop(w);
		getLocal(w, TAIL);
		w.write(Instruction.REF_IS_NULL);
		brIf(w, 1);
		getLocal(w, OUT);
		refCast(w, WasmLispCompiler.TYPE_HASH_BUCKETS);
		getLocal(w, IDX);
		getLocal(w, TAIL);
		refCast(w, WasmLispCompiler.TYPE_CONS);
		structGet(w, WasmLispCompiler.TYPE_CONS, 0);
		arraySet(w, WasmLispCompiler.TYPE_HASH_BUCKETS);
		getLocal(w, IDX);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		setLocal(w, IDX);
		getLocal(w, TAIL);
		refCast(w, WasmLispCompiler.TYPE_CONS);
		structGet(w, WasmLispCompiler.TYPE_CONS, 1);
		setLocal(w, TAIL);
		br(w, 0);
		end(w); // loop
		end(w); // block
		getLocal(w, IDX);
		w.write(Instruction.RETURN);
		end(w);
		// deeper: recurse per element
		getLocal(w, ITEMS);
		setLocal(w, TAIL);
		block(w);
		loop(w);
		getLocal(w, TAIL);
		w.write(Instruction.REF_IS_NULL);
		brIf(w, 1);
		getLocal(w, TAIL);
		refCast(w, WasmLispCompiler.TYPE_CONS);
		structGet(w, WasmLispCompiler.TYPE_CONS, 0);
		call(w, WasmLispCompiler.FUNC_RD_LEVEL);
		getLocal(w, DEPTH);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		getLocal(w, DIMS);
		getLocal(w, OUT);
		getLocal(w, IDX);
		call(w, WasmLispCompiler.FUNC_RD_FLAT);
		setLocal(w, IDX);
		getLocal(w, TAIL);
		refCast(w, WasmLispCompiler.TYPE_CONS);
		structGet(w, WasmLispCompiler.TYPE_CONS, 1);
		setLocal(w, TAIL);
		br(w, 0);
		end(w); // loop
		end(w); // block
		getLocal(w, IDX);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	// _rd_infer_rank (rows) -> i32: 1 + the depth of the first-element chain (numpy
	// style), mirroring the frontend's inferFloatArrayRank.
	static byte[] buildRdInferBody() {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		w.write(2);
		w.write(1);
		w.writeRefType(true, Type.EQ.code());
		w.write(1);
		w.write(Type.I32);
		final int ROWS = 0, PROBE = 1, RANK = 2;
		getLocal(w, ROWS);
		w.write(Instruction.REF_IS_NULL);
		ifVoid(w);
		i32(w, 1);
		w.write(Instruction.RETURN);
		end(w);
		getLocal(w, ROWS);
		refCast(w, WasmLispCompiler.TYPE_CONS);
		structGet(w, WasmLispCompiler.TYPE_CONS, 0);
		setLocal(w, PROBE);
		i32(w, 1);
		setLocal(w, RANK);
		block(w);
		loop(w);
		getLocal(w, PROBE);
		w.write(Instruction.REF_IS_NULL);
		ifVoid(w);
		getLocal(w, RANK);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		setLocal(w, RANK);
		br(w, 2); // empty nested level: cannot descend further
		end(w);
		getLocal(w, PROBE);
		refTest(w, WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.I32_EQZ);
		brIf(w, 1);
		getLocal(w, RANK);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		setLocal(w, RANK);
		getLocal(w, PROBE);
		refCast(w, WasmLispCompiler.TYPE_CONS);
		structGet(w, WasmLispCompiler.TYPE_CONS, 0);
		setLocal(w, PROBE);
		br(w, 0);
		end(w); // loop
		end(w); // block
		getLocal(w, RANK);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	// _rd_arrayn (rank) -> value: cursor just past the opening '('; reads the grouped
	// contents as a list, computes/validates dims like the frontend, and builds the
	// general runtime array. #( is rank 1.
	static byte[] buildRdArrayNBody(ReadCtx ctx) {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		w.write(2);
		w.write(3);
		w.writeRefType(true, Type.EQ.code());
		w.write(2);
		w.write(Type.I32);
		final int RANK = 0, ROWS = 1, DIMS = 2, DATA = 3, TOTAL = 4, D = 5;
		getLocal(w, RANK);
		i32(w, 1);
		w.write(Instruction.I32_LT_S);
		ifVoid(w);
		emitErr(w, ctx, ctx.msgRank());
		end(w);
		call(w, WasmLispCompiler.FUNC_READ_LIST);
		setLocal(w, ROWS);
		getLocal(w, ROWS);
		getLocal(w, RANK);
		call(w, WasmLispCompiler.FUNC_RD_DIMS);
		setLocal(w, DIMS);
		emitDimsProduct(w, DIMS, RANK, TOTAL, D);
		emitNull(w);
		getLocal(w, TOTAL);
		arrayNew(w, WasmLispCompiler.TYPE_HASH_BUCKETS);
		setLocal(w, DATA);
		getLocal(w, ROWS);
		i32(w, 0);
		getLocal(w, DIMS);
		getLocal(w, DATA);
		i32(w, 0);
		call(w, WasmLispCompiler.FUNC_RD_FLAT);
		w.write(Instruction.DROP);
		emitGeneralArrayCell(w, DIMS, DATA);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	/** Emits {@code TOTAL = product of the i31 dims}; clobbers {@code D}. */
	private static void emitDimsProduct(WasmWriter w, int DIMS, int RANK, int TOTAL, int D) {
		i32(w, 1);
		setLocal(w, TOTAL);
		i32(w, 0);
		setLocal(w, D);
		block(w);
		loop(w);
		getLocal(w, D);
		getLocal(w, RANK);
		w.write(Instruction.I32_GE_S);
		brIf(w, 1);
		getLocal(w, TOTAL);
		getLocal(w, DIMS);
		refCast(w, WasmLispCompiler.TYPE_HASH_BUCKETS);
		getLocal(w, D);
		arrayGet(w, WasmLispCompiler.TYPE_HASH_BUCKETS);
		refCast(w, Type.I31.code());
		i31GetS(w);
		w.write(Instruction.I32_MUL);
		setLocal(w, TOTAL);
		getLocal(w, D);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		setLocal(w, D);
		br(w, 0);
		end(w); // loop
		end(w); // block
	}

	// _rd_packed (single) -> value: cursor just past "#f(" / "#d("; rank inferred from
	// the nesting depth, leaves coerced to double (narrowed for #f), building the
	// packed TYPE_FARRAY -- an F64ARR/F32ARR data array, or a _v_new'd TYPE_VBLOCK
	// under --simd.
	static byte[] buildRdPackedBody(ReadCtx ctx) {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		w.write(2);
		w.write(5);
		w.writeRefType(true, Type.EQ.code());
		w.write(4);
		w.write(Type.I32);
		final int SINGLE = 0, ROWS = 1, DIMS = 2, TMP = 3, DATA = 4, VAL = 5, RANK = 6, TOTAL = 7, D = 8, I = 9;
		call(w, WasmLispCompiler.FUNC_READ_LIST);
		setLocal(w, ROWS);
		getLocal(w, ROWS);
		call(w, WasmLispCompiler.FUNC_RD_INFER);
		setLocal(w, RANK);
		getLocal(w, ROWS);
		getLocal(w, RANK);
		call(w, WasmLispCompiler.FUNC_RD_DIMS);
		setLocal(w, DIMS);
		emitDimsProduct(w, DIMS, RANK, TOTAL, D);
		emitNull(w);
		getLocal(w, TOTAL);
		arrayNew(w, WasmLispCompiler.TYPE_HASH_BUCKETS);
		setLocal(w, TMP);
		getLocal(w, ROWS);
		i32(w, 0);
		getLocal(w, DIMS);
		getLocal(w, TMP);
		i32(w, 0);
		call(w, WasmLispCompiler.FUNC_RD_FLAT);
		w.write(Instruction.DROP);
		if (ctx.simd()) {
			// vb = _v_new(total, kind); then _v_set per element (an f32 round-trip at
			// single width happens inside _v_set)
			getLocal(w, TOTAL);
			getLocal(w, SINGLE);
			call(w, WasmLispCompiler.FUNC_VEC_BASE + WasmVecSimdRuntimeBuilder.V_NEW);
			setLocal(w, DATA);
			i32(w, 0);
			setLocal(w, I);
			block(w);
			loop(w);
			getLocal(w, I);
			getLocal(w, TOTAL);
			w.write(Instruction.I32_GE_S);
			brIf(w, 1);
			getLocal(w, DATA);
			getLocal(w, I);
			emitCoerceF64(w, ctx, TMP, I, VAL);
			call(w, WasmLispCompiler.FUNC_VEC_BASE + WasmVecSimdRuntimeBuilder.V_SET);
			w.write(Instruction.DROP);
			getLocal(w, I);
			i32(w, 1);
			w.write(Instruction.I32_ADD);
			setLocal(w, I);
			br(w, 0);
			end(w); // loop
			end(w); // block
		}
		else {
			getLocal(w, SINGLE);
			ifVoid(w);
			// f32 data
			w.write(Instruction.F32_CONST);
			w.write(0x00, 0x00, 0x00, 0x00);
			getLocal(w, TOTAL);
			arrayNew(w, WasmLispCompiler.TYPE_F32ARR);
			setLocal(w, DATA);
			i32(w, 0);
			setLocal(w, I);
			block(w);
			loop(w);
			getLocal(w, I);
			getLocal(w, TOTAL);
			w.write(Instruction.I32_GE_S);
			brIf(w, 1);
			getLocal(w, DATA);
			refCast(w, WasmLispCompiler.TYPE_F32ARR);
			getLocal(w, I);
			emitCoerceF64(w, ctx, TMP, I, VAL);
			w.write(Instruction.F32_DEMOTE_F64);
			arraySet(w, WasmLispCompiler.TYPE_F32ARR);
			getLocal(w, I);
			i32(w, 1);
			w.write(Instruction.I32_ADD);
			setLocal(w, I);
			br(w, 0);
			end(w); // loop
			end(w); // block
			w.write(Instruction.ELSE);
			// f64 data
			w.write(Instruction.F64_CONST);
			w.writeF64(0.0);
			getLocal(w, TOTAL);
			arrayNew(w, WasmLispCompiler.TYPE_F64ARR);
			setLocal(w, DATA);
			i32(w, 0);
			setLocal(w, I);
			block(w);
			loop(w);
			getLocal(w, I);
			getLocal(w, TOTAL);
			w.write(Instruction.I32_GE_S);
			brIf(w, 1);
			getLocal(w, DATA);
			refCast(w, WasmLispCompiler.TYPE_F64ARR);
			getLocal(w, I);
			emitCoerceF64(w, ctx, TMP, I, VAL);
			arraySet(w, WasmLispCompiler.TYPE_F64ARR);
			getLocal(w, I);
			i32(w, 1);
			w.write(Instruction.I32_ADD);
			setLocal(w, I);
			br(w, 0);
			end(w); // loop
			end(w); // block
			end(w); // if single
		}
		getLocal(w, DIMS);
		getLocal(w, DATA);
		structNew(w, WasmLispCompiler.TYPE_FARRAY);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	/**
	 * Emits an f64 push coercing {@code TMP[I]}: an i31 integer, a float struct or a
	 * ratio; anything else signals the frontend's "expected a number" error.
	 */
	private static void emitCoerceF64(WasmWriter w, ReadCtx ctx, int TMP, int I, int VAL) {
		getLocal(w, TMP);
		refCast(w, WasmLispCompiler.TYPE_HASH_BUCKETS);
		getLocal(w, I);
		arrayGet(w, WasmLispCompiler.TYPE_HASH_BUCKETS);
		setLocal(w, VAL);
		getLocal(w, VAL);
		refTest(w, Type.I31.code());
		w.write(Instruction.IF, 0x7C); // (result f64)
		getLocal(w, VAL);
		refCast(w, Type.I31.code());
		i31GetS(w);
		w.write(Instruction.F64_CONVERT_S_I32);
		w.write(Instruction.ELSE);
		getLocal(w, VAL);
		refTest(w, WasmLispCompiler.TYPE_FLOAT);
		w.write(Instruction.IF, 0x7C);
		getLocal(w, VAL);
		refCast(w, WasmLispCompiler.TYPE_FLOAT);
		structGet(w, WasmLispCompiler.TYPE_FLOAT, 0);
		w.write(Instruction.ELSE);
		getLocal(w, VAL);
		refTest(w, WasmLispCompiler.TYPE_RATIO);
		w.write(Instruction.IF, 0x7C);
		getLocal(w, VAL);
		refCast(w, WasmLispCompiler.TYPE_RATIO);
		structGet(w, WasmLispCompiler.TYPE_RATIO, 0);
		w.write(Instruction.F64_CONVERT_S_I32);
		getLocal(w, VAL);
		refCast(w, WasmLispCompiler.TYPE_RATIO);
		structGet(w, WasmLispCompiler.TYPE_RATIO, 1);
		w.write(Instruction.F64_CONVERT_S_I32);
		w.write(Instruction.F64_DIV);
		w.write(Instruction.ELSE);
		emitErr(w, ctx, ctx.msgPackedNum());
		end(w);
		end(w);
		end(w);
	}

	// _rd_struct () -> value: cursor just past "#S(". Resolves the type in the baked
	// directory (findStructTag's exact-then-member-fallback rule), applies the fold's
	// slot rules (leftmost repeated slot wins, an omitted slot takes its nil/baked
	// constant-text initform or signals), and builds the TYPE_INSTANCE %obj-new builds.
	// Kept beside the printer's emitPrintInstance shape: the layout record supplies the
	// slot names, the slots array holds the values only.
	static byte[] buildRdStructBody(ReadCtx ctx) {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		if (ctx.instanceTypeIndex() < 0) {
			// no instance can exist, so no defstruct exists: any #S(...) is the
			// "not a defined structure type" error
			w.write(0);
			emitSkipWs(w, ctx);
			emitErr(w, ctx, ctx.msgStructType());
			w.write(Instruction.END);
			return body.toByteArray();
		}
		w.write(2);
		w.write(2);
		w.writeRefType(true, Type.EQ.code());
		w.write(20);
		w.write(Type.I32);
		final int SLOTS = 0, VAL = 1;
		final int NSTART = 2, NLEN = 3, QUAL = 4, PSTART = 5, PLEN = 6, MSTART = 7, MLEN = 8, IDX = 9, ENT = 10,
				LAYADDR = 11, SLOTC = 12, K = 13, SSTART = 14, SLEN = 15, SBASE = 16, SAVC = 17, SAVE = 18, CI = 19,
				INITS = 20, TMPI = 21;
		emitSkipWs(w, ctx);
		loadMem32(w, CURSOR);
		loadMem32(w, END_ADDR);
		w.write(Instruction.I32_GE_S);
		ifVoid(w);
		emitErr(w, ctx, ctx.msgEof());
		end(w);
		curByte(w);
		i32(w, ')');
		w.write(Instruction.I32_EQ);
		ifVoid(w);
		advanceCursor(w);
		emitErr(w, ctx, ctx.msgStructEmpty());
		end(w);
		call(w, WasmLispCompiler.FUNC_RD_TOKEN);
		setLocal(w, NSTART);
		loadMem32(w, CURSOR);
		getLocal(w, NSTART);
		w.write(Instruction.I32_SUB);
		setLocal(w, NLEN);
		getLocal(w, NLEN);
		w.write(Instruction.I32_EQZ);
		ifVoid(w);
		emitErr(w, ctx, ctx.msgStructName());
		end(w);
		// split PKG:MEMBER: CI = first ':', TMPI = last ':'
		i32(w, -1);
		setLocal(w, CI);
		i32(w, -1);
		setLocal(w, TMPI);
		getLocal(w, NSTART);
		setLocal(w, K);
		block(w);
		loop(w);
		getLocal(w, K);
		getLocal(w, NSTART);
		getLocal(w, NLEN);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_GE_S);
		brIf(w, 1);
		getLocal(w, K);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);
		i32(w, ':');
		w.write(Instruction.I32_EQ);
		ifVoid(w);
		getLocal(w, CI);
		i32(w, 0);
		w.write(Instruction.I32_LT_S);
		ifVoid(w);
		getLocal(w, K);
		setLocal(w, CI);
		end(w);
		getLocal(w, K);
		setLocal(w, TMPI);
		end(w);
		getLocal(w, K);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		setLocal(w, K);
		br(w, 0);
		end(w); // loop
		end(w); // block
		getLocal(w, CI);
		i32(w, 0);
		w.write(Instruction.I32_LT_S);
		ifVoid(w);
		i32(w, 0);
		setLocal(w, QUAL);
		getLocal(w, NSTART);
		setLocal(w, MSTART);
		getLocal(w, NLEN);
		setLocal(w, MLEN);
		w.write(Instruction.ELSE);
		i32(w, 1);
		setLocal(w, QUAL);
		getLocal(w, NSTART);
		setLocal(w, PSTART);
		getLocal(w, CI);
		getLocal(w, NSTART);
		w.write(Instruction.I32_SUB);
		setLocal(w, PLEN);
		getLocal(w, TMPI);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		setLocal(w, MSTART);
		getLocal(w, NSTART);
		getLocal(w, NLEN);
		w.write(Instruction.I32_ADD);
		getLocal(w, MSTART);
		w.write(Instruction.I32_SUB);
		setLocal(w, MLEN);
		end(w);
		// directory search: pass 1 exact, pass 2 member fallback, pass 3 class hint
		block(w); // SEARCH: broken with ENT set on a match
		// pass 1
		i32(w, 0);
		setLocal(w, IDX);
		block(w);
		loop(w);
		getLocal(w, IDX);
		i32(w, ctx.structDirCount());
		w.write(Instruction.I32_GE_S);
		brIf(w, 1);
		emitStructEntryAddr(w, ctx, IDX, ENT);
		// struct kind + member match
		emitStructMemberMatch(w, ENT, MSTART, MLEN, TMPI);
		ifVoid(w);
		// exact package rule
		getLocal(w, ENT);
		i32(w, 8);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		w.write(Instruction.I32_EQZ);
		ifVoid(w);
		// unqualified entry: an unqualified spelling matches exactly
		getLocal(w, QUAL);
		w.write(Instruction.I32_EQZ);
		brIf(w, 4); // labels: this if=0, member if=1, loop=2, block=3, SEARCH=4
		w.write(Instruction.ELSE);
		// qualified entry: same package required
		getLocal(w, QUAL);
		ifVoid(w);
		getLocal(w, ENT);
		i32(w, 8);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		getLocal(w, PLEN);
		w.write(Instruction.I32_EQ);
		ifVoid(w);
		getLocal(w, ENT);
		i32(w, 4);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		getLocal(w, PSTART);
		getLocal(w, PLEN);
		call(w, WasmLispCompiler.FUNC_RD_MEMEQ);
		// labels outward: pkg-len if=0, qual if=1, the unqual/qual if=2 (this is its
		// else arm), member if=3, loop=4, block=5, SEARCH=6
		brIf(w, 6);
		end(w);
		end(w);
		end(w);
		end(w);
		getLocal(w, IDX);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		setLocal(w, IDX);
		br(w, 0);
		end(w); // pass 1 loop
		end(w); // pass 1 block
		// pass 2: a qualified spelling falls back to an unqualified entry
		getLocal(w, QUAL);
		ifVoid(w);
		i32(w, 0);
		setLocal(w, IDX);
		block(w);
		loop(w);
		getLocal(w, IDX);
		i32(w, ctx.structDirCount());
		w.write(Instruction.I32_GE_S);
		brIf(w, 1);
		emitStructEntryAddr(w, ctx, IDX, ENT);
		emitStructMemberMatch(w, ENT, MSTART, MLEN, TMPI);
		ifVoid(w);
		getLocal(w, ENT);
		i32(w, 8);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		w.write(Instruction.I32_EQZ);
		brIf(w, 4); // labels: member if=0, loop=1, block=2, qual if=3, SEARCH=4
		end(w);
		getLocal(w, IDX);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		setLocal(w, IDX);
		br(w, 0);
		end(w); // pass 2 loop
		end(w); // pass 2 block
		end(w); // if QUAL
		// pass 3: a class of that member name -> the defstruct-only hint
		i32(w, 0);
		setLocal(w, IDX);
		block(w);
		loop(w);
		getLocal(w, IDX);
		i32(w, ctx.structDirCount());
		w.write(Instruction.I32_GE_S);
		brIf(w, 1);
		emitStructEntryAddr(w, ctx, IDX, ENT);
		// class kind + member match
		getLocal(w, ENT);
		i32(w, 20);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		ifVoid(w);
		getLocal(w, ENT);
		i32(w, 16);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		getLocal(w, MLEN);
		w.write(Instruction.I32_EQ);
		ifVoid(w);
		getLocal(w, ENT);
		i32(w, 12);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		getLocal(w, MSTART);
		getLocal(w, MLEN);
		call(w, WasmLispCompiler.FUNC_RD_MEMEQ);
		ifVoid(w);
		emitErr(w, ctx, ctx.msgStructClassHint());
		end(w);
		end(w);
		end(w);
		getLocal(w, IDX);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		setLocal(w, IDX);
		br(w, 0);
		end(w); // pass 3 loop
		end(w); // pass 3 block
		emitErr(w, ctx, ctx.msgStructType());
		end(w); // SEARCH
		// found: layout + slots
		getLocal(w, ENT);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		setLocal(w, LAYADDR);
		i32(w, ctx.structDirBase());
		getLocal(w, ENT);
		i32(w, 24);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		w.write(Instruction.I32_ADD);
		setLocal(w, INITS);
		getLocal(w, LAYADDR);
		i32(w, WasmInstanceLayouts.OFF_SLOT_COUNT);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		setLocal(w, SLOTC);
		emitNull(w);
		getLocal(w, SLOTC);
		arrayNew(w, WasmLispCompiler.TYPE_HASH_BUCKETS);
		setLocal(w, SLOTS);
		// fill every slot with the SLOTS array itself as an "unset" sentinel (nil
		// values are null refs, so unset needs a marker no read datum can be)
		i32(w, 0);
		setLocal(w, K);
		block(w);
		loop(w);
		getLocal(w, K);
		getLocal(w, SLOTC);
		w.write(Instruction.I32_GE_S);
		brIf(w, 1);
		getLocal(w, SLOTS);
		refCast(w, WasmLispCompiler.TYPE_HASH_BUCKETS);
		getLocal(w, K);
		getLocal(w, SLOTS);
		arraySet(w, WasmLispCompiler.TYPE_HASH_BUCKETS);
		getLocal(w, K);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		setLocal(w, K);
		br(w, 0);
		end(w); // loop
		end(w); // block
		// slot name/value pairs
		block(w); // pair-done
		loop(w); // pair loop
		emitSkipWs(w, ctx);
		loadMem32(w, CURSOR);
		loadMem32(w, END_ADDR);
		w.write(Instruction.I32_GE_S);
		ifVoid(w);
		emitErr(w, ctx, ctx.msgEof());
		end(w);
		curByte(w);
		i32(w, ')');
		w.write(Instruction.I32_EQ);
		ifVoid(w);
		advanceCursor(w);
		br(w, 2); // break the pair loop
		end(w);
		call(w, WasmLispCompiler.FUNC_RD_TOKEN);
		setLocal(w, SSTART);
		loadMem32(w, CURSOR);
		getLocal(w, SSTART);
		w.write(Instruction.I32_SUB);
		setLocal(w, SLEN);
		getLocal(w, SLEN);
		w.write(Instruction.I32_EQZ);
		ifVoid(w);
		emitErr(w, ctx, ctx.msgStructNoSlot());
		end(w);
		// strip a leading "#:" or ":" from the slot name
		getLocal(w, SSTART);
		setLocal(w, SBASE);
		getLocal(w, SSTART);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);
		i32(w, '#');
		w.write(Instruction.I32_EQ);
		getLocal(w, SLEN);
		i32(w, 1);
		w.write(Instruction.I32_GT_S);
		w.write(Instruction.I32_AND);
		getLocal(w, SSTART);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);
		i32(w, ':');
		w.write(Instruction.I32_EQ);
		w.write(Instruction.I32_AND);
		ifVoid(w);
		getLocal(w, SSTART);
		i32(w, 2);
		w.write(Instruction.I32_ADD);
		setLocal(w, SBASE);
		w.write(Instruction.ELSE);
		getLocal(w, SSTART);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);
		i32(w, ':');
		w.write(Instruction.I32_EQ);
		ifVoid(w);
		getLocal(w, SSTART);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		setLocal(w, SBASE);
		end(w);
		end(w);
		// strip a package prefix (the last ':' before the end)
		getLocal(w, SBASE);
		setLocal(w, K);
		i32(w, -1);
		setLocal(w, TMPI);
		block(w);
		loop(w);
		getLocal(w, K);
		getLocal(w, SSTART);
		getLocal(w, SLEN);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_GE_S);
		brIf(w, 1);
		getLocal(w, K);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);
		i32(w, ':');
		w.write(Instruction.I32_EQ);
		ifVoid(w);
		getLocal(w, K);
		setLocal(w, TMPI);
		end(w);
		getLocal(w, K);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		setLocal(w, K);
		br(w, 0);
		end(w); // loop
		end(w); // block
		getLocal(w, TMPI);
		i32(w, 0);
		w.write(Instruction.I32_GE_S);
		ifVoid(w);
		getLocal(w, TMPI);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		setLocal(w, SBASE);
		end(w);
		// base-name length into TMPI
		getLocal(w, SSTART);
		getLocal(w, SLEN);
		w.write(Instruction.I32_ADD);
		getLocal(w, SBASE);
		w.write(Instruction.I32_SUB);
		setLocal(w, TMPI);
		// the value
		emitSkipWs(w, ctx);
		loadMem32(w, CURSOR);
		loadMem32(w, END_ADDR);
		w.write(Instruction.I32_GE_S);
		ifVoid(w);
		emitErr(w, ctx, ctx.msgEof());
		end(w);
		curByte(w);
		i32(w, ')');
		w.write(Instruction.I32_EQ);
		ifVoid(w);
		emitErr(w, ctx, ctx.msgStructOdd());
		end(w);
		call(w, WasmLispCompiler.FUNC_READ_EXPR);
		setLocal(w, VAL);
		// slot index by base name against the layout record's slot entries
		i32(w, -1);
		setLocal(w, IDX);
		i32(w, 0);
		setLocal(w, K);
		block(w);
		loop(w);
		getLocal(w, K);
		getLocal(w, SLOTC);
		w.write(Instruction.I32_GE_S);
		brIf(w, 1);
		// entry = LAYADDR + OFF_SLOTS + K*8
		getLocal(w, LAYADDR);
		i32(w, WasmInstanceLayouts.OFF_SLOTS);
		w.write(Instruction.I32_ADD);
		getLocal(w, K);
		i32(w, 8);
		w.write(Instruction.I32_MUL);
		w.write(Instruction.I32_ADD);
		setLocal(w, SAVC); // SAVC doubles as the slot-entry scratch before any save
		getLocal(w, SAVC);
		i32(w, 4);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		getLocal(w, TMPI);
		w.write(Instruction.I32_EQ);
		ifVoid(w);
		getLocal(w, SAVC);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		getLocal(w, SBASE);
		getLocal(w, TMPI);
		call(w, WasmLispCompiler.FUNC_RD_MEMEQ);
		ifVoid(w);
		getLocal(w, K);
		setLocal(w, IDX);
		br(w, 3); // break the slot-scan block
		end(w);
		end(w);
		getLocal(w, K);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		setLocal(w, K);
		br(w, 0);
		end(w); // loop
		end(w); // block
		getLocal(w, IDX);
		i32(w, 0);
		w.write(Instruction.I32_LT_S);
		ifVoid(w);
		emitErr(w, ctx, ctx.msgStructNoSlot());
		end(w);
		// leftmost wins: store only while the slot still holds the sentinel
		getLocal(w, SLOTS);
		refCast(w, WasmLispCompiler.TYPE_HASH_BUCKETS);
		getLocal(w, IDX);
		arrayGet(w, WasmLispCompiler.TYPE_HASH_BUCKETS);
		getLocal(w, SLOTS);
		w.write(Instruction.REF_EQ);
		ifVoid(w);
		getLocal(w, SLOTS);
		refCast(w, WasmLispCompiler.TYPE_HASH_BUCKETS);
		getLocal(w, IDX);
		getLocal(w, VAL);
		arraySet(w, WasmLispCompiler.TYPE_HASH_BUCKETS);
		end(w);
		br(w, 0); // continue the pair loop
		end(w); // pair loop
		end(w); // pair-done block
		// omitted slots: nil, the baked constant text re-read in place, or a signal
		i32(w, 0);
		setLocal(w, K);
		block(w);
		loop(w);
		getLocal(w, K);
		getLocal(w, SLOTC);
		w.write(Instruction.I32_GE_S);
		brIf(w, 1);
		getLocal(w, SLOTS);
		refCast(w, WasmLispCompiler.TYPE_HASH_BUCKETS);
		getLocal(w, K);
		arrayGet(w, WasmLispCompiler.TYPE_HASH_BUCKETS);
		getLocal(w, SLOTS);
		w.write(Instruction.REF_EQ);
		ifVoid(w);
		// initLen into TMPI
		getLocal(w, INITS);
		getLocal(w, K);
		i32(w, 8);
		w.write(Instruction.I32_MUL);
		w.write(Instruction.I32_ADD);
		i32(w, 4);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		setLocal(w, TMPI);
		getLocal(w, TMPI);
		w.write(Instruction.I32_EQZ);
		ifVoid(w);
		// nil initform
		getLocal(w, SLOTS);
		refCast(w, WasmLispCompiler.TYPE_HASH_BUCKETS);
		getLocal(w, K);
		emitNull(w);
		arraySet(w, WasmLispCompiler.TYPE_HASH_BUCKETS);
		w.write(Instruction.ELSE);
		getLocal(w, TMPI);
		i32(w, 0);
		w.write(Instruction.I32_LT_S);
		ifVoid(w);
		emitErr(w, ctx, ctx.msgStructInit());
		end(w);
		// re-read the baked constant text in place (save/restore the reader state)
		loadMem32(w, CURSOR);
		setLocal(w, SAVC);
		loadMem32(w, END_ADDR);
		setLocal(w, SAVE);
		getLocal(w, INITS);
		getLocal(w, K);
		i32(w, 8);
		w.write(Instruction.I32_MUL);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		setLocal(w, CI); // the text offset
		storeMem32(w, CURSOR, () -> getLocal(w, CI));
		storeMem32(w, END_ADDR, () -> {
			getLocal(w, CI);
			getLocal(w, TMPI);
			w.write(Instruction.I32_ADD);
		});
		call(w, WasmLispCompiler.FUNC_READ_EXPR);
		setLocal(w, VAL);
		storeMem32(w, CURSOR, () -> getLocal(w, SAVC));
		storeMem32(w, END_ADDR, () -> getLocal(w, SAVE));
		getLocal(w, SLOTS);
		refCast(w, WasmLispCompiler.TYPE_HASH_BUCKETS);
		getLocal(w, K);
		getLocal(w, VAL);
		arraySet(w, WasmLispCompiler.TYPE_HASH_BUCKETS);
		end(w);
		end(w);
		getLocal(w, K);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		setLocal(w, K);
		br(w, 0);
		end(w); // loop
		end(w); // block
		// the instance: %obj-new's exact shape
		getLocal(w, LAYADDR);
		getLocal(w, SLOTS);
		structNew(w, ctx.instanceTypeIndex());
		w.write(Instruction.END);
		return body.toByteArray();
	}

	/** Pushes nothing; sets {@code ENT} = the address of directory entry {@code IDX}. */
	private static void emitStructEntryAddr(WasmWriter w, ReadCtx ctx, int IDX, int ENT) {
		i32(w, ctx.structDirBase() + 4);
		getLocal(w, IDX);
		i32(w, 28);
		w.write(Instruction.I32_MUL);
		w.write(Instruction.I32_ADD);
		setLocal(w, ENT);
	}

	/**
	 * Pushes an i32: whether the entry at {@code ENT} is a struct whose member name
	 * equals the token at {@code [MSTART, MSTART+MLEN)}. Clobbers {@code TMPI}.
	 */
	private static void emitStructMemberMatch(WasmWriter w, int ENT, int MSTART, int MLEN, int TMPI) {
		// kind == 0 (struct)
		getLocal(w, ENT);
		i32(w, 20);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		w.write(Instruction.I32_EQZ);
		// memberLen == MLEN
		getLocal(w, ENT);
		i32(w, 16);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		getLocal(w, MLEN);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.I32_AND);
		setLocal(w, TMPI);
		getLocal(w, TMPI);
		ifVoid(w);
		getLocal(w, ENT);
		i32(w, 12);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		getLocal(w, MSTART);
		getLocal(w, MLEN);
		call(w, WasmLispCompiler.FUNC_RD_MEMEQ);
		setLocal(w, TMPI);
		end(w);
		getLocal(w, TMPI);
	}

}
