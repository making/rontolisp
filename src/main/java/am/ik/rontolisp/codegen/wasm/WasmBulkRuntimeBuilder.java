package am.ik.rontolisp.codegen.wasm;

import am.ik.rontolisp.codegen.wasm.WasmJsonRuntimeBuilder.Asm;
import am.ik.rontolisp.codegen.wasm.WasmJsonRuntimeBuilder.Kind;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;
import org.jspecify.annotations.Nullable;

/**
 * Builds the wasm-GC halves of the three primitives that take a whole sequence at once:
 * {@code %string-split}, {@code %string-join} and {@code %sort-by-keys}
 * ({@code .kb/bulk-primitives.md}), and {@code _str_cat}, what a chain of
 * {@code %string-concat} is compiled to ({@link WasmStringConcatCompiler}). Each is one
 * function that walks the bytes of a {@code $str_bytes} array or the storage of a general
 * vector directly, where the Lisp loop it stands in for calls a helper per piece, per
 * element or per comparison. Each answers nil -- nothing decided -- for an operand it has
 * no arm for, and the caller's loop runs; the interpreter and the JVM answer constant
 * nil.
 */
final class WasmBulkRuntimeBuilder {

	/** {@code _str_split (string, separator, limit) -> vector or nil}. */
	static final int SPLIT = 0;

	/** {@code _str_join (strings, separator) -> string or nil}. */
	static final int JOIN = 1;

	/** {@code _sort_keys (keys, elements, out, descending) -> out or nil}. */
	static final int SORT = 2;

	/** {@code _str_cat (a, b, c, d) -> string}: up to four pieces as one string. */
	static final int CAT = 3;

	/**
	 * {@code _lit_init () -> ()}: the module's start function, which builds each literal
	 * a function body names into its global ({@code QuoteGlobals.literalInitBodies}). Its
	 * signature and body are the compiler's, not this class's.
	 */
	static final int LIT_INIT = 4;

	/** {@code _str_count (needle, string, start, end) -> fixnum or nil}. */
	static final int COUNT = 5;

	/**
	 * The functions {@code _lit_init} goes on in, each calling the next, where a module
	 * names more literals than one body should hold ({@link #LIT_INIT_PARTS} in all,
	 * {@code _lit_init} the first): a function body is bounded for the engine that
	 * compiles it ({@code .kb/wasm-function-body-size.md}).
	 */
	static final int LIT_INIT_MORE = 6;

	static final int LIT_INIT_PARTS = 8;

	static final int FUNC_COUNT = LIT_INIT_MORE + LIT_INIT_PARTS - 1;

	/**
	 * Which of {@code _lit_init}'s parts the block's function at an offset is.
	 * @param i the offset
	 * @return the part, 0 for {@code _lit_init} itself, or -1 for another function
	 */
	static int litInitPart(int i) {
		if (i == LIT_INIT) {
			return 0;
		}
		return i >= LIT_INIT_MORE && i < FUNC_COUNT ? i - LIT_INIT_MORE + 1 : -1;
	}

	private static final int BYTES = WasmLispCompiler.TYPE_STR_BYTES;

	private static final int ARR = WasmLispCompiler.TYPE_HASH_BUCKETS;

	private static final int INTS = WasmLispCompiler.TYPE_I32ARR;

	private static final int STRING = WasmLispCompiler.TYPE_STRING;

	private static final int CONS = WasmLispCompiler.TYPE_CONS;

	private static final int CELL = WasmLispCompiler.TYPE_CELL;

	private static final int I31 = Type.I31.code();

	private static final int QUOTE = '"';

	// TYPE_STRING's character count, -1 until counted (.kb/wasm-gc-strings.md).
	private static final int CHAR_COUNT = 5;

	// A key's prefix holds this many bytes of a string; its last byte is the length,
	// which saturates one past them.
	private static final int PREFIX_BYTES = 7;

	private WasmBulkRuntimeBuilder() {
	}

	/**
	 * What the bodies read of the module they are built into.
	 *
	 * @param identityHash whether a cons and a cell carry the identity-hash slot
	 * @param charvecPossible whether the module can hold a mutable character vector
	 * @param speed whether the module trades bytes for speed
	 */
	record Flags(boolean identityHash, boolean charvecPossible, boolean speed) {
	}

	/**
	 * The signature of the block's function at an offset.
	 * @param i the offset
	 * @return its type index
	 */
	static int typeIndexOf(int i) {
		return switch (i) {
			case SPLIT -> WasmLispCompiler.TYPE_CALLABLE_BASE + 2;
			case JOIN -> WasmLispCompiler.TYPE_CALLABLE_BASE + 1;
			case SORT, CAT, COUNT -> WasmLispCompiler.TYPE_CALLABLE_BASE + 3;
			default -> throw new IllegalArgumentException("no bulk runtime function " + i);
		};
	}

	/**
	 * The body of the block's function at an offset.
	 * @param i the offset
	 * @param flags the module's flags
	 * @return the encoded body
	 */
	static byte[] build(int i, Flags flags) {
		return switch (i) {
			case SPLIT -> buildSplit(flags);
			case JOIN -> buildJoin(flags);
			case SORT -> buildSort();
			case CAT -> buildCat();
			case COUNT -> buildCount();
			default -> throw new IllegalArgumentException("no bulk runtime function " + i);
		};
	}

