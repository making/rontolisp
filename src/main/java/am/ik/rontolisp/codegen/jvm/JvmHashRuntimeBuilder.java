package am.ik.rontolisp.codegen.jvm;

import java.lang.classfile.TypeKind;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.FieldRefEntry;
import java.lang.classfile.constantpool.MemberRefEntry;
import java.lang.classfile.constantpool.MethodRefEntry;
import java.lang.classfile.constantpool.StringEntry;
import java.lang.classfile.constantpool.Utf8Entry;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import am.ik.jvm.ConstantPool;
import am.ik.jvm.MethodCode;
import am.ik.rontolisp.LispEquality;
import am.ik.rontolisp.runtime.RontoHashTable;

/**
 * Builds the JVM bytecode for the hash-table runtime helpers.
 *
 * <p>
 * A hash table is a {@link #MAP_CLASS} ({@code runtime/RontoHashTable}): a chained table
 * -- a trie of hash chains once it has been copied -- the travelling class keeps, keyed
 * by the structural hash {@code _hash} gives a key. The class cannot compare two keys --
 * the test is the program's own {@code _equal} or {@code _eqv} -- so each helper here
 * hashes the key, asks the class for the chain ({@code find}), walks it with the table's
 * test, and hands the class back the entry it matched ({@code setValue}, {@code remove})
 * or a fresh pair ({@code add}). Separating placement from comparison is what a hash
 * table is: the table used to key on the {@code prin1} TEXT of the key, which cost the
 * size of the key's whole graph on every lookup and never terminated on a cyclic key at
 * all.
 *
 * <p>
 * An entry's pair is {@code {key, value}}, or {@code {fold, value, key}} for an
 * {@code equalp} table whose fold differs from the key. The class keeps the pairs in
 * insertion order too -- which {@code maphash} walks, matching the interpreter -- and
 * shares its structure with a copy ({@code _hashCopy}, {@code .kb/hash-tables.md},
 * "Copies share structure").
 *
 * <p>
 * The generated static helpers (all gated on the program actually using hash tables):
 * <ul>
 * <li>{@code _hash(key, depth, gas)} -&gt; the structural hash, capped at {@code depth}
 * levels so a cyclic key terminates and at {@code gas} node visits so a key with SHARED
 * substructure does not cost the exponentially many paths through it</li>
 * <li>{@code _hashMake()} -&gt; a fresh table</li>
 * <li>{@code _hashGet(key, table, default)} -&gt; the stored value or the default</li>
 * <li>{@code _hashPut(key, table, value)} -&gt; the stored value</li>
 * <li>{@code _hashRem(key, table)} -&gt; t if an entry was removed, else nil</li>
 * <li>{@code _hashClr(table)} -&gt; the table</li>
 * <li>{@code _hashCount(table)} -&gt; the entry count</li>
 * <li>{@code _hashSize(table)} -&gt; the same count as a bare int, for the printer</li>
 * <li>{@code _hashP(x)} -&gt; t if x is a hash table, else nil</li>
 * <li>{@code _hashValues(table)} -&gt; the entry pairs as an {@code Object[]} (for
 * {@code maphash})</li>
 * <li>{@code _hashCopy(table)} -&gt; a fresh {@code equal} table of the entries
 * ({@code rontolisp::%copy-equal-table})</li>
 * </ul>
 *
 * <p>
 * Three more ride on a gate of their own, the program writing {@code :test 'equalp}
 * somewhere ({@code .kb/hash-tables.md}): {@code _hashMakeP()} makes a table whose keys
 * are FOLDED, {@code _hashEqp(table)} answers whether a table is one (what the printer
 * and {@code hash-table-test} read), and {@code _hashKey(key, table)} folds a key through
 * the travelling {@code RontoHashTable.equalpKey} when the table asks for it -- which
 * {@code _hashGet}/{@code _hashPut}/{@code _hashRem} then do before they place or look
 * up. Placement stays ONE structural table: {@code equalp} on two values is {@code equal}
 * on their folds.
 */
final class JvmHashRuntimeBuilder {

	/**
	 * The runtime class of a Lisp hash table, a class of the program's own runtime: no
	 * host value is ever one, so {@code _hashP} and the printer tell a Lisp table from a
	 * host map a {@code java:} call returned by the class alone.
	 */
	static final String MAP_CLASS = RontoHashTable.MAP_CLASS;

	static final String HASH = "_hash";

	static final String HASH_DESC = "(Ljava/lang/Object;I[I)I";

	static final String MAKE = "_hashMake";

	static final String MAKE_DESC = "()Ljava/lang/Object;";

	static final String GET = "_hashGet";

	static final String GET_DESC = "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";

	static final String PUT = "_hashPut";

	static final String PUT_DESC = "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";

	static final String REM = "_hashRem";

	static final String REM_DESC = "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";

	static final String CLR = "_hashClr";

	static final String CLR_DESC = "(Ljava/lang/Object;)Ljava/lang/Object;";

	static final String COUNT = "_hashCount";

	static final String COUNT_DESC = "(Ljava/lang/Object;)Ljava/lang/Object;";

	static final String SIZE = "_hashSize";

	static final String SIZE_DESC = "(Ljava/lang/Object;)I";

	static final String P = "_hashP";

	static final String P_DESC = "(Ljava/lang/Object;)Ljava/lang/Object;";

	static final String VALUES = "_hashValues";

	static final String VALUES_DESC = "(Ljava/lang/Object;)[Ljava/lang/Object;";

	/**
	 * Copies a table into a fresh {@code equal} one
	 * ({@code rontolisp::%copy-equal-table}, the copy every Clojure map verb takes): an
	 * {@code equal} table in O(1), sharing its structure; any other test's re-placed key
	 * by key.
	 */
	static final String COPY = "_hashCopy";

	static final String COPY_DESC = "(Ljava/lang/Object;)Ljava/lang/Object;";

	static final String MAKE_EQUALP = "_hashMakeP";

	static final String MAKE_EQUALP_DESC = "()Ljava/lang/Object;";

