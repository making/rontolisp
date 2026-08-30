package am.ik.rontolisp.codegen.jvm;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import am.ik.jvm.ConstantPool;
import am.ik.jvm.ConstantPool.ClassConstant;
import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.ConstantPool.Utf8Constant;
import am.ik.jvm.Opcode;
import am.ik.rontolisp.RenderCycleGuard;

/**
 * Builds the JVM bytecode for the array runtime helpers. An array is represented at
 * runtime as a {@code java.util.ArrayList}: slot 0 holds a header {@code Object[]{dims,
 * fillPointer, adjustable}} -- {@code dims} is an {@code Object[]} of boxed {@code Long}
 * dimension sizes (length = rank), {@code fillPointer} is a {@code Long} or {@code null}
 * when the array has none, and {@code adjustable} is the raw {@code :adjustable} argument
 * ({@code null} = nil) -- and slots {@code 1..} hold the row-major data. Any rank
 * {@code >= 1} is supported: the flat index is the Horner fold over the subscripts, so a
 * rank-2 element {@code (i, j)} lives at list index {@code 1 + i * cols + j} and a rank-1
 * element {@code (i)} at {@code 1 + i}.
 *
 * <p>
 * A PLAIN general array (no fill pointer, not adjustable, not displaced, not a character
 * vector) whose {@code :initial-element} is nil or an integer starts PACKED: the
 * ArrayList holds ONLY a length-6 header {@code Object[]{dims, null, null, null, null,
 * long[] data}} and the row-major elements live unboxed in the {@code long[]}, with
 * {@code Long.MIN_VALUE} as the nil sentinel. A random {@code aref} is then one probe
 * into one flat primitive array instead of a dependent pointer chase through boxed
 * {@code Long}s -- the representation SBCL's simple-vector of immediate fixnums has. The
 * first store that cannot be packed (a non-integer, or the sentinel value itself) widens
 * the array IN PLACE to the boxed shape above ({@code _arrayWiden}); the ArrayList is the
 * identity, so every alias sees the widened array. The length-6 header never reads as a
 * displacement ({@code header[3]} is null) nor as a character vector (length != 4).
 *
 * <p>
 * A MUTABLE CHARACTER VECTOR ({@code make-array :element-type 'character} with
 * {@code :fill-pointer}/{@code :adjustable}) is the same representation holding
 * {@code java.lang.Character} elements, marked by a LENGTH-4 header {@code Object[]{dims,
 * fillPointer, adjustable, null}} ({@code _charVecMake}). The {@code _strv} normalizer
 * renders it into the quote-framed runtime string on demand so the string consumers
 * ({@code stringp}, {@code char}, {@code string=}, {@code subseq}, printing,
 * {@code _eqv}) treat it as a string.
 *
 * <p>
 * A displaced array ({@code make-array :displaced-to}) instead carries a 5-element header
 * {@code Object[]{dims, null, null, target, offset}} and holds NO data slots: every data
 * access goes through {@code _rmGet}/{@code _rmSet}, which follow the target chain adding
 * each hop's offset to the 1-based list index (so writes alias the target's storage) --
 * displacement is header length 5 exactly, so the character-vector marker never reads as
 * a displacement. A displaced array never has a fill pointer and is never adjustable
 * (lite semantics, enforced at compile time).
 *
 * <p>
 * The generated static helpers (gated on the program actually using arrays):
 * <ul>
 * <li>{@code _arrayMake(dims, init, fillPointer, adjustable)} -&gt; a fresh
 * ArrayList</li>
 * <li>{@code _aref1(arr, i)} / {@code _aref2(arr, i, j)} / {@code _arefN(arr, subs)}
 * -&gt; the stored element</li>
 * <li>{@code _aset1(arr, i, val)} / {@code _aset2(arr, i, j, val)} /
 * {@code _asetN(arr, subs, val)} -&gt; the value</li>
 * <li>{@code _fillPointer} / {@code _setFillPointer} / {@code _arrayHasFillPointer} /
 * {@code _adjustableArrayP} / {@code _vectorPush} / {@code _vectorPop} /
 * {@code _vectorPushExtend} -&gt; the fill-pointer surface (the fill pointer, when
 * present, is the effective length for {@code length} and printing; {@code aref} still
 * reaches the full backing store)</li>
 * </ul>
 * The {@code N} variants take the subscripts packaged into an {@code Object[]} and are
 * used by rank-3+ call sites; ranks 1 and 2 keep their dedicated fast helpers.
 */
final class JvmArrayRuntimeBuilder {

	static final String MAKE = "_arrayMake";

	static final String MAKE_DESC = "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";

	static final String AREF1 = "_aref1";

	static final String AREF1_DESC = "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";

	static final String AREF2 = "_aref2";

	static final String AREF2_DESC = "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";

	static final String AREFN = "_arefN";

	static final String AREFN_DESC = "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";

	static final String ASET1 = "_aset1";

	static final String ASET1_DESC = "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";

	static final String ASET2 = "_aset2";

	static final String ASET2_DESC = "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";

	static final String ASETN = "_asetN";

	static final String ASETN_DESC = "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";

	static final String DIMS = "_arrayDims";

	static final String DIMS_DESC = "(Ljava/lang/Object;)Ljava/lang/Object;";

	static final String TO_STRING = "_arrayToString";

	static final String TO_DISPLAY_STRING = "_arrayToDisplayString";

	static final String TO_STRING_DESC = "(Ljava/lang/Object;)Ljava/lang/String;";

	static final String FILL_POINTER = "_fillPointer";

	static final String FILL_POINTER_DESC = "(Ljava/lang/Object;)Ljava/lang/Object;";

	static final String SET_FILL_POINTER = "_setFillPointer";

	static final String SET_FILL_POINTER_DESC = "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";

	static final String HAS_FILL_POINTER = "_arrayHasFillPointer";

	static final String HAS_FILL_POINTER_DESC = "(Ljava/lang/Object;)Ljava/lang/Object;";

	static final String ADJUSTABLE_ARRAY_P = "_adjustableArrayP";

	static final String ADJUSTABLE_ARRAY_P_DESC = "(Ljava/lang/Object;)Ljava/lang/Object;";

	static final String VECTOR_PUSH = "_vectorPush";

	static final String VECTOR_PUSH_DESC = "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";

	static final String VECTOR_POP = "_vectorPop";

	static final String VECTOR_POP_DESC = "(Ljava/lang/Object;)Ljava/lang/Object;";

	static final String VECTOR_PUSH_EXTEND = "_vectorPushExtend";

	static final String VECTOR_PUSH_EXTEND_DESC = "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";

	static final String MAKE_DISPLACED = "_arrayMakeDisplaced";

	static final String MAKE_DISPLACED_DESC = "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";

	static final String RM_GET = "_rmGet";

	static final String RM_GET_DESC = "(Ljava/lang/Object;I)Ljava/lang/Object;";

	static final String RM_SET = "_rmSet";

	static final String RM_SET_DESC = "(Ljava/lang/Object;ILjava/lang/Object;)Ljava/lang/Object;";

	static final String ARRAY_BECOME = "_arrayBecome";

	static final String ARRAY_BECOME_DESC = "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";

	static final String DISP_TARGET = "_arrayDispTarget";

	static final String DISP_TARGET_DESC = "(Ljava/lang/Object;)Ljava/lang/Object;";

	static final String DISP_OFFSET = "_arrayDispOffset";

	static final String DISP_OFFSET_DESC = "(Ljava/lang/Object;)Ljava/lang/Object;";

	static final String CHAR_VEC_MAKE = "_charVecMake";

	static final String WIDEN = "_arrayWiden";

	static final String WIDEN_DESC = "(Ljava/lang/Object;)V";

	/**
	 * The packed general array's nil sentinel: the one {@code long} value a packed
	 * element cannot hold (storing the integer itself widens the array), so a
	 * {@code long[]} slot can represent "nil" without a box.
	 */
	static final long NIL_SENTINEL = Long.MIN_VALUE;

	static final String STRV = "_strv";

	static final String STRV_DESC = "(Ljava/lang/Object;)Ljava/lang/Object;";

	/**
	 * {@code _strToCharVec(String) -> Object}: the immutable runtime string copied into a
	 * fresh mutable character vector. A string view over an immutable string PROMOTES its
	 * target through this on the first write, so the view is mutable from then on (the
	 * immutable string value itself cannot be written -- exactly what
	 * {@code (setf (char s i) c)} on that same string already cannot do).
	 */
	static final String STR_TO_CHAR_VEC = "_strToCharVec";

	static final String STR_TO_CHAR_VEC_DESC = "(Ljava/lang/String;)Ljava/lang/Object;";

	/**
	 * Every method name {@link #build} and {@link #buildToStringMethods} emit, i.e.
	 * exactly the group the array gate switches on and off. {@code JvmLispCompiler}
	 * matches an unresolved own-class call against this set to tell "the gate
	 * under-predicted" from "some other internal inconsistency", so a name missing here
	 * would make the under-prediction unrecoverable; {@code JvmArrayRuntimeBuilderTest}
	 * pins the set against what the two builders actually produce.
	 */
	static final Set<String> METHOD_NAMES = Set.of(MAKE, AREF1, AREF2, AREFN, ASET1, ASET2, ASETN, DIMS, TO_STRING,
			TO_DISPLAY_STRING, FILL_POINTER, SET_FILL_POINTER, HAS_FILL_POINTER, ADJUSTABLE_ARRAY_P, VECTOR_PUSH,
			VECTOR_POP, VECTOR_PUSH_EXTEND, MAKE_DISPLACED, RM_GET, RM_SET, ARRAY_BECOME, DISP_TARGET, DISP_OFFSET,
			CHAR_VEC_MAKE, STRV, STR_TO_CHAR_VEC, WIDEN);

	/** An array helper method body ready to be emitted into the generated class. */
	record ArrayMethod(Utf8Constant name, Utf8Constant desc, int maxStack, int maxLocals, List<Integer> code) {
	}

	private JvmArrayRuntimeBuilder() {
	}

