package am.ik.rontolisp.codegen.jvm;

import java.util.ArrayList;
import java.util.List;

import am.ik.jvm.ConstantPool;
import am.ik.jvm.ConstantPool.ClassConstant;
import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.Opcode;
import am.ik.rontolisp.codegen.jvm.JvmArrayRuntimeBuilder.ArrayMethod;

/**
 * Builds the JVM bytecode for the packed integer-vector runtime helpers ({@code _iv*}). A
 * packed integer vector ({@code (make-array n :element-type '(unsigned-byte 8|16|32))},
 * rank 1, no fill pointer / adjustability / displacement, or ironclad's {@code #N@(...)}
 * literal) is represented at runtime as a bare {@code long[]} with a width header:
 * {@code [width, e_0, ..., e_{n-1}]} -- slot 0 holds the element width in bits (8, 16 or
 * 32) and the elements (pre-masked, non-negative) start at slot 1, so the length is
 * {@code arr.length - 1}. A {@code long[]} is disjoint from every other runtime shape
 * ({@code int[]} is the character box, {@code double[]}/{@code float[]} the packed float
 * arrays, {@code Object[]} cons/function/ratio, {@code ArrayList} general arrays), so
 * {@code instanceof long[]} is a free discriminator and no other value predicate changes.
 *
 * <p>
 * Element semantics (identical on every backend, {@code .kb/packed-integer-vectors.md}):
 * a store MASKS the value to the width (two's-complement truncation) and returns the
 * value AS STORED; a read returns the stored value widened unsigned; a {@code BigInteger}
 * store contributes its low bits ({@code Number.longValue()}); a non-integer store and an
 * out-of-range index are clear runtime errors.
 *
 * <p>
 * These helpers are emitted only when the program can produce a packed integer vector
 * (see {@code JvmLispCompiler.Ctx#usesIntArray}). Each accessor dispatches on
 * {@code instanceof long[]} first and otherwise delegates down the chain -- to the
 * {@code _fv*} float dispatch helper when the program also uses packed float arrays, else
 * straight to the general {@code _array*}/{@code _length} helper -- mirroring how the
 * {@code _fv*} helpers themselves delegate to the general tier.
 */
final class JvmIntArrayRuntimeBuilder {

	static final String OBJ = "Ljava/lang/Object;";

	static final String TO_GENERAL = "_ivToGeneral";

	static final String TO_GENERAL_DESC = "(" + OBJ + ")" + OBJ;

	static final String AREF1 = "_ivAref1";

	static final String ASET1 = "_ivAset1";

	static final String DIMS = "_ivDims";

	static final String LENGTH = "_ivLength";

	static final String MAKE = "_ivMake";

	static final String MAKE_DESC = "(" + OBJ + OBJ + "I)" + OBJ;

	static final String ELEMENT_TYPE = "_ivElementType";

	static final String ELEMENT_TYPE_DESC = "(" + OBJ + ")" + OBJ;

	static final String ALIKE = "_ivAlike";

	static final String ALIKE_DESC = "(" + OBJ + OBJ + ")" + OBJ;

	static final String REQUIRE_GENERAL = "_ivRequireGeneral";

	static final String REQUIRE_GENERAL_DESC = "(" + OBJ + ")" + OBJ;

	// _ivCheckRank(arr, given): packed (always rank 1) -> compare 1 against `given`; else
	// delegate down the chain (_fv* when the program also uses packed float arrays, else
	// straight to the general helper). See JvmArrayRuntimeBuilder#CHECK_RANK.
	static final String CHECK_RANK = "_ivCheckRank";

	static final String CHECK_RANK_DESC = "(" + OBJ + OBJ + ")" + OBJ;

	private JvmIntArrayRuntimeBuilder() {
	}

