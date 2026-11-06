package am.ik.rontolisp.codegen.wasm;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import am.ik.wasm.Instruction;
import am.ik.wasm.Type;
import am.ik.wasm.WasmWriter;

/**
 * Builds the wasm-GC JSON codec: {@code rontolisp::%json-decode} and
 * {@code rontolisp::%json-encode}, the native halves of {@code rontolisp:json-parse} and
 * {@code rontolisp:json-stringify} on this backend ({@code .kb/json.md}, "The wasm-GC
 * codec").
 *
 * <p>
 * Both walk the bytes of a {@code $str_bytes} array in one loop, without recursion: the
 * decoder keeps the values of the containers it is inside on one stack, a container's
 * start under a marker, and builds each container when it closes; the encoder keeps a
 * frame per container it is inside, the innermost in locals. A run of a string without an
 * escape is one {@code array.copy}. What they build is what {@code json.lisp}'s walk
 * builds -- the same hash tables, vectors, integers and floats, and its error for each
 * malformed input -- except that every string is an immutable string, an object's keys
 * are pooled within one parse as jzon pools them, and a container nests to any depth.
 *
 * <p>
 * The loops are written for an interpreting engine, where a {@code block} costs what an
 * {@code i32.add} does: a byte of a string runs no block -- the position moves first and
 * a {@code br_table} sends a plain byte back to its loop.
 *
 * <p>
 * The decoder answers {@code (value)} or the i31 code of the error json.lisp signals for
 * the input ({@code %json-native-fail} spells it). The encoder answers the JSON text, or
 * the i31 {@code 0} for an improper list; a value it has no arm for -- a float, a ratio,
 * a symbol, a character, an instance, a vector of another representation -- is the text
 * its {@code other} function answers for it, and a key that is no string is coerced by
 * its {@code key-name} function first.
 */
final class WasmJsonRuntimeBuilder {

	/** {@code _json_decode (string, null-symbol) -> (value) or error code}. */
	static final int DECODE = 0;

	/** {@code _json_encode (value, null-symbol, other, key-name) -> text or 0}. */
	static final int ENCODE = 1;

	/** {@code _json_grow (bytes, at-least) -> bytes}: a larger copy of a buffer. */
	static final int GROW = 2;

	/**
	 * {@code _json_spill (chunks, chunk, used, at-least) -> chunk}: the encoder's full
	 * chunk kept, and the next one.
	 */
	static final int SPILL = 3;

	static final int FUNC_COUNT = 4;

	// The decoder's error codes, in json.lisp's %json-native-fail order.
	static final int ERR_NOT_STRING = 0;

	static final int ERR_END = 1;

	static final int ERR_TRAILING = 2;

	static final int ERR_UNTERMINATED_STRING = 3;

	static final int ERR_UNTERMINATED_ESCAPE = 4;

	static final int ERR_INVALID_ESCAPE = 5;

	static final int ERR_UNTERMINATED_UNICODE = 6;

	static final int ERR_INVALID_HEX = 7;

	static final int ERR_INVALID_NUMBER = 8;

	static final int ERR_INVALID_LITERAL = 9;

	static final int ERR_UNEXPECTED_CHARACTER = 10;

	static final int ERR_UNTERMINATED_ARRAY = 11;

	static final int ERR_ARRAY_SEPARATOR = 12;

	static final int ERR_UNTERMINATED_OBJECT = 13;

	static final int ERR_OBJECT_KEY = 14;

	static final int ERR_OBJECT_COLON = 15;

	static final int ERR_OBJECT_SEPARATOR = 16;

	/** The encoder's one error: a list whose last cdr is no list. */
	static final int ERR_IMPROPER_LIST = 0;

	private static final int BYTES = WasmLispCompiler.TYPE_STR_BYTES;

	private static final int ARR = WasmLispCompiler.TYPE_HASH_BUCKETS;

	private static final int STRING = WasmLispCompiler.TYPE_STRING;

	private static final int CONS = WasmLispCompiler.TYPE_CONS;

	private static final int CELL = WasmLispCompiler.TYPE_CELL;

	private static final int I31 = Type.I31.code();

	private static final int QUOTE = '"';

	private static final int BACKSLASH = '\\';

	// The bytes JSON skips between tokens, as bits of a mask: tab, newline, return and
	// space.
	static final long WHITESPACE = (1L << 9) | (1L << 10) | (1L << 13) | (1L << 32);

	// What a container on the decoder's stack is (the marker's low bits), and what an
	// encoder frame walks.
	private static final int OBJECT = 1;

	private static final int ARRAY = 2;

	private static final int LIST = 3;

	// An encoder frame walking a small table's pairs, where OBJECT walks entries.
	private static final int PAIRS = 4;

	// The key pool's slots, a power of two; each holds a key and its placement hash.
	private static final int POOL = 64;

	private WasmJsonRuntimeBuilder() {
	}

	/**
	 * The module flags the bodies read.
	 *
	 * @param identityHash whether conses and cells carry the identity-hash slot
	 * @param charvecPossible whether a character vector can exist in the module
	 * ({@code Ctx.charvecPossible})
	 * @param equalpTables whether an entry may keep its key as written beside a folded
	 * one
	 * @param tagged whether a hash table's count carries its test in two low bits
	 */
	record Flags(boolean identityHash, boolean charvecPossible, boolean equalpTables, boolean tagged) {
	}

	/**
	 * The signature of the block's function at an offset.
	 * @param i the offset
	 * @return its type index
	 */
	static int typeIndexOf(int i) {
		return switch (i) {
			case DECODE, GROW -> WasmLispCompiler.TYPE_CALLABLE_BASE + 1;
			case ENCODE, SPILL -> WasmLispCompiler.TYPE_CALLABLE_BASE + 3;
			default -> throw new IllegalArgumentException("no JSON runtime function " + i);
		};
	}

	/**
	 * The body of the block's function at an offset.
	 * @param i the offset
	 * @param base the index of the block's first function
	 * @param flags the module's flags
	 * @return the encoded body
	 */
	static byte[] build(int i, int base, Flags flags) {
		return switch (i) {
			case DECODE -> buildDecode(base, flags);
			case ENCODE -> buildEncode(base, flags);
			case GROW -> buildGrow();
			case SPILL -> buildSpill();
			default -> throw new IllegalArgumentException("no JSON runtime function " + i);
		};
	}

	// _json_spill (chunks, chunk, used, at-least) -> chunk. chunks is [list, count,
	// total]: the list holds each kept chunk and the fixnum of the bytes it holds, count
	// the list's slots in use and total those bytes. The next chunk holds at least what
	// was asked for and as much as all before it, so the chunks' room stays within twice
	// what they hold and nothing is copied before the text is made.
	private static byte[] buildSpill() {
		Asm a = new Asm(4);
		int state = a.local(Kind.ARR);
		int list = a.local(Kind.ARR);
		int grownList = a.local(Kind.ARR);
		int count = a.local(Kind.I32);
		int total = a.local(Kind.I32);
		int size = a.local(Kind.I32);
		a.get(0).refCast(ARR).set(state);
		a.get(state).i32(0).arrayGet(ARR).refCast(ARR).set(list);
		a.get(state).i32(1).arrayGet(ARR).unI31().set(count);
		a.get(count).i32(2).op(Instruction.I32_ADD).get(list).arrayLen().op(Instruction.I32_GT_U).ifVoid();
		a.get(list).arrayLen().i32(1).op(Instruction.I32_SHL).arrayNewDefault(ARR).set(grownList);
		a.get(grownList).i32(0).get(list).i32(0).get(count).arrayCopy(ARR, ARR);
		a.get(grownList).set(list);
		a.get(state).i32(0).get(list).arraySet(ARR);
		a.end();
		a.get(list).get(count).get(1).arraySet(ARR);
		a.get(list).get(count).i32(1).op(Instruction.I32_ADD).get(2).arraySet(ARR);
		a.get(state).i32(1).get(count).i32(2).op(Instruction.I32_ADD).i31().arraySet(ARR);
		a.get(state).i32(2).arrayGet(ARR).unI31().get(2).unI31().op(Instruction.I32_ADD).set(total);
		a.get(state).i32(2).get(total).i31().arraySet(ARR);
		a.get(total).i32(64).get(total).i32(64).op(Instruction.I32_GT_U).op(Instruction.SELECT).set(size);
		a.get(3).unI31().get(size).get(3).unI31().get(size).op(Instruction.I32_GT_U).op(Instruction.SELECT);
		a.arrayNewDefault(BYTES);
		return a.finish();
	}

	// _json_grow (bytes, at-least) -> bytes: twice the room, or at least the fixnum asked
	// for, with what the buffer held at its front.
	private static byte[] buildGrow() {
		Asm a = new Asm(2);
		int old = a.local(Kind.BYTES);
		int cap = a.local(Kind.I32);
		int grown = a.local(Kind.BYTES);
		a.get(0).refCast(BYTES).set(old);
		a.get(old).arrayLen().i32(1).op(Instruction.I32_SHL).set(cap);
		a.get(cap).get(1).unI31().op(Instruction.I32_LT_U).ifVoid().get(1).unI31().set(cap).end();
		a.get(cap).arrayNewDefault(BYTES).set(grown);
		a.get(grown).i32(0).get(old).i32(0).get(old).arrayLen().arrayCopy(BYTES, BYTES);
		a.get(grown);
		return a.finish();
	}

	static Map<Integer, String> cases(String label, int... bytes) {
		Map<Integer, String> cases = new HashMap<>();
		for (int value : bytes) {
			cases.put(value, label);
		}
		return cases;
	}

	// --- the decoder ---------------------------------------------------------

	private static byte[] buildDecode(int base, Flags flags) {
		Asm a = new Asm(2);
		new Decoder(a, base, flags).emit();
		return a.finish();
	}

	/** The decoder's locals and the code that shares them. */
	private static final class Decoder {

		private final Asm a;

		private final int base;

		private final Flags flags;

		// params
		private final int s = 0;

		private final int nullSymbol = 1;

		// the input
		private final int data;

		private final int end;

		private final int p;

		private final int b;

		// the value stack and the container being read
		private final int vs;

		private final int vt;

		private final int cstart;

		private final int ckind;

		private final int wantKey;

		private final int x;

		private final int y;

		private final int stackGrown;

		// strings
		private final int start;

		private final int building;

		private final int tmp;

		private final int tlen;

		private final int out;

		private final int n;

		private final int len;

		private final int id;

		private final int cp;

		private final int q;

		private final int k;

		private final int hv;

		private final int h;

		private final int digit;

		private final int count;

		private final int at;

		private final int pool;

		private final int cand;

		// numbers
		private final int negative;

		private final int intStart;

		private final int intEnd;

		private final int fracStart;

		private final int fracEnd;

		private final int hasExponent;

		private final int exponentNegative;

		private final int iv;

		private final int e;

		private final int m;

		private final int fractionDigits;

		// containers
		private final int pairs;

		private final int buckets;

		private final int mask;

		private final int size;

		private final int elements;

		private final int record;

		private final int bucketArray;

		private final int order;

		private final int key;

		private final int value;

		private final int index;

		private final int entry;