	static List<ArrayMethod> build(ConstantPool cp, ClassConstant objectClass, ClassConstant objectArrayClass,
			ClassConstant selfClass) {
		ClassConstant arrayListClass = cp.addClass(cp.addUtf8("java/util/ArrayList"));
		ClassConstant longClass = cp.addClass(cp.addUtf8("java/lang/Long"));
		MethodrefConstant alInit = cp.addMethodref(arrayListClass,
				cp.addNameAndType(cp.addUtf8("<init>"), cp.addUtf8("()V")));
		MethodrefConstant alAdd = cp.addMethodref(arrayListClass,
				cp.addNameAndType(cp.addUtf8("add"), cp.addUtf8("(Ljava/lang/Object;)Z")));
		MethodrefConstant alGet = cp.addMethodref(arrayListClass,
				cp.addNameAndType(cp.addUtf8("get"), cp.addUtf8("(I)Ljava/lang/Object;")));
		MethodrefConstant alSet = cp.addMethodref(arrayListClass,
				cp.addNameAndType(cp.addUtf8("set"), cp.addUtf8("(ILjava/lang/Object;)Ljava/lang/Object;")));
		MethodrefConstant longIntValue = cp.addMethodref(longClass,
				cp.addNameAndType(cp.addUtf8("intValue"), cp.addUtf8("()I")));
		MethodrefConstant longValueOf = cp.addMethodref(longClass,
				cp.addNameAndType(cp.addUtf8("valueOf"), cp.addUtf8("(J)Ljava/lang/Long;")));
		MethodrefConstant alSize = cp.addMethodref(arrayListClass,
				cp.addNameAndType(cp.addUtf8("size"), cp.addUtf8("()I")));
		MethodrefConstant alRemove = cp.addMethodref(arrayListClass,
				cp.addNameAndType(cp.addUtf8("remove"), cp.addUtf8("(I)Ljava/lang/Object;")));
		ClassConstant rtExClass = cp.addClass(cp.addUtf8("java/lang/RuntimeException"));
		MethodrefConstant rtExInit = cp.addMethodref(rtExClass,
				cp.addNameAndType(cp.addUtf8("<init>"), cp.addUtf8("(Ljava/lang/String;)V")));
		MethodrefConstant rmGet = cp.addMethodref(selfClass,
				cp.addNameAndType(cp.addUtf8(RM_GET), cp.addUtf8(RM_GET_DESC)));
		MethodrefConstant rmSet = cp.addMethodref(selfClass,
				cp.addNameAndType(cp.addUtf8(RM_SET), cp.addUtf8(RM_SET_DESC)));
		MethodrefConstant widen = cp.addMethodref(selfClass,
				cp.addNameAndType(cp.addUtf8(WIDEN), cp.addUtf8(WIDEN_DESC)));
		ClassConstant longArrayClass = cp.addClass(cp.addUtf8("[J"));
		MethodrefConstant longLongValue = cp.addMethodref(longClass,
				cp.addNameAndType(cp.addUtf8("longValue"), cp.addUtf8("()J")));
		MethodrefConstant arraysFillLong = cp.addMethodref(cp.addClass(cp.addUtf8("java/util/Arrays")),
				cp.addNameAndType(cp.addUtf8("fill"), cp.addUtf8("([JJ)V")));
		am.ik.jvm.ConstantPool.LongConstant nilSentinel = cp.addLong(NIL_SENTINEL);

		List<ArrayMethod> methods = new ArrayList<>();

		// _arrayMake(dims, init, fp, adj):
		// list = new ArrayList(); build the Object[] dimension sizes and the total
		// element count from dims (a Long for the rank-1 shorthand, otherwise a cons
		// list of Longs); wrap them with the fill pointer and the adjustable flag into
		// the 3-element slot-0 header; repeat total times: list.add(init).
		JvmAsm m = new JvmAsm();
		int dims = 0, init = 1, fp = 2, adj = 3, list = 4, total = 5, dimsArr = 6, idx = 7, cur = 8, n = 9, fpVal = 10,
				v = 11;
		m.anew(arrayListClass);
		m.dup();
		m.invokespecial(alInit);
		m.astore(list);
		emitParseDims(m, objectClass, longClass, objectArrayClass, longIntValue, dims, dimsArr, total, cur, n, idx);
		// resolve the fill pointer (null = none; a Long = that value,
		// range-checked; anything else, i.e. t, = the vector size), requiring rank 1.
		m.aconstNull();
		m.astore(fpVal);
		int afterFp = m.label();
		m.aload(fp);
		m.branch(Opcode.IFNULL, afterFp);
		int rankOk = m.label();
		m.aload(dimsArr);
		m.arraylength();
		m.iconst(1);
		m.branch(Opcode.IF_ICMPEQ, rankOk);
		emitThrow(m, rtExClass, rtExInit, cp.addString("make-array: :fill-pointer requires a rank-1 array"));
		m.bind(rankOk);
		int fpIsT = m.label();
		m.aload(fp);
		m.instanceOf(longClass);
		m.branch(Opcode.IFEQ, fpIsT);
		m.aload(fp);
		m.checkcast(longClass);
		m.invokevirtual(longIntValue);
		m.istore(v);
		int fpBad = m.label();
		int fpLongOk = m.label();
		m.iload(v);
		m.branch(Opcode.IFLT, fpBad);
		m.iload(v);
		m.iload(total);
		m.branch(Opcode.IF_ICMPGT, fpBad);
		m.branch(Opcode.GOTO, fpLongOk);
		m.bind(fpBad);
		emitThrow(m, rtExClass, rtExInit, cp.addString("make-array: :fill-pointer out of range"));
		m.bind(fpLongOk);
		m.aload(fp);
		m.astore(fpVal);
		m.branch(Opcode.GOTO, afterFp);
		// :fill-pointer t -> the vector size (dimsArr[0] is already the boxed Long)
		m.bind(fpIsT);
		m.aload(dimsArr);
		m.iconst(0);
		m.aaload();
		m.astore(fpVal);
		m.bind(afterFp);
		// PACKED fast path: no fill pointer, not adjustable, and the initial element is
		// nil or an integer (excluding the sentinel value, which must stay
		// representable): the data is a flat long[] behind a length-6 header
		// {dims, null, null, null, null, data} and the list holds ONLY the header.
		int generalPath = m.label();
		int packedNilFill = m.label();
		int packedGo = m.label();
		int fillVal = 12, data = 14;
		m.aload(fpVal);
		m.branch(Opcode.IFNONNULL, generalPath);
		m.aload(adj);
		m.branch(Opcode.IFNONNULL, generalPath);
		m.aload(init);
		m.branch(Opcode.IFNULL, packedNilFill);
		m.aload(init);
		m.instanceOf(longClass);
		m.branch(Opcode.IFEQ, generalPath);
		m.aload(init);
		m.checkcast(longClass);
		m.invokevirtual(longLongValue);
		m.lstore(fillVal);
		m.lload(fillVal);
		m.ldc2Long(nilSentinel);
		m.op(Opcode.LCMP);
		m.branch(Opcode.IFEQ, generalPath);
		m.branch(Opcode.GOTO, packedGo);
		m.bind(packedNilFill);
		m.ldc2Long(nilSentinel);
		m.lstore(fillVal);
		m.bind(packedGo);
		// data = new long[total]; Arrays.fill(data, fillVal)
		m.iload(total);
		m.newarrayLong();
		m.astore(data);
		m.aload(data);
		m.lload(fillVal);
		m.invokestatic(arraysFillLong);
		// list.add(new Object[]{dimsArr, null, null, null, null, data}); return list
		m.aload(list);
		m.iconst(6);
		m.anewarray(objectClass);
		m.dup();
		m.iconst(0);
		m.aload(dimsArr);
		m.aastore();
		m.dup();
		m.iconst(5);
		m.aload(data);
		m.aastore();
		m.invokevirtual(alAdd);
		m.pop();
		m.aload(list);
		m.areturn();
		// list.add(new Object[]{dimsArr, fpVal, adj}); fill init total times
		m.bind(generalPath);
		m.aload(list);
		m.iconst(3);
		m.anewarray(objectClass);
		m.dup();
		m.iconst(0);
		m.aload(dimsArr);
		m.aastore();
		m.dup();
		m.iconst(1);
		m.aload(fpVal);
		m.aastore();
		m.dup();
		m.iconst(2);
		m.aload(adj);
		m.aastore();
		m.invokevirtual(alAdd);
		m.pop();
		m.iconst(0);
		m.istore(idx);
		int loop = m.label();
		int end = m.label();
		m.bind(loop);
		m.iload(idx);
		m.iload(total);
		m.branch(Opcode.IF_ICMPGE, end);
		m.aload(list);
		m.aload(init);
		m.invokevirtual(alAdd);
		m.pop();
		m.iinc(idx, 1);
		m.branch(Opcode.GOTO, loop);
		m.bind(end);
		m.aload(list);
		m.areturn();
		methods.add(new ArrayMethod(cp.addUtf8(MAKE), cp.addUtf8(MAKE_DESC), 5, 15, m.finish()));

		// _aref1(arr, i): return _rmGet(arr, 1 + ((Long) i).intValue()) -- _rmGet
		// follows the displacement chain, so every accessor goes through it. A string
		// is a rank-1 character array in CL: on a runtime String the string's content
		// lives in [1, length-1) (the surrounding quotes are the framing), and the
		// requested character index is translated BY CODE POINT via _cpoff(s, i)
		// -> s.codePointAt(codeUnit) so a supplementary code point counts as one
		// indexed element -- matching the (length s) contract everywhere else.
		ClassConstant strClass = cp.addClass(cp.addUtf8("java/lang/String"));
		MethodrefConstant strCpOffset = cp.addMethodref(selfClass,
				cp.addNameAndType(cp.addUtf8(JvmStringIndexRuntimeBuilder.OFFSET_METHOD),
						cp.addUtf8(JvmStringIndexRuntimeBuilder.OFFSET_DESC)));
		MethodrefConstant strCodePointAt = cp.addMethodref(strClass,
				cp.addNameAndType(cp.addUtf8("codePointAt"), cp.addUtf8("(I)I")));
		MethodrefConstant strCount = cp.addMethodref(selfClass,
				cp.addNameAndType(cp.addUtf8(JvmStringIndexRuntimeBuilder.COUNT_METHOD),
						cp.addUtf8(JvmStringIndexRuntimeBuilder.COUNT_DESC)));
		JvmAsm a1 = new JvmAsm();
		a1.aload(0);
		a1.instanceOf(strClass);
		int a1NotString = a1.label();
		a1.branch(Opcode.IFEQ, a1NotString);
		// s = (String) arr; codeUnit = _cpoff(s, ((Long)i).intValue());
		// return int[]{s.codePointAt(codeUnit)}.
		a1.aload(0);
		a1.checkcast(strClass);
		a1.astore(3); // slot 3: s
		a1.aload(3);
		a1.aload(1);
		a1.checkcast(longClass);
		a1.invokevirtual(longIntValue);
		a1.invokestatic(strCpOffset);
		a1.istore(4); // slot 4: codeUnit
		a1.aload(3);
		a1.iload(4);
		a1.invokevirtual(strCodePointAt);
		a1.istore(5); // slot 5: cp
		// Box cp as int[1]{cp} -- the runtime CHARACTER representation on the JVM
		// compile path.
		a1.iconst(1);
		a1.newarrayInt();
		a1.op(Opcode.DUP);
		a1.iconst(0);
		a1.iload(5);
		a1.iastore();
		a1.areturn();
		a1.bind(a1NotString);
		a1.aload(0);
		a1.iconst(1);
		a1.aload(1);
		a1.checkcast(longClass);
		a1.invokevirtual(longIntValue);
		a1.op(Opcode.IADD);
		a1.invokestatic(rmGet);
		a1.areturn();
		methods.add(new ArrayMethod(cp.addUtf8(AREF1), cp.addUtf8(AREF1_DESC), 4, 6, a1.finish()));

		// _aref2(arr, i, j): cols = dims[1]; return _rmGet(arr, 1 + i * cols + j)
		JvmAsm a2 = new JvmAsm();
		emitFlat2(a2, arrayListClass, longClass, objectArrayClass, alGet, longIntValue);
		a2.istore(3);
		a2.aload(0);
		a2.iload(3);
		a2.invokestatic(rmGet);
		a2.areturn();
		methods.add(new ArrayMethod(cp.addUtf8(AREF2), cp.addUtf8(AREF2_DESC), 4, 4, a2.finish()));

		// _aset1(arr, i, val): _rmSet(arr, 1 + i, val) -- returns val
		JvmAsm s1 = new JvmAsm();
		s1.aload(0);
		s1.iconst(1);
		s1.aload(1);
		s1.checkcast(longClass);
		s1.invokevirtual(longIntValue);
		s1.op(Opcode.IADD);
		s1.aload(2);
		s1.invokestatic(rmSet);
		s1.areturn();
		methods.add(new ArrayMethod(cp.addUtf8(ASET1), cp.addUtf8(ASET1_DESC), 4, 3, s1.finish()));

		// _arrayDims(arr): the dimension sizes as a fresh cons list, built backwards
		// over the dims Object[] in the slot-0 header (the sizes are already boxed
		// Longs). A cons is an Object[]{car, cdr} and nil is null, matching the compiled
		// cons representation.
		JvmAsm d = new JvmAsm();
		int dArr = 0, dDims = 1, dResult = 2, dJ = 3;
		d.aload(dArr);
		d.checkcast(arrayListClass);
		d.iconst(0);
		d.invokevirtual(alGet);
		d.checkcast(objectArrayClass);
		d.iconst(0);
		d.aaload();
		d.checkcast(objectArrayClass);
		d.astore(dDims);
		d.aconstNull();
		d.astore(dResult);
		d.aload(dDims);
		d.arraylength();
		d.iconst(1);
		d.op(Opcode.ISUB);
		d.istore(dJ);
		int dLoop = d.label();
		int dDone = d.label();
		d.bind(dLoop);
		d.iload(dJ);
		d.branch(Opcode.IFLT, dDone);
		// result = new Object[]{dims[j], result}
		d.iconst(2);
		d.anewarray(objectClass);
		d.dup();
		d.iconst(0);
		d.aload(dDims);
		d.iload(dJ);
		d.aaload();
		d.aastore();
		d.dup();
		d.iconst(1);
		d.aload(dResult);
		d.aastore();
		d.astore(dResult);
		d.iinc(dJ, -1);
		d.branch(Opcode.GOTO, dLoop);
		d.bind(dDone);
		d.aload(dResult);
		d.areturn();
		methods.add(new ArrayMethod(cp.addUtf8(DIMS), cp.addUtf8(DIMS_DESC), 6, 4, d.finish()));

		// _aset2(arr, i, j, val): _rmSet(arr, 1 + i * cols + j, val) -- returns val
		JvmAsm s2 = new JvmAsm();
		emitFlat2(s2, arrayListClass, longClass, objectArrayClass, alGet, longIntValue);
		s2.istore(4);
		s2.aload(0);
		s2.iload(4);
		s2.aload(3);
		s2.invokestatic(rmSet);
		s2.areturn();
		methods.add(new ArrayMethod(cp.addUtf8(ASET2), cp.addUtf8(ASET2_DESC), 4, 5, s2.finish()));

		// _arefN(arr, subs): return _rmGet(arr, 1 + flatIndex(arr, subs))
		JvmAsm an = new JvmAsm();
		emitFlatN(an, arrayListClass, longClass, objectArrayClass, alGet, longIntValue, 1, 2, 3, 4, 5);
		an.aload(0);
		an.iconst(1);
		an.iload(2);
		an.op(Opcode.IADD);
		an.invokestatic(rmGet);
		an.areturn();
		methods.add(new ArrayMethod(cp.addUtf8(AREFN), cp.addUtf8(AREFN_DESC), 4, 6, an.finish()));

		// _asetN(arr, subs, val): _rmSet(arr, 1 + flatIndex(arr, subs), val)
		JvmAsm sn = new JvmAsm();
		emitFlatN(sn, arrayListClass, longClass, objectArrayClass, alGet, longIntValue, 1, 3, 4, 5, 6);
		sn.aload(0);
		sn.iconst(1);
		sn.iload(3);
		sn.op(Opcode.IADD);
		sn.aload(2);
		sn.invokestatic(rmSet);
		sn.areturn();
		methods.add(new ArrayMethod(cp.addUtf8(ASETN), cp.addUtf8(ASETN_DESC), 4, 7, sn.finish()));

		// _fillPointer(arr): the fill pointer (a Long), or an error when the array has
		// none. Locals: 0 = arr, 1 = header.
		JvmAsm fpm = new JvmAsm();
		emitLoadHeader(fpm, arrayListClass, objectArrayClass, alGet, 0);
		fpm.astore(1);
		int fpPresent = fpm.label();
		fpm.aload(1);
		fpm.iconst(1);
		fpm.aaload();
		fpm.branch(Opcode.IFNONNULL, fpPresent);
		emitThrow(fpm, rtExClass, rtExInit, cp.addString("fill-pointer: array has no fill pointer"));
		fpm.bind(fpPresent);
		fpm.aload(1);
		fpm.iconst(1);
		fpm.aaload();
		fpm.areturn();
		methods.add(new ArrayMethod(cp.addUtf8(FILL_POINTER), cp.addUtf8(FILL_POINTER_DESC), 3, 2, fpm.finish()));

		// _setFillPointer(arr, value): range-checked fill-pointer store; returns value.
		// Locals: 0 = arr, 1 = value, 2 = header, 3 = v (int), 4 = cap (int).
		JvmAsm sfp = new JvmAsm();
		emitLoadHeader(sfp, arrayListClass, objectArrayClass, alGet, 0);
		sfp.astore(2);
		int sfpPresent = sfp.label();
		sfp.aload(2);
		sfp.iconst(1);
		sfp.aaload();
		sfp.branch(Opcode.IFNONNULL, sfpPresent);
		emitThrow(sfp, rtExClass, rtExInit, cp.addString("%set-fill-pointer: array has no fill pointer"));
		sfp.bind(sfpPresent);
		sfp.aload(1);
		sfp.checkcast(longClass);
		sfp.invokevirtual(longIntValue);
		sfp.istore(3);
		emitLoadDim0(sfp, longClass, objectArrayClass, longIntValue, 2);
		sfp.istore(4);
		int sfpBad = sfp.label();
		int sfpOk = sfp.label();
		sfp.iload(3);
		sfp.branch(Opcode.IFLT, sfpBad);
		sfp.iload(3);
		sfp.iload(4);
		sfp.branch(Opcode.IF_ICMPGT, sfpBad);
		sfp.branch(Opcode.GOTO, sfpOk);
		sfp.bind(sfpBad);
		emitThrow(sfp, rtExClass, rtExInit, cp.addString("%set-fill-pointer: fill pointer out of range"));
		sfp.bind(sfpOk);
		sfp.aload(2);
		sfp.iconst(1);
		sfp.aload(1);
		sfp.aastore();
		sfp.aload(1);
		sfp.areturn();
		methods
			.add(new ArrayMethod(cp.addUtf8(SET_FILL_POINTER), cp.addUtf8(SET_FILL_POINTER_DESC), 3, 5, sfp.finish()));

		// _arrayHasFillPointer(arr): "t" when the header carries a fill pointer, else
		// nil (null). Locals: 0 = arr.
		JvmAsm hfp = new JvmAsm();
		emitHeaderSlotToBool(hfp, arrayListClass, objectArrayClass, alGet, cp, 1);
		methods
			.add(new ArrayMethod(cp.addUtf8(HAS_FILL_POINTER), cp.addUtf8(HAS_FILL_POINTER_DESC), 3, 1, hfp.finish()));

		// _adjustableArrayP(arr): "t" when the array was created :adjustable (the raw
		// truthy argument is stored verbatim), else nil. Locals: 0 = arr.
		JvmAsm adp = new JvmAsm();
		emitHeaderSlotToBool(adp, arrayListClass, objectArrayClass, alGet, cp, 2);
		methods.add(new ArrayMethod(cp.addUtf8(ADJUSTABLE_ARRAY_P), cp.addUtf8(ADJUSTABLE_ARRAY_P_DESC), 3, 1,
				adp.finish()));

		// _vectorPush(val, arr): store val at the fill pointer and return the index used
		// (a Long), or nil (null) when the vector is full. Locals: 0 = val, 1 = arr,
		// 2 = header, 3 = fp (int), 4 = cap (int).
		JvmAsm vp = new JvmAsm();
		emitLoadHeader(vp, arrayListClass, objectArrayClass, alGet, 1);
		vp.astore(2);
		emitRequireFillPointer(vp, longClass, longIntValue, rtExClass, rtExInit,
				cp.addString("vector-push: vector has no fill pointer"), 2);
		vp.istore(3);
		emitLoadDim0(vp, longClass, objectArrayClass, longIntValue, 2);
		vp.istore(4);
		int vpStore = vp.label();
		vp.iload(3);
		vp.iload(4);
		vp.branch(Opcode.IF_ICMPLT, vpStore);
		vp.aconstNull();
		vp.areturn();
		vp.bind(vpStore);
		emitStoreAtFillPointerAndAdvance(vp, arrayListClass, longClass, alSet, longValueOf, 0, 1, 2, 3);
		methods.add(new ArrayMethod(cp.addUtf8(VECTOR_PUSH), cp.addUtf8(VECTOR_PUSH_DESC), 6, 5, vp.finish()));

		// _vectorPop(arr): decrement the fill pointer and return the element it passed.
		// Locals: 0 = arr, 1 = header, 2 = fp (int).
		JvmAsm vpop = new JvmAsm();
		emitLoadHeader(vpop, arrayListClass, objectArrayClass, alGet, 0);
		vpop.astore(1);
		emitRequireFillPointer(vpop, longClass, longIntValue, rtExClass, rtExInit,
				cp.addString("vector-pop: vector has no fill pointer"), 1);
		vpop.istore(2);
		int vpopOk = vpop.label();
		vpop.iload(2);
		vpop.branch(Opcode.IFNE, vpopOk);
		emitThrow(vpop, rtExClass, rtExInit, cp.addString("vector-pop: empty vector"));
		vpop.bind(vpopOk);
		vpop.aload(1);
		vpop.iconst(1);
		vpop.iload(2);
		vpop.iconst(1);
		vpop.op(Opcode.ISUB);
		vpop.op(Opcode.I2L);
		vpop.invokestatic(longValueOf);
		vpop.aastore();
		// list.get(1 + (fp - 1)) == list.get(fp)
		vpop.aload(0);
		vpop.checkcast(arrayListClass);
		vpop.iload(2);
		vpop.invokevirtual(alGet);
		vpop.areturn();
		methods.add(new ArrayMethod(cp.addUtf8(VECTOR_POP), cp.addUtf8(VECTOR_POP_DESC), 6, 3, vpop.finish()));

		// _vectorPushExtend(val, arr, ext): like _vectorPush but grows the backing store
		// (by at least ext elements, minimum 1) when the vector is full, updating the
		// stored dimension size. Locals: 0 = val, 1 = arr, 2 = ext, 3 = header,
		// 4 = fp (int), 5 = cap (int), 6 = grow (int), 7 = newCap (int).
		JvmAsm vpe = new JvmAsm();
		emitLoadHeader(vpe, arrayListClass, objectArrayClass, alGet, 1);
		vpe.astore(3);
		emitRequireFillPointer(vpe, longClass, longIntValue, rtExClass, rtExInit,
				cp.addString("vector-push-extend: vector has no fill pointer"), 3);
		vpe.istore(4);
		emitLoadDim0(vpe, longClass, objectArrayClass, longIntValue, 3);
		vpe.istore(5);
		int vpeStore = vpe.label();
		vpe.iload(4);
		vpe.iload(5);
		vpe.branch(Opcode.IF_ICMPLT, vpeStore);
		// grow = max(((Long) ext).intValue(), 1); newCap = cap + grow
		vpe.aload(2);
		vpe.checkcast(longClass);
		vpe.invokevirtual(longIntValue);
		vpe.istore(6);
		int growOk = vpe.label();
		vpe.iload(6);
		vpe.iconst(1);
		vpe.branch(Opcode.IF_ICMPGE, growOk);
		vpe.iconst(1);
		vpe.istore(6);
		vpe.bind(growOk);
		vpe.iload(5);
		vpe.iload(6);
		vpe.op(Opcode.IADD);
		vpe.istore(7);
		// while (list.size() - 1 < newCap) list.add(null) -- grown slots read as nil
		int growLoop = vpe.label();
		int growDone = vpe.label();
		vpe.bind(growLoop);
		vpe.aload(1);
		vpe.checkcast(arrayListClass);
		vpe.invokevirtual(alSize);
		vpe.iconst(1);
		vpe.op(Opcode.ISUB);
		vpe.iload(7);
		vpe.branch(Opcode.IF_ICMPGE, growDone);
		vpe.aload(1);
		vpe.checkcast(arrayListClass);
		vpe.aconstNull();
		vpe.invokevirtual(alAdd);
		vpe.pop();
		vpe.branch(Opcode.GOTO, growLoop);
		vpe.bind(growDone);
		// dims[0] = Long.valueOf(newCap)
		vpe.aload(3);
		vpe.iconst(0);
		vpe.aaload();
		vpe.checkcast(objectArrayClass);
		vpe.iconst(0);
		vpe.iload(7);
		vpe.op(Opcode.I2L);
		vpe.invokestatic(longValueOf);
		vpe.aastore();
		vpe.bind(vpeStore);
		emitStoreAtFillPointerAndAdvance(vpe, arrayListClass, longClass, alSet, longValueOf, 0, 1, 3, 4);
		methods.add(new ArrayMethod(cp.addUtf8(VECTOR_PUSH_EXTEND), cp.addUtf8(VECTOR_PUSH_EXTEND_DESC), 6, 8,
				vpe.finish()));

		// _rmGet(list, idx): the single data-read primitive (idx is the 1-based list
		// index). Follows the displacement chain: while the header is a 5-element
		// {dims, fp, adj, target, offset} with a non-null target, add the offset and
		// hop to the target list. A length-6 header is the PACKED shape: the element is
		// read from the long[] in header[5] (the sentinel reads back as nil).
		// Locals: 0 = list, 1 = idx, 2 = header, 3/4 = v (long).
		JvmAsm rg = new JvmAsm();
		int rgGeneral = rg.label();
		int rgBox = rg.label();
		int rgString = rg.label();
		emitResolveDisplacement(rg, arrayListClass, longClass, objectArrayClass, alGet, longIntValue, 0, 1, 2);
		emitLandedOnString(rg, 2, rgString);
		rg.aload(2);
		rg.arraylength();
		rg.iconst(6);
		rg.branch(Opcode.IF_ICMPNE, rgGeneral);
		rg.aload(2);
		rg.iconst(5);
		rg.aaload();
		rg.checkcast(longArrayClass);
		rg.iload(1);
		rg.iconst(1);
		rg.op(Opcode.ISUB);
		rg.laload();
		rg.lstore(3);
		rg.lload(3);
		rg.ldc2Long(nilSentinel);
		rg.op(Opcode.LCMP);
		rg.branch(Opcode.IFNE, rgBox);
		rg.aconstNull();
		rg.areturn();
		rg.bind(rgBox);
		rg.lload(3);
		rg.invokestatic(longValueOf);
		rg.areturn();
		rg.bind(rgGeneral);
		rg.aload(0);
		rg.checkcast(arrayListClass);
		rg.iload(1);
		rg.invokevirtual(alGet);
		rg.areturn();
		// The string-view arm: header[3] is the immutable runtime string this view
		// aliases and idx is the 1-based character index into it. Reads by CODE POINT
		// through _cpoff (the content lives in [1, length-1), inside the framing
		// quotes) and boxes as the runtime CHARACTER int[]{cp}, exactly like _aref1's
		// own string branch.
		rg.bind(rgString);
		rg.aload(2);
		rg.iconst(3);
		rg.aaload();
		rg.checkcast(strClass);
		rg.astore(5);
		rg.aload(5);
		rg.aload(5);
		rg.iload(1);
		rg.iconst(1);
		rg.op(Opcode.ISUB);
		rg.invokestatic(strCpOffset);
		rg.invokevirtual(strCodePointAt);
		rg.istore(6);
		rg.iconst(1);
		rg.newarrayInt();
		rg.op(Opcode.DUP);
		rg.iconst(0);
		rg.iload(6);
		rg.iastore();
		rg.areturn();
		methods.add(new ArrayMethod(cp.addUtf8(RM_GET), cp.addUtf8(RM_GET_DESC), 4, 7, rg.finish()));

		// _rmSet(list, idx, val): the single data-write primitive; returns val. A
		// PACKED array (length-6 header) stores an in-range Long unboxed; any other
		// value -- or the sentinel integer itself -- widens the array in place first
		// and falls through to the boxed store.
		// Locals: 0 = list, 1 = idx, 2 = val, 3 = header, 4/5 = v (long).
		JvmAsm rs = new JvmAsm();
		int rsGeneral = rs.label();
		int rsWiden = rs.label();
		int rsString = rs.label();
		MethodrefConstant strToCharVec = cp.addMethodref(selfClass,
				cp.addNameAndType(cp.addUtf8(STR_TO_CHAR_VEC), cp.addUtf8(STR_TO_CHAR_VEC_DESC)));
		emitResolveDisplacement(rs, arrayListClass, longClass, objectArrayClass, alGet, longIntValue, 0, 1, 3);
		emitLandedOnString(rs, 3, rsString);
		rs.aload(3);
		rs.arraylength();
		rs.iconst(6);
		rs.branch(Opcode.IF_ICMPNE, rsGeneral);
		rs.aload(2);
		rs.instanceOf(longClass);
		rs.branch(Opcode.IFEQ, rsWiden);
		rs.aload(2);
		rs.checkcast(longClass);
		rs.invokevirtual(longLongValue);
		rs.lstore(4);
		rs.lload(4);
		rs.ldc2Long(nilSentinel);
		rs.op(Opcode.LCMP);
		rs.branch(Opcode.IFEQ, rsWiden);
		rs.aload(3);
		rs.iconst(5);
		rs.aaload();
		rs.checkcast(longArrayClass);
		rs.iload(1);
		rs.iconst(1);
		rs.op(Opcode.ISUB);
		rs.lload(4);
		rs.lastore();
		rs.aload(2);
		rs.areturn();
		rs.bind(rsWiden);
		rs.aload(0);
		rs.invokestatic(widen);
		rs.bind(rsGeneral);
		rs.aload(0);
		rs.checkcast(arrayListClass);
		rs.iload(1);
		rs.aload(2);
		rs.invokevirtual(alSet);
		rs.pop();
		rs.aload(2);
		rs.areturn();
		// The string-view arm: the view aliases an IMMUTABLE runtime string, which no
		// write can reach. Promote it once -- header[3] becomes a mutable character
		// vector holding the same characters -- and store into that; every later access
		// through this view (and through array-displacement's answer) sees the promoted
		// vector, so the view behaves as a mutable string from here on.
		rs.bind(rsString);
		rs.aload(3);
		rs.iconst(3);
		rs.aaload();
		rs.checkcast(strClass);
		rs.invokestatic(strToCharVec);
		rs.astore(6);
		rs.aload(3);
		rs.iconst(3);
		rs.aload(6);
		rs.aastore();
		rs.aload(6);
		rs.astore(0);
		rs.branch(Opcode.GOTO, rsGeneral);
		methods.add(new ArrayMethod(cp.addUtf8(RM_SET), cp.addUtf8(RM_SET_DESC), 4, 7, rs.finish()));

		// _strToCharVec(s): the immutable runtime string s copied into a fresh mutable
		// character vector -- an ArrayList whose slot 0 is the length-4 header
		// {dims, fillPointer, null, null} and whose slots 1.. hold one int[]{codePoint}
		// per character. Characters are read BY CODE POINT (_cpoff + codePointAt), so a
		// supplementary code point becomes one element, as everywhere else.
		// Locals: 0 = s, 1 = n, 2 = list, 3 = i.
		JvmAsm tv = new JvmAsm();
		tv.aload(0);
		tv.invokestatic(strCount);
		tv.istore(1);
		tv.anew(arrayListClass);
		tv.dup();
		tv.invokespecial(alInit);
		tv.astore(2);
		tv.aload(2);
		tv.iconst(4);
		tv.anewarray(objectClass);
		tv.dup();
		tv.iconst(0);
		tv.iconst(1);
		tv.anewarray(objectClass);
		tv.dup();
		tv.iconst(0);
		tv.iload(1);
		tv.op(Opcode.I2L);
		tv.invokestatic(longValueOf);
		tv.aastore();
		tv.aastore();
		tv.dup();
		tv.iconst(1);
		tv.iload(1);
		tv.op(Opcode.I2L);
		tv.invokestatic(longValueOf);
		tv.aastore();
		tv.invokevirtual(alAdd);
		tv.pop();
		tv.iconst(0);
		tv.istore(3);
		int tvLoop = tv.label();
		int tvDone = tv.label();
		tv.bind(tvLoop);
		tv.iload(3);
		tv.iload(1);
		tv.branch(Opcode.IF_ICMPGE, tvDone);
		tv.aload(2);
		tv.iconst(1);
		tv.newarrayInt();
		tv.op(Opcode.DUP);
		tv.iconst(0);
		tv.aload(0);
		tv.aload(0);
		tv.iload(3);
		tv.invokestatic(strCpOffset);
		tv.invokevirtual(strCodePointAt);
		tv.iastore();
		tv.invokevirtual(alAdd);
		tv.pop();
		tv.iinc(3, 1);
		tv.branch(Opcode.GOTO, tvLoop);
		tv.bind(tvDone);
		tv.aload(2);
		tv.areturn();
		methods.add(new ArrayMethod(cp.addUtf8(STR_TO_CHAR_VEC), cp.addUtf8(STR_TO_CHAR_VEC_DESC), 10, 4, tv.finish()));

		// _arrayWiden(list): converts a PACKED array (length-6 header, long[] data) to
		// the boxed shape IN PLACE -- header replaced by the ordinary length-3
		// {dims, null, null}, each long[] element appended boxed (the sentinel as
		// null/nil). A non-packed array passes through untouched; the ArrayList object
		// is the array's identity, so every alias sees the widened shape.
		// Locals: 0 = list, 1 = header, 2 = data, 3 = i, 4/5 = v (long).
		JvmAsm wd = new JvmAsm();
		int wdDone = wd.label();
		int wdLoop = wd.label();
		int wdBox = wd.label();
		int wdAdd = wd.label();
		emitLoadHeader(wd, arrayListClass, objectArrayClass, alGet, 0);
		wd.astore(1);
		wd.aload(1);
		wd.arraylength();
		wd.iconst(6);
		wd.branch(Opcode.IF_ICMPNE, wdDone);
		wd.aload(1);
		wd.iconst(5);
		wd.aaload();
		wd.checkcast(longArrayClass);
		wd.astore(2);
		// list.set(0, new Object[]{header[0], null, null})
		wd.aload(0);
		wd.checkcast(arrayListClass);
		wd.iconst(0);
		wd.iconst(3);
		wd.anewarray(objectClass);
		wd.dup();
		wd.iconst(0);
		wd.aload(1);
		wd.iconst(0);
		wd.aaload();
		wd.aastore();
		wd.invokevirtual(alSet);
		wd.pop();
		// for (i = 0; i < data.length; i++) list.add(box(data[i]))
		wd.iconst(0);
		wd.istore(3);
		wd.bind(wdLoop);
		wd.iload(3);
		wd.aload(2);
		wd.arraylength();
		wd.branch(Opcode.IF_ICMPGE, wdDone);
		wd.aload(2);
		wd.iload(3);
		wd.laload();
		wd.lstore(4);
		wd.aload(0);
		wd.checkcast(arrayListClass);
		wd.lload(4);
		wd.ldc2Long(nilSentinel);
		wd.op(Opcode.LCMP);
		wd.branch(Opcode.IFNE, wdBox);
		wd.aconstNull();
		wd.branch(Opcode.GOTO, wdAdd);
		wd.bind(wdBox);
		wd.lload(4);
		wd.invokestatic(longValueOf);
		wd.bind(wdAdd);
		wd.invokevirtual(alAdd);
		wd.pop();
		wd.iinc(3, 1);
		wd.branch(Opcode.GOTO, wdLoop);
		wd.bind(wdDone);
		wd.op(Opcode.RETURN);
		methods.add(new ArrayMethod(cp.addUtf8(WIDEN), cp.addUtf8(WIDEN_DESC), 7, 6, wd.finish()));

		// _arrayMakeDisplaced(dims, target, offset): a displaced view -- a fresh
		// ArrayList holding ONLY the 5-element header {dimsArr, null, null, target,
		// offsetLong}; the view is bounds-checked against the target's total size.
		// Locals: 0 = dims, 1 = target, 2 = offsetArg, 3 = list, 4 = total,
		// 5 = dimsArr, 6 = idx, 7 = cur, 8 = n, 9 = off (int), 10 = targetHeader,
		// 11 = targetTotal (product scratch), 12 = m (product scratch).
		JvmAsm md = new JvmAsm();
		int mdDims = 0, mdTarget = 1, mdOffset = 2, mdList = 3, mdTotal = 4, mdDimsArr = 5, mdIdx = 6, mdCur = 7,
				mdN = 8, mdOff = 9, mdTargetHeader = 10, mdProduct = 11, mdM = 12, mdHeaderSize = 13;
		md.anew(arrayListClass);
		md.dup();
		md.invokespecial(alInit);
		md.astore(mdList);
		emitParseDims(md, objectClass, longClass, objectArrayClass, longIntValue, mdDims, mdDimsArr, mdTotal, mdCur,
				mdN, mdIdx);
		// off = offsetArg == null ? 0 : ((Long) offsetArg).intValue()
		int offGiven = md.label();
		int offDone = md.label();
		md.aload(mdOffset);
		md.branch(Opcode.IFNONNULL, offGiven);
		md.iconst(0);
		md.istore(mdOff);
		md.branch(Opcode.GOTO, offDone);
		md.bind(offGiven);
		md.aload(mdOffset);
		md.checkcast(longClass);
		md.invokevirtual(longIntValue);
		md.istore(mdOff);
		md.bind(offDone);
		// targetTotal = the target's element count, and headerSize = 7 when the target
		// is a STRING (an immutable runtime string, a mutable character vector, or
		// another string view) so the result is a string VIEW rather than a bare array
		// view: 7 is the header-length tag _strv and stringp read, exactly as 4 marks a
		// character vector. The shape follows the TARGET, not :element-type -- the
		// portable substring idiom passes the target's own (array-element-type seq).
		int mdStr = md.label();
		int mdHaveTotal = md.label();
		int mdViewTag = md.label();
		md.iconst(5);
		md.istore(mdHeaderSize);
		md.aload(mdTarget);
		md.instanceOf(strClass);
		md.branch(Opcode.IFNE, mdStr);
		emitLoadHeader(md, arrayListClass, objectArrayClass, alGet, mdTarget);
		md.astore(mdTargetHeader);
		emitDimsProduct(md, longClass, objectArrayClass, longIntValue, mdTargetHeader, mdProduct, mdM);
		md.istore(mdProduct);
		md.aload(mdTargetHeader);
		md.arraylength();
		md.iconst(4);
		md.branch(Opcode.IF_ICMPEQ, mdViewTag);
		md.aload(mdTargetHeader);
		md.arraylength();
		md.iconst(7);
		md.branch(Opcode.IF_ICMPEQ, mdViewTag);
		md.branch(Opcode.GOTO, mdHaveTotal);
		md.bind(mdStr);
		md.aload(mdTarget);
		md.checkcast(strClass);
		md.invokestatic(strCount);
		md.istore(mdProduct);
		md.bind(mdViewTag);
		md.iconst(7);
		md.istore(mdHeaderSize);
		md.bind(mdHaveTotal);
		// require 0 <= off and total + off <= targetTotal
		int mdBad = md.label();
		int mdOk = md.label();
		md.iload(mdOff);
		md.branch(Opcode.IFLT, mdBad);
		md.iload(mdTotal);
		md.iload(mdOff);
		md.op(Opcode.IADD);
		md.iload(mdProduct);
		md.branch(Opcode.IF_ICMPGT, mdBad);
		md.branch(Opcode.GOTO, mdOk);
		md.bind(mdBad);
		emitThrow(md, rtExClass, rtExInit,
				cp.addString("make-array: :displaced-to array is too small for the requested view"));
		md.bind(mdOk);
		// list.add(new Object[headerSize]{dimsArr, null, null, target,
		// Long.valueOf(off)})
		md.aload(mdList);
		md.iload(mdHeaderSize);
		md.anewarray(objectClass);
		md.dup();
		md.iconst(0);
		md.aload(mdDimsArr);
		md.aastore();
		md.dup();
		md.iconst(3);
		md.aload(mdTarget);
		md.aastore();
		md.dup();
		md.iconst(4);
		md.iload(mdOff);
		md.op(Opcode.I2L);
		md.invokestatic(longValueOf);
		md.aastore();
		md.invokevirtual(alAdd);
		md.pop();
		md.aload(mdList);
		md.areturn();
		methods.add(new ArrayMethod(cp.addUtf8(MAKE_DISPLACED), cp.addUtf8(MAKE_DISPLACED_DESC), 6, 14, md.finish()));

		// _arrayBecome(a, b): replace a's dims, fill pointer and data with b's in place
		// (the in-place half of adjust-array on an adjustable array); returns a. The
		// adjustable flag (header slot 2) is kept. Both arrays are widened first: the
		// size-based element copy below reads the BOXED data slots, so a packed operand
		// (a freshly made temp with neither fill pointer nor adjustability) must take
		// the boxed shape before it. Locals: 0 = a, 1 = b, 2 = headerA, 3 = headerB,
		// 4 = i.
		JvmAsm bc = new JvmAsm();
		bc.aload(0);
		bc.invokestatic(widen);
		bc.aload(1);
		bc.invokestatic(widen);
		emitLoadHeader(bc, arrayListClass, objectArrayClass, alGet, 0);
		bc.astore(2);
		emitLoadHeader(bc, arrayListClass, objectArrayClass, alGet, 1);
		bc.astore(3);
		// headerA[0] = headerB[0]; headerA[1] = headerB[1]
		bc.aload(2);
		bc.iconst(0);
		bc.aload(3);
		bc.iconst(0);
		bc.aaload();
		bc.aastore();
		bc.aload(2);
		bc.iconst(1);
		bc.aload(3);
		bc.iconst(1);
		bc.aaload();
		bc.aastore();
		// while (a.size() > b.size()) a.remove(a.size() - 1)
		int shrinkLoop = bc.label();
		int shrinkDone = bc.label();
		bc.bind(shrinkLoop);
		bc.aload(0);
		bc.checkcast(arrayListClass);
		bc.invokevirtual(alSize);
		bc.aload(1);
		bc.checkcast(arrayListClass);
		bc.invokevirtual(alSize);
		bc.branch(Opcode.IF_ICMPLE, shrinkDone);
		bc.aload(0);
		bc.checkcast(arrayListClass);
		bc.aload(0);
		bc.checkcast(arrayListClass);
		bc.invokevirtual(alSize);
		bc.iconst(1);
		bc.op(Opcode.ISUB);
		bc.invokevirtual(alRemove);
		bc.pop();
		bc.branch(Opcode.GOTO, shrinkLoop);
		bc.bind(shrinkDone);
		// while (a.size() < b.size()) a.add(null)
		int growLoop2 = bc.label();
		int growDone2 = bc.label();
		bc.bind(growLoop2);
		bc.aload(0);
		bc.checkcast(arrayListClass);
		bc.invokevirtual(alSize);
		bc.aload(1);
		bc.checkcast(arrayListClass);
		bc.invokevirtual(alSize);
		bc.branch(Opcode.IF_ICMPGE, growDone2);
		bc.aload(0);
		bc.checkcast(arrayListClass);
		bc.aconstNull();
		bc.invokevirtual(alAdd);
		bc.pop();
		bc.branch(Opcode.GOTO, growLoop2);
		bc.bind(growDone2);
		// for (i = 1; i < b.size(); i++) a.set(i, b.get(i))
		bc.iconst(1);
		bc.istore(4);
		int copyLoop = bc.label();
		int copyDone = bc.label();
		bc.bind(copyLoop);
		bc.iload(4);
		bc.aload(1);
		bc.checkcast(arrayListClass);
		bc.invokevirtual(alSize);
		bc.branch(Opcode.IF_ICMPGE, copyDone);
		bc.aload(0);
		bc.checkcast(arrayListClass);
		bc.iload(4);
		bc.aload(1);
		bc.checkcast(arrayListClass);
		bc.iload(4);
		bc.invokevirtual(alGet);
		bc.invokevirtual(alSet);
		bc.pop();
		bc.iinc(4, 1);
		bc.branch(Opcode.GOTO, copyLoop);
		bc.bind(copyDone);
		bc.aload(0);
		bc.areturn();
		methods.add(new ArrayMethod(cp.addUtf8(ARRAY_BECOME), cp.addUtf8(ARRAY_BECOME_DESC), 5, 5, bc.finish()));

		// _arrayDispTarget(arr): the displacement target, or null (nil).
		// Locals: 0 = arr, 1 = header.
		JvmAsm dt = new JvmAsm();
		emitLoadHeader(dt, arrayListClass, objectArrayClass, alGet, 0);
		dt.astore(1);
		int dtNil = dt.label();
		dt.aload(1);
		dt.arraylength();
		dt.iconst(4);
		dt.branch(Opcode.IF_ICMPLE, dtNil);
		dt.aload(1);
		dt.iconst(3);
		dt.aaload();
		dt.areturn();
		dt.bind(dtNil);
		dt.aconstNull();
		dt.areturn();
		methods.add(new ArrayMethod(cp.addUtf8(DISP_TARGET), cp.addUtf8(DISP_TARGET_DESC), 3, 2, dt.finish()));

		// _arrayDispOffset(arr): the displacement offset, or 0. Displacement is a
		// length-5+ header WITH a non-null target -- a packed array's length-6 header
		// has a null slot 3 and must answer 0 like any other non-displaced array.
		// Locals: 0 = arr, 1 = header.
		JvmAsm dofs = new JvmAsm();
		emitLoadHeader(dofs, arrayListClass, objectArrayClass, alGet, 0);
		dofs.astore(1);
		int dofsNone = dofs.label();
		dofs.aload(1);
		dofs.arraylength();
		dofs.iconst(4);
		dofs.branch(Opcode.IF_ICMPLE, dofsNone);
		dofs.aload(1);
		dofs.iconst(3);
		dofs.aaload();
		dofs.branch(Opcode.IFNULL, dofsNone);
		dofs.aload(1);
		dofs.iconst(4);
		dofs.aaload();
		dofs.areturn();
		dofs.bind(dofsNone);
		dofs.op(Opcode.LCONST_0);
		dofs.invokestatic(longValueOf);
		dofs.areturn();
		methods.add(new ArrayMethod(cp.addUtf8(DISP_OFFSET), cp.addUtf8(DISP_OFFSET_DESC), 3, 2, dofs.finish()));

		// _charVecMake(dims, init, fp, adj): _arrayMake with the returned list's slot-0
		// header replaced by a length-4 copy {dims, fp, adj, null} -- the mutable
		// character vector marker. Locals: 0..3 = params, 4 = list, 5 = header,
		// 6 = newHeader.
		MethodrefConstant selfArrayMake = cp.addMethodref(selfClass,
				cp.addNameAndType(cp.addUtf8(MAKE), cp.addUtf8(MAKE_DESC)));
		JvmAsm cv = new JvmAsm();
		cv.aload(0);
		cv.aload(1);
		cv.aload(2);
		cv.aload(3);
		cv.invokestatic(selfArrayMake);
		cv.checkcast(arrayListClass);
		cv.astore(4);
		// A character vector's elements are boxed CHARACTERs; if _arrayMake packed the
		// allocation (a nil :initial-element), widen before stamping the marker header.
		cv.aload(4);
		cv.invokestatic(widen);
		emitLoadHeader(cv, arrayListClass, objectArrayClass, alGet, 4);
		cv.astore(5);
		cv.iconst(4);
		cv.anewarray(objectClass);
		cv.astore(6);
		for (int slot = 0; slot < 3; slot++) {
			cv.aload(6);
			cv.iconst(slot);
			cv.aload(5);
			cv.iconst(slot);
			cv.aaload();
			cv.aastore();
		}
		cv.aload(4);
		cv.iconst(0);
		cv.aload(6);
		cv.invokevirtual(alSet);
		cv.pop();
		cv.aload(4);
		cv.areturn();
		methods.add(new ArrayMethod(cp.addUtf8(CHAR_VEC_MAKE), cp.addUtf8(MAKE_DESC), 4, 7, cv.finish()));

		// _strv(o): normalizes a mutable character vector (a length-4-header array whose
		// elements are runtime CHARACTERs -- length-1 int[]{codePoint}) into the
		// quote-framed runtime string, reading up to the fill pointer (or dims[0] when
		// the fill pointer is nil); any other value is returned unchanged. Each element
		// appends via StringBuilder.appendCodePoint(int) so a supplementary code point
		// expands to its two-unit UTF-16 pair rather than being narrowed to 16 bits.
		// Locals: 0 = o, 1 = list, 2 = header, 3 = n (int), 4 = sb, 5 = i (int).
		ClassConstant sbClass = cp.addClass(cp.addUtf8("java/lang/StringBuilder"));
		ClassConstant intArrayClass = cp.addClass(cp.addUtf8("[I"));
		MethodrefConstant sbInit = cp.addMethodref(sbClass,
				cp.addNameAndType(cp.addUtf8("<init>"), cp.addUtf8("(Ljava/lang/String;)V")));
		MethodrefConstant sbAppendCodePoint = cp.addMethodref(sbClass,
				cp.addNameAndType(cp.addUtf8("appendCodePoint"), cp.addUtf8("(I)Ljava/lang/StringBuilder;")));
		MethodrefConstant sbAppendStr = cp.addMethodref(sbClass,
				cp.addNameAndType(cp.addUtf8("append"), cp.addUtf8("(Ljava/lang/String;)Ljava/lang/StringBuilder;")));
		MethodrefConstant sbToString = cp.addMethodref(sbClass,
				cp.addNameAndType(cp.addUtf8("toString"), cp.addUtf8("()Ljava/lang/String;")));
		am.ik.jvm.ConstantPool.StringConstant quoteStr = cp.addString("\"");
		MethodrefConstant strSubstring = cp.addMethodref(strClass,
				cp.addNameAndType(cp.addUtf8("substring"), cp.addUtf8("(II)Ljava/lang/String;")));
		JvmAsm sv = new JvmAsm();
		int svNotCv = sv.label();
		int svView = sv.label();
		int svRender = sv.label();
		int svStr = sv.label();
		sv.aload(0);
		sv.instanceOf(arrayListClass);
		sv.branch(Opcode.IFEQ, svNotCv);
		sv.aload(0);
		sv.checkcast(arrayListClass);
		sv.astore(1);
		sv.aload(1);
		sv.invokevirtual(alSize);
		sv.branch(Opcode.IFEQ, svNotCv);
		sv.aload(1);
		sv.iconst(0);
		sv.invokevirtual(alGet);
		sv.instanceOf(objectArrayClass);
		sv.branch(Opcode.IFEQ, svNotCv);
		sv.aload(1);
		sv.iconst(0);
		sv.invokevirtual(alGet);
		sv.checkcast(objectArrayClass);
		sv.astore(2);
		sv.aload(2);
		sv.arraylength();
		sv.iconst(7);
		sv.branch(Opcode.IF_ICMPEQ, svView);
		sv.aload(2);
		sv.arraylength();
		sv.iconst(4);
		sv.branch(Opcode.IF_ICMPNE, svNotCv);
		// A character vector reads its own slots: base = 1, and
		// n = header[1] != null ? fill pointer : dims[0]
		sv.iconst(1);
		sv.istore(6);
		int svUseDim = sv.label();
		int svHaveN = sv.label();
		sv.aload(2);
		sv.iconst(1);
		sv.aaload();
		sv.branch(Opcode.IFNULL, svUseDim);
		sv.aload(2);
		sv.iconst(1);
		sv.aaload();
		sv.checkcast(longClass);
		sv.invokevirtual(longIntValue);
		sv.istore(3);
		sv.branch(Opcode.GOTO, svHaveN);
		sv.bind(svUseDim);
		emitLoadDim0(sv, longClass, objectArrayClass, longIntValue, 2);
		sv.istore(3);
		sv.bind(svHaveN);
		sv.branch(Opcode.GOTO, svRender);
		// A STRING VIEW (length-7 header) has no storage of its own: n is its
		// dimension, and the walk hands back either the character vector it aliases
		// (rendered element by element from the resolved base) or the immutable string
		// it aliases (sliced by code point in one substring).
		sv.bind(svView);
		emitLoadDim0(sv, longClass, objectArrayClass, longIntValue, 2);
		sv.istore(3);
		sv.iconst(1);
		sv.istore(6);
		emitResolveDisplacement(sv, arrayListClass, longClass, objectArrayClass, alGet, longIntValue, 1, 6, 2);
		emitLandedOnString(sv, 2, svStr);
		sv.aload(2);
		sv.arraylength();
		sv.iconst(4);
		sv.branch(Opcode.IF_ICMPNE, svNotCv);
		// sb = new StringBuilder("\""); for i in 0..n-1: sb.append(char at base + i)
		sv.bind(svRender);
		sv.anew(sbClass);
		sv.dup();
		sv.ldcString(quoteStr);
		sv.invokespecial(sbInit);
		sv.astore(4);
		sv.iconst(0);
		sv.istore(5);
		int svLoop = sv.label();
		int svDone = sv.label();
		sv.bind(svLoop);
		sv.iload(5);
		sv.iload(3);
		sv.branch(Opcode.IF_ICMPGE, svDone);
		sv.aload(4);
		sv.aload(1);
		sv.checkcast(arrayListClass);
		sv.iload(6);
		sv.iload(5);
		sv.op(Opcode.IADD);
		sv.invokevirtual(alGet);
		sv.checkcast(intArrayClass);
		sv.iconst(0);
		sv.iaload();
		sv.invokevirtual(sbAppendCodePoint);
		sv.pop();
		sv.iinc(5, 1);
		sv.branch(Opcode.GOTO, svLoop);
		sv.bind(svDone);
		sv.aload(4);
		sv.ldcString(quoteStr);
		sv.invokevirtual(sbAppendStr);
		sv.pop();
		sv.aload(4);
		sv.invokevirtual(sbToString);
		sv.areturn();
		// s = (String) header[3]; the view's characters are s[base - 1 .. base - 1 + n)
		// by CODE POINT, so both ends translate through _cpoff.
		sv.bind(svStr);
		sv.aload(2);
		sv.iconst(3);
		sv.aaload();
		sv.checkcast(strClass);
		sv.astore(7);
		sv.anew(sbClass);
		sv.dup();
		sv.ldcString(quoteStr);
		sv.invokespecial(sbInit);
		sv.astore(4);
		sv.aload(4);
		sv.aload(7);
		sv.aload(7);
		sv.iload(6);
		sv.iconst(1);
		sv.op(Opcode.ISUB);
		sv.invokestatic(strCpOffset);
		sv.aload(7);
		sv.iload(6);
		sv.iconst(1);
		sv.op(Opcode.ISUB);
		sv.iload(3);
		sv.op(Opcode.IADD);
		sv.invokestatic(strCpOffset);
		sv.invokevirtual(strSubstring);
		sv.invokevirtual(sbAppendStr);
		sv.pop();
		sv.aload(4);
		sv.ldcString(quoteStr);
		sv.invokevirtual(sbAppendStr);
		sv.pop();
		sv.aload(4);
		sv.invokevirtual(sbToString);
		sv.areturn();
		sv.bind(svNotCv);
		sv.aload(0);
		sv.areturn();
		methods.add(new ArrayMethod(cp.addUtf8(STRV), cp.addUtf8(STRV_DESC), 7, 8, sv.finish()));

		return methods;
	}

