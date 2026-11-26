package am.ik.rontolisp.codegen.wasm;

import java.util.Map;

import am.ik.rontolisp.codegen.wasm.WasmJsonRuntimeBuilder.Asm;
import am.ik.rontolisp.codegen.wasm.WasmJsonRuntimeBuilder.Kind;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;

/**
 * Builds the wasm-GC span scan: {@code rontolisp::%json-scan}, the native half of
 * {@code rontolisp:json-span} and {@code rontolisp:json-value} on this backend
 * ({@code .kb/json.md}, "json-span"). It is {@code json.lisp}'s {@code %json-locate},
 * decision for decision, over the document's bytes where they are: a string's own, an
 * octet vector's (a framed copy), or linear memory's at an address, which it never copies
 * -- a filter's body stays where the host put it.
 *
 * <p>
 * The scan reads a quote past the last byte, as the decoder reads a string's closing
 * frame ({@link WasmJsonRuntimeBuilder}): every loop a quote, a non-digit or a non-blank
 * stops ends there, and each arm that could take it for a byte of the document asks
 * whether it is past the end. A string has the quote already; past an address the scan
 * writes one and puts the byte back after, so the bytes have to be followed by one of the
 * memory's, and the few that end with the memory are copied instead.
 *
 * <p>
 * {@code (%json-scan document size path mode)} answers NIL when the path leads nowhere,
 * the i31 code of the error {@code %json-span-fail} signals, or the value: under mode 0
 * the list {@code (start end count cut-start cut-end)} of byte offsets from the
 * document's first byte, under mode 1 a string of its bytes. It allocates the path's
 * array and a stack of the open containers' kinds, nothing a byte; a key is compared only
 * in a container on the way to the value.
 *
 * <p>
 * The body is two copies of one emitter, the byte loads the one difference: an
 * {@code i32.load8_u} for memory, an {@code array.get_u} for an array.
 */
final class WasmJsonSpanRuntimeBuilder {

	/**
	 * {@code _json_scan (document, size, path, mode) -> list, text, NIL or error code}.
	 */
	static final int SCAN = 0;

	static final int FUNC_COUNT = 1;

	/** The document is no string, octet vector or address. */
	static final int ERR_NOT_DOCUMENT = 0;

	/** The address and the size name bytes past the memory's end. */
	static final int ERR_OUTSIDE_MEMORY = 18;

	private static final int BYTES = WasmLispCompiler.TYPE_STR_BYTES;

	private static final int ARR = WasmLispCompiler.TYPE_HASH_BUCKETS;

	private static final int STRING = WasmLispCompiler.TYPE_STRING;

	private static final int CONS = WasmLispCompiler.TYPE_CONS;

	private static final int OCTETS = WasmLispCompiler.TYPE_I8ARR;

	private static final int I31 = Type.I31.code();

	private static final int QUOTE = '"';

	private static final int BACKSLASH = '\\';

	// What MATCH says the next value is.
	private static final int ON_THE_WAY = 1;

	private static final int THE_VALUE = 2;

	private WasmJsonSpanRuntimeBuilder() {
	}

	/**
	 * The signature of the block's function at an offset.
	 * @param i the offset
	 * @return its type index
	 */
	static int typeIndexOf(int i) {
		if (i != SCAN) {
			throw new IllegalArgumentException("no JSON span function " + i);
		}
		return WasmLispCompiler.TYPE_CALLABLE_BASE + 3;
	}

	/**
	 * The body of the block's function at an offset.
	 * @param i the offset
	 * @param flags the module's flags
	 * @return the encoded body
	 */
	static byte[] build(int i, WasmJsonRuntimeBuilder.Flags flags) {
		if (i != SCAN) {
			throw new IllegalArgumentException("no JSON span function " + i);
		}
		Asm a = new Asm(4);
		new Scanner(a, flags).emit();
		return a.finish();
	}

	/** The scan's locals and the code that shares them. */
	private static final class Scanner {

		private final Asm a;

		private final WasmJsonRuntimeBuilder.Flags flags;

		// params
		private final int document = 0;

		private final int sizeArg = 1;

		private final int path = 2;

		private final int mode = 3;

		// the bytes: data[lo, end) or memory[lo, end), a quote at end
		private final int data;

		private final int lo;

		private final int end;

		private final int size;

		private final int saved;

		private final int p;

		private final int b;

		// the path
		private final int segs;

		private final int plen;

		// the containers the scan is inside: obj the innermost's kind, kinds the others'
		private final int depth;

		private final int obj;

		private final int kinds;

		private final int grown;

		// the way to the value, and the value
		private final int on;

		private final int focus;

		private final int tdepth;

		private final int index;

		private final int match;

		private final int found;

		private final int tstart;

		private final int tend;

		private final int tcount;

		private final int mstart;

		private final int mcomma;

		private final int copen;

		private final int lastComma;

		private final int commaDepth;

		// a key, and the segment it is compared with
		private final int kstart;

		private final int escaped;

		private final int seg;

		private final int segBytes;

		private final int segLen;

		private final int same;

		private final int k;

		private final int v;

		// scratch
		private final int q;

		private final int j;

		private final int cp;

		private final int hv;

		private final int digit;

		private final int count;

		private final int at;

		private final int x;

		private final int result;

		private final int out;

		private final int len;

		private final int id;

		private final int cs;

		private final int ce;

