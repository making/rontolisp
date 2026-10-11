package am.ik.rontolisp.codegen.wasm;

import java.io.ByteArrayOutputStream;

import am.ik.wasm.Instruction;
import am.ik.wasm.Type;
import am.ik.wasm.WasmWriter;

/**
 * Builds the wasm hash-table runtime, the JVM's {@code runtime/RontoHashTable} over
 * wasm-GC values: a chained table until its first copy, a persistent trie of hash chains
 * from then on, each keeping the entries' insertion order ({@code .kb/hash-tables.md},
 * "Representation" and "Copies share structure").
 *
 * <p>
 * A table is a {@code TYPE_CELL} holding its HEADER, a cons {@code (count . record)}: the
 * count an i31 (tagged with the test in a module that tags, so {@code hash-table-p} and
 * {@code hash-table-count} read it as before) and the RECORD a {@code TYPE_HASH_BUCKETS}
 * array whose length tells the two shapes apart.
 * <ul>
 * <li>An ENTRY is an array {@code [hash, pos, owner, next, key, value]} -- the key an
 * {@code equalp} table's fold, with a seventh slot holding the key as first stored when
 * the fold differs. The hash is the key's {@code _hash} (an eql/eq table's aggregate key
 * its {@code _ihash}) with its high half folded into the low, the JVM's spread, cut to 30
 * bits: an i31.</li>
 * <li>FLAT, {@code [buckets, order, size, dead]}, what {@code make-hash-table} and
 * {@code clrhash} answer: power-of-two {@code buckets} of entries linked through
 * {@code next} (a bucket mixes hashes), doubling past three quarters full, and the
 * entries in insertion order, a removed one's slot nil until {@code dead} reaches half of
 * {@code size} and the order compacts.</li>
 * <li>TRIE, {@code [root, vroot, vtail, vsize, vshift, dead]}: {@code root} the trie,
 * null when empty. A NODE is an array {@code [owner, bitmap, child...]}: sixteen ways a
 * level (four hash bits, so the bitmap fits an i31), the children of the set bits in bit
 * order, each a node or a CHAIN of the entries whose keys share the whole hash.
 * {@code vroot}/{@code vtail}/{@code vsize}/{@code vshift}: the entries in insertion
 * order, Clojure's persistent vector -- a 32-way trie of vector nodes
 * {@code [owner, slot0 .. slot31]} plus a tail -- a removed entry's slot null until
 * {@code dead} reaches half of {@code vsize} and the table rebuilds.</li>
 * </ul>
 *
 * <p>
 * A trie record is the table's EDIT token: a node, entry or vector node whose owner is
 * the record changes in place, anything else is copied along the path from the root first
 * (and an entry whose owner is another record is copied before its value changes). A copy
 * ({@link #COPY}) turns a flat table into a trie first ({@link #TOTRIE}, once), then
 * gives the copy a fresh record and the source another, both holding the same root and
 * vector, so neither changes in place what the other reads: O(1), where the copy every
 * Clojure map verb takes walked the table. wasm runs one thread, so changing the source's
 * record is no race.
 *
 * <p>
 * The functions sit at {@code WasmLispCompiler.FUNC_HT_BASE} + the offsets below (the
 * walk {@link #buildEntries} in the slot {@code _hash_resize} held, at
 * {@code FUNC_HT_ENTRIES}); every one takes and answers {@code (ref null eq)}, so each
 * reuses a {@code TYPE_CALLABLE_BASE} signature and no type is added, an integer argument
 * travelling as an i31. A module that makes no table calls none of them and the tree
 * shaker drops them.
 */
final class WasmHashTableRuntimeBuilder {

	/** {@code _ht_find (record, hash) -> entry}: the chain of a hash, or nil. */
	static final int FIND = 0;

	/** {@code _ht_get (header, key, default) -> value}: {@code gethash}. */
	static final int GET = 1;

	/** {@code _ht_put (header, key, value) -> value}: {@code puthash}. */
	static final int PUT = 2;

	/** {@code _ht_rem (header, key) -> t or nil}: {@code remhash}. */
	static final int REM = 3;

	/** {@code _ht_copy (table) -> table}: {@code rontolisp::%copy-equal-table}. */
	static final int COPY = 4;

	/** {@code _ht_set (record, entry, value) -> nil}: a found entry's new value. */
	static final int SET = 5;

	/** {@code _ht_add (header, hash, key, value, key-as-written) -> nil}. */
	static final int ADD = 6;

	/** {@code _ht_del (header, entry) -> nil}: a found entry removed. */
	static final int DEL = 7;

	/** {@code _ht_insert (record, node, shift, entry) -> node}. */
	static final int INSERT = 8;

	/** {@code _ht_split (record, shift, chain, chain) -> node}. */
	static final int SPLIT = 9;

	/** {@code _ht_without (record, node, shift, entry) -> node or nil}. */
	static final int WITHOUT = 10;

	/** {@code _ht_replace (record, node, shift, old, fresh) -> node}. */
	static final int REPLACE = 11;

	/** {@code _ht_relink (chain, target, tail) -> chain}. */
	static final int RELINK = 12;

	/** {@code _ht_editable (record, node, room) -> node}. */
	static final int EDITABLE = 13;

	/** {@code _ht_vedit (record, vector-node) -> vector-node}. */
	static final int VEDIT = 14;

	/** {@code _ht_oappend (record, entry) -> nil}. */
	static final int OAPPEND = 15;

	/** {@code _ht_opush (record, level, parent, full-tail) -> vector-node}. */
	static final int OPUSH = 16;

	/** {@code _ht_onewpath (record, level, leaf) -> vector-node}. */
	static final int ONEWPATH = 17;

	/** {@code _ht_oset (record, pos, entry) -> nil}. */
	static final int OSET = 18;

	/** {@code _ht_oassoc (record, level, vector-node, pos, entry) -> vector-node}. */
	static final int OASSOC = 19;

	/** {@code _ht_rebuild (record) -> nil}: the dead order slots dropped. */
	static final int REBUILD = 20;

	/** {@code _ht_collect (node, entries-by-pos) -> nil}. */
	static final int COLLECT = 21;

	/** {@code _ht_fgrow (flat-record) -> nil}: twice the buckets. */
	static final int FGROW = 22;

	/** {@code _ht_fcompact (flat-record) -> nil}: the dead order slots dropped. */
	static final int FCOMPACT = 23;

	/** {@code _ht_totrie (flat-record) -> trie-record}: the entries as a trie. */
	static final int TOTRIE = 24;

	/** How many functions the block holds. */
	static final int FUNC_COUNT = 25;

	// A flat record's slots: the buckets, the entries in order, how many order slots
	// are used and how many of those are dead.
	static final int F_BUCKETS = 0;

	static final int F_ORDER = 1;

	static final int F_SIZE = 2;

	static final int F_DEAD = 3;

	static final int F_LEN = 4;

	private static final int F_INITIAL = 8;

	// The record's slots.
	static final int R_ROOT = 0;

	static final int R_VROOT = 1;

	static final int R_VTAIL = 2;

	static final int R_VSIZE = 3;

	static final int R_VSHIFT = 4;

	static final int R_DEAD = 5;

	static final int R_LEN = 6;

	// A trie node's slots: the owner, the bitmap, then the children.
	private static final int N_OWNER = 0;

	private static final int N_BITMAP = 1;

	private static final int N_CHILD = 2;

	// An entry's slots.
	static final int E_HASH = 0;

	static final int E_POS = 1;

	static final int E_OWNER = 2;

	static final int E_NEXT = 3;

	static final int E_KEY = 4;

	static final int E_VALUE = 5;

	static final int E_ORIG = 6;

	// A vector node: the owner, then 32 slots.
	private static final int V_OWNER = 0;

	private static final int V_LEN = 33;

	private static final int BITS = 4;

	private static final int MASK = (1 << BITS) - 1;

	private static final int VBITS = 5;

	private static final int VWIDTH = 1 << VBITS;

	private static final int VMASK = VWIDTH - 1;

	private static final int HASH_MASK = 0x3fffffff;

	private static final int T = WasmLispCompiler.TYPE_HASH_BUCKETS;

	// ref.cast to a NULLABLE reference (the 0x16 REF_CAST traps on null).
	private static final int REF_CAST_NULL = 0x17;

	private WasmHashTableRuntimeBuilder() {
	}

	/**
	 * The signature of the block's function at an offset: {@code TYPE_CALLABLE_BASE} plus
	 * one less than its argument count.
	 * @param i the offset
	 * @return its type index
	 */
	static int typeIndexOf(int i) {
		int arguments = switch (i) {
			case COPY, REBUILD, FGROW, FCOMPACT, TOTRIE -> 1;
			case FIND, REM, DEL, VEDIT, OAPPEND, COLLECT -> 2;
			case GET, PUT, SET, RELINK, EDITABLE, ONEWPATH, OSET -> 3;
			case INSERT, SPLIT, WITHOUT, OPUSH -> 4;
			case REPLACE, OASSOC, ADD -> 5;
			default -> throw new IllegalArgumentException("no hash-table runtime function " + i);
		};
		return WasmLispCompiler.TYPE_CALLABLE_BASE + arguments - 1;
	}

	/**
	 * The module flags the bodies read.
	 *
	 * @param tagged whether counts carry the two-bit test tag
	 * ({@code entries * 4 + test})
	 * @param identity whether a table may be eql or eq -- and so whether conses and cells
	 * carry the identity-hash slot their allocations must fill
	 * @param equalp whether a table may be equalp, folding its keys
	 */
	record Flags(boolean tagged, boolean identity, boolean equalp) {
	}