	// Follows the displacement chain of the array list in listSlot: while its header is
	// a 5- or 7-element {dims, fp, adj, target, offset, ...} with a non-null target, add
	// the offset to the 1-based list index in idxSlot and hop listSlot to the target. A
	// length-4 header (a mutable character vector) is NOT a displacement, and neither
	// is the length-6 PACKED header (its slot 3 is null, so the target test ends the
	// loop); the caller reads the final header from headerSlot to pick the packed or
	// boxed data access.
	//
	// A STRING target (the length-7 string-view header over an immutable runtime
	// string) ends the walk WITHOUT hopping: the offset is already folded into idxSlot,
	// and headerSlot keeps the view's own header so the caller can read slot 3 as the
	// string. That is the one exit where {@code header.length > 4 && header[3] != null}
	// still holds afterwards, so a single test tells the caller it landed on a string.
	private static void emitResolveDisplacement(JvmAsm a, ClassConstant arrayListClass, ClassConstant longClass,
			ClassConstant objectArrayClass, MethodrefConstant alGet, MethodrefConstant longIntValue, int listSlot,
			int idxSlot, int headerSlot) {
		int loop = a.label();
		int done = a.label();
		a.bind(loop);
		emitLoadHeader(a, arrayListClass, objectArrayClass, alGet, listSlot);
		a.astore(headerSlot);
		a.aload(headerSlot);
		a.arraylength();
		a.iconst(4);
		a.branch(Opcode.IF_ICMPLE, done);
		a.aload(headerSlot);
		a.iconst(3);
		a.aaload();
		a.branch(Opcode.IFNULL, done);
		// idx += ((Long) header[4]).intValue()
		a.iload(idxSlot);
		a.aload(headerSlot);
		a.iconst(4);
		a.aaload();
		a.checkcast(longClass);
		a.invokevirtual(longIntValue);
		a.op(Opcode.IADD);
		a.istore(idxSlot);
		// A non-array target is the immutable string a string view aliases: stop here.
		a.aload(headerSlot);
		a.iconst(3);
		a.aaload();
		a.instanceOf(arrayListClass);
		a.branch(Opcode.IFEQ, done);
		// list = header[3]
		a.aload(headerSlot);
		a.iconst(3);
		a.aaload();
		a.astore(listSlot);
		a.branch(Opcode.GOTO, loop);
		a.bind(done);
	}