		Scanner(Asm a, WasmJsonRuntimeBuilder.Flags flags) {
			this.a = a;
			this.flags = flags;
			this.data = a.local(Kind.BYTES);
			this.lo = a.local(Kind.I32);
			this.end = a.local(Kind.I32);
			this.size = a.local(Kind.I32);
			this.saved = a.local(Kind.I32);
			this.p = a.local(Kind.I32);
			this.b = a.local(Kind.I32);
			this.segs = a.local(Kind.ARR);
			this.plen = a.local(Kind.I32);
			this.depth = a.local(Kind.I32);
			this.obj = a.local(Kind.I32);
			this.kinds = a.local(Kind.BYTES);
			this.grown = a.local(Kind.BYTES);
			this.on = a.local(Kind.I32);
			this.focus = a.local(Kind.I32);
			this.tdepth = a.local(Kind.I32);
			this.index = a.local(Kind.I32);
			this.match = a.local(Kind.I32);
			this.found = a.local(Kind.I32);
			this.tstart = a.local(Kind.I32);
			this.tend = a.local(Kind.I32);
			this.tcount = a.local(Kind.I32);
			this.mstart = a.local(Kind.I32);
			this.mcomma = a.local(Kind.I32);
			this.copen = a.local(Kind.I32);
			this.lastComma = a.local(Kind.I32);
			this.commaDepth = a.local(Kind.I32);
			this.kstart = a.local(Kind.I32);
			this.escaped = a.local(Kind.I32);
			this.seg = a.local(Kind.EQ);
			this.segBytes = a.local(Kind.BYTES);
			this.segLen = a.local(Kind.I32);
			this.same = a.local(Kind.I32);
			this.k = a.local(Kind.I32);
			this.v = a.local(Kind.I32);
			this.q = a.local(Kind.I32);
			this.j = a.local(Kind.I32);
			this.cp = a.local(Kind.I32);
			this.hv = a.local(Kind.I32);
			this.digit = a.local(Kind.I32);
			this.count = a.local(Kind.I32);
			this.at = a.local(Kind.I32);
			this.x = a.local(Kind.EQ);
			this.result = a.local(Kind.EQ);
			this.out = a.local(Kind.BYTES);
			this.len = a.local(Kind.I32);
			this.id = a.local(Kind.I32);
			this.cs = a.local(Kind.I32);
			this.ce = a.local(Kind.I32);
		}

		void emit() {
			emitPath();
			this.a.block("scanned");
			this.a.block("array");
			this.a.block("memory");
			emitDocument();
			this.a.end();
			// the bytes at the address, the quote written past them
			this.a.block("done");
			emitScan(true);
			this.a.end();
			this.a.get(this.end).get(this.saved).store8();
			this.a.br("scanned");
			this.a.end();
			this.a.block("done");
			emitScan(false);
			this.a.end();
			this.a.end();
			this.a.get(this.result);
		}

		// segs = the path's segments, a character vector as its string.
		private void emitPath() {
			this.a.i32(0).set(this.plen);
			this.a.get(this.path).set(this.x);
			this.a.block("counted");
			this.a.loop("count");
			this.a.get(this.x).isNull().brIf("counted");
			inc(this.plen, 1);
			this.a.get(this.x).refCast(CONS).structGet(CONS, 1).set(this.x);
			this.a.br("count");
			this.a.end();
			this.a.end();
			this.a.get(this.plen).arrayNewDefault(ARR).set(this.segs);
			this.a.get(this.path).set(this.x);
			this.a.i32(0).set(this.k);
			this.a.block("filled");
			this.a.loop("fill");
			this.a.get(this.k).get(this.plen).op(Instruction.I32_GE_U).brIf("filled");
			this.a.get(this.x).refCast(CONS).structGet(CONS, 0).set(this.seg);
			WasmEmitHelper.emitCharvecToStrLocal(this.a.w, this.seg, this.flags.charvecPossible());
			this.a.get(this.segs).get(this.k).get(this.seg).arraySet(ARR);
			this.a.get(this.x).refCast(CONS).structGet(CONS, 1).set(this.x);
			inc(this.k, 1);
			this.a.br("fill");
			this.a.end();
			this.a.end();
		}

		// The document's bytes: lo, end and the branch to the scan over them, or the
		// result for a document that is none.
		private void emitDocument() {
			this.a.get(this.document).refTest(I31);
			this.a.get(this.document).refTest(WasmLispCompiler.TYPE_BIGNUM).op(Instruction.I32_OR).ifVoid();
			// an address and a size, both in the u32 range (%json-native-scan)
			this.a.get(this.document).call(WasmLispCompiler.FUNC_U32_VAL).set(this.lo);
			this.a.get(this.sizeArg).call(WasmLispCompiler.FUNC_U32_VAL).set(this.size);
			this.a.get(this.lo).op(Instruction.I64_EXTEND_U_I32).get(this.size).op(Instruction.I64_EXTEND_U_I32);
			this.a.op(Instruction.I64_ADD);
			this.a.memorySize().op(Instruction.I64_EXTEND_U_I32).i64(16).op(Instruction.I64_SHL);
			this.a.op(Instruction.I64_GT_U).ifVoid();
			fail(ERR_OUTSIDE_MEMORY, "scanned");
			this.a.end();
			this.a.get(this.lo).op(Instruction.I64_EXTEND_U_I32).get(this.size).op(Instruction.I64_EXTEND_U_I32);
			this.a.op(Instruction.I64_ADD);
			this.a.memorySize().op(Instruction.I64_EXTEND_U_I32).i64(16).op(Instruction.I64_SHL);
			this.a.op(Instruction.I64_EQ).ifVoid();
			// no byte of the memory follows them to write the quote to: a framed copy
			this.a.get(this.size).i32(2).op(Instruction.I32_ADD).arrayNewDefault(BYTES).set(this.data);
			frame(this.data, this.size);
			this.a.i32(0).set(this.k);
			this.a.block("copied");
			this.a.loop("copy");
			this.a.get(this.k).get(this.size).op(Instruction.I32_GE_U).brIf("copied");
			this.a.get(this.data).get(this.k).i32(1).op(Instruction.I32_ADD);
			this.a.get(this.lo).get(this.k).op(Instruction.I32_ADD).load8u().arraySet(BYTES);
			inc(this.k, 1);
			this.a.br("copy");
			this.a.end();
			this.a.end();
			this.a.i32(1).set(this.lo);
			this.a.get(this.size).i32(1).op(Instruction.I32_ADD).set(this.end);
			this.a.br("array");
			this.a.end();
			this.a.get(this.lo).get(this.size).op(Instruction.I32_ADD).set(this.end);
			this.a.get(this.end).load8u().set(this.saved);
			this.a.get(this.end).i32(QUOTE).store8();
			this.a.br("memory");
			this.a.end();
			// a string: its bytes between the frame's quotes (a symbol shares the struct
			// unframed)
			WasmEmitHelper.emitCharvecToStrLocal(this.a.w, this.document, this.flags.charvecPossible());
			this.a.get(this.document).refTest(STRING).ifVoid();
			this.a.get(this.document).refCast(STRING).structGet(STRING, 2).refCast(BYTES).set(this.data);
			this.a.get(this.data).i32(0).arrayGetU(BYTES).i32(QUOTE).op(Instruction.I32_EQ).ifVoid();
			this.a.i32(1).set(this.lo);
			this.a.get(this.data).arrayLen().i32(1).op(Instruction.I32_SUB).set(this.end);
			this.a.br("array");
			this.a.end();
			this.a.end();
			// an octet vector: a framed copy
			this.a.get(this.document).refTest(OCTETS).ifVoid();
			this.a.get(this.document).refCast(OCTETS).arrayLen().set(this.size);
			this.a.get(this.size).i32(2).op(Instruction.I32_ADD).arrayNewDefault(BYTES).set(this.data);
			frame(this.data, this.size);
			this.a.get(this.data).i32(1).get(this.document).refCast(OCTETS).i32(0).get(this.size);
			this.a.arrayCopy(BYTES, OCTETS);
			this.a.i32(1).set(this.lo);
			this.a.get(this.size).i32(1).op(Instruction.I32_ADD).set(this.end);
			this.a.br("array");
			this.a.end();
			fail(ERR_NOT_DOCUMENT, "scanned");
		}