		Decoder(Asm a, int base, Flags flags) {
			this.a = a;
			this.base = base;
			this.flags = flags;
			this.data = a.local(Kind.BYTES);
			this.end = a.local(Kind.I32);
			this.p = a.local(Kind.I32);
			this.b = a.local(Kind.I32);
			this.vs = a.local(Kind.ARR);
			this.vt = a.local(Kind.I32);
			this.cstart = a.local(Kind.I32);
			this.ckind = a.local(Kind.I32);
			this.wantKey = a.local(Kind.I32);
			this.x = a.local(Kind.EQ);
			this.y = a.local(Kind.EQ);
			this.stackGrown = a.local(Kind.ARR);
			this.start = a.local(Kind.I32);
			this.building = a.local(Kind.I32);
			this.tmp = a.local(Kind.BYTES);
			this.tlen = a.local(Kind.I32);
			this.out = a.local(Kind.BYTES);
			this.n = a.local(Kind.I32);
			this.len = a.local(Kind.I32);
			this.id = a.local(Kind.I32);
			this.cp = a.local(Kind.I32);
			this.q = a.local(Kind.I32);
			this.k = a.local(Kind.I32);
			this.hv = a.local(Kind.I32);
			this.h = a.local(Kind.I32);
			this.digit = a.local(Kind.I32);
			this.count = a.local(Kind.I32);
			this.at = a.local(Kind.I32);
			this.pool = a.local(Kind.ARR);
			this.cand = a.local(Kind.BYTES);
			this.negative = a.local(Kind.I32);
			this.intStart = a.local(Kind.I32);
			this.intEnd = a.local(Kind.I32);
			this.fracStart = a.local(Kind.I32);
			this.fracEnd = a.local(Kind.I32);
			this.hasExponent = a.local(Kind.I32);
			this.exponentNegative = a.local(Kind.I32);
			this.iv = a.local(Kind.I64);
			this.e = a.local(Kind.I64);
			this.m = a.local(Kind.F64);
			this.fractionDigits = a.local(Kind.I32);
			this.pairs = a.local(Kind.I32);
			this.buckets = a.local(Kind.I32);
			this.mask = a.local(Kind.I32);
			this.size = a.local(Kind.I32);
			this.elements = a.local(Kind.ARR);
			this.record = a.local(Kind.ARR);
			this.bucketArray = a.local(Kind.ARR);
			this.order = a.local(Kind.ARR);
			this.key = a.local(Kind.EQ);
			this.value = a.local(Kind.EQ);
			this.index = a.local(Kind.I32);
			this.entry = a.local(Kind.ARR);
		}

		void emit() {
			// (stringp s): a character vector renders first, and anything but a
			// quote-framed string (a symbol shares the struct unframed) is no input
			WasmEmitHelper.emitCharvecToStrLocal(this.a.w, this.s, this.flags.charvecPossible());
			this.a.get(this.s).refTest(STRING).op(Instruction.I32_EQZ).ifVoid();
			fail(ERR_NOT_STRING);
			this.a.end();
			this.a.get(this.s).refCast(STRING).structGet(STRING, 2).refCast(BYTES).set(this.data);
			this.a.get(this.data).i32(0).arrayGetU(BYTES).i32(QUOTE).op(Instruction.I32_NE).ifVoid();
			fail(ERR_NOT_STRING);
			this.a.end();
			// The input is data[1, end): the frame's closing quote at end stops every
			// scan
			// that a quote, a non-digit or a non-blank stops, and every arm that reads it
			// past the input asks whether it is there.
			this.a.get(this.data).arrayLen().i32(1).op(Instruction.I32_SUB).set(this.end);
			this.a.i32(1).set(this.p);
			this.a.i32(16).arrayNewDefault(ARR).set(this.vs);
			// One value per round: parsed into x, or a container opened.
			this.a.loop("main");
			this.a.block("after");
			emitValue();
			this.a.end();
			// After a value: its place, and the separators and closes past it. An object
			// just opened skips to its first key.
			this.a.block("keyed");
			this.a.get(this.wantKey).brIf("keyed");
			push();
			this.a.loop("close");
			skipBlanks();
			this.a.get(this.ckind).op(Instruction.I32_EQZ).ifVoid();
			this.a.get(this.p).get(this.end).op(Instruction.I32_LT_U).ifVoid();
			fail(ERR_TRAILING);
			this.a.end();
			this.a.get(this.vs).i32(0).arrayGet(ARR).refNull();
			WasmEmitHelper.emitNewCons(this.a.w, this.flags.identityHash());
			this.a.ret();
			this.a.end();
			// the end of the input reads as the frame's quote, which no arm below takes
			this.a.get(this.b).i32(',').op(Instruction.I32_EQ).ifVoid();
			inc(this.p, 1);
			this.a.get(this.ckind).i32(OBJECT).op(Instruction.I32_EQ).brIf("keyed");
			this.a.br("main");
			this.a.end();
			this.a.get(this.ckind).i32(OBJECT).op(Instruction.I32_EQ).ifVoid();
			this.a.get(this.b).i32('}').op(Instruction.I32_NE).ifVoid();
			separatorFailure(ERR_UNTERMINATED_OBJECT, ERR_OBJECT_SEPARATOR);
			this.a.end();
			inc(this.p, 1);
			closeObject();
			this.a.elseArm();
			this.a.get(this.b).i32(']').op(Instruction.I32_NE).ifVoid();
			separatorFailure(ERR_UNTERMINATED_ARRAY, ERR_ARRAY_SEPARATOR);
			this.a.end();
			inc(this.p, 1);
			closeArray();
			this.a.end();
			// back to the container this one is in, the marker off the stack
			this.a.get(this.cstart).i32(1).op(Instruction.I32_SUB).set(this.vt);
			this.a.get(this.vs).get(this.vt).arrayGet(ARR).unI31().set(this.k);
			this.a.get(this.k).i32(3).op(Instruction.I32_AND).set(this.ckind);
			this.a.get(this.k).i32(2).op(Instruction.I32_SHR_U).set(this.cstart);
			push();
			this.a.br("close");
			this.a.end();
			this.a.end();
			this.a.i32(0).set(this.wantKey);
			emitKey();
			this.a.end();
			this.a.op(Instruction.UNREACHABLE);
		}

		private void fail(int code) {
			this.a.i32(code).i31().ret();
		}

		// p past the blanks; b the byte it stopped at (the frame's quote at the end).
		private void skipBlanks() {
			this.a.get(this.data).get(this.p).arrayGetU(BYTES).tee(this.b).i32(33).op(Instruction.I32_LT_U).ifVoid();
			this.a.block("blank-done");
			this.a.loop("blank");
			this.a.i64(1).get(this.b).op(Instruction.I64_EXTEND_U_I32).op(Instruction.I64_SHL);
			this.a.i64(WHITESPACE).op(Instruction.I64_AND).op(Instruction.I64_EQZ).brIf("blank-done");
			this.a.get(this.data).get(this.p).i32(1).op(Instruction.I32_ADD).tee(this.p).arrayGetU(BYTES).tee(this.b);
			this.a.i32(33).op(Instruction.I32_LT_U).brIf("blank");
			this.a.end();
			this.a.end();
			this.a.end();
		}

		private void inc(int local, int delta) {
			this.a.get(local).i32(delta).op(Instruction.I32_ADD).set(local);
		}

		// The byte at p + offset.
		private void byteAt(int offset) {
			this.a.get(this.data).get(this.p);
			if (offset != 0) {
				this.a.i32(offset).op(Instruction.I32_ADD);
			}
			this.a.arrayGetU(BYTES);
		}

		private void push() {
			this.a.get(this.vt).get(this.vs).arrayLen().op(Instruction.I32_EQ).ifVoid();
			this.a.get(this.vt).i32(1).op(Instruction.I32_SHL).arrayNewDefault(ARR).set(this.stackGrown);
			this.a.get(this.stackGrown).i32(0).get(this.vs).i32(0).get(this.vt).arrayCopy(ARR, ARR);
			this.a.get(this.stackGrown).set(this.vs);
			this.a.end();
			this.a.get(this.vs).get(this.vt).get(this.x).arraySet(ARR);
			inc(this.vt, 1);
		}