	/**
	 * The body of the block's function at an offset.
	 * @param i the offset
	 * @param flags the module's flags
	 * @return the encoded body
	 */
	static byte[] build(int i, Flags flags) {
		return switch (i) {
			case FIND -> buildFind();
			case GET -> buildGet(flags);
			case PUT -> buildPut(flags);
			case REM -> buildRem(flags);
			case COPY -> buildCopy(flags);
			case SET -> buildSet();
			case ADD -> buildAdd(flags);
			case DEL -> buildDel(flags);
			case INSERT -> buildInsert();
			case SPLIT -> buildSplit();
			case WITHOUT -> buildWithout();
			case REPLACE -> buildReplace();
			case RELINK -> buildRelink();
			case EDITABLE -> buildEditable();
			case VEDIT -> buildVedit();
			case OAPPEND -> buildOappend();
			case OPUSH -> buildOpush();
			case ONEWPATH -> buildOnewpath();
			case OSET -> buildOset();
			case OASSOC -> buildOassoc();
			case REBUILD -> buildRebuild();
			case COLLECT -> buildCollect();
			case FGROW -> buildFgrow();
			case FCOMPACT -> buildFcompact();
			case TOTRIE -> buildTotrie();
			default -> throw new IllegalArgumentException("no hash-table runtime function " + i);
		};
	}

	private static int func(int i) {
		return WasmLispCompiler.FUNC_HT_BASE + i;
	}

	/**
	 * Pushes a fresh empty FLAT record onto the stack -- what a new or cleared table
	 * holds.
	 * @param w the writer
	 */
	static void emitNewRecord(WasmWriter w) {
		Asm a = new Asm(w);
		a.i32(F_INITIAL).newArray().i32(F_INITIAL).newArray().i32(0).i31().i32(0).i31().fixed(F_LEN);
	}

	// Pushes a fresh empty TRIE record.
	private static void emitNewTrieRecord(Asm a) {
		a.nul().nul().nul().i32(0).i31().i32(VBITS).i31().i32(0).i31().fixed(R_LEN);
	}

	// i32: whether the record in recLocal is a flat one.
	private static void emitIsFlat(Asm a, int recLocal) {
		a.get(recLocal).len().i32(F_LEN).op(Instruction.I32_EQ);
	}

	// header.count += delta entries (by four in a module that tags).
	private static void emitAddToCount(Asm a, Flags flags, int headerLocal, int delta) {
		a.get(headerLocal).castCons();
		a.get(headerLocal).car().unI31().i32(flags.tagged() ? 4 * delta : delta).op(Instruction.I32_ADD).i31();
		a.setCar();
	}

	// _ht_find (rec, hbox) -> the chain of entries of hash hbox, or nil.
	// Locals: 2 node, 3 child (arrays), 4 h, 5 shift, 6 bitmap, 7 bit (i32).
	private static byte[] buildFind() {
		Asm a = Asm.function(L.ARR, L.ARR, L.I32, L.I32, L.I32, L.I32);
		a.get(1).unI31().set(4);
		a.get(0).arr().i32(R_ROOT).aget().arr().set(2);
		a.get(2).isNull().ifVoid().nul().ret().end();
		a.i32(0).set(5);
		a.block().loop();
		// bit = 1 << ((h >>> shift) & MASK); a clear bit is a miss
		a.get(2).i32(N_BITMAP).aget().unI31().set(6);
		a.i32(1)
			.get(4)
			.get(5)
			.op(Instruction.I32_SHR_U)
			.i32(MASK)
			.op(Instruction.I32_AND)
			.op(Instruction.I32_SHL)
			.set(7);
		a.get(6).get(7).op(Instruction.I32_AND).op(Instruction.I32_EQZ).brIf(1);
		a.get(2)
			.i32(N_CHILD)
			.get(6)
			.get(7)
			.i32(1)
			.op(Instruction.I32_SUB)
			.op(Instruction.I32_AND)
			.op(Instruction.I32_POPCNT)
			.op(Instruction.I32_ADD)
			.aget()
			.arr()
			.set(3);
		a.get(3).isEntry().ifVoid();
		a.get(3).i32(E_HASH).aget().unI31().get(4).op(Instruction.I32_EQ).ifVoid().get(3).ret().end();
		a.br(2);
		a.end();
		a.get(3).set(2);
		a.get(5).i32(BITS).op(Instruction.I32_ADD).set(5);
		a.br(0);
		a.end().end();
		a.nul();
		return a.finish();
	}

	// Leaves the i32 hash the table places key (local keyLocal) by: _hash, or for an
	// eql/eq table (tag in tagLocal >= 2) _ihash; spread and cut to 30 bits.
	private static void emitPlacementHash(Asm a, Flags flags, int keyLocal, int tagLocal, int hLocal) {
		if (flags.identity()) {
			a.get(tagLocal).i32(2).op(Instruction.I32_GE_S).ifI32();
			a.get(keyLocal).call(WasmLispCompiler.FUNC_IHASH);
			a.elseArm();
			a.get(keyLocal).call(WasmLispCompiler.FUNC_HASH);
			a.end();
		}
		else {
			a.get(keyLocal).call(WasmLispCompiler.FUNC_HASH);
		}
		a.set(hLocal);
		// The high half folded into the low (the JVM's spread): a bucket index and the
		// trie read the low bits first, and small integers keep their own.
		a.xorShift(hLocal, 16);
		a.get(hLocal).i32(HASH_MASK).op(Instruction.I32_AND).set(hLocal);
	}

	// tagLocal = the header's test tag (0 in a module that tags no count), and the key
	// folded when the table is equalp.
	private static void emitTagAndFold(Asm a, Flags flags, int headerLocal, int keyLocal, int tagLocal) {
		if (flags.tagged()) {
			a.get(headerLocal).car().unI31().i32(3).op(Instruction.I32_AND).set(tagLocal);
		}
		if (flags.equalp()) {
			a.get(tagLocal).i32(1).op(Instruction.I32_EQ).ifVoid();
			a.get(keyLocal).call(WasmLispCompiler.FUNC_EQUALP_KEY).set(keyLocal);
			a.end();
		}
	}

	// Leaves the i32 "the key in keyLocal is the entry in entryLocal's key" by the
	// table's test: eql (and eq, the same predicate) for an eql/eq table, else _equal.
	// entryKeyLocal is a scratch eqref local.
	private static void emitKeyTest(Asm a, Flags flags, int keyLocal, int entryLocal, int entryKeyLocal, int tagLocal) {
		a.get(entryLocal).i32(E_KEY).aget().set(entryKeyLocal);
		if (!flags.identity()) {
			a.get(keyLocal).get(entryKeyLocal).call(WasmLispCompiler.FUNC_EQUAL);
			return;
		}
		a.get(tagLocal).i32(2).op(Instruction.I32_GE_S).ifI32();
		// eql: identity, then a symbol or string by its interned offset, then a fixnum
		// (eql only to itself, which ref.eq refused), then the tail.
		a.get(keyLocal).get(entryKeyLocal).refEq().ifI32();
		a.i32(1);
		a.elseArm();
		a.get(keyLocal).refTest(WasmLispCompiler.TYPE_STRING).ifI32();
		a.get(entryKeyLocal).refTest(WasmLispCompiler.TYPE_STRING).ifI32();
		a.get(keyLocal).stringOffset().get(entryKeyLocal).stringOffset().op(Instruction.I32_EQ);
		a.elseArm().i32(0).end();
		a.elseArm();
		a.get(keyLocal).isI31().ifI32();
		a.i32(0);
		a.elseArm();
		a.get(keyLocal).get(entryKeyLocal).call(WasmLispCompiler.FUNC_EQL_TAIL);
		a.end();
		a.end();
		a.end();
		a.elseArm();
		a.get(keyLocal).get(entryKeyLocal).call(WasmLispCompiler.FUNC_EQUAL);
		a.end();
	}

	// _ht_get (header, key, default) -> the value stored under key, or default. A flat
	// table's bucket holds other hashes too, a trie's chain only the key's: the walk
	// skips an entry of another hash either way.
	// Locals: 3 entry (array), 4 tag, 5 h (i32), 6 entry key (eqref), 7 rec, 8 buckets
	// (arrays).
	private static byte[] buildGet(Flags flags) {
		Asm a = Asm.function(L.ARR, L.I32, L.I32, L.EQ, L.ARR, L.ARR);
		emitTagAndFold(a, flags, 0, 1, 4);
		emitPlacementHash(a, flags, 1, 4, 5);
		a.get(0).cdr().arr().set(7);
		emitIsFlat(a, 7);
		a.ifVoid();
		a.get(7).i32(F_BUCKETS).aget().arr().set(8);
		a.get(8).get(5).get(8).len().i32(1).op(Instruction.I32_SUB).op(Instruction.I32_AND).aget().arr().set(3);
		a.elseArm();
		a.get(7).get(5).i31().call(func(FIND)).arr().set(3);
		a.end();
		a.block().loop();
		a.get(3).isNull().brIf(1);
		a.get(3).i32(E_HASH).aget().unI31().get(5).op(Instruction.I32_EQ).ifVoid();
		emitKeyTest(a, flags, 1, 3, 6, 4);
		a.ifVoid().get(3).i32(E_VALUE).aget().ret().end();
		a.end();
		a.get(3).i32(E_NEXT).aget().arr().set(3);
		a.br(0);
		a.end().end();
		a.get(2);
		return a.finish();
	}