		// The quotes at either end of an array of n bytes and two.
		private void frame(int array, int n) {
			this.a.get(array).i32(0).i32(QUOTE).arraySet(BYTES);
			this.a.get(array).get(n).i32(1).op(Instruction.I32_ADD).i32(QUOTE).arraySet(BYTES);
		}

		private void fail(int code) {
			fail(code, "done");
		}

		private void fail(int code, String label) {
			this.a.i32(code).i31().set(this.result).br(label);
		}

		private void inc(int local, int delta) {
			this.a.get(local).i32(delta).op(Instruction.I32_ADD).set(local);
		}

		// The byte at the local's position plus an offset.
		private void byteAt(boolean memory, int local, int offset) {
			if (!memory) {
				this.a.get(this.data);
			}
			this.a.get(local);
			if (offset != 0) {
				this.a.i32(offset).op(Instruction.I32_ADD);
			}
			if (memory) {
				this.a.load8u();
			}
			else {
				this.a.arrayGetU(BYTES);
			}
		}

		// --- the scan: json.lisp's %json-locate ----------------------------------

		private void emitScan(boolean memory) {
			this.a.get(this.lo).set(this.p);
			this.a.i32(0).set(this.depth);
			this.a.i32(0).set(this.obj);
			this.a.i32(0).set(this.on);
			this.a.i32(-1).set(this.focus);
			this.a.i32(-1).set(this.tdepth);
			this.a.i32(-1).set(this.commaDepth);
			this.a.i32(0).set(this.index);
			this.a.i32(THE_VALUE).i32(ON_THE_WAY).get(this.plen).op(Instruction.I32_EQZ).op(Instruction.SELECT);
			this.a.set(this.match);
			this.a.i32(0).set(this.found);
			this.a.i32(-1).set(this.tcount);
			this.a.i32(-1).set(this.mcomma);
			this.a.i32(-1).set(this.lastComma);
			this.a.i32(16).arrayNewDefault(BYTES).set(this.kinds);
			// One value per round, a key before it in an object.
			this.a.loop("main");
			this.a.block("key");
			this.a.block("after");
			emitValue(memory);
			this.a.end();
			emitAfter(memory);
			this.a.end();
			emitKey(memory);
			this.a.end();
			this.a.op(Instruction.UNREACHABLE);
		}

		// p past the blanks; b the byte it stopped at (the quote past the end there).
		private void skipBlanks(boolean memory) {
			byteAt(memory, this.p, 0);
			this.a.tee(this.b).i32(33).op(Instruction.I32_LT_U).ifVoid();
			this.a.block("blank-done");
			this.a.loop("blank");
			this.a.i64(1).get(this.b).op(Instruction.I64_EXTEND_U_I32).op(Instruction.I64_SHL);
			this.a.i64(WasmJsonRuntimeBuilder.WHITESPACE).op(Instruction.I64_AND).op(Instruction.I64_EQZ);
			this.a.brIf("blank-done");
			inc(this.p, 1);
			byteAt(memory, this.p, 0);
			this.a.tee(this.b).i32(33).op(Instruction.I32_LT_U).brIf("blank");
			this.a.end();
			this.a.end();
			this.a.end();
		}

		// A value: what it is, p past it, and the branch to what follows it.
		private void emitValue(boolean memory) {
			skipBlanks(memory);
			// an element of the container the scan watches
			this.a.get(this.depth).get(this.focus).op(Instruction.I32_EQ).ifVoid();
			this.a.get(this.obj).op(Instruction.I32_EQZ).ifVoid();
			this.a.get(this.depth).get(this.tdepth).op(Instruction.I32_EQ).ifVoid();
			inc(this.tcount, 1);
			this.a.elseArm();
			segment();
			this.a.refTest(I31).ifVoid();
			this.a.get(this.seg).unI31().get(this.index).op(Instruction.I32_EQ).ifVoid();
			matched(this.p);
			this.a.end();
			this.a.end();
			inc(this.index, 1);
			this.a.end();
			this.a.end();
			this.a.end();
			// the value itself starts here
			this.a.get(this.match).ifVoid();
			this.a.get(this.match).i32(THE_VALUE).op(Instruction.I32_EQ).ifVoid();
			this.a.i32(1).set(this.found);
			this.a.get(this.p).set(this.tstart);
			this.a.i32(-1).set(this.tcount);
			this.a.end();
			this.a.end();
			this.a.block("number");
			this.a.block("null");
			this.a.block("false");
			this.a.block("true");
			this.a.block("array");
			this.a.block("object");
			this.a.block("open-quote");
			this.a.block("bad");
			Map<Integer, String> dispatch = WasmJsonRuntimeBuilder.cases("number", '-', '0', '1', '2', '3', '4', '5',
					'6', '7', '8', '9');
			dispatch.put(QUOTE, "open-quote");
			dispatch.put((int) '{', "object");
			dispatch.put((int) '[', "array");
			dispatch.put((int) 't', "true");
			dispatch.put((int) 'f', "false");
			dispatch.put((int) 'n', "null");
			this.a.get(this.b).brTable("bad", dispatch, '{' + 1);
			this.a.end();
			fail(WasmJsonRuntimeBuilder.ERR_UNEXPECTED_CHARACTER);
			this.a.end();
			// the quote past the end, which only the end of the input reads as one
			this.a.get(this.p).get(this.end).op(Instruction.I32_GE_U).ifVoid();
			fail(WasmJsonRuntimeBuilder.ERR_END);
			this.a.end();
			scanString(memory);
			this.a.br("after");
			this.a.end();
			emitOpen(memory, true);
			this.a.end();
			emitOpen(memory, false);
			this.a.end();
			literal(memory, "true");
			this.a.br("after");
			this.a.end();
			literal(memory, "false");
			this.a.br("after");
			this.a.end();
			literal(memory, "null");
			this.a.br("after");
			this.a.end();
			emitNumber(memory);
		}