		private void emitValue() {
			skipBlanks();
			this.a.block("number");
			this.a.block("null");
			this.a.block("false");
			this.a.block("true");
			this.a.block("array");
			this.a.block("object");
			this.a.block("open-quote");
			this.a.block("bad");
			Map<Integer, String> dispatch = cases("number", '-', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9');
			dispatch.put(QUOTE, "open-quote");
			dispatch.put((int) '{', "object");
			dispatch.put((int) '[', "array");
			dispatch.put((int) 't', "true");
			dispatch.put((int) 'f', "false");
			dispatch.put((int) 'n', "null");
			this.a.get(this.b).brTable("bad", dispatch, '{' + 1);
			this.a.end();
			fail(ERR_UNEXPECTED_CHARACTER);
			this.a.end();
			// the frame's quote, which only the end of the input reads as a quote
			this.a.get(this.p).get(this.end).op(Instruction.I32_GE_U).ifVoid();
			fail(ERR_END);
			this.a.end();
			scanString(false);
			this.a.br("after");
			this.a.end();
			emitOpen(OBJECT, '}', ERR_UNTERMINATED_OBJECT);
			this.a.end();
			emitOpen(ARRAY, ']', ERR_UNTERMINATED_ARRAY);
			this.a.end();
			literal("true");
			this.a.call(WasmLispCompiler.FUNC_T_SYM).set(this.x);
			this.a.br("after");
			this.a.end();
			literal("false");
			this.a.refNull().set(this.x);
			this.a.br("after");
			this.a.end();
			literal("null");
			this.a.get(this.nullSymbol).set(this.x);
			this.a.br("after");
			this.a.end();
			emitNumber();
			this.a.br("after");
		}

		// At { or [: the empty container, or a marker of the one it is inside and the
		// container open.
		private void emitOpen(int kind, int close, int unterminated) {
			inc(this.p, 1);
			skipBlanks();
			this.a.get(this.p).get(this.end).op(Instruction.I32_GE_U).ifVoid();
			fail(unterminated);
			this.a.end();
			this.a.get(this.b).i32(close).op(Instruction.I32_EQ).ifVoid();
			inc(this.p, 1);
			if (kind == OBJECT) {
				this.a.i32(0).i31();
				WasmHashTableRuntimeBuilder.emitNewSmallRecord(this.a.w, 0);
				WasmEmitHelper.emitNewCons(this.a.w, this.flags.identityHash());
				WasmEmitHelper.emitNewCell(this.a.w, this.flags.identityHash());
				this.a.set(this.x);
			}
			else {
				this.a.i32(0).arrayNewDefault(ARR).set(this.elements);
				this.a.i32(0).set(this.n);
				newVector();
			}
			this.a.br("after");
			this.a.end();
			this.a.get(this.cstart).i32(2).op(Instruction.I32_SHL).get(this.ckind).op(Instruction.I32_OR).i31();
			this.a.set(this.x);
			push();
			this.a.get(this.vt).set(this.cstart);
			this.a.i32(kind).set(this.ckind);
			if (kind == OBJECT) {
				this.a.i32(1).set(this.wantKey);
				this.a.br("after");
			}
			else {
				this.a.br("main");
			}
		}

		// The rest of a literal whose first byte matched: p past it, or the error.
		private void literal(String word) {
			this.a.get(this.p).i32(word.length()).op(Instruction.I32_ADD).get(this.end).op(Instruction.I32_GT_U);
			this.a.ifVoid();
			fail(ERR_INVALID_LITERAL);
			this.a.end();
			for (int i = 1; i < word.length(); i++) {
				byteAt(i);
				this.a.i32(word.charAt(i)).op(Instruction.I32_NE).ifVoid();
				fail(ERR_INVALID_LITERAL);
				this.a.end();
			}
			inc(this.p, word.length());
		}

		// A cell over the general array header of a simple vector of n elements.
		private void newVector() {
			this.a.get(this.n).i31().arrayNewFixed(ARR, 1);
			this.a.refNull().refNull().i32(0).i31();
			WasmEmitHelper.emitNewCons(this.a.w, this.flags.identityHash());
			WasmEmitHelper.emitNewCons(this.a.w, this.flags.identityHash());
			this.a.get(this.elements);
			WasmEmitHelper.emitNewCons(this.a.w, this.flags.identityHash());
			WasmEmitHelper.emitNewCons(this.a.w, this.flags.identityHash());
			WasmEmitHelper.emitNewCell(this.a.w, this.flags.identityHash());
			this.a.set(this.x);
		}

		// A key, its placement hash and its colon; then the value, the next round.
		private void emitKey() {
			skipBlanks();
			this.a.get(this.b).i32(QUOTE).op(Instruction.I32_NE);
			this.a.get(this.p).get(this.end).op(Instruction.I32_GE_U).op(Instruction.I32_OR).ifVoid();
			fail(ERR_OBJECT_KEY);
			this.a.end();
			scanString(true);
			push();
			this.a.get(this.h).i31().set(this.x);
			push();
			skipBlanks();
			// the end of the input reads as the frame's quote, no colon either
			this.a.get(this.b).i32(':').op(Instruction.I32_NE).ifVoid();
			fail(ERR_OBJECT_COLON);
			this.a.end();
			inc(this.p, 1);
			this.a.br("main");
		}

		// At a string's opening quote: x the string (and h its placement hash for a
		// key), p past its closing quote.
		private void scanString(boolean isKey) {
			this.a.get(this.p).i32(1).op(Instruction.I32_ADD).tee(this.p).set(this.start);
			this.a.i32(0).set(this.building);
			this.a.block("string-done");
			this.a.loop("more");
			this.a.block("backslash");
			this.a.block("quote");
			this.a.loop("scan");
			this.a.get(this.data).get(this.p).arrayGetU(BYTES);
			inc(this.p, 1);
			Map<Integer, String> stops = cases("quote", QUOTE);
			stops.put(BACKSLASH, "backslash");
			this.a.brTable("scan", stops, BACKSLASH + 1);
			this.a.end();
			this.a.end();
			// the quote before p: the frame's when nothing closed the string
			this.a.get(this.p).get(this.end).op(Instruction.I32_GT_U).ifVoid();
			fail(ERR_UNTERMINATED_STRING);
			this.a.end();
			this.a.get(this.p).i32(1).op(Instruction.I32_SUB).set(this.q);
			finishString(isKey);
			this.a.br("string-done");
			this.a.end();
			// the backslash before p
			inc(this.p, -1);
			emitEscape();
			this.a.get(this.p).set(this.start);
			this.a.br("more");
			this.a.end();
			this.a.end();
		}

		// x = the string data[start, q) spells, through the builder once an escape
		// opened it, and through the pool for a key without one.
		private void finishString(boolean isKey) {
			this.a.block("made");
			this.a.get(this.building).ifVoid();
			appendRun(this.q);
			this.a.get(this.tlen).set(this.n);
			newString(this.tmp, -1);
			if (isKey) {
				WasmHashTableRuntimeBuilder.emitEqualPlacementHash(this.a.w, this.x, this.h);
			}
			this.a.br("made");
			this.a.end();
			this.a.get(this.q).get(this.start).op(Instruction.I32_SUB).set(this.n);
			if (isKey) {
				// The pool's slot, by the key's length and its first and last bytes (an
				// object's keys mostly differ there); the bytes decide a hit, which
				// brings
				// the placement hash with it.
				this.a.get(this.data).get(this.start).arrayGetU(BYTES).i32(31).op(Instruction.I32_MUL);
				this.a.get(this.data).get(this.q).i32(1).op(Instruction.I32_SUB).arrayGetU(BYTES);
				this.a.op(Instruction.I32_ADD).get(this.n).i32(7).op(Instruction.I32_MUL).op(Instruction.I32_ADD);
				this.a.i32(POOL - 1).op(Instruction.I32_AND).i32(1).op(Instruction.I32_SHL).set(this.hv);
				this.a.get(this.pool).isNull().ifVoid();
				this.a.i32(2 * POOL).arrayNewDefault(ARR).set(this.pool);
				this.a.end();
				this.a.get(this.pool).get(this.hv).arrayGet(ARR).set(this.y);
				this.a.block("miss");
				this.a.get(this.y).isNull().brIf("miss");
				this.a.get(this.y).refCast(STRING).structGet(STRING, 2).refCast(BYTES).set(this.cand);
				this.a.get(this.cand).arrayLen().get(this.n).i32(2).op(Instruction.I32_ADD).op(Instruction.I32_NE);
				this.a.brIf("miss");
				this.a.i32(0).set(this.k);
				this.a.block("same");
				this.a.loop("compare");
				this.a.get(this.k).get(this.n).op(Instruction.I32_GE_U).brIf("same");
				this.a.get(this.cand).get(this.k).i32(1).op(Instruction.I32_ADD).arrayGetU(BYTES);
				this.a.get(this.data).get(this.start).get(this.k).op(Instruction.I32_ADD).arrayGetU(BYTES);
				this.a.op(Instruction.I32_NE).brIf("miss");
				inc(this.k, 1);
				this.a.br("compare");
				this.a.end();
				this.a.end();
				this.a.get(this.y).set(this.x);
				this.a.get(this.pool).get(this.hv).i32(1).op(Instruction.I32_ADD).arrayGet(ARR).unI31().set(this.h);
				this.a.br("made");
				this.a.end();
				newString(this.data, this.start);
				WasmHashTableRuntimeBuilder.emitEqualPlacementHash(this.a.w, this.x, this.h);
				this.a.get(this.pool).get(this.hv).get(this.x).arraySet(ARR);
				this.a.get(this.pool).get(this.hv).i32(1).op(Instruction.I32_ADD).get(this.h).i31().arraySet(ARR);
			}
			else {
				newString(this.data, this.start);
			}
			this.a.end();
		}

		// x = a fresh string of the n bytes of src from the local at (0 when at < 0).
		private void newString(int src, int from) {
			this.a.get(this.n).i32(2).op(Instruction.I32_ADD).tee(this.len).arrayNewDefault(BYTES).set(this.out);
			this.a.get(this.out).i32(0).i32(QUOTE).arraySet(BYTES);
			this.a.get(this.out).get(this.len).i32(1).op(Instruction.I32_SUB).i32(QUOTE).arraySet(BYTES);
			this.a.get(this.out).i32(1).get(src);
			if (from < 0) {
				this.a.i32(0);
			}
			else {
				this.a.get(from);
			}
			this.a.get(this.n).arrayCopy(BYTES, BYTES);
			WasmStringRuntimeBuilder.emitRuntimeString(this.a.w, this.len, this.out, this.id);
			this.a.set(this.x);
		}

		// Room for the given bytes more in the builder.
		private void reserve(int more) {
			this.a.get(this.tlen).i32(more).op(Instruction.I32_ADD).get(this.tmp).arrayLen().op(Instruction.I32_GT_U);
			this.a.ifVoid();
			this.a.get(this.tmp).get(this.tlen).i32(more).op(Instruction.I32_ADD).i31();
			this.a.call(this.base + GROW).refCast(BYTES).set(this.tmp);
			this.a.end();
		}

		// The builder gains data[start, the local's position).
		private void appendRun(int stop) {
			this.a.get(stop).get(this.start).op(Instruction.I32_SUB).set(this.n);
			this.a.get(this.tlen).get(this.n).op(Instruction.I32_ADD).get(this.tmp).arrayLen().op(Instruction.I32_GT_U);
			this.a.ifVoid();
			this.a.get(this.tmp).get(this.tlen).get(this.n).op(Instruction.I32_ADD).i31();
			this.a.call(this.base + GROW).refCast(BYTES).set(this.tmp);
			this.a.end();
			this.a.get(this.tmp).get(this.tlen).get(this.data).get(this.start).get(this.n).arrayCopy(BYTES, BYTES);
			this.a.get(this.tlen).get(this.n).op(Instruction.I32_ADD).set(this.tlen);
		}

		private void appendByte(int local) {
			reserve(1);
			this.a.get(this.tmp).get(this.tlen).get(local).arraySet(BYTES);
			inc(this.tlen, 1);
		}

		// At a backslash: what it escapes joins the builder, p past it.
		private void emitEscape() {
			this.a.get(this.p).i32(1).op(Instruction.I32_ADD).get(this.end).op(Instruction.I32_GE_U).ifVoid();
			fail(ERR_UNTERMINATED_ESCAPE);
			this.a.end();
			this.a.get(this.building).op(Instruction.I32_EQZ).ifVoid();
			this.a.i32(1).set(this.building);
			this.a.i32(0).set(this.tlen);
			this.a.get(this.tmp).isNull().ifVoid();
			this.a.i32(64).arrayNewDefault(BYTES).set(this.tmp);
			this.a.end();
			this.a.end();
			appendRun(this.p);
			byteAt(1);
			this.a.set(this.b);
			this.a.block("escaped");
			this.a.block("simple");
			this.a.block("unicode");
			this.a.block("tab");
			this.a.block("return");
			this.a.block("newline");
			this.a.block("formfeed");
			this.a.block("backspace");
			this.a.block("itself");
			this.a.block("invalid");
			Map<Integer, String> escapes = cases("itself", QUOTE, BACKSLASH, '/');
			escapes.put((int) 'b', "backspace");
			escapes.put((int) 'f', "formfeed");
			escapes.put((int) 'n', "newline");
			escapes.put((int) 'r', "return");
			escapes.put((int) 't', "tab");
			escapes.put((int) 'u', "unicode");
			this.a.get(this.b).brTable("invalid", escapes, 'u' + 1);
			this.a.end();
			fail(ERR_INVALID_ESCAPE);
			this.a.end();
			this.a.get(this.b).set(this.cp).br("simple");
			this.a.end();
			this.a.i32(8).set(this.cp).br("simple");
			this.a.end();
			this.a.i32(12).set(this.cp).br("simple");
			this.a.end();
			this.a.i32(10).set(this.cp).br("simple");
			this.a.end();
			this.a.i32(13).set(this.cp).br("simple");
			this.a.end();
			this.a.i32(9).set(this.cp).br("simple");
			this.a.end();
			emitUnicode();
			this.a.br("escaped");
			this.a.end();
			appendByte(this.cp);
			inc(this.p, 2);
			this.a.end();
		}

		// At a backslash-u escape: its character, a surrogate pair combined; p past the
		// escapes.
		private void emitUnicode() {
			this.a.get(this.p).i32(2).op(Instruction.I32_ADD).set(this.q);
			hex4();
			this.a.get(this.hv).set(this.cp);
			inc(this.p, 6);
			this.a.get(this.cp).i32(0xD800).op(Instruction.I32_GE_U);
			this.a.get(this.cp).i32(0xDBFF).op(Instruction.I32_LE_U).op(Instruction.I32_AND);
			this.a.get(this.p).i32(6).op(Instruction.I32_ADD).get(this.end).op(Instruction.I32_LE_U);
			this.a.op(Instruction.I32_AND).ifVoid();
			byteAt(0);
			this.a.i32(BACKSLASH).op(Instruction.I32_EQ);
			byteAt(1);
			this.a.i32('u').op(Instruction.I32_EQ).op(Instruction.I32_AND).ifVoid();
			this.a.get(this.p).i32(2).op(Instruction.I32_ADD).set(this.q);
			hex4();
			inc(this.p, 6);
			this.a.get(this.hv).i32(0xDC00).op(Instruction.I32_GE_U);
			this.a.get(this.hv).i32(0xDFFF).op(Instruction.I32_LE_U).op(Instruction.I32_AND).ifVoid();
			this.a.get(this.cp).i32(0xD800).op(Instruction.I32_SUB).i32(10).op(Instruction.I32_SHL);
			this.a.get(this.hv).i32(0xDC00).op(Instruction.I32_SUB).op(Instruction.I32_ADD);
			this.a.i32(0x10000).op(Instruction.I32_ADD).set(this.cp);
			this.a.elseArm();
			appendUtf8();
			this.a.get(this.hv).set(this.cp);
			this.a.end();
			this.a.end();
			this.a.end();
			appendUtf8();
		}

		// hv = the four hex digits at q, or the error json.lisp signals for them: a
		// remainder of fewer than four CHARACTERS is unterminated, which a multi-byte
		// character among the four bytes can make it.
		private void hex4() {
			this.a.get(this.q).i32(4).op(Instruction.I32_ADD).get(this.end).op(Instruction.I32_GT_U).ifVoid();
			fail(ERR_UNTERMINATED_UNICODE);
			this.a.end();
			this.a.i32(0).set(this.hv);
			this.a.i32(0).set(this.k);
			this.a.loop("hex");
			this.a.get(this.data).get(this.q).get(this.k).op(Instruction.I32_ADD).arrayGetU(BYTES).set(this.b);
			this.a.get(this.b).i32('0').op(Instruction.I32_SUB).tee(this.digit).i32(10).op(Instruction.I32_LT_U);
			this.a.ifVoid();
			this.a.get(this.hv).i32(4).op(Instruction.I32_SHL).get(this.digit).op(Instruction.I32_OR).set(this.hv);
			this.a.elseArm();
			this.a.get(this.b).i32(32).op(Instruction.I32_OR).i32('a').op(Instruction.I32_SUB).tee(this.digit);
			this.a.i32(6).op(Instruction.I32_LT_U).ifVoid();
			this.a.get(this.hv).i32(4).op(Instruction.I32_SHL);
			this.a.get(this.digit).i32(10).op(Instruction.I32_ADD).op(Instruction.I32_OR).set(this.hv);
			this.a.elseArm();
			hexFailure();
			this.a.end();
			this.a.end();
			inc(this.k, 1);
			this.a.get(this.k).i32(4).op(Instruction.I32_LT_U).brIf("hex");
			this.a.end();
		}

		private void hexFailure() {
			this.a.i32(0).set(this.count);
			this.a.get(this.q).set(this.at);
			this.a.block("counted");
			this.a.loop("lead");
			this.a.get(this.at).get(this.end).op(Instruction.I32_GE_U).brIf("counted");
			this.a.get(this.count).i32(4).op(Instruction.I32_GE_U).brIf("counted");
			this.a.get(this.data).get(this.at).arrayGetU(BYTES).i32(0xC0).op(Instruction.I32_AND);
			this.a.i32(0x80).op(Instruction.I32_NE).ifVoid();
			inc(this.count, 1);
			this.a.end();
			inc(this.at, 1);
			this.a.br("lead");
			this.a.end();
			this.a.end();
			this.a.get(this.count).i32(4).op(Instruction.I32_LT_U).ifVoid();
			fail(ERR_UNTERMINATED_UNICODE);
			this.a.end();
			fail(ERR_INVALID_HEX);
		}

		// The builder gains cp's UTF-8 bytes, what a string of (code-char cp) holds.
		private void appendUtf8() {
			reserve(4);
			this.a.get(this.cp).i32(0x80).op(Instruction.I32_LT_U).ifVoid();
			putByte(this.cp, 0, -1, 0);
			inc(this.tlen, 1);
			this.a.elseArm();
			this.a.get(this.cp).i32(0x800).op(Instruction.I32_LT_U).ifVoid();
			putByte(this.cp, 0, 6, 0xC0);
			putByte(this.cp, 1, 0, 0x80);
			inc(this.tlen, 2);
			this.a.elseArm();
			this.a.get(this.cp).i32(0x10000).op(Instruction.I32_LT_U).ifVoid();
			putByte(this.cp, 0, 12, 0xE0);
			putByte(this.cp, 1, 6, 0x80);
			putByte(this.cp, 2, 0, 0x80);
			inc(this.tlen, 3);
			this.a.elseArm();
			putByte(this.cp, 0, 18, 0xF0);
			putByte(this.cp, 1, 12, 0x80);
			putByte(this.cp, 2, 6, 0x80);
			putByte(this.cp, 3, 0, 0x80);
			inc(this.tlen, 4);
			this.a.end();
			this.a.end();
			this.a.end();
		}

		// tmp[tlen + offset] = lead | (value >> shift), its low six bits for a
		// continuation byte (lead 0x80); a negative shift stores the value as it is.
		private void putByte(int local, int offset, int shift, int lead) {
			this.a.get(this.tmp).get(this.tlen);
			if (offset != 0) {
				this.a.i32(offset).op(Instruction.I32_ADD);
			}
			this.a.get(local);
			if (shift > 0) {
				this.a.i32(shift).op(Instruction.I32_SHR_U);
			}
			if (lead == 0x80) {
				this.a.i32(0x3F).op(Instruction.I32_AND);
			}
			if (lead != 0) {
				this.a.i32(lead).op(Instruction.I32_OR);
			}
			this.a.arraySet(BYTES);
		}

		// At - or a digit: x = the number, p past it.
		private void emitNumber() {
			this.a.get(this.b).i32('-').op(Instruction.I32_EQ).set(this.negative);
			this.a.get(this.p).get(this.negative).op(Instruction.I32_ADD).tee(this.p).set(this.intStart);
			// the integer digits, folded as they are passed (past 18 of them the value is
			// a float's, and the fold is not read)
			this.a.i64(0).set(this.iv);
			this.a.block("int-done");
			this.a.loop("int");
			this.a.get(this.data).get(this.p).arrayGetU(BYTES).i32('0').op(Instruction.I32_SUB).tee(this.digit);
			this.a.i32(10).op(Instruction.I32_GE_U).brIf("int-done");
			this.a.get(this.iv).i64(10).op(Instruction.I64_MUL);
			this.a.get(this.digit).op(Instruction.I64_EXTEND_U_I32).op(Instruction.I64_ADD).set(this.iv);
			inc(this.p, 1);
			this.a.br("int");
			this.a.end();
			this.a.end();
			this.a.get(this.p).set(this.intEnd);
			this.a.get(this.intEnd).get(this.intStart).op(Instruction.I32_EQ).ifVoid();
			fail(ERR_INVALID_NUMBER);
			this.a.end();
			this.a.i32(0).set(this.fracStart);
			this.a.i32(0).set(this.fracEnd);
			this.a.i32(0).set(this.hasExponent);
			byteAt(0);
			this.a.i32('.').op(Instruction.I32_EQ).ifVoid();
			this.a.get(this.p).i32(1).op(Instruction.I32_ADD).tee(this.p).set(this.fracStart);
			skipDigits();
			this.a.get(this.p).set(this.fracEnd);
			this.a.get(this.fracEnd).get(this.fracStart).op(Instruction.I32_EQ).ifVoid();
			fail(ERR_INVALID_NUMBER);
			this.a.end();
			this.a.end();
			byteAt(0);
			this.a.i32(32).op(Instruction.I32_OR).i32('e').op(Instruction.I32_EQ).ifVoid();
			this.a.i32(1).set(this.hasExponent);
			inc(this.p, 1);
			byteAt(0);
			this.a.set(this.b);
			this.a.get(this.b).i32('+').op(Instruction.I32_EQ).get(this.b).i32('-').op(Instruction.I32_EQ);
			this.a.op(Instruction.I32_OR).ifVoid();
			inc(this.p, 1);
			this.a.end();
			this.a.get(this.p).set(this.q);
			skipDigits();
			this.a.get(this.p).get(this.q).op(Instruction.I32_EQ).ifVoid();
			fail(ERR_INVALID_NUMBER);
			this.a.end();
			this.a.end();
			// Eighteen digits at most, no fraction and no exponent: the exact integer.
			this.a.get(this.fracStart).op(Instruction.I32_EQZ).get(this.hasExponent).op(Instruction.I32_EQZ);
			this.a.op(Instruction.I32_AND);
			this.a.get(this.intEnd).get(this.intStart).op(Instruction.I32_SUB).i32(18).op(Instruction.I32_LE_S);
			this.a.op(Instruction.I32_AND).ifVoid();
			this.a.get(this.negative).ifVoid();
			this.a.i64(0).get(this.iv).op(Instruction.I64_SUB).set(this.iv);
			this.a.end();
			this.a.get(this.iv).call(WasmLispCompiler.FUNC_INT_NEW).set(this.x);
			this.a.elseArm();
			emitFloat();
			this.a.end();
		}

		// p past the digits
		private void skipDigits() {
			this.a.block("digits-done");
			this.a.loop("digits");
			this.a.get(this.data).get(this.p).arrayGetU(BYTES).i32('0').op(Instruction.I32_SUB);
			this.a.i32(10).op(Instruction.I32_GE_U).brIf("digits-done");
			inc(this.p, 1);
			this.a.br("digits");
			this.a.end();
			this.a.end();
		}

		// json.lisp's %json-float, operation for operation: the digits folded as a
		// double, then scaled by (expt 10.0 n), which is pow.
		private void emitFloat() {
			this.a.f64(0.0).set(this.m);
			digitsIntoDouble(this.intStart, this.intEnd);
			this.a.i32(0).set(this.fractionDigits);
			this.a.get(this.fracStart).ifVoid();
			digitsIntoDouble(this.fracStart, this.fracEnd);
			this.a.get(this.fracEnd).get(this.fracStart).op(Instruction.I32_SUB).set(this.fractionDigits);
			this.a.end();
			this.a.i64(0).set(this.e);
			this.a.get(this.hasExponent).ifVoid();
			// the byte after the e or E
			this.a.get(this.fracStart).ifI32().get(this.fracEnd).elseArm().get(this.intEnd).end();
			this.a.i32(1).op(Instruction.I32_ADD).set(this.k);
			this.a.get(this.data).get(this.k).arrayGetU(BYTES).set(this.b);
			this.a.get(this.b).i32('-').op(Instruction.I32_EQ).set(this.exponentNegative);
			this.a.get(this.b).i32('+').op(Instruction.I32_EQ).get(this.exponentNegative).op(Instruction.I32_OR);
			this.a.ifVoid();
			inc(this.k, 1);
			this.a.end();
			this.a.block("exp-done");
			this.a.loop("exp");
			this.a.get(this.k).get(this.p).op(Instruction.I32_GE_U).brIf("exp-done");
			// Past 10^15 the scale is infinite or zero whatever follows, as the exact
			// exponent json.lisp folds makes it.
			this.a.get(this.e).i64(1_000_000_000_000_000L).op(Instruction.I64_LT_S).ifVoid();
			this.a.get(this.e).i64(10).op(Instruction.I64_MUL);
			this.a.get(this.data).get(this.k).arrayGetU(BYTES).i32('0').op(Instruction.I32_SUB);
			this.a.op(Instruction.I64_EXTEND_U_I32).op(Instruction.I64_ADD).set(this.e);
			this.a.end();
			inc(this.k, 1);
			this.a.br("exp");
			this.a.end();
			this.a.end();
			this.a.get(this.exponentNegative).ifVoid();
			this.a.i64(0).get(this.e).op(Instruction.I64_SUB).set(this.e);
			this.a.end();
			this.a.end();
			this.a.get(this.e).get(this.fractionDigits).op(Instruction.I64_EXTEND_U_I32).op(Instruction.I64_SUB);
			this.a.set(this.e);
			this.a.get(this.e).i64(0).op(Instruction.I64_LT_S).ifVoid();
			this.a.get(this.m).f64(10.0).i64(0).get(this.e).op(Instruction.I64_SUB).op(Instruction.F64_CONVERT_S_I64);
			this.a.call(WasmLispCompiler.fdlibmFunc(WasmFdlibmRuntimeBuilder.Fn.POW)).op(Instruction.F64_DIV);
			this.a.set(this.m);
			this.a.elseArm();
			this.a.get(this.e).i64(0).op(Instruction.I64_GT_S).ifVoid();
			this.a.get(this.m).f64(10.0).get(this.e).op(Instruction.F64_CONVERT_S_I64);
			this.a.call(WasmLispCompiler.fdlibmFunc(WasmFdlibmRuntimeBuilder.Fn.POW)).op(Instruction.F64_MUL);
			this.a.set(this.m);
			this.a.end();
			this.a.end();
			this.a.get(this.negative).ifVoid();
			this.a.get(this.m).op(Instruction.F64_NEG).set(this.m);
			this.a.end();
			this.a.get(this.m).structNew(WasmLispCompiler.TYPE_FLOAT).set(this.x);
		}

		// m = m * 10.0 + digit for each digit of data[from, to).
		private void digitsIntoDouble(int from, int to) {
			this.a.get(from).set(this.k);
			this.a.block("fold-done");
			this.a.loop("fold");
			this.a.get(this.k).get(to).op(Instruction.I32_GE_U).brIf("fold-done");
			this.a.get(this.m).f64(10.0).op(Instruction.F64_MUL);
			this.a.get(this.data).get(this.k).arrayGetU(BYTES).i32('0').op(Instruction.I32_SUB);
			this.a.op(Instruction.F64_CONVERT_S_I32).op(Instruction.F64_ADD).set(this.m);
			inc(this.k, 1);
			this.a.br("fold");
			this.a.end();
			this.a.end();
		}

		// No separator where one belongs: the input ended, or something else is there.
		private void separatorFailure(int unterminated, int separator) {
			this.a.get(this.p).get(this.end).op(Instruction.I32_GE_U).ifVoid();
			fail(unterminated);
			this.a.end();
			fail(separator);
		}

		// x = the hash table of the (key, hash, value) triples on the stack from cstart,
		// each pair put in order (a repeated key's last value wins, at its first key's
		// place): the table _ht_put would have built -- a small record of at most
		// SMALL_MAX keys, its room the pairs, else a flat one without its growth tests.
		private void closeObject() {
			this.a.get(this.vt).get(this.cstart).op(Instruction.I32_SUB).i32(3).op(Instruction.I32_DIV_U);
			this.a.set(this.pairs);
			this.a.get(this.pairs).i32(WasmHashTableRuntimeBuilder.SMALL_MAX).op(Instruction.I32_LE_U).ifVoid();
			closeSmallObject();
			this.a.elseArm();
			closeFlatObject();
			this.a.end();
			// the header's count, its test tag (equal, 0) beside it in a module that tags
			this.a.get(this.size);
			if (this.flags.tagged()) {
				this.a.i32(2).op(Instruction.I32_SHL);
			}
			this.a.i31().get(this.record);
			WasmEmitHelper.emitNewCons(this.a.w, this.flags.identityHash());
			WasmEmitHelper.emitNewCell(this.a.w, this.flags.identityHash());
			this.a.set(this.x);
		}

		// record = the small record of the triples' pairs and size their count: a key
		// repeating an earlier triple's (the placement hashes compare first) gives that
		// triple its value and leaves its own without a key, which the pairs skip.
		private void closeSmallObject() {
			this.a.get(this.pairs).set(this.size);
			this.a.get(this.cstart).i32(3).op(Instruction.I32_ADD).set(this.k);
			this.a.block("deduped");
			this.a.loop("dedup");
			this.a.get(this.k).get(this.vt).op(Instruction.I32_GE_U).brIf("deduped");
			this.a.get(this.vs).get(this.k).i32(1).op(Instruction.I32_ADD).arrayGet(ARR).unI31().set(this.h);
			this.a.get(this.cstart).set(this.index);
			this.a.block("unique");
			this.a.loop("earlier");
			this.a.get(this.index).get(this.k).op(Instruction.I32_GE_U).brIf("unique");
			this.a.get(this.vs).get(this.index).arrayGet(ARR).set(this.key);
			this.a.get(this.key).isNull().op(Instruction.I32_EQZ);
			this.a.get(this.vs).get(this.index).i32(1).op(Instruction.I32_ADD).arrayGet(ARR).unI31();
			this.a.get(this.h).op(Instruction.I32_EQ).op(Instruction.I32_AND).ifVoid();
			this.a.get(this.key).get(this.vs).get(this.k).arrayGet(ARR).call(WasmLispCompiler.FUNC_EQUAL).ifVoid();
			this.a.get(this.vs).get(this.index).i32(2).op(Instruction.I32_ADD);
			this.a.get(this.vs).get(this.k).i32(2).op(Instruction.I32_ADD).arrayGet(ARR).arraySet(ARR);
			this.a.get(this.vs).get(this.k).refNull().arraySet(ARR);
			inc(this.size, -1);
			this.a.br("unique");
			this.a.end();
			this.a.end();
			inc(this.index, 3);
			this.a.br("earlier");
			this.a.end();
			this.a.end();
			inc(this.k, 3);
			this.a.br("dedup");
			this.a.end();
			this.a.end();
			this.a.get(this.size).i32(1).op(Instruction.I32_SHL).i32(WasmHashTableRuntimeBuilder.S_PAIRS);
			this.a.op(Instruction.I32_ADD).arrayNewDefault(ARR).set(this.record);
			this.a.get(this.record).i32(WasmHashTableRuntimeBuilder.S_COUNT).get(this.size).i31().arraySet(ARR);
			this.a.i32(WasmHashTableRuntimeBuilder.S_PAIRS).set(this.index);
			this.a.get(this.cstart).set(this.k);
			this.a.block("filled");
			this.a.loop("fill");
			this.a.get(this.k).get(this.vt).op(Instruction.I32_GE_U).brIf("filled");
			this.a.get(this.vs).get(this.k).arrayGet(ARR).set(this.key);
			this.a.get(this.key).isNull().op(Instruction.I32_EQZ).ifVoid();
			this.a.get(this.record).get(this.index).get(this.key).arraySet(ARR);
			this.a.get(this.record).get(this.index).i32(1).op(Instruction.I32_ADD);
			this.a.get(this.vs).get(this.k).i32(2).op(Instruction.I32_ADD).arrayGet(ARR).arraySet(ARR);
			inc(this.index, 2);
			this.a.end();
			inc(this.k, 3);
			this.a.br("fill");
			this.a.end();
			this.a.end();
		}

		// record = the flat record of the triples, its buckets and order sized so that no
		// insertion grows them, and size its entries.
		private void closeFlatObject() {
			this.a.i32(1).set(this.buckets);
			this.a.block("sized");
			this.a.loop("size");
			this.a.get(this.pairs);
			this.a.get(this.buckets).get(this.buckets).i32(2).op(Instruction.I32_SHR_U).op(Instruction.I32_SUB);
			this.a.op(Instruction.I32_LE_U).brIf("sized");
			this.a.get(this.buckets).i32(1).op(Instruction.I32_SHL).set(this.buckets);
			this.a.br("size");
			this.a.end();
			this.a.end();
			this.a.get(this.buckets).arrayNewDefault(ARR).set(this.bucketArray);
			this.a.get(this.pairs).arrayNewDefault(ARR).set(this.order);
			this.a.get(this.bucketArray).get(this.order).i32(0).i31().i32(0).i31();
			this.a.arrayNewFixed(ARR, WasmHashTableRuntimeBuilder.F_LEN).set(this.record);
			this.a.get(this.buckets).i32(1).op(Instruction.I32_SUB).set(this.mask);
			this.a.i32(0).set(this.size);
			this.a.get(this.cstart).set(this.k);
			this.a.block("put-done");
			this.a.loop("put");
			this.a.get(this.k).get(this.vt).op(Instruction.I32_GE_U).brIf("put-done");
			this.a.get(this.vs).get(this.k).arrayGet(ARR).set(this.key);
			this.a.get(this.vs).get(this.k).i32(1).op(Instruction.I32_ADD).arrayGet(ARR).unI31().set(this.h);
			this.a.get(this.vs).get(this.k).i32(2).op(Instruction.I32_ADD).arrayGet(ARR).set(this.value);
			WasmHashTableRuntimeBuilder.emitFlatInsert(this.a.w, this.record, this.bucketArray, this.order, this.mask,
					this.size, this.key, this.h, this.value, this.index, this.entry);
			inc(this.k, 3);
			this.a.br("put");
			this.a.end();
			this.a.end();
			this.a.get(this.record).i32(WasmHashTableRuntimeBuilder.F_SIZE).get(this.size).i31().arraySet(ARR);
		}

		// x = the vector of the values on the stack from cstart.
		private void closeArray() {
			this.a.get(this.vt).get(this.cstart).op(Instruction.I32_SUB).set(this.n);
			this.a.get(this.n).arrayNewDefault(ARR).set(this.elements);
			this.a.get(this.elements).i32(0).get(this.vs).get(this.cstart).get(this.n).arrayCopy(ARR, ARR);
			newVector();
		}

	}