	// _ht_put (header, key, value) -> value: the found entry's value replaced, else a
	// fresh entry -- an equalp table's keeping the key as written when its fold differs.
	// A flat table's entry goes in at its bucket's head, the order array and the buckets
	// growing as they fill; a trie's through _ht_set or _ht_add.
	// Locals: 3 entry (array), 4 tag, 5 h (i32), 6 the key as written, 7 entry key
	// (eqrefs), 8 rec, 9 buckets, 10 fresh entry, 11 order, 12 grown order (arrays),
	// 13 bucket index, 14 size (i32).
	private static byte[] buildPut(Flags flags) {
		Asm a = Asm.function(L.ARR, L.I32, L.I32, L.EQ, L.EQ, L.ARR, L.ARR, L.ARR, L.ARR, L.ARR, L.I32, L.I32);
		a.get(1).set(6);
		emitTagAndFold(a, flags, 0, 1, 4);
		emitPlacementHash(a, flags, 1, 4, 5);
		if (flags.equalp()) {
			// The key as written rides along only when the fold changed it.
			a.get(6).get(1).refEq().ifVoid().nul().set(6).end();
		}
		else {
			a.nul().set(6);
		}
		a.get(0).cdr().arr().set(8);
		emitIsFlat(a, 8);
		a.op(Instruction.I32_EQZ).ifVoid();
		// A trie: the chain of the hash, then _ht_set or _ht_add.
		a.get(8).get(5).i31().call(func(FIND)).arr().set(3);
		a.block().loop();
		a.get(3).isNull().brIf(1);
		emitKeyTest(a, flags, 1, 3, 7, 4);
		a.ifVoid();
		a.get(8).get(3).get(2).call(func(SET)).drop();
		a.get(2).ret();
		a.end();
		a.get(3).i32(E_NEXT).aget().arr().set(3);
		a.br(0);
		a.end().end();
		a.get(0).get(5).i31().get(1).get(2).get(6).call(func(ADD)).drop();
		a.get(2).ret();
		a.end();
		// Flat: the bucket's chain.
		a.get(8).i32(F_BUCKETS).aget().arr().set(9);
		a.get(5).get(9).len().i32(1).op(Instruction.I32_SUB).op(Instruction.I32_AND).set(13);
		a.get(9).get(13).aget().arr().set(3);
		a.block().loop();
		a.get(3).isNull().brIf(1);
		a.get(3).i32(E_HASH).aget().unI31().get(5).op(Instruction.I32_EQ).ifVoid();
		emitKeyTest(a, flags, 1, 3, 7, 4);
		a.ifVoid();
		a.get(3).i32(E_VALUE).get(2).aset();
		a.get(2).ret();
		a.end();
		a.end();
		a.get(3).i32(E_NEXT).aget().arr().set(3);
		a.br(0);
		a.end().end();
		// A fresh entry heads the bucket and goes last in the order.
		a.get(8).i32(F_SIZE).aget().unI31().set(14);
		a.get(6).isNull().ifArr();
		a.get(5).i31().get(14).i31().get(8).get(9).get(13).aget().get(1).get(2).fixed(E_VALUE + 1);
		a.elseArm();
		a.get(5).i31().get(14).i31().get(8).get(9).get(13).aget().get(1).get(2).get(6).fixed(E_ORIG + 1);
		a.end();
		a.set(10);
		a.get(9).get(13).get(10).aset();
		a.get(8).i32(F_ORDER).aget().arr().set(11);
		a.get(14).get(11).len().op(Instruction.I32_GE_S).ifVoid();
		a.get(14).i32(1).op(Instruction.I32_SHL).newArray().set(12);
		a.get(12).i32(0).get(11).i32(0).get(14).copy();
		a.get(12).set(11);
		a.get(8).i32(F_ORDER).get(11).aset();
		a.end();
		a.get(11).get(14).get(10).aset();
		a.get(8).i32(F_SIZE).get(14).i32(1).op(Instruction.I32_ADD).i31().aset();
		emitAddToCount(a, flags, 0, 1);
		// Past three quarters full: twice the buckets.
		a.get(0).car().unI31();
		if (flags.tagged()) {
			a.i32(2).op(Instruction.I32_SHR_S);
		}
		a.get(9).len().get(9).len().i32(2).op(Instruction.I32_SHR_U).op(Instruction.I32_SUB).op(Instruction.I32_GT_S);
		a.ifVoid().get(8).call(func(FGROW)).drop().end();
		a.get(2);
		return a.finish();
	}

	// _ht_rem (header, key) -> t when an entry went, else nil: a flat table's entry
	// unlinked from its bucket and its order slot nil (the order compacting once half
	// the slots are), a trie's through _ht_del.
	// Locals: 2 entry (array), 3 tag, 4 h (i32), 5 entry key (eqref), 6 rec, 7 buckets,
	// 8 previous entry, 9 order (arrays), 10 bucket index, 11 dead (i32).
	private static byte[] buildRem(Flags flags) {
		Asm a = Asm.function(L.ARR, L.I32, L.I32, L.EQ, L.ARR, L.ARR, L.ARR, L.ARR, L.I32, L.I32);
		emitTagAndFold(a, flags, 0, 1, 3);
		emitPlacementHash(a, flags, 1, 3, 4);
		a.get(0).cdr().arr().set(6);
		emitIsFlat(a, 6);
		a.op(Instruction.I32_EQZ).ifVoid();
		a.get(6).get(4).i31().call(func(FIND)).arr().set(2);
		a.block().loop();
		a.get(2).isNull().brIf(1);
		emitKeyTest(a, flags, 1, 2, 5, 3);
		a.ifVoid();
		a.get(0).get(2).call(func(DEL)).drop();
		a.call(WasmLispCompiler.FUNC_T_SYM).ret();
		a.end();
		a.get(2).i32(E_NEXT).aget().arr().set(2);
		a.br(0);
		a.end().end();
		a.nul().ret();
		a.end();
		a.get(6).i32(F_BUCKETS).aget().arr().set(7);
		a.get(4).get(7).len().i32(1).op(Instruction.I32_SUB).op(Instruction.I32_AND).set(10);
		a.get(7).get(10).aget().arr().set(2);
		a.block().loop();
		a.get(2).isNull().brIf(1);
		a.get(2).i32(E_HASH).aget().unI31().get(4).op(Instruction.I32_EQ).ifVoid();
		emitKeyTest(a, flags, 1, 2, 5, 3);
		a.ifVoid();
		a.get(8).isNull().ifVoid();
		a.get(7).get(10).get(2).i32(E_NEXT).aget().aset();
		a.elseArm();
		a.get(8).i32(E_NEXT).get(2).i32(E_NEXT).aget().aset();
		a.end();
		a.get(6).i32(F_ORDER).aget().arr().set(9);
		a.get(9).get(2).i32(E_POS).aget().unI31().nul().aset();
		emitAddToCount(a, flags, 0, -1);
		a.get(6).i32(F_DEAD).aget().unI31().i32(1).op(Instruction.I32_ADD).set(11);
		a.get(6).i32(F_DEAD).get(11).i31().aset();
		a.get(11).i32(1).op(Instruction.I32_SHL).get(6).i32(F_SIZE).aget().unI31().op(Instruction.I32_GE_S);
		a.ifVoid().get(6).call(func(FCOMPACT)).drop().end();
		a.call(WasmLispCompiler.FUNC_T_SYM).ret();
		a.end();
		a.end();
		a.get(2).set(8);
		a.get(2).i32(E_NEXT).aget().arr().set(2);
		a.br(0);
		a.end().end();
		a.nul();
		return a.finish();
	}

	// _ht_copy (table) -> a fresh equal table of its entries in its order. An equal
	// table's copy shares its structure: a flat one becomes a trie first, once; then
	// both tables take a fresh record over the trie, so neither owns what the other
	// reads. Any other test's table is re-placed key by key through _ht_put, each key as
	// first stored.
	// Locals: 1 header, 2 record, 3 the copy's record, 4 the source's new record,
	// 5 the copy, 6 entries, 7 entry (arrays but 1 and 5), 8 i, 9 n (i32), 10 the
	// copy's header.
	private static byte[] buildCopy(Flags flags) {
		Asm a = Asm.function(L.EQ, L.ARR, L.ARR, L.ARR, L.EQ, L.ARR, L.ARR, L.I32, L.I32, L.EQ);
		a.get(0).cellValue().set(1);
		a.get(1).cdr().arr().set(2);
		a.block();
		if (flags.tagged()) {
			a.get(1).car().unI31().i32(3).op(Instruction.I32_AND).brIf(0);
		}
		emitIsFlat(a, 2);
		a.ifVoid().get(2).call(func(TOTRIE)).arr().set(2).end();
		a.i32(R_LEN).newArray().set(3);
		a.get(3).i32(0).get(2).i32(0).i32(R_LEN).copy();
		a.i32(R_LEN).newArray().set(4);
		a.get(4).i32(0).get(2).i32(0).i32(R_LEN).copy();
		// The source owns nothing it shares from here.
		a.get(1).castCons().get(4).setCdr();
		a.get(1).car().get(3);
		WasmEmitHelper.emitNewCons(a.w, flags.identity());
		WasmEmitHelper.emitNewCell(a.w, flags.identity());
		a.ret();
		a.end();
		a.i32(0).i31();
		emitNewRecord(a.w);
		WasmEmitHelper.emitNewCons(a.w, flags.identity());
		a.set(10);
		a.get(10);
		WasmEmitHelper.emitNewCell(a.w, flags.identity());
		a.set(5);
		a.get(1).call(WasmLispCompiler.FUNC_HT_ENTRIES).arr().set(6);
		a.get(6).len().set(9);
		a.i32(0).set(8);
		a.block().loop();
		a.get(8).get(9).op(Instruction.I32_GE_S).brIf(1);
		a.get(6).get(8).aget().arr().set(7);
		a.get(10);
		if (flags.equalp()) {
			a.get(7).len().i32(E_ORIG + 1).op(Instruction.I32_EQ).ifEq();
			a.get(7).i32(E_ORIG).aget();
			a.elseArm();
			a.get(7).i32(E_KEY).aget();
			a.end();
		}
		else {
			a.get(7).i32(E_KEY).aget();
		}
		a.get(7).i32(E_VALUE).aget();
		a.call(func(PUT)).drop();
		a.get(8).i32(1).op(Instruction.I32_ADD).set(8);
		a.br(0);
		a.end().end();
		a.get(5);
		return a.finish();
	}