		// seg = the segment the container the scan watches compares with, left on the
		// stack too.
		private void segment() {
			this.a.get(this.segs).get(this.on).i32(1).op(Instruction.I32_SUB).arrayGet(ARR).tee(this.seg);
		}

		// A member of the container the scan watches answers its segment: the next
		// container on the way, or the value, the member starting at the local.
		private void matched(int start) {
			this.a.get(this.on).get(this.plen).op(Instruction.I32_EQ).ifVoid();
			this.a.i32(THE_VALUE).set(this.match);
			this.a.get(start).set(this.mstart);
			this.a.get(this.lastComma).set(this.mcomma);
			this.a.elseArm();
			this.a.i32(ON_THE_WAY).set(this.match);
			this.a.end();
			// a later one of a repeated key replaces what an earlier one led to
			this.a.i32(0).set(this.found);
		}

		// At { or [: the empty container, or the container open and the branch to its
		// first key or value.
		private void emitOpen(boolean memory, boolean object) {
			this.a.get(this.p).set(this.q);
			inc(this.p, 1);
			skipBlanks(memory);
			this.a.get(this.p).get(this.end).op(Instruction.I32_GE_U).ifVoid();
			fail(object ? WasmJsonRuntimeBuilder.ERR_UNTERMINATED_OBJECT
					: WasmJsonRuntimeBuilder.ERR_UNTERMINATED_ARRAY);
			this.a.end();
			this.a.get(this.b).i32(object ? '}' : ']').op(Instruction.I32_EQ).ifVoid();
			inc(this.p, 1);
			this.a.get(this.match).i32(THE_VALUE).op(Instruction.I32_EQ).ifVoid();
			this.a.i32(0).set(this.tcount);
			this.a.end();
			this.a.br("after");
			this.a.end();
			// the enclosing container's kind kept, the stack grown when full
			this.a.get(this.depth).get(this.kinds).arrayLen().op(Instruction.I32_EQ).ifVoid();
			this.a.get(this.depth).i32(1).op(Instruction.I32_SHL).arrayNewDefault(BYTES).set(this.grown);
			this.a.get(this.grown).i32(0).get(this.kinds).i32(0).get(this.depth).arrayCopy(BYTES, BYTES);
			this.a.get(this.grown).set(this.kinds);
			this.a.end();
			this.a.get(this.kinds).get(this.depth).get(this.obj).arraySet(BYTES);
			inc(this.depth, 1);
			this.a.i32(object ? 1 : 0).set(this.obj);
			this.a.get(this.match).ifVoid();
			this.a.get(this.match).i32(THE_VALUE).op(Instruction.I32_EQ).ifVoid();
			this.a.get(this.depth).tee(this.tdepth).set(this.focus);
			this.a.i32(0).set(this.tcount);
			this.a.elseArm();
			this.a.get(this.depth).tee(this.on).set(this.focus);
			this.a.i32(0).set(this.index);
			this.a.get(this.on).get(this.plen).op(Instruction.I32_EQ).ifVoid();
			this.a.get(this.depth).set(this.commaDepth);
			this.a.get(this.q).set(this.copen);
			this.a.i32(-1).set(this.lastComma);
			this.a.end();
			this.a.end();
			this.a.i32(0).set(this.match);
			this.a.end();
			this.a.br(object ? "key" : "main");
		}

		// After a value: its separator, or the containers it closes.
		private void emitAfter(boolean memory) {
			this.a.get(this.match).ifVoid();
			this.a.get(this.match).i32(THE_VALUE).op(Instruction.I32_EQ).ifVoid();
			this.a.get(this.p).set(this.tend);
			this.a.end();
			this.a.i32(0).set(this.match);
			this.a.end();
			this.a.loop("close");
			skipBlanks(memory);
			this.a.get(this.depth).op(Instruction.I32_EQZ).ifVoid();
			this.a.get(this.p).get(this.end).op(Instruction.I32_LT_U).ifVoid();
			fail(WasmJsonRuntimeBuilder.ERR_TRAILING);
			this.a.end();
			emitFinish(memory);
			this.a.end();
			this.a.get(this.b).i32(',').op(Instruction.I32_EQ).ifVoid();
			this.a.get(this.depth).get(this.commaDepth).op(Instruction.I32_EQ).ifVoid();
			this.a.get(this.p).set(this.lastComma);
			this.a.end();
			inc(this.p, 1);
			this.a.get(this.obj).brIf("key");
			this.a.br("main");
			this.a.end();
			// the end of the input reads as the quote past it, which no arm below takes
			this.a.get(this.obj).ifVoid();
			this.a.get(this.b).i32('}').op(Instruction.I32_NE).ifVoid();
			separatorFailure(WasmJsonRuntimeBuilder.ERR_UNTERMINATED_OBJECT,
					WasmJsonRuntimeBuilder.ERR_OBJECT_SEPARATOR);
			this.a.end();
			this.a.elseArm();
			this.a.get(this.b).i32(']').op(Instruction.I32_NE).ifVoid();
			separatorFailure(WasmJsonRuntimeBuilder.ERR_UNTERMINATED_ARRAY, WasmJsonRuntimeBuilder.ERR_ARRAY_SEPARATOR);
			this.a.end();
			this.a.end();
			inc(this.p, 1);
			this.a.get(this.depth).get(this.focus).op(Instruction.I32_EQ).ifVoid();
			this.a.get(this.depth).get(this.tdepth).op(Instruction.I32_EQ).ifVoid();
			// the value's container closed: its end, and the watch back on the way
			this.a.get(this.p).set(this.tend);
			this.a.i32(-1).set(this.tdepth);
			this.a.get(this.on).set(this.focus);
			this.a.elseArm();
			// a container on the way closed: the watch on the one around it
			this.a.get(this.on).i32(1).op(Instruction.I32_SUB).tee(this.on).set(this.focus);
			this.a.i32(-1).set(this.commaDepth);
			this.a.get(this.on).ifVoid();
			this.a.get(this.kinds).get(this.depth).i32(1).op(Instruction.I32_SUB).arrayGetU(BYTES);
			this.a.op(Instruction.I32_EQZ).ifVoid();
			// an array on the way matched its index: none after it can
			segment();
			this.a.op(Instruction.DROP);
			this.a.get(this.seg).unI31().i32(1).op(Instruction.I32_ADD).set(this.index);
			this.a.end();
			this.a.end();
			this.a.end();
			this.a.end();
			inc(this.depth, -1);
			this.a.get(this.kinds).get(this.depth).arrayGetU(BYTES).set(this.obj);
			this.a.br("close");
			this.a.end();
		}