	// --- the encoder ---------------------------------------------------------

	private static byte[] buildEncode(int base, Flags flags) {
		Asm a = new Asm(4);
		new Encoder(a, base, flags).emit();
		return a.finish();
	}

	/** The encoder's locals and the code that shares them. */
	private static final class Encoder {

		private final Asm a;

		private final int base;

		private final Flags flags;

		// params
		private final int v = 0;

		private final int nullSymbol = 1;

		private final int other = 2;

		private final int keyName = 3;

		private final int out;

		private final int pos;

		private final int stop;

		private final int stk;

		private final int sp;

		private final int stackGrown;

		private final int x;

		private final int y;

		private final int z;

		private final int tId;

		private final int nId;

		// the innermost frame: what it walks, where it is, where it ends
		private final int kind;

		private final int container;

		private final int rest;

		private final int i;

		private final int n;

		// the next frame, before it is pushed
		private final int newContainer;

		private final int newIndex;

		private final int newLimit;

		private final int entry;

		private final int str;

		private final int at;

		private final int last;

		private final int from;

		private final int run;

		private final int need;

		private final int b;

		private final int nibble;

		private final int iv;

		private final int u;

		private final int digits;

		private final int len;

		private final int id;

		private final int res;

		private final int chunks;

		private final int chunk;