	// _ht_fgrow (rec) -> nil: twice the buckets, every entry relinked by the hash it
	// keeps.
	// Locals: 1 rec, 2 old buckets, 3 new buckets, 4 entry, 5 next (arrays), 6 i,
	// 7 count, 8 mask, 9 index (i32).
	private static byte[] buildFgrow() {
		Asm a = Asm.function(L.ARR, L.ARR, L.ARR, L.ARR, L.ARR, L.I32, L.I32, L.I32, L.I32);
		a.get(0).arr().set(1);
		a.get(1).i32(F_BUCKETS).aget().arr().set(2);
		a.get(2).len().set(7);
		a.get(7).i32(1).op(Instruction.I32_SHL).newArray().set(3);
		a.get(7).i32(1).op(Instruction.I32_SHL).i32(1).op(Instruction.I32_SUB).set(8);
		a.i32(0).set(6);
		a.block().loop();
		a.get(6).get(7).op(Instruction.I32_GE_S).brIf(1);
		a.get(2).get(6).aget().arr().set(4);
		a.block().loop();
		a.get(4).isNull().brIf(1);
		a.get(4).i32(E_NEXT).aget().arr().set(5);
		a.get(4).i32(E_HASH).aget().unI31().get(8).op(Instruction.I32_AND).set(9);
		a.get(4).i32(E_NEXT).get(3).get(9).aget().aset();
		a.get(3).get(9).get(4).aset();
		a.get(5).set(4);
		a.br(0);
		a.end().end();
		a.get(6).i32(1).op(Instruction.I32_ADD).set(6);
		a.br(0);
		a.end().end();
		a.get(1).i32(F_BUCKETS).get(3).aset();
		a.nul();
		return a.finish();
	}

	// _ht_fcompact (rec) -> nil: the live entries moved to the front of the order, each
	// told its new position, the dead slots gone.
	// Locals: 1 rec, 2 order, 3 entry (arrays), 4 i, 5 kept, 6 size (i32).
	private static byte[] buildFcompact() {
		Asm a = Asm.function(L.ARR, L.ARR, L.ARR, L.I32, L.I32, L.I32);
		a.get(0).arr().set(1);
		a.get(1).i32(F_ORDER).aget().arr().set(2);
		a.get(1).i32(F_SIZE).aget().unI31().set(6);
		a.i32(0).set(4);
		a.i32(0).set(5);
		a.block().loop();
		a.get(4).get(6).op(Instruction.I32_GE_S).brIf(1);
		a.get(2).get(4).aget().arr().set(3);
		a.get(3).isNull().op(Instruction.I32_EQZ).ifVoid();
		a.get(3).i32(E_POS).get(5).i31().aset();
		a.get(2).get(5).get(3).aset();
		a.get(5).i32(1).op(Instruction.I32_ADD).set(5);
		a.end();
		a.get(4).i32(1).op(Instruction.I32_ADD).set(4);
		a.br(0);
		a.end().end();
		a.get(2).get(5).nul().get(6).get(5).op(Instruction.I32_SUB).fill();
		a.get(1).i32(F_SIZE).get(5).i31().aset();
		a.get(1).i32(F_DEAD).i32(0).i31().aset();
		a.nul();
		return a.finish();
	}

	// _ht_totrie (rec) -> a trie record of the flat record's live entries in their
	// order: fresh entries (a chain links its own), owned by the new record, which the
	// copy then hands neither table.
	// Locals: 1 rec, 2 trie, 3 order, 4 entry, 5 fresh (arrays), 6 i, 7 size (i32).
	private static byte[] buildTotrie() {
		Asm a = Asm.function(L.ARR, L.ARR, L.ARR, L.ARR, L.ARR, L.I32, L.I32);
		a.get(0).arr().set(1);
		emitNewTrieRecord(a);
		a.set(2);
		a.get(1).i32(F_ORDER).aget().arr().set(3);
		a.get(1).i32(F_SIZE).aget().unI31().set(7);
		a.i32(0).set(6);
		a.block().loop();
		a.get(6).get(7).op(Instruction.I32_GE_S).brIf(1);
		a.get(3).get(6).aget().arr().set(4);
		a.get(4).isNull().op(Instruction.I32_EQZ).ifVoid();
		a.get(4).len().newArray().set(5);
		a.get(5).i32(0).get(4).i32(0).get(4).len().copy();
		a.get(5).i32(E_POS).get(2).i32(R_VSIZE).aget().aset();
		a.get(5).i32(E_OWNER).get(2).aset();
		a.get(5).i32(E_NEXT).nul().aset();
		a.get(2).get(5).call(func(OAPPEND)).drop();
		a.get(2).i32(R_ROOT);
		a.get(2).get(2).i32(R_ROOT).aget().i32(0).i31().get(5).call(func(INSERT));
		a.aset();
		a.end();
		a.get(6).i32(1).op(Instruction.I32_ADD).set(6);
		a.br(0);
		a.end().end();
		a.get(2);
		return a.finish();
	}

	// _ht_set (rec, entry, value) -> nil: in place when the entry is the record's own,
	// else in a copy of it, which a copy of the path and the entry's order slot hold.
	// Locals: 3 entry, 4 fresh, 5 rec (arrays).
	private static byte[] buildSet() {
		Asm a = Asm.function(L.ARR, L.ARR, L.ARR);
		a.get(1).arr().set(3);
		a.get(0).arr().set(5);
		a.get(3).i32(E_OWNER).aget().get(0).refEq().ifVoid();
		a.get(3).i32(E_VALUE).get(2).aset();
		a.nul().ret();
		a.end();
		a.get(3).len().newArray().set(4);
		a.get(4).i32(0).get(3).i32(0).get(3).len().copy();
		a.get(4).i32(E_VALUE).get(2).aset();
		a.get(4).i32(E_OWNER).get(0).aset();
		a.get(5).i32(R_ROOT);
		a.get(0).get(5).i32(R_ROOT).aget().i32(0).i31().get(3).get(4).call(func(REPLACE));
		a.aset();
		a.get(0).get(3).i32(E_POS).aget().get(4).call(func(OSET)).drop();
		a.nul();
		return a.finish();
	}

	// _ht_add (header, hbox, key, value, orig) -> nil: a fresh entry at the end of the
	// order and in its hash's chain; orig, when not nil, rides in the seventh slot.
	// Locals: 5 rec, 6 entry (arrays).
	private static byte[] buildAdd(Flags flags) {
		Asm a = Asm.function(L.ARR, L.ARR);
		a.get(0).cdr().arr().set(5);
		a.get(4).isNull().ifArr();
		a.get(1).get(5).i32(R_VSIZE).aget().get(5).nul().get(2).get(3).fixed(E_VALUE + 1);
		a.elseArm();
		a.get(1).get(5).i32(R_VSIZE).aget().get(5).nul().get(2).get(3).get(4).fixed(E_ORIG + 1);
		a.end();
		a.set(6);
		a.get(5).get(6).call(func(OAPPEND)).drop();
		a.get(5).i32(R_ROOT);
		a.get(5).get(5).i32(R_ROOT).aget().i32(0).i31().get(6).call(func(INSERT));
		a.aset();
		a.get(0).castCons();
		a.get(0).car().unI31().i32(flags.tagged() ? 4 : 1).op(Instruction.I32_ADD).i31();
		a.setCar();
		a.nul();
		return a.finish();
	}

	// _ht_del (header, entry) -> nil: the entry out of its chain, its order slot dead,
	// the table rebuilt once half the slots are.
	// Locals: 2 rec, 3 entry (arrays), 4 dead (i32).
	private static byte[] buildDel(Flags flags) {
		Asm a = Asm.function(L.ARR, L.ARR, L.I32);
		a.get(0).cdr().arr().set(2);
		a.get(1).arr().set(3);
		a.get(2).i32(R_ROOT);
		a.get(2).get(2).i32(R_ROOT).aget().i32(0).i31().get(3).call(func(WITHOUT));
		a.aset();
		a.get(2).get(3).i32(E_POS).aget().nul().call(func(OSET)).drop();
		a.get(0).castCons();
		a.get(0).car().unI31().i32(flags.tagged() ? 4 : 1).op(Instruction.I32_SUB).i31();
		a.setCar();
		a.get(2).i32(R_DEAD).aget().unI31().i32(1).op(Instruction.I32_ADD).set(4);
		a.get(2).i32(R_DEAD).get(4).i31().aset();
		a.get(4).i32(1).op(Instruction.I32_SHL).get(2).i32(R_VSIZE).aget().unI31().op(Instruction.I32_GE_S).ifVoid();
		a.get(2).call(func(REBUILD)).drop();
		a.end();
		a.nul();
		return a.finish();
	}