		// No separator where one belongs: the input ended, or something else is there.
		private void separatorFailure(int unterminated, int separator) {
			this.a.get(this.p).get(this.end).op(Instruction.I32_GE_U).ifVoid();
			fail(unterminated);
			this.a.end();
			fail(separator);
		}

		// A key and its colon, then the branch to its value.
		private void emitKey(boolean memory) {
			skipBlanks(memory);
			this.a.get(this.b).i32(QUOTE).op(Instruction.I32_NE);
			this.a.get(this.p).get(this.end).op(Instruction.I32_GE_U).op(Instruction.I32_OR).ifVoid();
			fail(WasmJsonRuntimeBuilder.ERR_OBJECT_KEY);
			this.a.end();
			this.a.get(this.p).set(this.kstart);
			scanString(memory);
			this.a.get(this.depth).get(this.focus).op(Instruction.I32_EQ).ifVoid();
			this.a.get(this.depth).get(this.tdepth).op(Instruction.I32_EQ).ifVoid();
			inc(this.tcount, 1);
			this.a.elseArm();
			segment();
			this.a.refTest(STRING).ifVoid();
			compareKey(memory);
			this.a.get(this.same).ifVoid();
			matched(this.kstart);
			this.a.end();
			this.a.end();
			this.a.end();
			this.a.end();
			// the colon, mostly the very next byte: then no blank is looked for
			byteAt(memory, this.p, 0);
			this.a.i32(':').op(Instruction.I32_NE).ifVoid();
			skipBlanks(memory);
			// the end of the input reads as the quote past it, no colon either
			this.a.get(this.b).i32(':').op(Instruction.I32_NE).ifVoid();
			fail(WasmJsonRuntimeBuilder.ERR_OBJECT_COLON);
			this.a.end();
			this.a.end();
			inc(this.p, 1);
			this.a.br("main");
		}

		// At a string's opening quote: p past its closing quote. The position moves
		// first, so a byte of the string is six instructions over memory, seven over an
		// array.
		private void scanString(boolean memory) {
			this.a.block("string-done");
			this.a.loop("more");
			this.a.block("backslash");
			this.a.block("quote");
			this.a.loop("scan");
			nextByte(memory);
			Map<Integer, String> stops = WasmJsonRuntimeBuilder.cases("quote", QUOTE);
			stops.put(BACKSLASH, "backslash");
			this.a.brTable("scan", stops, BACKSLASH + 1);
			this.a.end();
			this.a.end();
			// at the closing quote: the one past the end when nothing closed the string
			this.a.get(this.p).get(this.end).op(Instruction.I32_GE_U).ifVoid();
			fail(WasmJsonRuntimeBuilder.ERR_UNTERMINATED_STRING);
			this.a.end();
			inc(this.p, 1);
			this.a.br("string-done");
			this.a.end();
			// at the backslash: past its escape, and back one for the round that moves
			// first
			emitEscape(memory);
			inc(this.p, -1);
			this.a.br("more");
			this.a.end();
			this.a.end();
		}

		// p moved to the next byte, which is left on the stack.
		private void nextByte(boolean memory) {
			if (!memory) {
				this.a.get(this.data);
			}
			this.a.get(this.p).i32(1).op(Instruction.I32_ADD).tee(this.p);
			if (memory) {
				this.a.load8u();
			}
			else {
				this.a.arrayGetU(BYTES);
			}
		}

		// At a backslash: p past its escape.
		private void emitEscape(boolean memory) {
			this.a.get(this.p).i32(1).op(Instruction.I32_ADD).get(this.end).op(Instruction.I32_GE_U).ifVoid();
			fail(WasmJsonRuntimeBuilder.ERR_UNTERMINATED_ESCAPE);
			this.a.end();
			byteAt(memory, this.p, 1);
			this.a.set(this.b);
			this.a.block("escaped");
			this.a.block("unicode");
			this.a.block("simple");
			this.a.block("invalid");
			Map<Integer, String> escapes = WasmJsonRuntimeBuilder.cases("simple", QUOTE, BACKSLASH, '/', 'b', 'f', 'n',
					'r', 't');
			escapes.put((int) 'u', "unicode");
			this.a.get(this.b).brTable("invalid", escapes, 'u' + 1);
			this.a.end();
			fail(WasmJsonRuntimeBuilder.ERR_INVALID_ESCAPE);
			this.a.end();
			inc(this.p, 2);
			this.a.br("escaped");
			this.a.end();
			// \\uXXXX, and a second one a high surrogate's escape is followed by
			this.a.get(this.p).i32(2).op(Instruction.I32_ADD).set(this.q);
			hex4(memory);
			inc(this.p, 6);
			surrogatePair(memory, this.hv, this.p);
			this.a.ifVoid();
			this.a.get(this.p).i32(2).op(Instruction.I32_ADD).set(this.q);
			hex4(memory);
			inc(this.p, 6);
			this.a.end();
			this.a.end();
		}