		Encoder(Asm a, int base, Flags flags) {
			this.a = a;
			this.base = base;
			this.flags = flags;
			this.out = a.local(Kind.BYTES);
			this.pos = a.local(Kind.I32);
			this.stop = a.local(Kind.I32);
			this.stk = a.local(Kind.ARR);
			this.sp = a.local(Kind.I32);
			this.stackGrown = a.local(Kind.ARR);
			this.x = a.local(Kind.EQ);
			this.y = a.local(Kind.EQ);
			this.z = a.local(Kind.EQ);
			this.tId = a.local(Kind.I32);
			this.nId = a.local(Kind.I32);
			this.kind = a.local(Kind.I32);
			this.container = a.local(Kind.ARR);
			this.rest = a.local(Kind.EQ);
			this.i = a.local(Kind.I32);
			this.n = a.local(Kind.I32);
			this.newContainer = a.local(Kind.ARR);
			this.newIndex = a.local(Kind.I32);
			this.newLimit = a.local(Kind.I32);
			this.entry = a.local(Kind.ARR);
			this.str = a.local(Kind.BYTES);
			this.at = a.local(Kind.I32);
			this.last = a.local(Kind.I32);
			this.from = a.local(Kind.I32);
			this.run = a.local(Kind.I32);
			this.need = a.local(Kind.I32);
			this.b = a.local(Kind.I32);
			this.nibble = a.local(Kind.I32);
			this.iv = a.local(Kind.I64);
			this.u = a.local(Kind.I64);
			this.digits = a.local(Kind.I32);
			this.len = a.local(Kind.I32);
			this.id = a.local(Kind.I32);
			this.res = a.local(Kind.BYTES);
			this.chunks = a.local(Kind.ARR);
			this.chunk = a.local(Kind.BYTES);
		}