	/**
	 * Builds the packed integer-vector helper methods emitted into the program class.
	 * @param cp the constant pool
	 * @param objectClass the {@code java/lang/Object} class constant
	 * @param objectArrayClass the {@code [Ljava/lang/Object;} class constant
	 * @param selfClass the generated program class (for self-referencing invokestatic)
	 * @param usesFloatArray whether the packed float-array helpers are emitted too; when
	 * true the non-packed delegation goes through the {@code _fv*} dispatch tier (iv
	 * -&gt; fv -&gt; general), else straight to the general helpers
	 * @return the helper methods
	 */
	static List<ArrayMethod> build(ConstantPool cp, ClassConstant objectClass, ClassConstant objectArrayClass,
			ClassConstant selfClass, boolean usesFloatArray) {
		ClassConstant longArrayClass = cp.addClass(cp.addUtf8("[J"));
		ClassConstant arrayListClass = cp.addClass(cp.addUtf8("java/util/ArrayList"));
		ClassConstant longClass = cp.addClass(cp.addUtf8("java/lang/Long"));
		ClassConstant bigIntegerClass = cp.addClass(cp.addUtf8("java/math/BigInteger"));
		ClassConstant numberClass = cp.addClass(cp.addUtf8("java/lang/Number"));
		ClassConstant rtExClass = cp.addClass(cp.addUtf8("java/lang/RuntimeException"));
		MethodrefConstant rtExInit = cp.addMethodref(rtExClass,
				cp.addNameAndType(cp.addUtf8("<init>"), cp.addUtf8("(Ljava/lang/String;)V")));
		MethodrefConstant alInit = cp.addMethodref(arrayListClass,
				cp.addNameAndType(cp.addUtf8("<init>"), cp.addUtf8("()V")));
		MethodrefConstant alAdd = cp.addMethodref(arrayListClass,
				cp.addNameAndType(cp.addUtf8("add"), cp.addUtf8("(Ljava/lang/Object;)Z")));
		MethodrefConstant longIntValue = cp.addMethodref(longClass,
				cp.addNameAndType(cp.addUtf8("intValue"), cp.addUtf8("()I")));
		MethodrefConstant longValueOf = cp.addMethodref(longClass,
				cp.addNameAndType(cp.addUtf8("valueOf"), cp.addUtf8("(J)Ljava/lang/Long;")));
		MethodrefConstant numberLongValue = cp.addMethodref(numberClass,
				cp.addNameAndType(cp.addUtf8("longValue"), cp.addUtf8("()J")));
		// The next tier of the dispatch chain: the _fv* float helpers when they are
		// emitted, else the general _array*/_length helpers directly.
		MethodrefConstant aref1Delegate = self(cp, selfClass,
				usesFloatArray ? JvmFloatArrayRuntimeBuilder.AREF1 : JvmArrayRuntimeBuilder.AREF1,
				JvmArrayRuntimeBuilder.AREF1_DESC);
		MethodrefConstant aset1Delegate = self(cp, selfClass,
				usesFloatArray ? JvmFloatArrayRuntimeBuilder.ASET1 : JvmArrayRuntimeBuilder.ASET1,
				JvmArrayRuntimeBuilder.ASET1_DESC);
		MethodrefConstant dimsDelegate = self(cp, selfClass,
				usesFloatArray ? JvmFloatArrayRuntimeBuilder.DIMS : JvmArrayRuntimeBuilder.DIMS,
				JvmArrayRuntimeBuilder.DIMS_DESC);
		MethodrefConstant checkRankDelegate = self(cp, selfClass,
				usesFloatArray ? JvmFloatArrayRuntimeBuilder.CHECK_RANK : JvmArrayRuntimeBuilder.CHECK_RANK,
				JvmArrayRuntimeBuilder.CHECK_RANK_DESC);
		MethodrefConstant lengthDelegate = self(cp, selfClass,
				usesFloatArray ? JvmFloatArrayRuntimeBuilder.LENGTH : JvmLengthRuntimeBuilder.METHOD,
				JvmLengthRuntimeBuilder.DESC);
		MethodrefConstant elementTypeDelegate = usesFloatArray ? self(cp, selfClass,
				JvmFloatArrayRuntimeBuilder.ELEMENT_TYPE, JvmFloatArrayRuntimeBuilder.ELEMENT_TYPE_DESC) : null;
		MethodrefConstant arrayMake = self(cp, selfClass, JvmArrayRuntimeBuilder.MAKE,
				JvmArrayRuntimeBuilder.MAKE_DESC);
		MethodrefConstant arrayMakeTyped = self(cp, selfClass, JvmArrayRuntimeBuilder.MAKE_TYPED,
				JvmArrayRuntimeBuilder.MAKE_TYPED_DESC);

		List<ArrayMethod> methods = new ArrayList<>();
		methods.add(buildAref1(cp, longArrayClass, longClass, longIntValue, longValueOf, rtExClass, rtExInit,
				aref1Delegate));
		methods.add(buildAset1(cp, longArrayClass, longClass, bigIntegerClass, numberClass, longIntValue, longValueOf,
				numberLongValue, rtExClass, rtExInit, aset1Delegate));
		methods.add(buildDims(cp, longArrayClass, objectClass, longValueOf, dimsDelegate));
		methods
			.add(buildCheckRank(cp, longArrayClass, longClass, longIntValue, rtExClass, rtExInit, checkRankDelegate));
		methods.add(buildLength(cp, longArrayClass, longValueOf, lengthDelegate));
		methods.add(buildToGeneral(cp, longArrayClass, arrayListClass, objectClass, alInit, alAdd, longValueOf));
		methods.add(buildElementType(cp, longArrayClass, objectClass, longValueOf, elementTypeDelegate));
		methods.add(buildMake(cp, longArrayClass, objectArrayClass, longClass, bigIntegerClass, numberClass,
				longIntValue, longValueOf, numberLongValue, rtExClass, rtExInit, arrayMakeTyped));
		methods.add(buildAlike(cp, longArrayClass, longClass, longIntValue, arrayMake));
		methods.add(buildRequireGeneral(cp, longArrayClass, rtExClass, rtExInit));
		return methods;
	}

