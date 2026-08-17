package am.ik.rontolisp.codegen.jvm;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import am.ik.jvm.ConstantPool;
import am.ik.jvm.ConstantPool.ClassConstant;
import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.ConstantPool.StringConstant;
import am.ik.jvm.ConstantPool.Utf8Constant;
import am.ik.jvm.Opcode;

/**
 * Builds the JVM bytecode for the hash-table runtime helpers. A hash table is represented
 * at runtime as a {@link #MAP_CLASS}; each entry maps the canonical key (the
 * {@code prin1} string of the Lisp key, produced by {@code _lispToString}) to an
 * {@code Object[2]} pair holding the original key and the stored value. Keying by the
 * printed representation gives structural ({@code equal}) comparison, matching the
 * interpreter and the WASM backend.
 *
 * <p>
 * The generated static helpers (all gated on the program actually using hash tables):
 * <ul>
 * <li>{@code _hashMake()} -&gt; a fresh map</li>
 * <li>{@code _hashGet(key, table, default)} -&gt; the stored value or the default</li>
 * <li>{@code _hashPut(key, table, value)} -&gt; the stored value</li>
 * <li>{@code _hashRem(key, table)} -&gt; t if an entry was removed, else nil</li>
 * <li>{@code _hashClr(table)} -&gt; the table</li>
 * <li>{@code _hashCount(table)} -&gt; the entry count</li>
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
	 * choice: iteration is insertion-ordered, which is what the interpreter's
	 * {@code LispHashTable} does, so {@code maphash} agrees with it for free.
	 */
	static final String MAP_CLASS = "java/util/LinkedHashMap";

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

	static final String P = "_hashP";

	static final String P_DESC = "(Ljava/lang/Object;)Ljava/lang/Object;";

	static final String VALUES = "_hashValues";

	static final String VALUES_DESC = "(Ljava/lang/Object;)[Ljava/lang/Object;";

	/**
	 * Every method name {@link #build} emits, i.e. exactly the group the hash gate
	 * switches on and off; {@code JvmLispCompiler} matches an unresolved own-class call
	 * against it to recognize an under-predicted gate. Pinned by
	 * {@code JvmRuntimeGroupNamesTest}.
	 */
	static final Set<String> METHOD_NAMES = Set.of(MAKE, GET, PUT, REM, CLR, COUNT, P, VALUES);

	/** A hash-table helper method body ready to be emitted into the generated class. */
	record HashMethod(Utf8Constant name, Utf8Constant desc, int maxStack, int maxLocals, List<Integer> code) {
	}

	private JvmHashRuntimeBuilder() {
	}

	static List<HashMethod> build(ConstantPool cp, ClassConstant thisClass, ClassConstant objectClass,
			ClassConstant objectArrayClass, MethodrefConstant longValueOf, MethodrefConstant lispToString) {
		ClassConstant hashMapClass = cp.addClass(cp.addUtf8(MAP_CLASS));
		ClassConstant collectionClass = cp.addClass(cp.addUtf8("java/util/Collection"));
		MethodrefConstant hashMapInit = cp.addMethodref(hashMapClass,
				cp.addNameAndType(cp.addUtf8("<init>"), cp.addUtf8("()V")));
		MethodrefConstant hashMapGet = cp.addMethodref(hashMapClass,
				cp.addNameAndType(cp.addUtf8("get"), cp.addUtf8("(Ljava/lang/Object;)Ljava/lang/Object;")));
		MethodrefConstant hashMapPut = cp.addMethodref(hashMapClass, cp.addNameAndType(cp.addUtf8("put"),
				cp.addUtf8("(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;")));
		MethodrefConstant hashMapRemove = cp.addMethodref(hashMapClass,
				cp.addNameAndType(cp.addUtf8("remove"), cp.addUtf8("(Ljava/lang/Object;)Ljava/lang/Object;")));
		MethodrefConstant hashMapClear = cp.addMethodref(hashMapClass,
				cp.addNameAndType(cp.addUtf8("clear"), cp.addUtf8("()V")));
		MethodrefConstant hashMapSize = cp.addMethodref(hashMapClass,
				cp.addNameAndType(cp.addUtf8("size"), cp.addUtf8("()I")));
		MethodrefConstant hashMapValues = cp.addMethodref(hashMapClass,
				cp.addNameAndType(cp.addUtf8("values"), cp.addUtf8("()Ljava/util/Collection;")));
		MethodrefConstant collectionToArray = cp.addInterfaceMethodref(collectionClass,
				cp.addNameAndType(cp.addUtf8("toArray"), cp.addUtf8("()[Ljava/lang/Object;")));
		// The compiled representation of the boolean t is the symbol "T" (a bare String).
		StringConstant trueStr = cp.addString("T");

		List<HashMethod> methods = new ArrayList<>();

		// _hashMake() -> new LinkedHashMap()
		JvmAsm make = new JvmAsm();
		make.anew(hashMapClass);
		make.dup();
		make.invokespecial(hashMapInit);
		make.areturn();
		methods.add(new HashMethod(cp.addUtf8(MAKE), cp.addUtf8(MAKE_DESC), 2, 1, make.code));

		// _hashGet(key, table, default): pair = ((LinkedHashMap)
		// table).get(_lispToString(key));
		// return (pair == null) ? default : pair[1]
		JvmAsm get = new JvmAsm();
		get.aload(0);
		get.invokestatic(lispToString);
		get.astore(3);
		get.aload(1);
		get.checkcast(hashMapClass);
		get.aload(3);
		get.invokevirtual(hashMapGet);
		get.dup();
		int getNull = get.label();
		get.branch(Opcode.IFNULL, getNull);
		get.checkcast(objectArrayClass);
		get.iconst(1);
		get.aaload();
		get.areturn();
		get.bind(getNull);
		get.pop();
		get.aload(2);
		get.areturn();
		methods.add(new HashMethod(cp.addUtf8(GET), cp.addUtf8(GET_DESC), 4, 4, get.code));

		// _hashPut(key, table, value): ((LinkedHashMap) table).put(_lispToString(key),
		// new
		// Object[]{key, value}); return value
		JvmAsm put = new JvmAsm();
		put.aload(1);
		put.checkcast(hashMapClass);
		put.aload(0);
		put.invokestatic(lispToString);
		put.iconst(2);
		put.anewarray(objectClass);
		put.dup();
		put.iconst(0);
		put.aload(0);
		put.aastore();
		put.dup();
		put.iconst(1);
		put.aload(2);
		put.aastore();
		put.invokevirtual(hashMapPut);
		put.pop();
		put.aload(2);
		put.areturn();
		methods.add(new HashMethod(cp.addUtf8(PUT), cp.addUtf8(PUT_DESC), 7, 3, put.code));

		// _hashRem(key, table): return (((LinkedHashMap)
		// table).remove(_lispToString(key)) !=
		// null) ? Long(1) : null
		JvmAsm rem = new JvmAsm();
		rem.aload(1);
		rem.checkcast(hashMapClass);
		rem.aload(0);
		rem.invokestatic(lispToString);
		rem.invokevirtual(hashMapRemove);
		int remNull = rem.label();
		rem.branch(Opcode.IFNULL, remNull);
		rem.ldcString(trueStr);
		rem.areturn();
		rem.bind(remNull);
		rem.aconstNull();
		rem.areturn();
		methods.add(new HashMethod(cp.addUtf8(REM), cp.addUtf8(REM_DESC), 3, 2, rem.code));

		// _hashClr(table): ((LinkedHashMap) table).clear(); return table
		JvmAsm clr = new JvmAsm();
		clr.aload(0);
		clr.checkcast(hashMapClass);
		clr.invokevirtual(hashMapClear);
		clr.aload(0);
		clr.areturn();
		methods.add(new HashMethod(cp.addUtf8(CLR), cp.addUtf8(CLR_DESC), 1, 1, clr.code));

		// _hashCount(table): return Long.valueOf(((LinkedHashMap) table).size())
		JvmAsm count = new JvmAsm();
		count.aload(0);
		count.checkcast(hashMapClass);
		count.invokevirtual(hashMapSize);
		count.op(Opcode.I2L);
		count.invokestatic(longValueOf);
		count.areturn();
		methods.add(new HashMethod(cp.addUtf8(COUNT), cp.addUtf8(COUNT_DESC), 2, 1, count.code));

		// _hashP(x): return (x instanceof LinkedHashMap) ? Long(1) : null
		JvmAsm hp = new JvmAsm();
		hp.aload(0);
		hp.instanceOf(hashMapClass);
		int hpFalse = hp.label();
		hp.branch(Opcode.IFEQ, hpFalse);
		hp.ldcString(trueStr);
		hp.areturn();
		hp.bind(hpFalse);
		hp.aconstNull();
		hp.areturn();
		methods.add(new HashMethod(cp.addUtf8(P), cp.addUtf8(P_DESC), 2, 1, hp.code));

		// _hashValues(table): return ((LinkedHashMap) table).values().toArray()
		JvmAsm values = new JvmAsm();
		values.aload(0);
		values.checkcast(hashMapClass);
		values.invokevirtual(hashMapValues);
		values.op(Opcode.INVOKEINTERFACE);
		values.u2(collectionToArray.index());
		values.op(1);
		values.op(0);
		values.areturn();
		methods.add(new HashMethod(cp.addUtf8(VALUES), cp.addUtf8(VALUES_DESC), 1, 1, values.code));

		return methods;
	}

}
