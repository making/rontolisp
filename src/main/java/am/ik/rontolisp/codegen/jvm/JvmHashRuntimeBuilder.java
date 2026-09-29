package am.ik.rontolisp.codegen.jvm;

import java.lang.classfile.TypeKind;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.MemberRefEntry;
import java.lang.classfile.constantpool.MethodRefEntry;
import java.lang.classfile.constantpool.StringEntry;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import am.ik.jvm.ConstantPool;
import am.ik.jvm.ConstantPool.Utf8Constant;
import am.ik.jvm.MethodCode;
import am.ik.rontolisp.LispEquality;
import am.ik.rontolisp.runtime.RontoHashTable;

/**
 * Builds the JVM bytecode for the hash-table runtime helpers.
 *
 * <p>
 * A hash table is a {@link #MAP_CLASS} used as a BUCKET index: an entry's key is placed
 * by the structural hash {@code _hash} (boxed {@code Integer}) and decided against the
 * other keys in that bucket by the recursive {@code _equal}. A bucket is an
 * {@link #LIST_CLASS} of {@code Object[2]} pairs holding the original key and the stored
 * value. Separating placement from comparison is what a hash table is: the table used to
 * key on the {@code prin1} TEXT of the key, which cost the size of the key's whole graph
 * on every lookup and never terminated on a cyclic key at all.
 *
 * <p>
 * Insertion order -- which {@code maphash} walks, matching the interpreter -- is kept by
 * a second {@link #LIST_CLASS} holding every live entry pair in the order it was first
 * stored. It lives in the same map under {@link #ORDER_KEY}, a String key no
 * {@code Integer} bucket key can collide with, so the table stays ONE object that
 * {@code _hashP} and the printer recognise by its class alone. Because an entry pair is
 * mutated in place when an existing key is re-stored, that list needs no maintenance on
 * re-put; the pair it holds is the pair the bucket holds. A REMOVED pair is not unlinked
 * from it either -- unlinking scans and memmoves, O(n) per removal -- but tombstoned (key
 * slot nulled, counted under {@code RontoHashTable.DEAD_KEY}), and the list compacts
 * lazily; see {@code RontoHashTable} for the shape.
 *
 * <p>
 * The generated static helpers (all gated on the program actually using hash tables):
 * <ul>
 * <li>{@code _hash(key, depth, gas)} -&gt; the structural hash, capped at {@code depth}
 * levels so a cyclic key terminates and at {@code gas} node visits so a key with SHARED
 * substructure does not cost the exponentially many paths through it</li>
 * <li>{@code _hashMake()} -&gt; a fresh table</li>
 * <li>{@code _hashOrd(table)} -&gt; the insertion-order list</li>
 * <li>{@code _hashGet(key, table, default)} -&gt; the stored value or the default</li>
 * <li>{@code _hashPut(key, table, value)} -&gt; the stored value</li>
 * <li>{@code _hashRem(key, table)} -&gt; t if an entry was removed, else nil</li>
 * <li>{@code _hashClr(table)} -&gt; the table</li>
 * <li>{@code _hashCount(table)} -&gt; the entry count</li>
 * <li>{@code _hashSize(table)} -&gt; the same count as a bare int, for the printer</li>
 * <li>{@code _hashP(x)} -&gt; t if x is a hash table, else nil</li>
 * <li>{@code _hashValues(table)} -&gt; the entry pairs as an {@code Object[]} (for
 * {@code maphash})</li>
 * </ul>
 *
 * <p>
 * Three more ride on a gate of their own, the program writing {@code :test 'equalp}
 * somewhere ({@code .kb/hash-tables.md}): {@code _hashMakeP()} marks a table as one whose
 * keys are FOLDED, {@code _hashEqp(table)} answers that mark (what the printer and
 * {@code hash-table-test} read), and {@code _hashKey(key, table)} folds a key through the
 * travelling {@code RontoHashTable.equalpKey} when the table asks for it -- which
 * {@code _hashGet}/{@code _hashPut}/{@code _hashRem} then do before they place or look
 * up. Placement stays ONE structural table: {@code equalp} on two values is {@code equal}
 * on their folds.
 */
final class JvmHashRuntimeBuilder {

	/**
	 * The runtime class of a Lisp hash table. It is deliberately NOT the plain
	 * {@code java.util.HashMap} a host {@code java:} call can hand back: {@code _hashP}
	 * and the printer both discriminate a Lisp table by this exact class, so a host map
	 * stays a host object ({@code hash-table-p} is nil, it prints as {@code #<java ...>})
	 * instead of impersonating a Lisp table. Being LINKED is the second half of the
	 * choice: the bucket index iterates deterministically, which keeps a rehash-free
	 * table's internal walk independent of the host's hash spreading.
	 */
	static final String MAP_CLASS = RontoHashTable.MAP_CLASS;

	/** The runtime class of a bucket and of the insertion-order list. */
	static final String LIST_CLASS = RontoHashTable.LIST_CLASS;

	/**
	 * The key the insertion-order list hangs off inside the table. Every other key in the
	 * map is the boxed {@code Integer} hash of a bucket, so a String can never collide
	 * with one.
	 */
	static final String ORDER_KEY = RontoHashTable.ORDER_KEY;