	// The framed bytes of the immutable string in a local, into bytes; a branch to fail
	// for anything else (a symbol's name is the same struct without the frame, and may
	// be empty).
	private static void stringBytes(Asm a, int value, int bytes, String fail) {
		a.get(value).refTest(STRING).op(Instruction.I32_EQZ).brIf(fail);
		a.get(value);
		WasmEmitHelper.emitStrBytesArray(a.w);
		a.tee(bytes).arrayLen().op(Instruction.I32_EQZ).brIf(fail);
		a.get(bytes).i32(0).arrayGetU(BYTES).i32(QUOTE).op(Instruction.I32_NE).brIf(fail);
	}

	// The storage and the length of the general vector in a local -- a rank-1 array with
	// storage of its own, whose cell names both
	// (WasmArrayRuntimeBuilder.buildVecSyncBody)
	// -- or a branch to fail. The length is the fill pointer, else the storage's own.
	private static void vectorStorage(Asm a, int value, int storage, int n, int scratch, String fail) {
		a.get(value).refTest(CELL).op(Instruction.I32_EQZ).brIf(fail);
		a.get(value)
			.refCast(CELL)
			.structGet(CELL, WasmLispCompiler.CELL_STORAGE_FIELD)
			.tee(storage)
			.isNull()
			.brIf(fail);
		a.get(value).refCast(CELL).structGet(CELL, WasmLispCompiler.CELL_META_FIELD).structGet(CONS, 0).tee(scratch);
		a.isNull().ifI32().get(storage).arrayLen().elseArm().get(scratch).unI31().end().set(n);
	}

	// dst[dpos ..] = src[spos .. spos + count), all i32 locals but the arrays. One
	// array.copy whatever the count: a loop for a few bytes was measured no faster
	// (.kb/bulk-primitives.md).
	private static void copyBytes(Asm a, int dst, int dpos, int src, int spos, int count) {
		a.get(dst).get(dpos).get(src).get(spos).get(count).arrayCopy(BYTES, BYTES);
	}

	private static void increment(Asm a, int local) {
		a.get(local).i32(1).op(Instruction.I32_ADD).set(local);
	}

	// --- split -----------------------------------------------------------------

	/** The locals of {@code _str_split}. */
	private record Split(int sb, int pb, int piece, int out, int end, int m, int limit, int first, int last, int i,
			int j, int from, int count, int k, int len, int framed, int n, int id, int cc, int ascii, int one) {
	}

	// _str_split (string, separator, limit): the pieces of the string between the
	// leftmost LIMIT occurrences of the separator that do not overlap (all of them when
	// LIMIT is negative), in a fresh vector as (make-array n :adjustable t :fill-pointer
	// n) makes one. Nil for an operand that is no string, an empty separator or a limit
	// that is no fixnum. The occurrences are counted first, so the vector is made at its
	// size and each piece is one byte array filled from the string's.
	private static byte[] buildSplit(Flags flags) {
		Asm a = new Asm(3);
		Split s = new Split(a.local(Kind.BYTES), a.local(Kind.BYTES), a.local(Kind.BYTES), a.local(Kind.ARR),
				a.local(Kind.I32), a.local(Kind.I32), a.local(Kind.I32), a.local(Kind.I32), a.local(Kind.I32),
				a.local(Kind.I32), a.local(Kind.I32), a.local(Kind.I32), a.local(Kind.I32), a.local(Kind.I32),
				a.local(Kind.I32), a.local(Kind.I32), a.local(Kind.I32), a.local(Kind.I32), a.local(Kind.I32),
				a.local(Kind.I32), a.local(Kind.I32));
		a.block("fail");
		WasmEmitHelper.emitCharvecToStrLocal(a.w, 0, flags.charvecPossible());
		WasmEmitHelper.emitCharvecToStrLocal(a.w, 1, flags.charvecPossible());
		stringBytes(a, 0, s.sb, "fail");
		stringBytes(a, 1, s.pb, "fail");
		a.get(2).refTest(I31).op(Instruction.I32_EQZ).brIf("fail");
		a.get(2).unI31().tee(s.limit).i32(0).op(Instruction.I32_LT_S);
		a.ifVoid().i32(Integer.MAX_VALUE).set(s.limit).end();
		a.get(s.sb).arrayLen().i32(1).op(Instruction.I32_SUB).set(s.end);
		a.get(s.pb).arrayLen().i32(2).op(Instruction.I32_SUB).tee(s.m).i32(0).op(Instruction.I32_LE_S).brIf("fail");
		a.get(s.pb).i32(1).arrayGetU(BYTES).set(s.first);
		a.get(s.end).get(s.m).op(Instruction.I32_SUB).set(s.last);
		a.i32(1).set(s.one);
		// a piece of a string whose every character is one byte is counted by its bytes
		a.get(0).refCast(STRING).structGet(STRING, CHAR_COUNT);
		a.get(s.end).i32(1).op(Instruction.I32_SUB).op(Instruction.I32_EQ).set(s.ascii);
		scan(a, s, () -> a.get(s.count).get(s.limit).op(Instruction.I32_GE_U), () -> increment(a, s.count));
		a.get(s.count).i32(1).op(Instruction.I32_ADD).tee(s.n).arrayNewDefault(ARR).set(s.out);
		a.i32(1).set(s.from);
		scan(a, s, () -> a.get(s.k).get(s.count).op(Instruction.I32_GE_U), () -> {
			piece(a, s, s.i, flags.speed());
			a.get(s.i).get(s.m).op(Instruction.I32_ADD).set(s.from);
		});
		piece(a, s, s.end, flags.speed());
		// (dims . ((fill-pointer . (adjustable . 0)) . data)), as make-array builds it
		a.get(s.n).i31().arrayNewFixed(ARR, 1);
		a.get(s.n).i31();
		a.call(WasmLispCompiler.FUNC_T_SYM);
		a.i32(0).i31();
		WasmEmitHelper.emitNewCons(a.w, flags.identityHash());
		WasmEmitHelper.emitNewCons(a.w, flags.identityHash());
		a.get(s.out);
		WasmEmitHelper.emitNewCons(a.w, flags.identityHash());
		WasmEmitHelper.emitNewCons(a.w, flags.identityHash());
		WasmEmitHelper.emitNewArrayCell(a.w, flags.identityHash());
		a.ret();
		a.end();
		a.refNull();
		return a.finish();
	}