		void emit() {
			this.a.i32(64).arrayNewDefault(BYTES).set(this.out);
			this.a.i32(8).arrayNewDefault(ARR).i32(0).i31().i32(0).i31().arrayNewFixed(ARR, 3).set(this.chunks);
			this.a.i32(16).arrayNewDefault(ARR).set(this.stk);
			// t and null are symbols: eq compares them by interned offset
			this.a.call(WasmLispCompiler.FUNC_T_SYM).refCast(STRING).structGet(STRING, 0).set(this.tId);
			this.a.i32(-1).set(this.nId);
			this.a.get(this.nullSymbol).refTest(STRING).ifVoid();
			this.a.get(this.nullSymbol).refCast(STRING).structGet(STRING, 0).set(this.nId);
			this.a.end();
			// the innermost frame is the top's (kind 0) until a container opens
			this.a.get(this.v).set(this.x);
			this.a.loop("main");
			this.a.block("pair");
			this.a.block("next");
			emitValue();
			this.a.end();
			emitNext();
			this.a.end();
			// An object's key in y and its value in x: the key and its colon, then the
			// value.
			key();
			text(":");
			this.a.br("main");
			this.a.end();
			this.a.op(Instruction.UNREACHABLE);
		}

		private void inc(int local, int delta) {
			this.a.get(local).i32(delta).op(Instruction.I32_ADD).set(local);
		}

		// Room for the given bytes more at pos (a constant, or the local when >= 0), in
		// the chunk being written or the next one. A step reserves once what it writes;
		// the writes after it are not checked.
		private void room(int constant, int local) {
			this.a.get(this.pos);
			if (local >= 0) {
				this.a.get(local);
			}
			else {
				this.a.i32(constant);
			}
			this.a.op(Instruction.I32_ADD).get(this.out).arrayLen().op(Instruction.I32_GT_U).ifVoid();
			this.a.get(this.chunks).get(this.out).get(this.pos).i31();
			if (local >= 0) {
				this.a.get(local);
			}
			else {
				this.a.i32(constant);
			}
			this.a.i31().call(this.base + SPILL).refCast(BYTES).set(this.out);
			this.a.i32(0).set(this.pos);
			this.a.end();
		}

		private void put(int offset, int value) {
			this.a.get(this.out).get(this.pos);
			if (offset != 0) {
				this.a.i32(offset).op(Instruction.I32_ADD);
			}
			this.a.i32(value).arraySet(BYTES);
		}

		// Bytes the step's room covers.
		private void text(String text) {
			for (int c = 0; c < text.length(); c++) {
				put(c, text.charAt(c));
			}
			inc(this.pos, text.length());
		}

		private void emitValue() {
			// Past this, a value writes at most a sign and 20 digits, or a literal, or an
			// opening bracket; a string or the other function's text reserves its own.
			room(24, -1);
			this.a.block("other");
			this.a.get(this.x).isNull().ifVoid();
			text("false");
			this.a.br("next");
			this.a.end();
			this.a.get(this.x).refTest(I31).ifVoid();
			this.a.get(this.x).unI31().op(Instruction.I64_EXTEND_S_I32).set(this.iv);
			integer();
			this.a.br("next");
			this.a.end();
			this.a.get(this.x).refTest(STRING).ifVoid();
			this.a.get(this.x).refCast(STRING).structGet(STRING, 0).set(this.at);
			this.a.get(this.at).get(this.tId).op(Instruction.I32_EQ).ifVoid();
			text("true");
			this.a.br("next");
			this.a.end();
			this.a.get(this.at).get(this.nId).op(Instruction.I32_EQ).ifVoid();
			text("null");
			this.a.br("next");
			this.a.end();
			this.a.get(this.x).refCast(STRING).structGet(STRING, 2).refCast(BYTES).set(this.str);
			// a symbol, unframed
			this.a.get(this.str).i32(0).arrayGetU(BYTES).i32(QUOTE).op(Instruction.I32_NE).brIf("other");
			escaped();
			this.a.br("next");
			this.a.end();
			this.a.get(this.x).refTest(WasmLispCompiler.TYPE_BIGNUM).ifVoid();
			this.a.get(this.x).refCast(WasmLispCompiler.TYPE_BIGNUM).structGet(WasmLispCompiler.TYPE_BIGNUM, 0);
			this.a.set(this.iv);
			integer();
			this.a.br("next");
			this.a.end();
			this.a.get(this.x).refTest(CELL).ifVoid();
			cellValue();
			this.a.end();
			this.a.get(this.x).refTest(CONS).ifVoid();
			this.a.get(this.x).refCast(CONS).structGet(CONS, 1).set(this.y);
			pushFrame(LIST);
			this.a.get(this.y).set(this.rest);
			text("[");
			this.a.get(this.x).refCast(CONS).structGet(CONS, 0).set(this.x);
			this.a.br("main");
			this.a.end();
			this.a.end();
			// Anything else is the text the Lisp side answers for it.
			this.a.get(this.other).get(this.x).call(WasmLispCompiler.FUNC_DISPATCH_BASE + 1).set(this.y);
			WasmEmitHelper.emitCharvecToStrLocal(this.a.w, this.y, this.flags.charvecPossible());
			this.a.get(this.y).refCast(STRING).structGet(STRING, 2).refCast(BYTES).set(this.str);
			this.a.get(this.str).arrayLen().i32(2).op(Instruction.I32_SUB).set(this.run);
			room(0, this.run);
			this.a.get(this.out).get(this.pos).get(this.str).i32(1).get(this.run).arrayCopy(BYTES, BYTES);
			this.a.get(this.pos).get(this.run).op(Instruction.I32_ADD).set(this.pos);
			this.a.br("next");
		}

		// A TYPE_CELL: a hash table, a character vector, or a vector of its own
		// elements; anything else (a displaced view, a rank other than one) is other.
		private void cellValue() {
			this.a.get(this.x).refCast(CELL).structGet(CELL, 0).set(this.z);
			this.a.get(this.z).refTest(CONS).op(Instruction.I32_EQZ).brIf("other");
			this.a.get(this.z).refCast(CONS).structGet(CONS, 0).refTest(I31).ifVoid();
			// A hash table, its entries in insertion order: a small table's pairs, a flat
			// table's order array as it stands (a removed entry's slot nil), a copied
			// one's walk.
			this.a.get(this.z).refCast(CONS).structGet(CONS, 1).refCast(ARR).set(this.newContainer);
			this.a.get(this.newContainer);
			WasmHashTableRuntimeBuilder.emitIsSmallRecord(this.a.w);
			this.a.ifVoid();
			this.a.get(this.newContainer).i32(WasmHashTableRuntimeBuilder.S_COUNT).arrayGet(ARR).unI31();
			this.a.set(this.newLimit);
			this.a.get(this.newLimit).op(Instruction.I32_EQZ).ifVoid();
			text("{}");
			this.a.br("next");
			this.a.end();
			this.a.i32(0).set(this.newIndex);
			pushFrame(PAIRS);
			text("{");
			pairKeyAndValue();
			this.a.end();
			this.a.get(this.newContainer).arrayLen().i32(WasmHashTableRuntimeBuilder.F_LEN).op(Instruction.I32_EQ);
			this.a.ifVoid();
			this.a.get(this.newContainer).i32(WasmHashTableRuntimeBuilder.F_SIZE).arrayGet(ARR).unI31();
			this.a.set(this.newLimit);
			this.a.get(this.newContainer).i32(WasmHashTableRuntimeBuilder.F_ORDER).arrayGet(ARR).refCast(ARR);
			this.a.set(this.newContainer);
			this.a.elseArm();
			this.a.get(this.z).call(WasmLispCompiler.FUNC_HT_ENTRIES).refCast(ARR).set(this.newContainer);
			this.a.get(this.newContainer).arrayLen().set(this.newLimit);
			this.a.end();
			this.a.i32(0).set(this.newIndex);
			skipRemoved(this.newContainer, this.newIndex, this.newLimit);
			this.a.get(this.newIndex).get(this.newLimit).op(Instruction.I32_GE_U).ifVoid();
			text("{}");
			this.a.br("next");
			this.a.end();
			pushFrame(OBJECT);
			text("{");
			entryKeyAndValue();
			this.a.end();
			if (this.flags.charvecPossible()) {
				this.a.get(this.x).call(WasmLispCompiler.FUNC_CHARVEC_P).ifVoid();
				this.a.get(this.x).call(WasmLispCompiler.FUNC_CHARVEC_TO_STR).refCast(STRING).structGet(STRING, 2);
				this.a.refCast(BYTES).set(this.str);
				escaped();
				this.a.br("next");
				this.a.end();
			}
			// header (dims . (meta . data)), meta (fill-pointer . (adjustable . marker))
			this.a.get(this.z).refCast(CONS).structGet(CONS, 0).refTest(ARR).op(Instruction.I32_EQZ).brIf("other");
			this.a.get(this.z).refCast(CONS).structGet(CONS, 0).refCast(ARR).set(this.newContainer);
			this.a.get(this.newContainer).arrayLen().i32(1).op(Instruction.I32_NE).brIf("other");
			this.a.get(this.z).refCast(CONS).structGet(CONS, 1).set(this.y);
			this.a.get(this.y).refTest(CONS).op(Instruction.I32_EQZ).brIf("other");
			this.a.get(this.y).refCast(CONS).structGet(CONS, 1).refTest(ARR).op(Instruction.I32_EQZ).brIf("other");
			this.a.get(this.y).refCast(CONS).structGet(CONS, 0).set(this.z);
			this.a.get(this.z).refTest(CONS).op(Instruction.I32_EQZ).brIf("other");
			this.a.get(this.z).refCast(CONS).structGet(CONS, 0).set(this.z);
			// the length: the fill pointer when there is one
			this.a.get(this.z).refTest(I31).ifI32();
			this.a.get(this.z).unI31();
			this.a.elseArm();
			this.a.get(this.newContainer).i32(0).arrayGet(ARR).unI31();
			this.a.end();
			this.a.set(this.newLimit);
			this.a.get(this.y).refCast(CONS).structGet(CONS, 1).refCast(ARR).set(this.newContainer);
			this.a.get(this.newLimit).op(Instruction.I32_EQZ).ifVoid();
			text("[]");
			this.a.br("next");
			this.a.end();
			this.a.i32(0).set(this.newIndex);
			pushFrame(ARRAY);
			text("[");
			this.a.get(this.container).i32(0).arrayGet(ARR).set(this.x);
			this.a.br("main");
		}

		// The index past the order's nil slots, up to the limit.
		private void skipRemoved(int array, int indexLocal, int limitLocal) {
			this.a.block("live");
			this.a.loop("removed");
			this.a.get(indexLocal).get(limitLocal).op(Instruction.I32_GE_U).brIf("live");
			this.a.get(array).get(indexLocal).arrayGet(ARR).isNull().op(Instruction.I32_EQZ).brIf("live");
			inc(indexLocal, 1);
			this.a.br("removed");
			this.a.end();
			this.a.end();
		}