		// Whether the code point in the local is a high surrogate whose escape, ending at
		// the position local, is followed by a second \\u escape: an i32 on the stack.
		private void surrogatePair(boolean memory, int value, int position) {
			this.a.get(value).i32(0xD800).op(Instruction.I32_GE_U);
			this.a.get(value).i32(0xDBFF).op(Instruction.I32_LE_U).op(Instruction.I32_AND);
			this.a.get(position).i32(6).op(Instruction.I32_ADD).get(this.end).op(Instruction.I32_LE_U);
			this.a.op(Instruction.I32_AND).ifI32();
			byteAt(memory, position, 0);
			this.a.i32(BACKSLASH).op(Instruction.I32_EQ);
			byteAt(memory, position, 1);
			this.a.i32('u').op(Instruction.I32_EQ).op(Instruction.I32_AND);
			this.a.elseArm();
			this.a.i32(0);
			this.a.end();
		}

		// hv = the four hex digits at q, or json-parse's error for them: fewer than four
		// CHARACTERS left is unterminated, which a multi-byte one among the four bytes
		// can
		// make it.
		private void hex4(boolean memory) {
			this.a.get(this.q).i32(4).op(Instruction.I32_ADD).get(this.end).op(Instruction.I32_GT_U).ifVoid();
			fail(WasmJsonRuntimeBuilder.ERR_UNTERMINATED_UNICODE);
			this.a.end();
			this.a.i32(0).set(this.hv);
			this.a.i32(0).set(this.j);
			this.a.loop("hex");
			this.a.get(this.q).get(this.j).op(Instruction.I32_ADD).set(this.at);
			byteAt(memory, this.at, 0);
			this.a.set(this.b);
			this.a.get(this.b).i32('0').op(Instruction.I32_SUB).tee(this.digit).i32(10).op(Instruction.I32_LT_U);
			this.a.ifVoid();
			this.a.get(this.hv).i32(4).op(Instruction.I32_SHL).get(this.digit).op(Instruction.I32_OR).set(this.hv);
			this.a.elseArm();
			this.a.get(this.b).i32(32).op(Instruction.I32_OR).i32('a').op(Instruction.I32_SUB).tee(this.digit);
			this.a.i32(6).op(Instruction.I32_LT_U).ifVoid();
			this.a.get(this.hv).i32(4).op(Instruction.I32_SHL);
			this.a.get(this.digit).i32(10).op(Instruction.I32_ADD).op(Instruction.I32_OR).set(this.hv);
			this.a.elseArm();
			hexFailure(memory);
			this.a.end();
			this.a.end();
			inc(this.j, 1);
			this.a.get(this.j).i32(4).op(Instruction.I32_LT_U).brIf("hex");
			this.a.end();
		}

		private void hexFailure(boolean memory) {
			this.a.i32(0).set(this.count);
			this.a.get(this.q).set(this.at);
			this.a.block("counted");
			this.a.loop("lead");
			this.a.get(this.at).get(this.end).op(Instruction.I32_GE_U).brIf("counted");
			this.a.get(this.count).i32(4).op(Instruction.I32_GE_U).brIf("counted");
			byteAt(memory, this.at, 0);
			this.a.i32(0xC0).op(Instruction.I32_AND).i32(0x80).op(Instruction.I32_NE).ifVoid();
			inc(this.count, 1);
			this.a.end();
			inc(this.at, 1);
			this.a.br("lead");
			this.a.end();
			this.a.end();
			this.a.get(this.count).i32(4).op(Instruction.I32_LT_U).ifVoid();
			fail(WasmJsonRuntimeBuilder.ERR_UNTERMINATED_UNICODE);
			this.a.end();
			fail(WasmJsonRuntimeBuilder.ERR_INVALID_HEX);
		}

		// At - or a digit: p past the number.
		private void emitNumber(boolean memory) {
			this.a.get(this.b).i32('-').op(Instruction.I32_EQ).get(this.p).op(Instruction.I32_ADD).set(this.p);
			digits(memory);
			byteAt(memory, this.p, 0);
			this.a.i32('.').op(Instruction.I32_EQ).ifVoid();
			inc(this.p, 1);
			digits(memory);
			this.a.end();
			byteAt(memory, this.p, 0);
			this.a.i32(32).op(Instruction.I32_OR).i32('e').op(Instruction.I32_EQ).ifVoid();
			inc(this.p, 1);
			byteAt(memory, this.p, 0);
			this.a.set(this.b);
			this.a.get(this.b).i32('+').op(Instruction.I32_EQ).get(this.b).i32('-').op(Instruction.I32_EQ);
			this.a.op(Instruction.I32_OR).get(this.p).op(Instruction.I32_ADD).set(this.p);
			digits(memory);
			this.a.end();
			this.a.br("after");
		}

		// p from a digit past the digits from it, or the error when there is none: the
		// position moves first, ten instructions a digit over memory.
		private void digits(boolean memory) {
			byteAt(memory, this.p, 0);
			this.a.i32('0').op(Instruction.I32_SUB).i32(10).op(Instruction.I32_GE_U).ifVoid();
			fail(WasmJsonRuntimeBuilder.ERR_INVALID_NUMBER);
			this.a.end();
			this.a.loop("digits");
			nextByte(memory);
			this.a.i32('0').op(Instruction.I32_SUB).i32(10).op(Instruction.I32_LT_U).brIf("digits");
			this.a.end();
		}

		// The rest of a literal whose first byte matched: p past it, or the error.
		private void literal(boolean memory, String word) {
			this.a.get(this.p).i32(word.length()).op(Instruction.I32_ADD).get(this.end).op(Instruction.I32_GT_U);
			this.a.ifVoid();
			fail(WasmJsonRuntimeBuilder.ERR_INVALID_LITERAL);
			this.a.end();
			for (int i = 1; i < word.length(); i++) {
				byteAt(memory, this.p, i);
				this.a.i32(word.charAt(i)).op(Instruction.I32_NE).ifVoid();
				fail(WasmJsonRuntimeBuilder.ERR_INVALID_LITERAL);
				this.a.end();
			}
			inc(this.p, word.length());
		}