	// One walk of the string from its first byte: at each occurrence of the separator
	// onMatch runs with i at its first byte, and the walk goes on behind it; it ends
	// where no occurrence fits any more or when stop answers true.
	private static void scan(Asm a, Split s, Runnable stop, Runnable onMatch) {
		a.i32(1).set(s.i);
		walk(a, new Walk(s.sb, s.pb, s.i, s.j, s.m, s.first, s.last), stop, onMatch);
	}

	/**
	 * What a walk over a string's bytes for a needle reads: the two byte arrays, the
	 * position and a counter, the needle's bytes, its first byte, and the last position
	 * an occurrence can start at.
	 */
	private record Walk(int sb, int pb, int i, int j, int m, int first, int last) {
	}

	// The walk of a string's bytes from the position in i: at each occurrence of the
	// needle onMatch runs with i at its first byte, and the walk goes on behind it; it
	// ends where no occurrence fits any more, or when stop (which may be null) answers
	// true.
	private static void walk(Asm a, Walk s, @Nullable Runnable stop, Runnable onMatch) {
		a.block("scanned").loop("scan");
		a.get(s.i).get(s.last).op(Instruction.I32_GT_S).brIf("scanned");
		if (stop != null) {
			stop.run();
			a.brIf("scanned");
		}
		a.get(s.sb).get(s.i).arrayGetU(BYTES).get(s.first).op(Instruction.I32_EQ).ifVoid();
		a.i32(1).set(s.j);
		a.block("differs").loop("rest");
		a.get(s.j).get(s.m).op(Instruction.I32_GE_U).ifVoid();
		onMatch.run();
		a.get(s.i).get(s.m).op(Instruction.I32_ADD).set(s.i);
		a.br("scan");
		a.end();
		a.get(s.sb).get(s.i).get(s.j).op(Instruction.I32_ADD).arrayGetU(BYTES);
		a.get(s.pb).get(s.j).i32(1).op(Instruction.I32_ADD).arrayGetU(BYTES);
		a.op(Instruction.I32_NE).brIf("differs");
		increment(a, s.j);
		a.br("rest");
		a.end().end();
		a.end();
		increment(a, s.i);
		a.br("scan");
		a.end().end();
	}

	// --- count -----------------------------------------------------------------

	// _str_count (needle, string, start, end): the occurrences of the needle in the
	// string between two character indices the caller checked, leftmost first and none
	// overlapping another, in one walk of the bytes. Nil for an operand that is no
	// immutable string, an empty needle or a bound that is no fixnum. A bound is its
	// byte less the opening quote in a string counted at its bytes.
	private static byte[] buildCount() {
		Asm a = new Asm(4);
		Walk s = new Walk(a.local(Kind.BYTES), a.local(Kind.BYTES), a.local(Kind.I32), a.local(Kind.I32),
				a.local(Kind.I32), a.local(Kind.I32), a.local(Kind.I32));
		int count = a.local(Kind.I32);
		a.block("fail");
		stringBytes(a, 0, s.pb, "fail");
		stringBytes(a, 1, s.sb, "fail");
		a.get(2).refTest(I31).get(3).refTest(I31).op(Instruction.I32_AND).op(Instruction.I32_EQZ).brIf("fail");
		a.get(s.pb).arrayLen().i32(2).op(Instruction.I32_SUB).tee(s.m).i32(0).op(Instruction.I32_LE_S).brIf("fail");
		a.get(s.pb).i32(1).arrayGetU(BYTES).set(s.first);
		a.get(1).refCast(STRING).structGet(STRING, CHAR_COUNT);
		a.get(s.sb).arrayLen().i32(2).op(Instruction.I32_SUB).op(Instruction.I32_EQ).ifVoid();
		a.get(2).unI31().i32(1).op(Instruction.I32_ADD).set(s.i);
		a.get(3).unI31().i32(1).op(Instruction.I32_ADD).get(s.m).op(Instruction.I32_SUB).set(s.last);
		a.elseArm();
		a.get(1).get(2).unI31().call(WasmLispCompiler.FUNC_STR_CHAR_BYTE_OFFSET).set(s.i);
		a.get(1).get(3).unI31().call(WasmLispCompiler.FUNC_STR_CHAR_BYTE_OFFSET);
		a.get(s.m).op(Instruction.I32_SUB).set(s.last);
		a.end();
		walk(a, s, null, () -> increment(a, count));
		a.get(count).i31().ret();
		a.end();
		a.refNull();
		return a.finish();
	}

	// A piece of at most this many bytes is made with its bytes in hand.
	private static final int SHORT_PIECE = 8;