		// The innermost frame onto the stack, and the next one in its place: a list's
		// rest the caller sets, anything else the new container, index and limit.
		private void pushFrame(int frameKind) {
			this.a.get(this.sp).i32(4).op(Instruction.I32_ADD).get(this.stk).arrayLen().op(Instruction.I32_GT_U);
			this.a.ifVoid();
			this.a.get(this.stk).arrayLen().i32(1).op(Instruction.I32_SHL).arrayNewDefault(ARR).set(this.stackGrown);
			this.a.get(this.stackGrown).i32(0).get(this.stk).i32(0).get(this.sp).arrayCopy(ARR, ARR);
			this.a.get(this.stackGrown).set(this.stk);
			this.a.end();
			this.a.get(this.stk).get(this.sp).get(this.kind).i31().arraySet(ARR);
			this.a.get(this.stk).get(this.sp).i32(1).op(Instruction.I32_ADD);
			this.a.get(this.kind).i32(LIST).op(Instruction.I32_EQ).ifEq();
			this.a.get(this.rest);
			this.a.elseArm();
			this.a.get(this.container);
			this.a.end();
			this.a.arraySet(ARR);
			this.a.get(this.stk).get(this.sp).i32(2).op(Instruction.I32_ADD).get(this.i).i31().arraySet(ARR);
			this.a.get(this.stk).get(this.sp).i32(3).op(Instruction.I32_ADD).get(this.n).i31().arraySet(ARR);
			inc(this.sp, 4);
			this.a.i32(frameKind).set(this.kind);
			if (frameKind != LIST) {
				this.a.get(this.newContainer).set(this.container);
				this.a.get(this.newIndex).set(this.i);
				this.a.get(this.newLimit).set(this.n);
			}
		}

		// The frame under the innermost back in its place.
		private void popFrame() {
			inc(this.sp, -4);
			this.a.get(this.stk).get(this.sp).arrayGet(ARR).unI31().set(this.kind);
			this.a.get(this.kind).i32(LIST).op(Instruction.I32_EQ).ifVoid();
			this.a.get(this.stk).get(this.sp).i32(1).op(Instruction.I32_ADD).arrayGet(ARR).set(this.rest);
			this.a.elseArm();
			this.a.get(this.stk).get(this.sp).i32(1).op(Instruction.I32_ADD).arrayGet(ARR).refCastNull(ARR);
			this.a.set(this.container);
			this.a.end();
			this.a.get(this.stk).get(this.sp).i32(2).op(Instruction.I32_ADD).arrayGet(ARR).unI31().set(this.i);
			this.a.get(this.stk).get(this.sp).i32(3).op(Instruction.I32_ADD).arrayGet(ARR).unI31().set(this.n);
		}

		// After a value: close the containers it ends, then the next element of the one
		// it is in -- or the text, once the outermost closed.
		private void emitNext() {
			this.a.loop("pop");
			// a closer or a separator; a key reserves its own
			room(8, -1);
			this.a.get(this.kind).i32(ARRAY).op(Instruction.I32_EQ).ifVoid();
			inc(this.i, 1);
			this.a.get(this.i).get(this.n).op(Instruction.I32_LT_U).ifVoid();
			text(",");
			this.a.get(this.container).get(this.i).arrayGet(ARR).set(this.x);
			this.a.br("main");
			this.a.end();
			text("]");
			popFrame();
			this.a.br("pop");
			this.a.end();
			this.a.get(this.kind).i32(OBJECT).op(Instruction.I32_EQ).ifVoid();
			inc(this.i, 1);
			skipRemoved(this.container, this.i, this.n);
			this.a.get(this.i).get(this.n).op(Instruction.I32_LT_U).ifVoid();
			text(",");
			entryKeyAndValue();
			this.a.end();
			text("}");
			popFrame();
			this.a.br("pop");
			this.a.end();
			this.a.get(this.kind).i32(PAIRS).op(Instruction.I32_EQ).ifVoid();
			inc(this.i, 1);
			this.a.get(this.i).get(this.n).op(Instruction.I32_LT_U).ifVoid();
			text(",");
			pairKeyAndValue();
			this.a.end();
			text("}");
			popFrame();
			this.a.br("pop");
			this.a.end();
			this.a.get(this.kind).i32(LIST).op(Instruction.I32_EQ).ifVoid();
			this.a.get(this.rest).isNull().ifVoid();
			text("]");
			popFrame();
			this.a.br("pop");
			this.a.end();
			this.a.get(this.rest).refTest(CONS).op(Instruction.I32_EQZ).ifVoid();
			this.a.i32(ERR_IMPROPER_LIST).i31().ret();
			this.a.end();
			text(",");
			this.a.get(this.rest).refCast(CONS).structGet(CONS, 0).set(this.x);
			this.a.get(this.rest).refCast(CONS).structGet(CONS, 1).set(this.rest);
			this.a.br("main");
			this.a.end();
			// the top: the text, framed, the kept chunks first
			this.a.get(this.chunks).i32(2).arrayGet(ARR).unI31().get(this.pos).op(Instruction.I32_ADD);
			this.a.i32(2).op(Instruction.I32_ADD).tee(this.len).arrayNewDefault(BYTES).set(this.res);
			this.a.get(this.res).i32(0).i32(QUOTE).arraySet(BYTES);
			this.a.get(this.res).get(this.len).i32(1).op(Instruction.I32_SUB).i32(QUOTE).arraySet(BYTES);
			this.a.get(this.chunks).i32(0).arrayGet(ARR).refCast(ARR).set(this.newContainer);
			this.a.get(this.chunks).i32(1).arrayGet(ARR).unI31().set(this.n);
			this.a.i32(1).set(this.at);
			this.a.i32(0).set(this.i);
			this.a.block("joined");
			this.a.loop("join");
			this.a.get(this.i).get(this.n).op(Instruction.I32_GE_U).brIf("joined");
			this.a.get(this.newContainer).get(this.i).arrayGet(ARR).refCast(BYTES).set(this.chunk);
			this.a.get(this.newContainer)
				.get(this.i)
				.i32(1)
				.op(Instruction.I32_ADD)
				.arrayGet(ARR)
				.unI31()
				.set(this.run);
			this.a.get(this.res).get(this.at).get(this.chunk).i32(0).get(this.run).arrayCopy(BYTES, BYTES);
			this.a.get(this.at).get(this.run).op(Instruction.I32_ADD).set(this.at);
			inc(this.i, 2);
			this.a.br("join");
			this.a.end();
			this.a.end();
			this.a.get(this.res).get(this.at).get(this.out).i32(0).get(this.pos).arrayCopy(BYTES, BYTES);
			WasmStringRuntimeBuilder.emitRuntimeString(this.a.w, this.len, this.res, this.id);
			this.a.ret();
			this.a.end();
		}

		// The i-th pair of the small record the frame walks, its key in y and its value
		// in x, to the key's writing.
		private void pairKeyAndValue() {
			this.a.get(this.container).get(this.i).i32(1).op(Instruction.I32_SHL);
			this.a.i32(WasmHashTableRuntimeBuilder.S_PAIRS).op(Instruction.I32_ADD).arrayGet(ARR).set(this.y);
			this.a.get(this.container).get(this.i).i32(1).op(Instruction.I32_SHL);
			this.a.i32(WasmHashTableRuntimeBuilder.S_PAIRS + 1).op(Instruction.I32_ADD).arrayGet(ARR).set(this.x);
			this.a.br("pair");
		}

		// The i-th entry of the order the frame walks, its key in y (an equalp entry's
		// seventh slot is the key as written) and its value in x, to the key's writing.
		private void entryKeyAndValue() {
			this.a.get(this.container).get(this.i).arrayGet(ARR).refCast(ARR).set(this.entry);
			this.a.get(this.entry).i32(WasmHashTableRuntimeBuilder.E_KEY).arrayGet(ARR).set(this.y);
			if (this.flags.equalpTables()) {
				this.a.get(this.entry).arrayLen().i32(WasmHashTableRuntimeBuilder.E_ORIG + 1).op(Instruction.I32_EQ);
				this.a.ifVoid();
				this.a.get(this.entry).i32(WasmHashTableRuntimeBuilder.E_ORIG).arrayGet(ARR).set(this.y);
				this.a.end();
			}
			this.a.get(this.entry).i32(WasmHashTableRuntimeBuilder.E_VALUE).arrayGet(ARR).set(this.x);
			this.a.br("pair");
		}

		// The key in y as a JSON string: a string as it is, anything else through
		// key-name first.
		private void key() {
			this.a.block("key-bytes");
			this.a.block("key-string");
			this.a.get(this.y).refTest(STRING).ifVoid();
			this.a.get(this.y).refCast(STRING).structGet(STRING, 2).refCast(BYTES).set(this.str);
			this.a.get(this.str).i32(0).arrayGetU(BYTES).i32(QUOTE).op(Instruction.I32_EQ).brIf("key-bytes");
			this.a.end();
			if (this.flags.charvecPossible()) {
				this.a.get(this.y).refTest(CELL).ifVoid();
				this.a.get(this.y).call(WasmLispCompiler.FUNC_CHARVEC_P).brIf("key-string");
				this.a.end();
			}
			this.a.get(this.keyName).get(this.y).call(WasmLispCompiler.FUNC_DISPATCH_BASE + 1).set(this.y);
			this.a.end();
			WasmEmitHelper.emitCharvecToStrLocal(this.a.w, this.y, this.flags.charvecPossible());
			this.a.get(this.y).refCast(STRING).structGet(STRING, 2).refCast(BYTES).set(this.str);
			this.a.end();
			escaped();
		}

		// The string in str, quoted, its quote, backslash and control characters
		// escaped as %json-out-string escapes them; each run between them one copy. The
		// room taken covers the string unescaped and 6 bytes after it; an escape takes
		// what it adds.
		private void escaped() {
			this.a.get(this.str).arrayLen().i32(1).op(Instruction.I32_SUB).tee(this.last);
			this.a.i32(7).op(Instruction.I32_ADD).set(this.need);
			room(0, this.need);
			put(0, QUOTE);
			inc(this.pos, 1);
			this.a.i32(1).tee(this.at).set(this.from);
			this.a.block("escaped");
			this.a.loop("more");
			this.a.block("special");
			this.a.loop("scan");
			this.a.get(this.str).get(this.at).arrayGetU(BYTES).tee(this.b);
			inc(this.at, 1);
			Map<Integer, String> specials = cases("special", QUOTE, BACKSLASH);
			for (int c = 0; c < 32; c++) {
				specials.put(c, "special");
			}
			this.a.brTable("scan", specials, BACKSLASH + 1);
			this.a.end();
			this.a.end();
			// the special byte b at at - 1; the frame's closing quote ends the content
			this.a.get(this.at).get(this.last).op(Instruction.I32_GT_U).brIf("escaped");
			escapeRoom();
			flush();
			this.a.block("escape-done");
			this.a.get(this.b).i32(QUOTE).op(Instruction.I32_EQ).ifVoid();
			text("\\\"");
			this.a.br("escape-done");
			this.a.end();
			this.a.get(this.b).i32(BACKSLASH).op(Instruction.I32_EQ).ifVoid();
			text("\\\\");
			this.a.br("escape-done");
			this.a.end();
			control();
			this.a.end();
			this.a.get(this.at).set(this.from);
			this.a.br("more");
			this.a.end();
			this.a.end();
			// the content's last run: up to the frame's quote, at - 1
			flush();
			put(0, QUOTE);
			inc(this.pos, 1);
		}

		// Room for the run not yet copied, an escape of up to 6 bytes, the content past
		// it, the closing quote and the 6 bytes after.
		private void escapeRoom() {
			this.a.get(this.last).get(this.from).op(Instruction.I32_SUB).i32(12).op(Instruction.I32_ADD).set(this.need);
			room(0, this.need);
		}

		// The run [from, at - 1) copied.
		private void flush() {
			this.a.get(this.at).i32(1).op(Instruction.I32_SUB).get(this.from).op(Instruction.I32_SUB).set(this.run);
			this.a.get(this.out).get(this.pos).get(this.str).get(this.from).get(this.run).arrayCopy(BYTES, BYTES);
			this.a.get(this.pos).get(this.run).op(Instruction.I32_ADD).set(this.pos);
		}