	// _ht_insert (rec, node, shiftbox, entry) -> node: the entry in node's subtree, the
	// node itself when it changed in place, else a copy (or a new node for a nil one).
	// Locals: 4 node, 5 entry, 6 child, 7 owned (arrays), 8 replacement (eqref), 9 shift,
	// 10 h, 11 bitmap, 12 bit, 13 index, 14 count (i32).
	private static byte[] buildInsert() {
		Asm a = Asm.function(L.ARR, L.ARR, L.ARR, L.ARR, L.EQ, L.I32, L.I32, L.I32, L.I32, L.I32, L.I32);
		a.get(2).unI31().set(9);
		a.get(3).arr().set(5);
		a.get(5).i32(E_HASH).aget().unI31().set(10);
		a.i32(1)
			.get(10)
			.get(9)
			.op(Instruction.I32_SHR_U)
			.i32(MASK)
			.op(Instruction.I32_AND)
			.op(Instruction.I32_SHL)
			.set(12);
		a.get(1).isNull().ifVoid();
		a.get(0).get(12).i31().get(5).fixed(3).ret();
		a.end();
		a.get(1).arr().set(4);
		a.get(4).i32(N_BITMAP).aget().unI31().set(11);
		a.get(11).get(12).i32(1).op(Instruction.I32_SUB).op(Instruction.I32_AND).op(Instruction.I32_POPCNT).set(13);
		a.get(11).get(12).op(Instruction.I32_AND).op(Instruction.I32_EQZ).ifVoid();
		// A new child: shift the ones after it up one slot.
		a.get(0).get(4).i32(1).i31().call(func(EDITABLE)).arr().set(7);
		a.get(11).op(Instruction.I32_POPCNT).set(14);
		a.get(7).get(13).i32(N_CHILD + 1).op(Instruction.I32_ADD);
		a.get(7).get(13).i32(N_CHILD).op(Instruction.I32_ADD);
		a.get(14).get(13).op(Instruction.I32_SUB).copy();
		a.get(7).get(13).i32(N_CHILD).op(Instruction.I32_ADD).get(5).aset();
		a.get(7).i32(N_BITMAP).get(11).get(12).op(Instruction.I32_OR).i31().aset();
		a.get(7).ret();
		a.end();
		a.get(4).get(13).i32(N_CHILD).op(Instruction.I32_ADD).aget().arr().set(6);
		a.get(6).isEntry().ifVoid();
		a.get(6).i32(E_HASH).aget().unI31().get(10).op(Instruction.I32_EQ).ifVoid();
		// The same whole hash: the fresh entry heads the chain.
		a.get(5).i32(E_NEXT).get(6).aset();
		a.get(5).set(8);
		a.elseArm();
		a.get(0).get(9).i32(BITS).op(Instruction.I32_ADD).i31().get(6).get(5).call(func(SPLIT)).set(8);
		a.end();
		a.elseArm();
		a.get(0).get(6).get(9).i32(BITS).op(Instruction.I32_ADD).i31().get(5).call(func(INSERT)).set(8);
		a.get(8).get(6).refEq().ifVoid().get(4).ret().end();
		a.end();
		a.get(0).get(4).i32(0).i31().call(func(EDITABLE)).arr().set(7);
		a.get(7).get(13).i32(N_CHILD).op(Instruction.I32_ADD).get(8).aset();
		a.get(7);
		return a.finish();
	}

	// _ht_split (rec, shiftbox, a, b) -> a node holding two chains of different hashes,
	// one level per four shared bits. Two different 30-bit hashes differ in some level.
	// Locals: 4 shift, 5 ma, 6 mb (i32).
	private static byte[] buildSplit() {
		Asm a = Asm.function(L.I32, L.I32, L.I32);
		a.get(1).unI31().set(4);
		a.get(2)
			.arr()
			.i32(E_HASH)
			.aget()
			.unI31()
			.get(4)
			.op(Instruction.I32_SHR_U)
			.i32(MASK)
			.op(Instruction.I32_AND)
			.set(5);
		a.get(3)
			.arr()
			.i32(E_HASH)
			.aget()
			.unI31()
			.get(4)
			.op(Instruction.I32_SHR_U)
			.i32(MASK)
			.op(Instruction.I32_AND)
			.set(6);
		a.get(5).get(6).op(Instruction.I32_EQ).ifVoid();
		a.get(0).i32(1).get(5).op(Instruction.I32_SHL).i31();
		a.get(0).get(4).i32(BITS).op(Instruction.I32_ADD).i31().get(2).get(3).call(func(SPLIT));
		a.fixed(3).ret();
		a.end();
		a.get(5).get(6).op(Instruction.I32_LT_U).ifArr();
		a.get(0)
			.i32(1)
			.get(5)
			.op(Instruction.I32_SHL)
			.i32(1)
			.get(6)
			.op(Instruction.I32_SHL)
			.op(Instruction.I32_OR)
			.i31()
			.get(2)
			.get(3)
			.fixed(4);
		a.elseArm();
		a.get(0)
			.i32(1)
			.get(5)
			.op(Instruction.I32_SHL)
			.i32(1)
			.get(6)
			.op(Instruction.I32_SHL)
			.op(Instruction.I32_OR)
			.i31()
			.get(3)
			.get(2)
			.fixed(4);
		a.end();
		return a.finish();
	}

	// _ht_without (rec, node, shiftbox, target) -> node without the target entry, nil
	// when that empties it.
	// Locals: 4 node, 5 target, 6 child, 7 owned (arrays), 8 replacement (eqref), 9
	// shift,
	// 10 h, 11 bitmap, 12 bit, 13 index, 14 count (i32).
	private static byte[] buildWithout() {
		Asm a = Asm.function(L.ARR, L.ARR, L.ARR, L.ARR, L.EQ, L.I32, L.I32, L.I32, L.I32, L.I32, L.I32);
		a.get(2).unI31().set(9);
		a.get(3).arr().set(5);
		a.get(5).i32(E_HASH).aget().unI31().set(10);
		a.get(1).arr().set(4);
		a.get(4).i32(N_BITMAP).aget().unI31().set(11);
		a.i32(1)
			.get(10)
			.get(9)
			.op(Instruction.I32_SHR_U)
			.i32(MASK)
			.op(Instruction.I32_AND)
			.op(Instruction.I32_SHL)
			.set(12);
		a.get(11).get(12).i32(1).op(Instruction.I32_SUB).op(Instruction.I32_AND).op(Instruction.I32_POPCNT).set(13);
		a.get(4).get(13).i32(N_CHILD).op(Instruction.I32_ADD).aget().arr().set(6);
		a.block();
		a.get(6).isEntry().ifVoid();
		a.get(6).get(5).get(5).i32(E_NEXT).aget().call(func(RELINK)).set(8);
		a.get(8).isNull().brIf(1);
		a.elseArm();
		a.get(0).get(6).get(9).i32(BITS).op(Instruction.I32_ADD).i31().get(5).call(func(WITHOUT)).set(8);
		a.get(8).isNull().brIf(1);
		a.get(8).get(6).refEq().ifVoid().get(4).ret().end();
		a.end();
		a.get(0).get(4).i32(0).i31().call(func(EDITABLE)).arr().set(7);
		a.get(7).get(13).i32(N_CHILD).op(Instruction.I32_ADD).get(8).aset();
		a.get(7).ret();
		a.end();
		// The child went: the last of the node's children empties it.
		a.get(11).op(Instruction.I32_POPCNT).set(14);
		a.get(14).i32(1).op(Instruction.I32_EQ).ifVoid().nul().ret().end();
		a.get(0).get(4).i32(0).i31().call(func(EDITABLE)).arr().set(7);
		a.get(7).get(13).i32(N_CHILD).op(Instruction.I32_ADD);
		a.get(7).get(13).i32(N_CHILD + 1).op(Instruction.I32_ADD);
		a.get(14).get(13).op(Instruction.I32_SUB).i32(1).op(Instruction.I32_SUB).copy();
		a.get(7).get(14).i32(N_CHILD - 1).op(Instruction.I32_ADD).nul().aset();
		a.get(7).i32(N_BITMAP).get(11).get(12).i32(-1).op(Instruction.I32_XOR).op(Instruction.I32_AND).i31().aset();
		a.get(7);
		return a.finish();
	}

	// _ht_replace (rec, node, shiftbox, old, fresh) -> node with the old entry replaced
	// by fresh (its next the old one's).
	// Locals: 5 node, 6 old, 7 child, 8 owned (arrays), 9 replacement (eqref), 10 shift,
	// 11 h, 12 bitmap, 13 bit, 14 index (i32).
	private static byte[] buildReplace() {
		Asm a = Asm.function(L.ARR, L.ARR, L.ARR, L.ARR, L.EQ, L.I32, L.I32, L.I32, L.I32, L.I32);
		a.get(2).unI31().set(10);
		a.get(3).arr().set(6);
		a.get(6).i32(E_HASH).aget().unI31().set(11);
		a.get(1).arr().set(5);
		a.get(5).i32(N_BITMAP).aget().unI31().set(12);
		a.i32(1)
			.get(11)
			.get(10)
			.op(Instruction.I32_SHR_U)
			.i32(MASK)
			.op(Instruction.I32_AND)
			.op(Instruction.I32_SHL)
			.set(13);
		a.get(12).get(13).i32(1).op(Instruction.I32_SUB).op(Instruction.I32_AND).op(Instruction.I32_POPCNT).set(14);
		a.get(5).get(14).i32(N_CHILD).op(Instruction.I32_ADD).aget().arr().set(7);
		a.get(7).isEntry().ifVoid();
		a.get(7).get(3).get(4).call(func(RELINK)).set(9);
		a.elseArm();
		a.get(0).get(7).get(10).i32(BITS).op(Instruction.I32_ADD).i31().get(3).get(4).call(func(REPLACE)).set(9);
		a.get(9).get(7).refEq().ifVoid().get(5).ret().end();
		a.end();
		a.get(0).get(5).i32(0).i31().call(func(EDITABLE)).arr().set(8);
		a.get(8).get(14).i32(N_CHILD).op(Instruction.I32_ADD).get(9).aset();
		a.get(8);
		return a.finish();
	}