	// out[k++] = the bytes of the string from `from` short of the local `to`, framed. A
	// short piece is one array.new_fixed over the bytes read, at the speed levels: an
	// array made empty and filled by array.copy is two calls into the engine (its fill
	// and its copy), which were most of what a word of a split cost.
	private static void piece(Asm a, Split s, int to, boolean speed) {
		a.get(to).get(s.from).op(Instruction.I32_SUB).tee(s.len);
		a.i32(2).op(Instruction.I32_ADD).set(s.framed);
		a.block("made");
		if (speed) {
			java.util.Map<Integer, String> cases = new java.util.HashMap<>();
			a.block("long");
			for (int n = SHORT_PIECE; n >= 0; n--) {
				a.block("short" + n);
				cases.put(n, "short" + n);
			}
			a.get(s.len).brTable("long", cases, SHORT_PIECE + 1);
			for (int n = 0; n <= SHORT_PIECE; n++) {
				a.end();
				a.i32(QUOTE);
				for (int j = 0; j < n; j++) {
					a.get(s.sb).get(s.from);
					if (j > 0) {
						a.i32(j).op(Instruction.I32_ADD);
					}
					a.arrayGetU(BYTES);
				}
				a.i32(QUOTE).arrayNewFixed(BYTES, n + 2).set(s.piece);
				a.br("made");
			}
			a.end();
		}
		a.get(s.framed).arrayNewDefault(BYTES).set(s.piece);
		a.get(s.piece).i32(0).i32(QUOTE).arraySet(BYTES);
		a.get(s.piece).get(s.len).i32(1).op(Instruction.I32_ADD).i32(QUOTE).arraySet(BYTES);
		copyBytes(a, s.piece, s.one, s.sb, s.from, s.len);
		a.end();
		a.get(s.len).i32(-1).get(s.ascii).op(Instruction.SELECT).set(s.cc);
		a.get(s.out).get(s.k);
		WasmStringRuntimeBuilder.emitCountedRuntimeString(a.w, s.framed, s.piece, s.id, s.cc);
		a.arraySet(ARR);
		increment(a, s.k);
	}

	// --- join ------------------------------------------------------------------

	// _str_join (strings, separator): the strings of a general vector with the separator
	// between them, as one string: its bytes counted first, then each copied once. Nil
	// when the vector is not one with storage of its own, or an element or the separator
	// is no immutable string. The characters are counted when every operand's are.
	private static byte[] buildJoin(Flags flags) {
		Asm a = new Asm(2);
		int pb = a.local(Kind.BYTES);
		int xb = a.local(Kind.BYTES);
		int out = a.local(Kind.BYTES);
		int items = a.local(Kind.ARR);
		int x = a.local(Kind.EQ);
		int n = a.local(Kind.I32);
		int i = a.local(Kind.I32);
		int m = a.local(Kind.I32);
		int total = a.local(Kind.I32);
		int pos = a.local(Kind.I32);
		int len = a.local(Kind.I32);
		int chars = a.local(Kind.I32);
		int c = a.local(Kind.I32);
		int id = a.local(Kind.I32);
		int one = a.local(Kind.I32);
		a.block("fail");
		WasmEmitHelper.emitCharvecToStrLocal(a.w, 1, flags.charvecPossible());
		stringBytes(a, 1, pb, "fail");
		vectorStorage(a, 0, items, n, x, "fail");
		a.get(pb).arrayLen().i32(2).op(Instruction.I32_SUB).set(m);
		a.i32(1).set(one);
		a.i32(2).set(total);
		a.block("measured").loop("measure");
		a.get(i).get(n).op(Instruction.I32_GE_U).brIf("measured");
		a.get(items).get(i).arrayGet(ARR).set(x);
		stringBytes(a, x, xb, "fail");
		a.get(total).get(xb).arrayLen().i32(2).op(Instruction.I32_SUB).op(Instruction.I32_ADD).set(total);
		// chars stays -1 from the first operand that was never counted
		a.get(x).refCast(STRING).structGet(STRING, CHAR_COUNT).tee(c);
		a.get(chars).op(Instruction.I32_OR).i32(0).op(Instruction.I32_LT_S);
		a.ifVoid().i32(-1).set(chars).elseArm().get(chars).get(c).op(Instruction.I32_ADD).set(chars).end();
		increment(a, i);
		a.br("measure");
		a.end().end();
		a.get(n).i32(1).op(Instruction.I32_GT_U).ifVoid();
		a.get(total).get(m).get(n).i32(1).op(Instruction.I32_SUB).op(Instruction.I32_MUL).op(Instruction.I32_ADD);
		a.set(total);
		a.get(1).refCast(STRING).structGet(STRING, CHAR_COUNT).tee(c);
		a.get(chars).op(Instruction.I32_OR).i32(0).op(Instruction.I32_LT_S);
		a.ifVoid().i32(-1).set(chars).elseArm();
		a.get(chars).get(c).get(n).i32(1).op(Instruction.I32_SUB).op(Instruction.I32_MUL).op(Instruction.I32_ADD);
		a.set(chars).end();
		a.end();
		a.get(total).arrayNewDefault(BYTES).set(out);
		a.get(out).i32(0).i32(QUOTE).arraySet(BYTES);
		a.get(out).get(total).i32(1).op(Instruction.I32_SUB).i32(QUOTE).arraySet(BYTES);
		a.i32(1).set(pos);
		a.i32(0).set(i);
		a.block("joined").loop("next");
		a.get(i).get(n).op(Instruction.I32_GE_U).brIf("joined");
		a.get(i).ifVoid();
		copyBytes(a, out, pos, pb, one, m);
		a.get(pos).get(m).op(Instruction.I32_ADD).set(pos);
		a.end();
		a.get(items).get(i).arrayGet(ARR);
		WasmEmitHelper.emitStrBytesArray(a.w);
		a.tee(xb).arrayLen().i32(2).op(Instruction.I32_SUB).set(len);
		copyBytes(a, out, pos, xb, one, len);
		a.get(pos).get(len).op(Instruction.I32_ADD).set(pos);
		increment(a, i);
		a.br("next");
		a.end().end();
		WasmStringRuntimeBuilder.emitCountedRuntimeString(a.w, total, out, id, chars);
		a.ret();
		a.end();
		a.refNull();
		return a.finish();
	}