		// same = whether the key from kstart's quote to the one before p spells the
		// string in seg, its escapes decoded to UTF-8 as json-parse decodes them.
		private void compareKey(boolean memory) {
			this.a.get(this.seg).refCast(STRING).structGet(STRING, 2).refCast(BYTES).set(this.segBytes);
			this.a.get(this.segBytes).arrayLen().i32(2).op(Instruction.I32_SUB).set(this.segLen);
			this.a.i32(1).set(this.same);
			this.a.i32(0).set(this.k);
			// whether the key holds an escape: a backslash among its bytes
			this.a.i32(0).set(this.escaped);
			this.a.get(this.kstart).set(this.q);
			this.a.block("searched");
			this.a.loop("search");
			this.a.get(this.q).i32(1).op(Instruction.I32_ADD).tee(this.q);
			this.a.get(this.p).i32(1).op(Instruction.I32_SUB).op(Instruction.I32_GE_U).brIf("searched");
			byteAt(memory, this.q, 0);
			this.a.i32(BACKSLASH).op(Instruction.I32_NE).brIf("search");
			this.a.i32(1).set(this.escaped);
			this.a.end();
			this.a.end();
			this.a.get(this.kstart).i32(1).op(Instruction.I32_ADD).set(this.q);
			this.a.get(this.escaped).ifVoid();
			compareEscaped(memory);
			this.a.elseArm();
			// as they are: the lengths, then the bytes
			this.a.get(this.p).i32(1).op(Instruction.I32_SUB).get(this.q).op(Instruction.I32_SUB);
			this.a.get(this.segLen).op(Instruction.I32_NE).ifVoid();
			this.a.i32(0).set(this.same);
			this.a.end();
			this.a.block("compared");
			this.a.loop("compare");
			this.a.get(this.same).op(Instruction.I32_EQZ).brIf("compared");
			this.a.get(this.k).get(this.segLen).op(Instruction.I32_GE_U).brIf("compared");
			byteAt(memory, this.q, 0);
			this.a.get(this.segBytes).get(this.k).i32(1).op(Instruction.I32_ADD).arrayGetU(BYTES);
			this.a.op(Instruction.I32_NE).ifVoid();
			this.a.i32(0).set(this.same);
			this.a.end();
			inc(this.q, 1);
			inc(this.k, 1);
			this.a.br("compare");
			this.a.end();
			this.a.end();
			this.a.end();
		}

		// compareKey for a key with an escape: its bytes decoded one by one.
		private void compareEscaped(boolean memory) {
			this.a.block("walked");
			this.a.loop("walk");
			this.a.get(this.q).get(this.p).i32(1).op(Instruction.I32_SUB).op(Instruction.I32_GE_U).brIf("walked");
			byteAt(memory, this.q, 0);
			this.a.tee(this.v).i32(BACKSLASH).op(Instruction.I32_NE).ifVoid();
			compareByte();
			inc(this.q, 1);
			this.a.elseArm();
			byteAt(memory, this.q, 1);
			this.a.tee(this.v).i32('u').op(Instruction.I32_EQ).ifVoid();
			hexValue(memory, this.cp);
			inc(this.q, 6);
			surrogatePair(memory, this.cp, this.q);
			this.a.ifVoid();
			hexValue(memory, this.hv);
			inc(this.q, 6);
			this.a.get(this.hv).i32(0xDC00).op(Instruction.I32_GE_U);
			this.a.get(this.hv).i32(0xDFFF).op(Instruction.I32_LE_U).op(Instruction.I32_AND).ifVoid();
			this.a.get(this.cp).i32(0xD800).op(Instruction.I32_SUB).i32(10).op(Instruction.I32_SHL);
			this.a.get(this.hv).i32(0xDC00).op(Instruction.I32_SUB).op(Instruction.I32_ADD);
			this.a.i32(0x10000).op(Instruction.I32_ADD).set(this.cp);
			this.a.elseArm();
			compareUtf8();
			this.a.get(this.hv).set(this.cp);
			this.a.end();
			this.a.end();
			compareUtf8();
			this.a.elseArm();
			// the one-byte escapes: b f n r t, and what the others escape
			for (int[] escape : new int[][] { { 'b', 8 }, { 'f', 12 }, { 'n', 10 }, { 'r', 13 }, { 't', 9 } }) {
				this.a.get(this.v).i32(escape[0]).op(Instruction.I32_EQ).ifVoid();
				this.a.i32(escape[1]).set(this.v);
				this.a.end();
			}
			compareByte();
			inc(this.q, 2);
			this.a.end();
			this.a.end();
			this.a.br("walk");
			this.a.end();
			this.a.end();
			this.a.get(this.k).get(this.segLen).op(Instruction.I32_NE).ifVoid();
			this.a.i32(0).set(this.same);
			this.a.end();
		}

		// The local = the four hex digits at q + 2, which the scan has checked.
		private void hexValue(boolean memory, int local) {
			this.a.i32(0).set(local);
			this.a.i32(2).set(this.j);
			this.a.loop("digit");
			this.a.get(this.q).get(this.j).op(Instruction.I32_ADD).set(this.at);
			byteAt(memory, this.at, 0);
			this.a.set(this.b);
			this.a.get(local).i32(4).op(Instruction.I32_SHL);
			this.a.get(this.b).i32('0').op(Instruction.I32_SUB);
			this.a.get(this.b).i32(32).op(Instruction.I32_OR).i32('a' - 10).op(Instruction.I32_SUB);
			this.a.get(this.b).i32('9').op(Instruction.I32_LE_U).op(Instruction.SELECT);
			this.a.op(Instruction.I32_OR).set(local);
			inc(this.j, 1);
			this.a.get(this.j).i32(6).op(Instruction.I32_LT_U).brIf("digit");
			this.a.end();
		}