	/**
	 * Makes a table whose aggregates key by identity ({@code :test 'eq}).
	 */
	static final String MAKE_EQ = "_hashMakeEq";

	static final String MAKE_EQ_DESC = "()Ljava/lang/Object;";

	/**
	 * Makes a table whose aggregates key by identity and whose numbers compare by type
	 * and value ({@code :test 'eql}).
	 */
	static final String MAKE_EQL = "_hashMakeEql";

	static final String MAKE_EQL_DESC = "()Ljava/lang/Object;";

	/**
	 * Answers the test code ({@code LispHashTable TEST_*} integers: 0 equal, 1 equalp, 2
	 * eql, 3 eq) a table's lookups implement.
	 */
	static final String TEST = "_hashTest";

	static final String TEST_DESC = "(Ljava/lang/Object;)I";

	static final String KEY = "_hashKey";

	static final String KEY_DESC = "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";

	static final String EQUALP_P = "_hashEqp";

	static final String EQUALP_P_DESC = "(Ljava/lang/Object;)Ljava/lang/Object;";

	/**
	 * The travelling classes a table is made of -- plain Java over the JVM value model:
	 * the table, its two shapes (flat, and the trie with its nodes and order-vector
	 * nodes), its chain entries, and the {@code equalp} fold, which a bytecode
	 * transcription of the same walk would only make harder to keep in step with the
	 * interpreter's. They go BESIDE a compiled program that uses hash tables, like the
	 * handle and served-request runtimes ({@code .kb/jvm-export.md}, "What travels");
	 * every other program still compiles to exactly one file.
	 */
	static final List<String> RUNTIME_CLASS_FILES = List.of("am/ik/rontolisp/runtime/RontoHashTable.class",
			"am/ik/rontolisp/runtime/RontoHashTable$Entry.class", "am/ik/rontolisp/runtime/RontoHashTable$Flat.class",
			"am/ik/rontolisp/runtime/RontoHashTable$Trie.class", "am/ik/rontolisp/runtime/RontoHashTable$Node.class",
			"am/ik/rontolisp/runtime/RontoHashTable$VNode.class");

	/**
	 * Every method name {@link #build} emits for the {@code equal} placement every table
	 * shares, i.e. exactly the group the hash gate switches on and off;
	 * {@code JvmLispCompiler} matches an unresolved own-class call against it to
	 * recognize an under-predicted gate. Pinned by {@code JvmRuntimeGroupNamesTest}.
	 */
	static final Set<String> METHOD_NAMES = Set.of(HASH, MAKE, GET, PUT, REM, CLR, COUNT, SIZE, P, VALUES, COPY);

	/**
	 * The three helpers the {@code equalp} fold adds on top, emitted only for a program
	 * that writes {@code :test 'equalp}. Its own roster because its own gate switches it:
	 * an unresolved call to one of these says the FOLD gate under-predicted, not the hash
	 * gate.
	 */
	static final Set<String> EQUALP_METHOD_NAMES = Set.of(MAKE_EQUALP, KEY, EQUALP_P);

	/**
	 * The helpers identity tables add on top, emitted only for a program that writes
	 * {@code :test 'eq} or {@code :test 'eql}. Its own roster because its own gate
	 * switches it, beside the fold's.
	 */
	static final Set<String> IDENTITY_METHOD_NAMES = Set.of(MAKE_EQ, MAKE_EQL, TEST);

	private static final String TABLE_DESC = "L" + MAP_CLASS + ";";

	private static final String ENTRY_DESC = "L" + RontoHashTable.ENTRY_CLASS + ";";

	/** A hash-table helper method body ready to be emitted into the generated class. */
	record HashMethod(Utf8Entry name, Utf8Entry desc, MethodCode code) {
	}

	/**
	 * The travelling class's members the helpers call, and the chain fields they read.
	 *
	 * @param table the table class
	 * @param find {@code find(table, hash)}: the chain of a hash, or {@code END}
	 * @param putOrChain {@code putOrChain(table, hash, key, value, written)}: stores a
	 * key of a hash no key has, else answers the chain of the hash
	 * @param add {@code add(table, hash, pair)}: stores a fresh key of a hash a key has
	 * @param setValue {@code setValue(table, entry, value)}
	 * @param remove {@code remove(table, entry)}
	 * @param newTable {@code newTable(test)}
	 * @param test {@code test(table)}
	 * @param count {@code count(table)}
	 * @param liveValues {@code liveValues(table)}: the pairs in insertion order
	 * @param end the chain terminator {@code END}
	 * @param hash an entry's hash
	 * @param pair an entry's pair
	 * @param next an entry's successor in its chain
	 */
	private record Runtime(ClassEntry table, MethodRefEntry find, MethodRefEntry putOrChain, MethodRefEntry add,
			MethodRefEntry setValue, MethodRefEntry remove, MethodRefEntry newTable, MethodRefEntry test,
			MethodRefEntry count, MethodRefEntry liveValues, FieldRefEntry end, FieldRefEntry hash, FieldRefEntry pair,
			FieldRefEntry next) {

		static Runtime of(ConstantPool cp) {
			ClassEntry table = cp.classEntry(MAP_CLASS);
			ClassEntry entry = cp.classEntry(RontoHashTable.ENTRY_CLASS);
			return new Runtime(table, cp.methodRef(table, "find", "(" + TABLE_DESC + "I)" + ENTRY_DESC),
					cp.methodRef(table, "putOrChain",
							"(" + TABLE_DESC + "ILjava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)" + ENTRY_DESC),
					cp.methodRef(table, "add", "(" + TABLE_DESC + "I[Ljava/lang/Object;)V"),
					cp.methodRef(table, "setValue", "(" + TABLE_DESC + ENTRY_DESC + "Ljava/lang/Object;)V"),
					cp.methodRef(table, "remove", "(" + TABLE_DESC + ENTRY_DESC + ")V"),
					cp.methodRef(table, "newTable", "(I)" + TABLE_DESC),
					cp.methodRef(table, "test", "(" + TABLE_DESC + ")I"),
					cp.methodRef(table, "count", "(" + TABLE_DESC + ")I"),
					cp.methodRef(table, "liveValues", "(" + TABLE_DESC + ")[Ljava/lang/Object;"),
					cp.fieldRef(table, "END", ENTRY_DESC), cp.fieldRef(entry, "hash", "I"),
					cp.fieldRef(entry, "pair", "[Ljava/lang/Object;"), cp.fieldRef(entry, "next", ENTRY_DESC));
		}

		// if (entry in entrySlot).hash != the hash in hashSlot goto skip: a flat table's
		// bucket holds other hashes too.
		void skipOtherHash(MethodCode a, int entrySlot, int hashSlot, MethodCode.Label skip) {
			a.aload(entrySlot);
			a.getfield(this.hash);
			a.iload(hashSlot);
			a.if_icmpne(skip);
		}

		// Pushes the table in local `slot`, cast to the table class.
		void load(MethodCode a, int slot) {
			a.aload(slot);
			a.checkcast(this.table);
		}

	}