	// _ht_relink (chain, target, tail) -> the chain with target replaced by tail (nil
	// for none): the entries ahead of target copied onto tail, the ones behind shared.
	// Iterative, so a chain of many keys of one hash costs no stack.
	// Locals: 3 entry, 4 prefix (arrays), 5 rest (eqref), 6 copy (array), 7 ahead, 8 i
	// (i32).
	private static byte[] buildRelink() {
		Asm a = Asm.function(L.ARR, L.ARR, L.EQ, L.ARR, L.I32, L.I32);
		a.i32(0).set(7);
		a.get(0).arr().set(3);
		a.block().loop();
		a.get(3).get(1).refEq().brIf(1);
		a.get(7).i32(1).op(Instruction.I32_ADD).set(7);
		a.get(3).i32(E_NEXT).aget().arr().set(3);
		a.br(0);
		a.end().end();
		a.get(7).op(Instruction.I32_EQZ).ifVoid().get(2).ret().end();
		a.get(7).newArray().set(4);
		a.get(0).arr().set(3);
		a.i32(0).set(8);
		a.block().loop();
		a.get(8).get(7).op(Instruction.I32_GE_S).brIf(1);
		a.get(4).get(8).get(3).aset();
		a.get(3).i32(E_NEXT).aget().arr().set(3);
		a.get(8).i32(1).op(Instruction.I32_ADD).set(8);
		a.br(0);
		a.end().end();
		a.get(2).set(5);
		a.get(7).i32(1).op(Instruction.I32_SUB).set(8);
		a.block().loop();
		a.get(8).i32(0).op(Instruction.I32_LT_S).brIf(1);
		a.get(4).get(8).aget().arr().set(3);
		a.get(3).len().newArray().set(6);
		a.get(6).i32(0).get(3).i32(0).get(3).len().copy();
		a.get(6).i32(E_NEXT).get(5).aset();
		a.get(6).set(5);
		a.get(8).i32(1).op(Instruction.I32_SUB).set(8);
		a.br(0);
		a.end().end();
		a.get(5);
		return a.finish();
	}

	// _ht_editable (rec, node, roombox) -> a node the record owns with room for `room`
	// more children: node itself when it is the record's and has the room, a grown copy
	// when it is the record's and has not, else a copy owned by the record.
	// Locals: 3 node, 4 fresh (arrays), 5 room, 6 count, 7 length (i32).
	private static byte[] buildEditable() {
		Asm a = Asm.function(L.ARR, L.ARR, L.I32, L.I32, L.I32);
		a.get(2).unI31().set(5);
		a.get(1).arr().set(3);
		a.get(3).i32(N_BITMAP).aget().unI31().op(Instruction.I32_POPCNT).set(6);
		a.get(3).i32(N_OWNER).aget().get(0).refEq().ifVoid();
		a.get(6)
			.get(5)
			.op(Instruction.I32_ADD)
			.i32(N_CHILD)
			.op(Instruction.I32_ADD)
			.get(3)
			.len()
			.op(Instruction.I32_LE_S)
			.ifVoid()
			.get(3)
			.ret()
			.end();
		// Grow to twice the children, at least the room asked.
		a.get(6).get(5).op(Instruction.I32_ADD).set(7);
		a.get(6).i32(1).op(Instruction.I32_SHL).get(7).op(Instruction.I32_GT_S).ifVoid();
		a.get(6).i32(1).op(Instruction.I32_SHL).set(7);
		a.end();
		a.get(7).i32(N_CHILD).op(Instruction.I32_ADD).newArray().set(4);
		a.get(4).i32(0).get(3).i32(0).get(6).i32(N_CHILD).op(Instruction.I32_ADD).copy();
		a.get(4).ret();
		a.end();
		a.get(6).get(5).op(Instruction.I32_ADD).i32(N_CHILD).op(Instruction.I32_ADD).newArray().set(4);
		a.get(4).i32(0).get(3).i32(0).get(6).i32(N_CHILD).op(Instruction.I32_ADD).copy();
		a.get(4).i32(N_OWNER).get(0).aset();
		a.get(4);
		return a.finish();
	}

	// _ht_vedit (rec, vnode) -> a vector node the record owns: vnode itself when it is
	// the record's, else a copy (a fresh one for nil).
	// Locals: 2 vnode, 3 fresh (arrays).
	private static byte[] buildVedit() {
		Asm a = Asm.function(L.ARR, L.ARR);
		a.get(1).isNull().ifVoid();
		a.i32(V_LEN).newArray().set(3);
		a.get(3).i32(V_OWNER).get(0).aset();
		a.get(3).ret();
		a.end();
		a.get(1).arr().set(2);
		a.get(2).i32(V_OWNER).aget().get(0).refEq().ifVoid().get(2).ret().end();
		a.i32(V_LEN).newArray().set(3);
		a.get(3).i32(0).get(2).i32(0).i32(V_LEN).copy();
		a.get(3).i32(V_OWNER).get(0).aset();
		a.get(3);
		return a.finish();
	}

	// Leaves the vector's tail offset for the size in sizeLocal: the index of the
	// tail's first slot.
	private static void emitTailOffset(Asm a, int sizeLocal) {
		a.get(sizeLocal).i32(VWIDTH).op(Instruction.I32_LT_S).ifI32();
		a.i32(0);
		a.elseArm();
		a.get(sizeLocal)
			.i32(1)
			.op(Instruction.I32_SUB)
			.i32(VBITS)
			.op(Instruction.I32_SHR_U)
			.i32(VBITS)
			.op(Instruction.I32_SHL);
		a.end();
	}

	// _ht_oappend (rec, entry) -> nil: the entry in the vector's next slot.
	// Locals: 2 rec, 3 tail, 4 children (arrays), 5 size, 6 shift (i32).
	private static byte[] buildOappend() {
		Asm a = Asm.function(L.ARR, L.ARR, L.ARR, L.I32, L.I32);
		a.get(0).arr().set(2);
		a.get(2).i32(R_VSIZE).aget().unI31().set(5);
		a.get(5);
		emitTailOffset(a, 5);
		a.op(Instruction.I32_SUB).i32(VWIDTH).op(Instruction.I32_LT_S).ifVoid();
		a.get(0).get(2).i32(R_VTAIL).aget().call(func(VEDIT)).arr().set(3);
		a.get(2).i32(R_VTAIL).get(3).aset();
		a.get(3).get(5).i32(VMASK).op(Instruction.I32_AND).i32(1).op(Instruction.I32_ADD).get(1).aset();
		a.elseArm();
		// The full tail becomes a leaf of the trie, as it is.
		a.get(2).i32(R_VSHIFT).aget().unI31().set(6);
		a.get(5)
			.i32(VBITS)
			.op(Instruction.I32_SHR_U)
			.i32(1)
			.get(6)
			.op(Instruction.I32_SHL)
			.op(Instruction.I32_GT_S)
			.ifVoid();
		a.i32(V_LEN).newArray().set(4);
		a.get(4).i32(V_OWNER).get(0).aset();
		a.get(4).i32(1).get(2).i32(R_VROOT).aget().aset();
		a.get(4).i32(2).get(0).get(6).i31().get(2).i32(R_VTAIL).aget().call(func(ONEWPATH)).aset();
		a.get(2).i32(R_VROOT).get(4).aset();
		a.get(2).i32(R_VSHIFT).get(6).i32(VBITS).op(Instruction.I32_ADD).i31().aset();
		a.elseArm();
		a.get(2).i32(R_VROOT);
		a.get(0).get(6).i31().get(2).i32(R_VROOT).aget().get(2).i32(R_VTAIL).aget().call(func(OPUSH));
		a.aset();
		a.end();
		a.i32(V_LEN).newArray().set(3);
		a.get(3).i32(V_OWNER).get(0).aset();
		a.get(3).i32(1).get(1).aset();
		a.get(2).i32(R_VTAIL).get(3).aset();
		a.end();
		a.get(2).i32(R_VSIZE).get(5).i32(1).op(Instruction.I32_ADD).i31().aset();
		a.nul();
		return a.finish();
	}

	// _ht_opush (rec, levelbox, parent, full) -> parent (owned) with the full tail at the
	// vector's next leaf position (the record's vsize is still the count before it).
	// Locals: 4 owned (array), 5 level, 6 index (i32), 7 child (eqref).
	private static byte[] buildOpush() {
		Asm a = Asm.function(L.ARR, L.I32, L.I32, L.EQ);
		a.get(1).unI31().set(5);
		a.get(0).get(2).call(func(VEDIT)).arr().set(4);
		a.get(0)
			.arr()
			.i32(R_VSIZE)
			.aget()
			.unI31()
			.i32(1)
			.op(Instruction.I32_SUB)
			.get(5)
			.op(Instruction.I32_SHR_U)
			.i32(VMASK)
			.op(Instruction.I32_AND)
			.set(6);
		a.get(5).i32(VBITS).op(Instruction.I32_EQ).ifVoid();
		a.get(4).get(6).i32(1).op(Instruction.I32_ADD).get(3).aset();
		a.elseArm();
		a.get(4).get(6).i32(1).op(Instruction.I32_ADD).aget().set(7);
		a.get(4).get(6).i32(1).op(Instruction.I32_ADD);
		a.get(7).isNull().ifEq();
		a.get(0).get(5).i32(VBITS).op(Instruction.I32_SUB).i31().get(3).call(func(ONEWPATH));
		a.elseArm();
		a.get(0).get(5).i32(VBITS).op(Instruction.I32_SUB).i31().get(7).get(3).call(func(OPUSH));
		a.end();
		a.aset();
		a.end();
		a.get(4);
		return a.finish();
	}