	private static MethodrefConstant self(ConstantPool cp, ClassConstant selfClass, String name, String desc) {
		return cp.addMethodref(selfClass, cp.addNameAndType(cp.addUtf8(name), cp.addUtf8(desc)));
	}

	// new RuntimeException(message); athrow.
	private static void emitThrow(JvmAsm a, ClassConstant rtExClass, MethodrefConstant rtExInit,
			ConstantPool.StringConstant message) {
		a.anew(rtExClass);
		a.dup();
		a.ldcString(message);
		a.invokespecial(rtExInit);
		a.op(Opcode.ATHROW);
	}

	// Bounds check for the packed vector in local l against the int index in local idx:
	// idx < 0 || idx >= l.length - 1 throws "out of range".
	private static void emitBoundsCheck(JvmAsm a, int l, int idx, ClassConstant rtExClass, MethodrefConstant rtExInit,
			ConstantPool.StringConstant message) {
		int oob = a.label();
		int ok = a.label();
		a.iload(idx);
		a.branch(Opcode.IFLT, oob);
		a.iload(idx);
		a.aload(l);
		a.arraylength();
		a.iconst(1);
		a.op(Opcode.ISUB);
		a.branch(Opcode.IF_ICMPLT, ok);
		a.bind(oob);
		emitThrow(a, rtExClass, rtExInit, message);
		a.bind(ok);
	}

	// Coerces the integer value in objSlot to a raw long in vSlot (Long or BigInteger:
	// Number.longValue() keeps the low 64 bits; the caller's width mask keeps fewer);
	// anything else throws the "stores integers" type error.
	private static void emitCoerceInt(JvmAsm a, int objSlot, int vSlot, ClassConstant longClass,
			ClassConstant bigIntegerClass, ClassConstant numberClass, MethodrefConstant numberLongValue,
			ClassConstant rtExClass, MethodrefConstant rtExInit, ConstantPool.StringConstant message) {
		int coerceOk = a.label();
		int bad = a.label();
		a.aload(objSlot);
		a.instanceOf(longClass);
		a.branch(Opcode.IFNE, coerceOk);
		a.aload(objSlot);
		a.instanceOf(bigIntegerClass);
		a.branch(Opcode.IFNE, coerceOk);
		a.bind(bad);
		emitThrow(a, rtExClass, rtExInit, message);
		a.bind(coerceOk);
		a.aload(objSlot);
		a.checkcast(numberClass);
		a.invokevirtual(numberLongValue);
		a.lstore(vSlot);
	}

	// Masks the raw long in vSlot to the width (in bits) in widthSlot:
	// v &= (1L << width) - 1.
	private static void emitMask(JvmAsm a, int vSlot, int widthSlot) {
		a.lload(vSlot);
		a.op(Opcode.LCONST_1);
		a.iload(widthSlot);
		a.op(Opcode.LSHL);
		a.op(Opcode.LCONST_1);
		a.op(Opcode.LSUB);
		a.op(Opcode.LAND);
		a.lstore(vSlot);
	}