	// --- concatenation ---------------------------------------------------------

	// chars += count, staying -1 from the first count that is -1 (a string never
	// counted).
	private static void addCount(Asm a, int chars, int count) {
		a.get(count).get(chars).op(Instruction.I32_OR).i32(0).op(Instruction.I32_LT_S);
		a.ifVoid().i32(-1).set(chars).elseArm().get(chars).get(count).op(Instruction.I32_ADD).set(chars).end();
	}

	// q = |v|
	private static void magnitude(Asm a, int v, int q) {
		a.i32(0).get(v).op(Instruction.I32_SUB).get(v).get(v).i32(0).op(Instruction.I32_LT_S);
		a.op(Instruction.SELECT).set(q);
	}

	// _str_cat (a, b, c, d): the pieces, in order, as one immutable string. A piece is an
	// immutable string, whose content is copied; a fixnum, whose decimal text is written
	// straight where it goes; or nil, which is no piece (a site of fewer than four pads
	// with it). What a chain of %string-concat over %princ-piece leaves answers, with one
	// byte array and one string made where each link and each fixnum's text made a pair
	// (WasmStringConcatCompiler). The lengths are summed first, the characters with them
	// while every string has its count.
	private static byte[] buildCat() {
		Asm a = new Asm(4);
		int out = a.local(Kind.BYTES);
		int[] bytes = new int[4];
		int[] len = new int[4];
		for (int k = 0; k < 4; k++) {
			bytes[k] = a.local(Kind.BYTES);
		}
		for (int k = 0; k < 4; k++) {
			len[k] = a.local(Kind.I32);
		}
		int total = a.local(Kind.I32);
		int chars = a.local(Kind.I32);
		int pos = a.local(Kind.I32);
		int id = a.local(Kind.I32);
		int v = a.local(Kind.I32);
		int q = a.local(Kind.I32);
		int i = a.local(Kind.I32);
		int one = a.local(Kind.I32);
		a.i32(1).set(one);
		a.i32(2).set(total);
		for (int k = 0; k < 4; k++) {
			a.get(k).isNull().op(Instruction.I32_EQZ).ifVoid();
			a.get(k).refTest(I31).ifVoid();
			// a digit for each power of ten in |v|, and the sign
			a.get(k).unI31().set(v);
			magnitude(a, v, q);
			a.get(v).i32(0).op(Instruction.I32_LT_S).i32(1).op(Instruction.I32_ADD).set(len[k]);
			a.block("counted").loop("count");
			a.get(q).i32(10).op(Instruction.I32_LT_U).brIf("counted");
			a.get(q).i32(10).op(Instruction.I32_DIV_U).set(q);
			increment(a, len[k]);
			a.br("count");
			a.end().end();
			addCount(a, chars, len[k]);
			a.elseArm();
			a.get(k);
			WasmEmitHelper.emitStrBytesArray(a.w);
			a.tee(bytes[k]).arrayLen().i32(2).op(Instruction.I32_SUB).set(len[k]);
			a.get(k).refCast(STRING).structGet(STRING, CHAR_COUNT).set(v);
			addCount(a, chars, v);
			a.end();
			a.get(total).get(len[k]).op(Instruction.I32_ADD).set(total);
			a.end();
		}
		a.get(total).arrayNewDefault(BYTES).set(out);
		a.get(out).i32(0).i32(QUOTE).arraySet(BYTES);
		a.get(out).get(total).i32(1).op(Instruction.I32_SUB).i32(QUOTE).arraySet(BYTES);
		a.i32(1).set(pos);
		for (int k = 0; k < 4; k++) {
			a.get(k).isNull().op(Instruction.I32_EQZ).ifVoid();
			a.get(k).refTest(I31).ifVoid();
			// the digits from the last place back, then the sign
			a.get(k).unI31().set(v);
			magnitude(a, v, q);
			a.get(pos).get(len[k]).op(Instruction.I32_ADD).set(i);
			a.loop("digit");
			a.get(i).i32(1).op(Instruction.I32_SUB).set(i);
			a.get(out).get(i).get(q).i32(10).op(Instruction.I32_REM_U).i32('0').op(Instruction.I32_ADD);
			a.arraySet(BYTES);
			a.get(q).i32(10).op(Instruction.I32_DIV_U).tee(q).brIf("digit");
			a.end();
			a.get(v).i32(0).op(Instruction.I32_LT_S).ifVoid();
			a.get(out).get(pos).i32('-').arraySet(BYTES);
			a.end();
			a.elseArm();
			copyBytes(a, out, pos, bytes[k], one, len[k]);
			a.end();
			a.get(pos).get(len[k]).op(Instruction.I32_ADD).set(pos);
			a.end();
		}
		WasmStringRuntimeBuilder.emitCountedRuntimeString(a.w, total, out, id, chars);
		return a.finish();
	}