	// Emits the "the displacement walk ended on a STRING target" test: leaves 1 on the
	// stack when headerSlot holds a length &gt; 4 header whose slot 3 is non-null (see
	// emitResolveDisplacement), 0 otherwise. Costs two compares on the ordinary path.
	private static void emitLandedOnString(JvmAsm a, int headerSlot, int yes) {
		int no = a.label();
		a.aload(headerSlot);
		a.arraylength();
		a.iconst(4);
		a.branch(Opcode.IF_ICMPLE, no);
		a.aload(headerSlot);
		a.iconst(3);
		a.aaload();
		a.branch(Opcode.IFNONNULL, yes);
		a.bind(no);
	}

	// Parses a make-array dimensions argument in the local dims (a Long for the rank-1
	// shorthand, otherwise a cons list of Longs) into an Object[] of boxed Long sizes
	// (dimsArr) and the int total element count (total). cur/n/idx are scratch slots.
	private static void emitParseDims(JvmAsm m, ClassConstant objectClass, ClassConstant longClass,
			ClassConstant objectArrayClass, MethodrefConstant longIntValue, int dims, int dimsArr, int total, int cur,
			int n, int idx) {
		m.aload(dims);
		m.instanceOf(longClass);
		int notLong = m.label();
		m.branch(Opcode.IFEQ, notLong);
		// 1-D integer shorthand: dimsArr = {dims}; total = ((Long) dims).intValue()
		m.iconst(1);
		m.anewarray(objectClass);
		m.dup();
		m.iconst(0);
		m.aload(dims);
		m.aastore();
		m.astore(dimsArr);
		m.aload(dims);
		m.checkcast(longClass);
		m.invokevirtual(longIntValue);
		m.istore(total);
		int afterDims = m.label();
		m.branch(Opcode.GOTO, afterDims);
		// cons list of dimensions: first count the length (n), then copy the sizes
		// into dimsArr while multiplying total.
		m.bind(notLong);
		m.iconst(0);
		m.istore(n);
		m.aload(dims);
		m.astore(cur);
		int countLoop = m.label();
		int countDone = m.label();
		m.bind(countLoop);
		m.aload(cur);
		m.instanceOf(objectArrayClass);
		m.branch(Opcode.IFEQ, countDone);
		m.iinc(n, 1);
		m.aload(cur);
		m.checkcast(objectArrayClass);
		m.iconst(1);
		m.aaload();
		m.astore(cur);
		m.branch(Opcode.GOTO, countLoop);
		m.bind(countDone);
		m.iload(n);
		m.anewarray(objectClass);
		m.astore(dimsArr);
		m.iconst(1);
		m.istore(total);
		m.iconst(0);
		m.istore(idx);
		m.aload(dims);
		m.astore(cur);
		int fillLoop = m.label();
		m.bind(fillLoop);
		m.iload(idx);
		m.iload(n);
		m.branch(Opcode.IF_ICMPGE, afterDims);
		// dimsArr[idx] = car(cur)
		m.aload(dimsArr);
		m.iload(idx);
		m.aload(cur);
		m.checkcast(objectArrayClass);
		m.iconst(0);
		m.aaload();
		m.aastore();
		// total *= ((Long) dimsArr[idx]).intValue()
		m.iload(total);
		m.aload(dimsArr);
		m.iload(idx);
		m.aaload();
		m.checkcast(longClass);
		m.invokevirtual(longIntValue);
		m.op(Opcode.IMUL);
		m.istore(total);
		// cur = cdr(cur)
		m.aload(cur);
		m.checkcast(objectArrayClass);
		m.iconst(1);
		m.aaload();
		m.astore(cur);
		m.iinc(idx, 1);
		m.branch(Opcode.GOTO, fillLoop);
		m.bind(afterDims);
	}