	// _ivAref1(arr, i): packed -> Long.valueOf(l[1 + (int) i]) (pre-masked unsigned, so
	// the boxed Long is the widened unsigned read); out-of-range index errors; else
	// delegate. Serves rank-1 aref and row-major-aref. Locals: 0=arr, 1=i, 2=l, 3=idx.
	private static ArrayMethod buildAref1(ConstantPool cp, ClassConstant longArrayClass, ClassConstant longClass,
			MethodrefConstant longIntValue, MethodrefConstant longValueOf, ClassConstant rtExClass,
			MethodrefConstant rtExInit, MethodrefConstant aref1Delegate) {
		int arr = 0, i = 1, l = 2, idx = 3;
		JvmAsm a = new JvmAsm();
		int notPacked = a.label();
		a.aload(arr);
		a.instanceOf(longArrayClass);
		a.branch(Opcode.IFEQ, notPacked);
		a.aload(arr);
		a.checkcast(longArrayClass);
		a.astore(l);
		a.aload(i);
		a.checkcast(longClass);
		a.invokevirtual(longIntValue);
		a.istore(idx);
		emitBoundsCheck(a, l, idx, rtExClass, rtExInit,
				cp.addString("aref: index out of range for a packed integer vector"));
		a.aload(l);
		a.iconst(1);
		a.iload(idx);
		a.op(Opcode.IADD);
		a.laload();
		a.invokestatic(longValueOf);
		a.areturn();
		a.bind(notPacked);
		a.aload(arr);
		a.aload(i);
		a.invokestatic(aref1Delegate);
		a.areturn();
		return new ArrayMethod(cp.addUtf8(AREF1), cp.addUtf8(JvmArrayRuntimeBuilder.AREF1_DESC), 5, 4, a.finish());
	}

	// _ivAset1(arr, i, val): packed -> l[1 + (int) i] = coerce(val) & widthMask, return
	// the stored value as a Long (the value AS STORED, matching the interpreter); else
	// delegate. Serves rank-1 %aset and %row-major-aset. Locals: 0=arr, 1=i, 2=val, 3=l,
	// 4=idx, 5=width, 6..7=v.
	private static ArrayMethod buildAset1(ConstantPool cp, ClassConstant longArrayClass, ClassConstant longClass,
			ClassConstant bigIntegerClass, ClassConstant numberClass, MethodrefConstant longIntValue,
			MethodrefConstant longValueOf, MethodrefConstant numberLongValue, ClassConstant rtExClass,
			MethodrefConstant rtExInit, MethodrefConstant aset1Delegate) {
		int arr = 0, i = 1, val = 2, l = 3, idx = 4, width = 5, v = 6;
		JvmAsm a = new JvmAsm();
		int notPacked = a.label();
		a.aload(arr);
		a.instanceOf(longArrayClass);
		a.branch(Opcode.IFEQ, notPacked);
		a.aload(arr);
		a.checkcast(longArrayClass);
		a.astore(l);
		a.aload(i);
		a.checkcast(longClass);
		a.invokevirtual(longIntValue);
		a.istore(idx);
		emitBoundsCheck(a, l, idx, rtExClass, rtExInit,
				cp.addString("%aset: index out of range for a packed integer vector"));
		emitCoerceInt(a, val, v, longClass, bigIntegerClass, numberClass, numberLongValue, rtExClass, rtExInit,
				cp.addString("%aset: a packed integer vector stores integers"));
		// width = (int) l[0]; v &= (1L << width) - 1
		a.aload(l);
		a.iconst(0);
		a.laload();
		a.l2i();
		a.istore(width);
		emitMask(a, v, width);
		a.aload(l);
		a.iconst(1);
		a.iload(idx);
		a.op(Opcode.IADD);
		a.lload(v);
		a.lastore();
		a.lload(v);
		a.invokestatic(longValueOf);
		a.areturn();
		a.bind(notPacked);
		a.aload(arr);
		a.aload(i);
		a.aload(val);
		a.invokestatic(aset1Delegate);
		a.areturn();
		return new ArrayMethod(cp.addUtf8(ASET1), cp.addUtf8(JvmArrayRuntimeBuilder.ASET1_DESC), 7, 8, a.finish());
	}

	// _ivDims(arr): packed -> the fresh cons list (n); else delegate. A cons is an
	// Object[]{car, cdr}, nil is null. Locals: 0=arr.
	private static ArrayMethod buildDims(ConstantPool cp, ClassConstant longArrayClass, ClassConstant objectClass,
			MethodrefConstant longValueOf, MethodrefConstant dimsDelegate) {
		JvmAsm a = new JvmAsm();
		int notPacked = a.label();
		a.aload(0);
		a.instanceOf(longArrayClass);
		a.branch(Opcode.IFEQ, notPacked);
		a.iconst(2);
		a.anewarray(objectClass);
		a.dup();
		a.iconst(0);
		a.aload(0);
		a.checkcast(longArrayClass);
		a.arraylength();
		a.iconst(1);
		a.op(Opcode.ISUB);
		a.i2l();
		a.invokestatic(longValueOf);
		a.aastore();
		a.areturn();
		a.bind(notPacked);
		a.aload(0);
		a.invokestatic(dimsDelegate);
		a.areturn();
		return new ArrayMethod(cp.addUtf8(DIMS), cp.addUtf8(JvmArrayRuntimeBuilder.DIMS_DESC), 7, 1, a.finish());
	}