	private JvmHashRuntimeBuilder() {
	}

	/**
	 * Builds the helper bodies.
	 * @param cp the constant pool to populate
	 * @param thisClass the generated class
	 * @param objectClass {@code java.lang.Object}
	 * @param objectArrayClass {@code Object[]}, the shape of a cons cell and of an entry
	 * pair
	 * @param longValueOf {@code Long.valueOf(long)}
	 * @param equalMethod the recursive {@code _equal} predicate the chain scan decides
	 * with
	 * @param eqvMethod the {@code _eqv} (eql, and so eq) predicate an eql or eq table's
	 * chain scan decides with; always emitted, like {@code equalMethod}
	 * @param strvMethod the {@code _strv} character-vector normalizer, or null when the
	 * program uses no arrays; when present {@code _hash} folds a character vector as the
	 * string with the same content, which is what {@code _equal} compares it as
	 * @param stringArrayClass {@code String[]}, the interned layout of an instance, or
	 * null when the program can build none
	 * @param equalpFold whether the program writes {@code :test 'equalp} somewhere, in
	 * which case the three fold helpers are emitted and the get/put/remove trio runs
	 * every key through {@link #KEY} first
	 * @param identityTables whether the program writes {@code :test 'eq} or
	 * {@code :test 'eql} somewhere, in which case the identity makers and the test reader
	 * are emitted and the get/put/remove trio compares and hashes by the table's own test
	 * @param hostTest in a {@code java:} program, the shared host-object test
	 * ({@code JvmJavaDirectSites#host}): an eql/eq table hashes a host object by
	 * identity, as {@code _eqv} compares it, and {@code _hash} (an equal table's) by its
	 * own {@code hashCode}, as {@code _equal} compares it by {@code equals}; null
	 * elsewhere
	 * @return the helper methods
	 */
	static List<HashMethod> build(ConstantPool cp, ClassEntry thisClass, ClassEntry objectClass,
			ClassEntry objectArrayClass, MemberRefEntry longValueOf, MemberRefEntry equalMethod,
			MemberRefEntry eqvMethod, @Nullable MemberRefEntry strvMethod, @Nullable ClassEntry stringArrayClass,
			boolean equalpFold, boolean identityTables, @Nullable MemberRefEntry hostTest) {
		Runtime rt = Runtime.of(cp);
		ClassEntry listClass = cp.classEntry("java/util/ArrayList");
		ClassEntry integerClass = cp.classEntry("java/lang/Integer");
		ClassEntry ratArrClass = cp.classEntry("[Ljava/math/BigInteger;");
		ClassEntry intArrClass = cp.classEntry("[I");
		MethodRefEntry identityHashCode = cp.methodRef(cp.classEntry("java/lang/System"), "identityHashCode",
				"(Ljava/lang/Object;)I");
		MethodRefEntry objectHashCode = cp.methodRef(objectClass, "hashCode", "()I");
		MethodRefEntry hashRef = cp.methodRef(thisClass, HASH, HASH_DESC);
		// The compiled representation of the boolean t is the symbol "T" (a bare String).
		StringEntry trueStr = cp.stringEntry("T");
		// The equalp fold: the key each of get/put/remove places by. Null in a program
		// that writes no equalp table, which is then emitted as it was before the fold
		// existed.
		final @Nullable MethodRefEntry keyRef = equalpFold ? cp.methodRef(thisClass, KEY, KEY_DESC) : null;
		// The test reader the identity dispatch below branches on. Emitted only for a
		// program that can build an identity table; every other program keeps the
		// bodies it had.
		final boolean anyOtherTest = equalpFold || identityTables;
		KeyHash keyHash = new KeyHash(hashRef, objectArrayClass, listClass, ratArrClass, integerClass, identityHashCode,
				identityTables, hostTest);
		Compare compare = new Compare(equalMethod, eqvMethod, identityTables);

		List<HashMethod> methods = new ArrayList<>();
		methods.add(buildHash(cp, objectArrayClass, ratArrClass, intArrClass, integerClass, stringArrayClass,
				strvMethod, objectHashCode, hashRef, listClass, rt.table(), identityHashCode, hostTest));

		methods.add(new HashMethod(cp.utf8Entry(MAKE), cp.utf8Entry(MAKE_DESC), newTable(rt, 0)));
		methods.add(buildGet(cp, rt, keyRef, identityTables, keyHash, compare));
		methods.add(buildPut(cp, rt, objectClass, keyRef, identityTables, keyHash, compare));
		methods.add(buildRem(cp, rt, trueStr, keyRef, identityTables, keyHash, compare));
		methods.add(buildCopy(cp, thisClass, rt, objectArrayClass, anyOtherTest, equalpFold));

		if (identityTables) {
			methods.addAll(buildIdentityTables(cp, rt));
		}

		if (equalpFold) {
			methods.addAll(buildEqualpFold(cp, thisClass, rt, trueStr, strvMethod));
		}

		// _hashClr(table): every entry goes and the table keeps its identity and its
		// test.
		MethodCode clr = new MethodCode();
		rt.load(clr, 0);
		clr.invokestatic(cp.methodRef(rt.table(), "clear", "(" + TABLE_DESC + ")V"));
		clr.aload(0);
		clr.areturn();
		methods.add(new HashMethod(cp.utf8Entry(CLR), cp.utf8Entry(CLR_DESC), clr));

		// _hashCount(table): return Long.valueOf(count(table))
		MethodCode count = new MethodCode();
		rt.load(count, 0);
		count.invokestatic(rt.count());
		count.i2l();
		count.invokestatic(longValueOf);
		count.areturn();
		methods.add(new HashMethod(cp.utf8Entry(COUNT), cp.utf8Entry(COUNT_DESC), count));

		// _hashSize(table): the same count as a bare int (the printer's :COUNT field)
		MethodCode size = new MethodCode();
		rt.load(size, 0);
		size.invokestatic(rt.count());
		size.ireturn();
		methods.add(new HashMethod(cp.utf8Entry(SIZE), cp.utf8Entry(SIZE_DESC), size));

		// _hashP(x): return (x instanceof RontoHashTable) ? "T" : null -- in a java:
		// program too, since no host value is one.
		MethodCode hp = new MethodCode();
		hp.aload(0);
		hp.instanceOf(rt.table());
		MethodCode.Label hpFalse = hp.newLabel();
		hp.ifeq(hpFalse);
		hp.ldc(trueStr);
		hp.areturn();
		hp.labelBinding(hpFalse);
		hp.aconst_null();
		hp.areturn();
		methods.add(new HashMethod(cp.utf8Entry(P), cp.utf8Entry(P_DESC), hp));

		// _hashValues(table): the live pairs in insertion order -- what maphash walks.
		MethodCode values = new MethodCode();
		rt.load(values, 0);
		values.invokestatic(rt.liveValues());
		values.areturn();
		methods.add(new HashMethod(cp.utf8Entry(VALUES), cp.utf8Entry(VALUES_DESC), values));

		return methods;
	}