	// Pushes the int product of the boxed Long dimension sizes of the header in
	// headerSlot (the total element count), using productSlot/mSlot as scratch.
	private static void emitDimsProduct(JvmAsm a, ClassConstant longClass, ClassConstant objectArrayClass,
			MethodrefConstant longIntValue, int headerSlot, int productSlot, int mSlot) {
		a.iconst(1);
		a.istore(productSlot);
		a.iconst(0);
		a.istore(mSlot);
		int loop = a.label();
		int done = a.label();
		a.bind(loop);
		a.iload(mSlot);
		a.aload(headerSlot);
		a.iconst(0);
		a.aaload();
		a.checkcast(objectArrayClass);
		a.arraylength();
		a.branch(Opcode.IF_ICMPGE, done);
		a.iload(productSlot);
		a.aload(headerSlot);
		a.iconst(0);
		a.aaload();
		a.checkcast(objectArrayClass);
		a.iload(mSlot);
		a.aaload();
		a.checkcast(longClass);
		a.invokevirtual(longIntValue);
		a.op(Opcode.IMUL);
		a.istore(productSlot);
		a.iinc(mSlot, 1);
		a.branch(Opcode.GOTO, loop);
		a.bind(done);
		a.iload(productSlot);
	}

	// Pushes the slot-0 header Object[] of the array in arrSlot.
	private static void emitLoadHeader(JvmAsm a, ClassConstant arrayListClass, ClassConstant objectArrayClass,
			MethodrefConstant alGet, int arrSlot) {
		a.aload(arrSlot);
		a.checkcast(arrayListClass);
		a.iconst(0);
		a.invokevirtual(alGet);
		a.checkcast(objectArrayClass);
	}