	// _ivCheckRank(arr, given): packed -> rank is always 1 (a packed integer vector is
	// always rank 1, no header field to read); else delegate down the chain. Locals:
	// 0=arr, 1=given, 2=rank, 3=giv.
	private static ArrayMethod buildCheckRank(ConstantPool cp, ClassConstant longArrayClass, ClassConstant longClass,
			MethodrefConstant longIntValue, ClassConstant rtExClass, MethodrefConstant rtExInit,
			MethodrefConstant checkRankDelegate) {
		ClassConstant sbClass = cp.addClass(cp.addUtf8("java/lang/StringBuilder"));
		MethodrefConstant sbInit = cp.addMethodref(sbClass, cp.addNameAndType(cp.addUtf8("<init>"), cp.addUtf8("()V")));
		MethodrefConstant sbAppendStr = cp.addMethodref(sbClass,
				cp.addNameAndType(cp.addUtf8("append"), cp.addUtf8("(Ljava/lang/String;)Ljava/lang/StringBuilder;")));
		MethodrefConstant sbAppendInt = cp.addMethodref(sbClass,
				cp.addNameAndType(cp.addUtf8("append"), cp.addUtf8("(I)Ljava/lang/StringBuilder;")));
		MethodrefConstant sbToString = cp.addMethodref(sbClass,
				cp.addNameAndType(cp.addUtf8("toString"), cp.addUtf8("()Ljava/lang/String;")));
		int arr = 0, given = 1, rank = 2, giv = 3;
		JvmAsm a = new JvmAsm();
		int notPacked = a.label();
		a.aload(arr);
		a.instanceOf(longArrayClass);
		a.branch(Opcode.IFEQ, notPacked);
		a.iconst(1);
		a.istore(rank);
		emitRankCheckAndReturn(cp, a, longClass, longIntValue, sbClass, sbInit, sbAppendStr, sbAppendInt, sbToString,
				rtExClass, rtExInit, arr, given, rank, giv);
		a.bind(notPacked);
		a.aload(arr);
		a.aload(given);
		a.invokestatic(checkRankDelegate);
		a.areturn();
		return new ArrayMethod(cp.addUtf8(CHECK_RANK), cp.addUtf8(CHECK_RANK_DESC), 6, 4, a.finish());
	}

	// Shared tail of _ivCheckRank: unbox `given` (givenSlot) to int (givSlot), compare it
	// against the already-computed actual rank (rankSlot); a match returns arr (arrSlot)
	// unchanged, a mismatch throws the "aref: expected N subscripts, got M" text the
	// interpreter uses.
	private static void emitRankCheckAndReturn(ConstantPool cp, JvmAsm a, ClassConstant longClass,
			MethodrefConstant longIntValue, ClassConstant sbClass, MethodrefConstant sbInit,
			MethodrefConstant sbAppendStr, MethodrefConstant sbAppendInt, MethodrefConstant sbToString,
			ClassConstant rtExClass, MethodrefConstant rtExInit, int arrSlot, int givenSlot, int rankSlot,
			int givSlot) {
		a.aload(givenSlot);
		a.checkcast(longClass);
		a.invokevirtual(longIntValue);
		a.istore(givSlot);
		int ok = a.label();
		a.iload(rankSlot);
		a.iload(givSlot);
		a.branch(Opcode.IF_ICMPEQ, ok);
		a.anew(rtExClass);
		a.dup();
		a.anew(sbClass);
		a.dup();
		a.invokespecial(sbInit);
		a.ldcString(cp.addString("aref: expected "));
		a.invokevirtual(sbAppendStr);
		a.iload(rankSlot);
		a.invokevirtual(sbAppendInt);
		a.ldcString(cp.addString(" subscripts, got "));
		a.invokevirtual(sbAppendStr);
		a.iload(givSlot);
		a.invokevirtual(sbAppendInt);
		a.invokevirtual(sbToString);
		a.invokespecial(rtExInit);
		a.op(Opcode.ATHROW);
		a.bind(ok);
		a.aload(arrSlot);
		a.areturn();
	}

