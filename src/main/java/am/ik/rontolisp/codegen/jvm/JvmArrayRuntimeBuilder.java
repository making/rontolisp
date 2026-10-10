package am.ik.rontolisp.codegen.jvm;

import java.lang.classfile.TypeKind;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.LongEntry;
import java.lang.classfile.constantpool.MethodRefEntry;
import java.lang.classfile.constantpool.StringEntry;
import java.lang.classfile.constantpool.Utf8Entry;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import am.ik.jvm.ConstantPool;
import am.ik.jvm.MethodCode;
import am.ik.rontolisp.ArrayGrowth;
import am.ik.rontolisp.RenderCycleGuard;
import am.ik.rontolisp.compiler.OperandTypes;

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

	/**
	 * {@code _arrayDimsTotal(dims) -> int}: the element count of a {@code make-array}
	 * dimensions argument, every dimension checked first ({@link #buildDimsTotal}). Every
	 * allocating helper calls it before it parses the argument.
	 */
	static final String DIMS_TOTAL = "_arrayDimsTotal";

	static final String DIMS_TOTAL_DESC = "(Ljava/lang/Object;)I";

	static final String DIMS_DESC = "(Ljava/lang/Object;)Ljava/lang/Object;";

	// _arrayCheckRank(arr, given): the array's own rank (its header dims length, or 1 for
	// a string, which is always a rank-1 character array) compared against `given`, the
	// subscript count the aref/%aset call site baked in at compile time. A mismatch
	// throws _rankErr's report for the operator's wrapper to name -- the interpreter's
	// (.kb/error-handling.md, "A rank mismatch"). A match returns `arr` unchanged; the
	// site calls it after the subscripts (and a store's value) are evaluated and
	// type-checked. Never called from row-major-aref/%row-major-aset, which
	// intentionally accept any rank.
	static final String CHECK_RANK = "_arrayCheckRank";

	static final String CHECK_RANK_DESC = "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";

	// _rankErr(arr, given): the unnamed type-error of an array whose rank is not the
	// `given` subscripts -- the datum the array, the expected type
	// OperandTypes#rankType's (VECTOR, (ARRAY * NIL), (ARRAY * (* *)) ...) built at run
	// time through _teOf. Every _*CheckRank and the quantized accessors' own rank checks
	// throw it.
	static final String RANK_ERR = "_rankErr";

	static final String RANK_ERR_DESC = "(Ljava/lang/Object;I)Ljava/lang/RuntimeException;";

	static final String TO_STRING = "_arrayToString";

	static final String TO_DISPLAY_STRING = "_arrayToDisplayString";

	static final String TO_STRING_DESC = "(Ljava/lang/Object;)Ljava/lang/String;";

	/**
	 * {@code _ckArr(Object) -> Object}: an array-shape accessor's operand check
	 * ({@code fill-pointer}, {@code vector-push}, {@code array-element-type} and their
	 * kin). Any array -- a general one (an {@code ArrayList}), a string (a quote-framed
	 * {@code String}; a symbol, the other {@code String}, is none) or a packed one (a
	 * {@code long[]}, {@code double[]}, {@code float[]}, {@code short[]} or
	 * {@code byte[]}) -- answers itself for the accessor to read as it always did, and
	 * anything else throws {@code _teRaw}'s unnamed {@code ARRAY} report for the
	 * operator's wrapper at the call site to name: the accessors cast to the general
	 * shape, which failed with a {@code ClassCastException} that carried no datum, and
	 * the predicates answered nil.
	 */
	static final String CK_ARRAY = "_ckArr";

	static final String CK_ARRAY_DESC = "(Ljava/lang/Object;)Ljava/lang/Object;";

	/**
	 * {@code _ckFp(Object) -> Object}: the fill-pointer surface's operand check
	 * ({@code fill-pointer} and its {@code setf}, {@code vector-push},
	 * {@code vector-push-extend}, {@code vector-pop}). A general vector or character
	 * vector (an {@code ArrayList}) whose header carries a fill pointer answers itself;
	 * any other array throws {@code _teOf}'s unnamed report of {@code (AND VECTOR
	 * (SATISFIES ARRAY-HAS-FILL-POINTER-P))} and a value that is no array at all
	 * {@link #CK_ARRAY}'s {@code ARRAY} one, for the operator's wrapper to name. The
	 * helpers behind it read the fill pointer unchecked.
	 */
	static final String CK_FILL_POINTER = "_ckFp";

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

	static final String ARRAY_BECOME_DISPLACED = "_arrayBecomeDisplaced";

	static final String ARRAY_BECOME_DISPLACED_DESC = "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";

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

	/**
	 * {@code _arrayAlike(seq, n) -> Object}: the {@code %array-alike} allocator -- a
	 * fresh zero-filled rank-1 array of length {@code n} of the SAME KIND as {@code seq},
	 * keyed on {@link #ELEMENT_TYPE}'s answer rather than on {@code seq}'s runtime class:
	 * a packed integer vector, a packed float array, a fill-pointer / adjustable vector
	 * that only REMEMBERS a packed width, and a displaced view over any of them all
	 * answer the same element type and so get the same packed result. What keeps
	 * {@code subseq} / {@code copy-seq} type-preserving ({@code .kb/subseq-runtime.md}).
	 */
	static final String ALIKE = "_arrayAlike";

	static final String ALIKE_DESC = "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";

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
	 * {@code _subseqCv(Object, Object, Object) -> Object}: the string {@code subseq} lane
	 * answering a MUTABLE character vector, so a {@code copy-seq}/{@code subseq} result
	 * has a writable identity like the interpreter's. A character vector or string view
	 * input copies elements through {@code _rmGet}; an immutable {@code String} slices by
	 * code point and converts once through {@code _strToCharVec}. The bounds arrive as
	 * given, a nil {@code end} meaning "to the length", so a refusal reports them as
	 * given.
	 */
	static final String SUBSEQ_CV = "_subseqCv";

	static final String SUBSEQ_CV_DESC = "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";

	/**
	 * {@code _toMutStr(Object) -> Object}: the mutable-result wrap the flipped string
	 * PRODUCERS ({@code concatenate 'string}, the case family, {@code format nil}, the
	 * string-stream capture, {@code read-line}) finish with. A {@code String} input --
	 * always a FRESH runtime string at those sites, never the shared literal itself --
	 * converts once through {@code _strToCharVec} (the fill-pointer slot cleared: the
	 * result is a SIMPLE string); anything else (a character vector already,
	 * {@code format t}'s nil, an eof value) passes through untouched.
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
			DEFAULT_ELEMENT, ADOPT_ELEMENT_TYPE, ALIKE, CHECK_RANK, RANK_ERR, ARRAY_BECOME_DISPLACED, CK_ARRAY,
			CK_FILL_POINTER, DIMS_TOTAL);

	/** An array helper method body ready to be emitted into the generated class. */
	record ArrayMethod(Utf8Entry name, Utf8Entry desc, MethodCode code) {
	}

	private JvmArrayRuntimeBuilder() {
	}

	/**
	 * Builds the general array helpers.
	 * @param cp the constant pool
	 * @param objectClass the {@code java/lang/Object} class constant
	 * @param objectArrayClass the {@code [Ljava/lang/Object;} class constant
	 * @param selfClass the generated program class
	 * @param usesFloatArray whether the packed float-array tier is emitted
	 * @param usesQuantized whether a quantized matrix can exist: no array, but the array
	 * inquiries take it ({@code .kb/quantized-matrix.md}), so the array check passes its
	 * holder
	 * @param subseqRuntime the shared subseq runtime
	 * @param consShape the cons shape test
	 * @return the helper methods
	 */
	static List<ArrayMethod> build(ConstantPool cp, ClassEntry objectClass, ClassEntry objectArrayClass,
			ClassEntry selfClass, boolean usesFloatArray, boolean usesQuantized,
			JvmOperandTypeRuntime.SubseqRuntime subseqRuntime, JvmOperandTypeRuntime.ConsShape consShape) {
		ClassEntry arrayListClass = cp.classEntry("java/util/ArrayList");
		ClassEntry longClass = cp.classEntry("java/lang/Long");
		MethodRefEntry alInit = cp.methodRef(arrayListClass, "<init>", "()V");
		MethodRefEntry alAdd = cp.methodRef(arrayListClass, "add", "(Ljava/lang/Object;)Z");
		MethodRefEntry alGet = cp.methodRef(arrayListClass, "get", "(I)Ljava/lang/Object;");
		MethodRefEntry alSet = cp.methodRef(arrayListClass, "set", "(ILjava/lang/Object;)Ljava/lang/Object;");
		MethodRefEntry longIntValue = cp.methodRef(longClass, "intValue", "()I");
		MethodRefEntry longValueOf = cp.methodRef(longClass, "valueOf", "(J)Ljava/lang/Long;");
		MethodRefEntry alSize = cp.methodRef(arrayListClass, "size", "()I");
		MethodRefEntry alRemove = cp.methodRef(arrayListClass, "remove", "(I)Ljava/lang/Object;");
		ClassEntry rtExClass = cp.classEntry("java/lang/RuntimeException");
		MethodRefEntry rtExInit = cp.methodRef(rtExClass, "<init>", "(Ljava/lang/String;)V");
		MethodRefEntry rmGet = cp.methodRef(selfClass, RM_GET, RM_GET_DESC);
		MethodRefEntry rmSet = cp.methodRef(selfClass, RM_SET, RM_SET_DESC);
		MethodRefEntry undisplace = cp.methodRef(selfClass, UNDISPLACE, UNDISPLACE_DESC);
		// Every subscript an accessor below indexes with is checked against the
		// dimension it indexes (JvmOperandTypeRuntime): an out-of-range one is the
		// access's type-error, named by its operator's wrapper.
		MethodRefEntry ckBound = cp.methodRef(selfClass, JvmOperandTypeRuntime.CK_BOUND,
				JvmOperandTypeRuntime.CK_BOUND_DESC);
		MethodRefEntry makeDisplaced = cp.methodRef(selfClass, MAKE_DISPLACED, MAKE_DISPLACED_DESC);
		MethodRefEntry widen = cp.methodRef(selfClass, WIDEN, WIDEN_DESC);
		MethodRefEntry defaultElement = cp.methodRef(selfClass, DEFAULT_ELEMENT, DEFAULT_ELEMENT_DESC);
		ClassEntry longArrayClass = cp.classEntry("[J");
		MethodRefEntry longLongValue = cp.methodRef(longClass, "longValue", "()J");
		// The PACKED displacement targets: a packed integer vector is a byte[]{8, e0,
		// ...} or a long[]{width, e0, ...} and a packed float array a
		// double[]/float[]{rank, dims..., e0, ...}. (A quantized matrix, the other
		// byte[], is no array a view can be displaced to.)
		ClassEntry byteArrayClass = cp.classEntry("[B");
		// A view over one is an ordinary displaced header whose slot 3 holds that array
		// instead of an ArrayList or a String, so the walk ends on it exactly as it ends
		// on a string view's target.
		ClassEntry doubleArrayClass = cp.classEntry("[D");
		ClassEntry floatArrayClass = cp.classEntry("[F");
		ClassEntry shortArrayClass = cp.classEntry("[S");
		// The bfloat16 conversion pair the _fv* tier emits (JvmFloatArrayRuntimeBuilder),
		// referenced only when that tier is emitted: a short[] cannot exist otherwise,
		// and the compiler refuses a class that calls an own method it does not declare.
		// The short[] arms of the displaced-view helpers are emitted under the same gate.
		MethodRefEntry bf16Value = usesFloatArray ? cp.methodRef(selfClass, JvmFloatArrayRuntimeBuilder.BF16_VALUE,
				JvmFloatArrayRuntimeBuilder.BF16_VALUE_DESC) : null;
		MethodRefEntry bf16Bits = usesFloatArray ? cp.methodRef(selfClass, JvmFloatArrayRuntimeBuilder.BF16_BITS,
				JvmFloatArrayRuntimeBuilder.BF16_BITS_DESC) : null;
		ClassEntry numberClass = cp.classEntry("java/lang/Number");
		MethodRefEntry numberLongValue = cp.methodRef(numberClass, "longValue", "()J");
		MethodRefEntry numberDoubleValue = cp.methodRef(numberClass, "doubleValue", "()D");
		ClassEntry doubleBoxClass = cp.classEntry("java/lang/Double");
		MethodRefEntry doubleBoxValueOf = cp.methodRef(doubleBoxClass, "valueOf", "(D)Ljava/lang/Double;");
		// The shared numeric coercion the packed float accessors already use: any
		// numeric value (Long, BigInteger, ratio, Double) as a Double.
		MethodRefEntry dblCoerce = cp.methodRef(selfClass, JvmNumericRuntimeBuilder.DBL,
				"(Ljava/lang/Object;)Ljava/lang/Object;");
		MethodRefEntry arraysFillLong = cp.methodRef(cp.classEntry("java/util/Arrays"), "fill", "([JJ)V");
		LongEntry nilSentinel = cp.entries().longEntry(NIL_SENTINEL);

		List<ArrayMethod> methods = new ArrayList<>();
		MethodRefEntry dimsTotal = cp.methodRef(selfClass, DIMS_TOTAL, DIMS_TOTAL_DESC);
		methods.add(buildDimsTotal(cp, selfClass, consShape));

		// _arrayMake(dims, init, fp, adj):
		// list = new ArrayList(); build the Object[] dimension sizes and the total
		// element count from dims (a Long for the rank-1 shorthand, otherwise a cons
		// list of Longs); wrap them with the fill pointer and the adjustable flag into
		// the 3-element slot-0 header; repeat total times: list.add(init).
		MethodCode m = new MethodCode();
		int dims = 0, init = 1, fp = 2, adj = 3, list = 4, total = 5, dimsArr = 6, idx = 7, cur = 8, n = 9, fpVal = 10,
				v = 11;
		m.new_(arrayListClass);
		m.dup();
		m.invokespecial(alInit);
		m.astore(list);
		emitParseDims(m, objectClass, objectArrayClass, dimsTotal, dims, dimsArr, total, cur, n, idx);
		emitResolveFillPointer(m, longClass, longIntValue, rtExClass, rtExInit, cp, fp, dimsArr, total, fpVal, v);
		// PACKED fast path: no fill pointer, not adjustable, and the initial element is
		// nil or an integer (excluding the sentinel value, which must stay
		// representable): the data is a flat long[] behind a length-6 header
		// {dims, null, null, null, null, data} and the list holds ONLY the header.
		MethodCode.Label generalPath = m.newLabel();
		MethodCode.Label packedNilFill = m.newLabel();
		MethodCode.Label packedGo = m.newLabel();
		int fillVal = 12, data = 14;
		m.aload(fpVal);
		m.ifnonnull(generalPath);
		m.aload(adj);
		m.ifnonnull(generalPath);
		m.aload(init);
		m.ifnull(packedNilFill);
		m.aload(init);
		m.instanceOf(longClass);
		m.ifeq(generalPath);
		m.aload(init);
		m.checkcast(longClass);
		m.invokevirtual(longLongValue);
		m.lstore(fillVal);
		m.lload(fillVal);
		m.ldc(nilSentinel);
		m.lcmp();
		m.ifeq(generalPath);
		m.goto_(packedGo);
		m.labelBinding(packedNilFill);
		m.ldc(nilSentinel);
		m.lstore(fillVal);
		m.labelBinding(packedGo);
		// data = new long[total]; Arrays.fill(data, fillVal)
		m.iload(total);
		m.newarray(TypeKind.LONG);
		m.astore(data);
		m.aload(data);
		m.lload(fillVal);
		m.invokestatic(arraysFillLong);
		// list.add(new Object[]{dimsArr, null, null, null, null, data}); return list
		m.aload(list);
		m.loadConstant(6);
		m.anewarray(objectClass);
		m.dup();
		m.loadConstant(0);
		m.aload(dimsArr);
		m.aastore();
		m.dup();
		m.loadConstant(5);
		m.aload(data);
		m.aastore();
		m.invokevirtual(alAdd);
		m.pop();
		m.aload(list);
		m.areturn();
		// list.add(new Object[]{dimsArr, fpVal, adj}); fill init total times
		m.labelBinding(generalPath);
		m.aload(list);
		m.loadConstant(3);
		m.anewarray(objectClass);
		m.dup();
		m.loadConstant(0);
		m.aload(dimsArr);
		m.aastore();
		m.dup();
		m.loadConstant(1);
		m.aload(fpVal);
		m.aastore();
		m.dup();
		m.loadConstant(2);
		m.aload(adj);
		m.aastore();
		m.invokevirtual(alAdd);
		m.pop();
		m.loadConstant(0);
		m.istore(idx);
		MethodCode.Label loop = m.newLabel();
		MethodCode.Label end = m.newLabel();
		m.labelBinding(loop);
		m.iload(idx);
		m.iload(total);
		m.if_icmpge(end);
		m.aload(list);
		m.aload(init);
		m.invokevirtual(alAdd);
		m.pop();
		m.iinc(idx, 1);
		m.goto_(loop);
		m.labelBinding(end);
		m.aload(list);
		m.areturn();
		methods.add(new ArrayMethod(cp.utf8Entry(MAKE), cp.utf8Entry(MAKE_DESC), m));

		// _aref1(arr, i): return _rmGet(arr, 1 + ((Long) i).intValue()) -- _rmGet
		// follows the displacement chain, so every accessor goes through it. A string
		// is a rank-1 character array in CL: on a runtime String the string's content
		// lives in [1, length-1) (the surrounding quotes are the framing), and the
		// requested character index is translated BY CODE POINT via _cpoff(s, i)
		// -> s.codePointAt(codeUnit) so a supplementary code point counts as one
		// indexed element -- matching the (length s) contract everywhere else.
		ClassEntry strClass = cp.classEntry("java/lang/String");
		// A String is a string only quote-framed: a symbol is the other String, and no
		// array (the test stringp makes, JvmStringpCompiler).
		MethodRefEntry strCharAt = cp.methodRef(strClass, "charAt", "(I)C");
		MethodRefEntry strCpOffset = cp.methodRef(selfClass, JvmStringIndexRuntimeBuilder.OFFSET_METHOD,
				JvmStringIndexRuntimeBuilder.OFFSET_DESC);
		MethodRefEntry strCodePointAt = cp.methodRef(strClass, "codePointAt", "(I)I");
		MethodRefEntry strCount = cp.methodRef(selfClass, JvmStringIndexRuntimeBuilder.COUNT_METHOD,
				JvmStringIndexRuntimeBuilder.COUNT_DESC);
		MethodCode a1 = new MethodCode();
		MethodCode.Label a1NotString = a1.newLabel();
		emitStringTest(a1, strClass, strCharAt, 0, a1NotString);
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
		a1.loadConstant(1);
		a1.newarray(TypeKind.INT);
		a1.dup();
		a1.loadConstant(0);
		a1.iload(5);
		a1.iastore();
		a1.areturn();
		a1.labelBinding(a1NotString);
		emitArrayCheck(a1, cp, selfClass, arrayListClass, null, null, 0);
		// A flat access (rank 1, or row-major-aref at any rank): the bound is the total
		// size.
		emitFlatBound(a1, arrayListClass, objectArrayClass, longArrayClass, alGet, alSize, longClass, longIntValue, 3,
				4, 5);
		a1.aload(0);
		a1.loadConstant(1);
		a1.aload(1);
		a1.iload(5);
		a1.invokestatic(ckBound);
		a1.iadd();
		a1.invokestatic(rmGet);
		a1.areturn();
		methods.add(new ArrayMethod(cp.utf8Entry(AREF1), cp.utf8Entry(AREF1_DESC), a1));

		// _aref2(arr, i, j): cols = dims[1]; return _rmGet(arr, 1 + i * cols + j)
		MethodCode a2 = new MethodCode();
		emitFlat2(a2, arrayListClass, longClass, objectArrayClass, alGet, longIntValue, ckBound, 4);
		a2.istore(3);
		a2.aload(0);
		a2.iload(3);
		a2.invokestatic(rmGet);
		a2.areturn();
		methods.add(new ArrayMethod(cp.utf8Entry(AREF2), cp.utf8Entry(AREF2_DESC), a2));

		// _aset1(arr, i, val): _rmSet(arr, 1 + i, val) -- returns val. A string passes
		// the
		// check: no store routes one here, and it fails the cast below as it always did.
		MethodCode s1 = new MethodCode();
		emitArrayCheck(s1, cp, selfClass, arrayListClass, strClass, strCharAt, 0);
		emitFlatBound(s1, arrayListClass, objectArrayClass, longArrayClass, alGet, alSize, longClass, longIntValue, 3,
				4, 5);
		s1.aload(0);
		s1.loadConstant(1);
		s1.aload(1);
		s1.iload(5);
		s1.invokestatic(ckBound);
		s1.iadd();
		s1.aload(2);
		s1.invokestatic(rmSet);
		s1.areturn();
		methods.add(new ArrayMethod(cp.utf8Entry(ASET1), cp.utf8Entry(ASET1_DESC), s1));

		// _arrayDims(arr): the dimension sizes as a fresh cons list, built backwards
		// over the dims Object[] in the slot-0 header (the sizes are already boxed
		// Longs). A cons is an Object[]{car, cdr} and nil is null, matching the compiled
		// cons representation.
		MethodCode d = new MethodCode();
		int dArr = 0, dDims = 1, dResult = 2, dJ = 3;
		// A runtime string carries no header at all, but it IS a rank-1 character array:
		// its dimensions are the one-element list of its length in code points. Every
		// other shape reader -- array-rank, array-dimension, array-total-size,
		// array-row-major-index -- expands through array-dimensions, so this one arm is
		// what lets all of them accept a string, as the interpreter's do.
		MethodCode.Label dNotString = d.newLabel();
		emitStringTest(d, strClass, strCharAt, dArr, dNotString);
		d.loadConstant(2);
		d.anewarray(objectClass);
		d.dup();
		d.loadConstant(0);
		d.aload(dArr);
		d.checkcast(strClass);
		d.invokestatic(strCount);
		d.i2l();
		d.invokestatic(longValueOf);
		d.aastore();
		d.areturn();
		d.labelBinding(dNotString);
		emitArrayCheck(d, cp, selfClass, arrayListClass, null, null, dArr);
		d.aload(dArr);
		d.checkcast(arrayListClass);
		d.loadConstant(0);
		d.invokevirtual(alGet);
		d.checkcast(objectArrayClass);
		d.loadConstant(0);
		d.aaload();
		d.checkcast(objectArrayClass);
		d.astore(dDims);
		d.aconst_null();
		d.astore(dResult);
		d.aload(dDims);
		d.arraylength();
		d.loadConstant(1);
		d.isub();
		d.istore(dJ);
		MethodCode.Label dLoop = d.newLabel();
		MethodCode.Label dDone = d.newLabel();
		d.labelBinding(dLoop);
		d.iload(dJ);
		d.iflt(dDone);
		// result = new Object[]{dims[j], result}
		d.loadConstant(2);
		d.anewarray(objectClass);
		d.dup();
		d.loadConstant(0);
		d.aload(dDims);
		d.iload(dJ);
		d.aaload();
		d.aastore();
		d.dup();
		d.loadConstant(1);
		d.aload(dResult);
		d.aastore();
		d.astore(dResult);
		d.iinc(dJ, -1);
		d.goto_(dLoop);
		d.labelBinding(dDone);
		d.aload(dResult);
		d.areturn();
		methods.add(new ArrayMethod(cp.utf8Entry(DIMS), cp.utf8Entry(DIMS_DESC), d));

		// _arrayCheckRank(arr, given): rank = 1 for a string, else the length of the
		// header's boxed dims (the same derivation DIMS uses, without building the cons
		// list). A mismatch against `given` throws; a match returns `arr` unchanged.
		// Locals: 0=arr, 1=given, 2=rank, 3=dims (Object[]), 4=giv.
		MethodRefEntry rankErr = JvmOperandTypeRuntime.self(cp, selfClass, RANK_ERR, RANK_ERR_DESC);
		int crArr = 0, crGiven = 1, crRank = 2, crDims = 3, crGiv = 4;
		MethodCode cr = new MethodCode();
		MethodCode.Label crNotString = cr.newLabel();
		MethodCode.Label crHaveRank = cr.newLabel();
		emitStringTest(cr, strClass, strCharAt, crArr, crNotString);
		cr.loadConstant(1);
		cr.istore(crRank);
		cr.goto_(crHaveRank);
		cr.labelBinding(crNotString);
		// Anything but an array (a general one is an ArrayList; the packed families were
		// answered by the _iv/_fv check a step up the chain) is the ARRAY type-error, for
		// the operator's wrapper at the call site to name: the cast failed with a
		// ClassCastException that carried no datum.
		emitArrayCheck(cr, cp, selfClass, arrayListClass, null, null, crArr);
		cr.aload(crArr);
		cr.checkcast(arrayListClass);
		cr.loadConstant(0);
		cr.invokevirtual(alGet);
		cr.checkcast(objectArrayClass);
		cr.loadConstant(0);
		cr.aaload();
		cr.checkcast(objectArrayClass);
		cr.astore(crDims);
		cr.aload(crDims);
		cr.arraylength();
		cr.istore(crRank);
		cr.labelBinding(crHaveRank);
		emitRankCheckAndReturn(cr, longClass, longIntValue, rankErr, crArr, crGiven, crRank, crGiv);
		methods.add(new ArrayMethod(cp.utf8Entry(CHECK_RANK), cp.utf8Entry(CHECK_RANK_DESC), cr));

		// _rankErr(arr, given): the expected type, then _teOf. VECTOR for one subscript,
		// else (ARRAY * stars), stars a list of `given` wildcards. Locals: 0=arr,
		// 1=given, 2=stars, 3=k, 4=type.
		MethodCode re = new MethodCode();
		MethodCode.Label reList = re.newLabel();
		MethodCode.Label reHaveType = re.newLabel();
		re.iload(1);
		re.loadConstant(1);
		re.if_icmpne(reList);
		re.ldc(cp.stringEntry(OperandTypes.VECTOR_TYPE));
		re.astore(4);
		re.goto_(reHaveType);
		re.labelBinding(reList);
		re.aconst_null();
		re.astore(2);
		re.iload(1);
		re.istore(3);
		MethodCode.Label reLoop = re.newLabel();
		MethodCode.Label reDone = re.newLabel();
		re.labelBinding(reLoop);
		re.iload(3);
		re.ifle(reDone);
		// stars = {"*", stars}
		emitList(re, objectClass, List.of(() -> re.ldc(cp.stringEntry(OperandTypes.WILDCARD))));
		re.dup();
		re.loadConstant(1);
		re.aload(2);
		re.aastore();
		re.astore(2);
		re.iinc(3, -1);
		re.goto_(reLoop);
		re.labelBinding(reDone);
		emitList(re, objectClass, List.of(() -> re.ldc(cp.stringEntry(OperandTypes.ARRAY_TYPE)),
				() -> re.ldc(cp.stringEntry(OperandTypes.WILDCARD)), () -> re.aload(2)));
		re.astore(4);
		re.labelBinding(reHaveType);
		re.aload(0);
		re.aload(4);
		re.invokestatic(JvmOperandTypeRuntime.self(cp, selfClass, JvmOperandTypeRuntime.TE_OF,
				JvmOperandTypeRuntime.TE_OF_DESC));
		re.areturn();
		methods.add(new ArrayMethod(cp.utf8Entry(RANK_ERR), cp.utf8Entry(RANK_ERR_DESC), re));

		// _aset2(arr, i, j, val): _rmSet(arr, 1 + i * cols + j, val) -- returns val
		MethodCode s2 = new MethodCode();
		emitFlat2(s2, arrayListClass, longClass, objectArrayClass, alGet, longIntValue, ckBound, 5);
		s2.istore(4);
		s2.aload(0);
		s2.iload(4);
		s2.aload(3);
		s2.invokestatic(rmSet);
		s2.areturn();
		methods.add(new ArrayMethod(cp.utf8Entry(ASET2), cp.utf8Entry(ASET2_DESC), s2));

		// _arefN(arr, subs): return _rmGet(arr, 1 + flatIndex(arr, subs))
		MethodCode an = new MethodCode();
		emitFlatN(an, arrayListClass, longClass, objectArrayClass, alGet, longIntValue, ckBound, 1, 2, 3, 4, 5);
		an.aload(0);
		an.loadConstant(1);
		an.iload(2);
		an.iadd();
		an.invokestatic(rmGet);
		an.areturn();
		methods.add(new ArrayMethod(cp.utf8Entry(AREFN), cp.utf8Entry(AREFN_DESC), an));

		// _asetN(arr, subs, val): _rmSet(arr, 1 + flatIndex(arr, subs), val)
		MethodCode sn = new MethodCode();
		emitFlatN(sn, arrayListClass, longClass, objectArrayClass, alGet, longIntValue, ckBound, 1, 3, 4, 5, 6);
		sn.aload(0);
		sn.loadConstant(1);
		sn.iload(3);
		sn.iadd();
		sn.aload(2);
		sn.invokestatic(rmSet);
		sn.areturn();
		methods.add(new ArrayMethod(cp.utf8Entry(ASETN), cp.utf8Entry(ASETN_DESC), sn));

		// _ckArr(x): x when it is an array of any representation -- or a quantized
		// matrix, which the array inquiries take -- else the unnamed ARRAY report
		// (CK_ARRAY). The string test is stringp's: a String whose first char is the
		// quote framing it. Locals: 0 = x.
		MethodCode ck = new MethodCode();
		MethodCode.Label ckPass = ck.newLabel();
		MethodCode.Label ckNotString = ck.newLabel();
		MethodCode.Label ckFail = ck.newLabel();
		ck.aload(0);
		ck.instanceOf(arrayListClass);
		ck.ifne(ckPass);
		ck.aload(0);
		ck.instanceOf(strClass);
		ck.ifeq(ckNotString);
		emitStringTest(ck, strClass, strCharAt, 0, ckFail);
		ck.goto_(ckPass);
		ck.labelBinding(ckNotString);
		List<ClassEntry> packedShapes = new ArrayList<>(
				List.of(longArrayClass, doubleArrayClass, floatArrayClass, shortArrayClass, byteArrayClass));
		if (usesQuantized) {
			packedShapes.add(JvmQuantizedMatrixRuntimeBuilder.carrierClass(cp));
		}
		for (ClassEntry packed : packedShapes) {
			ck.aload(0);
			ck.instanceOf(packed);
			ck.ifne(ckPass);
		}
		ck.labelBinding(ckFail);
		ck.aload(0);
		ck.ldc(cp.stringEntry(OperandTypes.Kind.ARRAY.typeName()));
		ck.invokestatic(JvmOperandTypeRuntime.self(cp, selfClass, JvmOperandTypeRuntime.TE_RAW,
				JvmOperandTypeRuntime.TE_RAW_DESC));
		ck.athrow();
		ck.labelBinding(ckPass);
		ck.aload(0);
		ck.areturn();
		methods.add(new ArrayMethod(cp.utf8Entry(CK_ARRAY), cp.utf8Entry(CK_ARRAY_DESC), ck));

		// _ckFp(x): x when it is a general vector with a fill pointer, else the unnamed
		// report of (AND VECTOR (SATISFIES ARRAY-HAS-FILL-POINTER-P)) -- after _ckArr,
		// which throws the ARRAY one for a value that is no array at all. Locals: 0 = x.
		MethodRefEntry teOf = JvmOperandTypeRuntime.self(cp, selfClass, JvmOperandTypeRuntime.TE_OF,
				JvmOperandTypeRuntime.TE_OF_DESC);
		MethodCode cfp = new MethodCode();
		MethodCode.Label cfpRefuse = cfp.newLabel();
		cfp.aload(0);
		cfp.instanceOf(arrayListClass);
		cfp.ifeq(cfpRefuse);
		emitLoadHeader(cfp, arrayListClass, objectArrayClass, alGet, 0);
		cfp.loadConstant(1);
		cfp.aaload();
		cfp.ifnull(cfpRefuse);
		cfp.aload(0);
		cfp.areturn();
		cfp.labelBinding(cfpRefuse);
		cfp.aload(0);
		cfp.invokestatic(JvmOperandTypeRuntime.self(cp, selfClass, CK_ARRAY, CK_ARRAY_DESC));
		cfp.pop();
		cfp.aload(0);
		emitTypeValue(cfp, cp, objectClass, longValueOf, OperandTypes.FILL_POINTER_VECTOR_TYPE);
		cfp.invokestatic(teOf);
		cfp.athrow();
		methods.add(new ArrayMethod(cp.utf8Entry(CK_FILL_POINTER), cp.utf8Entry(CK_ARRAY_DESC), cfp));

		// _fillPointer(arr): the fill pointer (a Long); _ckFp at the site has checked the
		// array carries one. Locals: 0 = arr.
		MethodCode fpm = new MethodCode();
		emitLoadHeader(fpm, arrayListClass, objectArrayClass, alGet, 0);
		fpm.loadConstant(1);
		fpm.aaload();
		fpm.areturn();
		methods.add(new ArrayMethod(cp.utf8Entry(FILL_POINTER), cp.utf8Entry(FILL_POINTER_DESC), fpm));

		// _setFillPointer(arr, value): the store behind _ckFp; returns value. A value
		// that
		// is no integer in [0, dimension] is the unnamed report of (INTEGER 0
		// dimension), for the (SETF FILL-POINTER) wrapper to name. Locals: 0 = arr,
		// 1 = value, 2 = header, 3 = cap (int), 4-5 = v (long).
		MethodCode sfp = new MethodCode();
		emitLoadHeader(sfp, arrayListClass, objectArrayClass, alGet, 0);
		sfp.astore(2);
		emitLoadDim0(sfp, longClass, objectArrayClass, longIntValue, 2);
		sfp.istore(3);
		MethodCode.Label sfpBad = sfp.newLabel();
		MethodCode.Label sfpOk = sfp.newLabel();
		sfp.aload(1);
		sfp.instanceOf(longClass);
		sfp.ifeq(sfpBad);
		sfp.aload(1);
		sfp.checkcast(longClass);
		sfp.invokevirtual(longLongValue);
		sfp.lstore(4);
		sfp.lload(4);
		sfp.lconst_0();
		sfp.lcmp();
		sfp.iflt(sfpBad);
		sfp.lload(4);
		sfp.iload(3);
		sfp.i2l();
		sfp.lcmp();
		sfp.ifgt(sfpBad);
		sfp.goto_(sfpOk);
		sfp.labelBinding(sfpBad);
		sfp.aload(1);
		emitList(sfp, objectClass, List.of(() -> sfp.ldc(cp.stringEntry(OperandTypes.INTEGER_TYPE)), () -> {
			sfp.lconst_0();
			sfp.invokestatic(longValueOf);
		}, () -> {
			sfp.iload(3);
			sfp.i2l();
			sfp.invokestatic(longValueOf);
		}));
		sfp.invokestatic(teOf);
		sfp.athrow();
		sfp.labelBinding(sfpOk);
		sfp.aload(2);
		sfp.loadConstant(1);
		sfp.aload(1);
		sfp.aastore();
		sfp.aload(1);
		sfp.areturn();
		methods.add(new ArrayMethod(cp.utf8Entry(SET_FILL_POINTER), cp.utf8Entry(SET_FILL_POINTER_DESC), sfp));

		// _arrayHasFillPointer(arr): "t" when the header carries a fill pointer, else
		// nil (null). Locals: 0 = arr.
		MethodCode hfp = new MethodCode();
		emitHeaderSlotToBool(hfp, arrayListClass, objectArrayClass, alGet, cp, 1);
		methods.add(new ArrayMethod(cp.utf8Entry(HAS_FILL_POINTER), cp.utf8Entry(HAS_FILL_POINTER_DESC), hfp));

		// _adjustableArrayP(arr): "t" when the array was created :adjustable (the raw
		// truthy argument is stored verbatim), else nil. Locals: 0 = arr.
		MethodCode adp = new MethodCode();
		emitHeaderSlotToBool(adp, arrayListClass, objectArrayClass, alGet, cp, 2);
		methods.add(new ArrayMethod(cp.utf8Entry(ADJUSTABLE_ARRAY_P), cp.utf8Entry(ADJUSTABLE_ARRAY_P_DESC), adp));

		// _vectorPush(val, arr): store val at the fill pointer and return the index used
		// (a Long), or nil (null) when the vector is full. Locals: 0 = val, 1 = arr,
		// 2 = header, 3 = fp (int), 4 = cap (int).
		MethodCode vp = new MethodCode();
		emitLoadHeader(vp, arrayListClass, objectArrayClass, alGet, 1);
		vp.astore(2);
		emitLoadFillPointer(vp, longClass, longIntValue, 2);
		vp.istore(3);
		emitLoadDim0(vp, longClass, objectArrayClass, longIntValue, 2);
		vp.istore(4);
		MethodCode.Label vpStore = vp.newLabel();
		vp.iload(3);
		vp.iload(4);
		vp.if_icmplt(vpStore);
		vp.aconst_null();
		vp.areturn();
		vp.labelBinding(vpStore);
		emitStoreAtFillPointerAndAdvance(vp, rmSet, longValueOf, 0, 1, 2, 3);
		methods.add(new ArrayMethod(cp.utf8Entry(VECTOR_PUSH), cp.utf8Entry(VECTOR_PUSH_DESC), vp));

		// _vectorPop(arr): decrement the fill pointer and return the element it passed.
		// Locals: 0 = arr, 1 = header, 2 = fp (int).
		MethodCode vpop = new MethodCode();
		emitLoadHeader(vpop, arrayListClass, objectArrayClass, alGet, 0);
		vpop.astore(1);
		emitLoadFillPointer(vpop, longClass, longIntValue, 1);
		vpop.istore(2);
		MethodCode.Label vpopOk = vpop.newLabel();
		vpop.iload(2);
		vpop.ifne(vpopOk);
		emitThrow(vpop, rtExClass, rtExInit, cp.stringEntry(OperandTypes.VECTOR_POP_EMPTY));
		vpop.labelBinding(vpopOk);
		vpop.aload(1);
		vpop.loadConstant(1);
		vpop.iload(2);
		vpop.loadConstant(1);
		vpop.isub();
		vpop.i2l();
		vpop.invokestatic(longValueOf);
		vpop.aastore();
		// _rmGet(arr, 1 + (fp - 1)) == _rmGet(arr, fp) -- through the displacement-aware
		// primitive, so a displaced fill-pointered view pops its TARGET's element.
		vpop.aload(0);
		vpop.iload(2);
		vpop.invokestatic(rmGet);
		vpop.areturn();
		methods.add(new ArrayMethod(cp.utf8Entry(VECTOR_POP), cp.utf8Entry(VECTOR_POP_DESC), vpop));

		// _vectorPushExtend(val, arr, ext): like _vectorPush but grows the backing store
		// when the vector is full, updating the stored dimension size. ext is the shared
		// "not supplied" sentinel (ArrayGrowth.NO_EXTENSION) when the optional argument
		// was omitted. Locals: 0 = val, 1 = arr, 2 = ext, 3 = header,
		// 4 = fp (int), 5 = cap (int), 6 = ext (int), 7 = newCap (int), 8 = the fill.
		MethodCode vpe = new MethodCode();
		emitLoadHeader(vpe, arrayListClass, objectArrayClass, alGet, 1);
		vpe.astore(3);
		emitLoadFillPointer(vpe, longClass, longIntValue, 3);
		vpe.istore(4);
		emitLoadDim0(vpe, longClass, objectArrayClass, longIntValue, 3);
		vpe.istore(5);
		MethodCode.Label vpeStore = vpe.newLabel();
		vpe.iload(4);
		vpe.iload(5);
		vpe.if_icmplt(vpeStore);
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
		MethodCode.Label vpeDefaultGrowth = vpe.newLabel();
		MethodCode.Label vpeDoubleCap = vpe.newLabel();
		MethodCode.Label vpeCapReady = vpe.newLabel();
		vpe.iload(6);
		vpe.loadConstant(ArrayGrowth.NO_EXTENSION);
		vpe.if_icmple(vpeDefaultGrowth);
		vpe.iload(5);
		vpe.iload(6);
		vpe.iadd();
		vpe.istore(7);
		vpe.goto_(vpeCapReady);
		vpe.labelBinding(vpeDefaultGrowth);
		vpe.iload(5);
		vpe.loadConstant(ArrayGrowth.MIN_CAPACITY);
		vpe.if_icmpge(vpeDoubleCap);
		vpe.loadConstant(ArrayGrowth.MIN_CAPACITY);
		vpe.istore(7);
		vpe.goto_(vpeCapReady);
		vpe.labelBinding(vpeDoubleCap);
		vpe.iload(5);
		vpe.loadConstant(ArrayGrowth.GROWTH_FACTOR);
		vpe.imul();
		vpe.istore(7);
		vpe.labelBinding(vpeCapReady);
		// while (list.size() - 1 < newCap) list.add(_arrayDefaultElement(arr)) -- the
		// slots the growth OPENS take the REMEMBERED element type's own zero, the same
		// fill make-array gives an unsupplied element, so a vector asked to hold
		// characters, bytes or floats never reads back nil above its old capacity (it is
		// null, i.e. nil, for the general vector, which is what this always added).
		// Local 8 holds it: one call, not one per opened slot.
		vpe.aload(1);
		vpe.invokestatic(defaultElement);
		vpe.astore(8);
		MethodCode.Label growLoop = vpe.newLabel();
		MethodCode.Label growDone = vpe.newLabel();
		vpe.labelBinding(growLoop);
		vpe.aload(1);
		vpe.checkcast(arrayListClass);
		vpe.invokevirtual(alSize);
		vpe.loadConstant(1);
		vpe.isub();
		vpe.iload(7);
		vpe.if_icmpge(growDone);
		vpe.aload(1);
		vpe.checkcast(arrayListClass);
		vpe.aload(8);
		vpe.invokevirtual(alAdd);
		vpe.pop();
		vpe.goto_(growLoop);
		vpe.labelBinding(growDone);
		// dims[0] = Long.valueOf(newCap)
		vpe.aload(3);
		vpe.loadConstant(0);
		vpe.aaload();
		vpe.checkcast(objectArrayClass);
		vpe.loadConstant(0);
		vpe.iload(7);
		vpe.i2l();
		vpe.invokestatic(longValueOf);
		vpe.aastore();
		vpe.labelBinding(vpeStore);
		emitStoreAtFillPointerAndAdvance(vpe, rmSet, longValueOf, 0, 1, 3, 4);
		methods.add(new ArrayMethod(cp.utf8Entry(VECTOR_PUSH_EXTEND), cp.utf8Entry(VECTOR_PUSH_EXTEND_DESC), vpe));

		// _rmGet(list, idx): the single data-read primitive (idx is the 1-based list
		// index). Follows the displacement chain: while the header is a 5-element
		// {dims, fp, adj, target, offset} with a non-null target, add the offset and
		// hop to the target list. A length-6 header is the PACKED shape: the element is
		// read from the long[] in header[5] (the sentinel reads back as nil).
		// Locals: 0 = list, 1 = idx, 2 = header, 3/4 = v (long).
		MethodCode rg = new MethodCode();
		MethodCode.Label rgGeneral = rg.newLabel();
		MethodCode.Label rgBox = rg.newLabel();
		MethodCode.Label rgString = rg.newLabel();
		emitResolveDisplacement(rg, arrayListClass, longClass, objectArrayClass, alGet, longIntValue, 0, 1, 2);
		emitLandedOnString(rg, 2, rgString);
		rg.aload(2);
		rg.arraylength();
		rg.loadConstant(6);
		rg.if_icmpne(rgGeneral);
		rg.aload(2);
		rg.loadConstant(5);
		rg.aaload();
		rg.checkcast(longArrayClass);
		rg.iload(1);
		rg.loadConstant(1);
		rg.isub();
		rg.laload();
		rg.lstore(3);
		rg.lload(3);
		rg.ldc(nilSentinel);
		rg.lcmp();
		rg.ifne(rgBox);
		rg.aconst_null();
		rg.areturn();
		rg.labelBinding(rgBox);
		rg.lload(3);
		rg.invokestatic(longValueOf);
		rg.areturn();
		rg.labelBinding(rgGeneral);
		rg.aload(0);
		rg.checkcast(arrayListClass);
		rg.iload(1);
		rg.invokevirtual(alGet);
		rg.areturn();
		// The NON-ARRAY target arm: header[3] is what the view aliases -- a PACKED
		// vector (the elements live unboxed in it) or the immutable runtime string a
		// string view aliases.
		rg.labelBinding(rgString);
		MethodCode.Label rgRealString = rg.newLabel();
		rg.aload(2);
		rg.loadConstant(3);
		rg.aaload();
		rg.astore(7);
		emitPackedTargetGet(rg, 7, 1, byteArrayClass, longArrayClass, doubleArrayClass, floatArrayClass,
				shortArrayClass, bf16Value, longValueOf, doubleBoxValueOf, rgRealString);
		// The string-view arm: header[3] is the immutable runtime string this view
		// aliases and idx is the 1-based character index into it. Reads by CODE POINT
		// through _cpoff (the content lives in [1, length-1), inside the framing
		// quotes) and boxes as the runtime CHARACTER int[]{cp}, exactly like _aref1's
		// own string branch.
		rg.labelBinding(rgRealString);
		rg.aload(2);
		rg.loadConstant(3);
		rg.aaload();
		rg.checkcast(strClass);
		rg.astore(5);
		rg.aload(5);
		rg.aload(5);
		rg.iload(1);
		rg.loadConstant(1);
		rg.isub();
		rg.invokestatic(strCpOffset);
		rg.invokevirtual(strCodePointAt);
		rg.istore(6);
		rg.loadConstant(1);
		rg.newarray(TypeKind.INT);
		rg.dup();
		rg.loadConstant(0);
		rg.iload(6);
		rg.iastore();
		rg.areturn();
		methods.add(new ArrayMethod(cp.utf8Entry(RM_GET), cp.utf8Entry(RM_GET_DESC), rg));

		// _rmSet(list, idx, val): the single data-write primitive; returns val. A
		// PACKED array (length-6 header) stores an in-range Long unboxed; any other
		// value -- or the sentinel integer itself -- widens the array in place first
		// and falls through to the boxed store.
		// Locals: 0 = list, 1 = idx, 2 = val, 3 = header, 4/5 = v (long).
		MethodCode rs = new MethodCode();
		MethodCode.Label rsGeneral = rs.newLabel();
		MethodCode.Label rsWiden = rs.newLabel();
		MethodCode.Label rsString = rs.newLabel();
		MethodRefEntry strToCharVec = cp.methodRef(selfClass, STR_TO_CHAR_VEC, STR_TO_CHAR_VEC_DESC);
		emitResolveDisplacement(rs, arrayListClass, longClass, objectArrayClass, alGet, longIntValue, 0, 1, 3);
		emitLandedOnString(rs, 3, rsString);
		rs.aload(3);
		rs.arraylength();
		rs.loadConstant(6);
		rs.if_icmpne(rsGeneral);
		rs.aload(2);
		rs.instanceOf(longClass);
		rs.ifeq(rsWiden);
		rs.aload(2);
		rs.checkcast(longClass);
		rs.invokevirtual(longLongValue);
		rs.lstore(4);
		rs.lload(4);
		rs.ldc(nilSentinel);
		rs.lcmp();
		rs.ifeq(rsWiden);
		rs.aload(3);
		rs.loadConstant(5);
		rs.aaload();
		rs.checkcast(longArrayClass);
		rs.iload(1);
		rs.loadConstant(1);
		rs.isub();
		rs.lload(4);
		rs.lastore();
		rs.aload(2);
		rs.areturn();
		rs.labelBinding(rsWiden);
		rs.aload(0);
		rs.invokestatic(widen);
		rs.labelBinding(rsGeneral);
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
		rs.labelBinding(rsString);
		MethodCode.Label rsRealString = rs.newLabel();
		rs.aload(3);
		rs.loadConstant(3);
		rs.aaload();
		rs.astore(7);
		emitPackedTargetSet(rs, cp, 7, 1, 2, 8, 9, 10, 11, 12, 14, byteArrayClass, longArrayClass, doubleArrayClass,
				floatArrayClass, shortArrayClass, bf16Value, bf16Bits, numberClass, numberLongValue, numberDoubleValue,
				longValueOf, doubleBoxValueOf, dblCoerce, rtExClass, rtExInit, rsRealString);
		// The string-view arm: the view aliases an IMMUTABLE runtime string, which no
		// write can reach. Promote it once -- header[3] becomes a mutable character
		// vector holding the same characters -- and store into that; every later access
		// through this view (and through array-displacement's answer) sees the promoted
		// vector, so the view behaves as a mutable string from here on.
		rs.labelBinding(rsRealString);
		rs.aload(3);
		rs.loadConstant(3);
		rs.aaload();
		rs.checkcast(strClass);
		rs.invokestatic(strToCharVec);
		rs.astore(6);
		rs.aload(3);
		rs.loadConstant(3);
		rs.aload(6);
		rs.aastore();
		rs.aload(6);
		rs.astore(0);
		rs.goto_(rsGeneral);
		methods.add(new ArrayMethod(cp.utf8Entry(RM_SET), cp.utf8Entry(RM_SET_DESC), rs));

		// _strToCharVec(s): the immutable runtime string s copied into a fresh mutable
		// character vector -- an ArrayList whose slot 0 is the length-4 header
		// {dims, fillPointer, null, null} and whose slots 1.. hold one int[]{codePoint}
		// per character. Characters are read BY CODE POINT (_cpoff + codePointAt), so a
		// supplementary code point becomes one element, as everywhere else.
		// Locals: 0 = s, 1 = n, 2 = list, 3 = i.
		MethodCode tv = new MethodCode();
		tv.aload(0);
		tv.invokestatic(strCount);
		tv.istore(1);
		tv.new_(arrayListClass);
		tv.dup();
		tv.invokespecial(alInit);
		tv.astore(2);
		tv.aload(2);
		tv.loadConstant(4);
		tv.anewarray(objectClass);
		tv.dup();
		tv.loadConstant(0);
		tv.loadConstant(1);
		tv.anewarray(objectClass);
		tv.dup();
		tv.loadConstant(0);
		tv.iload(1);
		tv.i2l();
		tv.invokestatic(longValueOf);
		tv.aastore();
		tv.aastore();
		tv.dup();
		tv.loadConstant(1);
		tv.iload(1);
		tv.i2l();
		tv.invokestatic(longValueOf);
		tv.aastore();
		tv.invokevirtual(alAdd);
		tv.pop();
		tv.loadConstant(0);
		tv.istore(3);
		MethodCode.Label tvLoop = tv.newLabel();
		MethodCode.Label tvDone = tv.newLabel();
		tv.labelBinding(tvLoop);
		tv.iload(3);
		tv.iload(1);
		tv.if_icmpge(tvDone);
		tv.aload(2);
		tv.loadConstant(1);
		tv.newarray(TypeKind.INT);
		tv.dup();
		tv.loadConstant(0);
		tv.aload(0);
		tv.aload(0);
		tv.iload(3);
		tv.invokestatic(strCpOffset);
		tv.invokevirtual(strCodePointAt);
		tv.iastore();
		tv.invokevirtual(alAdd);
		tv.pop();
		tv.iinc(3, 1);
		tv.goto_(tvLoop);
		tv.labelBinding(tvDone);
		tv.aload(2);
		tv.areturn();
		methods.add(new ArrayMethod(cp.utf8Entry(STR_TO_CHAR_VEC), cp.utf8Entry(STR_TO_CHAR_VEC_DESC), tv));

		// _arrayWiden(list): converts a PACKED array (length-6 header, long[] data) to
		// the boxed shape IN PLACE -- header replaced by {dims, null, null, null, et},
		// each long[] element appended boxed (the sentinel as
		// null/nil). A non-packed array passes through untouched; the ArrayList object
		// is the array's identity, so every alias sees the widened shape.
		// Locals: 0 = list, 1 = header, 2 = data, 3 = i, 4/5 = v (long).
		MethodCode wd = new MethodCode();
		MethodCode.Label wdDone = wd.newLabel();
		MethodCode.Label wdLoop = wd.newLabel();
		MethodCode.Label wdBox = wd.newLabel();
		MethodCode.Label wdAdd = wd.newLabel();
		emitLoadHeader(wd, arrayListClass, objectArrayClass, alGet, 0);
		wd.astore(1);
		wd.aload(1);
		wd.arraylength();
		wd.loadConstant(6);
		wd.if_icmpne(wdDone);
		wd.aload(1);
		wd.loadConstant(5);
		wd.aaload();
		wd.checkcast(longArrayClass);
		wd.astore(2);
		// list.set(0, new Object[]{header[0], null, null, null, header[4]}) -- the
		// REMEMBERED element type (slot 4) survives the widening, so an array that was
		// asked for (unsigned-byte 8) still answers it after a store widened it.
		wd.aload(0);
		wd.checkcast(arrayListClass);
		wd.loadConstant(0);
		wd.loadConstant(5);
		wd.anewarray(objectClass);
		wd.dup();
		wd.loadConstant(0);
		wd.aload(1);
		wd.loadConstant(0);
		wd.aaload();
		wd.aastore();
		wd.dup();
		wd.loadConstant(4);
		wd.aload(1);
		wd.loadConstant(4);
		wd.aaload();
		wd.aastore();
		wd.invokevirtual(alSet);
		wd.pop();
		// for (i = 0; i < data.length; i++) list.add(box(data[i]))
		wd.loadConstant(0);
		wd.istore(3);
		wd.labelBinding(wdLoop);
		wd.iload(3);
		wd.aload(2);
		wd.arraylength();
		wd.if_icmpge(wdDone);
		wd.aload(2);
		wd.iload(3);
		wd.laload();
		wd.lstore(4);
		wd.aload(0);
		wd.checkcast(arrayListClass);
		wd.lload(4);
		wd.ldc(nilSentinel);
		wd.lcmp();
		wd.ifne(wdBox);
		wd.aconst_null();
		wd.goto_(wdAdd);
		wd.labelBinding(wdBox);
		wd.lload(4);
		wd.invokestatic(longValueOf);
		wd.labelBinding(wdAdd);
		wd.invokevirtual(alAdd);
		wd.pop();
		wd.iinc(3, 1);
		wd.goto_(wdLoop);
		wd.labelBinding(wdDone);
		wd.return_();
		methods.add(new ArrayMethod(cp.utf8Entry(WIDEN), cp.utf8Entry(WIDEN_DESC), wd));

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
		MethodCode md = new MethodCode();
		int mdDims = 0, mdTarget = 1, mdOffset = 2, mdFp = 3, mdAdj = 4, mdList = 5, mdTotal = 6, mdDimsArr = 7,
				mdIdx = 8, mdCur = 9, mdN = 10, mdOff = 11, mdTargetHeader = 12, mdProduct = 13, mdM = 14,
				mdHeaderSize = 15, mdFpVal = 16, mdFpScratch = 17;
		md.new_(arrayListClass);
		md.dup();
		md.invokespecial(alInit);
		md.astore(mdList);
		emitParseDims(md, objectClass, objectArrayClass, dimsTotal, mdDims, mdDimsArr, mdTotal, mdCur, mdN, mdIdx);
		// off = offsetArg == null ? 0 : ((Long) offsetArg).intValue()
		MethodCode.Label offGiven = md.newLabel();
		MethodCode.Label offDone = md.newLabel();
		md.aload(mdOffset);
		md.ifnonnull(offGiven);
		md.loadConstant(0);
		md.istore(mdOff);
		md.goto_(offDone);
		md.labelBinding(offGiven);
		md.aload(mdOffset);
		md.checkcast(longClass);
		md.invokevirtual(longIntValue);
		md.istore(mdOff);
		md.labelBinding(offDone);
		// targetTotal = the target's element count, and headerSize = 7 when the target
		// is a STRING (an immutable runtime string, a mutable character vector, or
		// another string view) so the result is a string VIEW rather than a bare array
		// view: 7 is the header-length tag _strv and stringp read, exactly as 4 marks a
		// character vector. The shape follows the TARGET, not :element-type -- the
		// portable substring idiom passes the target's own (array-element-type seq).
		MethodCode.Label mdStr = md.newLabel();
		MethodCode.Label mdHaveTotal = md.newLabel();
		MethodCode.Label mdViewTag = md.newLabel();
		MethodCode.Label mdIv = md.newLabel();
		MethodCode.Label mdOctets = md.newLabel();
		MethodCode.Label mdDv = md.newLabel();
		MethodCode.Label mdFv = md.newLabel();
		MethodCode.Label mdBv = md.newLabel();
		md.loadConstant(5);
		md.istore(mdHeaderSize);
		md.aload(mdTarget);
		md.instanceOf(strClass);
		md.ifne(mdStr);
		// A PACKED target's element count is its representation's own: an integer
		// vector is byte[]{8, e0, ...} or long[]{width, e0, ...} (length - 1 elements)
		// and a float array double[]/float[]{rank, dims..., e0, ...} (length - 1 -
		// rank). The view over one is a plain length-5 array view -- only a STRING
		// target makes a string view.
		md.aload(mdTarget);
		md.instanceOf(byteArrayClass);
		md.ifne(mdOctets);
		md.aload(mdTarget);
		md.instanceOf(longArrayClass);
		md.ifne(mdIv);
		md.aload(mdTarget);
		md.instanceOf(doubleArrayClass);
		md.ifne(mdDv);
		md.aload(mdTarget);
		md.instanceOf(floatArrayClass);
		md.ifne(mdFv);
		md.aload(mdTarget);
		md.instanceOf(shortArrayClass);
		md.ifne(mdBv);
		emitLoadHeader(md, arrayListClass, objectArrayClass, alGet, mdTarget);
		md.astore(mdTargetHeader);
		emitDimsProduct(md, longClass, objectArrayClass, longIntValue, mdTargetHeader, mdProduct, mdM);
		md.istore(mdProduct);
		md.aload(mdTargetHeader);
		md.arraylength();
		md.loadConstant(4);
		md.if_icmpeq(mdViewTag);
		md.aload(mdTargetHeader);
		md.arraylength();
		md.loadConstant(7);
		md.if_icmpeq(mdViewTag);
		md.goto_(mdHaveTotal);
		md.labelBinding(mdOctets);
		md.aload(mdTarget);
		md.checkcast(byteArrayClass);
		md.arraylength();
		md.loadConstant(1);
		md.isub();
		md.istore(mdProduct);
		md.goto_(mdHaveTotal);
		md.labelBinding(mdIv);
		md.aload(mdTarget);
		md.checkcast(longArrayClass);
		md.arraylength();
		md.loadConstant(1);
		md.isub();
		md.istore(mdProduct);
		md.goto_(mdHaveTotal);
		md.labelBinding(mdDv);
		md.aload(mdTarget);
		md.checkcast(doubleArrayClass);
		md.arraylength();
		md.aload(mdTarget);
		md.checkcast(doubleArrayClass);
		md.loadConstant(0);
		md.daload();
		md.d2i();
		md.loadConstant(1);
		md.iadd();
		md.isub();
		md.istore(mdProduct);
		md.goto_(mdHaveTotal);
		md.labelBinding(mdFv);
		md.aload(mdTarget);
		md.checkcast(floatArrayClass);
		md.arraylength();
		md.aload(mdTarget);
		md.checkcast(floatArrayClass);
		md.loadConstant(0);
		md.faload();
		md.f2i();
		md.loadConstant(1);
		md.iadd();
		md.isub();
		md.istore(mdProduct);
		md.goto_(mdHaveTotal);
		// A bfloat16 target: length - (1 + 2 * rank), the two-slot header
		// (JvmPackedFloatWidth.BFLOAT16 owns the offset).
		md.labelBinding(mdBv);
		md.aload(mdTarget);
		md.checkcast(shortArrayClass);
		md.arraylength();
		md.aload(mdTarget);
		md.checkcast(shortArrayClass);
		JvmPackedFloatWidth.BFLOAT16.loadRank(md);
		JvmPackedFloatWidth.BFLOAT16.emitDataOffset(md);
		md.isub();
		md.istore(mdProduct);
		md.goto_(mdHaveTotal);
		md.labelBinding(mdStr);
		md.aload(mdTarget);
		md.checkcast(strClass);
		md.invokestatic(strCount);
		md.istore(mdProduct);
		md.labelBinding(mdViewTag);
		md.loadConstant(7);
		md.istore(mdHeaderSize);
		md.labelBinding(mdHaveTotal);
		// require 0 <= off and total + off <= targetTotal
		MethodCode.Label mdBad = md.newLabel();
		MethodCode.Label mdOk = md.newLabel();
		md.iload(mdOff);
		md.iflt(mdBad);
		md.iload(mdTotal);
		md.iload(mdOff);
		md.iadd();
		md.iload(mdProduct);
		md.if_icmpgt(mdBad);
		md.goto_(mdOk);
		md.labelBinding(mdBad);
		emitThrow(md, rtExClass, rtExInit,
				cp.stringEntry("make-array: :displaced-to array is too small for the requested view"));
		md.labelBinding(mdOk);
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
		md.loadConstant(0);
		md.aload(mdDimsArr);
		md.aastore();
		md.dup();
		md.loadConstant(1);
		md.aload(mdFpVal);
		md.aastore();
		md.dup();
		md.loadConstant(2);
		md.aload(mdAdj);
		md.aastore();
		md.dup();
		md.loadConstant(3);
		md.aload(mdTarget);
		md.aastore();
		md.dup();
		md.loadConstant(4);
		md.iload(mdOff);
		md.i2l();
		md.invokestatic(longValueOf);
		md.aastore();
		md.invokevirtual(alAdd);
		md.pop();
		md.aload(mdList);
		md.areturn();
		methods.add(new ArrayMethod(cp.utf8Entry(MAKE_DISPLACED), cp.utf8Entry(MAKE_DISPLACED_DESC), md));

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
		MethodCode un = new MethodCode();
		// An immutable string owns its characters and carries no header at all (a
		// string VIEW is a length-7 header, not a String), so it is returned unchanged
		// like any other undisplaced array -- adjust-array's expansion calls this
		// unconditionally, on every representation it accepts. Mirrors
		// _arrayDispTarget's same check.
		MethodCode.Label unNotString = un.newLabel();
		un.aload(0);
		un.instanceOf(strClass);
		un.ifeq(unNotString);
		un.aload(0);
		un.areturn();
		un.labelBinding(unNotString);
		emitLoadHeader(un, arrayListClass, objectArrayClass, alGet, 0);
		un.astore(1);
		MethodCode.Label unDisplaced = un.newLabel();
		un.aload(1);
		un.arraylength();
		un.loadConstant(4);
		un.if_icmpge(unDisplaced);
		un.aload(0);
		un.areturn();
		un.labelBinding(unDisplaced);
		MethodCode.Label unGo = un.newLabel();
		un.aload(1);
		un.loadConstant(3);
		un.aaload();
		un.ifnonnull(unGo);
		un.aload(0);
		un.areturn();
		un.labelBinding(unGo);
		emitDimsProduct(un, longClass, objectArrayClass, longIntValue, 1, 2, 6);
		un.istore(2);
		// elems[i] = _rmGet(arr, 1 + i) -- read through the chain BEFORE the header is
		// replaced, since that is what the reads resolve against.
		un.iload(2);
		un.anewarray(objectClass);
		un.astore(3);
		un.loadConstant(0);
		un.istore(4);
		MethodCode.Label unRead = un.newLabel();
		MethodCode.Label unReadDone = un.newLabel();
		un.labelBinding(unRead);
		un.iload(4);
		un.iload(2);
		un.if_icmpge(unReadDone);
		un.aload(3);
		un.iload(4);
		un.aload(0);
		un.loadConstant(1);
		un.iload(4);
		un.iadd();
		un.invokestatic(rmGet);
		un.aastore();
		un.iinc(4, 1);
		un.goto_(unRead);
		un.labelBinding(unReadDone);
		// et: the element type the CHAIN END remembers, read exactly as
		// _arrayElementType reads it (it hops the same way and stops on the same facts).
		// The view answered this while it was still a view, so the freed offset slot has
		// to keep answering it -- an array's element type is fixed when it is made.
		MethodCode.Label unWalk = un.newLabel();
		MethodCode.Label unWalkDone = un.newLabel();
		MethodCode.Label unWalkOwn = un.newLabel();
		un.aconst_null();
		un.astore(8);
		un.aload(1);
		un.astore(7);
		un.labelBinding(unWalk);
		un.aload(7);
		un.arraylength();
		un.loadConstant(4);
		un.if_icmple(unWalkDone);
		un.aload(7);
		un.loadConstant(3);
		un.aaload();
		un.astore(9);
		un.aload(9);
		un.ifnull(unWalkOwn);
		// A PACKED chain end's element type IS its representation, so the freed offset
		// slot records it exactly as it records a general array's remembered label: the
		// view answered it while it was a view, and an array's element type is fixed
		// when it is made.
		MethodCode.Label unNotPacked = un.newLabel();
		emitPackedElementTypeInto(un, cp, 9, 8, byteArrayClass, longArrayClass, doubleArrayClass, floatArrayClass,
				shortArrayClass, longValueOf, objectClass, unNotPacked, unWalkDone);
		un.labelBinding(unNotPacked);
		// A String chain end remembers nothing here: character-ness is the length-7
		// header's own answer (7 -> 4 below), not a remembered designator.
		un.aload(9);
		un.instanceOf(arrayListClass);
		un.ifeq(unWalkDone);
		emitLoadHeader(un, arrayListClass, objectArrayClass, alGet, 9);
		un.astore(7);
		un.goto_(unWalk);
		un.labelBinding(unWalkOwn);
		un.aload(7);
		un.loadConstant(4);
		un.aaload();
		un.astore(8);
		un.labelBinding(unWalkDone);
		MethodCode.Label unString = un.newLabel();
		MethodCode.Label unThree = un.newLabel();
		MethodCode.Label unLenReady = un.newLabel();
		MethodCode.Label unNoEt = un.newLabel();
		un.aload(1);
		un.arraylength();
		un.loadConstant(7);
		un.if_icmpeq(unString);
		un.aload(8);
		un.ifnull(unThree);
		un.loadConstant(5);
		un.goto_(unLenReady);
		un.labelBinding(unThree);
		un.loadConstant(3);
		un.goto_(unLenReady);
		un.labelBinding(unString);
		un.loadConstant(4);
		un.labelBinding(unLenReady);
		un.anewarray(objectClass);
		emitCopyHeaderSlots(un, 1, 3);
		un.astore(5);
		un.aload(5);
		un.arraylength();
		un.loadConstant(5);
		un.if_icmpne(unNoEt);
		un.aload(5);
		un.loadConstant(4);
		un.aload(8);
		un.aastore();
		un.labelBinding(unNoEt);
		un.aload(0);
		un.checkcast(arrayListClass);
		un.loadConstant(0);
		un.aload(5);
		un.invokevirtual(alSet);
		un.pop();
		un.loadConstant(0);
		un.istore(4);
		MethodCode.Label unFill = un.newLabel();
		MethodCode.Label unFillDone = un.newLabel();
		un.labelBinding(unFill);
		un.iload(4);
		un.iload(2);
		un.if_icmpge(unFillDone);
		un.aload(0);
		un.checkcast(arrayListClass);
		un.aload(3);
		un.iload(4);
		un.aaload();
		un.invokevirtual(alAdd);
		un.pop();
		un.iinc(4, 1);
		un.goto_(unFill);
		un.labelBinding(unFillDone);
		un.aload(0);
		un.areturn();
		methods.add(new ArrayMethod(cp.utf8Entry(UNDISPLACE), cp.utf8Entry(UNDISPLACE_DESC), un));

		// _arrayBecome(a, b): replace a's dims, fill pointer and data with b's in place
		// (the in-place half of adjust-array on an adjustable array); returns a. The
		// adjustable flag (header slot 2) is kept. Both arrays are widened first: the
		// size-based element copy below reads the BOXED data slots, so a packed operand
		// (a freshly made temp with neither fill pointer nor adjustability) must take
		// the boxed shape before it. Locals: 0 = a, 1 = b, 2 = headerA, 3 = headerB,
		// 4 = i.
		MethodCode bc = new MethodCode();
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
		bc.loadConstant(0);
		bc.aload(3);
		bc.loadConstant(0);
		bc.aaload();
		bc.aastore();
		bc.aload(2);
		bc.loadConstant(1);
		bc.aload(3);
		bc.loadConstant(1);
		bc.aaload();
		bc.aastore();
		// while (a.size() > b.size()) a.remove(a.size() - 1)
		MethodCode.Label shrinkLoop = bc.newLabel();
		MethodCode.Label shrinkDone = bc.newLabel();
		bc.labelBinding(shrinkLoop);
		bc.aload(0);
		bc.checkcast(arrayListClass);
		bc.invokevirtual(alSize);
		bc.aload(1);
		bc.checkcast(arrayListClass);
		bc.invokevirtual(alSize);
		bc.if_icmple(shrinkDone);
		bc.aload(0);
		bc.checkcast(arrayListClass);
		bc.aload(0);
		bc.checkcast(arrayListClass);
		bc.invokevirtual(alSize);
		bc.loadConstant(1);
		bc.isub();
		bc.invokevirtual(alRemove);
		bc.pop();
		bc.goto_(shrinkLoop);
		bc.labelBinding(shrinkDone);
		// while (a.size() < b.size()) a.add(null)
		MethodCode.Label growLoop2 = bc.newLabel();
		MethodCode.Label growDone2 = bc.newLabel();
		bc.labelBinding(growLoop2);
		bc.aload(0);
		bc.checkcast(arrayListClass);
		bc.invokevirtual(alSize);
		bc.aload(1);
		bc.checkcast(arrayListClass);
		bc.invokevirtual(alSize);
		bc.if_icmpge(growDone2);
		bc.aload(0);
		bc.checkcast(arrayListClass);
		bc.aconst_null();
		bc.invokevirtual(alAdd);
		bc.pop();
		bc.goto_(growLoop2);
		bc.labelBinding(growDone2);
		// for (i = 1; i < b.size(); i++) a.set(i, b.get(i))
		bc.loadConstant(1);
		bc.istore(4);
		MethodCode.Label copyLoop = bc.newLabel();
		MethodCode.Label copyDone = bc.newLabel();
		bc.labelBinding(copyLoop);
		bc.iload(4);
		bc.aload(1);
		bc.checkcast(arrayListClass);
		bc.invokevirtual(alSize);
		bc.if_icmpge(copyDone);
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
		bc.goto_(copyLoop);
		bc.labelBinding(copyDone);
		bc.aload(0);
		bc.areturn();
		methods.add(new ArrayMethod(cp.utf8Entry(ARRAY_BECOME), cp.utf8Entry(ARRAY_BECOME_DESC), bc));

		// _arrayBecomeDisplaced(a, dims, target, offset, fp): turn a (an adjustable
		// array) IN PLACE into a displaced view over target and return a -- the in-place
		// half of adjust-array with :displaced-to on an adjustable array, which keeps
		// its identity (any other adjustable adjustment is a %array-become). The
		// displaced header is the SAME one _arrayMakeDisplaced builds (5 slots, or 7 for
		// a string target), reused verbatim: the adjustable slot is read out of a's own
		// header, so the raw :adjustable argument survives. a's data slots are dropped
		// and slot 0 replaced by the moved header; the ArrayList object IS a's identity,
		// so eq holds. Locals: 0 = a, 1 = dims, 2 = target, 3 = offset, 4 = fp,
		// 5 = headerA, 6 = hdr.
		MethodCode bd = new MethodCode();
		emitLoadHeader(bd, arrayListClass, objectArrayClass, alGet, 0);
		bd.astore(5);
		bd.aload(1);
		bd.aload(2);
		bd.aload(3);
		bd.aload(4);
		bd.aload(5);
		bd.loadConstant(2);
		bd.aaload();
		bd.invokestatic(makeDisplaced);
		bd.checkcast(arrayListClass);
		bd.loadConstant(0);
		bd.invokevirtual(alGet);
		bd.astore(6);
		// while (a.size() > 1) a.remove(a.size() - 1)
		MethodCode.Label bdShrink = bd.newLabel();
		MethodCode.Label bdDone = bd.newLabel();
		bd.labelBinding(bdShrink);
		bd.aload(0);
		bd.checkcast(arrayListClass);
		bd.invokevirtual(alSize);
		bd.loadConstant(1);
		bd.if_icmple(bdDone);
		bd.aload(0);
		bd.checkcast(arrayListClass);
		bd.aload(0);
		bd.checkcast(arrayListClass);
		bd.invokevirtual(alSize);
		bd.loadConstant(1);
		bd.isub();
		bd.invokevirtual(alRemove);
		bd.pop();
		bd.goto_(bdShrink);
		bd.labelBinding(bdDone);
		// a.set(0, hdr)
		bd.aload(0);
		bd.checkcast(arrayListClass);
		bd.loadConstant(0);
		bd.aload(6);
		bd.invokevirtual(alSet);
		bd.pop();
		bd.aload(0);
		bd.areturn();
		methods
			.add(new ArrayMethod(cp.utf8Entry(ARRAY_BECOME_DISPLACED), cp.utf8Entry(ARRAY_BECOME_DISPLACED_DESC), bd));

		// _arrayDispTarget(arr): the displacement target, or null (nil).
		// Locals: 0 = arr, 1 = header.
		MethodCode dt = new MethodCode();
		MethodCode.Label dtNil = dt.newLabel();
		// A runtime string owns its storage and carries no header (a string VIEW is a
		// length-7 header, not a String), so it answers nil like any other undisplaced
		// array.
		dt.aload(0);
		dt.instanceOf(strClass);
		dt.ifne(dtNil);
		emitLoadHeader(dt, arrayListClass, objectArrayClass, alGet, 0);
		dt.astore(1);
		dt.aload(1);
		dt.arraylength();
		dt.loadConstant(4);
		dt.if_icmple(dtNil);
		dt.aload(1);
		dt.loadConstant(3);
		dt.aaload();
		dt.areturn();
		dt.labelBinding(dtNil);
		dt.aconst_null();
		dt.areturn();
		methods.add(new ArrayMethod(cp.utf8Entry(DISP_TARGET), cp.utf8Entry(DISP_TARGET_DESC), dt));

		// _arrayDispOffset(arr): the displacement offset, or 0. Displacement is a
		// length-5+ header WITH a non-null target -- a packed array's length-6 header
		// has a null slot 3 and must answer 0 like any other non-displaced array.
		// Locals: 0 = arr, 1 = header.
		MethodCode dofs = new MethodCode();
		MethodCode.Label dofsNone = dofs.newLabel();
		dofs.aload(0);
		dofs.instanceOf(strClass);
		dofs.ifne(dofsNone);
		emitLoadHeader(dofs, arrayListClass, objectArrayClass, alGet, 0);
		dofs.astore(1);
		dofs.aload(1);
		dofs.arraylength();
		dofs.loadConstant(4);
		dofs.if_icmple(dofsNone);
		dofs.aload(1);
		dofs.loadConstant(3);
		dofs.aaload();
		dofs.ifnull(dofsNone);
		dofs.aload(1);
		dofs.loadConstant(4);
		dofs.aaload();
		dofs.areturn();
		dofs.labelBinding(dofsNone);
		dofs.lconst_0();
		dofs.invokestatic(longValueOf);
		dofs.areturn();
		methods.add(new ArrayMethod(cp.utf8Entry(DISP_OFFSET), cp.utf8Entry(DISP_OFFSET_DESC), dofs));

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
		MethodRefEntry selfArrayMake = cp.methodRef(selfClass, MAKE, MAKE_DESC);
		MethodRefEntry selfMakeTyped = cp.methodRef(selfClass, MAKE_TYPED, MAKE_TYPED_DESC);
		MethodCode cv = new MethodCode();
		MethodCode.Label cvRank1 = cv.newLabel();
		MethodCode.Label cvTryList = cv.newLabel();
		MethodCode.Label cvGeneral = cv.newLabel();
		cv.aload(0);
		cv.instanceOf(longClass);
		cv.ifeq(cvTryList);
		cv.goto_(cvRank1);
		cv.labelBinding(cvTryList);
		cv.aload(0);
		cv.instanceOf(objectArrayClass);
		cv.ifeq(cvGeneral);
		cv.aload(0);
		cv.checkcast(objectArrayClass);
		cv.loadConstant(1);
		cv.aaload();
		cv.ifnonnull(cvGeneral);
		cv.labelBinding(cvRank1);
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
		cv.loadConstant(4);
		cv.anewarray(objectClass);
		cv.astore(6);
		for (int slot = 0; slot < 3; slot++) {
			cv.aload(6);
			cv.loadConstant(slot);
			cv.aload(5);
			cv.loadConstant(slot);
			cv.aaload();
			cv.aastore();
		}
		cv.aload(4);
		cv.loadConstant(0);
		cv.aload(6);
		cv.invokevirtual(alSet);
		cv.pop();
		cv.aload(4);
		cv.areturn();
		// rank n: the general boxed representation, with no fill pointer (the defaulted
		// one is the rank-1 marker's, not the program's) -- but REMEMBERING that the
		// element type asked for was character, which is the only trace it leaves above
		// rank 1.
		cv.labelBinding(cvGeneral);
		cv.aload(0);
		cv.aload(1);
		cv.aconst_null();
		cv.aload(3);
		cv.loadConstant(am.ik.rontolisp.ArrayElementTypes.CHARACTER);
		cv.invokestatic(selfMakeTyped);
		cv.areturn();
		methods.add(new ArrayMethod(cp.utf8Entry(CHAR_VEC_MAKE), cp.utf8Entry(MAKE_DESC), cv));

		// _arrayMakeTyped(dims, init, fp, adj, code): _arrayMake plus the REMEMBERED
		// element type in header slot 4. That slot is free on every non-displaced array
		// -- slot 3 (the displacement target) is what says whether slot 4 holds an
		// offset instead -- so the packed length-6 header takes the type without giving
		// up its long[], and only the ordinary length-3 header has to grow to 5.
		// Locals: 0..3 = the _arrayMake arguments, 4 = code (int), 5 = list, 6 = header,
		// 7 = et.
		MethodCode mt = new MethodCode();
		MethodCode.Label mtHaveEt = mt.newLabel();
		MethodCode.Label mtGrow = mt.newLabel();
		mt.aload(0);
		mt.aload(1);
		mt.aload(2);
		mt.aload(3);
		mt.invokestatic(selfArrayMake);
		mt.astore(5);
		emitElementTypeForCode(mt, cp, objectClass, longValueOf, 4, 7, mtHaveEt);
		mt.labelBinding(mtHaveEt);
		emitLoadHeader(mt, arrayListClass, objectArrayClass, alGet, 5);
		mt.astore(6);
		mt.aload(6);
		mt.arraylength();
		mt.loadConstant(5);
		mt.if_icmplt(mtGrow);
		mt.aload(6);
		mt.loadConstant(4);
		mt.aload(7);
		mt.aastore();
		mt.aload(5);
		mt.areturn();
		// list.set(0, new Object[]{header[0], header[1], header[2], null, et})
		mt.labelBinding(mtGrow);
		mt.aload(5);
		mt.checkcast(arrayListClass);
		mt.loadConstant(0);
		mt.loadConstant(5);
		mt.anewarray(objectClass);
		for (int slot = 0; slot < 3; slot++) {
			mt.dup();
			mt.loadConstant(slot);
			mt.aload(6);
			mt.loadConstant(slot);
			mt.aaload();
			mt.aastore();
		}
		mt.dup();
		mt.loadConstant(4);
		mt.aload(7);
		mt.aastore();
		mt.invokevirtual(alSet);
		mt.pop();
		mt.aload(5);
		mt.areturn();
		methods.add(new ArrayMethod(cp.utf8Entry(MAKE_TYPED), cp.utf8Entry(MAKE_TYPED_DESC), mt));

		// _arrayElementType(o): the remembered element type, or the boolean t. A
		// DISPLACED array HOPS: slot 4 is its offset, not a type, and a view owns no
		// storage -- its elements are the target's, so its element type is the target's,
		// resolved through the whole chain. CL requires the two to be type-equivalent
		// anyway (make-array, :displaced-to), so the chain end's remembered type IS the
		// view's declared :element-type in every program a conforming implementation
		// accepts. A chain that ends on a String never reaches here: the caller's
		// stringp arm answers character for a string view first.
		// Locals: 0 = o (re-assigned by the hop), 1 = header, 2 = scratch.
		MethodCode aet = new MethodCode();
		MethodCode.Label aetT = aet.newLabel();
		MethodCode.Label aetTop = aet.newLabel();
		MethodCode.Label aetOwn = aet.newLabel();
		MethodCode.Label aetNotPacked = aet.newLabel();
		MethodCode.Label aetPackedDone = aet.newLabel();
		aet.labelBinding(aetTop);
		// The hop may land on a PACKED target, whose element type is its representation
		// rather than a remembered label -- the same answer read a different way.
		emitPackedElementTypeInto(aet, cp, 0, 2, byteArrayClass, longArrayClass, doubleArrayClass, floatArrayClass,
				shortArrayClass, longValueOf, objectClass, aetNotPacked, aetPackedDone);
		aet.labelBinding(aetPackedDone);
		aet.aload(2);
		aet.areturn();
		aet.labelBinding(aetNotPacked);
		aet.aload(0);
		aet.instanceOf(arrayListClass);
		aet.ifeq(aetT);
		emitLoadHeader(aet, arrayListClass, objectArrayClass, alGet, 0);
		aet.astore(1);
		aet.aload(1);
		aet.arraylength();
		aet.loadConstant(4);
		aet.if_icmple(aetT);
		aet.aload(1);
		aet.loadConstant(3);
		aet.aaload();
		aet.astore(2);
		aet.aload(2);
		aet.ifnull(aetOwn);
		aet.aload(2);
		aet.astore(0);
		aet.goto_(aetTop);
		aet.labelBinding(aetOwn);
		aet.aload(1);
		aet.loadConstant(4);
		aet.aaload();
		aet.astore(2);
		aet.aload(2);
		aet.ifnull(aetT);
		aet.aload(2);
		aet.areturn();
		aet.labelBinding(aetT);
		aet.ldc(cp.stringEntry("T"));
		aet.areturn();
		methods.add(new ArrayMethod(cp.utf8Entry(ELEMENT_TYPE), cp.utf8Entry(ELEMENT_TYPE_DESC), aet));

		// _arrayDefaultElement(o): the element an UNSUPPLIED slot of o takes -- the
		// remembered element type's own zero -- or null (nil) when nothing is remembered.
		// Keyed off the SAME header facts _arrayElementType reads, in the same order: a
		// length-4 header is the character vector marker (the one specialized type that
		// spends no slot 4), a displaced or string-view header (slot 3 non-null) and a
		// plain length-3 one remember nothing, and otherwise slot 4 holds the element
		// type VALUE -- an Object[] cons for (unsigned-byte n), the name string
		// otherwise. Mirrors am.ik.rontolisp.ArrayElementTypes.defaultElement.
		// Locals: 0 = o, 1 = header, 2 = et.
		ClassEntry stringClass = cp.classEntry("java/lang/String");
		MethodRefEntry stringEquals = cp.methodRef(stringClass, "equals", "(Ljava/lang/Object;)Z");
		ClassEntry doubleClass = cp.classEntry("java/lang/Double");
		MethodRefEntry doubleValueOf = cp.methodRef(doubleClass, "valueOf", "(D)Ljava/lang/Double;");
		MethodCode de = new MethodCode();
		MethodCode.Label deNil = de.newLabel();
		MethodCode.Label deChar = de.newLabel();
		MethodCode.Label deInt = de.newLabel();
		// A runtime string IS a rank-1 character array, so it answers the character zero
		// even though it carries no header at all.
		de.aload(0);
		de.instanceOf(stringClass);
		de.ifne(deChar);
		de.aload(0);
		de.instanceOf(arrayListClass);
		de.ifeq(deNil);
		emitLoadHeader(de, arrayListClass, objectArrayClass, alGet, 0);
		de.astore(1);
		de.aload(1);
		de.arraylength();
		de.loadConstant(4);
		de.if_icmpeq(deChar);
		de.aload(1);
		de.arraylength();
		de.loadConstant(5);
		de.if_icmplt(deNil);
		de.aload(1);
		de.loadConstant(3);
		de.aaload();
		de.ifnonnull(deNil);
		de.aload(1);
		de.loadConstant(4);
		de.aaload();
		de.astore(2);
		de.aload(2);
		de.ifnull(deNil);
		de.aload(2);
		de.instanceOf(objectArrayClass);
		de.ifne(deInt);
		de.ldc(cp.stringEntry(am.ik.rontolisp.LispNames.CHARACTER_TYPE));
		de.aload(2);
		de.invokevirtual(stringEquals);
		de.ifne(deChar);
		// A bit vector's zero is the integer 0, not the float 0.0 the two float
		// widths take below: the stamp is a name string like theirs, so it needs
		// its own arm before the float fallthrough.
		de.ldc(cp.stringEntry(am.ik.rontolisp.LispNames.BIT));
		de.aload(2);
		de.invokevirtual(stringEquals);
		de.ifne(deInt);
		// The only remaining remembered names are the two float widths.
		de.dconst_0();
		de.invokestatic(doubleValueOf);
		de.areturn();
		// A runtime character is a length-1 int[] holding the code point.
		de.labelBinding(deChar);
		de.loadConstant(1);
		de.newarray(TypeKind.INT);
		de.dup();
		de.loadConstant(0);
		de.loadConstant(am.ik.rontolisp.ArrayElementTypes.DEFAULT_CHARACTER);
		de.iastore();
		de.areturn();
		de.labelBinding(deInt);
		de.lconst_0();
		de.invokestatic(longValueOf);
		de.areturn();
		de.labelBinding(deNil);
		de.aconst_null();
		de.areturn();
		methods.add(new ArrayMethod(cp.utf8Entry(DEFAULT_ELEMENT), cp.utf8Entry(DEFAULT_ELEMENT_DESC), de));

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
		MethodRefEntry selfElementType = cp.methodRef(selfClass, ELEMENT_TYPE, ELEMENT_TYPE_DESC);
		StringEntry characterName = cp.stringEntry(am.ik.rontolisp.LispNames.CHARACTER_TYPE);
		MethodCode ad = new MethodCode();
		MethodCode.Label adDone = ad.newLabel();
		MethodCode.Label adChar = ad.newLabel();
		MethodCode.Label adStamp = ad.newLabel();
		MethodCode.Label adGrow = ad.newLabel();
		MethodCode.Label adNotDisplaced = ad.newLabel();
		MethodCode.Label adHaveEt = ad.newLabel();
		MethodCode.Label adSrcChar = ad.newLabel();
		MethodCode.Label adSrcGeneral = ad.newLabel();
		// et: the element type SRC remembers, read from the same header facts
		// _arrayDefaultElement reads and in the same order. A runtime string carries no
		// header but IS a rank-1 character array, and a length-4 header is the character
		// vector marker -- the one specialized type that spends no header slot 4, which
		// is why _arrayElementType alone (slot 4 or t) cannot answer for it.
		ad.aload(1);
		ad.instanceOf(strClass);
		ad.ifne(adSrcChar);
		ad.aload(1);
		ad.instanceOf(arrayListClass);
		ad.ifeq(adSrcGeneral);
		emitLoadHeader(ad, arrayListClass, objectArrayClass, alGet, 1);
		ad.astore(4);
		ad.aload(4);
		ad.arraylength();
		ad.loadConstant(4);
		ad.if_icmpne(adSrcGeneral);
		ad.labelBinding(adSrcChar);
		ad.ldc(characterName);
		ad.astore(2);
		ad.goto_(adHaveEt);
		ad.labelBinding(adSrcGeneral);
		ad.aload(1);
		ad.invokestatic(selfElementType);
		ad.astore(2);
		ad.labelBinding(adHaveEt);
		// t is remembered as nothing at all, so there is nothing to carry over.
		ad.ldc(cp.stringEntry("T"));
		ad.aload(2);
		ad.invokevirtual(stringEquals);
		ad.ifne(adDone);
		ad.aload(0);
		ad.instanceOf(arrayListClass);
		ad.ifeq(adDone);
		emitLoadHeader(ad, arrayListClass, objectArrayClass, alGet, 0);
		ad.astore(3);
		ad.aload(3);
		ad.arraylength();
		ad.loadConstant(4);
		ad.if_icmpeq(adDone);
		ad.aload(3);
		ad.arraylength();
		ad.loadConstant(5);
		ad.if_icmplt(adNotDisplaced);
		ad.aload(3);
		ad.loadConstant(3);
		ad.aaload();
		ad.ifnonnull(adDone);
		ad.labelBinding(adNotDisplaced);
		ad.ldc(characterName);
		ad.aload(2);
		ad.invokevirtual(stringEquals);
		ad.ifeq(adStamp);
		ad.aload(3);
		ad.loadConstant(0);
		ad.aaload();
		ad.checkcast(objectArrayClass);
		ad.arraylength();
		ad.loadConstant(1);
		ad.if_icmpeq(adChar);
		ad.labelBinding(adStamp);
		ad.aload(3);
		ad.arraylength();
		ad.loadConstant(5);
		ad.if_icmplt(adGrow);
		ad.aload(3);
		ad.loadConstant(4);
		ad.aload(2);
		ad.aastore();
		ad.goto_(adDone);
		// dst.set(0, new Object[]{dims, fp, adj, null, et})
		ad.labelBinding(adGrow);
		ad.aload(0);
		ad.checkcast(arrayListClass);
		ad.loadConstant(0);
		ad.loadConstant(5);
		ad.anewarray(objectClass);
		emitCopyHeaderSlots(ad, 3, 3);
		ad.dup();
		ad.loadConstant(4);
		ad.aload(2);
		ad.aastore();
		ad.invokevirtual(alSet);
		ad.pop();
		ad.goto_(adDone);
		// dst.set(0, new Object[]{dims, fp, adj, null}) -- the character vector marker,
		// over the BOXED data it implies.
		ad.labelBinding(adChar);
		ad.aload(0);
		ad.invokestatic(widen);
		emitLoadHeader(ad, arrayListClass, objectArrayClass, alGet, 0);
		ad.astore(3);
		ad.aload(0);
		ad.checkcast(arrayListClass);
		ad.loadConstant(0);
		ad.loadConstant(4);
		ad.anewarray(objectClass);
		emitCopyHeaderSlots(ad, 3, 3);
		ad.invokevirtual(alSet);
		ad.pop();
		ad.labelBinding(adDone);
		ad.aload(0);
		ad.areturn();
		methods.add(new ArrayMethod(cp.utf8Entry(ADOPT_ELEMENT_TYPE), cp.utf8Entry(ADOPT_ELEMENT_TYPE_DESC), ad));

		// _arrayAlike(seq, n): a fresh zero-filled rank-1 array of length n of the same
		// KIND as seq. The kind is _arrayElementType's answer, not seq's runtime class:
		// that one call already tells a packed long[] / double[] / float[] / short[], a
		// general array that only REMEMBERS a packed width (a fill-pointer / adjustable
		// packed vector, .kb/adjustable-arrays.md) and a displaced view over either of
		// them apart from a plain vector, in the value shape _arrayMakeTyped stores --
		// the cons {"UNSIGNED-BYTE", {Long width, null}} for an integer width, the name
		// string for a float width, "T" or a name that is not a packed width for the
		// general vector (a rank-1 character array never reaches here: subseq's stringp
		// arm answers for it first). The interpreter's %array-alike is keyed the same
		// way (Environment.packedCopyForElementType). Locals: 0 = seq, 1 = n, 2 = et,
		// 3 = ni, 4 = arr, 5 = k.
		MethodRefEntry selfElementTypeForAlike = cp.methodRef(selfClass, ELEMENT_TYPE, ELEMENT_TYPE_DESC);
		MethodCode al = new MethodCode();
		MethodCode.Label alNotInt = al.newLabel();
		al.aload(0);
		al.invokestatic(selfElementTypeForAlike);
		al.astore(2);
		al.aload(1);
		al.checkcast(longClass);
		al.invokevirtual(longIntValue);
		al.istore(3);
		// (unsigned-byte w): a byte[]{8, 0...} at width 8, else a long[]{w, 0...} -- the
		// width is the cons's cadr (in k, as an int).
		MethodCode.Label alWide = al.newLabel();
		al.aload(2);
		al.instanceOf(objectArrayClass);
		al.ifeq(alNotInt);
		al.aload(2);
		al.checkcast(objectArrayClass);
		al.loadConstant(1);
		al.aaload();
		al.checkcast(objectArrayClass);
		al.loadConstant(0);
		al.aaload();
		al.checkcast(longClass);
		al.invokevirtual(longIntValue);
		al.istore(5);
		al.iload(5);
		al.loadConstant(JvmIntArrayRuntimeBuilder.OCTET_TAG);
		al.if_icmpne(alWide);
		al.iload(3);
		al.loadConstant(1);
		al.iadd();
		al.newarray(TypeKind.BYTE);
		al.dup();
		al.loadConstant(0);
		al.loadConstant(JvmIntArrayRuntimeBuilder.OCTET_TAG);
		al.bastore();
		al.areturn();
		al.labelBinding(alWide);
		al.iload(3);
		al.loadConstant(1);
		al.iadd();
		al.newarray(TypeKind.LONG);
		al.astore(4);
		al.aload(4);
		al.loadConstant(0);
		al.iload(5);
		al.i2l();
		al.lastore();
		al.aload(4);
		al.areturn();
		al.labelBinding(alNotInt);
		// A float width: the backing at that width with a fresh rank-1 header, laid out
		// by JvmPackedFloatWidth -- the one place that knows each width's header.
		for (JvmPackedFloatWidth w : JvmPackedFloatWidth.values()) {
			MethodCode.Label next = al.newLabel();
			al.ldc(cp.stringEntry(switch (w) {
				case DOUBLE -> am.ik.rontolisp.LispNames.DOUBLE_FLOAT;
				case SINGLE -> am.ik.rontolisp.LispNames.SINGLE_FLOAT;
				case BFLOAT16 -> am.ik.rontolisp.LispNames.BFLOAT16;
			}));
			al.aload(2);
			al.invokevirtual(stringEquals);
			al.ifeq(next);
			al.iload(3);
			al.loadConstant(w.dataOffset(1));
			al.iadd();
			w.newBacking(al);
			al.astore(4);
			al.aload(4);
			al.loadConstant(1);
			w.storeRank(al);
			al.loadConstant(0);
			al.istore(5);
			w.storeDim(al, 4, 5, 3);
			al.aload(4);
			al.areturn();
			al.labelBinding(next);
		}
		// Anything else: the general nil-filled vector, stamped with what seq
		// remembers -- a bit vector IS the general boxed array stamped bit, so the
		// copy keeps the stamp the way adjust-array carries it. T is
		// remembered as nothing, so the adopt is a no-op for a plain vector.
		al.aload(1);
		al.aconst_null();
		al.aconst_null();
		al.aconst_null();
		al.invokestatic(selfArrayMake);
		al.astore(4);
		al.aload(4);
		al.aload(0);
		al.invokestatic(cp.methodRef(selfClass, ADOPT_ELEMENT_TYPE, ADOPT_ELEMENT_TYPE_DESC));
		al.areturn();
		methods.add(new ArrayMethod(cp.utf8Entry(ALIKE), cp.utf8Entry(ALIKE_DESC), al));

		// _strv(o): normalizes a mutable character vector (a length-4-header array whose
		// elements are runtime CHARACTERs -- length-1 int[]{codePoint}) into the
		// quote-framed runtime string, reading up to the fill pointer (or dims[0] when
		// the fill pointer is nil); any other value is returned unchanged. Each element
		// appends via StringBuilder.appendCodePoint(int) so a supplementary code point
		// expands to its two-unit UTF-16 pair rather than being narrowed to 16 bits.
		// The class is exact (a Lisp array is never a subclass of ArrayList): a host
		// subclass a java: call answered is passed through, its own size / get unasked.
		// Locals: 0 = o, 1 = list, 2 = header, 3 = n (int), 4 = sb, 5 = i (int).
		ClassEntry sbClass = cp.classEntry("java/lang/StringBuilder");
		ClassEntry intArrayClass = cp.classEntry("[I");
		MethodRefEntry sbInit = cp.methodRef(sbClass, "<init>", "(Ljava/lang/String;)V");
		MethodRefEntry sbAppendCodePoint = cp.methodRef(sbClass, "appendCodePoint", "(I)Ljava/lang/StringBuilder;");
		MethodRefEntry sbAppendStr = cp.methodRef(sbClass, "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;");
		MethodRefEntry sbToString = cp.methodRef(sbClass, "toString", "()Ljava/lang/String;");
		StringEntry quoteStr = cp.stringEntry("\"");
		MethodRefEntry strSubstring = cp.methodRef(strClass, "substring", "(II)Ljava/lang/String;");
		MethodCode sv = new MethodCode();
		MethodCode.Label svNotCv = sv.newLabel();
		MethodCode.Label svView = sv.newLabel();
		MethodCode.Label svRender = sv.newLabel();
		MethodCode.Label svStr = sv.newLabel();
		sv.aload(0);
		sv.ifnull(svNotCv);
		sv.aload(0);
		sv.invokevirtual(cp.methodRef(objectClass, "getClass", "()Ljava/lang/Class;"));
		sv.ldc(arrayListClass);
		sv.if_acmpne(svNotCv);
		sv.aload(0);
		sv.checkcast(arrayListClass);
		sv.astore(1);
		sv.aload(1);
		sv.invokevirtual(alSize);
		sv.ifeq(svNotCv);
		sv.aload(1);
		sv.loadConstant(0);
		sv.invokevirtual(alGet);
		sv.instanceOf(objectArrayClass);
		sv.ifeq(svNotCv);
		sv.aload(1);
		sv.loadConstant(0);
		sv.invokevirtual(alGet);
		sv.checkcast(objectArrayClass);
		sv.astore(2);
		sv.aload(2);
		sv.arraylength();
		sv.loadConstant(7);
		sv.if_icmpeq(svView);
		sv.aload(2);
		sv.arraylength();
		sv.loadConstant(4);
		sv.if_icmpne(svNotCv);
		// A character vector reads its own slots: base = 1, and
		// n = header[1] != null ? fill pointer : dims[0]
		sv.loadConstant(1);
		sv.istore(6);
		emitActiveLength(sv, longClass, objectArrayClass, longIntValue, 2, 3);
		sv.goto_(svRender);
		// A STRING VIEW (length-7 header) has no storage of its own: n is its
		// dimension, and the walk hands back either the character vector it aliases
		// (rendered element by element from the resolved base) or the immutable string
		// it aliases (sliced by code point in one substring).
		sv.labelBinding(svView);
		// The view's OWN fill pointer bounds the rendering when it has one -- a
		// :displaced-to view may carry one, and then it is the string's length. Read it
		// before the walk below overwrites the header local.
		emitActiveLength(sv, longClass, objectArrayClass, longIntValue, 2, 3);
		sv.loadConstant(1);
		sv.istore(6);
		emitResolveDisplacement(sv, arrayListClass, longClass, objectArrayClass, alGet, longIntValue, 1, 6, 2);
		emitLandedOnString(sv, 2, svStr);
		sv.aload(2);
		sv.arraylength();
		sv.loadConstant(4);
		sv.if_icmpne(svNotCv);
		// sb = new StringBuilder("\""); for i in 0..n-1: sb.append(char at base + i)
		sv.labelBinding(svRender);
		sv.new_(sbClass);
		sv.dup();
		sv.ldc(quoteStr);
		sv.invokespecial(sbInit);
		sv.astore(4);
		sv.loadConstant(0);
		sv.istore(5);
		MethodCode.Label svLoop = sv.newLabel();
		MethodCode.Label svDone = sv.newLabel();
		sv.labelBinding(svLoop);
		sv.iload(5);
		sv.iload(3);
		sv.if_icmpge(svDone);
		sv.aload(4);
		sv.aload(1);
		sv.checkcast(arrayListClass);
		sv.iload(6);
		sv.iload(5);
		sv.iadd();
		sv.invokevirtual(alGet);
		sv.checkcast(intArrayClass);
		sv.loadConstant(0);
		sv.iaload();
		sv.invokevirtual(sbAppendCodePoint);
		sv.pop();
		sv.iinc(5, 1);
		sv.goto_(svLoop);
		sv.labelBinding(svDone);
		sv.aload(4);
		sv.ldc(quoteStr);
		sv.invokevirtual(sbAppendStr);
		sv.pop();
		sv.aload(4);
		sv.invokevirtual(sbToString);
		sv.areturn();
		// s = (String) header[3]; the view's characters are s[base - 1 .. base - 1 + n)
		// by CODE POINT, so both ends translate through _cpoff.
		sv.labelBinding(svStr);
		sv.aload(2);
		sv.loadConstant(3);
		sv.aaload();
		sv.checkcast(strClass);
		sv.astore(7);
		sv.new_(sbClass);
		sv.dup();
		sv.ldc(quoteStr);
		sv.invokespecial(sbInit);
		sv.astore(4);
		sv.aload(4);
		sv.aload(7);
		sv.aload(7);
		sv.iload(6);
		sv.loadConstant(1);
		sv.isub();
		sv.invokestatic(strCpOffset);
		sv.aload(7);
		sv.iload(6);
		sv.loadConstant(1);
		sv.isub();
		sv.iload(3);
		sv.iadd();
		sv.invokestatic(strCpOffset);
		sv.invokevirtual(strSubstring);
		sv.invokevirtual(sbAppendStr);
		sv.pop();
		sv.aload(4);
		sv.ldc(quoteStr);
		sv.invokevirtual(sbAppendStr);
		sv.pop();
		sv.aload(4);
		sv.invokevirtual(sbToString);
		sv.areturn();
		sv.labelBinding(svNotCv);
		sv.aload(0);
		sv.areturn();
		methods.add(new ArrayMethod(cp.utf8Entry(STRV), cp.utf8Entry(STRV_DESC), sv));

		// _subseqCv(o, start, end): the string subseq lane answering a MUTABLE character
		// vector (a copy-seq/subseq result has a writable identity, like the
		// interpreter's and SBCL's). A nil end means "to the length". A
		// character vector or string view copies its elements [start, end) directly
		// through _rmGet (never rendering the source, so chained slicing stays linear);
		// an immutable String slices by code point and converts once through
		// _strToCharVec, then clears the fill-pointer slot that promotion path sets so
		// the result is a SIMPLE string like the other backends'. Both arms refuse a
		// range outside the string with the interpreter's report, from one shared
		// block. Locals: 0 = o, 1 = start and 2 = end (each as given, the end nil when
		// omitted), 3 = header, 4 = the start as an index, 5 = n, 6 = out, 7 = i, 8 = s,
		// 9 = a, 10 = b, 11 = the character count, 12 = the resolved end.
		MethodRefEntry scStrToCharVec = cp.methodRef(selfClass, STR_TO_CHAR_VEC, STR_TO_CHAR_VEC_DESC);
		MethodRefEntry strLength = cp.methodRef(strClass, "length", "()I");
		MethodRefEntry strConcat = cp.methodRef(strClass, "concat", "(Ljava/lang/String;)Ljava/lang/String;");
		MethodCode sc = new MethodCode();
		MethodCode.Label scStr = sc.newLabel();
		MethodCode.Label scCv = sc.newLabel();
		MethodCode.Label scBad = sc.newLabel();
		subseqRuntime.emitIndex(sc, 1);
		sc.istore(4);
		sc.aload(0);
		sc.instanceOf(arrayListClass);
		sc.ifeq(scStr);
		sc.aload(0);
		sc.checkcast(arrayListClass);
		sc.invokevirtual(alSize);
		sc.ifle(scStr);
		sc.aload(0);
		sc.checkcast(arrayListClass);
		sc.loadConstant(0);
		sc.invokevirtual(alGet);
		sc.instanceOf(objectArrayClass);
		sc.ifeq(scStr);
		sc.aload(0);
		sc.checkcast(arrayListClass);
		sc.loadConstant(0);
		sc.invokevirtual(alGet);
		sc.checkcast(objectArrayClass);
		sc.astore(3);
		sc.aload(3);
		sc.arraylength();
		sc.loadConstant(4);
		sc.if_icmpeq(scCv);
		sc.aload(3);
		sc.arraylength();
		sc.loadConstant(7);
		sc.if_icmpne(scStr);
		sc.labelBinding(scCv);
		// len = header[1] != null (the fill pointer) ? its int : dims[0]
		MethodCode.Label scUseDim = sc.newLabel();
		MethodCode.Label scHaveLen = sc.newLabel();
		sc.aload(3);
		sc.loadConstant(1);
		sc.aaload();
		sc.ifnull(scUseDim);
		sc.aload(3);
		sc.loadConstant(1);
		sc.aaload();
		sc.checkcast(longClass);
		sc.invokevirtual(longIntValue);
		sc.istore(11);
		sc.goto_(scHaveLen);
		sc.labelBinding(scUseDim);
		emitLoadDim0(sc, longClass, objectArrayClass, longIntValue, 3);
		sc.istore(11);
		sc.labelBinding(scHaveLen);
		// realEnd = (end nil ? len : end), checked against len; n = realEnd - start
		JvmSubseqCompiler.emitResolveEnd(sc, subseqRuntime, 2, 11, 12);
		JvmSubseqCompiler.emitBoundsTest(sc, 4, 12, 11, scBad);
		sc.iload(12);
		sc.iload(4);
		sc.isub();
		sc.istore(5);
		// out = new ArrayList holding the length-4 header {dims{n}, null, null, null}
		sc.new_(arrayListClass);
		sc.dup();
		sc.invokespecial(alInit);
		sc.astore(6);
		sc.aload(6);
		sc.loadConstant(4);
		sc.anewarray(objectClass);
		sc.dup();
		sc.loadConstant(0);
		sc.loadConstant(1);
		sc.anewarray(objectClass);
		sc.dup();
		sc.loadConstant(0);
		sc.iload(5);
		sc.i2l();
		sc.invokestatic(longValueOf);
		sc.aastore();
		sc.aastore();
		sc.invokevirtual(alAdd);
		sc.pop();
		// for i in 0..n-1: out.add(_rmGet(o, 1 + start + i))
		sc.loadConstant(0);
		sc.istore(7);
		MethodCode.Label scLoop = sc.newLabel();
		MethodCode.Label scDone = sc.newLabel();
		sc.labelBinding(scLoop);
		sc.iload(7);
		sc.iload(5);
		sc.if_icmpge(scDone);
		sc.aload(6);
		sc.aload(0);
		sc.loadConstant(1);
		sc.iload(4);
		sc.iadd();
		sc.iload(7);
		sc.iadd();
		sc.invokestatic(rmGet);
		sc.invokevirtual(alAdd);
		sc.pop();
		sc.iinc(7, 1);
		sc.goto_(scLoop);
		sc.labelBinding(scDone);
		sc.aload(6);
		sc.areturn();
		// The immutable arm: slice by CODE POINT and convert once.
		sc.labelBinding(scStr);
		sc.aload(0);
		sc.checkcast(strClass);
		sc.astore(8);
		// Bounds check BEFORE any code-unit offset math, in characters, so a bad range
		// is the interpreter's report rather than String#substring's
		// StringIndexOutOfBoundsException.
		sc.aload(8);
		sc.invokestatic(strCount);
		sc.istore(11);
		JvmSubseqCompiler.emitResolveEnd(sc, subseqRuntime, 2, 11, 12);
		JvmSubseqCompiler.emitBoundsTest(sc, 4, 12, 11, scBad);
		sc.aload(8);
		sc.iload(4);
		sc.invokestatic(strCpOffset);
		sc.istore(9);
		MethodCode.Label scHaveEnd = sc.newLabel();
		MethodCode.Label scGotB = sc.newLabel();
		sc.aload(2);
		sc.ifnonnull(scHaveEnd);
		sc.aload(8);
		sc.invokevirtual(strLength);
		sc.loadConstant(1);
		sc.isub();
		sc.istore(10);
		sc.goto_(scGotB);
		sc.labelBinding(scHaveEnd);
		sc.aload(8);
		sc.iload(12);
		sc.invokestatic(strCpOffset);
		sc.istore(10);
		sc.labelBinding(scGotB);
		sc.ldc(quoteStr);
		sc.aload(8);
		sc.iload(9);
		sc.iload(10);
		sc.invokevirtual(strSubstring);
		sc.invokevirtual(strConcat);
		sc.ldc(quoteStr);
		sc.invokevirtual(strConcat);
		sc.invokestatic(scStrToCharVec);
		sc.astore(6);
		// A subseq result is a SIMPLE string: clear the fill-pointer slot the
		// promotion-path _strToCharVec stamps.
		sc.aload(6);
		sc.checkcast(arrayListClass);
		sc.loadConstant(0);
		sc.invokevirtual(alGet);
		sc.checkcast(objectArrayClass);
		sc.loadConstant(1);
		sc.aconst_null();
		sc.aastore();
		sc.aload(6);
		sc.areturn();
		// Both arms' refusal.
		sc.labelBinding(scBad);
		JvmSubseqCompiler.emitBoundsError(sc, subseqRuntime, new JvmSubseqCompiler.Bounds(1, 2, 11, "string"));
		methods.add(new ArrayMethod(cp.utf8Entry(SUBSEQ_CV), cp.utf8Entry(SUBSEQ_CV_DESC), sc));

		// _toMutStr(o): the flipped producers' mutable-result wrap. A QUOTE-FRAMED
		// String -- an actual runtime string -- converts once through _strToCharVec
		// with the fill-pointer slot cleared (the result is a SIMPLE string, like
		// _subseqCv's); anything else passes through. The frame test matters: a SYMBOL
		// shares the java.lang.String representation bare (no quotes), and read-line's
		// eof-value or a symbol flowing out of a producer expression must not be
		// laundered through the string conversion. Locals: 0 = o, 1 = cv.
		MethodCode tm = new MethodCode();
		MethodCode.Label tmPass = tm.newLabel();
		tm.aload(0);
		tm.instanceOf(strClass);
		tm.ifeq(tmPass);
		tm.aload(0);
		tm.checkcast(strClass);
		tm.invokevirtual(strLength);
		tm.ifle(tmPass);
		tm.aload(0);
		tm.checkcast(strClass);
		tm.loadConstant(0);
		tm.invokevirtual(strCharAt);
		tm.loadConstant('"');
		tm.if_icmpne(tmPass);
		tm.aload(0);
		tm.checkcast(strClass);
		tm.invokestatic(scStrToCharVec);
		tm.astore(1);
		tm.aload(1);
		tm.checkcast(arrayListClass);
		tm.loadConstant(0);
		tm.invokevirtual(alGet);
		tm.checkcast(objectArrayClass);
		tm.loadConstant(1);
		tm.aconst_null();
		tm.aastore();
		tm.aload(1);
		tm.areturn();
		tm.labelBinding(tmPass);
		tm.aload(0);
		tm.areturn();
		methods.add(new ArrayMethod(cp.utf8Entry(TO_MUT_STR), cp.utf8Entry(TO_MUT_STR_DESC), tm));

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
	private static void emitResolveDisplacement(MethodCode a, ClassEntry arrayListClass, ClassEntry longClass,
			ClassEntry objectArrayClass, MethodRefEntry alGet, MethodRefEntry longIntValue, int listSlot, int idxSlot,
			int headerSlot) {
		MethodCode.Label loop = a.newLabel();
		MethodCode.Label done = a.newLabel();
		a.labelBinding(loop);
		emitLoadHeader(a, arrayListClass, objectArrayClass, alGet, listSlot);
		a.astore(headerSlot);
		a.aload(headerSlot);
		a.arraylength();
		a.loadConstant(4);
		a.if_icmple(done);
		a.aload(headerSlot);
		a.loadConstant(3);
		a.aaload();
		a.ifnull(done);
		// idx += ((Long) header[4]).intValue()
		a.iload(idxSlot);
		a.aload(headerSlot);
		a.loadConstant(4);
		a.aaload();
		a.checkcast(longClass);
		a.invokevirtual(longIntValue);
		a.iadd();
		a.istore(idxSlot);
		// A non-array target is the immutable string a string view aliases: stop here.
		a.aload(headerSlot);
		a.loadConstant(3);
		a.aaload();
		a.instanceOf(arrayListClass);
		a.ifeq(done);
		// list = header[3]
		a.aload(headerSlot);
		a.loadConstant(3);
		a.aaload();
		a.astore(listSlot);
		a.goto_(loop);
		a.labelBinding(done);
	}

	// Stores into etSlot the element type of the PACKED value in targetSlot -- the cons
	// list {@code (unsigned-byte width)} for an integer vector, the name
	// {@code double-float} / {@code single-float} for a float array -- and branches to
	// done; branches to notPacked when the value is not packed. The shapes are exactly
	// what {@code _ivElementType} / {@code _fvElementType} answer and what
	// {@code _arrayDefaultElement} reads back out of header slot 4, so a view over a
	// packed target and the un-displaced array it becomes give the same answer.
	private static void emitPackedElementTypeInto(MethodCode a, ConstantPool cp, int targetSlot, int etSlot,
			ClassEntry byteArrayClass, ClassEntry longArrayClass, ClassEntry doubleArrayClass,
			ClassEntry floatArrayClass, ClassEntry shortArrayClass, MethodRefEntry longValueOf, ClassEntry objectClass,
			MethodCode.Label notPacked, MethodCode.Label done) {
		MethodCode.Label tryLong = a.newLabel();
		MethodCode.Label tryDouble = a.newLabel();
		MethodCode.Label tryFloat = a.newLabel();
		MethodCode.Label tryShort = a.newLabel();
		a.aload(targetSlot);
		a.instanceOf(byteArrayClass);
		a.ifeq(tryLong);
		emitUnsignedByteSpec(a, cp, objectClass, longValueOf, () -> {
			a.loadConstant(JvmIntArrayRuntimeBuilder.OCTET_TAG);
			a.i2l();
		});
		a.astore(etSlot);
		a.goto_(done);
		a.labelBinding(tryLong);
		a.aload(targetSlot);
		a.instanceOf(longArrayClass);
		a.ifeq(tryDouble);
		emitUnsignedByteSpec(a, cp, objectClass, longValueOf, () -> {
			a.aload(targetSlot);
			a.checkcast(longArrayClass);
			a.loadConstant(0);
			a.laload();
		});
		a.astore(etSlot);
		a.goto_(done);
		a.labelBinding(tryDouble);
		a.aload(targetSlot);
		a.instanceOf(doubleArrayClass);
		a.ifeq(tryFloat);
		a.ldc(cp.stringEntry(am.ik.rontolisp.LispNames.DOUBLE_FLOAT));
		a.astore(etSlot);
		a.goto_(done);
		a.labelBinding(tryFloat);
		a.aload(targetSlot);
		a.instanceOf(floatArrayClass);
		a.ifeq(tryShort);
		a.ldc(cp.stringEntry(am.ik.rontolisp.LispNames.SINGLE_FLOAT));
		a.astore(etSlot);
		a.goto_(done);
		a.labelBinding(tryShort);
		a.aload(targetSlot);
		a.instanceOf(shortArrayClass);
		a.ifeq(notPacked);
		a.ldc(cp.stringEntry(am.ik.rontolisp.LispNames.BFLOAT16));
		a.astore(etSlot);
		a.goto_(done);
	}

	// Pushes new Object[]{"UNSIGNED-BYTE", new Object[]{Long.valueOf(width), null}}, the
	// width pushed as a long by pushWidth.
	private static void emitUnsignedByteSpec(MethodCode a, ConstantPool cp, ClassEntry objectClass,
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

	// Reads and RETURNS the element the displaced view sees at the 1-based data index
	// idxSlot when the target in targetSlot is a PACKED vector; branches to notPacked
	// when it is not (a string view's target). The index arithmetic is the packed
	// representation's own: an integer vector's element `flat` lives at
	// {@code l[1 + flat]}, which the 1-based index already IS, and a float array's at
	// {@code d[1 + rank + flat]} == {@code d[rank + idx]} (and, at bfloat16, at
	// {@code s[1 + 2 * rank + flat]} == {@code s[2 * rank + idx]}).
	private static void emitPackedTargetGet(MethodCode a, int targetSlot, int idxSlot, ClassEntry byteArrayClass,
			ClassEntry longArrayClass, ClassEntry doubleArrayClass, ClassEntry floatArrayClass,
			ClassEntry shortArrayClass, @Nullable MethodRefEntry bf16Value, MethodRefEntry longValueOf,
			MethodRefEntry doubleBoxValueOf, MethodCode.Label notPacked) {
		MethodCode.Label tryLong = a.newLabel();
		MethodCode.Label tryDouble = a.newLabel();
		MethodCode.Label tryFloat = a.newLabel();
		MethodCode.Label tryShort = a.newLabel();
		a.aload(targetSlot);
		a.instanceOf(byteArrayClass);
		a.ifeq(tryLong);
		a.aload(targetSlot);
		a.checkcast(byteArrayClass);
		a.iload(idxSlot);
		a.baload();
		a.loadConstant(0xFF);
		a.iand();
		a.i2l();
		a.invokestatic(longValueOf);
		a.areturn();
		a.labelBinding(tryLong);
		a.aload(targetSlot);
		a.instanceOf(longArrayClass);
		a.ifeq(tryDouble);
		a.aload(targetSlot);
		a.checkcast(longArrayClass);
		a.iload(idxSlot);
		a.laload();
		a.invokestatic(longValueOf);
		a.areturn();
		a.labelBinding(tryDouble);
		a.aload(targetSlot);
		a.instanceOf(doubleArrayClass);
		a.ifeq(tryFloat);
		a.aload(targetSlot);
		a.checkcast(doubleArrayClass);
		a.dup();
		a.loadConstant(0);
		a.daload();
		a.d2i();
		a.iload(idxSlot);
		a.iadd();
		a.daload();
		a.invokestatic(doubleBoxValueOf);
		a.areturn();
		a.labelBinding(tryFloat);
		a.aload(targetSlot);
		a.instanceOf(floatArrayClass);
		a.ifeq(tryShort);
		a.aload(targetSlot);
		a.checkcast(floatArrayClass);
		a.dup();
		a.loadConstant(0);
		a.faload();
		a.f2i();
		a.iload(idxSlot);
		a.iadd();
		a.faload();
		a.f2d();
		a.invokestatic(doubleBoxValueOf);
		a.areturn();
		a.labelBinding(tryShort);
		if (bf16Value == null) {
			a.goto_(notPacked);
			return;
		}
		a.aload(targetSlot);
		a.instanceOf(shortArrayClass);
		a.ifeq(notPacked);
		a.aload(targetSlot);
		a.checkcast(shortArrayClass);
		a.dup();
		JvmPackedFloatWidth.BFLOAT16.loadRank(a);
		JvmPackedFloatWidth.BFLOAT16.emitDataOffset(a);
		a.loadConstant(1);
		a.isub();
		a.iload(idxSlot);
		a.iadd();
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
	private static void emitPackedTargetSet(MethodCode a, ConstantPool cp, int targetSlot, int idxSlot, int valSlot,
			int ivArrSlot, int dvArrSlot, int fvArrSlot, int ixSlot, int vSlot, int dvalSlot, ClassEntry byteArrayClass,
			ClassEntry longArrayClass, ClassEntry doubleArrayClass, ClassEntry floatArrayClass,
			ClassEntry shortArrayClass, @Nullable MethodRefEntry bf16Value, @Nullable MethodRefEntry bf16Bits,
			ClassEntry numberClass, MethodRefEntry numberLongValue, MethodRefEntry numberDoubleValue,
			MethodRefEntry longValueOf, MethodRefEntry doubleBoxValueOf, MethodRefEntry dblCoerce, ClassEntry rtExClass,
			MethodRefEntry rtExInit, MethodCode.Label notPacked) {
		MethodCode.Label tryLong = a.newLabel();
		MethodCode.Label tryDouble = a.newLabel();
		MethodCode.Label tryFloat = a.newLabel();
		MethodCode.Label tryShort = a.newLabel();
		MethodCode.Label intOk = a.newLabel();
		MethodCode.Label octetOk = a.newLabel();
		StringEntry storesIntegers = cp.stringEntry("%aset: a packed integer vector stores integers");
		// An octet vector: b[idx] = (byte) v, answering v & 0xFF -- the value as stored.
		a.aload(targetSlot);
		a.instanceOf(byteArrayClass);
		a.ifeq(tryLong);
		a.aload(valSlot);
		a.instanceOf(numberClass);
		a.ifne(octetOk);
		emitThrow(a, rtExClass, rtExInit, storesIntegers);
		a.labelBinding(octetOk);
		a.aload(valSlot);
		a.checkcast(numberClass);
		a.invokevirtual(numberLongValue);
		a.l2i();
		a.loadConstant(0xFF);
		a.iand();
		a.istore(ixSlot);
		a.aload(targetSlot);
		a.checkcast(byteArrayClass);
		a.iload(idxSlot);
		a.iload(ixSlot);
		a.bastore();
		a.iload(ixSlot);
		a.i2l();
		a.invokestatic(longValueOf);
		a.areturn();
		a.labelBinding(tryLong);
		a.aload(targetSlot);
		a.instanceOf(longArrayClass);
		a.ifeq(tryDouble);
		a.aload(targetSlot);
		a.checkcast(longArrayClass);
		a.astore(ivArrSlot);
		a.aload(valSlot);
		a.instanceOf(numberClass);
		a.ifne(intOk);
		emitThrow(a, rtExClass, rtExInit, storesIntegers);
		a.labelBinding(intOk);
		a.aload(valSlot);
		a.checkcast(numberClass);
		a.invokevirtual(numberLongValue);
		a.lstore(vSlot);
		// width = (int) l[0]; v &= (1L << width) - 1
		a.aload(ivArrSlot);
		a.loadConstant(0);
		a.laload();
		a.l2i();
		a.istore(ixSlot);
		a.lload(vSlot);
		a.lconst_1();
		a.iload(ixSlot);
		a.lshl();
		a.lconst_1();
		a.lsub();
		a.land();
		a.lstore(vSlot);
		a.aload(ivArrSlot);
		a.iload(idxSlot);
		a.lload(vSlot);
		a.lastore();
		a.lload(vSlot);
		a.invokestatic(longValueOf);
		a.areturn();
		a.labelBinding(tryDouble);
		a.aload(targetSlot);
		a.instanceOf(doubleArrayClass);
		a.ifeq(tryFloat);
		a.aload(targetSlot);
		a.checkcast(doubleArrayClass);
		a.astore(dvArrSlot);
		a.aload(dvArrSlot);
		a.loadConstant(0);
		a.daload();
		a.d2i();
		a.iload(idxSlot);
		a.iadd();
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
		a.labelBinding(tryFloat);
		a.aload(targetSlot);
		a.instanceOf(floatArrayClass);
		a.ifeq(tryShort);
		a.aload(targetSlot);
		a.checkcast(floatArrayClass);
		a.astore(fvArrSlot);
		a.aload(fvArrSlot);
		a.loadConstant(0);
		a.faload();
		a.f2i();
		a.iload(idxSlot);
		a.iadd();
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
		a.labelBinding(tryShort);
		if (bf16Value == null || bf16Bits == null) {
			a.goto_(notPacked);
			return;
		}
		a.aload(targetSlot);
		a.instanceOf(shortArrayClass);
		a.ifeq(notPacked);
		a.aload(targetSlot);
		a.checkcast(shortArrayClass);
		a.astore(fvArrSlot);
		a.aload(fvArrSlot);
		JvmPackedFloatWidth.BFLOAT16.loadRank(a);
		JvmPackedFloatWidth.BFLOAT16.emitDataOffset(a);
		a.loadConstant(1);
		a.isub();
		a.iload(idxSlot);
		a.iadd();
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
	private static void emitLandedOnString(MethodCode a, int headerSlot, MethodCode.Label yes) {
		MethodCode.Label no = a.newLabel();
		a.aload(headerSlot);
		a.arraylength();
		a.loadConstant(4);
		a.if_icmple(no);
		a.aload(headerSlot);
		a.loadConstant(3);
		a.aaload();
		a.ifnonnull(yes);
		a.labelBinding(no);
	}

	// Parses a make-array dimensions argument in the local dims (a Long for the rank-1
	// shorthand, otherwise a cons list of Longs) into an Object[] of boxed Long sizes
	// (dimsArr) and the int total element count (total), which _arrayDimsTotal answers
	// after checking every dimension -- so the parse below meets only valid ones.
	// cur/n/idx are scratch slots.
	private static void emitParseDims(MethodCode m, ClassEntry objectClass, ClassEntry objectArrayClass,
			MethodRefEntry dimsTotal, int dims, int dimsArr, int total, int cur, int n, int idx) {
		m.aload(dims);
		m.invokestatic(dimsTotal);
		m.istore(total);
		m.aload(dims);
		m.instanceOf(objectArrayClass);
		MethodCode.Label list = m.newLabel();
		m.ifne(list);
		MethodCode.Label afterDims = m.newLabel();
		// nil is the rank-0 shape: an empty dimsArr.
		m.aload(dims);
		m.ifnull(list);
		// 1-D integer shorthand: dimsArr = {dims}
		m.loadConstant(1);
		m.anewarray(objectClass);
		m.dup();
		m.loadConstant(0);
		m.aload(dims);
		m.aastore();
		m.astore(dimsArr);
		m.goto_(afterDims);
		// cons list of dimensions: first count the length (n), then copy the sizes
		// into dimsArr.
		m.labelBinding(list);
		m.loadConstant(0);
		m.istore(n);
		m.aload(dims);
		m.astore(cur);
		MethodCode.Label countLoop = m.newLabel();
		MethodCode.Label countDone = m.newLabel();
		m.labelBinding(countLoop);
		m.aload(cur);
		m.instanceOf(objectArrayClass);
		m.ifeq(countDone);
		m.iinc(n, 1);
		m.aload(cur);
		m.checkcast(objectArrayClass);
		m.loadConstant(1);
		m.aaload();
		m.astore(cur);
		m.goto_(countLoop);
		m.labelBinding(countDone);
		m.iload(n);
		m.anewarray(objectClass);
		m.astore(dimsArr);
		m.loadConstant(0);
		m.istore(idx);
		m.aload(dims);
		m.astore(cur);
		MethodCode.Label fillLoop = m.newLabel();
		m.labelBinding(fillLoop);
		m.iload(idx);
		m.iload(n);
		m.if_icmpge(afterDims);
		// dimsArr[idx] = car(cur)
		m.aload(dimsArr);
		m.iload(idx);
		m.aload(cur);
		m.checkcast(objectArrayClass);
		m.loadConstant(0);
		m.aaload();
		m.aastore();
		// cur = cdr(cur)
		m.aload(cur);
		m.checkcast(objectArrayClass);
		m.loadConstant(1);
		m.aaload();
		m.astore(cur);
		m.iinc(idx, 1);
		m.goto_(fillLoop);
		m.labelBinding(afterDims);
	}

	/**
	 * Builds {@code _arrayDimsTotal(Object dims) -> int}: the element count of a
	 * {@code make-array} dimensions argument -- a {@code Long} for the rank-1 shorthand,
	 * a proper list of them for any rank, nil for rank 0 -- after checking every
	 * dimension, as the interpreter's {@code Environment.parseDimensions} does. A
	 * dimension that is no {@code Long} in {@code [0, array-dimension-limit)} -- a
	 * {@code BigInteger}, a negative, a float, any other value -- and the first running
	 * product at or past {@code array-total-size-limit} throw
	 * {@code _opTypeErr(_oob(datum, limit), "MAKE-ARRAY", FUNNEL_TYPE)}, the report an
	 * out-of-range subscript gives; a dotted list's tail is {@code MAKE-ARRAY}'s
	 * {@code LIST} type-error. The rank-1 {@code Long} is two compares.
	 * @param cp the constant pool
	 * @param selfClass the program class
	 * @param consShape what tells a cons from the other {@code Object[]} values
	 * @return the method
	 */
	private static ArrayMethod buildDimsTotal(ConstantPool cp, ClassEntry selfClass,
			JvmOperandTypeRuntime.ConsShape consShape) {
		ClassEntry longClass = cp.classEntry("java/lang/Long");
		MethodRefEntry longValue = cp.methodRef(longClass, "longValue", "()J");
		MethodRefEntry longValueOf = cp.methodRef(longClass, "valueOf", "(J)Ljava/lang/Long;");
		long limit = am.ik.rontolisp.ClConstants.arraySizeLimit(false);
		LongEntry limitEntry = cp.entries().longEntry(limit);
		// Slots: 0 = dims, 1 = the list cursor, 2 = the cursor as a cons, 3 = its car,
		// 4-5 = the running product, 6-7 = the dimension.
		int dims = 0, cur = 1, cell = 2, head = 3, total = 4, size = 6;
		MethodCode a = new MethodCode();
		MethodCode.Label notLong = a.newLabel();
		MethodCode.Label loop = a.newLabel();
		MethodCode.Label done = a.newLabel();
		MethodCode.Label notCons = a.newLabel();
		MethodCode.Label badDims = a.newLabel();
		MethodCode.Label badHead = a.newLabel();
		MethodCode.Label badTotal = a.newLabel();
		a.aload(dims);
		a.instanceOf(longClass);
		a.ifeq(notLong);
		emitLongDimension(a, dims, size, longClass, longValue, limitEntry, badDims);
		a.lload(size);
		a.l2i();
		a.ireturn();
		a.labelBinding(notLong);
		a.lconst_1();
		a.lstore(total);
		a.aload(dims);
		a.astore(cur);
		a.labelBinding(loop);
		a.aload(cur);
		a.ifnull(done);
		consShape.emitTest(a, cur, cell, head, notCons);
		a.aload(head);
		a.instanceOf(longClass);
		a.ifeq(badHead);
		emitLongDimension(a, head, size, longClass, longValue, limitEntry, badHead);
		a.lload(total);
		a.lload(size);
		a.lmul();
		a.lstore(total);
		a.lload(total);
		a.ldc(limitEntry);
		a.lcmp();
		a.ifge(badTotal);
		a.aload(cell);
		a.loadConstant(1);
		a.aaload();
		a.astore(cur);
		a.goto_(loop);
		a.labelBinding(done);
		a.lload(total);
		a.l2i();
		a.ireturn();
		// Not a cons: the argument itself is no dimension, or a dotted list ends here.
		a.labelBinding(notCons);
		a.aload(cur);
		a.aload(dims);
		a.if_acmpeq(badDims);
		a.aload(cur);
		a.ldc(cp.stringEntry(OperandTypes.Kind.LIST.name()));
		a.invokestatic(JvmOperandTypeRuntime.self(cp, selfClass, JvmOperandTypeRuntime.TE_RAW,
				JvmOperandTypeRuntime.TE_RAW_DESC));
		emitMakeArrayThrow(a, cp, selfClass);
		a.labelBinding(badDims);
		a.aload(dims);
		emitOutOfLimitThrow(a, cp, selfClass, limit);
		a.labelBinding(badHead);
		a.aload(head);
		emitOutOfLimitThrow(a, cp, selfClass, limit);
		a.labelBinding(badTotal);
		a.lload(total);
		a.invokestatic(longValueOf);
		emitOutOfLimitThrow(a, cp, selfClass, limit);
		return new ArrayMethod(cp.utf8Entry(DIMS_TOTAL), cp.utf8Entry(DIMS_TOTAL_DESC), a);
	}

	// The Long in local slot as a long in sizeSlot, branching to bad unless it is in
	// [0, limit).
	private static void emitLongDimension(MethodCode a, int slot, int sizeSlot, ClassEntry longClass,
			MethodRefEntry longValue, LongEntry limit, MethodCode.Label bad) {
		a.aload(slot);
		a.checkcast(longClass);
		a.invokevirtual(longValue);
		a.lstore(sizeSlot);
		a.lload(sizeSlot);
		a.lconst_0();
		a.lcmp();
		a.iflt(bad);
		a.lload(sizeSlot);
		a.ldc(limit);
		a.lcmp();
		a.ifge(bad);
	}

	// throw _opTypeErr(_oob(datum, limit), "MAKE-ARRAY", FUNNEL_TYPE) over the datum on
	// the stack.
	private static void emitOutOfLimitThrow(MethodCode a, ConstantPool cp, ClassEntry selfClass, long limit) {
		a.ldc(cp.entries().intEntry((int) limit));
		a.invokestatic(
				JvmOperandTypeRuntime.self(cp, selfClass, JvmOperandTypeRuntime.OOB, JvmOperandTypeRuntime.OOB_DESC));
		emitMakeArrayThrow(a, cp, selfClass);
	}

	// throw _opTypeErr(error, "MAKE-ARRAY", FUNNEL_TYPE) over the error on the stack.
	private static void emitMakeArrayThrow(MethodCode a, ConstantPool cp, ClassEntry selfClass) {
		a.ldc(cp.stringEntry(OperandTypes.MAKE_ARRAY));
		a.ldc(cp.stringEntry(OperandTypes.FUNNEL_TYPE));
		a.invokestatic(JvmOperandTypeRuntime.self(cp, selfClass, JvmOperandTypeRuntime.OP_TYPE_ERR,
				JvmOperandTypeRuntime.OP_TYPE_ERR_DESC));
		a.athrow();
	}

	// Pushes the int product of the boxed Long dimension sizes of the header in
	// headerSlot (the total element count), using productSlot/mSlot as scratch.
	private static void emitDimsProduct(MethodCode a, ClassEntry longClass, ClassEntry objectArrayClass,
			MethodRefEntry longIntValue, int headerSlot, int productSlot, int mSlot) {
		a.loadConstant(1);
		a.istore(productSlot);
		a.loadConstant(0);
		a.istore(mSlot);
		MethodCode.Label loop = a.newLabel();
		MethodCode.Label done = a.newLabel();
		a.labelBinding(loop);
		a.iload(mSlot);
		a.aload(headerSlot);
		a.loadConstant(0);
		a.aaload();
		a.checkcast(objectArrayClass);
		a.arraylength();
		a.if_icmpge(done);
		a.iload(productSlot);
		a.aload(headerSlot);
		a.loadConstant(0);
		a.aaload();
		a.checkcast(objectArrayClass);
		a.iload(mSlot);
		a.aaload();
		a.checkcast(longClass);
		a.invokevirtual(longIntValue);
		a.imul();
		a.istore(productSlot);
		a.iinc(mSlot, 1);
		a.goto_(loop);
		a.labelBinding(done);
		a.iload(productSlot);
	}

	// Pushes the slot-0 header Object[] of the array in arrSlot.
	// Copies the first `count` slots of the header in headerSlot into the fresh Object[]
	// on top of the stack (which is left there), leaving the remaining slots null.
	private static void emitCopyHeaderSlots(MethodCode a, int headerSlot, int count) {
		for (int i = 0; i < count; i++) {
			a.dup();
			a.loadConstant(i);
			a.aload(headerSlot);
			a.loadConstant(i);
			a.aaload();
			a.aastore();
		}
	}

	private static void emitLoadHeader(MethodCode a, ClassEntry arrayListClass, ClassEntry objectArrayClass,
			MethodRefEntry alGet, int arrSlot) {
		a.aload(arrSlot);
		a.checkcast(arrayListClass);
		a.loadConstant(0);
		a.invokevirtual(alGet);
		a.checkcast(objectArrayClass);
	}

	// Pushes the int first dimension size of the header in headerSlot.
	private static void emitLoadDim0(MethodCode a, ClassEntry longClass, ClassEntry objectArrayClass,
			MethodRefEntry longIntValue, int headerSlot) {
		a.aload(headerSlot);
		a.loadConstant(0);
		a.aaload();
		a.checkcast(objectArrayClass);
		a.loadConstant(0);
		a.aaload();
		a.checkcast(longClass);
		a.invokevirtual(longIntValue);
	}

	// Emits a full helper body returning "t" when header[slot] of the array in local 0
	// is non-null, else null (nil). A non-array argument (a plain string handed to
	// adjustable-array-p, cl-ppcre's gather-strings collector) is nil, not a cast
	// error.
	private static void emitHeaderSlotToBool(MethodCode a, ClassEntry arrayListClass, ClassEntry objectArrayClass,
			MethodRefEntry alGet, ConstantPool cp, int slot) {
		MethodCode.Label isNil = a.newLabel();
		a.aload(0);
		a.instanceOf(arrayListClass);
		a.ifeq(isNil);
		emitLoadHeader(a, arrayListClass, objectArrayClass, alGet, 0);
		a.loadConstant(slot);
		a.aaload();
		a.ifnull(isNil);
		a.ldc(cp.stringEntry("T"));
		a.areturn();
		a.labelBinding(isNil);
		a.aconst_null();
		a.areturn();
	}

	// Pushes the int fill pointer of the header in headerSlot, which _ckFp at the site
	// has
	// checked carries one.
	private static void emitLoadFillPointer(MethodCode a, ClassEntry longClass, MethodRefEntry longIntValue,
			int headerSlot) {
		a.aload(headerSlot);
		a.loadConstant(1);
		a.aaload();
		a.checkcast(longClass);
		a.invokevirtual(longIntValue);
	}

	// Pushes a type spelled as nested lists of symbol names and Longs
	// (OperandTypes.FILL_POINTER_VECTOR_TYPE) as the Lisp value it spells: a String is a
	// symbol, a list a chain of Object[2] conses.
	static void emitTypeValue(MethodCode a, ConstantPool cp, ClassEntry objectClass, MethodRefEntry longValueOf,
			Object type) {
		if (type instanceof List<?> list) {
			List<Runnable> elements = new java.util.ArrayList<>();
			for (Object element : list) {
				Object nonNull = java.util.Objects.requireNonNull(element);
				elements.add(() -> emitTypeValue(a, cp, objectClass, longValueOf, nonNull));
			}
			emitList(a, objectClass, elements);
		}
		else if (type instanceof Long n) {
			a.ldc(cp.entries().longEntry(n));
			a.invokestatic(longValueOf);
		}
		else {
			a.ldc(cp.stringEntry((String) type));
		}
	}

	// Pushes a proper list of what each element pushes: {e0, {e1, ... null}}.
	private static void emitList(MethodCode a, ClassEntry objectClass, List<Runnable> elements) {
		for (Runnable element : elements) {
			a.loadConstant(2);
			a.anewarray(objectClass);
			a.dup();
			a.loadConstant(0);
			element.run();
			a.aastore();
			a.dup();
			a.loadConstant(1);
		}
		a.aconst_null();
		for (int i = 0; i < elements.size(); i++) {
			a.aastore();
		}
	}

	// new RuntimeException(message); athrow.
	// The shared make-array :fill-pointer rule, in bytecode: null (the keyword absent or
	// nil) leaves fpVal null, a Long is that value range-checked against the array's own
	// element count, and anything else -- i.e. t -- is that count. Only a rank-1 array
	// may carry one. A DISPLACED view resolves it exactly the same way: total is the
	// VIEW's element count, never the target's.
	private static void emitResolveFillPointer(MethodCode m, ClassEntry longClass, MethodRefEntry longIntValue,
			ClassEntry rtExClass, MethodRefEntry rtExInit, ConstantPool cp, int fp, int dimsArr, int total, int fpVal,
			int v) {
		m.aconst_null();
		m.astore(fpVal);
		MethodCode.Label afterFp = m.newLabel();
		m.aload(fp);
		m.ifnull(afterFp);
		MethodCode.Label rankOk = m.newLabel();
		m.aload(dimsArr);
		m.arraylength();
		m.loadConstant(1);
		m.if_icmpeq(rankOk);
		emitThrow(m, rtExClass, rtExInit, cp.stringEntry("make-array: :fill-pointer requires a rank-1 array"));
		m.labelBinding(rankOk);
		MethodCode.Label fpIsT = m.newLabel();
		m.aload(fp);
		m.instanceOf(longClass);
		m.ifeq(fpIsT);
		m.aload(fp);
		m.checkcast(longClass);
		m.invokevirtual(longIntValue);
		m.istore(v);
		MethodCode.Label fpBad = m.newLabel();
		MethodCode.Label fpLongOk = m.newLabel();
		m.iload(v);
		m.iflt(fpBad);
		m.iload(v);
		m.iload(total);
		m.if_icmpgt(fpBad);
		m.goto_(fpLongOk);
		m.labelBinding(fpBad);
		emitThrow(m, rtExClass, rtExInit, cp.stringEntry("make-array: :fill-pointer out of range"));
		m.labelBinding(fpLongOk);
		m.aload(fp);
		m.astore(fpVal);
		m.goto_(afterFp);
		// :fill-pointer t -> the vector size (dimsArr[0] is already the boxed Long)
		m.labelBinding(fpIsT);
		m.aload(dimsArr);
		m.loadConstant(0);
		m.aaload();
		m.astore(fpVal);
		m.labelBinding(afterFp);
	}

	// Stores the array's ACTIVE length into nSlot: the header's fill pointer (slot 1)
	// when it carries one, otherwise dimension 0. Shared by every reader that stops at
	// the fill pointer -- and a DISPLACED view's header carries the slot too.
	private static void emitActiveLength(MethodCode a, ClassEntry longClass, ClassEntry objectArrayClass,
			MethodRefEntry longIntValue, int headerSlot, int nSlot) {
		MethodCode.Label useDim = a.newLabel();
		MethodCode.Label haveN = a.newLabel();
		a.aload(headerSlot);
		a.loadConstant(1);
		a.aaload();
		a.ifnull(useDim);
		a.aload(headerSlot);
		a.loadConstant(1);
		a.aaload();
		a.checkcast(longClass);
		a.invokevirtual(longIntValue);
		a.istore(nSlot);
		a.goto_(haveN);
		a.labelBinding(useDim);
		emitLoadDim0(a, longClass, objectArrayClass, longIntValue, headerSlot);
		a.istore(nSlot);
		a.labelBinding(haveN);
	}

	private static void emitThrow(MethodCode a, ClassEntry rtExClass, MethodRefEntry rtExInit, StringEntry message) {
		a.new_(rtExClass);
		a.dup();
		a.ldc(message);
		a.invokespecial(rtExInit);
		a.athrow();
	}

	/**
	 * The shared tail of every {@code _*CheckRank} helper: unbox {@code given} (local
	 * {@code givenSlot}) to an int (local {@code givSlot}) and compare it against the
	 * already-computed rank (local {@code rankSlot}); a match returns the array (local
	 * {@code arrSlot}) unchanged, a mismatch throws {@code _rankErr(arr, given)}.
	 * @param a the helper's code
	 * @param longClass {@code java/lang/Long}
	 * @param longIntValue {@code Long.intValue}
	 * @param rankErr the class's own {@link #RANK_ERR}
	 * @param arrSlot the array's local
	 * @param givenSlot the boxed subscript count's local
	 * @param rankSlot the rank's local
	 * @param givSlot a free int local
	 */
	static void emitRankCheckAndReturn(MethodCode a, ClassEntry longClass, MethodRefEntry longIntValue,
			MethodRefEntry rankErr, int arrSlot, int givenSlot, int rankSlot, int givSlot) {
		a.aload(givenSlot);
		a.checkcast(longClass);
		a.invokevirtual(longIntValue);
		a.istore(givSlot);
		MethodCode.Label ok = a.newLabel();
		a.iload(rankSlot);
		a.iload(givSlot);
		a.if_icmpeq(ok);
		a.aload(arrSlot);
		a.iload(givSlot);
		a.invokestatic(rankErr);
		a.athrow();
		a.labelBinding(ok);
		a.aload(arrSlot);
		a.areturn();
	}

	// Stores val at the fill pointer of the array in arrSlot (data index 1 + fp),
	// advances the stored fill pointer and returns Long.valueOf(fp). The store goes
	// through _rmSet, not ArrayList.set, so a DISPLACED fill-pointered view writes
	// THROUGH to its target's storage the way SBCL's does -- the view holds no data
	// slots of its own.
	private static void emitStoreAtFillPointerAndAdvance(MethodCode a, MethodRefEntry rmSet, MethodRefEntry longValueOf,
			int valSlot, int arrSlot, int headerSlot, int fpSlot) {
		a.aload(arrSlot);
		a.loadConstant(1);
		a.iload(fpSlot);
		a.iadd();
		a.aload(valSlot);
		a.invokestatic(rmSet);
		a.pop();
		a.aload(headerSlot);
		a.loadConstant(1);
		a.iload(fpSlot);
		a.loadConstant(1);
		a.iadd();
		a.i2l();
		a.invokestatic(longValueOf);
		a.aastore();
		a.iload(fpSlot);
		a.i2l();
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
	static List<ArrayMethod> buildToStringMethods(ConstantPool cp, MethodRefEntry lispToString,
			MethodRefEntry lispToDisplayString, ClassEntry selfClass, JvmRuntimeBuilder.RenderGuardRefs renderGuard) {
		ClassEntry arrayListClass = cp.classEntry("java/util/ArrayList");
		ClassEntry longClass = cp.classEntry("java/lang/Long");
		ClassEntry objectArrayClass = cp.classEntry("[Ljava/lang/Object;");
		MethodRefEntry rmGet = cp.methodRef(selfClass, RM_GET, RM_GET_DESC);
		ClassEntry sbClass = cp.classEntry("java/lang/StringBuilder");
		ClassEntry stringClass = cp.classEntry("java/lang/String");
		MethodRefEntry alGet = cp.methodRef(arrayListClass, "get", "(I)Ljava/lang/Object;");
		MethodRefEntry alSize = cp.methodRef(arrayListClass, "size", "()I");
		MethodRefEntry longIntValue = cp.methodRef(longClass, "intValue", "()I");
		MethodRefEntry sbInit = cp.methodRef(sbClass, "<init>", "(Ljava/lang/String;)V");
		MethodRefEntry sbAppend = cp.methodRef(sbClass, "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;");
		MethodRefEntry sbToString = cp.methodRef(sbClass, "toString", "()Ljava/lang/String;");
		MethodRefEntry stringValueOfInt = cp.methodRef(stringClass, "valueOf", "(I)Ljava/lang/String;");
		MethodRefEntry elementType = cp.methodRef(selfClass, ELEMENT_TYPE, ELEMENT_TYPE_DESC);

		List<ArrayMethod> methods = new ArrayList<>();
		methods.add(new ArrayMethod(cp.utf8Entry(TO_STRING), cp.utf8Entry(TO_STRING_DESC),
				buildToString(cp, arrayListClass, longClass, objectArrayClass, alGet, alSize, longIntValue, sbInit,
						sbAppend, sbToString, stringValueOfInt, lispToString, rmGet, elementType, renderGuard)));
		methods.add(new ArrayMethod(cp.utf8Entry(TO_DISPLAY_STRING), cp.utf8Entry(TO_STRING_DESC),
				buildToString(cp, arrayListClass, longClass, objectArrayClass, alGet, alSize, longIntValue, sbInit,
						sbAppend, sbToString, stringValueOfInt, lispToDisplayString, rmGet, elementType, renderGuard)));
		return methods;
	}

	// Emits one array-printing helper implementing the readable #(...) / #nA(...)
	// syntax: a nested group paren opens where the flat index k is a multiple of that
	// dimension's stride (the product of the trailing dimension sizes) and closes where
	// k + 1 is. The element count clamps to the fill pointer when the header carries
	// one, so a fill-pointer vector prints only up to it. Locals: 0=arr, 1=list, 2=sb,
	// 3=n (element count), 4=dims (Object[]), 5=k, 6=j (dimension), 7=stride,
	// 8=m (stride scratch), 9=rank, 10=header (Object[]).
	private static MethodCode buildToString(ConstantPool cp, ClassEntry arrayListClass, ClassEntry longClass,
			ClassEntry objectArrayClass, MethodRefEntry alGet, MethodRefEntry alSize, MethodRefEntry longIntValue,
			MethodRefEntry sbInit, MethodRefEntry sbAppend, MethodRefEntry sbToString, MethodRefEntry stringValueOfInt,
			MethodRefEntry elementFormat, MethodRefEntry rmGet, MethodRefEntry elementType,
			JvmRuntimeBuilder.RenderGuardRefs renderGuard) {
		int arr = 0, list = 1, sb = 2, n = 3, dimsArr = 4, k = 5, j = 6, stride = 7, m = 8, rank = 9, header = 10,
				guardScratch = 11;
		MethodCode a = new MethodCode();
		// The cycle guard (the shared RenderGuardRefs discipline over the
		// _renderPath/_renderDepth statics, kept in step by
		// JvmLispCompilerTest.compileAndRunPrintOfACyclicConsIsFinite): an array
		// already on the current rendering path -- an array holding itself, directly or
		// through a list -- or the frame past the 256-frame depth cap renders as "#",
		// the *print-level* cutoff marker. A packed array routes here through its
		// boxed-general conversion, so it opens the same one frame the interpreter's
		// packed renderers open.
		MethodCode.Label pathInited = a.newLabel();
		a.getstatic(renderGuard.pathField());
		a.ifnonnull(pathInited);
		a.loadConstant(RenderCycleGuard.MAX_RENDER_DEPTH);
		a.anewarray(renderGuard.objectClass());
		a.putstatic(renderGuard.pathField());
		a.labelBinding(pathInited);
		a.loadConstant(0);
		a.istore(guardScratch);
		MethodCode.Label scanLoop = a.newLabel();
		MethodCode.Label scanDone = a.newLabel();
		MethodCode.Label scanMiss = a.newLabel();
		a.labelBinding(scanLoop);
		a.iload(guardScratch);
		a.getstatic(renderGuard.depthField());
		a.if_icmpge(scanDone);
		a.getstatic(renderGuard.pathField());
		a.iload(guardScratch);
		a.aaload();
		a.aload(arr);
		a.if_acmpne(scanMiss);
		a.ldc(renderGuard.depthMarkerStr());
		a.areturn();
		a.labelBinding(scanMiss);
		a.iinc(guardScratch, 1);
		a.goto_(scanLoop);
		a.labelBinding(scanDone);
		MethodCode.Label underCap = a.newLabel();
		a.getstatic(renderGuard.depthField());
		a.istore(guardScratch);
		a.iload(guardScratch);
		a.loadConstant(RenderCycleGuard.MAX_RENDER_DEPTH);
		a.if_icmplt(underCap);
		a.ldc(renderGuard.depthMarkerStr());
		a.areturn();
		a.labelBinding(underCap);
		a.getstatic(renderGuard.pathField());
		a.iload(guardScratch);
		a.aload(arr);
		a.aastore();
		a.iload(guardScratch);
		a.loadConstant(1);
		a.iadd();
		a.putstatic(renderGuard.depthField());
		// list = (ArrayList) arr; header = (Object[]) list.get(0)
		a.aload(arr);
		a.checkcast(arrayListClass);
		a.astore(list);
		a.aload(list);
		a.loadConstant(0);
		a.invokevirtual(alGet);
		a.checkcast(objectArrayClass);
		a.astore(header);
		// n = header[1] != null ? fill pointer : product of the dims (the total element
		// count; a displaced array holds no data slots, so size() - 1 would be wrong)
		MethodCode.Label useSize = a.newLabel();
		MethodCode.Label afterN = a.newLabel();
		a.aload(header);
		a.loadConstant(1);
		a.aaload();
		a.ifnull(useSize);
		a.aload(header);
		a.loadConstant(1);
		a.aaload();
		a.checkcast(longClass);
		a.invokevirtual(longIntValue);
		a.istore(n);
		a.goto_(afterN);
		a.labelBinding(useSize);
		emitDimsProduct(a, longClass, objectArrayClass, longIntValue, header, stride, m);
		a.istore(n);
		a.labelBinding(afterN);
		// dims = (Object[]) header[0]; rank = dims.length
		a.aload(header);
		a.loadConstant(0);
		a.aaload();
		a.checkcast(objectArrayClass);
		a.astore(dimsArr);
		a.aload(dimsArr);
		a.arraylength();
		a.istore(rank);
		// A rank-1 bit-stamped array prints #* when every element is 0/1, so a
		// printed bit vector reads back as one. make-array never
		// validates stores, so a non-bit element falls back to the general #()
		// vector below. The stamp is
		// read through _arrayElementType itself (which hops a displaced chain and
		// reads a packed target's width), never by hand off the header: a hand
		// transcription is exactly what let the adjustable and float shapes fall
		// through in %array-alike before (.kb/subseq-runtime.md). Locals k/j are
		// reused: the check loop runs before the general path initializes them,
		// and the build loop reuses k.
		ClassEntry bitStringClass = cp.classEntry("java/lang/String");
		MethodRefEntry bitStringEquals = cp.methodRef(bitStringClass, "equals", "(Ljava/lang/Object;)Z");
		MethodCode.Label bitGeneral = a.newLabel();
		a.iload(rank);
		a.loadConstant(1);
		a.if_icmpne(bitGeneral);
		a.ldc(cp.stringEntry(am.ik.rontolisp.LispNames.BIT));
		a.aload(arr);
		a.invokestatic(elementType);
		a.invokevirtual(bitStringEquals);
		a.ifeq(bitGeneral);
		// Validate: every element a Long 0/1 (read displaced-aware via _rmGet).
		a.loadConstant(0);
		a.istore(k);
		MethodCode.Label bitCheckLoop = a.newLabel();
		MethodCode.Label bitCheckDone = a.newLabel();
		a.labelBinding(bitCheckLoop);
		a.iload(k);
		a.iload(n);
		a.if_icmpge(bitCheckDone);
		a.aload(list);
		a.iload(k);
		a.loadConstant(1);
		a.iadd();
		a.invokestatic(rmGet);
		a.dup();
		a.instanceOf(longClass);
		MethodCode.Label bitIsLong = a.newLabel();
		a.ifne(bitIsLong);
		a.pop();
		a.goto_(bitGeneral);
		a.labelBinding(bitIsLong);
		a.checkcast(longClass);
		a.invokevirtual(longIntValue);
		a.istore(j);
		a.iload(j);
		MethodCode.Label bitCheckNext = a.newLabel();
		a.ifeq(bitCheckNext);
		a.iload(j);
		a.loadConstant(1);
		a.if_icmpne(bitGeneral);
		a.labelBinding(bitCheckNext);
		a.iinc(k, 1);
		a.goto_(bitCheckLoop);
		a.labelBinding(bitCheckDone);
		// Build "#*" + bits.
		a.new_(sbClass(cp));
		a.dup();
		a.ldc(cp.stringEntry("#*"));
		a.invokespecial(sbInit);
		a.astore(sb);
		a.loadConstant(0);
		a.istore(k);
		MethodCode.Label bitBuildLoop = a.newLabel();
		MethodCode.Label bitBuildDone = a.newLabel();
		a.labelBinding(bitBuildLoop);
		a.iload(k);
		a.iload(n);
		a.if_icmpge(bitBuildDone);
		a.aload(sb);
		a.aload(list);
		a.iload(k);
		a.loadConstant(1);
		a.iadd();
		a.invokestatic(rmGet);
		a.checkcast(longClass);
		a.invokevirtual(longIntValue);
		MethodCode.Label bitIsOne = a.newLabel();
		MethodCode.Label bitAppended = a.newLabel();
		a.ifne(bitIsOne);
		a.ldc(cp.stringEntry("0"));
		a.goto_(bitAppended);
		a.labelBinding(bitIsOne);
		a.ldc(cp.stringEntry("1"));
		a.labelBinding(bitAppended);
		a.invokevirtual(sbAppend);
		a.pop();
		a.iinc(k, 1);
		a.goto_(bitBuildLoop);
		a.labelBinding(bitBuildDone);
		a.aload(sb);
		a.invokevirtual(sbToString);
		MethodCode.Label bitPopClamp = a.newLabel();
		a.getstatic(renderGuard.depthField());
		a.loadConstant(1);
		a.isub();
		a.istore(guardScratch);
		a.iload(guardScratch);
		a.iflt(bitPopClamp);
		a.getstatic(renderGuard.pathField());
		a.iload(guardScratch);
		a.aconst_null();
		a.aastore();
		a.iload(guardScratch);
		a.putstatic(renderGuard.depthField());
		a.areturn();
		a.labelBinding(bitPopClamp);
		a.loadConstant(0);
		a.putstatic(renderGuard.depthField());
		a.areturn();
		a.labelBinding(bitGeneral);
		// sb = new StringBuilder("#"); rank 1 appends "(", rank n appends n then "A(",
		// and rank 0 appends "0A" with NO paren -- #0A<datum> is the whole rank-0
		// syntax, so the closing paren at the tail is skipped for it too.
		a.new_(sbClass(cp));
		a.dup();
		a.ldc(cp.stringEntry("#"));
		a.invokespecial(sbInit);
		a.astore(sb);
		MethodCode.Label rankN = a.newLabel();
		MethodCode.Label rank0 = a.newLabel();
		MethodCode.Label afterPrefix = a.newLabel();
		a.iload(rank);
		a.ifeq(rank0);
		a.iload(rank);
		a.loadConstant(1);
		a.if_icmpne(rankN);
		appendStr(a, sb, sbAppend, cp.stringEntry("("));
		a.goto_(afterPrefix);
		a.labelBinding(rank0);
		appendStr(a, sb, sbAppend, cp.stringEntry("0A"));
		a.goto_(afterPrefix);
		a.labelBinding(rankN);
		a.aload(sb);
		a.iload(rank);
		a.invokestatic(stringValueOfInt);
		a.invokevirtual(sbAppend);
		a.pop();
		appendStr(a, sb, sbAppend, cp.stringEntry("A("));
		a.labelBinding(afterPrefix);
		// k = 0
		a.loadConstant(0);
		a.istore(k);
		MethodCode.Label loop = a.newLabel();
		MethodCode.Label end = a.newLabel();
		a.labelBinding(loop);
		a.iload(k);
		a.iload(n);
		a.if_icmpge(end);
		// if (k != 0) sb.append(" ")
		MethodCode.Label noSpace = a.newLabel();
		a.iload(k);
		a.ifeq(noSpace);
		appendStr(a, sb, sbAppend, cp.stringEntry(" "));
		a.labelBinding(noSpace);
		// opens: for j in 1..rank-1 (outermost first): if (k % stride(j) == 0) "("
		a.loadConstant(1);
		a.istore(j);
		MethodCode.Label openLoop = a.newLabel();
		MethodCode.Label openDone = a.newLabel();
		a.labelBinding(openLoop);
		a.iload(j);
		a.iload(rank);
		a.if_icmpge(openDone);
		emitStride(a, longClass, longIntValue, dimsArr, j, stride, m, rank);
		MethodCode.Label noOpen = a.newLabel();
		a.iload(k);
		a.iload(stride);
		a.irem();
		a.ifne(noOpen);
		appendStr(a, sb, sbAppend, cp.stringEntry("("));
		a.labelBinding(noOpen);
		a.iinc(j, 1);
		a.goto_(openLoop);
		a.labelBinding(openDone);
		// sb.append(elementFormat(_rmGet(list, k + 1))) -- displaced-aware element read
		a.aload(sb);
		a.aload(list);
		a.iload(k);
		a.loadConstant(1);
		a.iadd();
		a.invokestatic(rmGet);
		a.invokestatic(elementFormat);
		a.invokevirtual(sbAppend);
		a.pop();
		// closes: for j in rank-1..1 (innermost first): if ((k+1) % stride(j) == 0) ")"
		a.iload(rank);
		a.loadConstant(1);
		a.isub();
		a.istore(j);
		MethodCode.Label closeLoop = a.newLabel();
		MethodCode.Label closeDone = a.newLabel();
		a.labelBinding(closeLoop);
		a.iload(j);
		a.loadConstant(1);
		a.if_icmplt(closeDone);
		emitStride(a, longClass, longIntValue, dimsArr, j, stride, m, rank);
		MethodCode.Label noClose = a.newLabel();
		a.iload(k);
		a.loadConstant(1);
		a.iadd();
		a.iload(stride);
		a.irem();
		a.ifne(noClose);
		appendStr(a, sb, sbAppend, cp.stringEntry(")"));
		a.labelBinding(noClose);
		a.iinc(j, -1);
		a.goto_(closeLoop);
		a.labelBinding(closeDone);
		// k++; loop
		a.iinc(k, 1);
		a.goto_(loop);
		a.labelBinding(end);
		// sb.append(")"); return sb.toString() -- under the guard's pop, the twin of
		// JvmRuntimeBuilder.emitRenderGuardExitAndReturn: over one read, clamped so a
		// rendering race between request threads can at worst misplace a marker. A
		// rank-0 array opened no paren, so it closes none.
		MethodCode.Label noRparen = a.newLabel();
		a.iload(rank);
		a.ifeq(noRparen);
		appendStr(a, sb, sbAppend, cp.stringEntry(")"));
		a.labelBinding(noRparen);
		a.aload(sb);
		a.invokevirtual(sbToString);
		MethodCode.Label popClamp = a.newLabel();
		a.getstatic(renderGuard.depthField());
		a.loadConstant(1);
		a.isub();
		a.istore(guardScratch);
		a.iload(guardScratch);
		a.iflt(popClamp);
		a.getstatic(renderGuard.pathField());
		a.iload(guardScratch);
		a.aconst_null();
		a.aastore();
		a.iload(guardScratch);
		a.putstatic(renderGuard.depthField());
		a.areturn();
		a.labelBinding(popClamp);
		a.loadConstant(0);
		a.putstatic(renderGuard.depthField());
		a.areturn();
		return a;
	}

	private static ClassEntry sbClass(ConstantPool cp) {
		return cp.classEntry("java/lang/StringBuilder");
	}

	// stride = product of ((Long) dims[m]).intValue() for m in j..rank-1.
	private static void emitStride(MethodCode a, ClassEntry longClass, MethodRefEntry longIntValue, int dimsArrSlot,
			int jSlot, int strideSlot, int mSlot, int rankSlot) {
		a.loadConstant(1);
		a.istore(strideSlot);
		a.iload(jSlot);
		a.istore(mSlot);
		MethodCode.Label strideLoop = a.newLabel();
		MethodCode.Label strideDone = a.newLabel();
		a.labelBinding(strideLoop);
		a.iload(mSlot);
		a.iload(rankSlot);
		a.if_icmpge(strideDone);
		a.iload(strideSlot);
		a.aload(dimsArrSlot);
		a.iload(mSlot);
		a.aaload();
		a.checkcast(longClass);
		a.invokevirtual(longIntValue);
		a.imul();
		a.istore(strideSlot);
		a.iinc(mSlot, 1);
		a.goto_(strideLoop);
		a.labelBinding(strideDone);
	}

	// sb.append(str); discard the returned StringBuilder.
	private static void appendStr(MethodCode a, int sbSlot, MethodRefEntry sbAppend, StringEntry str) {
		a.aload(sbSlot);
		a.ldc(str);
		a.invokevirtual(sbAppend);
		a.pop();
	}

	// Pushes the rank-2 flat index 1 + i*cols + j, where arr is in slot 0, i in slot 1,
	// j in slot 2, and the dims are the Object[] dimension header, loaded into dimsSlot:
	// each subscript is checked against ITS OWN dimension first (_ckBound), so a column
	// past its dimension is out of range rather than folding into the next row.
	private static void emitFlat2(MethodCode a, ClassEntry arrayListClass, ClassEntry longClass,
			ClassEntry objectArrayClass, MethodRefEntry get, MethodRefEntry intValue, MethodRefEntry ckBound,
			int dimsSlot) {
		emitLoadDims(a, arrayListClass, objectArrayClass, get, 0);
		a.astore(dimsSlot);
		a.loadConstant(1);
		a.aload(1);
		emitDim(a, longClass, intValue, dimsSlot, 0);
		a.invokestatic(ckBound);
		emitDim(a, longClass, intValue, dimsSlot, 1);
		a.imul();
		a.iadd();
		a.aload(2);
		emitDim(a, longClass, intValue, dimsSlot, 1);
		a.invokestatic(ckBound);
		a.iadd();
	}

	/**
	 * Branches to {@code notString} unless the local {@code slot} holds a string: a
	 * {@code String} framed by the quote, the test {@code stringp} makes -- a symbol is
	 * the other {@code String}, and no array. Falls through for a string. Peak operand
	 * stack: 2.
	 */
	private static void emitStringTest(MethodCode a, ClassEntry strClass, MethodRefEntry strCharAt, int slot,
			MethodCode.Label notString) {
		a.aload(slot);
		a.instanceOf(strClass);
		a.ifeq(notString);
		a.aload(slot);
		a.checkcast(strClass);
		a.loadConstant(0);
		a.invokevirtual(strCharAt);
		a.loadConstant('"');
		a.if_icmpne(notString);
	}

	/**
	 * Emits the general accessors' operand check over the local {@code slot}: a general
	 * array (an {@code ArrayList}; the packed families were answered a step up the chain)
	 * passes, and so does a string when {@code strClass} is given (a quote-framed
	 * {@code String}, {@link #emitStringTest}), and anything else throws {@code _teRaw}'s
	 * unnamed {@code ARRAY} report for the operator's wrapper at the call site to name --
	 * the cast that followed failed with a {@code ClassCastException} that carried no
	 * datum.
	 */
	private static void emitArrayCheck(MethodCode a, ConstantPool cp, ClassEntry selfClass, ClassEntry arrayListClass,
			@Nullable ClassEntry strClass, @Nullable MethodRefEntry strCharAt, int slot) {
		MethodCode.Label pass = a.newLabel();
		a.aload(slot);
		a.instanceOf(arrayListClass);
		a.ifne(pass);
		if (strClass != null && strCharAt != null) {
			MethodCode.Label notString = a.newLabel();
			emitStringTest(a, strClass, strCharAt, slot, notString);
			a.goto_(pass);
			a.labelBinding(notString);
		}
		a.aload(slot);
		a.ldc(cp.stringEntry(OperandTypes.Kind.ARRAY.typeName()));
		a.invokestatic(JvmOperandTypeRuntime.self(cp, selfClass, JvmOperandTypeRuntime.TE_RAW,
				JvmOperandTypeRuntime.TE_RAW_DESC));
		a.athrow();
		a.labelBinding(pass);
	}

	// Stores into totalSlot the total size of the general array in slot 0 -- the bound
	// of a flat access -- read from what already holds it: a packed array's long[]
	// length, a boxed one's element count (its ArrayList's size past the header), and
	// only for a displaced view, whose storage is its target's, the product of its own
	// dims. Every load but the view's is one the element access makes anyway, so the JIT
	// shares them. headerSlot and kSlot are scratch.
	private static void emitFlatBound(MethodCode a, ClassEntry arrayListClass, ClassEntry objectArrayClass,
			ClassEntry longArrayClass, MethodRefEntry get, MethodRefEntry size, ClassEntry longClass,
			MethodRefEntry intValue, int headerSlot, int kSlot, int totalSlot) {
		MethodCode.Label notPacked = a.newLabel();
		MethodCode.Label boxed = a.newLabel();
		MethodCode.Label done = a.newLabel();
		a.aload(0);
		a.checkcast(arrayListClass);
		a.loadConstant(0);
		a.invokevirtual(get);
		a.checkcast(objectArrayClass);
		a.astore(headerSlot);
		a.aload(headerSlot);
		a.arraylength();
		a.loadConstant(6);
		a.if_icmpne(notPacked);
		a.aload(headerSlot);
		a.loadConstant(5);
		a.aaload();
		a.checkcast(longArrayClass);
		a.arraylength();
		a.istore(totalSlot);
		a.goto_(done);
		a.labelBinding(notPacked);
		// a displaced view: header length > 4 with a target in slot 3
		a.aload(headerSlot);
		a.arraylength();
		a.loadConstant(4);
		a.if_icmple(boxed);
		a.aload(headerSlot);
		a.loadConstant(3);
		a.aaload();
		a.ifnull(boxed);
		a.aload(headerSlot);
		a.loadConstant(0);
		a.aaload();
		a.checkcast(objectArrayClass);
		a.astore(headerSlot);
		emitTotalSize(a, longClass, intValue, headerSlot, kSlot, totalSlot);
		a.goto_(done);
		a.labelBinding(boxed);
		a.aload(0);
		a.checkcast(arrayListClass);
		a.invokevirtual(size);
		a.loadConstant(1);
		a.isub();
		a.istore(totalSlot);
		a.labelBinding(done);
	}

	// Pushes the Object[] dimension header of the general array in arrSlot:
	// (Object[]) ((Object[]) ((ArrayList) arr).get(0))[0].
	private static void emitLoadDims(MethodCode a, ClassEntry arrayListClass, ClassEntry objectArrayClass,
			MethodRefEntry get, int arrSlot) {
		a.aload(arrSlot);
		a.checkcast(arrayListClass);
		a.loadConstant(0);
		a.invokevirtual(get);
		a.checkcast(objectArrayClass);
		a.loadConstant(0);
		a.aaload();
		a.checkcast(objectArrayClass);
	}

	// Pushes dimension k of the dims Object[] in dimsSlot as an int.
	private static void emitDim(MethodCode a, ClassEntry longClass, MethodRefEntry intValue, int dimsSlot, int k) {
		a.aload(dimsSlot);
		a.loadConstant(k);
		a.aaload();
		a.checkcast(longClass);
		a.invokevirtual(intValue);
	}

	// Stores into totalSlot the total size of the dims Object[] in dimsSlot -- the
	// product of its dimensions, 1 for rank 0 -- the bound of a flat access.
	private static void emitTotalSize(MethodCode a, ClassEntry longClass, MethodRefEntry intValue, int dimsSlot,
			int kSlot, int totalSlot) {
		// rank 1, the common case: the one dimension, no loop
		MethodCode.Label general = a.newLabel();
		MethodCode.Label done = a.newLabel();
		a.aload(dimsSlot);
		a.arraylength();
		a.loadConstant(1);
		a.if_icmpne(general);
		emitDim(a, longClass, intValue, dimsSlot, 0);
		a.istore(totalSlot);
		a.goto_(done);
		a.labelBinding(general);
		a.loadConstant(1);
		a.istore(totalSlot);
		a.loadConstant(0);
		a.istore(kSlot);
		MethodCode.Label loop = a.newLabel();
		a.labelBinding(loop);
		a.iload(kSlot);
		a.aload(dimsSlot);
		a.arraylength();
		a.if_icmpge(done);
		a.iload(totalSlot);
		a.aload(dimsSlot);
		a.iload(kSlot);
		a.aaload();
		a.checkcast(longClass);
		a.invokevirtual(intValue);
		a.imul();
		a.istore(totalSlot);
		a.iinc(kSlot, 1);
		a.goto_(loop);
		a.labelBinding(done);
	}

	// Computes the Horner flat index over an Object[] of Long subscripts (in the slot
	// subs) against the array in slot 0, leaving it in the int slot flat:
	// flat = 0; for k in 0..: flat = flat * dims[k] + subs[k], each subscript checked
	// against its own dimension (_ckBound). The fold starts at 0 (not at subs[0]) so an
	// EMPTY subscript array -- a rank-0 array -- answers 0.
	private static void emitFlatN(MethodCode a, ClassEntry arrayListClass, ClassEntry longClass,
			ClassEntry objectArrayClass, MethodRefEntry get, MethodRefEntry intValue, MethodRefEntry ckBound, int subs,
			int flat, int kSlot, int nSlot, int dimsSlot) {
		// dims = (Object[]) ((Object[]) ((ArrayList) arr).get(0))[0]
		emitLoadDims(a, arrayListClass, objectArrayClass, get, 0);
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
		a.loadConstant(0);
		a.istore(flat);
		a.loadConstant(0);
		a.istore(kSlot);
		MethodCode.Label loop = a.newLabel();
		MethodCode.Label done = a.newLabel();
		a.labelBinding(loop);
		a.iload(kSlot);
		a.iload(nSlot);
		a.if_icmpge(done);
		a.iload(flat);
		a.aload(dimsSlot);
		a.iload(kSlot);
		a.aaload();
		a.checkcast(longClass);
		a.invokevirtual(intValue);
		a.imul();
		a.aload(subs);
		a.iload(kSlot);
		a.aaload();
		a.aload(dimsSlot);
		a.iload(kSlot);
		a.aaload();
		a.checkcast(longClass);
		a.invokevirtual(intValue);
		a.invokestatic(ckBound);
		a.iadd();
		a.istore(flat);
		a.iinc(kSlot, 1);
		a.goto_(loop);
		a.labelBinding(done);
	}

	// Emits the ArrayElementTypes code -> element type VALUE switch: reads the int in
	// codeSlot and leaves the value in etSlot, then branches to done. The value is the
	// runtime shape array-element-type answers -- a name string for the symbol types,
	// the cons {"UNSIGNED-BYTE", {Long, null}} for the packed integer widths -- built
	// once at allocation rather than at every read.
	private static void emitElementTypeForCode(MethodCode a, ConstantPool cp, ClassEntry objectClass,
			MethodRefEntry longValueOf, int codeSlot, int etSlot, MethodCode.Label done) {
		emitElementTypeCase(a, codeSlot, etSlot, done, am.ik.rontolisp.ArrayElementTypes.CHARACTER,
				() -> a.ldc(cp.stringEntry(am.ik.rontolisp.LispNames.CHARACTER_TYPE)));
		emitElementTypeCase(a, codeSlot, etSlot, done, am.ik.rontolisp.ArrayElementTypes.UNSIGNED_BYTE_8,
				() -> emitUnsignedByte(a, cp, objectClass, longValueOf, 8));
		emitElementTypeCase(a, codeSlot, etSlot, done, am.ik.rontolisp.ArrayElementTypes.UNSIGNED_BYTE_16,
				() -> emitUnsignedByte(a, cp, objectClass, longValueOf, 16));
		emitElementTypeCase(a, codeSlot, etSlot, done, am.ik.rontolisp.ArrayElementTypes.UNSIGNED_BYTE_32,
				() -> emitUnsignedByte(a, cp, objectClass, longValueOf, 32));
		emitElementTypeCase(a, codeSlot, etSlot, done, am.ik.rontolisp.ArrayElementTypes.SINGLE_FLOAT,
				() -> a.ldc(cp.stringEntry(am.ik.rontolisp.LispNames.SINGLE_FLOAT)));
		emitElementTypeCase(a, codeSlot, etSlot, done, am.ik.rontolisp.ArrayElementTypes.DOUBLE_FLOAT,
				() -> a.ldc(cp.stringEntry(am.ik.rontolisp.LispNames.DOUBLE_FLOAT)));
		// A GENERAL array that merely REMEMBERS bfloat16 decodes here too. The packed
		// bfloat16 representation itself does not reach this backend yet; this arm only
		// keeps array-element-type from answering nothing for a remembered width.
		emitElementTypeCase(a, codeSlot, etSlot, done, am.ik.rontolisp.ArrayElementTypes.BFLOAT16,
				() -> a.ldc(cp.stringEntry(am.ik.rontolisp.LispNames.BFLOAT16)));
		// A bit vector is the general boxed array stamped bit: the stamp is the whole
		// representation, so it decodes to the name the same way.
		emitElementTypeCase(a, codeSlot, etSlot, done, am.ik.rontolisp.ArrayElementTypes.BIT,
				() -> a.ldc(cp.stringEntry(am.ik.rontolisp.LispNames.BIT)));
		// ArrayElementTypes.T, which never reaches here: nothing is remembered for it.
		a.aconst_null();
		a.astore(etSlot);
		a.goto_(done);
	}

	private static void emitElementTypeCase(MethodCode a, int codeSlot, int etSlot, MethodCode.Label done, int code,
			Runnable value) {
		MethodCode.Label next = a.newLabel();
		a.iload(codeSlot);
		a.loadConstant(code);
		a.if_icmpne(next);
		value.run();
		a.astore(etSlot);
		a.goto_(done);
		a.labelBinding(next);
	}

	// new Object[]{"UNSIGNED-BYTE", new Object[]{Long.valueOf(width), null}} -- the cons
	// (unsigned-byte width), in the two-slot Object[] a cons cell is on this backend.
	private static void emitUnsignedByte(MethodCode a, ConstantPool cp, ClassEntry objectClass,
			MethodRefEntry longValueOf, int width) {
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
		a.loadConstant(width);
		a.i2l();
		a.invokestatic(longValueOf);
		a.aastore();
		a.aastore();
	}

}
