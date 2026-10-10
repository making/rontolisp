package am.ik.rontolisp.codegen.jvm;

import java.lang.classfile.TypeKind;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.MethodRefEntry;
import java.lang.classfile.constantpool.StringEntry;
import java.util.ArrayList;
import java.util.List;

import am.ik.jvm.ConstantPool;
import am.ik.jvm.MethodCode;
import am.ik.rontolisp.codegen.jvm.JvmArrayRuntimeBuilder.ArrayMethod;

/**
 * Builds the JVM bytecode for the packed integer-vector runtime helpers ({@code _iv*}). A
 * packed integer vector ({@code (make-array n :element-type '(unsigned-byte 8|16|32))},
 * rank 1, no fill pointer / adjustability / displacement, or ironclad's {@code #N@(...)}
 * literal) is represented at runtime as a bare array:
 *
 * <ul>
 * <li>{@code (unsigned-byte 8)}: a {@code byte[]} {@code [e_0, ..., e_{n-1}]}, the octets
 * and nothing else, an element read as {@code e & 0xFF} -- one byte an octet, which is
 * what every HTTP body, binary stream and digest buffer is made of, and the very array
 * Java holds, so a {@code byte[]} Java answers or keeps IS the vector
 * ({@code .kb/java-interop.md});</li>
 * <li>{@code (unsigned-byte 16|32)}: a {@code long[]} {@code [width, e_0, ...]} -- slot 0
 * holds the element width in bits and the elements (pre-masked, non-negative) start at
 * slot 1, so the length is {@code arr.length - 1}.</li>
 * </ul>
 *
 * A {@code long[]} is disjoint from every other runtime shape ({@code int[]} is the
 * character box, {@code double[]}/{@code float[]}/{@code short[]} the packed float
 * arrays, {@code Object[]} cons/function/ratio, {@code ArrayList} general arrays), so
 * {@code instanceof long[]} is a free discriminator, and so is {@code instanceof byte[]}
 * ({@link Octets}): a quantized matrix holds its bytes in a holder of its own
 * ({@code .kb/quantized-matrix.md}).
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

	/**
	 * The width of the {@code (unsigned-byte 8)} vector, the {@code byte[]}: what its
	 * element type names, and the width {@code _ivMake} is asked for. Unlike a
	 * {@code long[]} vector's, it is in no slot.
	 */
	static final int OCTET_WIDTH = 8;

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
	static List<ArrayMethod> build(ConstantPool cp, ClassEntry objectClass, ClassEntry objectArrayClass,
			ClassEntry selfClass, boolean usesFloatArray) {
		ClassEntry longArrayClass = cp.classEntry("[J");
		Octets octets = new Octets(cp.classEntry("[B"));
		ClassEntry arrayListClass = cp.classEntry("java/util/ArrayList");
		ClassEntry longClass = cp.classEntry("java/lang/Long");
		ClassEntry bigIntegerClass = cp.classEntry("java/math/BigInteger");
		ClassEntry numberClass = cp.classEntry("java/lang/Number");
		ClassEntry rtExClass = cp.classEntry("java/lang/RuntimeException");
		MethodRefEntry rtExInit = cp.methodRef(rtExClass, "<init>", "(Ljava/lang/String;)V");
		MethodRefEntry alInit = cp.methodRef(arrayListClass, "<init>", "()V");
		MethodRefEntry alAdd = cp.methodRef(arrayListClass, "add", "(Ljava/lang/Object;)Z");
		MethodRefEntry longIntValue = cp.methodRef(longClass, "intValue", "()I");
		MethodRefEntry longValueOf = cp.methodRef(longClass, "valueOf", "(J)Ljava/lang/Long;");
		MethodRefEntry numberLongValue = cp.methodRef(numberClass, "longValue", "()J");
		// The next tier of the dispatch chain: the _fv* float helpers when they are
		// emitted, else the general _array*/_length helpers directly.
		MethodRefEntry aref1Delegate = self(cp, selfClass,
				usesFloatArray ? JvmFloatArrayRuntimeBuilder.AREF1 : JvmArrayRuntimeBuilder.AREF1,
				JvmArrayRuntimeBuilder.AREF1_DESC);
		MethodRefEntry aset1Delegate = self(cp, selfClass,
				usesFloatArray ? JvmFloatArrayRuntimeBuilder.ASET1 : JvmArrayRuntimeBuilder.ASET1,
				JvmArrayRuntimeBuilder.ASET1_DESC);
		MethodRefEntry dimsDelegate = self(cp, selfClass,
				usesFloatArray ? JvmFloatArrayRuntimeBuilder.DIMS : JvmArrayRuntimeBuilder.DIMS,
				JvmArrayRuntimeBuilder.DIMS_DESC);
		MethodRefEntry checkRankDelegate = self(cp, selfClass,
				usesFloatArray ? JvmFloatArrayRuntimeBuilder.CHECK_RANK : JvmArrayRuntimeBuilder.CHECK_RANK,
				JvmArrayRuntimeBuilder.CHECK_RANK_DESC);
		MethodRefEntry lengthDelegate = self(cp, selfClass,
				usesFloatArray ? JvmFloatArrayRuntimeBuilder.LENGTH : JvmLengthRuntimeBuilder.METHOD,
				JvmLengthRuntimeBuilder.DESC);
		MethodRefEntry elementTypeDelegate = usesFloatArray ? self(cp, selfClass,
				JvmFloatArrayRuntimeBuilder.ELEMENT_TYPE, JvmFloatArrayRuntimeBuilder.ELEMENT_TYPE_DESC) : null;
		MethodRefEntry arrayMakeTyped = self(cp, selfClass, JvmArrayRuntimeBuilder.MAKE_TYPED,
				JvmArrayRuntimeBuilder.MAKE_TYPED_DESC);

		// An out-of-range subscript is the access's type-error (JvmOperandTypeRuntime),
		// named by its operator's wrapper.
		MethodRefEntry ckBound = self(cp, selfClass, JvmOperandTypeRuntime.CK_BOUND,
				JvmOperandTypeRuntime.CK_BOUND_DESC);

		List<ArrayMethod> methods = new ArrayList<>();
		methods.add(buildAref1(cp, octets, longArrayClass, longValueOf, ckBound, aref1Delegate));
		methods.add(buildAset1(cp, octets, longArrayClass, ckBound, longClass, bigIntegerClass, numberClass,
				longValueOf, numberLongValue, rtExClass, rtExInit, aset1Delegate));
		methods.add(buildDims(cp, octets, longArrayClass, objectClass, longValueOf, dimsDelegate));
		methods.add(buildCheckRank(cp, octets, longArrayClass, longClass, longIntValue,
				self(cp, selfClass, JvmArrayRuntimeBuilder.RANK_ERR, JvmArrayRuntimeBuilder.RANK_ERR_DESC),
				checkRankDelegate));
		methods.add(buildLength(cp, octets, longArrayClass, longValueOf, lengthDelegate));
		methods
			.add(buildToGeneral(cp, octets, longArrayClass, arrayListClass, objectClass, alInit, alAdd, longValueOf));
		methods.add(buildElementType(cp, octets, longArrayClass, objectClass, longValueOf, elementTypeDelegate));
		methods.add(buildMake(cp, octets, longArrayClass, objectArrayClass, longClass, bigIntegerClass, numberClass,
				longValueOf, numberLongValue, rtExClass, rtExInit, arrayMakeTyped,
				cp.methodRef(selfClass, JvmArrayRuntimeBuilder.DIMS_TOTAL, JvmArrayRuntimeBuilder.DIMS_TOTAL_DESC)));
		methods.add(buildRequireGeneral(cp, octets, longArrayClass, rtExClass, rtExInit));
		return methods;
	}

	/**
	 * Emits the octet vector test over the value on top of the stack, leaving it there:
	 * falls through when it is an {@code (unsigned-byte 8)} vector, and jumps to
	 * {@code notOctets} (where the value is still on the stack) otherwise. The inline
	 * twin of {@link Octets#emitTest}, for the site-emitted predicates.
	 * @param ctx the compilation context
	 * @param notOctets where any other value jumps
	 */
	static void emitOctetTestOnStack(JvmLispCompiler.Ctx ctx, MethodCode.Label notOctets) {
		ctx.body.dup().instanceOf(ctx.cp.classEntry("[B")).ifeq(notOctets);
	}

	/**
	 * The {@code (unsigned-byte 8)} representation's discriminator: a {@code byte[]}.
	 *
	 * @param byteArrayClass the {@code [B} class constant
	 */
	record Octets(ClassEntry byteArrayClass) {

		/**
		 * Branches to {@code notOctets} unless local {@code slot} holds an octet vector.
		 * @param a the method being built
		 * @param slot the local holding the value
		 * @param notOctets the label taken for any other value
		 */
		void emitTest(MethodCode a, int slot, MethodCode.Label notOctets) {
			a.aload(slot);
			a.instanceOf(this.byteArrayClass);
			a.ifeq(notOctets);
		}

	}

	private static MethodRefEntry self(ConstantPool cp, ClassEntry selfClass, String name, String desc) {
		return cp.methodRef(selfClass, name, desc);
	}

	// new RuntimeException(message); athrow.
	private static void emitThrow(MethodCode a, ClassEntry rtExClass, MethodRefEntry rtExInit, StringEntry message) {
		a.new_(rtExClass);
		a.dup();
		a.ldc(message);
		a.invokespecial(rtExInit);
		a.athrow();
	}

	// Coerces the integer value in objSlot to a raw long in vSlot (Long or BigInteger:
	// Number.longValue() keeps the low 64 bits; the caller's width mask keeps fewer);
	// anything else throws the "stores integers" type error.
	private static void emitCoerceInt(MethodCode a, int objSlot, int vSlot, ClassEntry longClass,
			ClassEntry bigIntegerClass, ClassEntry numberClass, MethodRefEntry numberLongValue, ClassEntry rtExClass,
			MethodRefEntry rtExInit, StringEntry message) {
		MethodCode.Label coerceOk = a.newLabel();
		MethodCode.Label bad = a.newLabel();
		a.aload(objSlot);
		a.instanceOf(longClass);
		a.ifne(coerceOk);
		a.aload(objSlot);
		a.instanceOf(bigIntegerClass);
		a.ifne(coerceOk);
		a.labelBinding(bad);
		emitThrow(a, rtExClass, rtExInit, message);
		a.labelBinding(coerceOk);
		a.aload(objSlot);
		a.checkcast(numberClass);
		a.invokevirtual(numberLongValue);
		a.lstore(vSlot);
	}

	// Masks the raw long in vSlot to the width (in bits) in widthSlot:
	// v &= (1L << width) - 1.
	private static void emitMask(MethodCode a, int vSlot, int widthSlot) {
		a.lload(vSlot);
		a.lconst_1();
		a.iload(widthSlot);
		a.lshl();
		a.lconst_1();
		a.lsub();
		a.land();
		a.lstore(vSlot);
	}

	// _ivAref1(arr, i): octets -> Long.valueOf(b[i] & 0xFF); long[] -> Long.valueOf(l[1
	// + i]) (pre-masked unsigned, so the boxed Long is the widened unsigned read); i
	// checked against the length (_ckBound); else delegate. Serves rank-1 aref and
	// row-major-aref. Locals: 0=arr, 1=i, 2=l.
	private static ArrayMethod buildAref1(ConstantPool cp, Octets octets, ClassEntry longArrayClass,
			MethodRefEntry longValueOf, MethodRefEntry ckBound, MethodRefEntry aref1Delegate) {
		int arr = 0, i = 1, l = 2;
		MethodCode a = new MethodCode();
		MethodCode.Label notOctets = a.newLabel();
		octets.emitTest(a, arr, notOctets);
		// Long.valueOf(b[i] & 0xFF)
		a.aload(arr);
		a.checkcast(octets.byteArrayClass());
		a.astore(l);
		a.aload(l);
		emitBoundedIndex(a, l, i, 0, ckBound);
		a.baload();
		a.loadConstant(0xFF);
		a.iand();
		a.i2l();
		a.invokestatic(longValueOf);
		a.areturn();
		a.labelBinding(notOctets);
		MethodCode.Label notPacked = a.newLabel();
		a.aload(arr);
		a.instanceOf(longArrayClass);
		a.ifeq(notPacked);
		a.aload(arr);
		a.checkcast(longArrayClass);
		a.astore(l);
		a.aload(l);
		a.loadConstant(1);
		emitBoundedIndex(a, l, i, 1, ckBound);
		a.iadd();
		a.laload();
		a.invokestatic(longValueOf);
		a.areturn();
		a.labelBinding(notPacked);
		a.aload(arr);
		a.aload(i);
		a.invokestatic(aref1Delegate);
		a.areturn();
		return new ArrayMethod(cp.utf8Entry(AREF1), cp.utf8Entry(JvmArrayRuntimeBuilder.AREF1_DESC), a);
	}

	// Pushes the subscript in local i checked against the vector's length
	// (l.length - header: the octets have none, a long[] vector its width): the index
	// as an int.
	private static void emitBoundedIndex(MethodCode a, int l, int i, int header, MethodRefEntry ckBound) {
		a.aload(i);
		a.aload(l);
		a.arraylength();
		if (header != 0) {
			a.loadConstant(header);
			a.isub();
		}
		a.invokestatic(ckBound);
	}

	// Pushes the length of the packed vector in local slot, known to be one of the two
	// representations: the octets' array length, or a long[]'s past the width header.
	private static void emitPackedLength(MethodCode a, int slot, Octets octets, ClassEntry longArrayClass) {
		MethodCode.Label wide = a.newLabel();
		MethodCode.Label done = a.newLabel();
		a.aload(slot);
		a.instanceOf(octets.byteArrayClass());
		a.ifeq(wide);
		a.aload(slot);
		a.checkcast(octets.byteArrayClass());
		a.arraylength();
		a.goto_(done);
		a.labelBinding(wide);
		a.aload(slot);
		a.checkcast(longArrayClass);
		a.arraylength();
		a.loadConstant(1);
		a.isub();
		a.labelBinding(done);
	}

	// _ivAset1(arr, i, val): octets -> b[i] = (byte) coerce(val); long[] -> l[1 + i] =
	// coerce(val) & widthMask; return the stored value as a Long (the value AS STORED,
	// matching the interpreter); else delegate. The value is checked before the bound, as
	// every store checks them. Serves rank-1 %aset and %row-major-aset. Locals: 0=arr,
	// 1=i, 2=val, 3=l, 4=idx, 5=width, 6..7=v.
	private static ArrayMethod buildAset1(ConstantPool cp, Octets octets, ClassEntry longArrayClass,
			MethodRefEntry ckBound, ClassEntry longClass, ClassEntry bigIntegerClass, ClassEntry numberClass,
			MethodRefEntry longValueOf, MethodRefEntry numberLongValue, ClassEntry rtExClass, MethodRefEntry rtExInit,
			MethodRefEntry aset1Delegate) {
		int arr = 0, i = 1, val = 2, l = 3, idx = 4, width = 5, v = 6;
		MethodCode a = new MethodCode();
		StringEntry storesIntegers = cp.stringEntry("%aset: a packed integer vector stores integers");
		MethodCode.Label notOctets = a.newLabel();
		octets.emitTest(a, arr, notOctets);
		// b[i] = (byte) v; answer v & 0xFF, the value as stored. The narrowing store is
		// the mask.
		a.aload(arr);
		a.checkcast(octets.byteArrayClass());
		a.astore(l);
		emitCoerceInt(a, val, v, longClass, bigIntegerClass, numberClass, numberLongValue, rtExClass, rtExInit,
				storesIntegers);
		emitBoundedIndex(a, l, i, 0, ckBound);
		a.istore(idx);
		a.lload(v);
		a.l2i();
		a.loadConstant(0xFF);
		a.iand();
		a.istore(width);
		a.aload(l);
		a.iload(idx);
		a.iload(width);
		a.bastore();
		a.iload(width);
		a.i2l();
		a.invokestatic(longValueOf);
		a.areturn();
		a.labelBinding(notOctets);
		MethodCode.Label notPacked = a.newLabel();
		a.aload(arr);
		a.instanceOf(longArrayClass);
		a.ifeq(notPacked);
		a.aload(arr);
		a.checkcast(longArrayClass);
		a.astore(l);
		emitCoerceInt(a, val, v, longClass, bigIntegerClass, numberClass, numberLongValue, rtExClass, rtExInit,
				storesIntegers);
		emitBoundedIndex(a, l, i, 1, ckBound);
		a.istore(idx);
		// width = (int) l[0]; v &= (1L << width) - 1
		a.aload(l);
		a.loadConstant(0);
		a.laload();
		a.l2i();
		a.istore(width);
		emitMask(a, v, width);
		a.aload(l);
		a.loadConstant(1);
		a.iload(idx);
		a.iadd();
		a.lload(v);
		a.lastore();
		a.lload(v);
		a.invokestatic(longValueOf);
		a.areturn();
		a.labelBinding(notPacked);
		a.aload(arr);
		a.aload(i);
		a.aload(val);
		a.invokestatic(aset1Delegate);
		a.areturn();
		return new ArrayMethod(cp.utf8Entry(ASET1), cp.utf8Entry(JvmArrayRuntimeBuilder.ASET1_DESC), a);
	}

	// _ivDims(arr): packed -> the fresh cons list (n); else delegate. A cons is an
	// Object[]{car, cdr}, nil is null. Locals: 0=arr.
	private static ArrayMethod buildDims(ConstantPool cp, Octets octets, ClassEntry longArrayClass,
			ClassEntry objectClass, MethodRefEntry longValueOf, MethodRefEntry dimsDelegate) {
		MethodCode a = new MethodCode();
		MethodCode.Label notPacked = a.newLabel();
		MethodCode.Label packed = a.newLabel();
		MethodCode.Label notOctets = a.newLabel();
		octets.emitTest(a, 0, notOctets);
		a.goto_(packed);
		a.labelBinding(notOctets);
		a.aload(0);
		a.instanceOf(longArrayClass);
		a.ifeq(notPacked);
		// Either representation: the length is the array's past the width header.
		a.labelBinding(packed);
		a.loadConstant(2);
		a.anewarray(objectClass);
		a.dup();
		a.loadConstant(0);
		emitPackedLength(a, 0, octets, longArrayClass);
		a.i2l();
		a.invokestatic(longValueOf);
		a.aastore();
		a.areturn();
		a.labelBinding(notPacked);
		a.aload(0);
		a.invokestatic(dimsDelegate);
		a.areturn();
		return new ArrayMethod(cp.utf8Entry(DIMS), cp.utf8Entry(JvmArrayRuntimeBuilder.DIMS_DESC), a);
	}

	// _ivCheckRank(arr, given): packed -> rank is always 1 (a packed integer vector is
	// always rank 1, no header field to read); else delegate down the chain. Locals:
	// 0=arr, 1=given, 2=rank, 3=giv.
	private static ArrayMethod buildCheckRank(ConstantPool cp, Octets octets, ClassEntry longArrayClass,
			ClassEntry longClass, MethodRefEntry longIntValue, MethodRefEntry rankErr,
			MethodRefEntry checkRankDelegate) {
		int arr = 0, given = 1, rank = 2, giv = 3;
		MethodCode a = new MethodCode();
		MethodCode.Label notPacked = a.newLabel();
		MethodCode.Label packed = a.newLabel();
		MethodCode.Label notOctets = a.newLabel();
		octets.emitTest(a, arr, notOctets);
		a.goto_(packed);
		a.labelBinding(notOctets);
		a.aload(arr);
		a.instanceOf(longArrayClass);
		a.ifeq(notPacked);
		a.labelBinding(packed);
		a.loadConstant(1);
		a.istore(rank);
		JvmArrayRuntimeBuilder.emitRankCheckAndReturn(a, longClass, longIntValue, rankErr, arr, given, rank, giv);
		a.labelBinding(notPacked);
		a.aload(arr);
		a.aload(given);
		a.invokestatic(checkRankDelegate);
		a.areturn();
		return new ArrayMethod(cp.utf8Entry(CHECK_RANK), cp.utf8Entry(CHECK_RANK_DESC), a);
	}

	// _ivLength(arr): packed -> Long.valueOf(arr.length - 1); else delegate. Locals:
	// 0=arr.
	private static ArrayMethod buildLength(ConstantPool cp, Octets octets, ClassEntry longArrayClass,
			MethodRefEntry longValueOf, MethodRefEntry lengthDelegate) {
		MethodCode a = new MethodCode();
		MethodCode.Label notPacked = a.newLabel();
		MethodCode.Label packed = a.newLabel();
		MethodCode.Label notOctets = a.newLabel();
		octets.emitTest(a, 0, notOctets);
		a.goto_(packed);
		a.labelBinding(notOctets);
		a.aload(0);
		a.instanceOf(longArrayClass);
		a.ifeq(notPacked);
		a.labelBinding(packed);
		emitPackedLength(a, 0, octets, longArrayClass);
		a.i2l();
		a.invokestatic(longValueOf);
		a.areturn();
		a.labelBinding(notPacked);
		a.aload(0);
		a.invokestatic(lengthDelegate);
		a.areturn();
		return new ArrayMethod(cp.utf8Entry(LENGTH), cp.utf8Entry(JvmLengthRuntimeBuilder.DESC), a);
	}

	// _ivToGeneral(o): converts a packed vector into the equivalent general array (an
	// ArrayList whose slot 0 is the {dims, null, null} header and slots 1.. are boxed
	// Longs), so the general _arrayToString renderer prints it as a plain #(...) vector
	// (CL prints specialized vectors that way). Only ever called with a packed vector
	// (the print dispatch tests the representation first). Locals: 0=o, 1=unused, 2=n,
	// 3=list, 4=f.
	private static ArrayMethod buildToGeneral(ConstantPool cp, Octets octets, ClassEntry longArrayClass,
			ClassEntry arrayListClass, ClassEntry objectClass, MethodRefEntry alInit, MethodRefEntry alAdd,
			MethodRefEntry longValueOf) {
		int o = 0, n = 2, list = 3, f = 4;
		MethodCode a = new MethodCode();
		emitPackedLength(a, o, octets, longArrayClass);
		a.istore(n);
		a.new_(arrayListClass);
		a.dup();
		a.invokespecial(alInit);
		a.astore(list);
		// list.add(new Object[]{new Object[]{Long.valueOf(n)}, null, null})
		a.aload(list);
		a.loadConstant(3);
		a.anewarray(objectClass);
		a.dup();
		a.loadConstant(0);
		a.loadConstant(1);
		a.anewarray(objectClass);
		a.dup();
		a.loadConstant(0);
		a.iload(n);
		a.i2l();
		a.invokestatic(longValueOf);
		a.aastore();
		a.aastore();
		a.invokevirtual(alAdd);
		a.pop();
		MethodCode.Label wide = a.newLabel();
		MethodCode.Label done = a.newLabel();
		a.aload(o);
		a.instanceOf(octets.byteArrayClass());
		a.ifeq(wide);
		emitBoxEach(a, o, n, list, f, alAdd, longValueOf, done, () -> {
			a.checkcast(octets.byteArrayClass());
			a.iload(f);
			a.baload();
			a.loadConstant(0xFF);
			a.iand();
			a.i2l();
		});
		a.labelBinding(wide);
		emitBoxEach(a, o, n, list, f, alAdd, longValueOf, done, () -> {
			a.checkcast(longArrayClass);
			a.loadConstant(1);
			a.iload(f);
			a.iadd();
			a.laload();
		});
		a.labelBinding(done);
		a.aload(list);
		a.areturn();
		return new ArrayMethod(cp.utf8Entry(TO_GENERAL), cp.utf8Entry(TO_GENERAL_DESC), a);
	}

	// for (f = 0; f < n; f++) list.add(Long.valueOf(<element f>)); goto done -- the
	// element pushed as a long by readElement, which finds the vector on the stack.
	private static void emitBoxEach(MethodCode a, int o, int n, int list, int f, MethodRefEntry alAdd,
			MethodRefEntry longValueOf, MethodCode.Label done, Runnable readElement) {
		a.loadConstant(0);
		a.istore(f);
		MethodCode.Label loop = a.newLabel();
		a.labelBinding(loop);
		a.iload(f);
		a.iload(n);
		a.if_icmpge(done);
		a.aload(list);
		a.aload(o);
		readElement.run();
		a.invokestatic(longValueOf);
		a.invokevirtual(alAdd);
		a.pop();
		a.iinc(f, 1);
		a.goto_(loop);
	}

	// _ivElementType(arr): packed -> the fresh cons list (UNSIGNED-BYTE width) (the REAL
	// specifier, matching the interpreter); else delegate to _fvElementType (when the
	// float helpers are emitted) or answer the symbol t directly (general arrays are
	// element-type t). Locals: 0=arr.
	private static ArrayMethod buildElementType(ConstantPool cp, Octets octets, ClassEntry longArrayClass,
			ClassEntry objectClass, MethodRefEntry longValueOf,
			@org.jspecify.annotations.Nullable MethodRefEntry elementTypeDelegate) {
		MethodCode a = new MethodCode();
		MethodCode.Label notPacked = a.newLabel();
		MethodCode.Label notOctets = a.newLabel();
		octets.emitTest(a, 0, notOctets);
		emitUnsignedByteSpec(cp, a, objectClass, longValueOf, () -> {
			a.loadConstant(OCTET_WIDTH);
			a.i2l();
		});
		a.areturn();
		a.labelBinding(notOctets);
		a.aload(0);
		a.instanceOf(longArrayClass);
		a.ifeq(notPacked);
		emitUnsignedByteSpec(cp, a, objectClass, longValueOf, () -> {
			a.aload(0);
			a.checkcast(longArrayClass);
			a.loadConstant(0);
			a.laload();
		});
		a.areturn();
		a.labelBinding(notPacked);
		if (elementTypeDelegate != null) {
			a.aload(0);
			a.invokestatic(elementTypeDelegate);
			a.areturn();
		}
		else {
			a.ldc(cp.stringEntry("T"));
			a.areturn();
		}
		return new ArrayMethod(cp.utf8Entry(ELEMENT_TYPE), cp.utf8Entry(ELEMENT_TYPE_DESC), a);
	}

	// Pushes new Object[]{"UNSIGNED-BYTE", new Object[]{Long.valueOf(width), null}}, the
	// width pushed as a long by pushWidth.
	private static void emitUnsignedByteSpec(ConstantPool cp, MethodCode a, ClassEntry objectClass,
			MethodRefEntry longValueOf, Runnable pushWidth) {
		a.loadConstant(2);
		a.anewarray(objectClass);
		a.dup();
		a.loadConstant(0);
		a.ldc(cp.stringEntry(am.ik.rontolisp.LispNames.UNSIGNED_BYTE));
		a.aastore();
		a.dup();
		a.loadConstant(1);
		a.loadConstant(2);
		a.anewarray(objectClass);
		a.dup();
		a.loadConstant(0);
		pushWidth.run();
		a.invokestatic(longValueOf);
		a.aastore();
		a.aastore();
	}

	// _ivMake(dims, init, width): build a packed vector of the compile-time literal width
	// when dims designates rank 1 (a Long, or a one-element cons list of Longs) -- the
	// bare byte[] at width 8, a long[] otherwise -- filled with the masked integer init
	// (default 0; a non-integer init is a type error). Any other dims shape (rank n)
	// keeps the general boxed representation via _arrayMake, mirroring the
	// interpreter's runtime rank check. The rank-1 length is _arrayDimsTotal's, which
	// checks the dimension first. Locals: 0=dims, 1=init, 2=width, 3=n, 4=arr, 5=i,
	// 6..7=fill.
	private static ArrayMethod buildMake(ConstantPool cp, Octets octets, ClassEntry longArrayClass,
			ClassEntry objectArrayClass, ClassEntry longClass, ClassEntry bigIntegerClass, ClassEntry numberClass,
			MethodRefEntry longValueOf, MethodRefEntry numberLongValue, ClassEntry rtExClass, MethodRefEntry rtExInit,
			MethodRefEntry arrayMakeTyped, MethodRefEntry dimsTotal) {
		int dims = 0, init = 1, width = 2, n = 3, arr = 4, i = 5, fill = 6;
		MethodCode a = new MethodCode();
		MethodCode.Label tryList = a.newLabel();
		MethodCode.Label general = a.newLabel();
		MethodCode.Label haveN = a.newLabel();
		a.aload(dims);
		a.instanceOf(longClass);
		a.ifeq(tryList);
		// rank-1 shorthand: n = _arrayDimsTotal(dims)
		a.aload(dims);
		a.invokestatic(dimsTotal);
		a.istore(n);
		a.goto_(haveN);
		// a one-element cons list of dims is rank 1 too: (n) with cdr nil
		a.labelBinding(tryList);
		a.aload(dims);
		a.instanceOf(objectArrayClass);
		a.ifeq(general);
		a.aload(dims);
		a.checkcast(objectArrayClass);
		a.loadConstant(1);
		a.aaload();
		a.ifnonnull(general);
		a.aload(dims);
		a.invokestatic(dimsTotal);
		a.istore(n);
		a.goto_(haveN);
		// rank n: the general representation (no fill pointer / adjustability at this
		// call site by construction), REMEMBERING the (unsigned-byte width) it was asked
		// for and defaulting an unsupplied element to 0 rather than nil. The 0 also
		// keeps the allocation on _arrayMake's packed long[] path, so the type is
		// remembered without giving up the packing.
		a.labelBinding(general);
		MethodCode.Label initGiven = a.newLabel();
		MethodCode.Label initDone = a.newLabel();
		a.aload(dims);
		a.aload(init);
		a.ifnonnull(initGiven);
		a.lconst_0();
		a.invokestatic(longValueOf);
		a.goto_(initDone);
		a.labelBinding(initGiven);
		a.aload(init);
		a.labelBinding(initDone);
		a.aconst_null();
		a.aconst_null();
		emitWidthToElementTypeCode(a, width);
		a.invokestatic(arrayMakeTyped);
		a.areturn();
		a.labelBinding(haveN);
		StringEntry storesIntegers = cp.stringEntry("make-array: a packed integer vector stores integers");
		MethodCode.Label wide = a.newLabel();
		a.iload(width);
		a.loadConstant(OCTET_WIDTH);
		a.if_icmpne(wide);
		// width 8: b = new byte[n]; the init narrowed into every element (a zero init is
		// the array's own zero fill)
		a.iload(n);
		a.newarray(TypeKind.BYTE);
		a.astore(arr);
		MethodCode.Label octetsDone = a.newLabel();
		a.aload(init);
		a.ifnull(octetsDone);
		emitCoerceInt(a, init, fill, longClass, bigIntegerClass, numberClass, numberLongValue, rtExClass, rtExInit,
				storesIntegers);
		a.loadConstant(0);
		a.istore(i);
		MethodCode.Label octetsLoop = a.newLabel();
		a.labelBinding(octetsLoop);
		a.iload(i);
		a.iload(n);
		a.if_icmpge(octetsDone);
		a.aload(arr);
		a.iload(i);
		a.lload(fill);
		a.l2i();
		a.bastore();
		a.iinc(i, 1);
		a.goto_(octetsLoop);
		a.labelBinding(octetsDone);
		a.aload(arr);
		a.areturn();
		a.labelBinding(wide);
		a.iload(n);
		a.loadConstant(1);
		a.iadd();
		a.newarray(TypeKind.LONG);
		a.astore(arr);
		a.aload(arr);
		a.loadConstant(0);
		a.iload(width);
		a.i2l();
		a.lastore();
		MethodCode.Label done = a.newLabel();
		a.aload(init);
		a.ifnull(done);
		emitCoerceInt(a, init, fill, longClass, bigIntegerClass, numberClass, numberLongValue, rtExClass, rtExInit,
				storesIntegers);
		emitMask(a, fill, width);
		a.loadConstant(0);
		a.istore(i);
		MethodCode.Label loop = a.newLabel();
		a.labelBinding(loop);
		a.iload(i);
		a.iload(n);
		a.if_icmpge(done);
		a.aload(arr);
		a.loadConstant(1);
		a.iload(i);
		a.iadd();
		a.lload(fill);
		a.lastore();
		a.iinc(i, 1);
		a.goto_(loop);
		a.labelBinding(done);
		a.aload(arr);
		a.areturn();
		return new ArrayMethod(cp.utf8Entry(MAKE), cp.utf8Entry(MAKE_DESC), a);
	}

	// _ivRequireGeneral(o): the fill-pointer-surface guard -- a packed integer vector
	// has no fill pointer, adjustability or displacement, so those operations reject it
	// with a clear error (mirroring the interpreter's requireGeneralArray); any other
	// value passes through unchanged. Locals: 0=o.
	private static ArrayMethod buildRequireGeneral(ConstantPool cp, Octets octets, ClassEntry longArrayClass,
			ClassEntry rtExClass, MethodRefEntry rtExInit) {
		MethodCode a = new MethodCode();
		StringEntry notApplicable = cp.stringEntry("not applicable to a packed integer vector");
		MethodCode.Label notOctets = a.newLabel();
		octets.emitTest(a, 0, notOctets);
		emitThrow(a, rtExClass, rtExInit, notApplicable);
		a.labelBinding(notOctets);
		MethodCode.Label ok = a.newLabel();
		a.aload(0);
		a.instanceOf(longArrayClass);
		a.ifeq(ok);
		emitThrow(a, rtExClass, rtExInit, notApplicable);
		a.labelBinding(ok);
		a.aload(0);
		a.areturn();
		return new ArrayMethod(cp.utf8Entry(REQUIRE_GENERAL), cp.utf8Entry(REQUIRE_GENERAL_DESC), a);
	}

	// The ArrayElementTypes code for the packed width held in widthSlot: the widths are
	// 8/16/32 by construction, so two compares decide it.
	private static void emitWidthToElementTypeCode(MethodCode a, int widthSlot) {
		MethodCode.Label is8 = a.newLabel();
		MethodCode.Label is16 = a.newLabel();
		MethodCode.Label done = a.newLabel();
		a.iload(widthSlot);
		a.loadConstant(8);
		a.if_icmpeq(is8);
		a.iload(widthSlot);
		a.loadConstant(16);
		a.if_icmpeq(is16);
		a.loadConstant(am.ik.rontolisp.ArrayElementTypes.UNSIGNED_BYTE_32);
		a.goto_(done);
		a.labelBinding(is8);
		a.loadConstant(am.ik.rontolisp.ArrayElementTypes.UNSIGNED_BYTE_8);
		a.goto_(done);
		a.labelBinding(is16);
		a.loadConstant(am.ik.rontolisp.ArrayElementTypes.UNSIGNED_BYTE_16);
		a.labelBinding(done);
	}

}