	// Pushes the int first dimension size of the header in headerSlot.
	private static void emitLoadDim0(JvmAsm a, ClassConstant longClass, ClassConstant objectArrayClass,
			MethodrefConstant longIntValue, int headerSlot) {
		a.aload(headerSlot);
		a.iconst(0);
		a.aaload();
		a.checkcast(objectArrayClass);
		a.iconst(0);
		a.aaload();
		a.checkcast(longClass);
		a.invokevirtual(longIntValue);
	}

	// Emits a full helper body returning "t" when header[slot] of the array in local 0
	// is non-null, else null (nil). A non-array argument (a plain string handed to
	// adjustable-array-p, cl-ppcre's gather-strings collector) is nil, not a cast
	// error.
	private static void emitHeaderSlotToBool(JvmAsm a, ClassConstant arrayListClass, ClassConstant objectArrayClass,
			MethodrefConstant alGet, ConstantPool cp, int slot) {
		int isNil = a.label();
		a.aload(0);
		a.instanceOf(arrayListClass);
		a.branch(Opcode.IFEQ, isNil);
		emitLoadHeader(a, arrayListClass, objectArrayClass, alGet, 0);
		a.iconst(slot);
		a.aaload();
		a.branch(Opcode.IFNULL, isNil);
		a.ldcString(cp.addString("T"));
		a.areturn();
		a.bind(isNil);
		a.aconstNull();
		a.areturn();
	}