		// The byte in v against the segment's next: the next on a match, same 0 else.
		private void compareByte() {
			this.a.get(this.same).ifVoid();
			this.a.get(this.k).get(this.segLen).op(Instruction.I32_LT_U).ifVoid();
			this.a.get(this.segBytes).get(this.k).i32(1).op(Instruction.I32_ADD).arrayGetU(BYTES);
			this.a.get(this.v).op(Instruction.I32_EQ).ifVoid();
			inc(this.k, 1);
			this.a.elseArm();
			this.a.i32(0).set(this.same);
			this.a.end();
			this.a.elseArm();
			this.a.i32(0).set(this.same);
			this.a.end();
			this.a.end();
		}

		// cp's UTF-8 bytes against the segment's next, what a string of (code-char cp)
		// holds.
		private void compareUtf8() {
			this.a.get(this.cp).i32(0x80).op(Instruction.I32_LT_U).ifVoid();
			this.a.get(this.cp).set(this.v);
			compareByte();
			this.a.elseArm();
			this.a.get(this.cp).i32(0x800).op(Instruction.I32_LT_U).ifVoid();
			utf8Byte(6, 0xC0);
			utf8Byte(0, 0x80);
			this.a.elseArm();
			this.a.get(this.cp).i32(0x10000).op(Instruction.I32_LT_U).ifVoid();
			utf8Byte(12, 0xE0);
			utf8Byte(6, 0x80);
			utf8Byte(0, 0x80);
			this.a.elseArm();
			utf8Byte(18, 0xF0);
			utf8Byte(12, 0x80);
			utf8Byte(6, 0x80);
			utf8Byte(0, 0x80);
			this.a.end();
			this.a.end();
			this.a.end();
		}

		// v = lead | (cp >> shift), its low six bits for a continuation byte (lead
		// 0x80), compared.
		private void utf8Byte(int shift, int lead) {
			this.a.get(this.cp);
			if (shift > 0) {
				this.a.i32(shift).op(Instruction.I32_SHR_U);
			}
			if (lead == 0x80) {
				this.a.i32(0x3F).op(Instruction.I32_AND);
			}
			this.a.i32(lead).op(Instruction.I32_OR).set(this.v);
			compareByte();
		}

		// The end of the document: what the scan found, as the mode asks.
		private void emitFinish(boolean memory) {
			this.a.get(this.found).op(Instruction.I32_EQZ).ifVoid();
			this.a.refNull().set(this.result);
			this.a.br("done");
			this.a.end();
			this.a.get(this.mode).unI31().ifVoid();
			// the value's bytes, a string
			this.a.get(this.tend).get(this.tstart).op(Instruction.I32_SUB).set(this.size);
			this.a.get(this.size).i32(2).op(Instruction.I32_ADD).tee(this.len).arrayNewDefault(BYTES).set(this.out);
			frame(this.out, this.size);
			if (memory) {
				this.a.i32(0).set(this.k);
				this.a.block("copied");
				this.a.loop("copy");
				this.a.get(this.k).get(this.size).op(Instruction.I32_GE_U).brIf("copied");
				this.a.get(this.out).get(this.k).i32(1).op(Instruction.I32_ADD);
				this.a.get(this.tstart).get(this.k).op(Instruction.I32_ADD).load8u().arraySet(BYTES);
				inc(this.k, 1);
				this.a.br("copy");
				this.a.end();
				this.a.end();
			}
			else {
				this.a.get(this.out).i32(1).get(this.data).get(this.tstart).get(this.size).arrayCopy(BYTES, BYTES);
			}
			WasmStringRuntimeBuilder.emitRuntimeString(this.a.w, this.len, this.out, this.id);
			this.a.set(this.result);
			this.a.br("done");
			this.a.end();
			// What deleting the member takes, from the first byte: the comma before it,
			// or
			// the one after it to the next member, or for the only one all inside its
			// container. A position is an address in memory, past 2^31 too: -1 is none.
			this.a.i32(-1).set(this.cs);
			this.a.i32(-1).set(this.ce);
			this.a.get(this.plen).ifVoid();
			this.a.get(this.mcomma).i32(-1).op(Instruction.I32_NE).ifVoid();
			this.a.get(this.mcomma).set(this.cs);
			this.a.get(this.tend).set(this.p);
			this.a.elseArm();
			this.a.get(this.tend).set(this.p);
			skipBlanks(memory);
			this.a.get(this.b).i32(',').op(Instruction.I32_EQ).ifVoid();
			inc(this.p, 1);
			skipBlanks(memory);
			this.a.get(this.mstart).set(this.cs);
			this.a.elseArm();
			this.a.get(this.copen).i32(1).op(Instruction.I32_ADD).set(this.cs);
			this.a.end();
			this.a.end();
			this.a.get(this.cs).get(this.lo).op(Instruction.I32_SUB).set(this.cs);
			this.a.get(this.p).get(this.lo).op(Instruction.I32_SUB).set(this.ce);
			this.a.end();
			// (start end count cut-start cut-end)
			integerOrNil(this.ce);
			this.a.refNull();
			WasmEmitHelper.emitNewCons(this.a.w, this.flags.identityHash());
			this.a.set(this.x);
			integerOrNil(this.cs);
			this.a.get(this.x);
			WasmEmitHelper.emitNewCons(this.a.w, this.flags.identityHash());
			this.a.set(this.x);
			integerOrNil(this.tcount);
			this.a.get(this.x);
			WasmEmitHelper.emitNewCons(this.a.w, this.flags.identityHash());
			this.a.set(this.x);
			for (int local : new int[] { this.tend, this.tstart }) {
				this.a.get(local).get(this.lo).op(Instruction.I32_SUB);
				this.a.op(Instruction.I64_EXTEND_U_I32).call(WasmLispCompiler.FUNC_INT_NEW);
				this.a.get(this.x);
				WasmEmitHelper.emitNewCons(this.a.w, this.flags.identityHash());
				this.a.set(this.x);
			}
			this.a.get(this.x).set(this.result);
			this.a.br("done");
		}

		// The local's integer, or NIL for -1.
		private void integerOrNil(int local) {
			this.a.get(local).i32(-1).op(Instruction.I32_EQ).ifEq();
			this.a.refNull();
			this.a.elseArm();
			this.a.get(local).op(Instruction.I64_EXTEND_U_I32).call(WasmLispCompiler.FUNC_INT_NEW);
			this.a.end();
		}

	}

}