	// return RontoHashTable.newTable(test)
	private static MethodCode newTable(Runtime rt, int test) {
		MethodCode make = new MethodCode();
		make.loadConstant(test);
		make.invokestatic(rt.newTable());
		make.areturn();
		return make;
	}

	// _hash(Object v, int d, int[] gas): the structural hash _equal agrees with -- equal
	// values hash equal. d is the REMAINING depth: at zero the fold answers a constant
	// instead of descending, which is free correctness (a hash need not be injective) and
	// is what makes a cyclic key terminate. gas is the whole traversal's remaining NODE
	// budget, a one-cell array because siblings must SHARE it: a per-branch count would
	// bound nothing when the branches share their substructure, and the paths through a
	// shared graph are exponential in its height. Both caps are by depth or count alone,
	// never by anything order- or address-dependent, so two equal keys still fold
	// identically: they have the same shape, so this traversal visits them in the same
	// order and runs out of gas in the same place.
	private static HashMethod buildHash(ConstantPool cp, ClassEntry objectArrayClass, ClassEntry ratArrClass,
			ClassEntry intArrClass, ClassEntry integerClass, @Nullable ClassEntry stringArrayClass,
			@Nullable MemberRefEntry strvMethod, MethodRefEntry objectHashCode, MethodRefEntry hashRef,
			ClassEntry listClass, ClassEntry tableClass, MethodRefEntry identityHashCode,
			@Nullable MemberRefEntry hostTest) {
		MethodCode a = new MethodCode();
		// if (d <= 0) return 0
		a.iload(1);
		MethodCode.Label haveDepth = a.newLabel();
		a.ifgt(haveDepth);
		a.loadConstant(0);
		a.ireturn();
		a.labelBinding(haveDepth);
		// if (gas[0] <= 0) return 0; else gas[0]--
		a.aload(2);
		a.loadConstant(0);
		a.iaload();
		MethodCode.Label haveGas = a.newLabel();
		a.ifgt(haveGas);
		a.loadConstant(0);
		a.ireturn();
		a.labelBinding(haveGas);
		a.aload(2);
		a.loadConstant(0);
		a.dup2();
		a.iaload();
		a.loadConstant(1);
		a.isub();
		a.iastore();
		// if (v == null) return 0 -- nil
		a.aload(0);
		MethodCode.Label notNull = a.newLabel();
		a.ifnonnull(notNull);
		a.loadConstant(0);
		a.ireturn();
		a.labelBinding(notNull);
		// A mutable character vector folds as the string with the same content, which
		// is how _eqv compares it.
		if (strvMethod != null) {
			a.aload(0);
			a.invokestatic(strvMethod);
			a.astore(0);
		}
		// An instance folds its interned layout (compared by identity in _equal, so an
		// identity hash agrees) and then every slot. Checked BEFORE the cons arm, whose
		// Object[] shape an instance would otherwise satisfy.
		if (stringArrayClass != null) {
			MethodCode.Label notInstance = a.newLabel();
			a.aload(0);
			a.instanceOf(objectArrayClass);
			a.ifeq(notInstance);
			a.aload(0);
			a.checkcast(objectArrayClass);
			a.arraylength();
			a.ifeq(notInstance);
			a.aload(0);
			a.checkcast(objectArrayClass);
			a.loadConstant(0);
			a.aaload();
			a.instanceOf(stringArrayClass);
			a.ifeq(notInstance);
			a.aload(0);
			a.checkcast(objectArrayClass);
			a.astore(3);
			a.aload(3);
			a.loadConstant(0);
			a.aaload();
			a.invokevirtual(objectHashCode);
			a.istore(5);
			a.loadConstant(1);
			a.istore(4);
			MethodCode.Label slotTop = a.newLabel();
			MethodCode.Label slotDone = a.newLabel();
			a.labelBinding(slotTop);
			// Up to the layout's slot count, as _equal compares: a reserved cell past
			// the slots (a parked unread-char) must not move the key's bucket.
			a.iload(4);
			a.aload(3);
			a.loadConstant(0);
			a.aaload();
			a.checkcast(stringArrayClass);
			a.arraylength();
			a.loadConstant(2);
			a.isub();
			a.if_icmpge(slotDone);
			a.iload(5);
			a.loadConstant(31);
			a.imul();
			a.aload(3);
			a.iload(4);
			a.aaload();
			a.iload(1);
			a.loadConstant(1);
			a.isub();
			a.aload(2);
			a.invokestatic(hashRef);
			a.iadd();
			a.istore(5);
			a.iinc(4, 1);
			a.goto_(slotTop);
			a.labelBinding(slotDone);
			a.iload(5);
			a.ireturn();
			a.labelBinding(notInstance);
		}
		// A cons folds 31 * hash(car) + hash(cdr) + 1. The guard is _equal's: an
		// Object[] that is neither a ratio nor an array (whose header slot is an
		// Integer).
		MethodCode.Label notCons = a.newLabel();
		a.aload(0);
		a.instanceOf(objectArrayClass);
		a.ifeq(notCons);
		a.aload(0);
		a.instanceOf(ratArrClass);
		a.ifne(notCons);
		a.aload(0);
		a.checkcast(objectArrayClass);
		a.loadConstant(0);
		a.aaload();
		a.instanceOf(integerClass);
		a.ifne(notCons);
		a.loadConstant(31);
		a.aload(0);
		a.checkcast(objectArrayClass);
		a.loadConstant(0);
		a.aaload();
		a.iload(1);
		a.loadConstant(1);
		a.isub();
		a.aload(2);
		a.invokestatic(hashRef);
		a.imul();
		a.aload(0);
		a.checkcast(objectArrayClass);
		a.loadConstant(1);
		a.aaload();
		a.iload(1);
		a.loadConstant(1);
		a.isub();
		a.aload(2);
		a.invokestatic(hashRef);
		a.iadd();
		a.loadConstant(1);
		a.iadd();
		a.ireturn();
		a.labelBinding(notCons);
		// A character is an int[]{codepoint}; _eqv compares that code point.
		MethodCode.Label notChar = a.newLabel();
		a.aload(0);
		a.instanceOf(intArrClass);
		a.ifeq(notChar);
		a.aload(0);
		a.checkcast(intArrClass);
		a.loadConstant(0);
		a.iaload();
		a.ireturn();
		a.labelBinding(notChar);
		// A ratio is a BigInteger[]{num, den}; _eqv compares both components.
		MethodCode.Label notRatio = a.newLabel();
		a.aload(0);
		a.instanceOf(ratArrClass);
		a.ifeq(notRatio);
		a.aload(0);
		a.checkcast(ratArrClass);
		a.loadConstant(0);
		a.aaload();
		a.invokevirtual(objectHashCode);
		a.loadConstant(31);
		a.imul();
		a.aload(0);
		a.checkcast(ratArrClass);
		a.loadConstant(1);
		a.aaload();
		a.invokevirtual(objectHashCode);
		a.iadd();
		a.ireturn();
		a.labelBinding(notRatio);
		// A general vector is an ArrayList, whose own hashCode walks its contents, and a
		// hash table a RontoHashTable: equal on either is identity, so is its hash -- a
		// key filled after it was stored keeps its bucket, and a vector holding itself (a
		// cycle a Scheme printer's eq table meets) would otherwise never finish.
		MethodCode.Label identity = a.newLabel();
		MethodCode.Label notAggregate = a.newLabel();
		// A host collection is no Lisp one: _equal asks its equals, so its own hashCode
		// is what agrees.
		if (hostTest != null) {
			a.aload(0);
			a.invokestatic(hostTest);
			a.ifne(notAggregate);
		}
		a.aload(0);
		a.instanceOf(listClass);
		a.ifne(identity);
		a.aload(0);
		a.instanceOf(tableClass);
		a.ifeq(notAggregate);
		a.labelBinding(identity);
		a.aload(0);
		a.invokestatic(identityHashCode);
		a.ireturn();
		a.labelBinding(notAggregate);
		// Everything else answers with its own hashCode, which its equals agrees with
		// by the Java contract -- and which is identity exactly where _eqv's final
		// Object.equals is identity (a closure).
		a.aload(0);
		a.invokevirtual(objectHashCode);
		a.ireturn();
		return new HashMethod(cp.utf8Entry(HASH), cp.utf8Entry(HASH_DESC), a);
	}