	// _ht_onewpath (rec, levelbox, leaf) -> the leaf under one new vector node a level.
	// Locals: 3 node (array), 4 level (i32).
	private static byte[] buildOnewpath() {
		Asm a = Asm.function(L.ARR, L.I32);
		a.get(1).unI31().set(4);
		a.get(4).op(Instruction.I32_EQZ).ifVoid().get(2).ret().end();
		a.i32(V_LEN).newArray().set(3);
		a.get(3).i32(V_OWNER).get(0).aset();
		a.get(3).i32(1).get(0).get(4).i32(VBITS).op(Instruction.I32_SUB).i31().get(2).call(func(ONEWPATH)).aset();
		a.get(3);
		return a.finish();
	}

	// _ht_oset (rec, posbox, entry) -> nil: the vector's slot pos holds entry (nil for a
	// dead slot).
	// Locals: 3 rec, 4 tail (arrays), 5 pos, 6 size (i32).
	private static byte[] buildOset() {
		Asm a = Asm.function(L.ARR, L.ARR, L.I32, L.I32);
		a.get(0).arr().set(3);
		a.get(1).unI31().set(5);
		a.get(3).i32(R_VSIZE).aget().unI31().set(6);
		a.get(5);
		emitTailOffset(a, 6);
		a.op(Instruction.I32_GE_S).ifVoid();
		a.get(0).get(3).i32(R_VTAIL).aget().call(func(VEDIT)).arr().set(4);
		a.get(3).i32(R_VTAIL).get(4).aset();
		a.get(4).get(5).i32(VMASK).op(Instruction.I32_AND).i32(1).op(Instruction.I32_ADD).get(2).aset();
		a.nul().ret();
		a.end();
		a.get(3).i32(R_VROOT);
		a.get(0).get(3).i32(R_VSHIFT).aget().get(3).i32(R_VROOT).aget().get(1).get(2).call(func(OASSOC));
		a.aset();
		a.nul();
		return a.finish();
	}

	// _ht_oassoc (rec, levelbox, vnode, posbox, entry) -> vnode (owned) holding entry at
	// pos.
	// Locals: 5 owned (array), 6 level, 7 pos, 8 index (i32).
	private static byte[] buildOassoc() {
		Asm a = Asm.function(L.ARR, L.I32, L.I32, L.I32);
		a.get(1).unI31().set(6);
		a.get(3).unI31().set(7);
		a.get(0).get(2).call(func(VEDIT)).arr().set(5);
		a.get(6).op(Instruction.I32_EQZ).ifVoid();
		a.get(5).get(7).i32(VMASK).op(Instruction.I32_AND).i32(1).op(Instruction.I32_ADD).get(4).aset();
		a.elseArm();
		a.get(7).get(6).op(Instruction.I32_SHR_U).i32(VMASK).op(Instruction.I32_AND).set(8);
		a.get(5).get(8).i32(1).op(Instruction.I32_ADD);
		a.get(0).get(6).i32(VBITS).op(Instruction.I32_SUB).i31();
		a.get(5).get(8).i32(1).op(Instruction.I32_ADD).aget();
		a.get(3).get(4).call(func(OASSOC));
		a.aset();
		a.end();
		a.get(5);
		return a.finish();
	}

	/**
	 * Builds {@code _ht_entries (header) -> array} ({@code FUNC_HT_ENTRIES}): the table's
	 * live entries in insertion order, a fresh array -- the snapshot {@code maphash}
	 * walks, so storing into the table or removing the current key from inside the walk
	 * changes neither the array nor which entries it holds.
	 * @param tagged whether counts carry the test tag
	 * @return the encoded body
	 */
	static byte[] buildEntries(boolean tagged) {
		// Locals: 1 rec, 2 out, 3 leaf, 4 node (arrays), 5 entry (eqref), 6 size, 7 base,
		// 8 tail offset, 9 level, 10 i, 11 end, 12 kept (i32).
		Asm a = Asm.function(L.ARR, L.ARR, L.ARR, L.ARR, L.EQ, L.I32, L.I32, L.I32, L.I32, L.I32, L.I32, L.I32);
		a.get(0).cdr().arr().set(1);
		a.get(0).car().unI31();
		if (tagged) {
			a.i32(2).op(Instruction.I32_SHR_S);
		}
		a.newArray().set(2);
		emitIsFlat(a, 1);
		a.ifVoid();
		// Flat: the order array's live slots.
		a.get(1).i32(F_ORDER).aget().arr().set(3);
		a.get(1).i32(F_SIZE).aget().unI31().set(6);
		a.i32(0).set(10);
		a.i32(0).set(12);
		a.block().loop();
		a.get(10).get(6).op(Instruction.I32_GE_S).brIf(1);
		a.get(3).get(10).aget().set(5);
		a.get(5).isNull().op(Instruction.I32_EQZ).ifVoid();
		a.get(2).get(12).get(5).aset();
		a.get(12).i32(1).op(Instruction.I32_ADD).set(12);
		a.end();
		a.get(10).i32(1).op(Instruction.I32_ADD).set(10);
		a.br(0);
		a.end().end();
		a.get(2).ret();
		a.end();
		a.get(1).i32(R_VSIZE).aget().unI31().set(6);
		emitTailOffset(a, 6);
		a.set(8);
		a.i32(0).set(7);
		a.i32(0).set(12);
		a.block().loop();
		a.get(7).get(6).op(Instruction.I32_GE_S).brIf(1);
		a.get(7).get(8).op(Instruction.I32_GE_S).ifVoid();
		a.get(1).i32(R_VTAIL).aget().arr().set(3);
		a.elseArm();
		a.get(1).i32(R_VROOT).aget().arr().set(4);
		a.get(1).i32(R_VSHIFT).aget().unI31().set(9);
		a.block().loop();
		a.get(9).i32(0).op(Instruction.I32_LE_S).brIf(1);
		a.get(4)
			.get(7)
			.get(9)
			.op(Instruction.I32_SHR_U)
			.i32(VMASK)
			.op(Instruction.I32_AND)
			.i32(1)
			.op(Instruction.I32_ADD)
			.aget()
			.arr()
			.set(4);
		a.get(9).i32(VBITS).op(Instruction.I32_SUB).set(9);
		a.br(0);
		a.end().end();
		a.get(4).set(3);
		a.end();
		a.get(6).get(7).op(Instruction.I32_SUB).set(11);
		a.get(11).i32(VWIDTH).op(Instruction.I32_GT_S).ifVoid().i32(VWIDTH).set(11).end();
		a.i32(0).set(10);
		a.block().loop();
		a.get(10).get(11).op(Instruction.I32_GE_S).brIf(1);
		a.get(3).get(10).i32(1).op(Instruction.I32_ADD).aget().set(5);
		a.get(5).isNull().op(Instruction.I32_EQZ).ifVoid();
		a.get(2).get(12).get(5).aset();
		a.get(12).i32(1).op(Instruction.I32_ADD).set(12);
		a.end();
		a.get(10).i32(1).op(Instruction.I32_ADD).set(10);
		a.br(0);
		a.end().end();
		a.get(7).i32(VWIDTH).op(Instruction.I32_ADD).set(7);
		a.br(0);
		a.end().end();
		a.get(2);
		return a.finish();
	}

	// _ht_rebuild (rec) -> nil: the trie and the vector built again from the live
	// entries in their order, the dead slots gone. An entry the record owns is reused,
	// any other copied (keeping its owner, so a value another table shares stays one
	// this table copies before changing).
	// Locals: 1 rec, 2 by position, 3 entry, 4 copy (arrays), 5 n, 6 i (i32).
	private static byte[] buildRebuild() {
		Asm a = Asm.function(L.ARR, L.ARR, L.ARR, L.ARR, L.I32, L.I32);
		a.get(0).arr().set(1);
		a.get(1).i32(R_VSIZE).aget().unI31().set(5);
		a.get(5).newArray().set(2);
		a.get(1).i32(R_ROOT).aget().isNull().op(Instruction.I32_EQZ).ifVoid();
		a.get(1).i32(R_ROOT).aget().get(2).call(func(COLLECT)).drop();
		a.end();
		a.get(1).i32(R_ROOT).nul().aset();
		a.get(1).i32(R_VROOT).nul().aset();
		a.get(1).i32(R_VTAIL).nul().aset();
		a.get(1).i32(R_VSIZE).i32(0).i31().aset();
		a.get(1).i32(R_VSHIFT).i32(VBITS).i31().aset();
		a.get(1).i32(R_DEAD).i32(0).i31().aset();
		a.i32(0).set(6);
		a.block().loop();
		a.get(6).get(5).op(Instruction.I32_GE_S).brIf(1);
		a.get(2).get(6).aget().arr().set(3);
		a.get(3).isNull().op(Instruction.I32_EQZ).ifVoid();
		a.get(3).i32(E_OWNER).aget().get(0).refEq().op(Instruction.I32_EQZ).ifVoid();
		a.get(3).len().newArray().set(4);
		a.get(4).i32(0).get(3).i32(0).get(3).len().copy();
		a.get(4).set(3);
		a.end();
		a.get(3).i32(E_POS).get(1).i32(R_VSIZE).aget().aset();
		a.get(3).i32(E_NEXT).nul().aset();
		a.get(0).get(3).call(func(OAPPEND)).drop();
		a.get(1).i32(R_ROOT);
		a.get(0).get(1).i32(R_ROOT).aget().i32(0).i31().get(3).call(func(INSERT));
		a.aset();
		a.end();
		a.get(6).i32(1).op(Instruction.I32_ADD).set(6);
		a.br(0);
		a.end().end();
		a.nul();
		return a.finish();
	}