	// Requires a fill pointer on the header in headerSlot (throws with message when
	// absent) and pushes its int value.
	private static void emitRequireFillPointer(JvmAsm a, ClassConstant longClass, MethodrefConstant longIntValue,
			ClassConstant rtExClass, MethodrefConstant rtExInit, am.ik.jvm.ConstantPool.StringConstant message,
			int headerSlot) {
		int present = a.label();
		a.aload(headerSlot);
		a.iconst(1);
		a.aaload();
		a.branch(Opcode.IFNONNULL, present);
		emitThrow(a, rtExClass, rtExInit, message);
		a.bind(present);
		a.aload(headerSlot);
		a.iconst(1);
		a.aaload();
		a.checkcast(longClass);
		a.invokevirtual(longIntValue);
	}

	// new RuntimeException(message); athrow.
	private static void emitThrow(JvmAsm a, ClassConstant rtExClass, MethodrefConstant rtExInit,
			am.ik.jvm.ConstantPool.StringConstant message) {
		a.anew(rtExClass);
		a.dup();
		a.ldcString(message);
		a.invokespecial(rtExInit);
		a.op(Opcode.ATHROW);
	}

	// Stores val at the fill pointer of the array in arrSlot (data index 1 + fp),
	// advances the stored fill pointer and returns Long.valueOf(fp).
	private static void emitStoreAtFillPointerAndAdvance(JvmAsm a, ClassConstant arrayListClass,
			ClassConstant longClass, MethodrefConstant alSet, MethodrefConstant longValueOf, int valSlot, int arrSlot,
			int headerSlot, int fpSlot) {
		a.aload(arrSlot);
		a.checkcast(arrayListClass);
		a.iconst(1);
		a.iload(fpSlot);
		a.op(Opcode.IADD);
		a.aload(valSlot);
		a.invokevirtual(alSet);
		a.pop();
		a.aload(headerSlot);
		a.iconst(1);
		a.iload(fpSlot);
		a.iconst(1);
		a.op(Opcode.IADD);
		a.op(Opcode.I2L);
		a.invokestatic(longValueOf);
		a.aastore();
		a.iload(fpSlot);
		a.op(Opcode.I2L);
		a.invokestatic(longValueOf);
		a.areturn();
	}

	/**
	 * Builds the two array-printing helpers ({@code _arrayToString} for prin1 and
	 * {@code _arrayToDisplayString} for princ). Each renders a rank-1 array as
	 * {@code #(...)} and a rank-n array as {@code #nA((...) ...)}, calling back into the
	 * element formatter ({@code _lispToString} / {@code _lispToDisplayString}) for each
	 * element. They are gated alongside the other array helpers.
	 * @param cp the constant pool
	 * @param lispToString the prin1 element formatter ({@code _lispToString})
	 * @param lispToDisplayString the princ element formatter
	 * ({@code _lispToDisplayString})
	 * @return the two helper methods
	 */
	static List<ArrayMethod> buildToStringMethods(ConstantPool cp, MethodrefConstant lispToString,
			MethodrefConstant lispToDisplayString, ClassConstant selfClass,
			JvmRuntimeBuilder.RenderGuardRefs renderGuard) {
		ClassConstant arrayListClass = cp.addClass(cp.addUtf8("java/util/ArrayList"));
		ClassConstant longClass = cp.addClass(cp.addUtf8("java/lang/Long"));
		ClassConstant objectArrayClass = cp.addClass(cp.addUtf8("[Ljava/lang/Object;"));
		MethodrefConstant rmGet = cp.addMethodref(selfClass,
				cp.addNameAndType(cp.addUtf8(RM_GET), cp.addUtf8(RM_GET_DESC)));
		ClassConstant sbClass = cp.addClass(cp.addUtf8("java/lang/StringBuilder"));
		ClassConstant stringClass = cp.addClass(cp.addUtf8("java/lang/String"));
		MethodrefConstant alGet = cp.addMethodref(arrayListClass,
				cp.addNameAndType(cp.addUtf8("get"), cp.addUtf8("(I)Ljava/lang/Object;")));
		MethodrefConstant alSize = cp.addMethodref(arrayListClass,
				cp.addNameAndType(cp.addUtf8("size"), cp.addUtf8("()I")));
		MethodrefConstant longIntValue = cp.addMethodref(longClass,
				cp.addNameAndType(cp.addUtf8("intValue"), cp.addUtf8("()I")));
		MethodrefConstant sbInit = cp.addMethodref(sbClass,
				cp.addNameAndType(cp.addUtf8("<init>"), cp.addUtf8("(Ljava/lang/String;)V")));
		MethodrefConstant sbAppend = cp.addMethodref(sbClass,
				cp.addNameAndType(cp.addUtf8("append"), cp.addUtf8("(Ljava/lang/String;)Ljava/lang/StringBuilder;")));
		MethodrefConstant sbToString = cp.addMethodref(sbClass,
				cp.addNameAndType(cp.addUtf8("toString"), cp.addUtf8("()Ljava/lang/String;")));
		MethodrefConstant stringValueOfInt = cp.addMethodref(stringClass,
				cp.addNameAndType(cp.addUtf8("valueOf"), cp.addUtf8("(I)Ljava/lang/String;")));

		List<ArrayMethod> methods = new ArrayList<>();
		methods.add(new ArrayMethod(cp.addUtf8(TO_STRING), cp.addUtf8(TO_STRING_DESC), 5, 12,
				buildToString(cp, arrayListClass, longClass, objectArrayClass, alGet, alSize, longIntValue, sbInit,
						sbAppend, sbToString, stringValueOfInt, lispToString, rmGet, renderGuard)));
		methods.add(new ArrayMethod(cp.addUtf8(TO_DISPLAY_STRING), cp.addUtf8(TO_STRING_DESC), 5, 12,
				buildToString(cp, arrayListClass, longClass, objectArrayClass, alGet, alSize, longIntValue, sbInit,
						sbAppend, sbToString, stringValueOfInt, lispToDisplayString, rmGet, renderGuard)));
		return methods;
	}

