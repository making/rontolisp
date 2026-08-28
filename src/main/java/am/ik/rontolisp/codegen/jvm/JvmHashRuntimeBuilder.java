package am.ik.rontolisp.codegen.jvm;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import am.ik.jvm.ConstantPool;
import am.ik.jvm.ConstantPool.ClassConstant;
import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.ConstantPool.StringConstant;
import am.ik.jvm.ConstantPool.Utf8Constant;
import am.ik.jvm.Opcode;
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
 * re-put; the pair it holds is the pair the bucket holds.
 *
 * <p>
 * The generated static helpers (all gated on the program actually using hash tables):
 * <ul>
 * <li>{@code _hash(key, depth)} -&gt; the structural hash, capped at {@code depth} levels
 * so a cyclic key terminates</li>
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

	static final String HASH_DESC = "(Ljava/lang/Object;I)I";

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

	static final String KEY = "_hashKey";

	static final String KEY_DESC = "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";

	static final String EQUALP_P = "_hashEqp";

	static final String EQUALP_P_DESC = "(Ljava/lang/Object;)Ljava/lang/Object;";

	/**
	 * The travelling class the {@code equalp} fold is written in -- plain Java over the
	 * JVM value model, which a bytecode transcription of the same walk would only make
	 * harder to keep in step with the interpreter's. It goes BESIDE a compiled program
	 * that makes an {@code equalp} table, like the handle and served-request runtimes
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

	/** A hash-table helper method body ready to be emitted into the generated class. */
	record HashMethod(Utf8Constant name, Utf8Constant desc, int maxStack, int maxLocals, List<Integer> code) {
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
	 * @param strvMethod the {@code _strv} character-vector normalizer, or null when the
	 * program uses no arrays; when present {@code _hash} folds a character vector as the
	 * string with the same content, which is what {@code _equal} compares it as
	 * @param stringArrayClass {@code String[]}, the interned layout of an instance, or
	 * null when the program can build none
	 * @param equalpFold whether the program writes {@code :test 'equalp} somewhere, in
	 * which case the three fold helpers are emitted and the get/put/remove trio runs
	 * every key through {@link #KEY} first
	 * @return the helper methods
	 */
	static List<HashMethod> build(ConstantPool cp, ClassConstant thisClass, ClassConstant objectClass,
			ClassConstant objectArrayClass, MethodrefConstant longValueOf, MethodrefConstant equalMethod,
			@Nullable MethodrefConstant strvMethod, @Nullable ClassConstant stringArrayClass, boolean equalpFold) {
		ClassConstant mapClass = cp.addClass(cp.addUtf8(MAP_CLASS));
		ClassConstant listClass = cp.addClass(cp.addUtf8(LIST_CLASS));
		ClassConstant integerClass = cp.addClass(cp.addUtf8("java/lang/Integer"));
		ClassConstant ratArrClass = cp.addClass(cp.addUtf8("[Ljava/math/BigInteger;"));
		ClassConstant intArrClass = cp.addClass(cp.addUtf8("[I"));
		MethodrefConstant mapInit = cp.addMethodref(mapClass,
				cp.addNameAndType(cp.addUtf8("<init>"), cp.addUtf8("()V")));
		MethodrefConstant mapGet = cp.addMethodref(mapClass,
				cp.addNameAndType(cp.addUtf8("get"), cp.addUtf8("(Ljava/lang/Object;)Ljava/lang/Object;")));
		MethodrefConstant mapPut = cp.addMethodref(mapClass, cp.addNameAndType(cp.addUtf8("put"),
				cp.addUtf8("(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;")));
		MethodrefConstant mapRemove = cp.addMethodref(mapClass,
				cp.addNameAndType(cp.addUtf8("remove"), cp.addUtf8("(Ljava/lang/Object;)Ljava/lang/Object;")));
		MethodrefConstant mapClear = cp.addMethodref(mapClass,
				cp.addNameAndType(cp.addUtf8("clear"), cp.addUtf8("()V")));
		// A BUCKET is created with room for one entry, not with ArrayList's default
		// ten: a bucket holds exactly one pair unless two structurally distinct keys
		// hash alike, and the default backing array is 56 bytes per bucket against 24
		// -- on a table with a million keys that difference IS the allocation profile.
		// The insertion-order list keeps the no-argument constructor (it grows to the
		// table's whole size).
		MethodrefConstant listInitCapacity = cp.addMethodref(listClass,
				cp.addNameAndType(cp.addUtf8("<init>"), cp.addUtf8("(I)V")));
		MethodrefConstant listInit = cp.addMethodref(listClass,
				cp.addNameAndType(cp.addUtf8("<init>"), cp.addUtf8("()V")));
		MethodrefConstant listAdd = cp.addMethodref(listClass,
				cp.addNameAndType(cp.addUtf8("add"), cp.addUtf8("(Ljava/lang/Object;)Z")));
		MethodrefConstant listGet = cp.addMethodref(listClass,
				cp.addNameAndType(cp.addUtf8("get"), cp.addUtf8("(I)Ljava/lang/Object;")));
		MethodrefConstant listSize = cp.addMethodref(listClass,
				cp.addNameAndType(cp.addUtf8("size"), cp.addUtf8("()I")));
		MethodrefConstant listRemoveAt = cp.addMethodref(listClass,
				cp.addNameAndType(cp.addUtf8("remove"), cp.addUtf8("(I)Ljava/lang/Object;")));
		MethodrefConstant listRemoveObj = cp.addMethodref(listClass,
				cp.addNameAndType(cp.addUtf8("remove"), cp.addUtf8("(Ljava/lang/Object;)Z")));
		MethodrefConstant listClear = cp.addMethodref(listClass,
				cp.addNameAndType(cp.addUtf8("clear"), cp.addUtf8("()V")));
		MethodrefConstant listToArray = cp.addMethodref(listClass,
				cp.addNameAndType(cp.addUtf8("toArray"), cp.addUtf8("()[Ljava/lang/Object;")));
		MethodrefConstant integerValueOf = cp.addMethodref(integerClass,
				cp.addNameAndType(cp.addUtf8("valueOf"), cp.addUtf8("(I)Ljava/lang/Integer;")));
		MethodrefConstant objectHashCode = cp.addMethodref(objectClass,
				cp.addNameAndType(cp.addUtf8("hashCode"), cp.addUtf8("()I")));
		MethodrefConstant hashRef = cp.addMethodref(thisClass,
				cp.addNameAndType(cp.addUtf8(HASH), cp.addUtf8(HASH_DESC)));
		MethodrefConstant ordRef = cp.addMethodref(thisClass, cp.addNameAndType(cp.addUtf8(ORD), cp.addUtf8(ORD_DESC)));
		StringConstant orderKey = cp.addString(ORDER_KEY);
		// The compiled representation of the boolean t is the symbol "T" (a bare String).
		StringConstant trueStr = cp.addString("T");
		// The equalp fold: the key each of get/put/remove places by, and the marker the
		// table carries. Null in a program that writes no equalp table, which is then
		// emitted exactly as it was before the fold existed.
		final @Nullable MethodrefConstant keyRef = equalpFold
				? cp.addMethodref(thisClass, cp.addNameAndType(cp.addUtf8(KEY), cp.addUtf8(KEY_DESC))) : null;

		List<HashMethod> methods = new ArrayList<>();
		methods.add(buildHash(cp, objectArrayClass, ratArrClass, intArrClass, integerClass, stringArrayClass,
				strvMethod, objectHashCode, hashRef));

		// _hashMake(): m = new LinkedHashMap(); m.put(ORDER_KEY, new ArrayList());
		// return m
		JvmAsm make = new JvmAsm();
		make.anew(mapClass);
		make.dup();
		make.invokespecial(mapInit);
		make.dup();
		make.ldcString(orderKey);
		make.anew(listClass);
		make.dup();
		make.invokespecial(listInit);
		make.invokevirtual(mapPut);
		make.pop();
		make.areturn();
		methods.add(new HashMethod(cp.addUtf8(MAKE), cp.addUtf8(MAKE_DESC), 5, 1, make.code));

		// _hashOrd(table): return (ArrayList) ((LinkedHashMap) table).get(ORDER_KEY)
		JvmAsm ord = new JvmAsm();
		ord.aload(0);
		ord.checkcast(mapClass);
		ord.ldcString(orderKey);
		ord.invokevirtual(mapGet);
		ord.checkcast(listClass);
		ord.areturn();
		methods.add(new HashMethod(cp.addUtf8(ORD), cp.addUtf8(ORD_DESC), 2, 1, ord.code));

		methods.add(buildGet(cp, mapClass, listClass, objectArrayClass, mapGet, listGet, listSize, integerValueOf,
				hashRef, equalMethod, keyRef));
		methods.add(buildPut(cp, mapClass, listClass, objectClass, objectArrayClass, mapGet, mapPut, listInitCapacity,
				listAdd, listGet, listSize, integerValueOf, hashRef, ordRef, equalMethod, keyRef));
		methods.add(buildRem(cp, mapClass, listClass, objectArrayClass, mapGet, mapRemove, listGet, listSize,
				listRemoveAt, listRemoveObj, integerValueOf, hashRef, ordRef, equalMethod, trueStr, keyRef));

		if (equalpFold) {
			methods.addAll(buildEqualpFold(cp, thisClass, mapClass, mapGet, mapPut, trueStr));
		}

		// _hashClr(table): the order list is emptied and re-hung, so every bucket goes
		// with the clear and the table keeps its identity -- and, in a program that
		// folds, so does its TEST: the marker is read before the clear and hung back
		// beside the order list, or an emptied equalp table would place structurally
		// from there on.
		JvmAsm clr = new JvmAsm();
		if (equalpFold) {
			clr.aload(0);
			clr.invokestatic(
					cp.addMethodref(thisClass, cp.addNameAndType(cp.addUtf8(EQUALP_P), cp.addUtf8(EQUALP_P_DESC))));
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
		clr.ldcString(orderKey);
		clr.aload(1);
		clr.invokevirtual(mapPut);
		clr.pop();
		if (equalpFold) {
			int notFolding = clr.label();
			clr.aload(2);
			clr.branch(Opcode.IFNULL, notFolding);
			clr.aload(0);
			clr.checkcast(mapClass);
			clr.ldcString(cp.addString(RontoHashTable.EQUALP_KEY));
			clr.aload(2);
			clr.invokevirtual(mapPut);
			clr.pop();
			clr.bind(notFolding);
		}
		clr.aload(0);
		clr.areturn();
		methods.add(new HashMethod(cp.addUtf8(CLR), cp.addUtf8(CLR_DESC), equalpFold ? 4 : 3, equalpFold ? 3 : 2,
				clr.code));

		// _hashCount(table): return Long.valueOf(_hashOrd(table).size())
		JvmAsm count = new JvmAsm();
		count.aload(0);
		count.invokestatic(ordRef);
		count.invokevirtual(listSize);
		count.op(Opcode.I2L);
		count.invokestatic(longValueOf);
		count.areturn();
		methods.add(new HashMethod(cp.addUtf8(COUNT), cp.addUtf8(COUNT_DESC), 2, 1, count.code));

		// _hashSize(table): the same count as a bare int (the printer's :COUNT field)
		JvmAsm size = new JvmAsm();
		size.aload(0);
		size.invokestatic(ordRef);
		size.invokevirtual(listSize);
		size.ireturn();
		methods.add(new HashMethod(cp.addUtf8(SIZE), cp.addUtf8(SIZE_DESC), 1, 1, size.code));

		// _hashP(x): return (x instanceof LinkedHashMap) ? "T" : null
		JvmAsm hp = new JvmAsm();
		hp.aload(0);
		hp.instanceOf(mapClass);
		int hpFalse = hp.label();
		hp.branch(Opcode.IFEQ, hpFalse);
		hp.ldcString(trueStr);
		hp.areturn();
		hp.bind(hpFalse);
		hp.aconstNull();
		hp.areturn();
		methods.add(new HashMethod(cp.addUtf8(P), cp.addUtf8(P_DESC), 2, 1, hp.code));

		// _hashValues(table): return _hashOrd(table).toArray()
		JvmAsm values = new JvmAsm();
		values.aload(0);
		values.invokestatic(ordRef);
		values.invokevirtual(listToArray);
		values.areturn();
		methods.add(new HashMethod(cp.addUtf8(VALUES), cp.addUtf8(VALUES_DESC), 1, 1, values.code));

		return methods;
	}

	// _hash(Object v, int d): the structural hash _equal agrees with -- equal values
	// hash equal. d is the REMAINING depth: at zero the fold answers a constant instead
	// of descending, which is free correctness (a hash need not be injective) and is what
	// makes a cyclic key terminate. The cap is by depth alone, never by anything
	// order- or address-dependent, so two equal keys still fold identically.
	private static HashMethod buildHash(ConstantPool cp, ClassConstant objectArrayClass, ClassConstant ratArrClass,
			ClassConstant intArrClass, ClassConstant integerClass, @Nullable ClassConstant stringArrayClass,
			@Nullable MethodrefConstant strvMethod, MethodrefConstant objectHashCode, MethodrefConstant hashRef) {
		JvmAsm a = new JvmAsm();
		// if (d <= 0) return 0
		a.iload(1);
		int haveDepth = a.label();
		a.branch(Opcode.IFGT, haveDepth);
		a.iconst(0);
		a.ireturn();
		a.bind(haveDepth);
		// if (v == null) return 0 -- nil
		a.aload(0);
		int notNull = a.label();
		a.branch(Opcode.IFNONNULL, notNull);
		a.iconst(0);
		a.ireturn();
		a.bind(notNull);
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
			int notInstance = a.label();
			a.aload(0);
			a.instanceOf(objectArrayClass);
			a.branch(Opcode.IFEQ, notInstance);
			a.aload(0);
			a.checkcast(objectArrayClass);
			a.arraylength();
			a.branch(Opcode.IFEQ, notInstance);
			a.aload(0);
			a.checkcast(objectArrayClass);
			a.iconst(0);
			a.aaload();
			a.instanceOf(stringArrayClass);
			a.branch(Opcode.IFEQ, notInstance);
			a.aload(0);
			a.checkcast(objectArrayClass);
			a.astore(2);
			a.aload(2);
			a.iconst(0);
			a.aaload();
			a.invokevirtual(objectHashCode);
			a.istore(4);
			a.iconst(1);
			a.istore(3);
			int slotTop = a.label();
			int slotDone = a.label();
			a.bind(slotTop);
			a.iload(3);
			a.aload(2);
			a.arraylength();
			a.branch(Opcode.IF_ICMPGE, slotDone);
			a.iload(4);
			a.iconst(31);
			a.op(Opcode.IMUL);
			a.aload(2);
			a.iload(3);
			a.aaload();
			a.iload(1);
			a.iconst(1);
			a.op(Opcode.ISUB);
			a.invokestatic(hashRef);
			a.op(Opcode.IADD);
			a.istore(4);
			a.iinc(3, 1);
			a.branch(Opcode.GOTO, slotTop);
			a.bind(slotDone);
			a.iload(4);
			a.ireturn();
			a.bind(notInstance);
		}
		// A cons folds 31 * hash(car) + hash(cdr) + 1. The guard is _equal's: an
		// Object[] that is neither a ratio nor an array (whose header slot is an
		// Integer).
		int notCons = a.label();
		a.aload(0);
		a.instanceOf(objectArrayClass);
		a.branch(Opcode.IFEQ, notCons);
		a.aload(0);
		a.instanceOf(ratArrClass);
		a.branch(Opcode.IFNE, notCons);
		a.aload(0);
		a.checkcast(objectArrayClass);
		a.iconst(0);
		a.aaload();
		a.instanceOf(integerClass);
		a.branch(Opcode.IFNE, notCons);
		a.iconst(31);
		a.aload(0);
		a.checkcast(objectArrayClass);
		a.iconst(0);
		a.aaload();
		a.iload(1);
		a.iconst(1);
		a.op(Opcode.ISUB);
		a.invokestatic(hashRef);
		a.op(Opcode.IMUL);
		a.aload(0);
		a.checkcast(objectArrayClass);
		a.iconst(1);
		a.aaload();
		a.iload(1);
		a.iconst(1);
		a.op(Opcode.ISUB);
		a.invokestatic(hashRef);
		a.op(Opcode.IADD);
		a.iconst(1);
		a.op(Opcode.IADD);
		a.ireturn();
		a.bind(notCons);
		// A character is an int[]{codepoint}; _eqv compares that code point.
		int notChar = a.label();
		a.aload(0);
		a.instanceOf(intArrClass);
		a.branch(Opcode.IFEQ, notChar);
		a.aload(0);
		a.checkcast(intArrClass);
		a.iconst(0);
		a.op(Opcode.IALOAD);
		a.ireturn();
		a.bind(notChar);
		// A ratio is a BigInteger[]{num, den}; _eqv compares both components.
		int notRatio = a.label();
		a.aload(0);
		a.instanceOf(ratArrClass);
		a.branch(Opcode.IFEQ, notRatio);
		a.aload(0);
		a.checkcast(ratArrClass);
		a.iconst(0);
		a.aaload();
		a.invokevirtual(objectHashCode);
		a.iconst(31);
		a.op(Opcode.IMUL);
		a.aload(0);
		a.checkcast(ratArrClass);
		a.iconst(1);
		a.aaload();
		a.invokevirtual(objectHashCode);
		a.op(Opcode.IADD);
		a.ireturn();
		a.bind(notRatio);
		// Everything else answers with its own hashCode, which its equals agrees with
		// by the Java contract -- and which is identity exactly where _eqv's final
		// Object.equals is identity (a general array, a closure).
		a.aload(0);
		a.invokevirtual(objectHashCode);
		a.ireturn();
		return new HashMethod(cp.addUtf8(HASH), cp.addUtf8(HASH_DESC), 4, 5, a.code);
	}

	// _hashGet(key, table, default): scan the key's bucket with _equal.
	private static HashMethod buildGet(ConstantPool cp, ClassConstant mapClass, ClassConstant listClass,
			ClassConstant objectArrayClass, MethodrefConstant mapGet, MethodrefConstant listGet,
			MethodrefConstant listSize, MethodrefConstant integerValueOf, MethodrefConstant hashRef,
			MethodrefConstant equalMethod, @Nullable MethodrefConstant keyRef) {
		JvmAsm a = new JvmAsm();
		emitFoldKey(a, keyRef);
		a.aload(1);
		a.checkcast(mapClass);
		emitKeyHash(a, hashRef, integerValueOf);
		a.invokevirtual(mapGet);
		a.checkcast(listClass);
		a.astore(3);
		int haveBucket = a.label();
		a.aload(3);
		a.branch(Opcode.IFNONNULL, haveBucket);
		a.aload(2);
		a.areturn();
		a.bind(haveBucket);
		a.iconst(0);
		a.istore(4);
		int top = a.label();
		int miss = a.label();
		int next = a.label();
		a.bind(top);
		a.iload(4);
		a.aload(3);
		a.invokevirtual(listSize);
		a.branch(Opcode.IF_ICMPGE, miss);
		a.aload(3);
		a.iload(4);
		a.invokevirtual(listGet);
		a.checkcast(objectArrayClass);
		a.astore(5);
		a.aload(5);
		a.iconst(0);
		a.aaload();
		a.aload(0);
		a.invokestatic(equalMethod);
		a.branch(Opcode.IFEQ, next);
		a.aload(5);
		a.iconst(1);
		a.aaload();
		a.areturn();
		a.bind(next);
		a.iinc(4, 1);
		a.branch(Opcode.GOTO, top);
		a.bind(miss);
		a.aload(2);
		a.areturn();
		return new HashMethod(cp.addUtf8(GET), cp.addUtf8(GET_DESC), 3, 6, a.code);
	}

	// _hashPut(key, table, value): replace the value of the equal key in the bucket, or
	// append a fresh pair to the bucket AND to the insertion-order list. Re-storing an
	// existing key mutates the pair in place, so the order list needs no maintenance and
	// the entry keeps the position it was first stored at -- what maphash walks.
	private static HashMethod buildPut(ConstantPool cp, ClassConstant mapClass, ClassConstant listClass,
			ClassConstant objectClass, ClassConstant objectArrayClass, MethodrefConstant mapGet,
			MethodrefConstant mapPut, MethodrefConstant listInit, MethodrefConstant listAdd, MethodrefConstant listGet,
			MethodrefConstant listSize, MethodrefConstant integerValueOf, MethodrefConstant hashRef,
			MethodrefConstant ordRef, MethodrefConstant equalMethod, @Nullable MethodrefConstant keyRef) {
		JvmAsm a = new JvmAsm();
		emitFoldKey(a, keyRef);
		emitKeyHash(a, hashRef, integerValueOf);
		a.astore(6);
		a.aload(1);
		a.checkcast(mapClass);
		a.aload(6);
		a.invokevirtual(mapGet);
		a.checkcast(listClass);
		a.astore(3);
		int haveBucket = a.label();
		a.aload(3);
		a.branch(Opcode.IFNONNULL, haveBucket);
		a.anew(listClass);
		a.dup();
		a.iconst(1);
		a.invokespecial(listInit);
		a.astore(3);
		a.aload(1);
		a.checkcast(mapClass);
		a.aload(6);
		a.aload(3);
		a.invokevirtual(mapPut);
		a.pop();
		a.bind(haveBucket);
		a.iconst(0);
		a.istore(4);
		int top = a.label();
		int fresh = a.label();
		int next = a.label();
		a.bind(top);
		a.iload(4);
		a.aload(3);
		a.invokevirtual(listSize);
		a.branch(Opcode.IF_ICMPGE, fresh);
		a.aload(3);
		a.iload(4);
		a.invokevirtual(listGet);
		a.checkcast(objectArrayClass);
		a.astore(5);
		a.aload(5);
		a.iconst(0);
		a.aaload();
		a.aload(0);
		a.invokestatic(equalMethod);
		a.branch(Opcode.IFEQ, next);
		// The stored key becomes the key just handed in, matching the interpreter (its
		// entry record is replaced), so maphash hands back the newest key object.
		a.aload(5);
		a.iconst(0);
		a.aload(0);
		a.aastore();
		a.aload(5);
		a.iconst(1);
		a.aload(2);
		a.aastore();
		a.aload(2);
		a.areturn();
		a.bind(next);
		a.iinc(4, 1);
		a.branch(Opcode.GOTO, top);
		a.bind(fresh);
		a.iconst(2);
		a.anewarray(objectClass);
		a.astore(5);
		a.aload(5);
		a.iconst(0);
		a.aload(0);
		a.aastore();
		a.aload(5);
		a.iconst(1);
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
		a.aload(2);
		a.areturn();
		return new HashMethod(cp.addUtf8(PUT), cp.addUtf8(PUT_DESC), 4, 7, a.code);
	}

	// _hashRem(key, table): drop the pair from its bucket and from the order list; an
	// emptied bucket goes with it, so a put/remove cycle does not leak bucket objects.
	private static HashMethod buildRem(ConstantPool cp, ClassConstant mapClass, ClassConstant listClass,
			ClassConstant objectArrayClass, MethodrefConstant mapGet, MethodrefConstant mapRemove,
			MethodrefConstant listGet, MethodrefConstant listSize, MethodrefConstant listRemoveAt,
			MethodrefConstant listRemoveObj, MethodrefConstant integerValueOf, MethodrefConstant hashRef,
			MethodrefConstant ordRef, MethodrefConstant equalMethod, StringConstant trueStr,
			@Nullable MethodrefConstant keyRef) {
		JvmAsm a = new JvmAsm();
		emitFoldKey(a, keyRef);
		emitKeyHash(a, hashRef, integerValueOf);
		a.astore(5);
		a.aload(1);
		a.checkcast(mapClass);
		a.aload(5);
		a.invokevirtual(mapGet);
		a.checkcast(listClass);
		a.astore(2);
		int haveBucket = a.label();
		a.aload(2);
		a.branch(Opcode.IFNONNULL, haveBucket);
		a.aconstNull();
		a.areturn();
		a.bind(haveBucket);
		a.iconst(0);
		a.istore(3);
		int top = a.label();
		int miss = a.label();
		int next = a.label();
		int keepBucket = a.label();
		a.bind(top);
		a.iload(3);
		a.aload(2);
		a.invokevirtual(listSize);
		a.branch(Opcode.IF_ICMPGE, miss);
		a.aload(2);
		a.iload(3);
		a.invokevirtual(listGet);
		a.checkcast(objectArrayClass);
		a.astore(4);
		a.aload(4);
		a.iconst(0);
		a.aaload();
		a.aload(0);
		a.invokestatic(equalMethod);
		a.branch(Opcode.IFEQ, next);
		a.aload(2);
		a.iload(3);
		a.invokevirtual(listRemoveAt);
		a.pop();
		a.aload(1);
		a.invokestatic(ordRef);
		a.aload(4);
		a.invokevirtual(listRemoveObj);
		a.pop();
		a.aload(2);
		a.invokevirtual(listSize);
		a.branch(Opcode.IFNE, keepBucket);
		a.aload(1);
		a.checkcast(mapClass);
		a.aload(5);
		a.invokevirtual(mapRemove);
		a.pop();
		a.bind(keepBucket);
		a.ldcString(trueStr);
		a.areturn();
		a.bind(next);
		a.iinc(3, 1);
		a.branch(Opcode.GOTO, top);
		a.bind(miss);
		a.aconstNull();
		a.areturn();
		return new HashMethod(cp.addUtf8(REM), cp.addUtf8(REM_DESC), 3, 6, a.code);
	}

	// The equalp trio, emitted only for a program that writes :test 'equalp.
	//
	// _hashMakeP() marks a fresh table with the reserved EQUALP_KEY (a String key, so it
	// collides with no Integer bucket key, exactly like ORDER_KEY); _hashEqp(table)
	// answers that marker, which is what the printer and hash-table-test read; and
	// _hashKey(key, table) folds a key through the travelling RontoHashTable.equalpKey
	// when the table carries the marker. Placement stays ONE structural table: equalp on
	// two values is equal on their folds, so _hash and _equal are untouched.
	private static List<HashMethod> buildEqualpFold(ConstantPool cp, ClassConstant thisClass, ClassConstant mapClass,
			MethodrefConstant mapGet, MethodrefConstant mapPut, StringConstant trueStr) {
		StringConstant equalpKey = cp.addString(RontoHashTable.EQUALP_KEY);
		MethodrefConstant makeRef = cp.addMethodref(thisClass,
				cp.addNameAndType(cp.addUtf8(MAKE), cp.addUtf8(MAKE_DESC)));
		MethodrefConstant equalpPRef = cp.addMethodref(thisClass,
				cp.addNameAndType(cp.addUtf8(EQUALP_P), cp.addUtf8(EQUALP_P_DESC)));
		MethodrefConstant foldRef = cp.addMethodref(
				cp.addClass(cp.addUtf8(RontoHashTable.class.getName().replace('.', '/'))),
				cp.addNameAndType(cp.addUtf8("equalpKey"), cp.addUtf8("(Ljava/lang/Object;I)Ljava/lang/Object;")));

		List<HashMethod> methods = new ArrayList<>();

		JvmAsm make = new JvmAsm();
		make.invokestatic(makeRef);
		make.astore(0);
		make.aload(0);
		make.checkcast(mapClass);
		make.ldcString(equalpKey);
		make.ldcString(trueStr);
		make.invokevirtual(mapPut);
		make.pop();
		make.aload(0);
		make.areturn();
		methods.add(new HashMethod(cp.addUtf8(MAKE_EQUALP), cp.addUtf8(MAKE_EQUALP_DESC), 4, 1, make.code));

		JvmAsm eqp = new JvmAsm();
		eqp.aload(0);
		eqp.instanceOf(mapClass);
		int notTable = eqp.label();
		eqp.branch(Opcode.IFEQ, notTable);
		eqp.aload(0);
		eqp.checkcast(mapClass);
		eqp.ldcString(equalpKey);
		eqp.invokevirtual(mapGet);
		eqp.areturn();
		eqp.bind(notTable);
		eqp.aconstNull();
		eqp.areturn();
		methods.add(new HashMethod(cp.addUtf8(EQUALP_P), cp.addUtf8(EQUALP_P_DESC), 2, 1, eqp.code));

		JvmAsm key = new JvmAsm();
		key.aload(1);
		key.invokestatic(equalpPRef);
		int unfolded = key.label();
		key.branch(Opcode.IFNULL, unfolded);
		key.aload(0);
		key.iconst(RontoHashTable.FOLD_DEPTH_CAP);
		key.invokestatic(foldRef);
		key.areturn();
		key.bind(unfolded);
		key.aload(0);
		key.areturn();
		methods.add(new HashMethod(cp.addUtf8(KEY), cp.addUtf8(KEY_DESC), 2, 2, key.code));

		return methods;
	}

	// Replaces local 0 (the key) with its equalp fold when the table in local 1 asks for
	// one. A no-op -- not one instruction -- in a program with no equalp table.
	private static void emitFoldKey(JvmAsm a, @Nullable MethodrefConstant keyRef) {
		if (keyRef == null) {
			return;
		}
		a.aload(0);
		a.aload(1);
		a.invokestatic(keyRef);
		a.astore(0);
	}

	// Pushes Integer.valueOf(_hash(local 0, HASH_DEPTH_CAP)) -- the bucket key.
	private static void emitKeyHash(JvmAsm a, MethodrefConstant hashRef, MethodrefConstant integerValueOf) {
		a.aload(0);
		a.iconst(LispEquality.HASH_DEPTH_CAP);
		a.invokestatic(hashRef);
		a.invokestatic(integerValueOf);
	}

}