	// --- sort ------------------------------------------------------------------

	// _sort_keys (keys, elements, out, descending): the elements -- the keys themselves
	// when ELEMENTS is nil -- into the general vector OUT in the order of their keys,
	// ascending, or descending when DESCENDING is not nil, elements of equal keys in the
	// order they stood in. Answers OUT; nil, with OUT as it was, unless the keys are all
	// integers within 64 bits or all immutable strings and the three are vectors with
	// storage of their own, ELEMENTS and OUT as long as KEYS at least.
	//
	// A key is read once, into two i32 words that order as it does: an integer's 64 bits
	// with the sign bit flipped; of a string its first seven bytes and then its length,
	// which saturates at eight -- so two strings of equal words are equal, or both longer
	// than seven bytes. What is sorted is the keys' places: by a radix sort over the
	// words from the low byte up, a byte every key agrees on skipped -- N steps a byte
	// and no branch the keys decide, where a merge mispredicts every other one -- and
	// then each run of longer strings with equal words by a merge over their bytes
	// (_str_order), bottom up, a pair of runs already in order copied.
	private static byte[] buildSort() {
		Asm a = new Asm(4);
		int ks = a.local(Kind.ARR);
		int es = a.local(Kind.ARR);
		int os = a.local(Kind.ARR);
		int hi = a.local(Kind.INTS);
		int lo = a.local(Kind.INTS);
		int src = a.local(Kind.INTS);
		int dst = a.local(Kind.INTS);
		int swap = a.local(Kind.INTS);
		int word = a.local(Kind.INTS);
		int counts = a.local(Kind.INTS);
		int from = a.local(Kind.INTS);
		int to = a.local(Kind.INTS);
		int xb = a.local(Kind.BYTES);
		int x = a.local(Kind.EQ);
		int n = a.local(Kind.I32);
		int n2 = a.local(Kind.I32);
		int i = a.local(Kind.I32);
		int j = a.local(Kind.I32);
		int k = a.local(Kind.I32);
		int h = a.local(Kind.I32);
		int l = a.local(Kind.I32);
		int cl = a.local(Kind.I32);
		int strs = a.local(Kind.I32);
		int desc = a.local(Kind.I32);
		int orHi = a.local(Kind.I32);
		int andHi = a.local(Kind.I32);
		int orLo = a.local(Kind.I32);
		int andLo = a.local(Kind.I32);
		int digit = a.local(Kind.I32);
		int shift = a.local(Kind.I32);
		int flip = a.local(Kind.I32);
		int c = a.local(Kind.I32);
		int t = a.local(Kind.I32);
		int sum = a.local(Kind.I32);
		int width = a.local(Kind.I32);
		int left = a.local(Kind.I32);
		int mid = a.local(Kind.I32);
		int right = a.local(Kind.I32);
		int mi = a.local(Kind.I32);
		int mj = a.local(Kind.I32);
		int flipped = a.local(Kind.I32);
		int pa = a.local(Kind.I32);
		int pb = a.local(Kind.I32);
		int v = a.local(Kind.I64);
		a.block("fail");
		vectorStorage(a, 0, ks, n, x, "fail");
		a.get(1).isNull().ifVoid().get(ks).set(es).elseArm();
		vectorStorage(a, 1, es, n2, x, "fail");
		a.get(n2).get(n).op(Instruction.I32_LT_U).brIf("fail");
		a.end();
		vectorStorage(a, 2, os, n2, x, "fail");
		a.get(n2).get(n).op(Instruction.I32_LT_U).brIf("fail");
		a.get(3).isNull().op(Instruction.I32_EQZ).set(desc);
		a.get(n).op(Instruction.I32_EQZ).ifVoid().get(2).ret().end();
		a.get(n).arrayNewDefault(INTS).set(hi);
		a.get(n).arrayNewDefault(INTS).set(lo);
		a.get(n).arrayNewDefault(INTS).set(src);
		a.get(n).arrayNewDefault(INTS).set(dst);
		a.get(ks).i32(0).arrayGet(ARR).refTest(STRING).set(strs);
		a.i32(-1).set(andHi);
		a.i32(-1).set(andLo);
		// the keys' words, and each key's place
		a.block("read").loop("key");
		a.get(i).get(n).op(Instruction.I32_GE_U).brIf("read");
		a.get(ks).get(i).arrayGet(ARR).set(x);
		a.get(strs).ifVoid();
		stringBytes(a, x, xb, "fail");
		a.get(xb).arrayLen().i32(2).op(Instruction.I32_SUB).set(cl);
		a.i32(0).set(h);
		a.i32(0).set(l);
		a.i32(0).set(j);
		a.block("packed").loop("pack");
		a.get(j).get(cl).op(Instruction.I32_GE_U).get(j).i32(PREFIX_BYTES).op(Instruction.I32_GE_U);
		a.op(Instruction.I32_OR).brIf("packed");
		// byte j of the content at bit 24 - 8 * (j mod 4) of its word
		a.get(xb).get(j).i32(1).op(Instruction.I32_ADD).arrayGetU(BYTES);
		a.i32(24).get(j).i32(3).op(Instruction.I32_AND).i32(3).op(Instruction.I32_SHL).op(Instruction.I32_SUB);
		a.op(Instruction.I32_SHL).set(k);
		a.get(j).i32(4).op(Instruction.I32_LT_U).ifVoid();
		a.get(h).get(k).op(Instruction.I32_OR).set(h);
		a.elseArm();
		a.get(l).get(k).op(Instruction.I32_OR).set(l);
		a.end();
		increment(a, j);
		a.br("pack");
		a.end().end();
		a.get(l).get(cl).i32(PREFIX_BYTES + 1).get(cl).i32(PREFIX_BYTES + 1).op(Instruction.I32_LT_U);
		a.op(Instruction.SELECT).op(Instruction.I32_OR).set(l);
		a.elseArm();
		a.get(x).refTest(I31).ifVoid();
		a.get(x).unI31().op(Instruction.I64_EXTEND_S_I32).set(v);
		a.elseArm();
		a.get(x).refTest(WasmLispCompiler.TYPE_BIGNUM).op(Instruction.I32_EQZ).brIf("fail");
		a.get(x).refCast(WasmLispCompiler.TYPE_BIGNUM).structGet(WasmLispCompiler.TYPE_BIGNUM, 0).set(v);
		a.end();
		a.get(v).i64(Long.MIN_VALUE).op(Instruction.I64_XOR).set(v);
		a.get(v).i64(32).op(Instruction.I64_SHR_U).op(Instruction.I32_WRAP_I64).set(h);
		a.get(v).op(Instruction.I32_WRAP_I64).set(l);
		a.end();
		a.get(hi).get(i).get(h).arraySet(INTS);
		a.get(lo).get(i).get(l).arraySet(INTS);
		a.get(src).get(i).get(i).arraySet(INTS);
		a.get(orHi).get(h).op(Instruction.I32_OR).set(orHi);
		a.get(andHi).get(h).op(Instruction.I32_AND).set(andHi);
		a.get(orLo).get(l).op(Instruction.I32_OR).set(orLo);
		a.get(andLo).get(l).op(Instruction.I32_AND).set(andLo);
		increment(a, i);
		a.br("key");
		a.end().end();
		// the radix passes: the bits the keys differ in are or ^ and
		a.get(orHi).get(andHi).op(Instruction.I32_XOR).set(orHi);
		a.get(orLo).get(andLo).op(Instruction.I32_XOR).set(orLo);
		a.i32(0).i32(255).get(desc).op(Instruction.I32_EQZ).op(Instruction.SELECT).set(flip);
		a.block("ordered").loop("byte");
		a.get(digit).i32(8).op(Instruction.I32_GE_U).brIf("ordered");
		a.get(digit).i32(3).op(Instruction.I32_AND).i32(3).op(Instruction.I32_SHL).set(shift);
		a.get(digit).i32(4).op(Instruction.I32_LT_U).ifVoid();
		a.get(lo).set(word);
		a.get(orLo).set(c);
		a.elseArm();
		a.get(hi).set(word);
		a.get(orHi).set(c);
		a.end();
		increment(a, digit);
		a.get(c).get(shift).op(Instruction.I32_SHR_U).i32(255).op(Instruction.I32_AND).op(Instruction.I32_EQZ);
		a.brIf("byte");
		// how many keys have each value of the byte
		a.i32(256).arrayNewDefault(INTS).set(counts);
		a.i32(0).set(i);
		a.block("counted").loop("count");
		a.get(i).get(n).op(Instruction.I32_GE_U).brIf("counted");
		a.get(counts);
		radixByte(a, word, i, shift, flip);
		a.tee(c);
		a.get(counts).get(c).arrayGet(INTS).i32(1).op(Instruction.I32_ADD).arraySet(INTS);
		increment(a, i);
		a.br("count");
		a.end().end();
		// where the first key of each value goes
		a.i32(0).set(i);
		a.i32(0).set(sum);
		a.block("placed").loop("place");
		a.get(i).i32(256).op(Instruction.I32_GE_U).brIf("placed");
		a.get(counts).get(i).arrayGet(INTS).set(t);
		a.get(counts).get(i).get(sum).arraySet(INTS);
		a.get(sum).get(t).op(Instruction.I32_ADD).set(sum);
		increment(a, i);
		a.br("place");
		a.end().end();
		a.i32(0).set(i);
		a.block("moved").loop("move");
		a.get(i).get(n).op(Instruction.I32_GE_U).brIf("moved");
		a.get(src).get(i).arrayGet(INTS).set(k);
		radixByte(a, word, k, shift, flip);
		a.set(c);
		a.get(dst).get(counts).get(c).arrayGet(INTS).tee(t).get(k).arraySet(INTS);
		a.get(counts).get(c).get(t).i32(1).op(Instruction.I32_ADD).arraySet(INTS);
		increment(a, i);
		a.br("move");
		a.end().end();
		a.get(src).set(swap);
		a.get(dst).set(src);
		a.get(swap).set(dst);
		a.br("byte");
		a.end().end();
		// each run of strings past the prefix with equal words, by their bytes
		a.get(strs).ifVoid();
		a.i32(0).set(i);
		a.block("settled").loop("run");
		a.get(i).get(n).op(Instruction.I32_GE_U).brIf("settled");
		a.get(i).i32(1).op(Instruction.I32_ADD).set(j);
		a.get(lo).get(src).get(i).arrayGet(INTS).tee(k).arrayGet(INTS).tee(l);
		a.i32(255).op(Instruction.I32_AND).i32(PREFIX_BYTES + 1).op(Instruction.I32_EQ).ifVoid();
		a.get(hi).get(k).arrayGet(INTS).set(h);
		a.block("ended").loop("same");
		a.get(j).get(n).op(Instruction.I32_GE_U).brIf("ended");
		a.get(hi).get(src).get(j).arrayGet(INTS).tee(k).arrayGet(INTS).get(h).op(Instruction.I32_NE).brIf("ended");
		a.get(lo).get(k).arrayGet(INTS).get(l).op(Instruction.I32_NE).brIf("ended");
		increment(a, j);
		a.br("same");
		a.end().end();
		a.get(j).get(i).op(Instruction.I32_SUB).i32(1).op(Instruction.I32_GT_U).ifVoid();
		// the places i short of j: runs of width merged from `from` into `to`, which
		// then change roles
		a.get(src).set(from);
		a.get(dst).set(to);
		a.i32(0).set(flipped);
		a.i32(1).set(width);
		a.block("sorted").loop("pass");
		a.get(width).get(j).get(i).op(Instruction.I32_SUB).op(Instruction.I32_GE_U).brIf("sorted");
		a.get(i).set(left);
		a.block("merged").loop("runs");
		a.get(left).get(j).op(Instruction.I32_GE_U).brIf("merged");
		minOfSumAnd(a, left, width, j, mid);
		minOfSumAnd(a, mid, width, j, right);
		a.get(left).set(mi);
		a.get(mid).set(mj);
		a.get(left).set(k);
		a.get(mid).get(right).op(Instruction.I32_LT_U).ifVoid();
		a.get(from).get(mid).i32(1).op(Instruction.I32_SUB).arrayGet(INTS).set(pa);
		a.get(from).get(mid).arrayGet(INTS).set(pb);
		takesRight(a, ks, pa, pb, desc);
		a.ifVoid();
		a.block("done").loop("merge");
		a.get(mi).get(mid).op(Instruction.I32_GE_U).get(mj).get(right).op(Instruction.I32_GE_U);
		a.op(Instruction.I32_OR).brIf("done");
		a.get(from).get(mi).arrayGet(INTS).set(pa);
		a.get(from).get(mj).arrayGet(INTS).set(pb);
		takesRight(a, ks, pa, pb, desc);
		a.ifVoid();
		a.get(to).get(k).get(pb).arraySet(INTS);
		increment(a, mj);
		a.elseArm();
		a.get(to).get(k).get(pa).arraySet(INTS);
		increment(a, mi);
		a.end();
		increment(a, k);
		a.br("merge");
		a.end().end();
		a.end();
		a.end();
		// what is left of either run
		a.get(to).get(k).get(from).get(mi).get(mid).get(mi).op(Instruction.I32_SUB).arrayCopy(INTS, INTS);
		a.get(to).get(k).get(mid).get(mi).op(Instruction.I32_SUB).op(Instruction.I32_ADD);
		a.get(from).get(mj).get(right).get(mj).op(Instruction.I32_SUB).arrayCopy(INTS, INTS);
		a.get(right).set(left);
		a.br("runs");
		a.end().end();
		a.get(from).set(swap);
		a.get(to).set(from);
		a.get(swap).set(to);
		a.get(flipped).i32(1).op(Instruction.I32_XOR).set(flipped);
		a.get(width).i32(1).op(Instruction.I32_SHL).set(width);
		a.br("pass");
		a.end().end();
		a.get(flipped).ifVoid();
		a.get(src).get(i).get(from).get(i).get(j).get(i).op(Instruction.I32_SUB).arrayCopy(INTS, INTS);
		a.end();
		a.end();
		a.end();
		a.get(j).set(i);
		a.br("run");
		a.end().end();
		a.end();
		a.i32(0).set(i);
		a.block("written").loop("write");
		a.get(i).get(n).op(Instruction.I32_GE_U).brIf("written");
		a.get(os).get(i).get(es).get(src).get(i).arrayGet(INTS).arrayGet(ARR).arraySet(ARR);
		increment(a, i);
		a.br("write");
		a.end().end();
		a.get(2).ret();
		a.end();
		a.refNull();
		return a.finish();
	}