	// _hashGet(key, table, default): walk the key's chain with the table's own test --
	// _equal, or _eqv for an eql/eq table (identity for aggregates).
	// Locals: 0 key, 1 table, 2 default, 3 the cast table, 4 entry, 5 pair, 6 test,
	// 7 hash.
	private static HashMethod buildGet(ConstantPool cp, Runtime rt, @Nullable MethodRefEntry keyRef,
			boolean identityTables, KeyHash keyHash, Compare compare) {
		MethodCode a = new MethodCode();
		emitFoldKey(a, keyRef);
		rt.load(a, 1);
		a.astore(3);
		if (identityTables) {
			a.aload(3);
			a.invokestatic(rt.test());
			a.istore(6);
		}
		keyHash.emit(a, 6);
		a.istore(7);
		a.aload(3);
		a.iload(7);
		a.invokestatic(rt.find());
		a.astore(4);
		MethodCode.Label top = a.newLabel();
		MethodCode.Label miss = a.newLabel();
		MethodCode.Label next = a.newLabel();
		a.labelBinding(top);
		a.aload(4);
		a.getstatic(rt.end());
		a.if_acmpeq(miss);
		rt.skipOtherHash(a, 4, 7, next);
		a.aload(4);
		a.getfield(rt.pair());
		a.astore(5);
		compare.emit(a, 5, 6);
		a.ifeq(next);
		a.aload(5);
		a.loadConstant(1);
		a.aaload();
		a.areturn();
		a.labelBinding(next);
		a.aload(4);
		a.getfield(rt.next());
		a.astore(4);
		a.goto_(top);
		a.labelBinding(miss);
		a.aload(2);
		a.areturn();
		return new HashMethod(cp.utf8Entry(GET), cp.utf8Entry(GET_DESC), a);
	}