	// Emits one array-printing helper implementing the readable #(...) / #nA(...)
	// syntax: a nested group paren opens where the flat index k is a multiple of that
	// dimension's stride (the product of the trailing dimension sizes) and closes where
	// k + 1 is. The element count clamps to the fill pointer when the header carries
	// one, so a fill-pointer vector prints only up to it. Locals: 0=arr, 1=list, 2=sb,
	// 3=n (element count), 4=dims (Object[]), 5=k, 6=j (dimension), 7=stride,
	// 8=m (stride scratch), 9=rank, 10=header (Object[]).
	private static List<Integer> buildToString(ConstantPool cp, ClassConstant arrayListClass, ClassConstant longClass,
			ClassConstant objectArrayClass, MethodrefConstant alGet, MethodrefConstant alSize,
			MethodrefConstant longIntValue, MethodrefConstant sbInit, MethodrefConstant sbAppend,
			MethodrefConstant sbToString, MethodrefConstant stringValueOfInt, MethodrefConstant elementFormat,
			MethodrefConstant rmGet, JvmRuntimeBuilder.RenderGuardRefs renderGuard) {
		int arr = 0, list = 1, sb = 2, n = 3, dimsArr = 4, k = 5, j = 6, stride = 7, m = 8, rank = 9, header = 10,
				guardScratch = 11;
		JvmAsm a = new JvmAsm();
		// The cycle guard (the shared RenderGuardRefs discipline over the
		// _renderPath/_renderDepth statics, kept in step by
		// JvmLispCompilerTest.compileAndRunPrintOfACyclicConsIsFinite): an array
		// already on the current rendering path -- an array holding itself, directly or
		// through a list -- or the frame past the 256-frame depth cap renders as "#",
		// the *print-level* cutoff marker. A packed array routes here through its
		// boxed-general conversion, so it opens the same one frame the interpreter's
		// packed renderers open.
		int pathInited = a.label();
		a.getstatic(renderGuard.pathField());
		a.branch(Opcode.IFNONNULL, pathInited);
		a.iconst(RenderCycleGuard.MAX_RENDER_DEPTH);
		a.anewarray(renderGuard.objectClass());
		a.putstatic(renderGuard.pathField());
		a.bind(pathInited);
		a.iconst(0);
		a.istore(guardScratch);
		int scanLoop = a.label();
		int scanDone = a.label();
		int scanMiss = a.label();
		a.bind(scanLoop);
		a.iload(guardScratch);
		a.getstatic(renderGuard.depthField());
		a.branch(Opcode.IF_ICMPGE, scanDone);
		a.getstatic(renderGuard.pathField());
		a.iload(guardScratch);
		a.aaload();
		a.aload(arr);
		a.branch(Opcode.IF_ACMPNE, scanMiss);
		a.ldcString(renderGuard.depthMarkerStr());
		a.areturn();
		a.bind(scanMiss);
		a.iinc(guardScratch, 1);
		a.branch(Opcode.GOTO, scanLoop);
		a.bind(scanDone);
		int underCap = a.label();
		a.getstatic(renderGuard.depthField());
		a.istore(guardScratch);
		a.iload(guardScratch);
		a.iconst(RenderCycleGuard.MAX_RENDER_DEPTH);
		a.branch(Opcode.IF_ICMPLT, underCap);
		a.ldcString(renderGuard.depthMarkerStr());
		a.areturn();
		a.bind(underCap);
		a.getstatic(renderGuard.pathField());
		a.iload(guardScratch);
		a.aload(arr);
		a.aastore();
		a.iload(guardScratch);
		a.iconst(1);
		a.op(Opcode.IADD);
		a.putstatic(renderGuard.depthField());
		// list = (ArrayList) arr; header = (Object[]) list.get(0)
		a.aload(arr);
		a.checkcast(arrayListClass);
		a.astore(list);
		a.aload(list);
		a.iconst(0);
		a.invokevirtual(alGet);
		a.checkcast(objectArrayClass);
		a.astore(header);
		// n = header[1] != null ? fill pointer : product of the dims (the total element
		// count; a displaced array holds no data slots, so size() - 1 would be wrong)
		int useSize = a.label();
		int afterN = a.label();
		a.aload(header);
		a.iconst(1);
		a.aaload();
		a.branch(Opcode.IFNULL, useSize);
		a.aload(header);
		a.iconst(1);
		a.aaload();
		a.checkcast(longClass);
		a.invokevirtual(longIntValue);
		a.istore(n);
		a.branch(Opcode.GOTO, afterN);
		a.bind(useSize);
		emitDimsProduct(a, longClass, objectArrayClass, longIntValue, header, stride, m);
		a.istore(n);
		a.bind(afterN);
		// dims = (Object[]) header[0]; rank = dims.length
		a.aload(header);
		a.iconst(0);
		a.aaload();
		a.checkcast(objectArrayClass);
		a.astore(dimsArr);
		a.aload(dimsArr);
		a.arraylength();
		a.istore(rank);
		// sb = new StringBuilder("#"); rank 1 appends "(", rank n appends n then "A("
		a.anew(sbClass(cp));
		a.dup();
		a.ldcString(cp.addString("#"));
		a.invokespecial(sbInit);
		a.astore(sb);
		int rankN = a.label();
		int afterPrefix = a.label();
		a.iload(rank);
		a.iconst(1);
		a.branch(Opcode.IF_ICMPNE, rankN);
		appendStr(a, sb, sbAppend, cp.addString("("));
		a.branch(Opcode.GOTO, afterPrefix);
		a.bind(rankN);
		a.aload(sb);
		a.iload(rank);
		a.invokestatic(stringValueOfInt);
		a.invokevirtual(sbAppend);
		a.pop();
		appendStr(a, sb, sbAppend, cp.addString("A("));
		a.bind(afterPrefix);
		// k = 0
		a.iconst(0);
		a.istore(k);
		int loop = a.label();
		int end = a.label();
		a.bind(loop);
		a.iload(k);
		a.iload(n);
		a.branch(Opcode.IF_ICMPGE, end);
		// if (k != 0) sb.append(" ")
		int noSpace = a.label();
		a.iload(k);
		a.branch(Opcode.IFEQ, noSpace);
		appendStr(a, sb, sbAppend, cp.addString(" "));
		a.bind(noSpace);
		// opens: for j in 1..rank-1 (outermost first): if (k % stride(j) == 0) "("
		a.iconst(1);
		a.istore(j);
		int openLoop = a.label();
		int openDone = a.label();
		a.bind(openLoop);
		a.iload(j);
		a.iload(rank);
		a.branch(Opcode.IF_ICMPGE, openDone);
		emitStride(a, longClass, longIntValue, dimsArr, j, stride, m, rank);
		int noOpen = a.label();
		a.iload(k);
		a.iload(stride);
		a.op(Opcode.IREM);
		a.branch(Opcode.IFNE, noOpen);
		appendStr(a, sb, sbAppend, cp.addString("("));
		a.bind(noOpen);
		a.iinc(j, 1);
		a.branch(Opcode.GOTO, openLoop);
		a.bind(openDone);
		// sb.append(elementFormat(_rmGet(list, k + 1))) -- displaced-aware element read
		a.aload(sb);
		a.aload(list);
		a.iload(k);
		a.iconst(1);
		a.op(Opcode.IADD);
		a.invokestatic(rmGet);
		a.invokestatic(elementFormat);
		a.invokevirtual(sbAppend);
		a.pop();
		// closes: for j in rank-1..1 (innermost first): if ((k+1) % stride(j) == 0) ")"
		a.iload(rank);
		a.iconst(1);
		a.op(Opcode.ISUB);
		a.istore(j);
		int closeLoop = a.label();
		int closeDone = a.label();
		a.bind(closeLoop);
		a.iload(j);
		a.iconst(1);
		a.branch(Opcode.IF_ICMPLT, closeDone);
		emitStride(a, longClass, longIntValue, dimsArr, j, stride, m, rank);
		int noClose = a.label();
		a.iload(k);
		a.iconst(1);
		a.op(Opcode.IADD);
		a.iload(stride);
		a.op(Opcode.IREM);
		a.branch(Opcode.IFNE, noClose);
		appendStr(a, sb, sbAppend, cp.addString(")"));
		a.bind(noClose);
		a.iinc(j, -1);
		a.branch(Opcode.GOTO, closeLoop);
		a.bind(closeDone);
		// k++; loop
		a.iinc(k, 1);
		a.branch(Opcode.GOTO, loop);
		a.bind(end);
		// sb.append(")"); return sb.toString() -- under the guard's pop, the twin of
		// JvmRuntimeBuilder.emitRenderGuardExitAndReturn: over one read, clamped so a
		// rendering race between request threads can at worst misplace a marker.
		appendStr(a, sb, sbAppend, cp.addString(")"));
		a.aload(sb);
		a.invokevirtual(sbToString);
		int popClamp = a.label();
		a.getstatic(renderGuard.depthField());
		a.iconst(1);
		a.op(Opcode.ISUB);
		a.istore(guardScratch);
		a.iload(guardScratch);
		a.branch(Opcode.IFLT, popClamp);
		a.getstatic(renderGuard.pathField());
		a.iload(guardScratch);
		a.op(Opcode.ACONST_NULL);
		a.aastore();
		a.iload(guardScratch);
		a.putstatic(renderGuard.depthField());
		a.areturn();
		a.bind(popClamp);
		a.iconst(0);
		a.putstatic(renderGuard.depthField());
		a.areturn();
		return a.finish();
	}

	private static ClassConstant sbClass(ConstantPool cp) {
		return cp.addClass(cp.addUtf8("java/lang/StringBuilder"));
	}

	// stride = product of ((Long) dims[m]).intValue() for m in j..rank-1.
	private static void emitStride(JvmAsm a, ClassConstant longClass, MethodrefConstant longIntValue, int dimsArrSlot,
			int jSlot, int strideSlot, int mSlot, int rankSlot) {
		a.iconst(1);
		a.istore(strideSlot);
		a.iload(jSlot);
		a.istore(mSlot);
		int strideLoop = a.label();
		int strideDone = a.label();
		a.bind(strideLoop);
		a.iload(mSlot);
		a.iload(rankSlot);
		a.branch(Opcode.IF_ICMPGE, strideDone);
		a.iload(strideSlot);
		a.aload(dimsArrSlot);
		a.iload(mSlot);
		a.aaload();
		a.checkcast(longClass);
		a.invokevirtual(longIntValue);
		a.op(Opcode.IMUL);
		a.istore(strideSlot);
		a.iinc(mSlot, 1);
		a.branch(Opcode.GOTO, strideLoop);
		a.bind(strideDone);
	}

	// sb.append(str); discard the returned StringBuilder.
	private static void appendStr(JvmAsm a, int sbSlot, MethodrefConstant sbAppend,
			am.ik.jvm.ConstantPool.StringConstant str) {
		a.aload(sbSlot);
		a.ldcString(str);
		a.invokevirtual(sbAppend);
		a.pop();
	}

	// Pushes the rank-2 flat index 1 + i*cols + j, where arr is in slot 0, i in slot 1,
	// j in slot 2, and cols is the second element of the Object[] dimension header.
	private static void emitFlat2(JvmAsm a, ClassConstant arrayListClass, ClassConstant longClass,
			ClassConstant objectArrayClass, MethodrefConstant get, MethodrefConstant intValue) {
		a.iconst(1);
		a.aload(1);
		a.checkcast(longClass);
		a.invokevirtual(intValue);
		// cols = ((Long) ((Object[]) ((Object[]) list.get(0))[0])[1]).intValue()
		a.aload(0);
		a.checkcast(arrayListClass);
		a.iconst(0);
		a.invokevirtual(get);
		a.checkcast(objectArrayClass);
		a.iconst(0);
		a.aaload();
		a.checkcast(objectArrayClass);
		a.iconst(1);
		a.aaload();
		a.checkcast(longClass);
		a.invokevirtual(intValue);
		a.op(Opcode.IMUL);
		a.op(Opcode.IADD);
		a.aload(2);
		a.checkcast(longClass);
		a.invokevirtual(intValue);
		a.op(Opcode.IADD);
	}

	// Computes the Horner flat index over an Object[] of Long subscripts (in the slot
	// subs) against the array in slot 0, leaving it in the int slot flat:
	// flat = subs[0]; for k in 1..: flat = flat * dims[k] + subs[k].
	private static void emitFlatN(JvmAsm a, ClassConstant arrayListClass, ClassConstant longClass,
			ClassConstant objectArrayClass, MethodrefConstant get, MethodrefConstant intValue, int subs, int flat,
			int kSlot, int nSlot, int dimsSlot) {
		// dims = (Object[]) ((Object[]) ((ArrayList) arr).get(0))[0]
		a.aload(0);
		a.checkcast(arrayListClass);
		a.iconst(0);
		a.invokevirtual(get);
		a.checkcast(objectArrayClass);
		a.iconst(0);
		a.aaload();
		a.checkcast(objectArrayClass);
		a.astore(dimsSlot);
		// subs = (Object[]) subs (re-store the checked cast); n = subs.length
		a.aload(subs);
		a.checkcast(objectArrayClass);
		a.astore(subs);
		a.aload(subs);
		a.arraylength();
		a.istore(nSlot);
		// flat = ((Long) subs[0]).intValue()
		a.aload(subs);
		a.iconst(0);
		a.aaload();
		a.checkcast(longClass);
		a.invokevirtual(intValue);
		a.istore(flat);
		// for k in 1..n-1: flat = flat * dims[k] + subs[k]
		a.iconst(1);
		a.istore(kSlot);
		int loop = a.label();
		int done = a.label();
		a.bind(loop);
		a.iload(kSlot);
		a.iload(nSlot);
		a.branch(Opcode.IF_ICMPGE, done);
		a.iload(flat);
		a.aload(dimsSlot);
		a.iload(kSlot);
		a.aaload();
		a.checkcast(longClass);
		a.invokevirtual(intValue);
		a.op(Opcode.IMUL);
		a.aload(subs);
		a.iload(kSlot);
		a.aaload();
		a.checkcast(longClass);
		a.invokevirtual(intValue);
		a.op(Opcode.IADD);
		a.istore(flat);
		a.iinc(kSlot, 1);
		a.branch(Opcode.GOTO, loop);
		a.bind(done);
	}

}