	// into = min(from + width, n)
	private static void minOfSumAnd(Asm a, int from, int width, int n, int into) {
		a.get(from).get(width).op(Instruction.I32_ADD).tee(into).get(n);
		a.get(into).get(n).op(Instruction.I32_LT_U).op(Instruction.SELECT).set(into);
	}

	// Pushes the byte `shift` bits up the word at a place, complemented when flip is 255.
	private static void radixByte(Asm a, int word, int place, int shift, int flip) {
		a.get(word).get(place).arrayGet(INTS).get(shift).op(Instruction.I32_SHR_U);
		a.i32(255).op(Instruction.I32_AND).get(flip).op(Instruction.I32_XOR);
	}

	// Pushes whether the string key at the place pb, of the right run, goes ahead of the
	// one at pa, of the left run: strictly less ascending, strictly greater descending,
	// so equal keys leave the left one first.
	private static void takesRight(Asm a, int ks, int pa, int pb, int desc) {
		a.get(ks).get(pa).get(pb).get(desc).op(Instruction.SELECT).arrayGet(ARR);
		a.get(ks).get(pb).get(pa).get(desc).op(Instruction.SELECT).arrayGet(ARR);
		a.call(WasmLispCompiler.FUNC_STR_ORDER).unI31().i32(0).op(Instruction.I32_LT_S);
	}

}