	// _hashPut(key, table, value): one walk stores the fresh pair of a hash no key has;
	// else the hash's chain is scanned -- the value of the matching entry replaced, or a
	// fresh pair added to the chain. Only the value changes on a re-store, so the entry
	// keeps the position and the key it was first stored with -- what maphash walks and
	// hands back.
	// Locals: 0 key, 1 table, 2 value, 3 the cast table, 4 entry, 5 pair, 6 hash,
	// 7 test, 8 the key as written (before the fold).
	private static HashMethod buildPut(ConstantPool cp, Runtime rt, ClassEntry objectClass,
			@Nullable MethodRefEntry keyRef, boolean identityTables, KeyHash keyHash, Compare compare) {
		MethodCode a = new MethodCode();
		if (keyRef != null) {
			// The key as written, kept for a fresh pair before the fold replaces it.
			a.aload(0);
			a.astore(8);
		}
		emitFoldKey(a, keyRef);
		rt.load(a, 1);
		a.astore(3);
		if (identityTables) {
			a.aload(3);
			a.invokestatic(rt.test());
			a.istore(7);
		}
		keyHash.emit(a, 7);
		a.istore(6);
		// One walk: a hash no key has takes the fresh pair at once; else its chain comes
		// back to be scanned.
		a.aload(3);
		a.iload(6);
		a.aload(0);
		a.aload(2);
		a.aload(keyRef != null ? 8 : 0);
		a.invokestatic(rt.putOrChain());
		a.astore(4);
		MethodCode.Label top = a.newLabel();
		MethodCode.Label fresh = a.newLabel();
		MethodCode.Label next = a.newLabel();
		a.aload(4);
		a.getstatic(rt.end());
		a.if_acmpne(top);
		a.aload(2);
		a.areturn();
		a.labelBinding(top);
		a.aload(4);
		a.getstatic(rt.end());
		a.if_acmpeq(fresh);
		rt.skipOtherHash(a, 4, 6, next);
		a.aload(4);
		a.getfield(rt.pair());
		a.astore(5);
		compare.emit(a, 5, 7);
		a.ifeq(next);
		// Only the value is replaced: the pair keeps the key it was first stored under,
		// which is what maphash hands back (SBCL's choice, and every backend's).
		a.aload(3);
		a.aload(4);
		a.aload(2);
		a.invokestatic(rt.setValue());
		a.aload(2);
		a.areturn();
		a.labelBinding(next);
		a.aload(4);
		a.getfield(rt.next());
		a.astore(4);
		a.goto_(top);
		a.labelBinding(fresh);
		if (keyRef == null) {
			a.loadConstant(2);
			a.anewarray(objectClass);
			a.astore(5);
		}
		else {
			// A key its fold changed gets a third slot holding the key as written --
			// what maphash hands back (JvmHashTableCompiler.compileMaphash); a key that
			// is its own fold, and every key of a table that does not fold, keeps the
			// two-slot pair.
			MethodCode.Label ownFold = a.newLabel();
			MethodCode.Label allocated = a.newLabel();
			a.aload(0);
			a.aload(8);
			a.if_acmpeq(ownFold);
			a.loadConstant(3);
			a.anewarray(objectClass);
			a.astore(5);
			a.aload(5);
			a.loadConstant(2);
			a.aload(8);
			a.aastore();
			a.goto_(allocated);
			a.labelBinding(ownFold);
			a.loadConstant(2);
			a.anewarray(objectClass);
			a.astore(5);
			a.labelBinding(allocated);
		}
		a.aload(5);
		a.loadConstant(0);
		a.aload(0);
		a.aastore();
		a.aload(5);
		a.loadConstant(1);
		a.aload(2);
		a.aastore();
		a.aload(3);
		a.iload(6);
		a.aload(5);
		a.invokestatic(rt.add());
		a.aload(2);
		a.areturn();
		return new HashMethod(cp.utf8Entry(PUT), cp.utf8Entry(PUT_DESC), a);
	}

	// _hashRem(key, table): drop the matching entry -- the class leaves its order slot
	// dead in O(1) and rebuilds once half the slots are.
	// Locals: 0 key, 1 table, 2 the cast table, 3 entry, 4 pair, 5 test, 6 hash.
	private static HashMethod buildRem(ConstantPool cp, Runtime rt, StringEntry trueStr,
			@Nullable MethodRefEntry keyRef, boolean identityTables, KeyHash keyHash, Compare compare) {
		MethodCode a = new MethodCode();
		emitFoldKey(a, keyRef);
		rt.load(a, 1);
		a.astore(2);
		if (identityTables) {
			a.aload(2);
			a.invokestatic(rt.test());
			a.istore(5);
		}
		keyHash.emit(a, 5);
		a.istore(6);
		a.aload(2);
		a.iload(6);
		a.invokestatic(rt.find());
		a.astore(3);
		MethodCode.Label top = a.newLabel();
		MethodCode.Label miss = a.newLabel();
		MethodCode.Label next = a.newLabel();
		a.labelBinding(top);
		a.aload(3);
		a.getstatic(rt.end());
		a.if_acmpeq(miss);
		rt.skipOtherHash(a, 3, 6, next);
		a.aload(3);
		a.getfield(rt.pair());
		a.astore(4);
		compare.emit(a, 4, 5);
		a.ifeq(next);
		a.aload(2);
		a.aload(3);
		a.invokestatic(rt.remove());
		a.ldc(trueStr);
		a.areturn();
		a.labelBinding(next);
		a.aload(3);
		a.getfield(rt.next());
		a.astore(3);
		a.goto_(top);
		a.labelBinding(miss);
		a.aconst_null();
		a.areturn();
		return new HashMethod(cp.utf8Entry(REM), cp.utf8Entry(REM_DESC), a);
	}