	// _ivLength(arr): packed -> Long.valueOf(arr.length - 1); else delegate. Locals:
	// 0=arr.
	private static ArrayMethod buildLength(ConstantPool cp, ClassConstant longArrayClass, MethodrefConstant longValueOf,
			MethodrefConstant lengthDelegate) {
		JvmAsm a = new JvmAsm();
		int notPacked = a.label();
		a.aload(0);
		a.instanceOf(longArrayClass);
		a.branch(Opcode.IFEQ, notPacked);
		a.aload(0);
		a.checkcast(longArrayClass);
		a.arraylength();
		a.iconst(1);
		a.op(Opcode.ISUB);
		a.i2l();
		a.invokestatic(longValueOf);
		a.areturn();
		a.bind(notPacked);
		a.aload(0);
		a.invokestatic(lengthDelegate);
		a.areturn();
		return new ArrayMethod(cp.addUtf8(LENGTH), cp.addUtf8(JvmLengthRuntimeBuilder.DESC), 3, 1, a.finish());
	}

	// _ivToGeneral(o): converts a packed long[] into the equivalent general array (an
	// ArrayList whose slot 0 is the {dims, null, null} header and slots 1.. are boxed
	// Longs), so the general _arrayToString renderer prints it as a plain #(...) vector
	// (CL prints specialized vectors that way). Only ever called with a packed vector
	// (the print dispatch tests instanceof first). Locals: 0=o, 1=l, 2=n, 3=list, 4=f.
	private static ArrayMethod buildToGeneral(ConstantPool cp, ClassConstant longArrayClass,
			ClassConstant arrayListClass, ClassConstant objectClass, MethodrefConstant alInit, MethodrefConstant alAdd,
			MethodrefConstant longValueOf) {
		int o = 0, l = 1, n = 2, list = 3, f = 4;
		JvmAsm a = new JvmAsm();
		a.aload(o);
		a.checkcast(longArrayClass);
		a.astore(l);
		a.aload(l);
		a.arraylength();
		a.iconst(1);
		a.op(Opcode.ISUB);
		a.istore(n);
		a.anew(arrayListClass);
		a.dup();
		a.invokespecial(alInit);
		a.astore(list);
		// list.add(new Object[]{new Object[]{Long.valueOf(n)}, null, null})
		a.aload(list);
		a.iconst(3);
		a.anewarray(objectClass);
		a.dup();
		a.iconst(0);
		a.iconst(1);
		a.anewarray(objectClass);
		a.dup();
		a.iconst(0);
		a.iload(n);
		a.i2l();
		a.invokestatic(longValueOf);
		a.aastore();
		a.aastore();
		a.invokevirtual(alAdd);
		a.pop();
		a.iconst(0);
		a.istore(f);
		int loop = a.label();
		int done = a.label();
		a.bind(loop);
		a.iload(f);
		a.iload(n);
		a.branch(Opcode.IF_ICMPGE, done);
		a.aload(list);
		a.aload(l);
		a.iconst(1);
		a.iload(f);
		a.op(Opcode.IADD);
		a.laload();
		a.invokestatic(longValueOf);
		a.invokevirtual(alAdd);
		a.pop();
		a.iinc(f, 1);
		a.branch(Opcode.GOTO, loop);
		a.bind(done);
		a.aload(list);
		a.areturn();
		return new ArrayMethod(cp.addUtf8(TO_GENERAL), cp.addUtf8(TO_GENERAL_DESC), 10, 5, a.finish());
	}

	// _ivElementType(arr): packed -> the fresh cons list (UNSIGNED-BYTE width) (the REAL
	// specifier, matching the interpreter); else delegate to _fvElementType (when the
	// float helpers are emitted) or answer the symbol t directly (general arrays are
	// element-type t). Locals: 0=arr.
	private static ArrayMethod buildElementType(ConstantPool cp, ClassConstant longArrayClass,
			ClassConstant objectClass, MethodrefConstant longValueOf,
			@org.jspecify.annotations.Nullable MethodrefConstant elementTypeDelegate) {
		JvmAsm a = new JvmAsm();
		int notPacked = a.label();
		a.aload(0);
		a.instanceOf(longArrayClass);
		a.branch(Opcode.IFEQ, notPacked);
		// new Object[]{"UNSIGNED-BYTE", new Object[]{Long.valueOf(l[0]), null}}
		a.iconst(2);
		a.anewarray(objectClass);
		a.dup();
		a.iconst(0);
		a.ldcString(cp.addString(am.ik.rontolisp.LispNames.UNSIGNED_BYTE));
		a.aastore();
		a.dup();
		a.iconst(1);
		a.iconst(2);
		a.anewarray(objectClass);
		a.dup();
		a.iconst(0);
		a.aload(0);
		a.checkcast(longArrayClass);
		a.iconst(0);
		a.laload();
		a.invokestatic(longValueOf);
		a.aastore();
		a.aastore();
		a.areturn();
		a.bind(notPacked);
		if (elementTypeDelegate != null) {
			a.aload(0);
			a.invokestatic(elementTypeDelegate);
			a.areturn();
		}
		else {
			a.ldcString(cp.addString("T"));
			a.areturn();
		}
		return new ArrayMethod(cp.addUtf8(ELEMENT_TYPE), cp.addUtf8(ELEMENT_TYPE_DESC), 10, 1, a.finish());
	}