	static final String HASH = "_hash";

	static final String HASH_DESC = "(Ljava/lang/Object;I[I)I";

	static final String MAKE = "_hashMake";

	static final String MAKE_DESC = "()Ljava/lang/Object;";

	static final String ORD = "_hashOrd";

	static final String ORD_DESC = "(Ljava/lang/Object;)Ljava/util/ArrayList;";

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
	 * eql, 3 eq) a table's lookups implement, read off its marker keys.
	 */
	static final String TEST = "_hashTest";

	static final String TEST_DESC = "(Ljava/lang/Object;)I";

	static final String KEY = "_hashKey";

	static final String KEY_DESC = "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";

	static final String EQUALP_P = "_hashEqp";

	static final String EQUALP_P_DESC = "(Ljava/lang/Object;)Ljava/lang/Object;";

	/**
	 * The travelling class the {@code equalp} fold is written in -- plain Java over the
	 * JVM value model, which a bytecode transcription of the same walk would only make
	 * harder to keep in step with the interpreter's. It goes BESIDE a compiled program
	 * that uses hash tables, like the handle and served-request runtimes
	 * ({@code .kb/jvm-export.md}, "What travels"); every other program still compiles to
	 * exactly one file.
	 */
	static final List<String> RUNTIME_CLASS_FILES = List.of("am/ik/rontolisp/runtime/RontoHashTable.class");

	/**
	 * Every method name {@link #build} emits for the {@code equal} placement every table
	 * shares, i.e. exactly the group the hash gate switches on and off;
	 * {@code JvmLispCompiler} matches an unresolved own-class call against it to
	 * recognize an under-predicted gate. Pinned by {@code JvmRuntimeGroupNamesTest}.
	 */
	static final Set<String> METHOD_NAMES = Set.of(HASH, MAKE, ORD, GET, PUT, REM, CLR, COUNT, SIZE, P, VALUES);

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

	/**
	 * The marker an {@code eq} table hangs off inside the table, beside
	 * {@link #ORDER_KEY} and {@code RontoHashTable.EQUALP_KEY}: a String key, so it
	 * collides with no {@code Integer} bucket key.
	 */
	static final String EQ_KEY = "#eq";

	/** The marker an {@code eql} table hangs off inside the table. */
	static final String EQL_KEY = "#eql";

	/** A hash-table helper method body ready to be emitted into the generated class. */
	record HashMethod(Utf8Constant name, Utf8Constant desc, MethodCode code) {
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
	 * @param equalMethod the recursive {@code _equal} predicate the bucket scan decides
	 * with
	 * @param eqvMethod the {@code _eqv} (eql, and so eq) predicate an eql or eq table's
	 * bucket scan decides with; always emitted, like {@code equalMethod}
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
	 * @param lispTable in a {@code java:} program, the shared test
	 * ({@code JvmJavaDirectSites#lispTable}) that tells a table from a host
	 * {@code LinkedHashMap} for {@code hash-table-p}; null elsewhere, where the class
	 * alone decides
	 * @return the helper methods
	 */
	static List<HashMethod> build(ConstantPool cp, ClassEntry thisClass, ClassEntry objectClass,
			ClassEntry objectArrayClass, MemberRefEntry longValueOf, MemberRefEntry equalMethod,
			MemberRefEntry eqvMethod, @Nullable MemberRefEntry strvMethod, @Nullable ClassEntry stringArrayClass,
			boolean equalpFold, boolean identityTables, @Nullable MemberRefEntry lispTable) {
		ClassEntry mapClass = cp.classEntry(MAP_CLASS);
		ClassEntry listClass = cp.classEntry(LIST_CLASS);
		ClassEntry integerClass = cp.classEntry("java/lang/Integer");
		ClassEntry ratArrClass = cp.classEntry("[Ljava/math/BigInteger;");
		ClassEntry intArrClass = cp.classEntry("[I");
		MethodRefEntry mapInit = cp.methodRef(mapClass, "<init>", "()V");
		MethodRefEntry mapGet = cp.methodRef(mapClass, "get", "(Ljava/lang/Object;)Ljava/lang/Object;");
		MethodRefEntry mapPut = cp.methodRef(mapClass, "put",
				"(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
		MethodRefEntry mapRemove = cp.methodRef(mapClass, "remove", "(Ljava/lang/Object;)Ljava/lang/Object;");
		MethodRefEntry mapClear = cp.methodRef(mapClass, "clear", "()V");
		// A BUCKET is created with room for one entry, not with ArrayList's default
		// ten: a bucket holds exactly one pair unless two structurally distinct keys
		// hash alike, and the default backing array is 56 bytes per bucket against 24
		// -- on a table with a million keys that difference IS the allocation profile.
		// The insertion-order list keeps the no-argument constructor (it grows to the
		// table's whole size).
		MethodRefEntry listInitCapacity = cp.methodRef(listClass, "<init>", "(I)V");
		MethodRefEntry listInit = cp.methodRef(listClass, "<init>", "()V");
		MethodRefEntry listAdd = cp.methodRef(listClass, "add", "(Ljava/lang/Object;)Z");
		MethodRefEntry listGet = cp.methodRef(listClass, "get", "(I)Ljava/lang/Object;");
		MethodRefEntry listSize = cp.methodRef(listClass, "size", "()I");
		MethodRefEntry listRemoveAt = cp.methodRef(listClass, "remove", "(I)Ljava/lang/Object;");
		MethodRefEntry listClear = cp.methodRef(listClass, "clear", "()V");
		MethodRefEntry integerValueOf = cp.methodRef(integerClass, "valueOf", "(I)Ljava/lang/Integer;");
		MethodRefEntry identityHashCode = cp.methodRef(cp.classEntry("java/lang/System"), "identityHashCode",
				"(Ljava/lang/Object;)I");
		MethodRefEntry objectHashCode = cp.methodRef(objectClass, "hashCode", "()I");
		MethodRefEntry hashRef = cp.methodRef(thisClass, HASH, HASH_DESC);
		MethodRefEntry ordRef = cp.methodRef(thisClass, ORD, ORD_DESC);
		StringEntry orderKey = cp.stringEntry(ORDER_KEY);
		StringEntry deadKey = cp.stringEntry(RontoHashTable.DEAD_KEY);
		ClassEntry rontoHashTableClass = cp.classEntry(RontoHashTable.class.getName().replace('.', '/'));
		ClassEntry mapInterface = cp.classEntry("java/util/Map");
		// The tombstone machinery
		// (RontoHashTable.tombstone/liveCount/liveValues/maybeCompact):
		// removals null the pair's key slot instead of unlinking the order list.
		MethodRefEntry tombstoneRef = cp.methodRef(rontoHashTableClass, "tombstone",
				"(Ljava/util/Map;[Ljava/lang/Object;)V");
		MethodRefEntry liveCountRef = cp.methodRef(rontoHashTableClass, "liveCount", "(Ljava/util/Map;)I");
		MethodRefEntry liveValuesRef = cp.methodRef(rontoHashTableClass, "liveValues",
				"(Ljava/util/Map;)[Ljava/lang/Object;");
		MethodRefEntry maybeCompactRef = cp.methodRef(rontoHashTableClass, "maybeCompact", "(Ljava/util/Map;)V");
		// The compiled representation of the boolean t is the symbol "T" (a bare String).
		StringEntry trueStr = cp.stringEntry("T");
		// The equalp fold: the key each of get/put/remove places by, and the marker the
		// table carries. Null in a program that writes no equalp table, which is then
		// emitted exactly as it was before the fold existed.
		final @Nullable MethodRefEntry keyRef = equalpFold ? cp.methodRef(thisClass, KEY, KEY_DESC) : null;

		List<HashMethod> methods = new ArrayList<>();
		methods.add(buildHash(cp, objectArrayClass, ratArrClass, intArrClass, integerClass, stringArrayClass,
				strvMethod, objectHashCode, hashRef, listClass, cp.classEntry("java/util/Map"), identityHashCode));

		// _hashMake(): m = new LinkedHashMap(); m.put(ORDER_KEY, new ArrayList());
		// m.put(DEAD_KEY, 0); return m
		MethodCode make = new MethodCode();
		make.new_(mapClass);
		make.dup();
		make.invokespecial(mapInit);
		make.dup();
		make.ldc(orderKey);
		make.new_(listClass);
		make.dup();
		make.invokespecial(listInit);
		make.invokevirtual(mapPut);
		make.pop();
		make.dup();
		make.ldc(deadKey);
		make.loadConstant(0);
		make.invokestatic(integerValueOf);
		make.invokevirtual(mapPut);
		make.pop();
		make.areturn();
		methods.add(new HashMethod(cp.addUtf8(MAKE), cp.addUtf8(MAKE_DESC), make));

		// _hashOrd(table): return (ArrayList) ((LinkedHashMap) table).get(ORDER_KEY)
		MethodCode ord = new MethodCode();
		ord.aload(0);
		ord.checkcast(mapClass);
		ord.ldc(orderKey);
		ord.invokevirtual(mapGet);
		ord.checkcast(listClass);
		ord.areturn();
		methods.add(new HashMethod(cp.addUtf8(ORD), cp.addUtf8(ORD_DESC), ord));

		// The test reader the identity dispatch below branches on. Emitted only for a
		// program that can build an identity table; every other program keeps the
		// bodies it had.
		final @Nullable MethodRefEntry testRef = identityTables ? cp.methodRef(thisClass, TEST, TEST_DESC) : null;

		methods.add(buildGet(cp, mapClass, listClass, objectArrayClass, mapGet, listGet, listSize, integerValueOf,
				hashRef, equalMethod, eqvMethod, ratArrClass, integerClass, identityHashCode, keyRef, testRef,
				identityTables));
		methods.add(buildPut(cp, mapClass, listClass, objectClass, objectArrayClass, mapGet, mapPut, listInitCapacity,
				listAdd, listGet, listSize, integerValueOf, hashRef, ordRef, equalMethod, eqvMethod, ratArrClass,
				integerClass, identityHashCode, keyRef, testRef, identityTables, maybeCompactRef));
		methods.add(buildRem(cp, mapClass, listClass, objectArrayClass, mapGet, mapRemove, listGet, listSize,
				listRemoveAt, integerValueOf, hashRef, equalMethod, eqvMethod, ratArrClass, integerClass,
				identityHashCode, trueStr, keyRef, testRef, identityTables, tombstoneRef));

		if (identityTables) {
			methods.addAll(buildIdentityTables(cp, thisClass, mapClass, mapGet, mapPut, trueStr));
		}

		if (equalpFold) {
			methods.addAll(buildEqualpFold(cp, thisClass, mapClass, mapGet, mapPut, trueStr, strvMethod));
		}

		// _hashClr(table): the order list is emptied and re-hung, so every bucket goes
		// with the clear and the table keeps its identity -- and, in a program that
		// folds or keys by identity, so does its TEST: the markers are read before the
		// clear and hung back beside the order list, or an emptied equalp table would
		// place structurally from there on.
		MethodCode clr = new MethodCode();
		if (identityTables) {
			clr.aload(0);
			clr.invokestatic(cp.methodRef(thisClass, TEST, TEST_DESC));
			clr.istore(2);
		}
		else if (equalpFold) {
			clr.aload(0);
			clr.invokestatic(cp.methodRef(thisClass, EQUALP_P, EQUALP_P_DESC));
			clr.astore(2);
		}
		clr.aload(0);
		clr.invokestatic(ordRef);
		clr.astore(1);
		clr.aload(1);
		clr.invokevirtual(listClear);
		clr.aload(0);
		clr.checkcast(mapClass);
		clr.invokevirtual(mapClear);
		clr.aload(0);
		clr.checkcast(mapClass);
		clr.ldc(orderKey);
		clr.aload(1);
		clr.invokevirtual(mapPut);
		clr.pop();
		clr.aload(0);
		clr.checkcast(mapClass);
		clr.ldc(deadKey);
		clr.loadConstant(0);
		clr.invokestatic(integerValueOf);
		clr.invokevirtual(mapPut);
		clr.pop();
		if (identityTables) {
			// Re-hang the test the table carried: 1 equalp, 2 eql, 3 eq.
			MethodCode.Label rehangEqualp = clr.newLabel();
			MethodCode.Label rehangDone = clr.newLabel();
			clr.iload(2);
			clr.loadConstant(1);
			clr.if_icmpne(rehangEqualp);
			clr.aload(0);
			clr.checkcast(mapClass);
			clr.ldc(cp.stringEntry(RontoHashTable.EQUALP_KEY));
			clr.ldc(trueStr);
			clr.invokevirtual(mapPut);
			clr.pop();
			clr.goto_(rehangDone);
			clr.labelBinding(rehangEqualp);
			MethodCode.Label rehangEql = clr.newLabel();
			clr.iload(2);
			clr.loadConstant(2);
			clr.if_icmpne(rehangEql);
			clr.aload(0);
			clr.checkcast(mapClass);
			clr.ldc(cp.stringEntry(EQL_KEY));
			clr.ldc(trueStr);
			clr.invokevirtual(mapPut);
			clr.pop();
			clr.goto_(rehangDone);
			clr.labelBinding(rehangEql);
			MethodCode.Label rehangPlain = clr.newLabel();
			clr.iload(2);
			clr.loadConstant(3);
			clr.if_icmpne(rehangPlain);
			clr.aload(0);
			clr.checkcast(mapClass);
			clr.ldc(cp.stringEntry(EQ_KEY));
			clr.ldc(trueStr);
			clr.invokevirtual(mapPut);
			clr.pop();
			clr.labelBinding(rehangPlain);
			clr.labelBinding(rehangDone);
		}
		else if (equalpFold) {
			MethodCode.Label notFolding = clr.newLabel();
			clr.aload(2);
			clr.ifnull(notFolding);
			clr.aload(0);
			clr.checkcast(mapClass);
			clr.ldc(cp.stringEntry(RontoHashTable.EQUALP_KEY));
			clr.aload(2);
			clr.invokevirtual(mapPut);
			clr.pop();
			clr.labelBinding(notFolding);
		}
		clr.aload(0);
		clr.areturn();
		methods.add(new HashMethod(cp.addUtf8(CLR), cp.addUtf8(CLR_DESC), clr));

		// _hashCount(table): return Long.valueOf(liveCount(table))
		MethodCode count = new MethodCode();
		count.aload(0);
		count.invokestatic(liveCountRef);
		count.i2l();
		count.invokestatic(longValueOf);
		count.areturn();
		methods.add(new HashMethod(cp.addUtf8(COUNT), cp.addUtf8(COUNT_DESC), count));

		// _hashSize(table): the same count as a bare int (the printer's :COUNT field)
		MethodCode size = new MethodCode();
		size.aload(0);
		size.invokestatic(liveCountRef);
		size.ireturn();
		methods.add(new HashMethod(cp.addUtf8(SIZE), cp.addUtf8(SIZE_DESC), size));

		// _hashP(x): return (x instanceof LinkedHashMap) ? "T" : null -- in a java:
		// program
		// _jltab(x), since a host map is a LinkedHashMap too.
		MethodCode hp = new MethodCode();
		hp.aload(0);
		if (lispTable != null) {
			hp.invokestatic(lispTable);
		}
		else {
			hp.instanceOf(mapClass);
		}
		MethodCode.Label hpFalse = hp.newLabel();
		hp.ifeq(hpFalse);
		hp.ldc(trueStr);
		hp.areturn();
		hp.labelBinding(hpFalse);
		hp.aconst_null();
		hp.areturn();
		methods.add(new HashMethod(cp.addUtf8(P), cp.addUtf8(P_DESC), hp));

		// _hashValues(table): the live pairs in insertion order, compacting first when
		// half dead -- what maphash walks.
		MethodCode values = new MethodCode();
		values.aload(0);
		values.invokestatic(liveValuesRef);
		values.areturn();
		methods.add(new HashMethod(cp.addUtf8(VALUES), cp.addUtf8(VALUES_DESC), values));

		return methods;
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
			ClassEntry listClass, ClassEntry mapInterface, MethodRefEntry identityHashCode) {
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
			a.iload(4);
			a.aload(3);
			a.arraylength();
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
		// A general vector is an ArrayList and a hash table a Map, whose own hashCodes
		// walk their contents: equal on either is identity, so is its hash -- a key
		// filled after it was stored keeps its bucket, and a vector holding itself (a
		// cycle a Scheme printer's eq table meets) would otherwise never finish.
		MethodCode.Label identity = a.newLabel();
		MethodCode.Label notAggregate = a.newLabel();
		a.aload(0);
		a.instanceOf(listClass);
		a.ifne(identity);
		a.aload(0);
		a.instanceOf(mapInterface);
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
		return new HashMethod(cp.addUtf8(HASH), cp.addUtf8(HASH_DESC), a);
	}

	// _hashGet(key, table, default): scan the key's bucket with the table's own test --
	// _equal, or _eqv/_eq for an eql/eq table (identity for aggregates).
	private static HashMethod buildGet(ConstantPool cp, ClassEntry mapClass, ClassEntry listClass,
			ClassEntry objectArrayClass, MethodRefEntry mapGet, MethodRefEntry listGet, MethodRefEntry listSize,
			MethodRefEntry integerValueOf, MethodRefEntry hashRef, MemberRefEntry equalMethod, MemberRefEntry eqvMethod,
			ClassEntry ratArrClass, ClassEntry integerClass, MethodRefEntry identityHashCode,
			@Nullable MethodRefEntry keyRef, @Nullable MethodRefEntry testRef, boolean identityTables) {
		MethodCode a = new MethodCode();
		emitFoldKey(a, keyRef);
		if (testRef != null) {
			a.aload(1);
			a.invokestatic(testRef);
			a.istore(6);
		}
		a.aload(1);
		a.checkcast(mapClass);
		emitKeyHash(a, hashRef, integerValueOf, objectArrayClass, listClass, ratArrClass, integerClass,
				identityHashCode, 6, identityTables);
		a.invokevirtual(mapGet);
		a.checkcast(listClass);
		a.astore(3);
		MethodCode.Label haveBucket = a.newLabel();
		a.aload(3);
		a.ifnonnull(haveBucket);
		a.aload(2);
		a.areturn();
		a.labelBinding(haveBucket);
		a.loadConstant(0);
		a.istore(4);
		MethodCode.Label top = a.newLabel();
		MethodCode.Label miss = a.newLabel();
		MethodCode.Label next = a.newLabel();
		a.labelBinding(top);
		a.iload(4);
		a.aload(3);
		a.invokevirtual(listSize);
		a.if_icmpge(miss);
		a.aload(3);
		a.iload(4);
		a.invokevirtual(listGet);
		a.checkcast(objectArrayClass);
		a.astore(5);
		emitTestCompare(a, 5, 6, equalMethod, eqvMethod, identityTables);
		a.ifeq(next);
		a.aload(5);
		a.loadConstant(1);
		a.aaload();
		a.areturn();
		a.labelBinding(next);
		a.iinc(4, 1);
		a.goto_(top);
		a.labelBinding(miss);
		a.aload(2);
		a.areturn();
		return new HashMethod(cp.addUtf8(GET), cp.addUtf8(GET_DESC), a);
	}

	// _hashPut(key, table, value): replace the value of the equal key in the bucket, or
	// append a fresh pair to the bucket AND to the insertion-order list. Re-storing an
	// existing key mutates the pair in place, so the order list needs no maintenance and
	// the entry keeps the position it was first stored at -- what maphash walks.
	private static HashMethod buildPut(ConstantPool cp, ClassEntry mapClass, ClassEntry listClass,
			ClassEntry objectClass, ClassEntry objectArrayClass, MethodRefEntry mapGet, MethodRefEntry mapPut,
			MethodRefEntry listInit, MethodRefEntry listAdd, MethodRefEntry listGet, MethodRefEntry listSize,
			MethodRefEntry integerValueOf, MethodRefEntry hashRef, MethodRefEntry ordRef, MemberRefEntry equalMethod,
			MemberRefEntry eqvMethod, ClassEntry ratArrClass, ClassEntry integerClass, MethodRefEntry identityHashCode,
			@Nullable MethodRefEntry keyRef, @Nullable MethodRefEntry testRef, boolean identityTables,
			MethodRefEntry maybeCompactRef) {
		MethodCode a = new MethodCode();
		emitFoldKey(a, keyRef);
		if (testRef != null) {
			a.aload(1);
			a.invokestatic(testRef);
			a.istore(7);
		}
		emitKeyHash(a, hashRef, integerValueOf, objectArrayClass, listClass, ratArrClass, integerClass,
				identityHashCode, 7, identityTables);
		a.astore(6);
		a.aload(1);
		a.checkcast(mapClass);
		a.aload(6);
		a.invokevirtual(mapGet);
		a.checkcast(listClass);
		a.astore(3);
		MethodCode.Label haveBucket = a.newLabel();
		a.aload(3);
		a.ifnonnull(haveBucket);
		a.new_(listClass);
		a.dup();
		a.loadConstant(1);
		a.invokespecial(listInit);
		a.astore(3);
		a.aload(1);
		a.checkcast(mapClass);
		a.aload(6);
		a.aload(3);
		a.invokevirtual(mapPut);
		a.pop();
		a.labelBinding(haveBucket);
		a.loadConstant(0);
		a.istore(4);
		MethodCode.Label top = a.newLabel();
		MethodCode.Label fresh = a.newLabel();
		MethodCode.Label next = a.newLabel();
		a.labelBinding(top);
		a.iload(4);
		a.aload(3);
		a.invokevirtual(listSize);
		a.if_icmpge(fresh);
		a.aload(3);
		a.iload(4);
		a.invokevirtual(listGet);
		a.checkcast(objectArrayClass);
		a.astore(5);
		emitTestCompare(a, 5, 7, equalMethod, eqvMethod, identityTables);
		a.ifeq(next);
		// The stored key becomes the key just handed in, matching the interpreter (its
		// entry record is replaced), so maphash hands back the newest key object.
		a.aload(5);
		a.loadConstant(0);
		a.aload(0);
		a.aastore();
		a.aload(5);
		a.loadConstant(1);
		a.aload(2);
		a.aastore();
		a.aload(2);
		a.areturn();
		a.labelBinding(next);
		a.iinc(4, 1);
		a.goto_(top);
		a.labelBinding(fresh);
		a.loadConstant(2);
		a.anewarray(objectClass);
		a.astore(5);
		a.aload(5);
		a.loadConstant(0);
		a.aload(0);
		a.aastore();
		a.aload(5);
		a.loadConstant(1);
		a.aload(2);
		a.aastore();
		a.aload(3);
		a.aload(5);
		a.invokevirtual(listAdd);
		a.pop();
		a.aload(1);
		a.invokestatic(ordRef);
		a.aload(5);
		a.invokevirtual(listAdd);
		a.pop();
		// A fresh pair may push a dead-majority order list over the compaction
		// threshold; compacting here keeps every removal O(1) amortised.
		a.aload(1);
		a.invokestatic(maybeCompactRef);
		a.aload(2);
		a.areturn();
		return new HashMethod(cp.addUtf8(PUT), cp.addUtf8(PUT_DESC), a);
	}

	// _hashRem(key, table): drop the pair from its bucket and tombstone it in the order
	// list (null the key slot, count one more dead) instead of unlinking it there --
	// unlinking scans and memmoves, O(n) per removal. An emptied bucket goes with the
	// pair, so a put/remove cycle does not leak bucket objects.
	private static HashMethod buildRem(ConstantPool cp, ClassEntry mapClass, ClassEntry listClass,
			ClassEntry objectArrayClass, MethodRefEntry mapGet, MethodRefEntry mapRemove, MethodRefEntry listGet,
			MethodRefEntry listSize, MethodRefEntry listRemoveAt, MethodRefEntry integerValueOf, MethodRefEntry hashRef,
			MemberRefEntry equalMethod, MemberRefEntry eqvMethod, ClassEntry ratArrClass, ClassEntry integerClass,
			MethodRefEntry identityHashCode, StringEntry trueStr, @Nullable MethodRefEntry keyRef,
			@Nullable MethodRefEntry testRef, boolean identityTables, MethodRefEntry tombstoneRef) {
		MethodCode a = new MethodCode();
		emitFoldKey(a, keyRef);
		if (testRef != null) {
			a.aload(1);
			a.invokestatic(testRef);
			a.istore(6);
		}
		emitKeyHash(a, hashRef, integerValueOf, objectArrayClass, listClass, ratArrClass, integerClass,
				identityHashCode, 6, identityTables);
		a.astore(5);
		a.aload(1);
		a.checkcast(mapClass);
		a.aload(5);
		a.invokevirtual(mapGet);
		a.checkcast(listClass);
		a.astore(2);
		MethodCode.Label haveBucket = a.newLabel();
		a.aload(2);
		a.ifnonnull(haveBucket);
		a.aconst_null();
		a.areturn();
		a.labelBinding(haveBucket);
		a.loadConstant(0);
		a.istore(3);
		MethodCode.Label top = a.newLabel();
		MethodCode.Label miss = a.newLabel();
		MethodCode.Label next = a.newLabel();
		MethodCode.Label keepBucket = a.newLabel();
		a.labelBinding(top);
		a.iload(3);
		a.aload(2);
		a.invokevirtual(listSize);
		a.if_icmpge(miss);
		a.aload(2);
		a.iload(3);
		a.invokevirtual(listGet);
		a.checkcast(objectArrayClass);
		a.astore(4);
		emitTestCompare(a, 4, 6, equalMethod, eqvMethod, identityTables);
		a.ifeq(next);
		a.aload(2);
		a.iload(3);
		a.invokevirtual(listRemoveAt);
		a.pop();
		a.aload(1);
		a.aload(4);
		a.invokestatic(tombstoneRef);
		a.aload(2);
		a.invokevirtual(listSize);
		a.ifne(keepBucket);
		a.aload(1);
		a.checkcast(mapClass);
		a.aload(5);
		a.invokevirtual(mapRemove);
		a.pop();
		a.labelBinding(keepBucket);
		a.ldc(trueStr);
		a.areturn();
		a.labelBinding(next);
		a.iinc(3, 1);
		a.goto_(top);
		a.labelBinding(miss);
		a.aconst_null();
		a.areturn();
		return new HashMethod(cp.addUtf8(REM), cp.addUtf8(REM_DESC), a);
	}

	// The equalp trio, emitted only for a program that writes :test 'equalp.
	//
	// _hashMakeP() marks a fresh table with the reserved EQUALP_KEY (a String key, so it
	// collides with no Integer bucket key, exactly like ORDER_KEY); _hashEqp(table)
	// answers that marker, which is what the printer and hash-table-test read; and
	// _hashKey(key, table) folds a key through the travelling RontoHashTable.equalpKey
	// when the table carries the marker. Placement stays ONE structural table: equalp on
	// two values is equal on their folds, so _hash and _equal are untouched.
	private static List<HashMethod> buildEqualpFold(ConstantPool cp, ClassEntry thisClass, ClassEntry mapClass,
			MethodRefEntry mapGet, MethodRefEntry mapPut, StringEntry trueStr, @Nullable MemberRefEntry strvMethod) {
		StringEntry equalpKey = cp.stringEntry(RontoHashTable.EQUALP_KEY);
		MethodRefEntry makeRef = cp.methodRef(thisClass, MAKE, MAKE_DESC);
		MethodRefEntry equalpPRef = cp.methodRef(thisClass, EQUALP_P, EQUALP_P_DESC);
		MethodRefEntry foldRef = cp.methodRef(RontoHashTable.class.getName().replace('.', '/'), "equalpKey",
				"(Ljava/lang/Object;I)Ljava/lang/Object;");

		List<HashMethod> methods = new ArrayList<>();

		MethodCode make = new MethodCode();
		make.invokestatic(makeRef);
		make.astore(0);
		make.aload(0);
		make.checkcast(mapClass);
		make.ldc(equalpKey);
		make.ldc(trueStr);
		make.invokevirtual(mapPut);
		make.pop();
		make.aload(0);
		make.areturn();
		methods.add(new HashMethod(cp.addUtf8(MAKE_EQUALP), cp.addUtf8(MAKE_EQUALP_DESC), make));

		MethodCode eqp = new MethodCode();
		eqp.aload(0);
		eqp.instanceOf(mapClass);
		MethodCode.Label notTable = eqp.newLabel();
		eqp.ifeq(notTable);
		eqp.aload(0);
		eqp.checkcast(mapClass);
		eqp.ldc(equalpKey);
		eqp.invokevirtual(mapGet);
		eqp.areturn();
		eqp.labelBinding(notTable);
		eqp.aconst_null();
		eqp.areturn();
		methods.add(new HashMethod(cp.addUtf8(EQUALP_P), cp.addUtf8(EQUALP_P_DESC), eqp));

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
		methods.add(new HashMethod(cp.addUtf8(KEY), cp.addUtf8(KEY_DESC), key));

		return methods;
	}

	// The identity pair, emitted only for a program that writes :test 'eq or
	// :test 'eql.
	//
	// _hashMakeEq() / _hashMakeEql() mark a fresh table with the reserved EQ_KEY /
	// EQL_KEY (String keys, so neither collides with an Integer bucket key, exactly
	// like ORDER_KEY); _hashTest(table) answers the test code the table's lookups
	// implement (0 equal, 1 equalp, 2 eql, 3 eq), which is what the get/put/remove
	// dispatch, the printer and hash-table-test read.
	private static List<HashMethod> buildIdentityTables(ConstantPool cp, ClassEntry thisClass, ClassEntry mapClass,
			MethodRefEntry mapGet, MethodRefEntry mapPut, StringEntry trueStr) {
		StringEntry eqKey = cp.stringEntry(EQ_KEY);
		StringEntry eqlKey = cp.stringEntry(EQL_KEY);
		StringEntry equalpKey = cp.stringEntry(RontoHashTable.EQUALP_KEY);
		MethodRefEntry makeRef = cp.methodRef(thisClass, MAKE, MAKE_DESC);

		List<HashMethod> methods = new ArrayList<>();

		MethodCode makeEq = new MethodCode();
		makeEq.invokestatic(makeRef);
		makeEq.astore(0);
		makeEq.aload(0);
		makeEq.checkcast(mapClass);
		makeEq.ldc(eqKey);
		makeEq.ldc(trueStr);
		makeEq.invokevirtual(mapPut);
		makeEq.pop();
		makeEq.aload(0);
		makeEq.areturn();
		methods.add(new HashMethod(cp.addUtf8(MAKE_EQ), cp.addUtf8(MAKE_EQ_DESC), makeEq));

		MethodCode makeEql = new MethodCode();
		makeEql.invokestatic(makeRef);
		makeEql.astore(0);
		makeEql.aload(0);
		makeEql.checkcast(mapClass);
		makeEql.ldc(eqlKey);
		makeEql.ldc(trueStr);
		makeEql.invokevirtual(mapPut);
		makeEql.pop();
		makeEql.aload(0);
		makeEql.areturn();
		methods.add(new HashMethod(cp.addUtf8(MAKE_EQL), cp.addUtf8(MAKE_EQL_DESC), makeEql));

		MethodCode test = new MethodCode();
		test.aload(0);
		test.instanceOf(mapClass);
		MethodCode.Label notTable = test.newLabel();
		test.ifeq(notTable);
		MethodCode.Label notEq = test.newLabel();
		MethodCode.Label notEql = test.newLabel();
		MethodCode.Label notEqualp = test.newLabel();
		test.aload(0);
		test.checkcast(mapClass);
		test.ldc(eqKey);
		test.invokevirtual(mapGet);
		test.ifnull(notEq);
		test.loadConstant(3);
		test.ireturn();
		test.labelBinding(notEq);
		test.aload(0);
		test.checkcast(mapClass);
		test.ldc(eqlKey);
		test.invokevirtual(mapGet);
		test.ifnull(notEql);
		test.loadConstant(2);
		test.ireturn();
		test.labelBinding(notEql);
		test.aload(0);
		test.checkcast(mapClass);
		test.ldc(equalpKey);
		test.invokevirtual(mapGet);
		test.ifnull(notEqualp);
		test.loadConstant(1);
		test.ireturn();
		test.labelBinding(notEqualp);
		test.labelBinding(notTable);
		test.loadConstant(0);
		test.ireturn();
		methods.add(new HashMethod(cp.addUtf8(TEST), cp.addUtf8(TEST_DESC), test));

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

	// Pushes the bucket-scan comparison of the entry pair in pairSlot against the key
	// in local 0: _eqv for an eql or eq table (eq IS eql, .kb/eq-numbers.md), _equal
	// otherwise. The table's test code sits in testSlot; without identity tables this is
	// the _equal call it always was, not one instruction more.
	private static void emitTestCompare(MethodCode a, int pairSlot, int testSlot, MemberRefEntry equalMethod,
			MemberRefEntry eqvMethod, boolean identityTables) {
		if (!identityTables) {
			a.aload(pairSlot);
			a.loadConstant(0);
			a.aaload();
			a.aload(0);
			a.invokestatic(equalMethod);
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
		a.invokestatic(equalMethod);
		a.goto_(done);
		a.labelBinding(eqlArm);
		a.aload(pairSlot);
		a.loadConstant(0);
		a.aaload();
		a.aload(0);
		a.invokestatic(eqvMethod);
		a.labelBinding(done);
	}

	// Pushes Integer.valueOf(_hash(local 0, HASH_DEPTH_CAP, new int[]{HASH_WORK_CAP})) --
	// the bucket key. The gas cell is allocated PER PLACEMENT, so what a key hashes to is
	// a function of that key alone and never of what the table hashed before it. For an
	// eql/eq table (testSlot >= 2) an aggregate key -- a cons or an instance, the values
	// _equal would fold structurally -- hashes by identity instead, so mutating it after
	// insertion keeps its bucket; every other key hashes exactly as before.
	private static void emitKeyHash(MethodCode a, MethodRefEntry hashRef, MethodRefEntry integerValueOf,
			ClassEntry objectArrayClass, ClassEntry listClass, ClassEntry ratArrClass, ClassEntry integerClass,
			MethodRefEntry identityHashCode, int testSlot, boolean identityTables) {
		if (!identityTables) {
			emitStructuralKeyHash(a, hashRef, integerValueOf);
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
		// string grown after it was stored keeps its bucket.
		a.aload(0);
		a.instanceOf(listClass);
		a.ifne(identity);
		a.aload(0);
		a.instanceOf(objectArrayClass);
		a.ifeq(structural);
		a.aload(0);
		a.instanceOf(ratArrClass);
		a.ifne(structural);
		a.aload(0);
		a.checkcast(objectArrayClass);
		a.loadConstant(0);
		a.aaload();
		a.instanceOf(integerClass);
		a.ifne(structural);
		a.labelBinding(identity);
		a.aload(0);
		a.invokestatic(identityHashCode);
		a.invokestatic(integerValueOf);
		a.goto_(done);
		a.labelBinding(structural);
		emitStructuralKeyHash(a, hashRef, integerValueOf);
		a.labelBinding(done);
	}

	private static void emitStructuralKeyHash(MethodCode a, MethodRefEntry hashRef, MethodRefEntry integerValueOf) {
		a.aload(0);
		a.loadConstant(LispEquality.HASH_DEPTH_CAP);
		a.loadConstant(1);
		a.newarray(TypeKind.INT);
		a.dup();
		a.loadConstant(0);
		a.loadConstant(LispEquality.HASH_WORK_CAP);
		a.iastore();
		a.invokestatic(hashRef);
		a.invokestatic(integerValueOf);
	}

}