	// _hashCopy(table): a fresh equal table of the entries in the table's order. An
	// equal table's copy shares its structure (RontoHashTable.copyEqual, O(1)); a table
	// of another test -- only in a program that can make one -- is re-placed key by key
	// through _hashPut, each key as first stored, as storing into a fresh table would.
	// Locals: 0 table, 1 the cast table, 2 the copy, 3 pairs, 4 index, 5 pair.
	private static HashMethod buildCopy(ConstantPool cp, ClassEntry thisClass, Runtime rt, ClassEntry objectArrayClass,
			boolean anyOtherTest, boolean equalpFold) {
		MethodCode a = new MethodCode();
		rt.load(a, 0);
		a.astore(1);
		MethodCode.Label replace = a.newLabel();
		if (anyOtherTest) {
			a.aload(1);
			a.invokestatic(rt.test());
			a.ifne(replace);
		}
		a.aload(1);
		a.invokestatic(cp.methodRef(rt.table(), "copyEqual", "(" + TABLE_DESC + ")" + TABLE_DESC));
		a.areturn();
		if (anyOtherTest) {
			a.labelBinding(replace);
			a.loadConstant(0);
			a.invokestatic(rt.newTable());
			a.astore(2);
			a.aload(1);
			a.invokestatic(rt.liveValues());
			a.astore(3);
			a.loadConstant(0);
			a.istore(4);
			MethodCode.Label top = a.newLabel();
			MethodCode.Label done = a.newLabel();
			a.labelBinding(top);
			a.iload(4);
			a.aload(3);
			a.arraylength();
			a.if_icmpge(done);
			a.aload(3);
			a.iload(4);
			a.aaload();
			a.checkcast(objectArrayClass);
			a.astore(5);
			// _hashPut(key as first stored, copy, value): pair[(length - 2) * 2] is slot
			// 2 of a folded equalp pair and slot 0 of every other.
			a.aload(5);
			if (equalpFold) {
				a.aload(5);
				a.arraylength();
				a.loadConstant(2);
				a.isub();
				a.loadConstant(1);
				a.ishl();
			}
			else {
				a.loadConstant(0);
			}
			a.aaload();
			a.aload(2);
			a.aload(5);
			a.loadConstant(1);
			a.aaload();
			a.invokestatic(cp.methodRef(thisClass, PUT, PUT_DESC));
			a.pop();
			a.iinc(4, 1);
			a.goto_(top);
			a.labelBinding(done);
			a.aload(2);
			a.areturn();
		}
		return new HashMethod(cp.utf8Entry(COPY), cp.utf8Entry(COPY_DESC), a);
	}

	// The equalp trio, emitted only for a program that writes :test 'equalp.
	//
	// _hashMakeP() makes a table of test 1; _hashEqp(table) answers whether a table is
	// one, which is what the printer and hash-table-test read; and _hashKey(key, table)
	// folds a key through the travelling RontoHashTable.equalpKey when the table is.
	// Placement stays ONE structural table: equalp on two values is equal on their
	// folds, so _hash and _equal are untouched.
	private static List<HashMethod> buildEqualpFold(ConstantPool cp, ClassEntry thisClass, Runtime rt,
			StringEntry trueStr, @Nullable MemberRefEntry strvMethod) {
		MethodRefEntry equalpPRef = cp.methodRef(thisClass, EQUALP_P, EQUALP_P_DESC);
		MethodRefEntry foldRef = cp.methodRef(rt.table(), "equalpKey", "(Ljava/lang/Object;I)Ljava/lang/Object;");

		List<HashMethod> methods = new ArrayList<>();

		methods.add(new HashMethod(cp.utf8Entry(MAKE_EQUALP), cp.utf8Entry(MAKE_EQUALP_DESC),
				newTable(rt, RontoHashTable.TEST_EQUALP)));

		MethodCode eqp = new MethodCode();
		eqp.aload(0);
		eqp.instanceOf(rt.table());
		MethodCode.Label notEqualp = eqp.newLabel();
		eqp.ifeq(notEqualp);
		rt.load(eqp, 0);
		eqp.invokestatic(rt.test());
		eqp.loadConstant(RontoHashTable.TEST_EQUALP);
		eqp.if_icmpne(notEqualp);
		eqp.ldc(trueStr);
		eqp.areturn();
		eqp.labelBinding(notEqualp);
		eqp.aconst_null();
		eqp.areturn();
		methods.add(new HashMethod(cp.utf8Entry(EQUALP_P), cp.utf8Entry(EQUALP_P_DESC), eqp));

		MethodCode key = new MethodCode();
		key.aload(1);
		key.invokestatic(equalpPRef);
		MethodCode.Label unfolded = key.newLabel();
		key.ifnull(unfolded);
		key.aload(0);
		// A mutable character vector renders to its quote-framed string BEFORE the
		// travelling fold: RontoHashTable.equalpKey knows the compiled value model but
		// not the array representation, so without this two same-content producer-built
		// keys fold to two distinct ArrayLists and never collide. Null without the
		// array runtime, where no character vector can exist.
		if (strvMethod != null) {
			key.invokestatic(strvMethod);
		}
		key.loadConstant(RontoHashTable.FOLD_DEPTH_CAP);
		key.invokestatic(foldRef);
		key.areturn();
		key.labelBinding(unfolded);
		key.aload(0);
		key.areturn();
		methods.add(new HashMethod(cp.utf8Entry(KEY), cp.utf8Entry(KEY_DESC), key));

		return methods;
	}

	// The identity trio, emitted only for a program that writes :test 'eq or
	// :test 'eql.
	//
	// _hashMakeEq() / _hashMakeEql() make a table of test 3 / 2; _hashTest(table)
	// answers the test code the table's lookups implement (0 equal, 1 equalp, 2 eql, 3
	// eq), which is what the get/put/remove dispatch, the printer and hash-table-test
	// read.
	private static List<HashMethod> buildIdentityTables(ConstantPool cp, Runtime rt) {
		List<HashMethod> methods = new ArrayList<>();
		methods.add(new HashMethod(cp.utf8Entry(MAKE_EQ), cp.utf8Entry(MAKE_EQ_DESC),
				newTable(rt, RontoHashTable.TEST_EQ)));
		methods.add(new HashMethod(cp.utf8Entry(MAKE_EQL), cp.utf8Entry(MAKE_EQL_DESC),
				newTable(rt, RontoHashTable.TEST_EQL)));

		MethodCode test = new MethodCode();
		test.aload(0);
		test.instanceOf(rt.table());
		MethodCode.Label notTable = test.newLabel();
		test.ifeq(notTable);
		rt.load(test, 0);
		test.invokestatic(rt.test());
		test.ireturn();
		test.labelBinding(notTable);
		test.loadConstant(0);
		test.ireturn();
		methods.add(new HashMethod(cp.utf8Entry(TEST), cp.utf8Entry(TEST_DESC), test));

		return methods;
	}