	// _ivMake(dims, init, width): build a packed long[] of the compile-time literal
	// width when dims designates rank 1 (a Long, or a one-element cons list of Longs),
	// filled with the masked integer init (default 0; a non-integer init is a type
	// error). Any other dims shape (rank n) keeps the general boxed representation via
	// _arrayMake, mirroring the interpreter's runtime rank check. Locals: 0=dims,
	// 1=init, 2=width, 3=n, 4=arr, 5=i, 6..7=fill.
	private static ArrayMethod buildMake(ConstantPool cp, ClassConstant longArrayClass, ClassConstant objectArrayClass,
			ClassConstant longClass, ClassConstant bigIntegerClass, ClassConstant numberClass,
			MethodrefConstant longIntValue, MethodrefConstant longValueOf, MethodrefConstant numberLongValue,
			ClassConstant rtExClass, MethodrefConstant rtExInit, MethodrefConstant arrayMakeTyped) {
		int dims = 0, init = 1, width = 2, n = 3, arr = 4, i = 5, fill = 6;
		JvmAsm a = new JvmAsm();
		int tryList = a.label();
		int general = a.label();
		int haveN = a.label();
		a.aload(dims);
		a.instanceOf(longClass);
		a.branch(Opcode.IFEQ, tryList);
		// rank-1 shorthand: n = (int) dims
		a.aload(dims);
		a.checkcast(longClass);
		a.invokevirtual(longIntValue);
		a.istore(n);
		a.branch(Opcode.GOTO, haveN);
		// a one-element cons list of dims is rank 1 too: (n) with cdr nil
		a.bind(tryList);
		a.aload(dims);
		a.instanceOf(objectArrayClass);
		a.branch(Opcode.IFEQ, general);
		a.aload(dims);
		a.checkcast(objectArrayClass);
		a.iconst(1);
		a.aaload();
		a.branch(Opcode.IFNONNULL, general);
		a.aload(dims);
		a.checkcast(objectArrayClass);
		a.iconst(0);
		a.aaload();
		a.checkcast(longClass);
		a.invokevirtual(longIntValue);
		a.istore(n);
		a.branch(Opcode.GOTO, haveN);
		// rank n: the general representation (no fill pointer / adjustability at this
		// call site by construction), REMEMBERING the (unsigned-byte width) it was asked
		// for and defaulting an unsupplied element to 0 rather than nil. The 0 also
		// keeps the allocation on _arrayMake's packed long[] path, so the type is
		// remembered without giving up the packing.
		a.bind(general);
		int initGiven = a.label();
		int initDone = a.label();
		a.aload(dims);
		a.aload(init);
		a.branch(Opcode.IFNONNULL, initGiven);
		a.op(Opcode.LCONST_0);
		a.invokestatic(longValueOf);
		a.branch(Opcode.GOTO, initDone);
		a.bind(initGiven);
		a.aload(init);
		a.bind(initDone);
		a.aconstNull();
		a.aconstNull();
		emitWidthToElementTypeCode(a, width);
		a.invokestatic(arrayMakeTyped);
		a.areturn();
		a.bind(haveN);
		a.iload(n);
		a.iconst(1);
		a.op(Opcode.IADD);
		a.newarrayLong();
		a.astore(arr);
		a.aload(arr);
		a.iconst(0);
		a.iload(width);
		a.i2l();
		a.lastore();
		int done = a.label();
		a.aload(init);
		a.branch(Opcode.IFNULL, done);
		emitCoerceInt(a, init, fill, longClass, bigIntegerClass, numberClass, numberLongValue, rtExClass, rtExInit,
				cp.addString("make-array: a packed integer vector stores integers"));
		emitMask(a, fill, width);
		a.iconst(0);
		a.istore(i);
		int loop = a.label();
		a.bind(loop);
		a.iload(i);
		a.iload(n);
		a.branch(Opcode.IF_ICMPGE, done);
		a.aload(arr);
		a.iconst(1);
		a.iload(i);
		a.op(Opcode.IADD);
		a.lload(fill);
		a.lastore();
		a.iinc(i, 1);
		a.branch(Opcode.GOTO, loop);
		a.bind(done);
		a.aload(arr);
		a.areturn();
		return new ArrayMethod(cp.addUtf8(MAKE), cp.addUtf8(MAKE_DESC), 7, 8, a.finish());
	}