	// _ht_collect (node, byPos) -> nil: every entry of the subtree at its position.
	// Locals: 2 node, 3 child, 4 by position (arrays), 5 count, 6 i (i32).
	private static byte[] buildCollect() {
		Asm a = Asm.function(L.ARR, L.ARR, L.ARR, L.I32, L.I32);
		a.get(0).arr().set(2);
		a.get(1).arr().set(4);
		a.get(2).i32(N_BITMAP).aget().unI31().op(Instruction.I32_POPCNT).set(5);
		a.i32(0).set(6);
		a.block().loop();
		a.get(6).get(5).op(Instruction.I32_GE_S).brIf(1);
		a.get(2).get(6).i32(N_CHILD).op(Instruction.I32_ADD).aget().arr().set(3);
		a.get(3).isEntry().ifVoid();
		a.block().loop();
		a.get(3).isNull().brIf(1);
		a.get(4).get(3).i32(E_POS).aget().unI31().get(3).aset();
		a.get(3).i32(E_NEXT).aget().arr().set(3);
		a.br(0);
		a.end().end();
		a.elseArm();
		a.get(3).get(1).call(func(COLLECT)).drop();
		a.end();
		a.get(6).i32(1).op(Instruction.I32_ADD).set(6);
		a.br(0);
		a.end().end();
		a.nul();
		return a.finish();
	}

	/** The local kinds a body declares after its parameters. */
	private enum L {

		EQ, ARR, I32

	}

	/**
	 * A thin chaining layer over {@link WasmWriter} for the bodies above: one method per
	 * instruction they use, the array ones on {@code TYPE_HASH_BUCKETS}.
	 */
	private static final class Asm {

		private final ByteArrayOutputStream body;

		final WasmWriter w;

		private Asm(ByteArrayOutputStream body, WasmWriter w) {
			this.body = body;
			this.w = w;
		}

		Asm(WasmWriter w) {
			this(new am.ik.wasm.UnsynchronizedByteArrayOutputStream(), w);
		}

		// A function body whose locals after the parameters are the given kinds, in
		// order: a run of one kind is one group, the shortest encoding.
		static Asm function(L... locals) {
			ByteArrayOutputStream body = new am.ik.wasm.UnsynchronizedByteArrayOutputStream();
			WasmWriter w = new WasmWriter(body);
			int groups = 0;
			for (int i = 0; i < locals.length; i++) {
				if (i == 0 || locals[i] != locals[i - 1]) {
					groups++;
				}
			}
			w.writeUnsignedLeb128(groups);
			int i = 0;
			while (i < locals.length) {
				int run = 1;
				while (i + run < locals.length && locals[i + run] == locals[i]) {
					run++;
				}
				w.writeUnsignedLeb128(run);
				switch (locals[i]) {
					case EQ -> w.writeRefType(true, Type.EQ.code());
					case ARR -> w.writeRefType(true, T);
					case I32 -> w.write(Type.I32);
				}
				i += run;
			}
			return new Asm(body, w);
		}

		byte[] finish() {
			this.w.write(Instruction.END);
			return this.body.toByteArray();
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

		Asm i32(int value) {
			this.w.write(Instruction.I32_CONST);
			this.w.writeSignedLeb128(value);
			return this;
		}

		Asm call(int function) {
			this.w.write(Instruction.CALL);
			this.w.writeUnsignedLeb128(function);
			return this;
		}

		Asm drop() {
			return op(Instruction.DROP);
		}

		Asm ret() {
			return op(Instruction.RETURN);
		}

		Asm block() {
			this.w.write(Instruction.BLOCK);
			this.w.write(0x40);
			return this;
		}

		Asm loop() {
			this.w.write(Instruction.LOOP);
			this.w.write(0x40);
			return this;
		}

		Asm ifVoid() {
			this.w.write(Instruction.IF);
			this.w.write(0x40);
			return this;
		}

		Asm ifI32() {
			this.w.write(Instruction.IF);
			this.w.write(Type.I32);
			return this;
		}

		Asm ifEq() {
			this.w.write(Instruction.IF);
			this.w.writeRefType(true, Type.EQ.code());
			return this;
		}

		Asm ifArr() {
			this.w.write(Instruction.IF);
			this.w.writeRefType(true, T);
			return this;
		}

		Asm elseArm() {
			return op(Instruction.ELSE);
		}

		Asm end() {
			return op(Instruction.END);
		}

		Asm br(int depth) {
			this.w.write(Instruction.BR);
			this.w.writeUnsignedLeb128(depth);
			return this;
		}

		Asm brIf(int depth) {
			this.w.write(Instruction.BR_IF);
			this.w.writeUnsignedLeb128(depth);
			return this;
		}

		Asm nul() {
			this.w.write(Instruction.REF_NULL);
			this.w.writeHeapType(Type.EQ.code());
			return this;
		}

		Asm isNull() {
			return op(Instruction.REF_IS_NULL);
		}

		Asm refEq() {
			return op(Instruction.REF_EQ);
		}

		Asm refTest(int heapType) {
			gc(Instruction.REF_TEST);
			this.w.writeHeapType(heapType);
			return this;
		}

		Asm isI31() {
			return refTest(Type.I31.code());
		}

		// An eqref to a nullable TYPE_HASH_BUCKETS reference.
		Asm arr() {
			gc(REF_CAST_NULL);
			this.w.writeHeapType(T);
			return this;
		}

		// Whether the array on the stack -- a trie child -- is an entry: its slot 0 is
		// the
		// i31 hash, where a node's is its owner record.
		Asm isEntry() {
			return i32(0).aget().isI31();
		}

		Asm aget() {
			gc(Instruction.ARRAY_GET);
			this.w.writeUnsignedLeb128(T);
			return this;
		}

		Asm aset() {
			gc(Instruction.ARRAY_SET);
			this.w.writeUnsignedLeb128(T);
			return this;
		}

		Asm len() {
			return gc(Instruction.ARRAY_LEN);
		}

		// array.copy [dst, dstIndex, src, srcIndex, count]
		Asm copy() {
			gc(Instruction.ARRAY_COPY);
			this.w.writeUnsignedLeb128(T);
			this.w.writeUnsignedLeb128(T);
			return this;
		}

		// array.fill [array, index, value, count]
		Asm fill() {
			gc(Instruction.ARRAY_FILL);
			this.w.writeUnsignedLeb128(T);
			return this;
		}

		// [length] -> a fresh array of nils
		Asm newArray() {
			gc(Instruction.ARRAY_NEW_DEFAULT);
			this.w.writeUnsignedLeb128(T);
			return this;
		}

		Asm fixed(int count) {
			gc(Instruction.ARRAY_NEW_FIXED);
			this.w.writeUnsignedLeb128(T);
			this.w.writeUnsignedLeb128(count);
			return this;
		}

		Asm i31() {
			return gc(Instruction.I31_REF_NEW);
		}

		Asm unI31() {
			gc(Instruction.REF_CAST);
			this.w.writeHeapType(Type.I31.code());
			return gc(Instruction.I31_GET_S);
		}

		Asm castCons() {
			gc(Instruction.REF_CAST);
			this.w.writeHeapType(WasmLispCompiler.TYPE_CONS);
			return this;
		}

		Asm car() {
			castCons();
			gc(Instruction.STRUCT_GET);
			this.w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
			this.w.writeUnsignedLeb128(0);
			return this;
		}

		Asm cdr() {
			castCons();
			gc(Instruction.STRUCT_GET);
			this.w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
			this.w.writeUnsignedLeb128(1);
			return this;
		}

		// [cons, value] -> car set
		Asm setCar() {
			gc(Instruction.STRUCT_SET);
			this.w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
			this.w.writeUnsignedLeb128(0);
			return this;
		}

		// [cons, value] -> cdr set
		Asm setCdr() {
			gc(Instruction.STRUCT_SET);
			this.w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
			this.w.writeUnsignedLeb128(1);
			return this;
		}

		Asm cellValue() {
			gc(Instruction.REF_CAST);
			this.w.writeHeapType(WasmLispCompiler.TYPE_CELL);
			gc(Instruction.STRUCT_GET);
			this.w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CELL);
			this.w.writeUnsignedLeb128(0);
			return this;
		}

		// A symbol or string's interned offset (TYPE_STRING's field 0).
		Asm stringOffset() {
			gc(Instruction.REF_CAST);
			this.w.writeHeapType(WasmLispCompiler.TYPE_STRING);
			gc(Instruction.STRUCT_GET);
			this.w.writeUnsignedLeb128(WasmLispCompiler.TYPE_STRING);
			this.w.writeUnsignedLeb128(0);
			return this;
		}

		// h ^= h >>> shift, on a local
		Asm xorShift(int local, int shift) {
			return get(local).get(local).i32(shift).op(Instruction.I32_SHR_U).op(Instruction.I32_XOR).set(local);
		}

	}

}