	// Replaces local 0 (the key) with its equalp fold when the table in local 1 asks for
	// one. A no-op -- not one instruction -- in a program with no equalp table.
	private static void emitFoldKey(MethodCode a, @Nullable MethodRefEntry keyRef) {
		if (keyRef == null) {
			return;
		}
		a.aload(0);
		a.aload(1);
		a.invokestatic(keyRef);
		a.astore(0);
	}

	/**
	 * The chain scan's comparison: {@code _eqv} for an eql or eq table (eq IS eql,
	 * {@code .kb/eq-numbers.md}), {@code _equal} otherwise.
	 *
	 * @param equalMethod {@code _equal}
	 * @param eqvMethod {@code _eqv}
	 * @param identityTables whether a table of the program may be eql or eq: without one
	 * the comparison is the {@code _equal} call it always was, not one instruction more
	 */
	private record Compare(MemberRefEntry equalMethod, MemberRefEntry eqvMethod, boolean identityTables) {

		// Pushes the comparison of the pair in pairSlot's slot 0 against the key in
		// local 0, the table's test code in testSlot.
		void emit(MethodCode a, int pairSlot, int testSlot) {
			if (!this.identityTables) {
				a.aload(pairSlot);
				a.loadConstant(0);
				a.aaload();
				a.aload(0);
				a.invokestatic(this.equalMethod);
				return;
			}
			MethodCode.Label eqlArm = a.newLabel();
			MethodCode.Label done = a.newLabel();
			a.iload(testSlot);
			a.loadConstant(2);
			a.if_icmpge(eqlArm);
			a.aload(pairSlot);
			a.loadConstant(0);
			a.aaload();
			a.aload(0);
			a.invokestatic(this.equalMethod);
			a.goto_(done);
			a.labelBinding(eqlArm);
			a.aload(pairSlot);
			a.loadConstant(0);
			a.aaload();
			a.aload(0);
			a.invokestatic(this.eqvMethod);
			a.labelBinding(done);
		}

	}

	/**
	 * The hash a table places a key by: {@code _hash(key, HASH_DEPTH_CAP, new
	 * int[]{HASH_WORK_CAP})}, or for an eql/eq table an aggregate key's identity hash.
	 *
	 * @param hashRef {@code _hash}
	 * @param objectArrayClass {@code Object[]}, a cons or an instance
	 * @param listClass {@code ArrayList}, every array
	 * @param ratArrClass {@code BigInteger[]}, a ratio
	 * @param integerClass {@code Integer}, an array header's slot
	 * @param identityHashCode {@code System.identityHashCode}
	 * @param identityTables whether a table of the program may be eql or eq
	 * @param hostTest the host-object test in a {@code java:} program, else null
	 */
	private record KeyHash(MethodRefEntry hashRef, ClassEntry objectArrayClass, ClassEntry listClass,
			ClassEntry ratArrClass, ClassEntry integerClass, MethodRefEntry identityHashCode, boolean identityTables,
			@Nullable MemberRefEntry hostTest) {

		// Pushes the int hash of local 0. The gas cell is allocated PER PLACEMENT, so
		// what a key hashes to is a function of that key alone and never of what the
		// table hashed before it. For an eql/eq table (testSlot >= 2) an aggregate key --
		// a cons or an instance, the values _equal would fold structurally -- hashes by
		// identity instead, so mutating it after insertion keeps its chain, and so does a
		// host object in a java: program (hostTest), which _eqv compares by identity;
		// every other key hashes exactly as an equal table's.
		void emit(MethodCode a, int testSlot) {
			if (!this.identityTables) {
				emitStructural(a);
				return;
			}
			MethodCode.Label structural = a.newLabel();
			MethodCode.Label done = a.newLabel();
			MethodCode.Label identity = a.newLabel();
			a.iload(testSlot);
			a.loadConstant(2);
			a.if_icmplt(structural);
			// An array (a List) -- a mutable character vector included, which _hash would
			// fold by content -- is identity under eql/eq, so is its hash: a fill-pointer
			// string grown after it was stored keeps its chain.
			a.aload(0);
			a.instanceOf(this.listClass);
			a.ifne(identity);
			MemberRefEntry host = this.hostTest;
			if (host != null) {
				a.aload(0);
				a.invokestatic(host);
				a.ifne(identity);
			}
			a.aload(0);
			a.instanceOf(this.objectArrayClass);
			a.ifeq(structural);
			a.aload(0);
			a.instanceOf(this.ratArrClass);
			a.ifne(structural);
			a.aload(0);
			a.checkcast(this.objectArrayClass);
			a.loadConstant(0);
			a.aaload();
			a.instanceOf(this.integerClass);
			a.ifne(structural);
			a.labelBinding(identity);
			a.aload(0);
			a.invokestatic(this.identityHashCode);
			a.goto_(done);
			a.labelBinding(structural);
			emitStructural(a);
			a.labelBinding(done);
		}

		private void emitStructural(MethodCode a) {
			a.aload(0);
			a.loadConstant(LispEquality.HASH_DEPTH_CAP);
			a.loadConstant(1);
			a.newarray(TypeKind.INT);
			a.dup();
			a.loadConstant(0);
			a.loadConstant(LispEquality.HASH_WORK_CAP);
			a.iastore();
			a.invokestatic(this.hashRef);
		}

	}

}