		// A control character in b: backslash and b, f, n, r or t, or backslash-u 00XX
		// in lower-case hex.
		private void control() {
			put(0, BACKSLASH);
			this.a.block("control-done");
			this.a.block("u");
			this.a.block("t");
			this.a.block("r");
			this.a.block("n");
			this.a.block("f");
			this.a.block("bs");
			this.a.get(this.b).brTable("u", Map.of(8, "bs", 12, "f", 10, "n", 13, "r", 9, "t"), 14);
			this.a.end();
			twoCharEscape('b');
			this.a.end();
			twoCharEscape('f');
			this.a.end();
			twoCharEscape('n');
			this.a.end();
			twoCharEscape('r');
			this.a.end();
			twoCharEscape('t');
			this.a.end();
			put(1, 'u');
			put(2, '0');
			put(3, '0');
			hexDigit(4, 4);
			hexDigit(5, 0);
			inc(this.pos, 6);
			this.a.end();
		}

		private void twoCharEscape(char c) {
			put(1, c);
			inc(this.pos, 2);
			this.a.br("control-done");
		}

		private void hexDigit(int offset, int shift) {
			this.a.get(this.out).get(this.pos).i32(offset).op(Instruction.I32_ADD);
			this.a.get(this.b);
			if (shift > 0) {
				this.a.i32(shift).op(Instruction.I32_SHR_U);
			}
			this.a.i32(15).op(Instruction.I32_AND).tee(this.nibble).i32(10).op(Instruction.I32_LT_U).ifI32();
			this.a.get(this.nibble).i32('0').op(Instruction.I32_ADD);
			this.a.elseArm();
			this.a.get(this.nibble).i32('a' - 10).op(Instruction.I32_ADD);
			this.a.end();
			this.a.arraySet(BYTES);
		}

		// The decimal digits of the integer in iv, in the value step's room.
		private void integer() {
			this.a.get(this.iv).i64(0).op(Instruction.I64_LT_S).ifVoid();
			put(0, '-');
			inc(this.pos, 1);
			// as an unsigned magnitude, which the least i64 has too
			this.a.i64(0).get(this.iv).op(Instruction.I64_SUB).set(this.iv);
			this.a.end();
			this.a.i32(1).set(this.digits);
			this.a.get(this.iv).set(this.u);
			this.a.block("counted");
			this.a.loop("count");
			this.a.get(this.u).i64(10).op(Instruction.I64_LT_U).brIf("counted");
			this.a.get(this.u).i64(10).op(Instruction.I64_DIV_U).set(this.u);
			inc(this.digits, 1);
			this.a.br("count");
			this.a.end();
			this.a.end();
			this.a.get(this.pos).get(this.digits).op(Instruction.I32_ADD).tee(this.pos).set(this.at);
			this.a.loop("digit");
			inc(this.at, -1);
			this.a.get(this.out).get(this.at);
			this.a.get(this.iv).i64(10).op(Instruction.I64_REM_U).op(Instruction.I32_WRAP_I64).i32('0');
			this.a.op(Instruction.I32_ADD).arraySet(BYTES);
			this.a.get(this.iv).i64(10).op(Instruction.I64_DIV_U).tee(this.iv).i64(0).op(Instruction.I64_NE);
			this.a.brIf("digit");
			this.a.end();
		}

	}

	// --- a small assembler -------------------------------------------------------

	/** The kinds of local the bodies declare. */
	enum Kind {

		EQ, BYTES, ARR, I32, I64, F64

	}

	/**
	 * A function body: its locals as they are asked for, and branches by the name of the
	 * block or loop they leave or repeat, which the depth is counted from. The span scan
	 * ({@link WasmJsonSpanRuntimeBuilder}) writes its body with it too.
	 */
	static final class Asm {

		// ref.cast to a NULLABLE reference (0x16, REF_CAST, traps on null).
		private static final int REF_CAST_NULL = 0x17;

		private final ByteArrayOutputStream code = new am.ik.wasm.UnsynchronizedByteArrayOutputStream();

		final WasmWriter w = new WasmWriter(this.code);

		private final int params;

		private final List<Kind> locals = new ArrayList<>();

		// innermost last; null for an if, which a branch never names
		private final List<String> labels = new ArrayList<>();

		Asm(int params) {
			this.params = params;
		}

		int local(Kind kind) {
			this.locals.add(kind);
			return this.params + this.locals.size() - 1;
		}

		byte[] finish() {
			if (!this.labels.isEmpty()) {
				throw new IllegalStateException("unclosed blocks " + this.labels);
			}
			this.w.write(Instruction.END);
			ByteArrayOutputStream body = new am.ik.wasm.UnsynchronizedByteArrayOutputStream();
			WasmWriter header = new WasmWriter(body);
			int groups = 0;
			for (int i = 0; i < this.locals.size(); i++) {
				if (i == 0 || this.locals.get(i) != this.locals.get(i - 1)) {
					groups++;
				}
			}
			header.writeUnsignedLeb128(groups);
			int i = 0;
			while (i < this.locals.size()) {
				int run = 1;
				while (i + run < this.locals.size() && this.locals.get(i + run) == this.locals.get(i)) {
					run++;
				}
				header.writeUnsignedLeb128(run);
				switch (this.locals.get(i)) {
					case EQ -> header.writeRefType(true, Type.EQ.code());
					case BYTES -> header.writeRefType(true, BYTES);
					case ARR -> header.writeRefType(true, ARR);
					case I32 -> header.write(Type.I32);
					case I64 -> header.write(Type.I64);
					case F64 -> header.write(Type.F64);
				}
				i += run;
			}
			try {
				this.code.writeTo(body);
			}
			catch (IOException ex) {
				throw new UncheckedIOException(ex);
			}
			return body.toByteArray();
		}

		private int depth(String label) {
			for (int i = this.labels.size() - 1; i >= 0; i--) {
				if (label.equals(this.labels.get(i))) {
					return this.labels.size() - 1 - i;
				}
			}
			throw new IllegalStateException("no enclosing block " + label);
		}

		Asm op(int opcode) {
			this.w.write(opcode);
			return this;
		}

		private Asm gc(int sub) {
			this.w.write(Instruction.GC_PREFIX);
			this.w.writeUnsignedLeb128(sub);
			return this;
		}

		Asm get(int local) {
			this.w.write(Instruction.GET_LOCAL);
			this.w.writeUnsignedLeb128(local);
			return this;
		}

		Asm set(int local) {
			this.w.write(Instruction.SET_LOCAL);
			this.w.writeUnsignedLeb128(local);
			return this;
		}

		Asm tee(int local) {
			this.w.write(Instruction.TEE_LOCAL);
			this.w.writeUnsignedLeb128(local);
			return this;
		}

		Asm i32(int value) {
			this.w.write(Instruction.I32_CONST);
			this.w.writeSignedLeb128(value);
			return this;
		}

		Asm i64(long value) {
			this.w.write(Instruction.I64_CONST);
			this.w.writeSignedLeb128(value);
			return this;
		}

		Asm f64(double value) {
			this.w.write(Instruction.F64_CONST);
			this.w.writeF64(value);
			return this;
		}

		Asm call(int function) {
			this.w.write(Instruction.CALL);
			this.w.writeUnsignedLeb128(function);
			return this;
		}

		Asm ret() {
			return op(Instruction.RETURN);
		}

		Asm block(String label) {
			this.w.write(Instruction.BLOCK);
			this.w.write(0x40);
			this.labels.add(label);
			return this;
		}

		Asm loop(String label) {
			this.w.write(Instruction.LOOP);
			this.w.write(0x40);
			this.labels.add(label);
			return this;
		}

		Asm ifVoid() {
			this.w.write(Instruction.IF);
			this.w.write(0x40);
			this.labels.add(null);
			return this;
		}

		Asm ifI32() {
			this.w.write(Instruction.IF);
			this.w.write(Type.I32);
			this.labels.add(null);
			return this;
		}

		Asm ifEq() {
			this.w.write(Instruction.IF);
			this.w.writeRefType(true, Type.EQ.code());
			this.labels.add(null);
			return this;
		}

		Asm elseArm() {
			return op(Instruction.ELSE);
		}

		Asm end() {
			this.labels.remove(this.labels.size() - 1);
			return op(Instruction.END);
		}

		Asm br(String label) {
			this.w.write(Instruction.BR);
			this.w.writeUnsignedLeb128(depth(label));
			return this;
		}

		Asm brIf(String label) {
			this.w.write(Instruction.BR_IF);
			this.w.writeUnsignedLeb128(depth(label));
			return this;
		}

		// br_table over [0, size): each case's label, the default's elsewhere and past
		// the end.
		Asm brTable(String otherwise, Map<Integer, String> cases, int size) {
			this.w.write(Instruction.BR_TABLE);
			this.w.writeUnsignedLeb128(size);
			for (int i = 0; i < size; i++) {
				this.w.writeUnsignedLeb128(depth(cases.getOrDefault(i, otherwise)));
			}
			this.w.writeUnsignedLeb128(depth(otherwise));
			return this;
		}

		Asm refNull() {
			this.w.write(Instruction.REF_NULL);
			this.w.writeHeapType(Type.EQ.code());
			return this;
		}

		Asm isNull() {
			return op(Instruction.REF_IS_NULL);
		}

		Asm refTest(int heapType) {
			gc(Instruction.REF_TEST);
			this.w.writeHeapType(heapType);
			return this;
		}

		Asm refCast(int heapType) {
			gc(Instruction.REF_CAST);
			this.w.writeHeapType(heapType);
			return this;
		}

		Asm refCastNull(int heapType) {
			gc(REF_CAST_NULL);
			this.w.writeHeapType(heapType);
			return this;
		}

		Asm i31() {
			return gc(Instruction.I31_REF_NEW);
		}

		Asm unI31() {
			return refCast(I31).gc(Instruction.I31_GET_S);
		}

		Asm structGet(int type, int field) {
			gc(Instruction.STRUCT_GET);
			this.w.writeUnsignedLeb128(type);
			this.w.writeUnsignedLeb128(field);
			return this;
		}

		Asm structNew(int type) {
			gc(Instruction.STRUCT_NEW);
			this.w.writeUnsignedLeb128(type);
			return this;
		}

		Asm arrayNewDefault(int type) {
			gc(Instruction.ARRAY_NEW_DEFAULT);
			this.w.writeUnsignedLeb128(type);
			return this;
		}

		Asm arrayNewFixed(int type, int count) {
			gc(Instruction.ARRAY_NEW_FIXED);
			this.w.writeUnsignedLeb128(type);
			this.w.writeUnsignedLeb128(count);
			return this;
		}

		Asm arrayGet(int type) {
			gc(Instruction.ARRAY_GET);
			this.w.writeUnsignedLeb128(type);
			return this;
		}

		Asm arrayGetU(int type) {
			gc(Instruction.ARRAY_GET_U);
			this.w.writeUnsignedLeb128(type);
			return this;
		}

		Asm arraySet(int type) {
			gc(Instruction.ARRAY_SET);
			this.w.writeUnsignedLeb128(type);
			return this;
		}

		Asm arrayLen() {
			return gc(Instruction.ARRAY_LEN);
		}

		Asm arrayCopy(int destination, int source) {
			gc(Instruction.ARRAY_COPY);
			this.w.writeUnsignedLeb128(destination);
			this.w.writeUnsignedLeb128(source);
			return this;
		}

		// Linear memory, at the byte alignment an address anywhere takes.

		Asm load8u() {
			this.w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);
			return this;
		}

		Asm load64() {
			this.w.write(Instruction.I64_LOAD, 0x00, 0x00);
			return this;
		}

		Asm store8() {
			this.w.write(Instruction.I32_STORE8, 0x00, 0x00);
			return this;
		}

		// memory.size of memory 0, in pages
		Asm memorySize() {
			this.w.write(Instruction.CURRENT_MEMORY, 0x00);
			return this;
		}

	}

}
