package am.ik.rontolisp.codegen.jvm;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import am.ik.jvm.ConstantPool;
import am.ik.jvm.ConstantPool.ClassConstant;
import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.ConstantPool.Utf8Constant;
import am.ik.jvm.Opcode;
import am.ik.rontolisp.ArrayGrowth;
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
 * element {@code (i)} at {@code 1 + i}. A RANK-0 array is the empty case of the same
 * model: a zero-length {@code dims}, one data slot, and an empty fold that answers 0.
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
 * A MUTABLE CHARACTER VECTOR ({@code make-array :element-type 'character}, with or
 * without {@code :fill-pointer}/{@code :adjustable}) is the same representation holding
 * {@code java.lang.Character} elements, marked by a LENGTH-4 header {@code Object[]{dims,
 * fillPointer, adjustable, null}} ({@code _charVecMake}). Mutability is the MARKER's, not
 * the fill pointer's: with no {@code :fill-pointer} the slot stays null and the value is
 * a SIMPLE string that {@code setf char}/{@code setf aref} still write, exactly as in CL.
 * The {@code _strv} normalizer renders it into the quote-framed runtime string on demand
 * so the string consumers ({@code stringp}, {@code char}, {@code string=},
 * {@code subseq}, printing, {@code _eqv}) treat it as a string. The marker IMPLIES RANK
 * 1, which is why no reader of it checks the rank: a string is a rank-1 character array
 * and nothing else, so {@code _charVecMake} stamps it only when {@code dims} designates
 * rank 1 and lets a rank-n character request degrade to the plain general array
 * ({@code .kb/array-literals.md}).
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

	// _arrayCheckRank(arr, given): the array's own rank (its header dims length, or 1 for
	// a string, which is always a rank-1 character array) compared against `given`, the
	// subscript count the aref/%aset call site baked in at compile time. A mismatch
	// throws "aref: expected N subscripts, got M" -- the same wording
	// LispArray/LispFloatArray#flatIndex use in the interpreter
	// (.kb/adjustable-arrays.md). A match returns `arr` unchanged, so this call slots in
	// right after the array expression is evaluated, ahead of the subscripts. Never
	// called from row-major-aref/%row-major-aset, which intentionally accept any rank.
	static final String CHECK_RANK = "_arrayCheckRank";

	static final String CHECK_RANK_DESC = "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";

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

	static final String MAKE_DISPLACED_DESC = "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";

	static final String UNDISPLACE = "_arrayUndisplace";

	static final String UNDISPLACE_DESC = "(Ljava/lang/Object;)Ljava/lang/Object;";

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

	/**
	 * {@code _arrayMakeTyped(dims, init, fp, adj, code) -> Object}: {@link #MAKE} for an
	 * array that REMEMBERS the element type it was asked to hold. The code is an
	 * {@code am.ik.rontolisp.ArrayElementTypes} constant; the value it names is built
	 * once here and stored in header slot 4, which is free on every non-displaced array
	 * (slot 3, the displacement target, is what says whether slot 4 is an offset
	 * instead). A length-3 header grows to 5 for it; the length-6 PACKED header already
	 * has the slot, so a remembered element type never costs the packing.
	 */
	static final String MAKE_TYPED = "_arrayMakeTyped";

	static final String MAKE_TYPED_DESC = "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;I)Ljava/lang/Object;";

	/**
	 * {@code _arrayElementType(Object) -> Object}: the remembered element type of a
	 * general array -- header slot 4 of a non-displaced header long enough to have one --
	 * or the boolean {@code t} for everything else.
	 */
	static final String ELEMENT_TYPE = "_arrayElementType";

	/**
	 * {@code _arrayDefaultElement(o)}: the element an UNSUPPLIED slot of {@code o} takes
	 * -- its remembered element type's own zero, or null (nil) when it remembers nothing.
	 * The JVM half of {@code %array-default-element}
	 * ({@code am.ik.rontolisp.ArrayElementTypes#defaultElement}), and what
	 * {@code _vectorPushExtend} fills the slots its growth opens with.
	 */
	static final String DEFAULT_ELEMENT = "_arrayDefaultElement";

	/**
	 * {@code _arrayAdoptElementType(dst, src) -> dst}: makes the freshly built general
	 * array {@code dst} remember what {@code src} remembers. The JVM half of
	 * {@code %array-adopt-element-type}: {@code adjust-array} does not change an array's
	 * element type, so the fresh copy a NON-adjustable adjustment answers is stamped with
	 * the adjusted array's.
	 */
	static final String ADOPT_ELEMENT_TYPE = "_arrayAdoptElementType";

	static final String ADOPT_ELEMENT_TYPE_DESC = "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";

	static final String ELEMENT_TYPE_DESC = "(Ljava/lang/Object;)Ljava/lang/Object;";

	static final String DEFAULT_ELEMENT_DESC = "(Ljava/lang/Object;)Ljava/lang/Object;";

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
	 * {@code _subseqCv(Object, int, int) -> Object}: the string {@code subseq} lane
	 * answering a MUTABLE character vector, so a {@code copy-seq}/{@code subseq} result
	 * has a writable identity like the interpreter's ({@code .todo/559} step 2). A
	 * character vector or string view input copies elements through {@code _rmGet}; an
	 * immutable {@code String} slices by code point and converts once through
	 * {@code _strToCharVec}. The int {@code end} uses {@code -1} for "to the length".
	 */
	static final String SUBSEQ_CV = "_subseqCv";

	static final String SUBSEQ_CV_DESC = "(Ljava/lang/Object;II)Ljava/lang/Object;";

	/**
	 * {@code _toMutStr(Object) -> Object}: the mutable-result wrap the flipped string
	 * PRODUCERS ({@code concatenate 'string}, the case family, {@code format nil}, the
	 * string-stream capture, {@code read-line} -- {@code .todo/559}'s follow-up) finish
	 * with. A {@code String} input -- always a FRESH runtime string at those sites, never
	 * the shared literal itself -- converts once through {@code _strToCharVec} (the
	 * fill-pointer slot cleared: the result is a SIMPLE string); anything else (a
	 * character vector already, {@code format t}'s nil, an eof value) passes through
	 * untouched.
	 */
	static final String TO_MUT_STR = "_toMutStr";

	static final String TO_MUT_STR_DESC = "(Ljava/lang/Object;)Ljava/lang/Object;";

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
			VECTOR_POP, VECTOR_PUSH_EXTEND, MAKE_DISPLACED, UNDISPLACE, RM_GET, RM_SET, ARRAY_BECOME, DISP_TARGET,
			DISP_OFFSET, CHAR_VEC_MAKE, STRV, STR_TO_CHAR_VEC, SUBSEQ_CV, TO_MUT_STR, WIDEN, MAKE_TYPED, ELEMENT_TYPE,
			DEFAULT_ELEMENT, ADOPT_ELEMENT_TYPE, CHECK_RANK);

	/** An array helper method body ready to be emitted into the generated class. */
	record ArrayMethod(Utf8Constant name, Utf8Constant desc, int maxStack, int maxLocals, List<Integer> code) {
	}

	private JvmArrayRuntimeBuilder() {
	}

	static List<ArrayMethod> build(ConstantPool cp, ClassConstant objectClass, ClassConstant objectArrayClass,
			ClassConstant selfClass, boolean usesFloatArray) {
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
		MethodrefConstant undisplace = cp.addMethodref(selfClass,
				cp.addNameAndType(cp.addUtf8(UNDISPLACE), cp.addUtf8(UNDISPLACE_DESC)));
		MethodrefConstant widen = cp.addMethodref(selfClass,
				cp.addNameAndType(cp.addUtf8(WIDEN), cp.addUtf8(WIDEN_DESC)));
		MethodrefConstant defaultElement = cp.addMethodref(selfClass,
				cp.addNameAndType(cp.addUtf8(DEFAULT_ELEMENT), cp.addUtf8(DEFAULT_ELEMENT_DESC)));
		ClassConstant longArrayClass = cp.addClass(cp.addUtf8("[J"));
		MethodrefConstant longLongValue = cp.addMethodref(longClass,
				cp.addNameAndType(cp.addUtf8("longValue"), cp.addUtf8("()J")));
		// The PACKED displacement targets: a packed integer vector is a long[]{width,
		// e0, ...} and a packed float array a double[]/float[]{rank, dims..., e0, ...}.
		// A view over one is an ordinary displaced header whose slot 3 holds that array
		// instead of an ArrayList or a String, so the walk ends on it exactly as it ends
		// on a string view's target.
		ClassConstant doubleArrayClass = cp.addClass(cp.addUtf8("[D"));
		ClassConstant floatArrayClass = cp.addClass(cp.addUtf8("[F"));
		ClassConstant shortArrayClass = cp.addClass(cp.addUtf8("[S"));
		// The bfloat16 conversion pair the _fv* tier emits (JvmFloatArrayRuntimeBuilder),
		// referenced only when that tier is emitted: a short[] cannot exist otherwise,
		// and the compiler refuses a class that calls an own method it does not declare.
		// The short[] arms of the displaced-view helpers are emitted under the same gate.
		MethodrefConstant bf16Value = usesFloatArray
				? cp.addMethodref(selfClass, cp.addNameAndType(cp.addUtf8(JvmFloatArrayRuntimeBuilder.BF16_VALUE),
						cp.addUtf8(JvmFloatArrayRuntimeBuilder.BF16_VALUE_DESC)))
				: null;
		MethodrefConstant bf16Bits = usesFloatArray
				? cp.addMethodref(selfClass, cp.addNameAndType(cp.addUtf8(JvmFloatArrayRuntimeBuilder.BF16_BITS),
						cp.addUtf8(JvmFloatArrayRuntimeBuilder.BF16_BITS_DESC)))
				: null;
		ClassConstant numberClass = cp.addClass(cp.addUtf8("java/lang/Number"));
		MethodrefConstant numberLongValue = cp.addMethodref(numberClass,
				cp.addNameAndType(cp.addUtf8("longValue"), cp.addUtf8("()J")));
		MethodrefConstant numberDoubleValue = cp.addMethodref(numberClass,
				cp.addNameAndType(cp.addUtf8("doubleValue"), cp.addUtf8("()D")));
		ClassConstant doubleBoxClass = cp.addClass(cp.addUtf8("java/lang/Double"));
		MethodrefConstant doubleBoxValueOf = cp.addMethodref(doubleBoxClass,
				cp.addNameAndType(cp.addUtf8("valueOf"), cp.addUtf8("(D)Ljava/lang/Double;")));
		// The shared numeric coercion the packed float accessors already use: any
		// numeric value (Long, BigInteger, ratio, Double) as a Double.
		MethodrefConstant dblCoerce = cp.addMethodref(selfClass, cp.addNameAndType(
				cp.addUtf8(JvmNumericRuntimeBuilder.DBL), cp.addUtf8("(Ljava/lang/Object;)Ljava/lang/Object;")));
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
		emitResolveFillPointer(m, longClass, longIntValue, rtExClass, rtExInit, cp, fp, dimsArr, total, fpVal, v);
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
		// A runtime string carries no header at all, but it IS a rank-1 character array:
		// its dimensions are the one-element list of its length in code points. Every
		// other shape reader -- array-rank, array-dimension, array-total-size,
		// array-row-major-index -- expands through array-dimensions, so this one arm is
		// what lets all of them accept a string, as the interpreter's do.
		int dNotString = d.label();
		d.aload(dArr);
		d.instanceOf(strClass);
		d.branch(Opcode.IFEQ, dNotString);
		d.iconst(2);
		d.anewarray(objectClass);
		d.dup();
		d.iconst(0);
		d.aload(dArr);
		d.checkcast(strClass);
		d.invokestatic(strCount);
		d.op(Opcode.I2L);
		d.invokestatic(longValueOf);
		d.aastore();
		d.areturn();
		d.bind(dNotString);
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

		// _arrayCheckRank(arr, given): rank = 1 for a string, else the length of the
		// header's boxed dims (the same derivation DIMS uses, without building the cons
		// list). A mismatch against `given` throws; a match returns `arr` unchanged.
		// Locals: 0=arr, 1=given, 2=rank, 3=dims (Object[]), 4=giv.
		ClassConstant crSbClass = cp.addClass(cp.addUtf8("java/lang/StringBuilder"));
		MethodrefConstant crSbInit = cp.addMethodref(crSbClass,
				cp.addNameAndType(cp.addUtf8("<init>"), cp.addUtf8("()V")));
		MethodrefConstant crSbAppendStr = cp.addMethodref(crSbClass,
				cp.addNameAndType(cp.addUtf8("append"), cp.addUtf8("(Ljava/lang/String;)Ljava/lang/StringBuilder;")));
		MethodrefConstant crSbAppendInt = cp.addMethodref(crSbClass,
				cp.addNameAndType(cp.addUtf8("append"), cp.addUtf8("(I)Ljava/lang/StringBuilder;")));
		MethodrefConstant crSbToString = cp.addMethodref(crSbClass,
				cp.addNameAndType(cp.addUtf8("toString"), cp.addUtf8("()Ljava/lang/String;")));
		int crArr = 0, crGiven = 1, crRank = 2, crDims = 3, crGiv = 4;
		JvmAsm cr = new JvmAsm();
		int crNotString = cr.label();
		int crHaveRank = cr.label();
		cr.aload(crArr);
		cr.instanceOf(strClass);
		cr.branch(Opcode.IFEQ, crNotString);
		cr.iconst(1);
		cr.istore(crRank);
		cr.branch(Opcode.GOTO, crHaveRank);
		cr.bind(crNotString);
		cr.aload(crArr);
		cr.checkcast(arrayListClass);
		cr.iconst(0);
		cr.invokevirtual(alGet);
		cr.checkcast(objectArrayClass);
		cr.iconst(0);
		cr.aaload();
		cr.checkcast(objectArrayClass);
		cr.astore(crDims);
		cr.aload(crDims);
		cr.arraylength();
		cr.istore(crRank);
		cr.bind(crHaveRank);
		emitRankCheckAndReturn(cp, cr, longClass, longIntValue, crSbClass, crSbInit, crSbAppendStr, crSbAppendInt,
				crSbToString, rtExClass, rtExInit, crArr, crGiven, crRank, crGiv);
		methods.add(new ArrayMethod(cp.addUtf8(CHECK_RANK), cp.addUtf8(CHECK_RANK_DESC), 6, 5, cr.finish()));

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
		emitStoreAtFillPointerAndAdvance(vp, rmSet, longValueOf, 0, 1, 2, 3);
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
		// _rmGet(arr, 1 + (fp - 1)) == _rmGet(arr, fp) -- through the displacement-aware
		// primitive, so a displaced fill-pointered view pops its TARGET's element.
		vpop.aload(0);
		vpop.iload(2);
		vpop.invokestatic(rmGet);
		vpop.areturn();
		methods.add(new ArrayMethod(cp.addUtf8(VECTOR_POP), cp.addUtf8(VECTOR_POP_DESC), 6, 3, vpop.finish()));

		// _vectorPushExtend(val, arr, ext): like _vectorPush but grows the backing store
		// when the vector is full, updating the stored dimension size. ext is the shared
		// "not supplied" sentinel (ArrayGrowth.NO_EXTENSION) when the optional argument
		// was omitted. Locals: 0 = val, 1 = arr, 2 = ext, 3 = header,
		// 4 = fp (int), 5 = cap (int), 6 = ext (int), 7 = newCap (int), 8 = the fill.
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
		// A full DISPLACED view stops being a view first: its elements move into storage
		// of its own and the displacement is dropped, so the growth below extends that
		// storage instead of running past the end of the target (SBCL 2.2.9 does the
		// same, and array-displacement answers nil from here on). The header object is
		// REPLACED, so reload it before the fill-pointer store reads slot 1.
		vpe.aload(1);
		vpe.invokestatic(undisplace);
		vpe.pop();
		emitLoadHeader(vpe, arrayListClass, objectArrayClass, alGet, 1);
		vpe.astore(3);
		// The shared growth policy, spelled out in bytecode (am.ik.rontolisp.ArrayGrowth,
		// which generated code cannot call): a supplied extension is added verbatim, and
		// otherwise the capacity doubles, off a floor for the zero-capacity vector.
		vpe.aload(2);
		vpe.checkcast(longClass);
		vpe.invokevirtual(longIntValue);
		vpe.istore(6);
		int vpeDefaultGrowth = vpe.label();
		int vpeDoubleCap = vpe.label();
		int vpeCapReady = vpe.label();
		vpe.iload(6);
		vpe.iconst(ArrayGrowth.NO_EXTENSION);
		vpe.branch(Opcode.IF_ICMPLE, vpeDefaultGrowth);
		vpe.iload(5);
		vpe.iload(6);
		vpe.op(Opcode.IADD);
		vpe.istore(7);
		vpe.branch(Opcode.GOTO, vpeCapReady);
		vpe.bind(vpeDefaultGrowth);
		vpe.iload(5);
		vpe.iconst(ArrayGrowth.MIN_CAPACITY);
		vpe.branch(Opcode.IF_ICMPGE, vpeDoubleCap);
		vpe.iconst(ArrayGrowth.MIN_CAPACITY);
		vpe.istore(7);
		vpe.branch(Opcode.GOTO, vpeCapReady);
		vpe.bind(vpeDoubleCap);
		vpe.iload(5);
		vpe.iconst(ArrayGrowth.GROWTH_FACTOR);
		vpe.op(Opcode.IMUL);
		vpe.istore(7);
		vpe.bind(vpeCapReady);
		// while (list.size() - 1 < newCap) list.add(_arrayDefaultElement(arr)) -- the
		// slots the growth OPENS take the REMEMBERED element type's own zero, the same
		// fill make-array gives an unsupplied element, so a vector asked to hold
		// characters, bytes or floats never reads back nil above its old capacity (it is
		// null, i.e. nil, for the general vector, which is what this always added).
		// Local 8 holds it: one call, not one per opened slot.
		vpe.aload(1);
		vpe.invokestatic(defaultElement);
		vpe.astore(8);
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
		vpe.aload(8);
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
		emitStoreAtFillPointerAndAdvance(vpe, rmSet, longValueOf, 0, 1, 3, 4);
		methods.add(new ArrayMethod(cp.addUtf8(VECTOR_PUSH_EXTEND), cp.addUtf8(VECTOR_PUSH_EXTEND_DESC), 6, 9,
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
		// The NON-ARRAY target arm: header[3] is what the view aliases -- a PACKED
		// vector (the elements live unboxed in it) or the immutable runtime string a
		// string view aliases.
		rg.bind(rgString);
		int rgRealString = rg.label();
		rg.aload(2);
		rg.iconst(3);
		rg.aaload();
		rg.astore(7);
		emitPackedTargetGet(rg, 7, 1, longArrayClass, doubleArrayClass, floatArrayClass, shortArrayClass, bf16Value,
				longValueOf, doubleBoxValueOf, rgRealString);
		// The string-view arm: header[3] is the immutable runtime string this view
		// aliases and idx is the 1-based character index into it. Reads by CODE POINT
		// through _cpoff (the content lives in [1, length-1), inside the framing
		// quotes) and boxes as the runtime CHARACTER int[]{cp}, exactly like _aref1's
		// own string branch.
		rg.bind(rgRealString);
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
		methods.add(new ArrayMethod(cp.addUtf8(RM_GET), cp.addUtf8(RM_GET_DESC), 6, 8, rg.finish()));

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
		// The NON-ARRAY target arm: a PACKED vector takes the store into its own
		// unboxed slot (masked to the element width for an integer vector, narrowed to
		// the backing width for a float array, and answering the value AS STORED --
		// which is what a store straight into the target answers).
		rs.bind(rsString);
		int rsRealString = rs.label();
		rs.aload(3);
		rs.iconst(3);
		rs.aaload();
		rs.astore(7);
		emitPackedTargetSet(rs, cp, 7, 1, 2, 8, 9, 10, 11, 12, 14, longArrayClass, doubleArrayClass, floatArrayClass,
				shortArrayClass, bf16Value, bf16Bits, numberClass, numberLongValue, numberDoubleValue, longValueOf,
				doubleBoxValueOf, dblCoerce, rtExClass, rtExInit, rsRealString);
		// The string-view arm: the view aliases an IMMUTABLE runtime string, which no
		// write can reach. Promote it once -- header[3] becomes a mutable character
		// vector holding the same characters -- and store into that; every later access
		// through this view (and through array-displacement's answer) sees the promoted
		// vector, so the view behaves as a mutable string from here on.
		rs.bind(rsRealString);
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
		methods.add(new ArrayMethod(cp.addUtf8(RM_SET), cp.addUtf8(RM_SET_DESC), 8, 16, rs.finish()));

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
		// the boxed shape IN PLACE -- header replaced by {dims, null, null, null, et},
		// each long[] element appended boxed (the sentinel as
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
		// list.set(0, new Object[]{header[0], null, null, null, header[4]}) -- the
		// REMEMBERED element type (slot 4) survives the widening, so an array that was
		// asked for (unsigned-byte 8) still answers it after a store widened it.
		wd.aload(0);
		wd.checkcast(arrayListClass);
		wd.iconst(0);
		wd.iconst(5);
		wd.anewarray(objectClass);
		wd.dup();
		wd.iconst(0);
		wd.aload(1);
		wd.iconst(0);
		wd.aaload();
		wd.aastore();
		wd.dup();
		wd.iconst(4);
		wd.aload(1);
		wd.iconst(4);
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

		// _arrayMakeDisplaced(dims, target, offset, fp, adj): a displaced view -- a fresh
		// ArrayList holding ONLY the 5-element header {dimsArr, fp, adj, target,
		// offsetLong}; the view is bounds-checked against the target's total size.
		// Slots 1 and 2 are the SAME fill-pointer / adjustable slots an ordinary header
		// carries, so every reader of them (_fillPointer, _arrayHasFillPointer,
		// _adjustableArrayP, _length, the printer) answers for a view unchanged: CLHS
		// forbids only :initial-element beside :displaced-to.
		// Locals: 0 = dims, 1 = target, 2 = offsetArg, 3 = fpArg, 4 = adjArg, 5 = list,
		// 6 = total, 7 = dimsArr, 8 = idx, 9 = cur, 10 = n, 11 = off (int),
		// 12 = targetHeader, 13 = targetTotal (product scratch), 14 = m (product
		// scratch), 15 = headerSize, 16 = fpVal, 17 = fp scratch (int).
		JvmAsm md = new JvmAsm();
		int mdDims = 0, mdTarget = 1, mdOffset = 2, mdFp = 3, mdAdj = 4, mdList = 5, mdTotal = 6, mdDimsArr = 7,
				mdIdx = 8, mdCur = 9, mdN = 10, mdOff = 11, mdTargetHeader = 12, mdProduct = 13, mdM = 14,
				mdHeaderSize = 15, mdFpVal = 16, mdFpScratch = 17;
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
		int mdIv = md.label();
		int mdDv = md.label();
		int mdFv = md.label();
		int mdBv = md.label();
		md.iconst(5);
		md.istore(mdHeaderSize);
		md.aload(mdTarget);
		md.instanceOf(strClass);
		md.branch(Opcode.IFNE, mdStr);
		// A PACKED target's element count is its representation's own: an integer
		// vector is long[]{width, e0, ...} (length - 1 elements) and a float array
		// double[]/float[]{rank, dims..., e0, ...} (length - 1 - rank). The view over
		// one is a plain length-5 array view -- only a STRING target makes a string
		// view.
		md.aload(mdTarget);
		md.instanceOf(longArrayClass);
		md.branch(Opcode.IFNE, mdIv);
		md.aload(mdTarget);
		md.instanceOf(doubleArrayClass);
		md.branch(Opcode.IFNE, mdDv);
		md.aload(mdTarget);
		md.instanceOf(floatArrayClass);
		md.branch(Opcode.IFNE, mdFv);
		md.aload(mdTarget);
		md.instanceOf(shortArrayClass);
		md.branch(Opcode.IFNE, mdBv);
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
		md.bind(mdIv);
		md.aload(mdTarget);
		md.checkcast(longArrayClass);
		md.arraylength();
		md.iconst(1);
		md.op(Opcode.ISUB);
		md.istore(mdProduct);
		md.branch(Opcode.GOTO, mdHaveTotal);
		md.bind(mdDv);
		md.aload(mdTarget);
		md.checkcast(doubleArrayClass);
		md.arraylength();
		md.aload(mdTarget);
		md.checkcast(doubleArrayClass);
		md.iconst(0);
		md.daload();
		md.d2i();
		md.iconst(1);
		md.op(Opcode.IADD);
		md.op(Opcode.ISUB);
		md.istore(mdProduct);
		md.branch(Opcode.GOTO, mdHaveTotal);
		md.bind(mdFv);
		md.aload(mdTarget);
		md.checkcast(floatArrayClass);
		md.arraylength();
		md.aload(mdTarget);
		md.checkcast(floatArrayClass);
		md.iconst(0);
		md.faload();
		md.f2i();
		md.iconst(1);
		md.op(Opcode.IADD);
		md.op(Opcode.ISUB);
		md.istore(mdProduct);
		md.branch(Opcode.GOTO, mdHaveTotal);
		// A bfloat16 target: length - (1 + 2 * rank), the two-slot header
		// (JvmPackedFloatWidth.BFLOAT16 owns the offset).
		md.bind(mdBv);
		md.aload(mdTarget);
		md.checkcast(shortArrayClass);
		md.arraylength();
		md.aload(mdTarget);
		md.checkcast(shortArrayClass);
		JvmPackedFloatWidth.BFLOAT16.loadRank(md);
		JvmPackedFloatWidth.BFLOAT16.emitDataOffset(md);
		md.op(Opcode.ISUB);
		md.istore(mdProduct);
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
		// The fill pointer is resolved against the VIEW's own element count, by the same
		// rule _arrayMake uses (null / range-checked Long / t -> the size).
		emitResolveFillPointer(md, longClass, longIntValue, rtExClass, rtExInit, cp, mdFp, mdDimsArr, mdTotal, mdFpVal,
				mdFpScratch);
		// list.add(new Object[headerSize]{dimsArr, fpVal, adj, target,
		// Long.valueOf(off)})
		md.aload(mdList);
		md.iload(mdHeaderSize);
		md.anewarray(objectClass);
		md.dup();
		md.iconst(0);
		md.aload(mdDimsArr);
		md.aastore();
		md.dup();
		md.iconst(1);
		md.aload(mdFpVal);
		md.aastore();
		md.dup();
		md.iconst(2);
		md.aload(mdAdj);
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
		methods.add(new ArrayMethod(cp.addUtf8(MAKE_DISPLACED), cp.addUtf8(MAKE_DISPLACED_DESC), 6, 18, md.finish()));

		// _arrayUndisplace(arr): copy a displaced view's CURRENT contents into data slots
		// of its own and drop the displacement, keeping the dims, the fill pointer and
		// the adjustable flag; returns arr. A non-displaced array is returned untouched.
		// The header LENGTH carries the shape, so the new one is 4 (the character-vector
		// marker) where the old was 7 (a displaced STRING view), 5 where the chain
		// REMEMBERED an element type (carried into slot 4, the slot the offset was
		// spending) and 3 otherwise -- a grown string view stays a string, and
		// array-element-type answers what it answered while the view was still a view.
		// Called by _vectorPushExtend when a full view has to grow: the growth then
		// extends storage of its own instead of running off the end of someone else's
		// array, which is what SBCL 2.2.9 does.
		// Locals: 0 = arr, 1 = header, 2 = total, 3 = elems, 4 = i, 5 = newHeader,
		// 6 = product scratch, 7 = walk cursor, 8 = et, 9 = target scratch.
		JvmAsm un = new JvmAsm();
		// An immutable string owns its characters and carries no header at all (a
		// string VIEW is a length-7 header, not a String), so it is returned unchanged
		// like any other undisplaced array -- adjust-array's expansion calls this
		// unconditionally, on every representation it accepts. Mirrors
		// _arrayDispTarget's same check.
		int unNotString = un.label();
		un.aload(0);
		un.instanceOf(strClass);
		un.branch(Opcode.IFEQ, unNotString);
		un.aload(0);
		un.areturn();
		un.bind(unNotString);
		emitLoadHeader(un, arrayListClass, objectArrayClass, alGet, 0);
		un.astore(1);
		int unDisplaced = un.label();
		un.aload(1);
		un.arraylength();
		un.iconst(4);
		un.branch(Opcode.IF_ICMPGE, unDisplaced);
		un.aload(0);
		un.areturn();
		un.bind(unDisplaced);
		int unGo = un.label();
		un.aload(1);
		un.iconst(3);
		un.aaload();
		un.branch(Opcode.IFNONNULL, unGo);
		un.aload(0);
		un.areturn();
		un.bind(unGo);
		emitDimsProduct(un, longClass, objectArrayClass, longIntValue, 1, 2, 6);
		un.istore(2);
		// elems[i] = _rmGet(arr, 1 + i) -- read through the chain BEFORE the header is
		// replaced, since that is what the reads resolve against.
		un.iload(2);
		un.anewarray(objectClass);
		un.astore(3);
		un.iconst(0);
		un.istore(4);
		int unRead = un.label();
		int unReadDone = un.label();
		un.bind(unRead);
		un.iload(4);
		un.iload(2);
		un.branch(Opcode.IF_ICMPGE, unReadDone);
		un.aload(3);
		un.iload(4);
		un.aload(0);
		un.iconst(1);
		un.iload(4);
		un.op(Opcode.IADD);
		un.invokestatic(rmGet);
		un.aastore();
		un.iinc(4, 1);
		un.branch(Opcode.GOTO, unRead);
		un.bind(unReadDone);
		// et: the element type the CHAIN END remembers, read exactly as
		// _arrayElementType reads it (it hops the same way and stops on the same facts).
		// The view answered this while it was still a view, so the freed offset slot has
		// to keep answering it -- an array's element type is fixed when it is made.
		int unWalk = un.label();
		int unWalkDone = un.label();
		int unWalkOwn = un.label();
		un.aconstNull();
		un.astore(8);
		un.aload(1);
		un.astore(7);
		un.bind(unWalk);
		un.aload(7);
		un.arraylength();
		un.iconst(4);
		un.branch(Opcode.IF_ICMPLE, unWalkDone);
		un.aload(7);
		un.iconst(3);
		un.aaload();
		un.astore(9);
		un.aload(9);
		un.branch(Opcode.IFNULL, unWalkOwn);
		// A PACKED chain end's element type IS its representation, so the freed offset
		// slot records it exactly as it records a general array's remembered label: the
		// view answered it while it was a view, and an array's element type is fixed
		// when it is made.
		int unNotPacked = un.label();
		emitPackedElementTypeInto(un, cp, 9, 8, longArrayClass, doubleArrayClass, floatArrayClass, shortArrayClass,
				longValueOf, objectClass, unNotPacked, unWalkDone);
		un.bind(unNotPacked);
		// A String chain end remembers nothing here: character-ness is the length-7
		// header's own answer (7 -> 4 below), not a remembered designator.
		un.aload(9);
		un.instanceOf(arrayListClass);
		un.branch(Opcode.IFEQ, unWalkDone);
		emitLoadHeader(un, arrayListClass, objectArrayClass, alGet, 9);
		un.astore(7);
		un.branch(Opcode.GOTO, unWalk);
		un.bind(unWalkOwn);
		un.aload(7);
		un.iconst(4);
		un.aaload();
		un.astore(8);
		un.bind(unWalkDone);
		int unString = un.label();
		int unThree = un.label();
		int unLenReady = un.label();
		int unNoEt = un.label();
		un.aload(1);
		un.arraylength();
		un.iconst(7);
		un.branch(Opcode.IF_ICMPEQ, unString);
		un.aload(8);
		un.branch(Opcode.IFNULL, unThree);
		un.iconst(5);
		un.branch(Opcode.GOTO, unLenReady);
		un.bind(unThree);
		un.iconst(3);
		un.branch(Opcode.GOTO, unLenReady);
		un.bind(unString);
		un.iconst(4);
		un.bind(unLenReady);
		un.anewarray(objectClass);
		emitCopyHeaderSlots(un, 1, 3);
		un.astore(5);
		un.aload(5);
		un.arraylength();
		un.iconst(5);
		un.branch(Opcode.IF_ICMPNE, unNoEt);
		un.aload(5);
		un.iconst(4);
		un.aload(8);
		un.aastore();
		un.bind(unNoEt);
		un.aload(0);
		un.checkcast(arrayListClass);
		un.iconst(0);
		un.aload(5);
		un.invokevirtual(alSet);
		un.pop();
		un.iconst(0);
		un.istore(4);
		int unFill = un.label();
		int unFillDone = un.label();
		un.bind(unFill);
		un.iload(4);
		un.iload(2);
		un.branch(Opcode.IF_ICMPGE, unFillDone);
		un.aload(0);
		un.checkcast(arrayListClass);
		un.aload(3);
		un.iload(4);
		un.aaload();
		un.invokevirtual(alAdd);
		un.pop();
		un.iinc(4, 1);
		un.branch(Opcode.GOTO, unFill);
		un.bind(unFillDone);
		un.aload(0);
		un.areturn();
		methods.add(new ArrayMethod(cp.addUtf8(UNDISPLACE), cp.addUtf8(UNDISPLACE_DESC), 9, 10, un.finish()));

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
		int dtNil = dt.label();
		// A runtime string owns its storage and carries no header (a string VIEW is a
		// length-7 header, not a String), so it answers nil like any other undisplaced
		// array.
		dt.aload(0);
		dt.instanceOf(strClass);
		dt.branch(Opcode.IFNE, dtNil);
		emitLoadHeader(dt, arrayListClass, objectArrayClass, alGet, 0);
		dt.astore(1);
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
		int dofsNone = dofs.label();
		dofs.aload(0);
		dofs.instanceOf(strClass);
		dofs.branch(Opcode.IFNE, dofsNone);
		emitLoadHeader(dofs, arrayListClass, objectArrayClass, alGet, 0);
		dofs.astore(1);
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
		//
		// The marker MEANS "a rank-1 character array", i.e. a string, so it is stamped
		// only when dims designates rank 1 (a Long, or a one-element cons list) -- the
		// rank is a runtime fact. Above rank 1 a character element type selects no
		// representation of its own: the value is the plain general array, without the
		// marker and without a fill pointer (which rank-n _arrayMake would reject
		// anyway). Same runtime rank test, same fallback, as _ivMake.
		MethodrefConstant selfArrayMake = cp.addMethodref(selfClass,
				cp.addNameAndType(cp.addUtf8(MAKE), cp.addUtf8(MAKE_DESC)));
		MethodrefConstant selfMakeTyped = cp.addMethodref(selfClass,
				cp.addNameAndType(cp.addUtf8(MAKE_TYPED), cp.addUtf8(MAKE_TYPED_DESC)));
		JvmAsm cv = new JvmAsm();
		int cvRank1 = cv.label();
		int cvTryList = cv.label();
		int cvGeneral = cv.label();
		cv.aload(0);
		cv.instanceOf(longClass);
		cv.branch(Opcode.IFEQ, cvTryList);
		cv.branch(Opcode.GOTO, cvRank1);
		cv.bind(cvTryList);
		cv.aload(0);
		cv.instanceOf(objectArrayClass);
		cv.branch(Opcode.IFEQ, cvGeneral);
		cv.aload(0);
		cv.checkcast(objectArrayClass);
		cv.iconst(1);
		cv.aaload();
		cv.branch(Opcode.IFNONNULL, cvGeneral);
		cv.bind(cvRank1);
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
		// rank n: the general boxed representation, with no fill pointer (the defaulted
		// one is the rank-1 marker's, not the program's) -- but REMEMBERING that the
		// element type asked for was character, which is the only trace it leaves above
		// rank 1.
		cv.bind(cvGeneral);
		cv.aload(0);
		cv.aload(1);
		cv.aconstNull();
		cv.aload(3);
		cv.iconst(am.ik.rontolisp.ArrayElementTypes.CHARACTER);
		cv.invokestatic(selfMakeTyped);
		cv.areturn();
		methods.add(new ArrayMethod(cp.addUtf8(CHAR_VEC_MAKE), cp.addUtf8(MAKE_DESC), 5, 7, cv.finish()));

		// _arrayMakeTyped(dims, init, fp, adj, code): _arrayMake plus the REMEMBERED
		// element type in header slot 4. That slot is free on every non-displaced array
		// -- slot 3 (the displacement target) is what says whether slot 4 holds an
		// offset instead -- so the packed length-6 header takes the type without giving
		// up its long[], and only the ordinary length-3 header has to grow to 5.
		// Locals: 0..3 = the _arrayMake arguments, 4 = code (int), 5 = list, 6 = header,
		// 7 = et.
		JvmAsm mt = new JvmAsm();
		int mtHaveEt = mt.label();
		int mtGrow = mt.label();
		mt.aload(0);
		mt.aload(1);
		mt.aload(2);
		mt.aload(3);
		mt.invokestatic(selfArrayMake);
		mt.astore(5);
		emitElementTypeForCode(mt, cp, objectClass, longValueOf, 4, 7, mtHaveEt);
		mt.bind(mtHaveEt);
		emitLoadHeader(mt, arrayListClass, objectArrayClass, alGet, 5);
		mt.astore(6);
		mt.aload(6);
		mt.arraylength();
		mt.iconst(5);
		mt.branch(Opcode.IF_ICMPLT, mtGrow);
		mt.aload(6);
		mt.iconst(4);
		mt.aload(7);
		mt.aastore();
		mt.aload(5);
		mt.areturn();
		// list.set(0, new Object[]{header[0], header[1], header[2], null, et})
		mt.bind(mtGrow);
		mt.aload(5);
		mt.checkcast(arrayListClass);
		mt.iconst(0);
		mt.iconst(5);
		mt.anewarray(objectClass);
		for (int slot = 0; slot < 3; slot++) {
			mt.dup();
			mt.iconst(slot);
			mt.aload(6);
			mt.iconst(slot);
			mt.aaload();
			mt.aastore();
		}
		mt.dup();
		mt.iconst(4);
		mt.aload(7);
		mt.aastore();
		mt.invokevirtual(alSet);
		mt.pop();
		mt.aload(5);
		mt.areturn();
		methods.add(new ArrayMethod(cp.addUtf8(MAKE_TYPED), cp.addUtf8(MAKE_TYPED_DESC), 9, 8, mt.finish()));

		// _arrayElementType(o): the remembered element type, or the boolean t. A
		// DISPLACED array HOPS: slot 4 is its offset, not a type, and a view owns no
		// storage -- its elements are the target's, so its element type is the target's,
		// resolved through the whole chain. CL requires the two to be type-equivalent
		// anyway (make-array, :displaced-to), so the chain end's remembered type IS the
		// view's declared :element-type in every program a conforming implementation
		// accepts. A chain that ends on a String never reaches here: the caller's
		// stringp arm answers character for a string view first.
		// Locals: 0 = o (re-assigned by the hop), 1 = header, 2 = scratch.
		JvmAsm aet = new JvmAsm();
		int aetT = aet.label();
		int aetTop = aet.label();
		int aetOwn = aet.label();
		int aetNotPacked = aet.label();
		int aetPackedDone = aet.label();
		aet.bind(aetTop);
		// The hop may land on a PACKED target, whose element type is its representation
		// rather than a remembered label -- the same answer read a different way.
		emitPackedElementTypeInto(aet, cp, 0, 2, longArrayClass, doubleArrayClass, floatArrayClass, shortArrayClass,
				longValueOf, objectClass, aetNotPacked, aetPackedDone);
		aet.bind(aetPackedDone);
		aet.aload(2);
		aet.areturn();
		aet.bind(aetNotPacked);
		aet.aload(0);
		aet.instanceOf(arrayListClass);
		aet.branch(Opcode.IFEQ, aetT);
		emitLoadHeader(aet, arrayListClass, objectArrayClass, alGet, 0);
		aet.astore(1);
		aet.aload(1);
		aet.arraylength();
		aet.iconst(4);
		aet.branch(Opcode.IF_ICMPLE, aetT);
		aet.aload(1);
		aet.iconst(3);
		aet.aaload();
		aet.astore(2);
		aet.aload(2);
		aet.branch(Opcode.IFNULL, aetOwn);
		aet.aload(2);
		aet.astore(0);
		aet.branch(Opcode.GOTO, aetTop);
		aet.bind(aetOwn);
		aet.aload(1);
		aet.iconst(4);
		aet.aaload();
		aet.astore(2);
		aet.aload(2);
		aet.branch(Opcode.IFNULL, aetT);
		aet.aload(2);
		aet.areturn();
		aet.bind(aetT);
		aet.ldcString(cp.addString("T"));
		aet.areturn();
		methods.add(new ArrayMethod(cp.addUtf8(ELEMENT_TYPE), cp.addUtf8(ELEMENT_TYPE_DESC), 9, 3, aet.finish()));

		// _arrayDefaultElement(o): the element an UNSUPPLIED slot of o takes -- the
		// remembered element type's own zero -- or null (nil) when nothing is remembered.
		// Keyed off the SAME header facts _arrayElementType reads, in the same order: a
		// length-4 header is the character vector marker (the one specialized type that
		// spends no slot 4), a displaced or string-view header (slot 3 non-null) and a
		// plain length-3 one remember nothing, and otherwise slot 4 holds the element
		// type VALUE -- an Object[] cons for (unsigned-byte n), the name string
		// otherwise. Mirrors am.ik.rontolisp.ArrayElementTypes.defaultElement.
		// Locals: 0 = o, 1 = header, 2 = et.
		ClassConstant stringClass = cp.addClass(cp.addUtf8("java/lang/String"));
		MethodrefConstant stringEquals = cp.addMethodref(stringClass,
				cp.addNameAndType(cp.addUtf8("equals"), cp.addUtf8("(Ljava/lang/Object;)Z")));
		ClassConstant doubleClass = cp.addClass(cp.addUtf8("java/lang/Double"));
		MethodrefConstant doubleValueOf = cp.addMethodref(doubleClass,
				cp.addNameAndType(cp.addUtf8("valueOf"), cp.addUtf8("(D)Ljava/lang/Double;")));
		JvmAsm de = new JvmAsm();
		int deNil = de.label();
		int deChar = de.label();
		int deInt = de.label();
		// A runtime string IS a rank-1 character array, so it answers the character zero
		// even though it carries no header at all.
		de.aload(0);
		de.instanceOf(stringClass);
		de.branch(Opcode.IFNE, deChar);
		de.aload(0);
		de.instanceOf(arrayListClass);
		de.branch(Opcode.IFEQ, deNil);
		emitLoadHeader(de, arrayListClass, objectArrayClass, alGet, 0);
		de.astore(1);
		de.aload(1);
		de.arraylength();
		de.iconst(4);
		de.branch(Opcode.IF_ICMPEQ, deChar);
		de.aload(1);
		de.arraylength();
		de.iconst(5);
		de.branch(Opcode.IF_ICMPLT, deNil);
		de.aload(1);
		de.iconst(3);
		de.aaload();
		de.branch(Opcode.IFNONNULL, deNil);
		de.aload(1);
		de.iconst(4);
		de.aaload();
		de.astore(2);
		de.aload(2);
		de.branch(Opcode.IFNULL, deNil);
		de.aload(2);
		de.instanceOf(objectArrayClass);
		de.branch(Opcode.IFNE, deInt);
		de.ldcString(cp.addString(am.ik.rontolisp.LispNames.CHARACTER_TYPE));
		de.aload(2);
		de.invokevirtual(stringEquals);
		de.branch(Opcode.IFNE, deChar);
		// The only remaining remembered names are the two float widths.
		de.op(Opcode.DCONST_0);
		de.invokestatic(doubleValueOf);
		de.areturn();
		// A runtime character is a length-1 int[] holding the code point.
		de.bind(deChar);
		de.iconst(1);
		de.newarrayInt();
		de.dup();
		de.iconst(0);
		de.iconst(am.ik.rontolisp.ArrayElementTypes.DEFAULT_CHARACTER);
		de.iastore();
		de.areturn();
		de.bind(deInt);
		de.op(Opcode.LCONST_0);
		de.invokestatic(longValueOf);
		de.areturn();
		de.bind(deNil);
		de.aconstNull();
		de.areturn();
		methods.add(new ArrayMethod(cp.addUtf8(DEFAULT_ELEMENT), cp.addUtf8(DEFAULT_ELEMENT_DESC), 4, 3, de.finish()));

		// _arrayAdoptElementType(dst, src): make the freshly built general array dst
		// remember what src remembers, and return dst. adjust-array does not change an
		// array's element type, and a NON-adjustable adjustment answers a fresh array,
		// so the copy has to be stamped with the original's type -- which is one header
		// word here, not a re-run of make-array's representation choice.
		//
		// The stamp takes the SAME two shapes the allocator's do: the CHARACTER type of
		// a RANK-1 array is the length-4 header marker (over boxed data, hence the
		// widen), and every other remembered type is header slot 4, which a length-3
		// header grows to hold and a packed length-6 one already has. A dst that is
		// already a character vector, or is displaced (slot 3 non-null, where slot 4 is
		// the offset), keeps what it has. Locals: 0 = dst, 1 = src, 2 = et, 3 = header,
		// 4 = src's header.
		MethodrefConstant selfElementType = cp.addMethodref(selfClass,
				cp.addNameAndType(cp.addUtf8(ELEMENT_TYPE), cp.addUtf8(ELEMENT_TYPE_DESC)));
		am.ik.jvm.ConstantPool.StringConstant characterName = cp.addString(am.ik.rontolisp.LispNames.CHARACTER_TYPE);
		JvmAsm ad = new JvmAsm();
		int adDone = ad.label();
		int adChar = ad.label();
		int adStamp = ad.label();
		int adGrow = ad.label();
		int adNotDisplaced = ad.label();
		int adHaveEt = ad.label();
		int adSrcChar = ad.label();
		int adSrcGeneral = ad.label();
		// et: the element type SRC remembers, read from the same header facts
		// _arrayDefaultElement reads and in the same order. A runtime string carries no
		// header but IS a rank-1 character array, and a length-4 header is the character
		// vector marker -- the one specialized type that spends no header slot 4, which
		// is why _arrayElementType alone (slot 4 or t) cannot answer for it.
		ad.aload(1);
		ad.instanceOf(strClass);
		ad.branch(Opcode.IFNE, adSrcChar);
		ad.aload(1);
		ad.instanceOf(arrayListClass);
		ad.branch(Opcode.IFEQ, adSrcGeneral);
		emitLoadHeader(ad, arrayListClass, objectArrayClass, alGet, 1);
		ad.astore(4);
		ad.aload(4);
		ad.arraylength();
		ad.iconst(4);
		ad.branch(Opcode.IF_ICMPNE, adSrcGeneral);
		ad.bind(adSrcChar);
		ad.ldcString(characterName);
		ad.astore(2);
		ad.branch(Opcode.GOTO, adHaveEt);
		ad.bind(adSrcGeneral);
		ad.aload(1);
		ad.invokestatic(selfElementType);
		ad.astore(2);
		ad.bind(adHaveEt);
		// t is remembered as nothing at all, so there is nothing to carry over.
		ad.ldcString(cp.addString("T"));
		ad.aload(2);
		ad.invokevirtual(stringEquals);
		ad.branch(Opcode.IFNE, adDone);
		ad.aload(0);
		ad.instanceOf(arrayListClass);
		ad.branch(Opcode.IFEQ, adDone);
		emitLoadHeader(ad, arrayListClass, objectArrayClass, alGet, 0);
		ad.astore(3);
		ad.aload(3);
		ad.arraylength();
		ad.iconst(4);
		ad.branch(Opcode.IF_ICMPEQ, adDone);
		ad.aload(3);
		ad.arraylength();
		ad.iconst(5);
		ad.branch(Opcode.IF_ICMPLT, adNotDisplaced);
		ad.aload(3);
		ad.iconst(3);
		ad.aaload();
		ad.branch(Opcode.IFNONNULL, adDone);
		ad.bind(adNotDisplaced);
		ad.ldcString(characterName);
		ad.aload(2);
		ad.invokevirtual(stringEquals);
		ad.branch(Opcode.IFEQ, adStamp);
		ad.aload(3);
		ad.iconst(0);
		ad.aaload();
		ad.checkcast(objectArrayClass);
		ad.arraylength();
		ad.iconst(1);
		ad.branch(Opcode.IF_ICMPEQ, adChar);
		ad.bind(adStamp);
		ad.aload(3);
		ad.arraylength();
		ad.iconst(5);
		ad.branch(Opcode.IF_ICMPLT, adGrow);
		ad.aload(3);
		ad.iconst(4);
		ad.aload(2);
		ad.aastore();
		ad.branch(Opcode.GOTO, adDone);
		// dst.set(0, new Object[]{dims, fp, adj, null, et})
		ad.bind(adGrow);
		ad.aload(0);
		ad.checkcast(arrayListClass);
		ad.iconst(0);
		ad.iconst(5);
		ad.anewarray(objectClass);
		emitCopyHeaderSlots(ad, 3, 3);
		ad.dup();
		ad.iconst(4);
		ad.aload(2);
		ad.aastore();
		ad.invokevirtual(alSet);
		ad.pop();
		ad.branch(Opcode.GOTO, adDone);
		// dst.set(0, new Object[]{dims, fp, adj, null}) -- the character vector marker,
		// over the BOXED data it implies.
		ad.bind(adChar);
		ad.aload(0);
		ad.invokestatic(widen);
		emitLoadHeader(ad, arrayListClass, objectArrayClass, alGet, 0);
		ad.astore(3);
		ad.aload(0);
		ad.checkcast(arrayListClass);
		ad.iconst(0);
		ad.iconst(4);
		ad.anewarray(objectClass);
		emitCopyHeaderSlots(ad, 3, 3);
		ad.invokevirtual(alSet);
		ad.pop();
		ad.bind(adDone);
		ad.aload(0);
		ad.areturn();
		methods.add(new ArrayMethod(cp.addUtf8(ADOPT_ELEMENT_TYPE), cp.addUtf8(ADOPT_ELEMENT_TYPE_DESC), 7, 5,
				ad.finish()));

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
		emitActiveLength(sv, longClass, objectArrayClass, longIntValue, 2, 3);
		sv.branch(Opcode.GOTO, svRender);
		// A STRING VIEW (length-7 header) has no storage of its own: n is its
		// dimension, and the walk hands back either the character vector it aliases
		// (rendered element by element from the resolved base) or the immutable string
		// it aliases (sliced by code point in one substring).
		sv.bind(svView);
		// The view's OWN fill pointer bounds the rendering when it has one -- a
		// :displaced-to view may carry one, and then it is the string's length. Read it
		// before the walk below overwrites the header local.
		emitActiveLength(sv, longClass, objectArrayClass, longIntValue, 2, 3);
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

		// _subseqCv(o, start, end): the string subseq lane answering a MUTABLE character
		// vector (.todo/559 step 2 -- a copy-seq/subseq result has a writable identity,
		// like the interpreter's and SBCL's). end == -1 means "to the length". A
		// character vector or string view copies its elements [start, end) directly
		// through _rmGet (never rendering the source, so chained slicing stays linear);
		// an immutable String slices by code point and converts once through
		// _strToCharVec, then clears the fill-pointer slot that promotion path sets so
		// the result is a SIMPLE string like the other backends'. Locals: 0 = o,
		// 1 = start, 2 = end, 3 = header, 4 = len, 5 = n, 6 = out, 7 = i, 8 = s,
		// 9 = a, 10 = b.
		MethodrefConstant scStrToCharVec = cp.addMethodref(selfClass,
				cp.addNameAndType(cp.addUtf8(STR_TO_CHAR_VEC), cp.addUtf8(STR_TO_CHAR_VEC_DESC)));
		MethodrefConstant strLength = cp.addMethodref(strClass,
				cp.addNameAndType(cp.addUtf8("length"), cp.addUtf8("()I")));
		MethodrefConstant strConcat = cp.addMethodref(strClass,
				cp.addNameAndType(cp.addUtf8("concat"), cp.addUtf8("(Ljava/lang/String;)Ljava/lang/String;")));
		JvmAsm sc = new JvmAsm();
		int scStr = sc.label();
		int scCv = sc.label();
		sc.aload(0);
		sc.instanceOf(arrayListClass);
		sc.branch(Opcode.IFEQ, scStr);
		sc.aload(0);
		sc.checkcast(arrayListClass);
		sc.invokevirtual(alSize);
		sc.branch(Opcode.IFLE, scStr);
		sc.aload(0);
		sc.checkcast(arrayListClass);
		sc.iconst(0);
		sc.invokevirtual(alGet);
		sc.instanceOf(objectArrayClass);
		sc.branch(Opcode.IFEQ, scStr);
		sc.aload(0);
		sc.checkcast(arrayListClass);
		sc.iconst(0);
		sc.invokevirtual(alGet);
		sc.checkcast(objectArrayClass);
		sc.astore(3);
		sc.aload(3);
		sc.arraylength();
		sc.iconst(4);
		sc.branch(Opcode.IF_ICMPEQ, scCv);
		sc.aload(3);
		sc.arraylength();
		sc.iconst(7);
		sc.branch(Opcode.IF_ICMPNE, scStr);
		sc.bind(scCv);
		// len = header[1] != null (the fill pointer) ? its int : dims[0]
		int scUseDim = sc.label();
		int scHaveLen = sc.label();
		sc.aload(3);
		sc.iconst(1);
		sc.aaload();
		sc.branch(Opcode.IFNULL, scUseDim);
		sc.aload(3);
		sc.iconst(1);
		sc.aaload();
		sc.checkcast(longClass);
		sc.invokevirtual(longIntValue);
		sc.istore(4);
		sc.branch(Opcode.GOTO, scHaveLen);
		sc.bind(scUseDim);
		emitLoadDim0(sc, longClass, objectArrayClass, longIntValue, 3);
		sc.istore(4);
		sc.bind(scHaveLen);
		// n = (end < 0 ? len : end) - start
		int scUseEnd = sc.label();
		int scHaveN = sc.label();
		sc.iload(2);
		sc.branch(Opcode.IFGE, scUseEnd);
		sc.iload(4);
		sc.istore(5);
		sc.branch(Opcode.GOTO, scHaveN);
		sc.bind(scUseEnd);
		sc.iload(2);
		sc.istore(5);
		sc.bind(scHaveN);
		sc.iload(5);
		sc.iload(1);
		sc.op(Opcode.ISUB);
		sc.istore(5);
		// out = new ArrayList holding the length-4 header {dims{n}, null, null, null}
		sc.anew(arrayListClass);
		sc.dup();
		sc.invokespecial(alInit);
		sc.astore(6);
		sc.aload(6);
		sc.iconst(4);
		sc.anewarray(objectClass);
		sc.dup();
		sc.iconst(0);
		sc.iconst(1);
		sc.anewarray(objectClass);
		sc.dup();
		sc.iconst(0);
		sc.iload(5);
		sc.op(Opcode.I2L);
		sc.invokestatic(longValueOf);
		sc.aastore();
		sc.aastore();
		sc.invokevirtual(alAdd);
		sc.pop();
		// for i in 0..n-1: out.add(_rmGet(o, 1 + start + i))
		sc.iconst(0);
		sc.istore(7);
		int scLoop = sc.label();
		int scDone = sc.label();
		sc.bind(scLoop);
		sc.iload(7);
		sc.iload(5);
		sc.branch(Opcode.IF_ICMPGE, scDone);
		sc.aload(6);
		sc.aload(0);
		sc.iconst(1);
		sc.iload(1);
		sc.op(Opcode.IADD);
		sc.iload(7);
		sc.op(Opcode.IADD);
		sc.invokestatic(rmGet);
		sc.invokevirtual(alAdd);
		sc.pop();
		sc.iinc(7, 1);
		sc.branch(Opcode.GOTO, scLoop);
		sc.bind(scDone);
		sc.aload(6);
		sc.areturn();
		// The immutable arm: slice by CODE POINT and convert once.
		sc.bind(scStr);
		sc.aload(0);
		sc.checkcast(strClass);
		sc.astore(8);
		sc.aload(8);
		sc.iload(1);
		sc.invokestatic(strCpOffset);
		sc.istore(9);
		int scHaveEnd = sc.label();
		int scGotB = sc.label();
		sc.iload(2);
		sc.branch(Opcode.IFGE, scHaveEnd);
		sc.aload(8);
		sc.invokevirtual(strLength);
		sc.iconst(1);
		sc.op(Opcode.ISUB);
		sc.istore(10);
		sc.branch(Opcode.GOTO, scGotB);
		sc.bind(scHaveEnd);
		sc.aload(8);
		sc.iload(2);
		sc.invokestatic(strCpOffset);
		sc.istore(10);
		sc.bind(scGotB);
		sc.ldcString(quoteStr);
		sc.aload(8);
		sc.iload(9);
		sc.iload(10);
		sc.invokevirtual(strSubstring);
		sc.invokevirtual(strConcat);
		sc.ldcString(quoteStr);
		sc.invokevirtual(strConcat);
		sc.invokestatic(scStrToCharVec);
		sc.astore(6);
		// A subseq result is a SIMPLE string: clear the fill-pointer slot the
		// promotion-path _strToCharVec stamps.
		sc.aload(6);
		sc.checkcast(arrayListClass);
		sc.iconst(0);
		sc.invokevirtual(alGet);
		sc.checkcast(objectArrayClass);
		sc.iconst(1);
		sc.aconstNull();
		sc.aastore();
		sc.aload(6);
		sc.areturn();
		methods.add(new ArrayMethod(cp.addUtf8(SUBSEQ_CV), cp.addUtf8(SUBSEQ_CV_DESC), 10, 11, sc.finish()));

		// _toMutStr(o): the flipped producers' mutable-result wrap. A QUOTE-FRAMED
		// String -- an actual runtime string -- converts once through _strToCharVec
		// with the fill-pointer slot cleared (the result is a SIMPLE string, like
		// _subseqCv's); anything else passes through. The frame test matters: a SYMBOL
		// shares the java.lang.String representation bare (no quotes), and read-line's
		// eof-value or a symbol flowing out of a producer expression must not be
		// laundered through the string conversion. Locals: 0 = o, 1 = cv.
		MethodrefConstant strCharAt = cp.addMethodref(strClass,
				cp.addNameAndType(cp.addUtf8("charAt"), cp.addUtf8("(I)C")));
		JvmAsm tm = new JvmAsm();
		int tmPass = tm.label();
		tm.aload(0);
		tm.instanceOf(strClass);
		tm.branch(Opcode.IFEQ, tmPass);
		tm.aload(0);
		tm.checkcast(strClass);
		tm.invokevirtual(strLength);
		tm.branch(Opcode.IFLE, tmPass);
		tm.aload(0);
		tm.checkcast(strClass);
		tm.iconst(0);
		tm.invokevirtual(strCharAt);
		tm.iconst('"');
		tm.branch(Opcode.IF_ICMPNE, tmPass);
		tm.aload(0);
		tm.checkcast(strClass);
		tm.invokestatic(scStrToCharVec);
		tm.astore(1);
		tm.aload(1);
		tm.checkcast(arrayListClass);
		tm.iconst(0);
		tm.invokevirtual(alGet);
		tm.checkcast(objectArrayClass);
		tm.iconst(1);
		tm.aconstNull();
		tm.aastore();
		tm.aload(1);
		tm.areturn();
		tm.bind(tmPass);
		tm.aload(0);
		tm.areturn();
		methods.add(new ArrayMethod(cp.addUtf8(TO_MUT_STR), cp.addUtf8(TO_MUT_STR_DESC), 3, 2, tm.finish()));

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

	// Stores into etSlot the element type of the PACKED value in targetSlot -- the cons
	// list {@code (unsigned-byte width)} for an integer vector, the name
	// {@code double-float} / {@code single-float} for a float array -- and branches to
	// done; branches to notPacked when the value is not packed. The shapes are exactly
	// what {@code _ivElementType} / {@code _fvElementType} answer and what
	// {@code _arrayDefaultElement} reads back out of header slot 4, so a view over a
	// packed target and the un-displaced array it becomes give the same answer.
	private static void emitPackedElementTypeInto(JvmAsm a, ConstantPool cp, int targetSlot, int etSlot,
			ClassConstant longArrayClass, ClassConstant doubleArrayClass, ClassConstant floatArrayClass,
			ClassConstant shortArrayClass, MethodrefConstant longValueOf, ClassConstant objectClass, int notPacked,
			int done) {
		int tryDouble = a.label();
		int tryFloat = a.label();
		int tryShort = a.label();
		a.aload(targetSlot);
		a.instanceOf(longArrayClass);
		a.branch(Opcode.IFEQ, tryDouble);
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
		a.aload(targetSlot);
		a.checkcast(longArrayClass);
		a.iconst(0);
		a.laload();
		a.invokestatic(longValueOf);
		a.aastore();
		a.aastore();
		a.astore(etSlot);
		a.branch(Opcode.GOTO, done);
		a.bind(tryDouble);
		a.aload(targetSlot);
		a.instanceOf(doubleArrayClass);
		a.branch(Opcode.IFEQ, tryFloat);
		a.ldcString(cp.addString(am.ik.rontolisp.LispNames.DOUBLE_FLOAT));
		a.astore(etSlot);
		a.branch(Opcode.GOTO, done);
		a.bind(tryFloat);
		a.aload(targetSlot);
		a.instanceOf(floatArrayClass);
		a.branch(Opcode.IFEQ, tryShort);
		a.ldcString(cp.addString(am.ik.rontolisp.LispNames.SINGLE_FLOAT));
		a.astore(etSlot);
		a.branch(Opcode.GOTO, done);
		a.bind(tryShort);
		a.aload(targetSlot);
		a.instanceOf(shortArrayClass);
		a.branch(Opcode.IFEQ, notPacked);
		a.ldcString(cp.addString(am.ik.rontolisp.LispNames.BFLOAT16));
		a.astore(etSlot);
		a.branch(Opcode.GOTO, done);
	}

	// Reads and RETURNS the element the displaced view sees at the 1-based data index
	// idxSlot when the target in targetSlot is a PACKED vector; branches to notPacked
	// when it is not (a string view's target). The index arithmetic is the packed
	// representation's own: an integer vector's element `flat` lives at
	// {@code l[1 + flat]}, which the 1-based index already IS, and a float array's at
	// {@code d[1 + rank + flat]} == {@code d[rank + idx]} (and, at bfloat16, at
	// {@code s[1 + 2 * rank + flat]} == {@code s[2 * rank + idx]}).
	private static void emitPackedTargetGet(JvmAsm a, int targetSlot, int idxSlot, ClassConstant longArrayClass,
			ClassConstant doubleArrayClass, ClassConstant floatArrayClass, ClassConstant shortArrayClass,
			@Nullable MethodrefConstant bf16Value, MethodrefConstant longValueOf, MethodrefConstant doubleBoxValueOf,
			int notPacked) {
		int tryDouble = a.label();
		int tryFloat = a.label();
		int tryShort = a.label();
		a.aload(targetSlot);
		a.instanceOf(longArrayClass);
		a.branch(Opcode.IFEQ, tryDouble);
		a.aload(targetSlot);
		a.checkcast(longArrayClass);
		a.iload(idxSlot);
		a.laload();
		a.invokestatic(longValueOf);
		a.areturn();
		a.bind(tryDouble);
		a.aload(targetSlot);
		a.instanceOf(doubleArrayClass);
		a.branch(Opcode.IFEQ, tryFloat);
		a.aload(targetSlot);
		a.checkcast(doubleArrayClass);
		a.dup();
		a.iconst(0);
		a.daload();
		a.d2i();
		a.iload(idxSlot);
		a.op(Opcode.IADD);
		a.daload();
		a.invokestatic(doubleBoxValueOf);
		a.areturn();
		a.bind(tryFloat);
		a.aload(targetSlot);
		a.instanceOf(floatArrayClass);
		a.branch(Opcode.IFEQ, tryShort);
		a.aload(targetSlot);
		a.checkcast(floatArrayClass);
		a.dup();
		a.iconst(0);
		a.faload();
		a.f2i();
		a.iload(idxSlot);
		a.op(Opcode.IADD);
		a.faload();
		a.f2d();
		a.invokestatic(doubleBoxValueOf);
		a.areturn();
		a.bind(tryShort);
		if (bf16Value == null) {
			a.branch(Opcode.GOTO, notPacked);
			return;
		}
		a.aload(targetSlot);
		a.instanceOf(shortArrayClass);
		a.branch(Opcode.IFEQ, notPacked);
		a.aload(targetSlot);
		a.checkcast(shortArrayClass);
		a.dup();
		JvmPackedFloatWidth.BFLOAT16.loadRank(a);
		JvmPackedFloatWidth.BFLOAT16.emitDataOffset(a);
		a.iconst(1);
		a.op(Opcode.ISUB);
		a.iload(idxSlot);
		a.op(Opcode.IADD);
		JvmPackedFloatWidth.BFLOAT16.loadElem(a, bf16Value);
		a.invokestatic(doubleBoxValueOf);
		a.areturn();
	}

	// Stores through a displaced view into a PACKED target and RETURNS the value as
	// stored; branches to notPacked when the target is not packed. The element
	// semantics are the target representation's own -- an integer vector masks to its
	// width (a non-integer is the same type error a direct store gives), a float array
	// narrows to its backing width -- so a store through a view is a store into the
	// target, spelled through one more indirection.
	private static void emitPackedTargetSet(JvmAsm a, ConstantPool cp, int targetSlot, int idxSlot, int valSlot,
			int ivArrSlot, int dvArrSlot, int fvArrSlot, int ixSlot, int vSlot, int dvalSlot,
			ClassConstant longArrayClass, ClassConstant doubleArrayClass, ClassConstant floatArrayClass,
			ClassConstant shortArrayClass, @Nullable MethodrefConstant bf16Value, @Nullable MethodrefConstant bf16Bits,
			ClassConstant numberClass, MethodrefConstant numberLongValue, MethodrefConstant numberDoubleValue,
			MethodrefConstant longValueOf, MethodrefConstant doubleBoxValueOf, MethodrefConstant dblCoerce,
			ClassConstant rtExClass, MethodrefConstant rtExInit, int notPacked) {
		int tryDouble = a.label();
		int tryFloat = a.label();
		int tryShort = a.label();
		int intOk = a.label();
		a.aload(targetSlot);
		a.instanceOf(longArrayClass);
		a.branch(Opcode.IFEQ, tryDouble);
		a.aload(targetSlot);
		a.checkcast(longArrayClass);
		a.astore(ivArrSlot);
		a.aload(valSlot);
		a.instanceOf(numberClass);
		a.branch(Opcode.IFNE, intOk);
		emitThrow(a, rtExClass, rtExInit, cp.addString("%aset: a packed integer vector stores integers"));
		a.bind(intOk);
		a.aload(valSlot);
		a.checkcast(numberClass);
		a.invokevirtual(numberLongValue);
		a.lstore(vSlot);
		// width = (int) l[0]; v &= (1L << width) - 1
		a.aload(ivArrSlot);
		a.iconst(0);
		a.laload();
		a.l2i();
		a.istore(ixSlot);
		a.lload(vSlot);
		a.op(Opcode.LCONST_1);
		a.iload(ixSlot);
		a.op(Opcode.LSHL);
		a.op(Opcode.LCONST_1);
		a.op(Opcode.LSUB);
		a.op(Opcode.LAND);
		a.lstore(vSlot);
		a.aload(ivArrSlot);
		a.iload(idxSlot);
		a.lload(vSlot);
		a.lastore();
		a.lload(vSlot);
		a.invokestatic(longValueOf);
		a.areturn();
		a.bind(tryDouble);
		a.aload(targetSlot);
		a.instanceOf(doubleArrayClass);
		a.branch(Opcode.IFEQ, tryFloat);
		a.aload(targetSlot);
		a.checkcast(doubleArrayClass);
		a.astore(dvArrSlot);
		a.aload(dvArrSlot);
		a.iconst(0);
		a.daload();
		a.d2i();
		a.iload(idxSlot);
		a.op(Opcode.IADD);
		a.istore(ixSlot);
		a.aload(valSlot);
		a.invokestatic(dblCoerce);
		a.checkcast(numberClass);
		a.invokevirtual(numberDoubleValue);
		a.dstore(dvalSlot);
		a.aload(dvArrSlot);
		a.iload(ixSlot);
		a.dload(dvalSlot);
		a.dastore();
		a.dload(dvalSlot);
		a.invokestatic(doubleBoxValueOf);
		a.areturn();
		a.bind(tryFloat);
		a.aload(targetSlot);
		a.instanceOf(floatArrayClass);
		a.branch(Opcode.IFEQ, tryShort);
		a.aload(targetSlot);
		a.checkcast(floatArrayClass);
		a.astore(fvArrSlot);
		a.aload(fvArrSlot);
		a.iconst(0);
		a.faload();
		a.f2i();
		a.iload(idxSlot);
		a.op(Opcode.IADD);
		a.istore(ixSlot);
		a.aload(valSlot);
		a.invokestatic(dblCoerce);
		a.checkcast(numberClass);
		a.invokevirtual(numberDoubleValue);
		a.dstore(dvalSlot);
		a.aload(fvArrSlot);
		a.iload(ixSlot);
		a.dload(dvalSlot);
		a.d2f();
		a.fastore();
		// The value AS STORED: read the f32 slot back widened, so a single-float view
		// answers what the next read will answer.
		a.aload(fvArrSlot);
		a.iload(ixSlot);
		a.faload();
		a.f2d();
		a.invokestatic(doubleBoxValueOf);
		a.areturn();
		// A bfloat16 target: the slot is fvArrSlot again (a slot's type is per path),
		// the index 2 * rank + idx, the store and the read-back through the pair.
		a.bind(tryShort);
		if (bf16Value == null || bf16Bits == null) {
			a.branch(Opcode.GOTO, notPacked);
			return;
		}
		a.aload(targetSlot);
		a.instanceOf(shortArrayClass);
		a.branch(Opcode.IFEQ, notPacked);
		a.aload(targetSlot);
		a.checkcast(shortArrayClass);
		a.astore(fvArrSlot);
		a.aload(fvArrSlot);
		JvmPackedFloatWidth.BFLOAT16.loadRank(a);
		JvmPackedFloatWidth.BFLOAT16.emitDataOffset(a);
		a.iconst(1);
		a.op(Opcode.ISUB);
		a.iload(idxSlot);
		a.op(Opcode.IADD);
		a.istore(ixSlot);
		a.aload(valSlot);
		a.invokestatic(dblCoerce);
		a.checkcast(numberClass);
		a.invokevirtual(numberDoubleValue);
		a.dstore(dvalSlot);
		a.aload(fvArrSlot);
		a.iload(ixSlot);
		a.dload(dvalSlot);
		JvmPackedFloatWidth.BFLOAT16.storeElem(a, bf16Bits);
		a.aload(fvArrSlot);
		a.iload(ixSlot);
		JvmPackedFloatWidth.BFLOAT16.loadElem(a, bf16Value);
		a.invokestatic(doubleBoxValueOf);
		a.areturn();
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
	// Copies the first `count` slots of the header in headerSlot into the fresh Object[]
	// on top of the stack (which is left there), leaving the remaining slots null.
	private static void emitCopyHeaderSlots(JvmAsm a, int headerSlot, int count) {
		for (int i = 0; i < count; i++) {
			a.dup();
			a.iconst(i);
			a.aload(headerSlot);
			a.iconst(i);
			a.aaload();
			a.aastore();
		}
	}

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
	// The shared make-array :fill-pointer rule, in bytecode: null (the keyword absent or
	// nil) leaves fpVal null, a Long is that value range-checked against the array's own
	// element count, and anything else -- i.e. t -- is that count. Only a rank-1 array
	// may carry one. A DISPLACED view resolves it exactly the same way: total is the
	// VIEW's element count, never the target's.
	private static void emitResolveFillPointer(JvmAsm m, ClassConstant longClass, MethodrefConstant longIntValue,
			ClassConstant rtExClass, MethodrefConstant rtExInit, ConstantPool cp, int fp, int dimsArr, int total,
			int fpVal, int v) {
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
	}

	// Stores the array's ACTIVE length into nSlot: the header's fill pointer (slot 1)
	// when it carries one, otherwise dimension 0. Shared by every reader that stops at
	// the fill pointer -- and a DISPLACED view's header carries the slot too.
	private static void emitActiveLength(JvmAsm a, ClassConstant longClass, ClassConstant objectArrayClass,
			MethodrefConstant longIntValue, int headerSlot, int nSlot) {
		int useDim = a.label();
		int haveN = a.label();
		a.aload(headerSlot);
		a.iconst(1);
		a.aaload();
		a.branch(Opcode.IFNULL, useDim);
		a.aload(headerSlot);
		a.iconst(1);
		a.aaload();
		a.checkcast(longClass);
		a.invokevirtual(longIntValue);
		a.istore(nSlot);
		a.branch(Opcode.GOTO, haveN);
		a.bind(useDim);
		emitLoadDim0(a, longClass, objectArrayClass, longIntValue, headerSlot);
		a.istore(nSlot);
		a.bind(haveN);
	}

	private static void emitThrow(JvmAsm a, ClassConstant rtExClass, MethodrefConstant rtExInit,
			am.ik.jvm.ConstantPool.StringConstant message) {
		a.anew(rtExClass);
		a.dup();
		a.ldcString(message);
		a.invokespecial(rtExInit);
		a.op(Opcode.ATHROW);
	}

	// Shared tail of every _*CheckRank helper: unbox `given` (local givenSlot) to int
	// (local givSlot), compare it against the already-computed actual rank (local
	// rankSlot); a match returns arr (local arrSlot) unchanged, a mismatch throws
	// new RuntimeException("aref: expected " + rank + " subscripts, got " + given) --
	// the wording LispArray/LispFloatArray#flatIndex use in the interpreter.
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

	// Stores val at the fill pointer of the array in arrSlot (data index 1 + fp),
	// advances the stored fill pointer and returns Long.valueOf(fp). The store goes
	// through _rmSet, not ArrayList.set, so a DISPLACED fill-pointered view writes
	// THROUGH to its target's storage the way SBCL's does -- the view holds no data
	// slots of its own.
	private static void emitStoreAtFillPointerAndAdvance(JvmAsm a, MethodrefConstant rmSet,
			MethodrefConstant longValueOf, int valSlot, int arrSlot, int headerSlot, int fpSlot) {
		a.aload(arrSlot);
		a.iconst(1);
		a.iload(fpSlot);
		a.op(Opcode.IADD);
		a.aload(valSlot);
		a.invokestatic(rmSet);
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
		// sb = new StringBuilder("#"); rank 1 appends "(", rank n appends n then "A(",
		// and rank 0 appends "0A" with NO paren -- #0A<datum> is the whole rank-0
		// syntax, so the closing paren at the tail is skipped for it too.
		a.anew(sbClass(cp));
		a.dup();
		a.ldcString(cp.addString("#"));
		a.invokespecial(sbInit);
		a.astore(sb);
		int rankN = a.label();
		int rank0 = a.label();
		int afterPrefix = a.label();
		a.iload(rank);
		a.branch(Opcode.IFEQ, rank0);
		a.iload(rank);
		a.iconst(1);
		a.branch(Opcode.IF_ICMPNE, rankN);
		appendStr(a, sb, sbAppend, cp.addString("("));
		a.branch(Opcode.GOTO, afterPrefix);
		a.bind(rank0);
		appendStr(a, sb, sbAppend, cp.addString("0A"));
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
		// rendering race between request threads can at worst misplace a marker. A
		// rank-0 array opened no paren, so it closes none.
		int noRparen = a.label();
		a.iload(rank);
		a.branch(Opcode.IFEQ, noRparen);
		appendStr(a, sb, sbAppend, cp.addString(")"));
		a.bind(noRparen);
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
	// flat = 0; for k in 0..: flat = flat * dims[k] + subs[k]. The fold starts at 0 (not
	// at subs[0]) so an EMPTY subscript array -- a rank-0 array -- answers 0.
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
		// flat = 0; for k in 0..n-1: flat = flat * dims[k] + subs[k]. Starting the fold
		// at 0 rather than at subs[0] is what makes an EMPTY subscript array -- a rank-0
		// array -- answer 0 instead of reading a subscript that is not there.
		a.iconst(0);
		a.istore(flat);
		a.iconst(0);
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

	// Emits the ArrayElementTypes code -> element type VALUE switch: reads the int in
	// codeSlot and leaves the value in etSlot, then branches to done. The value is the
	// runtime shape array-element-type answers -- a name string for the symbol types,
	// the cons {"UNSIGNED-BYTE", {Long, null}} for the packed integer widths -- built
	// once at allocation rather than at every read.
	private static void emitElementTypeForCode(JvmAsm a, ConstantPool cp, ClassConstant objectClass,
			MethodrefConstant longValueOf, int codeSlot, int etSlot, int done) {
		emitElementTypeCase(a, codeSlot, etSlot, done, am.ik.rontolisp.ArrayElementTypes.CHARACTER,
				() -> a.ldcString(cp.addString(am.ik.rontolisp.LispNames.CHARACTER_TYPE)));
		emitElementTypeCase(a, codeSlot, etSlot, done, am.ik.rontolisp.ArrayElementTypes.UNSIGNED_BYTE_8,
				() -> emitUnsignedByte(a, cp, objectClass, longValueOf, 8));
		emitElementTypeCase(a, codeSlot, etSlot, done, am.ik.rontolisp.ArrayElementTypes.UNSIGNED_BYTE_16,
				() -> emitUnsignedByte(a, cp, objectClass, longValueOf, 16));
		emitElementTypeCase(a, codeSlot, etSlot, done, am.ik.rontolisp.ArrayElementTypes.UNSIGNED_BYTE_32,
				() -> emitUnsignedByte(a, cp, objectClass, longValueOf, 32));
		emitElementTypeCase(a, codeSlot, etSlot, done, am.ik.rontolisp.ArrayElementTypes.SINGLE_FLOAT,
				() -> a.ldcString(cp.addString(am.ik.rontolisp.LispNames.SINGLE_FLOAT)));
		emitElementTypeCase(a, codeSlot, etSlot, done, am.ik.rontolisp.ArrayElementTypes.DOUBLE_FLOAT,
				() -> a.ldcString(cp.addString(am.ik.rontolisp.LispNames.DOUBLE_FLOAT)));
		// A GENERAL array that merely REMEMBERS bfloat16 decodes here too. The packed
		// bfloat16 representation itself does not reach this backend yet; this arm only
		// keeps array-element-type from answering nothing for a remembered width.
		emitElementTypeCase(a, codeSlot, etSlot, done, am.ik.rontolisp.ArrayElementTypes.BFLOAT16,
				() -> a.ldcString(cp.addString(am.ik.rontolisp.LispNames.BFLOAT16)));
		// ArrayElementTypes.T, which never reaches here: nothing is remembered for it.
		a.aconstNull();
		a.astore(etSlot);
		a.branch(Opcode.GOTO, done);
	}

	private static void emitElementTypeCase(JvmAsm a, int codeSlot, int etSlot, int done, int code, Runnable value) {
		int next = a.label();
		a.iload(codeSlot);
		a.iconst(code);
		a.branch(Opcode.IF_ICMPNE, next);
		value.run();
		a.astore(etSlot);
		a.branch(Opcode.GOTO, done);
		a.bind(next);
	}

	// new Object[]{"UNSIGNED-BYTE", new Object[]{Long.valueOf(width), null}} -- the cons
	// (unsigned-byte width), in the two-slot Object[] a cons cell is on this backend.
	private static void emitUnsignedByte(JvmAsm a, ConstantPool cp, ClassConstant objectClass,
			MethodrefConstant longValueOf, int width) {
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
		a.iconst(width);
		a.op(Opcode.I2L);
		a.invokestatic(longValueOf);
		a.aastore();
		a.aastore();
	}

}