	// _ivAlike(seq, n): the %array-alike allocator -- a fresh zero-filled rank-1 array
	// with the SAME representation as seq: packed at seq's width when seq is a packed
	// integer vector, else a general nil-filled vector via _arrayMake. Keeps subseq /
	// copy-seq type-preserving. Locals: 0=seq, 1=n, 2=ni, 3=arr.
	private static ArrayMethod buildAlike(ConstantPool cp, ClassConstant longArrayClass, ClassConstant longClass,
			MethodrefConstant longIntValue, MethodrefConstant arrayMake) {
		int seq = 0, n = 1, ni = 2, arr = 3;
		JvmAsm a = new JvmAsm();
		int general = a.label();
		a.aload(seq);
		a.instanceOf(longArrayClass);
		a.branch(Opcode.IFEQ, general);
		a.aload(n);
		a.checkcast(longClass);
		a.invokevirtual(longIntValue);
		a.istore(ni);
		a.iload(ni);
		a.iconst(1);
		a.op(Opcode.IADD);
		a.newarrayLong();
		a.astore(arr);
		// arr[0] = seq[0] -- copy the width header; the data slots stay zero
		a.aload(arr);
		a.iconst(0);
		a.aload(seq);
		a.checkcast(longArrayClass);
		a.iconst(0);
		a.laload();
		a.lastore();
		a.aload(arr);
		a.areturn();
		a.bind(general);
		a.aload(n);
		a.aconstNull();
		a.aconstNull();
		a.aconstNull();
		a.invokestatic(arrayMake);
		a.areturn();
		return new ArrayMethod(cp.addUtf8(ALIKE), cp.addUtf8(ALIKE_DESC), 6, 4, a.finish());
	}

	// _ivRequireGeneral(o): the fill-pointer-surface guard -- a packed integer vector
	// has no fill pointer, adjustability or displacement, so those operations reject it
	// with a clear error (mirroring the interpreter's requireGeneralArray); any other
	// value passes through unchanged. Locals: 0=o.
	private static ArrayMethod buildRequireGeneral(ConstantPool cp, ClassConstant longArrayClass,
			ClassConstant rtExClass, MethodrefConstant rtExInit) {
		JvmAsm a = new JvmAsm();
		int ok = a.label();
		a.aload(0);
		a.instanceOf(longArrayClass);
		a.branch(Opcode.IFEQ, ok);
		emitThrow(a, rtExClass, rtExInit, cp.addString("not applicable to a packed integer vector"));
		a.bind(ok);
		a.aload(0);
		a.areturn();
		return new ArrayMethod(cp.addUtf8(REQUIRE_GENERAL), cp.addUtf8(REQUIRE_GENERAL_DESC), 3, 1, a.finish());
	}

	// The ArrayElementTypes code for the packed width held in widthSlot: the widths are
	// 8/16/32 by construction, so two compares decide it.
	private static void emitWidthToElementTypeCode(JvmAsm a, int widthSlot) {
		int is8 = a.label();
		int is16 = a.label();
		int done = a.label();
		a.iload(widthSlot);
		a.iconst(8);
		a.branch(Opcode.IF_ICMPEQ, is8);
		a.iload(widthSlot);
		a.iconst(16);
		a.branch(Opcode.IF_ICMPEQ, is16);
		a.iconst(am.ik.rontolisp.ArrayElementTypes.UNSIGNED_BYTE_32);
		a.branch(Opcode.GOTO, done);
		a.bind(is8);
		a.iconst(am.ik.rontolisp.ArrayElementTypes.UNSIGNED_BYTE_8);
		a.branch(Opcode.GOTO, done);
		a.bind(is16);
		a.iconst(am.ik.rontolisp.ArrayElementTypes.UNSIGNED_BYTE_16);
		a.bind(done);
	}

}
