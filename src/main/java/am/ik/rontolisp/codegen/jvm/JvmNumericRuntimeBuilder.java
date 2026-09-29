package am.ik.rontolisp.codegen.jvm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import am.ik.jvm.ConstantPool;
import am.ik.jvm.ConstantPool.ClassConstant;
import am.ik.jvm.ConstantPool.FieldrefConstant;
import am.ik.jvm.ConstantPool.LongConstant;
import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.ConstantPool.Utf8Constant;
import am.ik.jvm.MethodCode;
import org.jspecify.annotations.Nullable;

/**
 * Builds JVM bytecode for the numeric runtime helpers that give compiled programs
 * automatic {@code long}-to-{@link java.math.BigInteger} promotion and exact rational
 * (ratio) arithmetic. Integers are represented at runtime as either {@code Long} or
 * {@code BigInteger}; a ratio is a normalized {@code BigInteger[2]}
 * <code>{numerator, denominator}</code> (coprime, denominator &gt; 1, sign on the
 * numerator). Every numeric operation goes through one of these {@code private static}
 * helpers, which perform the {@code long} fast path with overflow detection and fall back
 * to {@code BigInteger}/rational arithmetic. Results are normalized: a {@code BigInteger}
 * that fits in a {@code long} is demoted by {@code _norm}, and a rational whose
 * denominator reduces to one is demoted to an integer by {@code _rat}.
 *
 * <p>
 * The {@code _add}, {@code _sub}, {@code _mul} and {@code _neg} helpers use
 * {@code Math.*Exact} guarded by a {@code try/catch} on {@code ArithmeticException}; the
 * generated methods therefore carry an exception table. {@code _div} implements Common
 * Lisp exact division: it always goes through {@code _rat}, so {@code (/ 10 2)} is
 * {@code 5} and {@code (/ 10 3)} is the ratio {@code 10/3}.
 */
final class JvmNumericRuntimeBuilder {

	/** Operation keys used to look up the invokable helper method references. */
	static final String ADD = "_add";

	static final String SUB = "_sub";

	static final String MUL = "_mul";

	static final String NEG = "_neg";

	static final String DIV = "_div";

	static final String MOD = "_mod";

	static final String REM = "_rem";

	/** Floating-point modulo whose result takes the sign of the divisor. */
	static final String FMOD = "_fmod";

	/** Floating-point remainder: {@code DREM} with CLHS's sign for a zero result. */
	static final String FREM = "_frem";

	static final String CMP = "_cmp";

	static final String CMPB = "_cmpb";

	/** {@link #CMPB} bit for {@code a < b}. */
	static final int CMPB_LT = 1;

	/** {@link #CMPB} bit for {@code a = b}. */
	static final int CMPB_EQ = 2;

	/**
	 * {@link #CMPB} bit for {@code a > b}. An unordered (NaN) pair sets no bit at all.
	 */
	static final int CMPB_GT = 4;

	static final String ABS = "_abs";

	static final String SIGNUM = "_signum";

	static final String RANDOM = "_random";

	static final String MIN = "_min";

	static final String MAX = "_max";

	/**
	 * {@link #MIN} on raw doubles, for call sites that already have both operands
	 * unboxed.
	 */
	static final String FMIN = "_fmin";

	/**
	 * {@link #MAX} on raw doubles, for call sites that already have both operands
	 * unboxed.
	 */
	static final String FMAX = "_fmax";

	/** Coerces an {@code Object} (Long or BigInteger) to a {@code BigInteger}. */
	static final String BIG_OP = "_big";

	/** Normalizes a {@code BigInteger} back to a {@code Long} when it fits. */
	static final String NORM_OP = "_norm";

	/** Numerator of a rational value ({@code _big(x)} for integers). */
	static final String RAT_NUM = "_ratnum";

	/** Denominator of a rational value ({@code BigInteger.ONE} for integers). */
	static final String RAT_DEN = "_ratden";

	/** Builds a normalized rational value from a numerator and a denominator. */
	static final String RAT = "_rat";

	/** Converts any numeric value (Long, BigInteger, ratio, Double) to a Double. */
	static final String DBL = "_dbl";

	/**
	 * The correctly-rounded double nearest a numerator/denominator pair
	 * (round-half-even), shared by {@code _dbl}'s ratio arm. The same contract as
	 * {@link am.ik.rontolisp.LispRatio#doubleValue}, emitted here so the class stays
	 * self-contained (a compiled class runs with no rontolisp runtime on the classpath).
	 */
	static final String RAT_TO_DOUBLE = "_ratToDouble";

	/** Raises a rational base to an integer power, keeping an exact result. */
	static final String POW = "_pow";

	/** Value equality that compares ratios element-wise ({@code eql} semantics). */
	static final String EQV = "_eqv";

	/**
	 * Structural equality ({@code equal} semantics): cons cells are compared recursively
	 * by car and cdr, strings by content, everything else delegates to {@link #EQV}.
	 */
	static final String EQUAL = "_equal";

	/**
	 * Renders a number as a fixed-point decimal string, the {@code %fixed-decimal}
	 * primitive behind {@code format}'s {@code ~F} / {@code ~$}. The algorithm is
	 * {@link am.ik.rontolisp.compiler.FixedDecimal}, emitted here so the class stays
	 * self-contained (a compiled class runs with no rontolisp runtime on the classpath).
	 */
	static final String FIXED_DEC = "_fixdec";

	/** Truncates a rational toward zero. */
	static final String RAT_TRUNC = "_rtrunc";

	/** Floor of a rational (toward negative infinity). */
	static final String RAT_FLOOR = "_rfloor";

	/** Ceiling of a rational (toward positive infinity). */
	static final String RAT_CEIL = "_rceil";

	/** Rounds a rational to the nearest integer, ties to even. */
	static final String RAT_ROUND = "_rround";

	/**
	 * The exact quotient of a float division under one of the four rounding modes (0 =
	 * truncate, 1 = floor, 2 = ceiling, 3 = round), or {@code null} to decline.
	 */
	static final String FDIV = "_fdiv";

	/**
	 * A number as the exact rational it is: a finite {@code Double} becomes a
	 * {@code BigInteger[2]}, an integer answers itself, anything else {@code null}.
	 */
	static final String FRAT = "_frat";

	/**
	 * The {@code rational} built-in: integers and ratios answer themselves, a finite
	 * {@code Double} normalizes through {@code _frat} + {@code _rat}, and anything else
	 * (a complex, a non-finite float, a non-number) throws the interpreter's text for its
	 * kind.
	 */
	static final String RATIONAL = "_rational";

	/** Bitwise AND ({@code logand}) with a {@code long} fast path. */
	static final String LOGAND = "_logand";

	/** Bitwise inclusive OR ({@code logior}) with a {@code long} fast path. */
	static final String LOGIOR = "_logior";

	/** Bitwise exclusive OR ({@code logxor}) with a {@code long} fast path. */
	static final String LOGXOR = "_logxor";

	/** Bitwise complement ({@code lognot}) with a {@code long} fast path. */
	static final String LOGNOT = "_lognot";

	/** Arithmetic shift ({@code ash}) with a {@code long} fast path. */
	static final String ASH = "_ash";

	/** {@code integer-length} with a {@code long} fast path. */
	static final String INTEGER_LENGTH = "_intlen";

	/** {@code logbitp} with a {@code long} fast path; answers an {@code int} 0/1. */
	static final String LOGBITP = "_lbitp";

	private static final String OBJ = "Ljava/lang/Object;";

	private static final String BIG = "Ljava/math/BigInteger;";

	private static final String UNARY_DESC = "(" + OBJ + ")" + OBJ;

	private static final String BINARY_DESC = "(" + OBJ + OBJ + ")" + OBJ;

	/**
	 * The helpers a wrong-type operand can escape from, with their descriptors: a call to
	 * one compiled inside a named operator's form goes through that operator's wrapper
	 * ({@link JvmOperandTypeRuntime.Wrappers}). The rest -- {@code _eqv}, {@code _norm},
	 * the raw-double {@code _fmod} family -- cannot see a non-number.
	 */
	private static final Map<String, String> WRAPPED_DESCS = Map.ofEntries(Map.entry(ADD, BINARY_DESC),
			Map.entry(SUB, BINARY_DESC), Map.entry(MUL, BINARY_DESC), Map.entry(DIV, BINARY_DESC),
			Map.entry(MOD, BINARY_DESC), Map.entry(REM, BINARY_DESC), Map.entry(MIN, BINARY_DESC),
			Map.entry(MAX, BINARY_DESC), Map.entry(POW, BINARY_DESC), Map.entry(LOGAND, BINARY_DESC),
			Map.entry(LOGIOR, BINARY_DESC), Map.entry(LOGXOR, BINARY_DESC), Map.entry(ASH, BINARY_DESC),
			Map.entry(NEG, UNARY_DESC), Map.entry(ABS, UNARY_DESC), Map.entry(SIGNUM, UNARY_DESC),
			Map.entry(DBL, UNARY_DESC), Map.entry(RATIONAL, UNARY_DESC), Map.entry(LOGNOT, UNARY_DESC),
			Map.entry(INTEGER_LENGTH, UNARY_DESC), Map.entry(CMP, "(" + OBJ + OBJ + ")I"),
			Map.entry(CMPB, "(" + OBJ + OBJ + ")I"), Map.entry(LOGBITP, "(" + OBJ + OBJ + ")I"),
			Map.entry(BIG_OP, "(" + OBJ + ")" + BIG), Map.entry(RAT_NUM, "(" + OBJ + ")" + BIG),
			Map.entry(RAT_DEN, "(" + OBJ + ")" + BIG), Map.entry(FDIV, "(" + OBJ + OBJ + "I)" + OBJ),
			Map.entry(RANDOM, UNARY_DESC), Map.entry(JvmOperandTypeRuntime.CK_IDX, JvmOperandTypeRuntime.CK_IDX_DESC),
			Map.entry(JvmOperandTypeRuntime.CK_RAT, JvmOperandTypeRuntime.CK_RAT_DESC),
			Map.entry(JvmOperandTypeRuntime.CK_TAB, JvmOperandTypeRuntime.CK_IDX_DESC),
			Map.entry(JvmOperandTypeRuntime.CK_CHR, JvmOperandTypeRuntime.CK_IDX_DESC),
			Map.entry(JvmOperandTypeRuntime.CK_LIST, JvmOperandTypeRuntime.FIELD_DESC),
			Map.entry(JvmOperandTypeRuntime.CK_CONS, JvmOperandTypeRuntime.CK_CONS_DESC));

	/**
	 * The descriptor of a helper a wrong-type operand can escape from, or null.
	 * @param key the helper's key
	 * @return its descriptor, or null when it needs no operator wrapper
	 */
	static @Nullable String wrappedDesc(String key) {
		return WRAPPED_DESCS.get(key);
	}

	private JvmNumericRuntimeBuilder() {
	}

	/**
	 * A generated numeric helper method.
	 *
	 * @param nameUtf8 the method name constant
	 * @param descUtf8 the method descriptor constant
	 * @param code the body, with any exception catch it needs
	 */
	record NumericMethod(Utf8Constant nameUtf8, Utf8Constant descUtf8, MethodCode code) {
	}

	/**
	 * The generated numeric runtime: the helper methods to emit and the references that
	 * compiled code invokes.
	 *
	 * @param methods the helper methods to emit into the class
	 * @param ops the invokable helper references, keyed by operation
	 */
	record NumericRuntime(List<NumericMethod> methods, Map<String, MethodrefConstant> ops) {
	}

	/**
	 * The constant-pool references the non-number landing needs: the exception class and
	 * constructor (the non-finite texts build their own), and the funnels' shared thrower
	 * ({@link JvmOperandTypeRuntime}).
	 *
	 * @param rte {@code java/lang/RuntimeException}
	 * @param rteInit its {@code (String)} constructor
	 * @param throwRefs {@code _teRaw} and the kind names
	 */
	record TypeErrRefs(ClassConstant rte, MethodrefConstant rteInit, JvmOperandTypeRuntime.ThrowRefs throwRefs) {
	}

	/**
	 * Emits {@code throw _teRaw(local0, kind)}. Peak operand stack: 2.
	 * @param c the bytecode sink
	 * @param refs the shared references
	 * @param numberContext whether the funnel wanted a number (an integer otherwise)
	 */
	private static void emitTypeErrThrow(MethodCode c, TypeErrRefs refs, boolean numberContext) {
		JvmOperandTypeRuntime.ThrowRefs t = refs.throwRefs();
		t.emitThrow(c, 0, numberContext ? t.numberKind() : t.integerKind());
	}

	// The real-context twin of emitTypeErrThrow: a complex reaching a real-only
	// funnel (catchable as a type-error, like _ccmpb's).
	private static void emitRealErrThrow(MethodCode c, TypeErrRefs refs) {
		refs.throwRefs().emitThrow(c, 0, refs.throwRefs().realKind());
	}

	/**
	 * Builds all numeric helper methods and registers their constant-pool entries.
	 * @param cp the constant pool to populate
	 * @param thisClass the generated class
	 * @param strvMethod the {@code _strv} character-vector normalizer emitted with the
	 * array runtime helpers, or null when the program uses no arrays; when present,
	 * {@code _equal}'s string arm normalizes both operands through it so a mutable
	 * character vector is {@code equal} to the string with the same content
	 * @return the helper methods and the invokable references compiled code calls
	 */
	static NumericRuntime build(ConstantPool cp, ClassConstant thisClass,
			@org.jspecify.annotations.Nullable MethodrefConstant strvMethod,
			@org.jspecify.annotations.Nullable ClassConstant strArrClass, boolean usesComplex) {
		ClassConstant longClass = cp.addClass(cp.addUtf8("java/lang/Long"));
		ClassConstant bigClass = cp.addClass(cp.addUtf8("java/math/BigInteger"));
		ClassConstant arithEx = cp.addClass(cp.addUtf8("java/lang/ArithmeticException"));
		ClassConstant mathClass = cp.addClass(cp.addUtf8("java/lang/Math"));
		ClassConstant objectClass = cp.addClass(cp.addUtf8("java/lang/Object"));
		ClassConstant numberClass = cp.addClass(cp.addUtf8("java/lang/Number"));
		ClassConstant doubleClass = cp.addClass(cp.addUtf8("java/lang/Double"));
		ClassConstant ratArrClass = cp.addClass(cp.addUtf8("[Ljava/math/BigInteger;"));
		ClassConstant intArrClass = cp.addClass(cp.addUtf8("[I"));
		ClassConstant objArrClass = cp.addClass(cp.addUtf8("[Ljava/lang/Object;"));
		ClassConstant integerClass = cp.addClass(cp.addUtf8("java/lang/Integer"));
		ClassConstant bigDecClass = cp.addClass(cp.addUtf8("java/math/BigDecimal"));

		MethodrefConstant longValueOf = cp.addMethodref(longClass,
				cp.addNameAndType(cp.addUtf8("valueOf"), cp.addUtf8("(J)Ljava/lang/Long;")));
		MethodrefConstant longValue = cp.addMethodref(longClass,
				cp.addNameAndType(cp.addUtf8("longValue"), cp.addUtf8("()J")));

		MethodrefConstant addExact = cp.addMethodref(mathClass,
				cp.addNameAndType(cp.addUtf8("addExact"), cp.addUtf8("(JJ)J")));
		MethodrefConstant subExact = cp.addMethodref(mathClass,
				cp.addNameAndType(cp.addUtf8("subtractExact"), cp.addUtf8("(JJ)J")));
		MethodrefConstant mulExact = cp.addMethodref(mathClass,
				cp.addNameAndType(cp.addUtf8("multiplyExact"), cp.addUtf8("(JJ)J")));
		MethodrefConstant negExact = cp.addMethodref(mathClass,
				cp.addNameAndType(cp.addUtf8("negateExact"), cp.addUtf8("(J)J")));
		MethodrefConstant absLong = cp.addMethodref(mathClass,
				cp.addNameAndType(cp.addUtf8("abs"), cp.addUtf8("(J)J")));
		MethodrefConstant absDouble = cp.addMethodref(mathClass,
				cp.addNameAndType(cp.addUtf8("abs"), cp.addUtf8("(D)D")));
		MethodrefConstant signumDouble = cp.addMethodref(mathClass,
				cp.addNameAndType(cp.addUtf8("signum"), cp.addUtf8("(D)D")));
		MethodrefConstant intSignum = cp.addMethodref(integerClass,
				cp.addNameAndType(cp.addUtf8("signum"), cp.addUtf8("(I)I")));
		ClassConstant tlrClass = cp.addClass(cp.addUtf8("java/util/concurrent/ThreadLocalRandom"));
		MethodrefConstant tlrCurrent = cp.addMethodref(tlrClass,
				cp.addNameAndType(cp.addUtf8("current"), cp.addUtf8("()Ljava/util/concurrent/ThreadLocalRandom;")));
		MethodrefConstant tlrNextDouble = cp.addMethodref(tlrClass,
				cp.addNameAndType(cp.addUtf8("nextDouble"), cp.addUtf8("()D")));
		MethodrefConstant floorModLong = cp.addMethodref(mathClass,
				cp.addNameAndType(cp.addUtf8("floorMod"), cp.addUtf8("(JJ)J")));

		MethodrefConstant biValueOf = cp.addMethodref(bigClass,
				cp.addNameAndType(cp.addUtf8("valueOf"), cp.addUtf8("(J)" + BIG)));
		MethodrefConstant biAdd = cp.addMethodref(bigClass,
				cp.addNameAndType(cp.addUtf8("add"), cp.addUtf8("(" + BIG + ")" + BIG)));
		MethodrefConstant biSub = cp.addMethodref(bigClass,
				cp.addNameAndType(cp.addUtf8("subtract"), cp.addUtf8("(" + BIG + ")" + BIG)));
		MethodrefConstant biMul = cp.addMethodref(bigClass,
				cp.addNameAndType(cp.addUtf8("multiply"), cp.addUtf8("(" + BIG + ")" + BIG)));
		MethodrefConstant biDiv = cp.addMethodref(bigClass,
				cp.addNameAndType(cp.addUtf8("divide"), cp.addUtf8("(" + BIG + ")" + BIG)));
		MethodrefConstant biRem = cp.addMethodref(bigClass,
				cp.addNameAndType(cp.addUtf8("remainder"), cp.addUtf8("(" + BIG + ")" + BIG)));
		MethodrefConstant biNeg = cp.addMethodref(bigClass,
				cp.addNameAndType(cp.addUtf8("negate"), cp.addUtf8("()" + BIG)));
		MethodrefConstant biAbs = cp.addMethodref(bigClass,
				cp.addNameAndType(cp.addUtf8("abs"), cp.addUtf8("()" + BIG)));
		MethodrefConstant biBitLength = cp.addMethodref(bigClass,
				cp.addNameAndType(cp.addUtf8("bitLength"), cp.addUtf8("()I")));
		MethodrefConstant biLongValue = cp.addMethodref(bigClass,
				cp.addNameAndType(cp.addUtf8("longValue"), cp.addUtf8("()J")));
		MethodrefConstant biCompareTo = cp.addMethodref(bigClass,
				cp.addNameAndType(cp.addUtf8("compareTo"), cp.addUtf8("(" + BIG + ")I")));
		MethodrefConstant biGcd = cp.addMethodref(bigClass,
				cp.addNameAndType(cp.addUtf8("gcd"), cp.addUtf8("(" + BIG + ")" + BIG)));
		MethodrefConstant biMod = cp.addMethodref(bigClass,
				cp.addNameAndType(cp.addUtf8("mod"), cp.addUtf8("(" + BIG + ")" + BIG)));
		MethodrefConstant biSignum = cp.addMethodref(bigClass,
				cp.addNameAndType(cp.addUtf8("signum"), cp.addUtf8("()I")));
		MethodrefConstant biPow = cp.addMethodref(bigClass,
				cp.addNameAndType(cp.addUtf8("pow"), cp.addUtf8("(I)" + BIG)));
		// StrictMath, like every transcendental on every backend
		// (.kb/transcendentals.md).
		MethodrefConstant mathPow = cp.addMethodref(cp.addClass(cp.addUtf8("java/lang/StrictMath")),
				cp.addNameAndType(cp.addUtf8("pow"), cp.addUtf8("(DD)D")));
		// The complex arms' shared references, created only when the program may
		// observe a complex: merely creating them would put the travelling holder
		// in every constant pool, and every arm that tests for it would resolve
		// the class the first time it runs (`.kb/jvm-complex.md`). The throw-only
		// helpers (_ccmpb, _cphase) live in the gated group instead, for the same
		// reason.
		ClassConstant rcClass = usesComplex ? cp.addClass(cp.addUtf8("am/ik/rontolisp/runtime/RontoComplex")) : null;
		FieldrefConstant rcReal = usesComplex ? cp.addFieldref(Objects.requireNonNull(rcClass),
				cp.addNameAndType(cp.addUtf8("real"), cp.addUtf8(OBJ))) : null;
		FieldrefConstant rcImag = usesComplex ? cp.addFieldref(Objects.requireNonNull(rcClass),
				cp.addNameAndType(cp.addUtf8("imag"), cp.addUtf8(OBJ))) : null;
		MethodrefConstant mathHypot = usesComplex
				? cp.addMethodref(mathClass, cp.addNameAndType(cp.addUtf8("hypot"), cp.addUtf8("(DD)D"))) : null;
		// The gated _csignum reference for _signum's holder arm: a self-methodref the
		// complex group emits beside it (JvmComplexRuntimeBuilder.SIGNUM), created
		// only with the gate on so no complex-free constant pool names it.
		MethodrefConstant rCsignum = usesComplex
				? cp.addMethodref(thisClass, cp.addNameAndType(cp.addUtf8(JvmComplexRuntimeBuilder.SIGNUM),
						cp.addUtf8(JvmComplexRuntimeBuilder.descFor(JvmComplexRuntimeBuilder.SIGNUM))))
				: null;
		// The holder-presence probe (.todo/757, minted in JvmLispCompiler): every
		// holder arm below consults it before resolving the travelling class, so a
		// lone class run without the file beside it takes the holder-less shape.
		// Null exactly when the gate is off, like rcClass.
		FieldrefConstant hasComplex = usesComplex
				? cp.addFieldref(thisClass, cp.addNameAndType(cp.addUtf8("_hasComplex"), cp.addUtf8("Z"))) : null;
		MethodrefConstant biShiftLeft = cp.addMethodref(bigClass,
				cp.addNameAndType(cp.addUtf8("shiftLeft"), cp.addUtf8("(I)" + BIG)));
		MethodrefConstant biTestBit = cp.addMethodref(bigClass,
				cp.addNameAndType(cp.addUtf8("testBit"), cp.addUtf8("(I)Z")));
		MethodrefConstant biAnd = cp.addMethodref(bigClass,
				cp.addNameAndType(cp.addUtf8("and"), cp.addUtf8("(" + BIG + ")" + BIG)));
		MethodrefConstant biOr = cp.addMethodref(bigClass,
				cp.addNameAndType(cp.addUtf8("or"), cp.addUtf8("(" + BIG + ")" + BIG)));
		MethodrefConstant biXor = cp.addMethodref(bigClass,
				cp.addNameAndType(cp.addUtf8("xor"), cp.addUtf8("(" + BIG + ")" + BIG)));
		MethodrefConstant biNot = cp.addMethodref(bigClass,
				cp.addNameAndType(cp.addUtf8("not"), cp.addUtf8("()" + BIG)));
		MethodrefConstant longNlz = cp.addMethodref(longClass,
				cp.addNameAndType(cp.addUtf8("numberOfLeadingZeros"), cp.addUtf8("(J)I")));
		FieldrefConstant biOne = cp.addFieldref(bigClass, cp.addNameAndType(cp.addUtf8("ONE"), cp.addUtf8(BIG)));

		MethodrefConstant objEquals = cp.addMethodref(objectClass,
				cp.addNameAndType(cp.addUtf8("equals"), cp.addUtf8("(" + OBJ + ")Z")));
		MethodrefConstant aeInit = cp.addMethodref(arithEx,
				cp.addNameAndType(cp.addUtf8("<init>"), cp.addUtf8("(Ljava/lang/String;)V")));
		ConstantPool.StringConstant divZeroStr = cp.addString("Division by zero");
		ConstantPool.StringConstant ashTooLargeStr = cp.addString("ash: shift count too large");
		ConstantPool.StringConstant rationalNonFiniteStr = cp.addString("rational of a non-finite float is undefined");
		ConstantPool.StringConstant roundingNonFiniteStr = cp
			.addString(am.ik.rontolisp.ClosRegistry.NON_FINITE_ROUNDING_MESSAGE);

		// The non-number landing (_big / _dbl / _abs's BigInteger arm): a plain
		// RuntimeException carrying "The value <prin1> is not of type INTEGER|NUMBER" --
		// the
		// interpreter's exact text, rendered through the unconditional _lispToString.
		// A checkcast cannot be the check here: null PASSES a checkcast and the failure
		// then surfaces later as a Java NPE naming BigInteger internals. The landing-pad
		// classification of the prefix as a type-error is JvmHandlerCaseCompiler's.
		ClassConstant rteClass = cp.addClass(cp.addUtf8("java/lang/RuntimeException"));
		ClassConstant stringClass = cp.addClass(cp.addUtf8("java/lang/String"));
		TypeErrRefs typeErrRefs = new TypeErrRefs(rteClass,
				cp.addMethodref(rteClass, cp.addNameAndType(cp.addUtf8("<init>"), cp.addUtf8("(Ljava/lang/String;)V"))),
				JvmOperandTypeRuntime.ThrowRefs.of(cp, thisClass));

		MethodrefConstant bdInitDouble = cp.addMethodref(bigDecClass,
				cp.addNameAndType(cp.addUtf8("<init>"), cp.addUtf8("(D)V")));
		MethodrefConstant bdUnscaled = cp.addMethodref(bigDecClass,
				cp.addNameAndType(cp.addUtf8("unscaledValue"), cp.addUtf8("()" + BIG)));
		MethodrefConstant bdScale = cp.addMethodref(bigDecClass,
				cp.addNameAndType(cp.addUtf8("scale"), cp.addUtf8("()I")));
		FieldrefConstant biTen = cp.addFieldref(bigClass, cp.addNameAndType(cp.addUtf8("TEN"), cp.addUtf8(BIG)));
		MethodrefConstant dblIsFinite = cp.addMethodref(doubleClass,
				cp.addNameAndType(cp.addUtf8("isFinite"), cp.addUtf8("(D)Z")));
		MethodrefConstant dblIsInfinite = cp.addMethodref(doubleClass,
				cp.addNameAndType(cp.addUtf8("isInfinite"), cp.addUtf8("(D)Z")));
		MethodrefConstant doubleValueOf = cp.addMethodref(doubleClass,
				cp.addNameAndType(cp.addUtf8("valueOf"), cp.addUtf8("(D)Ljava/lang/Double;")));
		MethodrefConstant dblLongBits = cp.addMethodref(doubleClass,
				cp.addNameAndType(cp.addUtf8("longBitsToDouble"), cp.addUtf8("(J)D")));
		FieldrefConstant dblNegInf = cp.addFieldref(doubleClass,
				cp.addNameAndType(cp.addUtf8("NEGATIVE_INFINITY"), cp.addUtf8("D")));
		FieldrefConstant dblPosInf = cp.addFieldref(doubleClass,
				cp.addNameAndType(cp.addUtf8("POSITIVE_INFINITY"), cp.addUtf8("D")));
		MethodrefConstant numDoubleValue = cp.addMethodref(numberClass,
				cp.addNameAndType(cp.addUtf8("doubleValue"), cp.addUtf8("()D")));

		LongConstant cMin = cp.addLong(Long.MIN_VALUE);
		LongConstant cRat3 = cp.addLong(3L);
		LongConstant cRat4 = cp.addLong(4L);
		LongConstant cRat2p53 = cp.addLong(1L << 53);
		LongConstant cRatFracMask = cp.addLong(0xF_FFFF_FFFF_FFFFL);

		// Self method references (name+descriptor against the generated class).
		Utf8Constant nBig = cp.addUtf8(BIG_OP);
		Utf8Constant dBig = cp.addUtf8("(" + OBJ + ")" + BIG);
		MethodrefConstant rBig = cp.addMethodref(thisClass, cp.addNameAndType(nBig, dBig));
		Utf8Constant nNorm = cp.addUtf8(NORM_OP);
		Utf8Constant dNorm = cp.addUtf8("(" + BIG + ")" + OBJ);
		MethodrefConstant rNorm = cp.addMethodref(thisClass, cp.addNameAndType(nNorm, dNorm));
		Utf8Constant nRatNum = cp.addUtf8(RAT_NUM);
		MethodrefConstant rRatNum = cp.addMethodref(thisClass, cp.addNameAndType(nRatNum, dBig));
		Utf8Constant nRatDen = cp.addUtf8(RAT_DEN);
		MethodrefConstant rRatDen = cp.addMethodref(thisClass, cp.addNameAndType(nRatDen, dBig));
		Utf8Constant nRat = cp.addUtf8(RAT);
		Utf8Constant dRat = cp.addUtf8("(" + BIG + BIG + ")" + OBJ);
		MethodrefConstant rRat = cp.addMethodref(thisClass, cp.addNameAndType(nRat, dRat));

		Utf8Constant nAdd = cp.addUtf8(ADD);
		Utf8Constant nSub = cp.addUtf8(SUB);
		Utf8Constant nMul = cp.addUtf8(MUL);
		Utf8Constant nNeg = cp.addUtf8(NEG);
		Utf8Constant nDiv = cp.addUtf8(DIV);
		Utf8Constant nMod = cp.addUtf8(MOD);
		Utf8Constant nRem = cp.addUtf8(REM);
		Utf8Constant nFmod = cp.addUtf8(FMOD);
		Utf8Constant nFrem = cp.addUtf8(FREM);
		Utf8Constant nCmp = cp.addUtf8(CMP);
		Utf8Constant nCmpb = cp.addUtf8(CMPB);
		Utf8Constant nAbs = cp.addUtf8(ABS);
		Utf8Constant nSignum = cp.addUtf8(SIGNUM);
		Utf8Constant nRandom = cp.addUtf8(RANDOM);
		Utf8Constant nMin = cp.addUtf8(MIN);
		Utf8Constant nMax = cp.addUtf8(MAX);
		Utf8Constant nFmin = cp.addUtf8(FMIN);
		Utf8Constant nFmax = cp.addUtf8(FMAX);
		Utf8Constant nDbl = cp.addUtf8(DBL);
		Utf8Constant nPow = cp.addUtf8(POW);
		Utf8Constant nEqv = cp.addUtf8(EQV);
		Utf8Constant nEqual = cp.addUtf8(EQUAL);
		Utf8Constant nRatTrunc = cp.addUtf8(RAT_TRUNC);
		Utf8Constant nRatFloor = cp.addUtf8(RAT_FLOOR);
		Utf8Constant nRatCeil = cp.addUtf8(RAT_CEIL);
		Utf8Constant nRatRound = cp.addUtf8(RAT_ROUND);
		Utf8Constant nFdiv = cp.addUtf8(FDIV);
		Utf8Constant nFrat = cp.addUtf8(FRAT);
		Utf8Constant nRational = cp.addUtf8(RATIONAL);
		Utf8Constant nLogand = cp.addUtf8(LOGAND);
		Utf8Constant nLogior = cp.addUtf8(LOGIOR);
		Utf8Constant nLogxor = cp.addUtf8(LOGXOR);
		Utf8Constant nLognot = cp.addUtf8(LOGNOT);
		Utf8Constant nAsh = cp.addUtf8(ASH);
		Utf8Constant nIntLen = cp.addUtf8(INTEGER_LENGTH);
		Utf8Constant nLogbitp = cp.addUtf8(LOGBITP);
		Utf8Constant nFixDec = cp.addUtf8(FIXED_DEC);
		Utf8Constant dFixDec = cp.addUtf8("(" + OBJ + OBJ + OBJ + OBJ + ")Ljava/lang/String;");
		Utf8Constant dBinary = cp.addUtf8(BINARY_DESC);
		Utf8Constant dUnary = cp.addUtf8(UNARY_DESC);
		Utf8Constant dCmp = cp.addUtf8("(" + OBJ + OBJ + ")I");
		Utf8Constant dFmod = cp.addUtf8("(DD)D");

		MethodrefConstant rAdd = cp.addMethodref(thisClass, cp.addNameAndType(nAdd, dBinary));
		MethodrefConstant rSub = cp.addMethodref(thisClass, cp.addNameAndType(nSub, dBinary));
		MethodrefConstant rMul = cp.addMethodref(thisClass, cp.addNameAndType(nMul, dBinary));
		MethodrefConstant rNeg = cp.addMethodref(thisClass, cp.addNameAndType(nNeg, dUnary));
		MethodrefConstant rDiv = cp.addMethodref(thisClass, cp.addNameAndType(nDiv, dBinary));
		MethodrefConstant rMod = cp.addMethodref(thisClass, cp.addNameAndType(nMod, dBinary));
		MethodrefConstant rRem = cp.addMethodref(thisClass, cp.addNameAndType(nRem, dBinary));
		MethodrefConstant rFmod = cp.addMethodref(thisClass, cp.addNameAndType(nFmod, dFmod));
		MethodrefConstant rFrem = cp.addMethodref(thisClass, cp.addNameAndType(nFrem, dFmod));
		MethodrefConstant rCmp = cp.addMethodref(thisClass, cp.addNameAndType(nCmp, dCmp));
		MethodrefConstant rCmpb = cp.addMethodref(thisClass, cp.addNameAndType(nCmpb, dCmp));
		MethodrefConstant rAbs = cp.addMethodref(thisClass, cp.addNameAndType(nAbs, dUnary));
		MethodrefConstant rSignum = cp.addMethodref(thisClass, cp.addNameAndType(nSignum, dUnary));
		MethodrefConstant rRandom = cp.addMethodref(thisClass, cp.addNameAndType(nRandom, dUnary));
		MethodrefConstant rMin = cp.addMethodref(thisClass, cp.addNameAndType(nMin, dBinary));
		MethodrefConstant rMax = cp.addMethodref(thisClass, cp.addNameAndType(nMax, dBinary));
		MethodrefConstant rFmin = cp.addMethodref(thisClass, cp.addNameAndType(nFmin, dFmod));
		MethodrefConstant rFmax = cp.addMethodref(thisClass, cp.addNameAndType(nFmax, dFmod));
		MethodrefConstant rDbl = cp.addMethodref(thisClass, cp.addNameAndType(nDbl, dUnary));
		Utf8Constant nRatToDouble = cp.addUtf8(RAT_TO_DOUBLE);
		Utf8Constant dRatToDouble = cp.addUtf8("(" + BIG + BIG + ")D");
		MethodrefConstant rRatToDouble = cp.addMethodref(thisClass, cp.addNameAndType(nRatToDouble, dRatToDouble));
		MethodrefConstant rPow = cp.addMethodref(thisClass, cp.addNameAndType(nPow, dBinary));
		MethodrefConstant rEqv = cp.addMethodref(thisClass, cp.addNameAndType(nEqv, dCmp));
		MethodrefConstant rEqual = cp.addMethodref(thisClass, cp.addNameAndType(nEqual, dCmp));
		MethodrefConstant rRatTrunc = cp.addMethodref(thisClass, cp.addNameAndType(nRatTrunc, dUnary));
		MethodrefConstant rRatFloor = cp.addMethodref(thisClass, cp.addNameAndType(nRatFloor, dUnary));
		MethodrefConstant rRatCeil = cp.addMethodref(thisClass, cp.addNameAndType(nRatCeil, dUnary));
		MethodrefConstant rRatRound = cp.addMethodref(thisClass, cp.addNameAndType(nRatRound, dUnary));
		Utf8Constant dFdiv = cp.addUtf8("(" + OBJ + OBJ + "I)" + OBJ);
		MethodrefConstant rFdiv = cp.addMethodref(thisClass, cp.addNameAndType(nFdiv, dFdiv));
		MethodrefConstant rFrat = cp.addMethodref(thisClass, cp.addNameAndType(nFrat, dUnary));
		MethodrefConstant rRational = cp.addMethodref(thisClass, cp.addNameAndType(nRational, dUnary));
		MethodrefConstant rLogand = cp.addMethodref(thisClass, cp.addNameAndType(nLogand, dBinary));
		MethodrefConstant rLogior = cp.addMethodref(thisClass, cp.addNameAndType(nLogior, dBinary));
		MethodrefConstant rLogxor = cp.addMethodref(thisClass, cp.addNameAndType(nLogxor, dBinary));
		MethodrefConstant rLognot = cp.addMethodref(thisClass, cp.addNameAndType(nLognot, dUnary));
		MethodrefConstant rAsh = cp.addMethodref(thisClass, cp.addNameAndType(nAsh, dBinary));
		MethodrefConstant rIntLen = cp.addMethodref(thisClass, cp.addNameAndType(nIntLen, dUnary));
		MethodrefConstant rLogbitp = cp.addMethodref(thisClass, cp.addNameAndType(nLogbitp, dCmp));
		MethodrefConstant rFixDec = cp.addMethodref(thisClass, cp.addNameAndType(nFixDec, dFixDec));

		List<NumericMethod> methods = new ArrayList<>();
		methods.add(buildBig(nBig, dBig, longClass, bigClass, longValue, biValueOf, typeErrRefs));
		methods.add(buildNorm(nNorm, dNorm, longValueOf, biBitLength, biLongValue));
		methods.add(buildRatNum(nRatNum, dBig, ratArrClass, rBig));
		methods.add(buildRatDen(nRatDen, dBig, ratArrClass, biOne));
		methods.add(buildRat(nRat, dRat, bigClass, arithEx, aeInit, divZeroStr, biSignum, biNeg, biGcd, biDiv, biOne,
				objEquals, rNorm));
		methods.add(buildExactBinary(nAdd, dBinary, longClass, addExact, longValue, longValueOf, rBig, rNorm, biAdd,
				arithEx, ratArrClass, rRatNum, rRatDen, rRat, biMul, biAdd, doubleClass, rDbl, numberClass,
				numDoubleValue, doubleValueOf, MethodCode::dadd));
		methods.add(buildExactBinary(nSub, dBinary, longClass, subExact, longValue, longValueOf, rBig, rNorm, biSub,
				arithEx, ratArrClass, rRatNum, rRatDen, rRat, biMul, biSub, doubleClass, rDbl, numberClass,
				numDoubleValue, doubleValueOf, MethodCode::dsub));
		methods.add(buildExactBinary(nMul, dBinary, longClass, mulExact, longValue, longValueOf, rBig, rNorm, biMul,
				arithEx, ratArrClass, rRatNum, rRatDen, rRat, biMul, null, doubleClass, rDbl, numberClass,
				numDoubleValue, doubleValueOf, MethodCode::dmul));
		methods.add(buildNeg(nNeg, dUnary, longClass, negExact, longValue, longValueOf, rBig, rNorm, biNeg, arithEx,
				ratArrClass, rRatNum, rRatDen, rRat, doubleClass, rDbl, numberClass, numDoubleValue, doubleValueOf));
		methods.add(buildDiv(nDiv, dBinary, rRatNum, rRatDen, rRat, biMul, doubleClass, rDbl, numberClass,
				numDoubleValue, doubleValueOf));
		methods.add(buildMod(nMod, dBinary, longClass, longValue, longValueOf, rBig, rNorm, biRem, floorModLong,
				biSignum, biAdd, doubleClass, rDbl, numberClass, numDoubleValue, doubleValueOf, rFmod, ratArrClass,
				rRatNum, rRatDen, rRat, biMul));
		methods.add(buildRem(nRem, dBinary, longClass, longValue, longValueOf, rBig, rNorm, biRem, doubleClass, rDbl,
				numberClass, numDoubleValue, doubleValueOf, rFrem, ratArrClass, rRatNum, rRatDen, rRat, biMul));
		methods.add(buildFmod(nFmod, dFmod, rFrem));
		methods.add(buildFrem(nFrem, dFmod));
		methods.add(buildCmp(nCmp, dCmp, longClass, longValue, rBig, biCompareTo, ratArrClass, rRatNum, rRatDen, biMul,
				doubleClass, rDbl, numberClass, numDoubleValue, bigClass, rFrat, intSignum, typeErrRefs));
		methods.add(buildCmpBits(nCmpb, dCmp, doubleClass, rDbl, numberClass, numDoubleValue, rCmp, intSignum, rcClass,
				rcReal, rcImag, longValueOf, hasComplex, rFrat, ratArrClass, longClass, bigClass, rRatNum, rRatDen,
				biMul, biCompareTo, typeErrRefs));
		methods.add(buildAbs(nAbs, dUnary, longClass, bigClass, longValue, longValueOf, absLong, biValueOf, biNeg,
				biAbs, rNorm, cMin, ratArrClass, rRatNum, rRatDen, rRat, doubleClass, rDbl, numberClass, numDoubleValue,
				doubleValueOf, absDouble, rBig, rcClass, rcReal, rcImag, mathHypot, hasComplex));
		methods.add(buildSignum(nSignum, dUnary, doubleClass, rDbl, numberClass, numDoubleValue, doubleValueOf,
				signumDouble, rRatNum, biSignum, longValueOf, rcClass, rCsignum, hasComplex));
		methods.add(buildRandom(nRandom, dUnary, doubleClass, rDbl, numberClass, numDoubleValue, doubleValueOf,
				longValueOf, tlrCurrent, tlrNextDouble, ratArrClass, typeErrRefs));
		methods.add(buildSelect(nMin, dBinary, rCmpb, CMPB_LT | CMPB_EQ, rcClass, typeErrRefs, hasComplex));
		methods.add(buildSelect(nMax, dBinary, rCmpb, CMPB_GT | CMPB_EQ, rcClass, typeErrRefs, hasComplex));
		methods.add(buildFloatSelect(nFmin, dFmod, MethodCode::dcmpg, MethodCode::ifle));
		methods.add(buildFloatSelect(nFmax, dFmod, MethodCode::dcmpl, MethodCode::ifge));
		methods.add(buildDbl(nDbl, dUnary, ratArrClass, doubleClass, numberClass, doubleValueOf, numDoubleValue,
				rRatNum, rRatDen, rRatToDouble, typeErrRefs, rcClass, hasComplex));
		methods.add(buildRatToDouble(nRatToDouble, dRatToDouble, biSignum, biNeg, biBitLength, biShiftLeft, biCompareTo,
				biDiv, biRem, biLongValue, dblLongBits, dblNegInf, dblPosInf, cRat3, cRat4, cRat2p53, cRatFracMask));
		methods.add(buildPow(nPow, dBinary, rRatNum, rRatDen, rRat, biPow, doubleClass, longClass, longValue,
				numberClass, numDoubleValue, doubleValueOf, mathPow, rDbl, cp.addLong(Integer.MAX_VALUE),
				cp.addLong(-(long) Integer.MAX_VALUE)));
		ClassConstant listClass = cp.addClass(cp.addUtf8("java/util/List"));
		StringRefs stringRefs = new StringRefs(stringClass, listClass,
				cp.addMethodref(stringClass, cp.addNameAndType(cp.addUtf8("isEmpty"), cp.addUtf8("()Z"))),
				cp.addMethodref(stringClass, cp.addNameAndType(cp.addUtf8("charAt"), cp.addUtf8("(I)C"))));
		methods.add(buildEqv(nEqv, dCmp, ratArrClass, intArrClass, cp.addClass(cp.addUtf8("java/util/Map")), objEquals,
				stringRefs));
		methods.add(buildEqual(nEqual, dCmp, objArrClass, ratArrClass, integerClass, rEqv, rEqual, strArrClass,
				strvMethod, stringRefs, objEquals));
		methods.add(buildRatTrunc(nRatTrunc, dUnary, rRatNum, rRatDen, rNorm, biDiv));
		methods.add(buildRatFloor(nRatFloor, dUnary, rRatNum, rRatDen, rNorm, biMod, biSub, biDiv, null, null));
		methods.add(buildRatFloor(nRatCeil, dUnary, rRatNum, rRatDen, rNorm, biMod, biSub, biDiv, biOne, biAdd));
		methods.add(buildRatRound(nRatRound, dUnary, rRatNum, rRatDen, rNorm, biMod, biSub, biDiv, biMul, biShiftLeft,
				biCompareTo, biTestBit, biOne, biAdd));
		methods.add(buildFrat(nFrat, dUnary, doubleClass, longClass, bigClass, numberClass, numDoubleValue, dblIsFinite,
				bigDecClass, bdInitDouble, bdUnscaled, bdScale, biTen, biPow));
		methods.add(buildRational(nRational, dUnary, longClass, bigClass, doubleClass, ratArrClass, rFrat, rRat,
				typeErrRefs, rationalNonFiniteStr, rcClass, hasComplex));
		methods.add(buildFdiv(nFdiv, dFdiv, doubleClass, numberClass, numDoubleValue, ratArrClass, rFrat, rDiv,
				rRatTrunc, rRatFloor, rRatCeil, rRatRound, dblIsInfinite, dblIsFinite, longClass, longValue, bigClass,
				biSignum, longValueOf, typeErrRefs, roundingNonFiniteStr));
		methods
			.add(buildLogOp(nLogand, dBinary, longClass, longValue, longValueOf, rBig, rNorm, biAnd, MethodCode::land));
		methods
			.add(buildLogOp(nLogior, dBinary, longClass, longValue, longValueOf, rBig, rNorm, biOr, MethodCode::lor));
		methods
			.add(buildLogOp(nLogxor, dBinary, longClass, longValue, longValueOf, rBig, rNorm, biXor, MethodCode::lxor));
		methods.add(buildLogNot(nLognot, dUnary, longClass, longValue, longValueOf, rBig, rNorm, biNot));
		methods.add(buildAsh(nAsh, dBinary, longClass, longValue, longValueOf, rBig, rNorm, biShiftLeft, biSignum,
				arithEx, aeInit, ashTooLargeStr));
		methods.add(buildIntegerLength(nIntLen, dUnary, longClass, longValue, longValueOf, rBig, biBitLength, longNlz));
		methods.add(buildLogbitp(nLogbitp, dCmp, longClass, longValue, rBig, biTestBit));
		methods.add(buildFixedDec(cp, nFixDec, dFixDec, mathClass, longClass, numberClass, numDoubleValue, rDbl));

		Map<String, MethodrefConstant> ops = new LinkedHashMap<>();
		ops.put(ADD, rAdd);
		ops.put(SUB, rSub);
		ops.put(MUL, rMul);
		ops.put(NEG, rNeg);
		ops.put(DIV, rDiv);
		ops.put(MOD, rMod);
		ops.put(REM, rRem);
		ops.put(FMOD, rFmod);
		ops.put(FREM, rFrem);
		ops.put(CMP, rCmp);
		ops.put(CMPB, rCmpb);
		ops.put(ABS, rAbs);
		ops.put(SIGNUM, rSignum);
		ops.put(RANDOM, rRandom);
		ops.put(MIN, rMin);
		ops.put(MAX, rMax);
		ops.put(FMIN, rFmin);
		ops.put(FMAX, rFmax);
		ops.put(BIG_OP, rBig);
		ops.put(NORM_OP, rNorm);
		ops.put(RAT_NUM, rRatNum);
		ops.put(RAT_DEN, rRatDen);
		ops.put(RAT, rRat);
		ops.put(DBL, rDbl);
		ops.put(RAT_TO_DOUBLE, rRatToDouble);
		ops.put(POW, rPow);
		ops.put(EQV, rEqv);
		ops.put(EQUAL, rEqual);
		ops.put(RAT_TRUNC, rRatTrunc);
		ops.put(RAT_FLOOR, rRatFloor);
		ops.put(RAT_CEIL, rRatCeil);
		ops.put(RAT_ROUND, rRatRound);
		ops.put(FDIV, rFdiv);
		ops.put(FRAT, rFrat);
		ops.put(RATIONAL, rRational);
		ops.put(LOGAND, rLogand);
		ops.put(LOGIOR, rLogior);
		ops.put(LOGXOR, rLogxor);
		ops.put(LOGNOT, rLognot);
		ops.put(ASH, rAsh);
		ops.put(INTEGER_LENGTH, rIntLen);
		ops.put(LOGBITP, rLogbitp);
		ops.put(FIXED_DEC, rFixDec);
		return new NumericRuntime(methods, ops);
	}

	// _big(Object x): Long -> BigInteger.valueOf(x), otherwise (BigInteger) x.
	private static NumericMethod buildBig(Utf8Constant name, Utf8Constant desc, ClassConstant longClass,
			ClassConstant bigClass, MethodrefConstant longValue, MethodrefConstant biValueOf, TypeErrRefs typeErrRefs) {
		MethodCode c = new MethodCode();
		c.aload(0);
		c.instanceOf(longClass.entry());
		MethodCode.Label ifNotLong = c.newLabel();
		c.ifeq(ifNotLong);
		c.aload(0);
		c.checkcast(longClass.entry());
		c.invokevirtual(longValue.methodRefEntry());
		c.invokestatic(biValueOf.entry());
		c.areturn();
		c.labelBinding(ifNotLong);
		// Anything but a BigInteger throws the interpreter's INTEGER operand-type report
		// text: a
		// bare checkcast is not a check here (null passes it and fails later as a Java
		// NPE naming BigInteger internals, and a cast failure's own text names Java
		// classes). One instanceof on the widening (out-of-long) arm only.
		c.aload(0);
		c.instanceOf(bigClass.entry());
		MethodCode.Label ifNotBig = c.newLabel();
		c.ifeq(ifNotBig);
		c.aload(0);
		c.checkcast(bigClass.entry());
		c.areturn();
		c.labelBinding(ifNotBig);
		emitTypeErrThrow(c, typeErrRefs, false);
		return new NumericMethod(name, desc, c);
	}

	// _norm(BigInteger b): demote to Long when it fits in a long, else keep BigInteger.
	private static NumericMethod buildNorm(Utf8Constant name, Utf8Constant desc, MethodrefConstant longValueOf,
			MethodrefConstant biBitLength, MethodrefConstant biLongValue) {
		MethodCode c = new MethodCode();
		c.aload(0);
		c.invokevirtual(biBitLength.methodRefEntry());
		c.loadConstant(64);
		MethodCode.Label ifGe = c.newLabel();
		c.if_icmpge(ifGe);
		c.aload(0);
		c.invokevirtual(biLongValue.methodRefEntry());
		c.invokestatic(longValueOf.entry());
		c.areturn();
		c.labelBinding(ifGe);
		c.aload(0);
		c.areturn();
		return new NumericMethod(name, desc, c);
	}

	// _ratnum(Object x): ratio -> x[0], otherwise _big(x).
	private static NumericMethod buildRatNum(Utf8Constant name, Utf8Constant desc, ClassConstant ratArrClass,
			MethodrefConstant rBig) {
		MethodCode c = new MethodCode();
		c.aload(0);
		c.instanceOf(ratArrClass.entry());
		MethodCode.Label ifNotRat = c.newLabel();
		c.ifeq(ifNotRat);
		c.aload(0);
		c.checkcast(ratArrClass.entry());
		c.iconst_0();
		c.aaload();
		c.areturn();
		c.labelBinding(ifNotRat);
		c.aload(0);
		c.invokestatic(rBig.entry());
		c.areturn();
		return new NumericMethod(name, desc, c);
	}

	// _ratden(Object x): ratio -> x[1], otherwise BigInteger.ONE.
	private static NumericMethod buildRatDen(Utf8Constant name, Utf8Constant desc, ClassConstant ratArrClass,
			FieldrefConstant biOne) {
		MethodCode c = new MethodCode();
		c.aload(0);
		c.instanceOf(ratArrClass.entry());
		MethodCode.Label ifNotRat = c.newLabel();
		c.ifeq(ifNotRat);
		c.aload(0);
		c.checkcast(ratArrClass.entry());
		c.iconst_1();
		c.aaload();
		c.areturn();
		c.labelBinding(ifNotRat);
		c.getstatic(biOne.entry());
		c.areturn();
		return new NumericMethod(name, desc, c);
	}

	// _rat(BigInteger num, BigInteger den): builds a normalized rational value. Moves
	// the sign to the numerator, reduces by the gcd, and demotes a denominator-one
	// result to an integer via _norm. A zero denominator throws ArithmeticException.
	private static NumericMethod buildRat(Utf8Constant name, Utf8Constant desc, ClassConstant bigClass,
			ClassConstant arithEx, MethodrefConstant aeInit, ConstantPool.StringConstant divZeroStr,
			MethodrefConstant biSignum, MethodrefConstant biNeg, MethodrefConstant biGcd, MethodrefConstant biDiv,
			FieldrefConstant biOne, MethodrefConstant objEquals, MethodrefConstant rNorm) {
		MethodCode c = new MethodCode();
		// if (den.signum() == 0) throw new ArithmeticException("Division by zero");
		c.aload(1);
		c.invokevirtual(biSignum.methodRefEntry());
		MethodCode.Label ifNonZero = c.newLabel();
		c.ifne(ifNonZero);
		c.new_(arithEx.entry());
		c.dup();
		c.ldc(divZeroStr.entry());
		c.invokespecial(aeInit.entry());
		c.athrow();
		// if (den.signum() < 0) { num = num.negate(); den = den.negate(); }
		c.labelBinding(ifNonZero);
		c.aload(1);
		c.invokevirtual(biSignum.methodRefEntry());
		MethodCode.Label ifPositive = c.newLabel();
		c.ifge(ifPositive);
		c.aload(0);
		c.invokevirtual(biNeg.methodRefEntry());
		c.astore(0);
		c.aload(1);
		c.invokevirtual(biNeg.methodRefEntry());
		c.astore(1);
		// BigInteger g = num.gcd(den); num = num.divide(g); den = den.divide(g);
		c.labelBinding(ifPositive);
		c.aload(0);
		c.aload(1);
		c.invokevirtual(biGcd.methodRefEntry());
		c.astore(2);
		c.aload(0);
		c.aload(2);
		c.invokevirtual(biDiv.methodRefEntry());
		c.astore(0);
		c.aload(1);
		c.aload(2);
		c.invokevirtual(biDiv.methodRefEntry());
		c.astore(1);
		// if (den.equals(BigInteger.ONE)) return _norm(num);
		c.aload(1);
		c.getstatic(biOne.entry());
		c.invokevirtual(objEquals.methodRefEntry());
		MethodCode.Label ifNotOne = c.newLabel();
		c.ifeq(ifNotOne);
		c.aload(0);
		c.invokestatic(rNorm.entry());
		c.areturn();
		// return new BigInteger[] { num, den };
		c.labelBinding(ifNotOne);
		c.iconst_2();
		c.anewarray(bigClass.entry());
		c.dup();
		c.iconst_0();
		c.aload(0);
		c.aastore();
		c.dup();
		c.iconst_1();
		c.aload(1);
		c.aastore();
		c.areturn();
		return new NumericMethod(name, desc, c);
	}

	// _add/_sub/_mul(Object a, Object b): rational path when either operand is a ratio;
	// otherwise long fast path via Math.*Exact, promoting to BigInteger on overflow
	// (caught) or when an operand is already a BigInteger.
	private static NumericMethod buildExactBinary(Utf8Constant name, Utf8Constant desc, ClassConstant longClass,
			MethodrefConstant exact, MethodrefConstant longValue, MethodrefConstant longValueOf, MethodrefConstant rBig,
			MethodrefConstant rNorm, MethodrefConstant biOp, ClassConstant arithEx, ClassConstant ratArrClass,
			MethodrefConstant rRatNum, MethodrefConstant rRatDen, MethodrefConstant rRat, MethodrefConstant biMul,
			@Nullable MethodrefConstant ratioCross, ClassConstant doubleClass, MethodrefConstant rDbl,
			ClassConstant numberClass, MethodrefConstant numDoubleValue, MethodrefConstant doubleValueOf,
			Consumer<MethodCode> doubleOp) {
		MethodCode c = new MethodCode();
		emitDoubleBinaryPrologue(c, doubleClass, rDbl, numberClass, numDoubleValue, doubleValueOf, doubleOp);
		MethodCode.Label toRatio = c.newLabel();
		emitRatioGuard(c, ratArrClass, toRatio);
		c.aload(0);
		c.instanceOf(longClass.entry());
		MethodCode.Label ifSlow1 = c.newLabel();
		c.ifeq(ifSlow1);
		c.aload(1);
		c.instanceOf(longClass.entry());
		MethodCode.Label ifSlow2 = c.newLabel();
		c.ifeq(ifSlow2);
		MethodCode.Label tryStart = c.newBoundLabel();
		emitUnboxLong(c, 0, longClass, longValue);
		emitUnboxLong(c, 1, longClass, longValue);
		c.invokestatic(exact.entry());
		c.invokestatic(longValueOf.entry());
		c.areturn();
		MethodCode.Label handler = c.newBoundLabel();
		c.pop();
		c.labelBinding(ifSlow1);
		c.labelBinding(ifSlow2);
		emitBigBinary(c, rBig, biOp, rNorm);
		c.labelBinding(toRatio);
		emitRatioBinary(c, rRatNum, rRatDen, rRat, biMul, ratioCross);
		c.exceptionCatch(tryStart, handler, handler, arithEx.entry());
		return new NumericMethod(name, desc, c);
	}

	// _neg(Object a): negate via Math.negateExact, promoting to BigInteger on overflow;
	// a ratio negates its numerator.
	private static NumericMethod buildNeg(Utf8Constant name, Utf8Constant desc, ClassConstant longClass,
			MethodrefConstant negExact, MethodrefConstant longValue, MethodrefConstant longValueOf,
			MethodrefConstant rBig, MethodrefConstant rNorm, MethodrefConstant biNeg, ClassConstant arithEx,
			ClassConstant ratArrClass, MethodrefConstant rRatNum, MethodrefConstant rRatDen, MethodrefConstant rRat,
			ClassConstant doubleClass, MethodrefConstant rDbl, ClassConstant numberClass,
			MethodrefConstant numDoubleValue, MethodrefConstant doubleValueOf) {
		MethodCode c = new MethodCode();
		emitDoubleUnaryPrologue(c, doubleClass, rDbl, numberClass, numDoubleValue, doubleValueOf, MethodCode::dneg);
		c.aload(0);
		c.instanceOf(ratArrClass.entry());
		MethodCode.Label ifRat = c.newLabel();
		c.ifne(ifRat);
		c.aload(0);
		c.instanceOf(longClass.entry());
		MethodCode.Label ifSlow = c.newLabel();
		c.ifeq(ifSlow);
		MethodCode.Label tryStart = c.newBoundLabel();
		emitUnboxLong(c, 0, longClass, longValue);
		c.invokestatic(negExact.entry());
		c.invokestatic(longValueOf.entry());
		c.areturn();
		MethodCode.Label handler = c.newBoundLabel();
		c.pop();
		c.labelBinding(ifSlow);
		c.aload(0);
		c.invokestatic(rBig.entry());
		c.invokevirtual(biNeg.methodRefEntry());
		c.invokestatic(rNorm.entry());
		c.areturn();
		c.labelBinding(ifRat);
		c.aload(0);
		c.invokestatic(rRatNum.entry());
		c.invokevirtual(biNeg.methodRefEntry());
		c.aload(0);
		c.invokestatic(rRatDen.entry());
		c.invokestatic(rRat.entry());
		c.areturn();
		c.exceptionCatch(tryStart, handler, handler, arithEx.entry());
		return new NumericMethod(name, desc, c);
	}

	// _div(Object a, Object b): Common Lisp exact rational division for any mix of
	// integers and ratios: _rat(num(a)*den(b), den(a)*num(b)). The result demotes to an
	// integer when the division is exact; division by zero throws inside _rat.
	private static NumericMethod buildDiv(Utf8Constant name, Utf8Constant desc, MethodrefConstant rRatNum,
			MethodrefConstant rRatDen, MethodrefConstant rRat, MethodrefConstant biMul, ClassConstant doubleClass,
			MethodrefConstant rDbl, ClassConstant numberClass, MethodrefConstant numDoubleValue,
			MethodrefConstant doubleValueOf) {
		MethodCode c = new MethodCode();
		emitDoubleBinaryPrologue(c, doubleClass, rDbl, numberClass, numDoubleValue, doubleValueOf, MethodCode::ddiv);
		c.aload(0);
		c.invokestatic(rRatNum.entry());
		c.aload(1);
		c.invokestatic(rRatDen.entry());
		c.invokevirtual(biMul.methodRefEntry());
		c.aload(0);
		c.invokestatic(rRatDen.entry());
		c.aload(1);
		c.invokestatic(rRatNum.entry());
		c.invokevirtual(biMul.methodRefEntry());
		c.invokestatic(rRat.entry());
		c.areturn();
		return new NumericMethod(name, desc, c);
	}

	// _mod(Object a, Object b): Common Lisp modulo whose result takes the sign of the
	// divisor. Long fast path via Math.floorMod; BigInteger path corrects the remainder
	// by
	// adding the divisor when the signs differ.
	private static NumericMethod buildMod(Utf8Constant name, Utf8Constant desc, ClassConstant longClass,
			MethodrefConstant longValue, MethodrefConstant longValueOf, MethodrefConstant rBig, MethodrefConstant rNorm,
			MethodrefConstant biRem, MethodrefConstant floorModLong, MethodrefConstant biSignum,
			MethodrefConstant biAdd, ClassConstant doubleClass, MethodrefConstant rDbl, ClassConstant numberClass,
			MethodrefConstant numDoubleValue, MethodrefConstant doubleValueOf, MethodrefConstant rFmod,
			ClassConstant ratArrClass, MethodrefConstant rRatNum, MethodrefConstant rRatDen, MethodrefConstant rRat,
			MethodrefConstant biMul) {
		MethodCode c = new MethodCode();
		// A float operand takes CL's divisor-signed float modulo, the same _fmod the
		// double-literal emission calls -- without this arm a Double reaching the
		// generic helper (a fused site's bail, an argument the emitter could not see
		// the type of) fell through to _big and died casting Double to BigInteger.
		emitDoubleBinaryPrologue(c, doubleClass, rDbl, numberClass, numDoubleValue, doubleValueOf, MethodCode::drem,
				rFmod);
		MethodCode.Label toRatio = c.newLabel();
		emitRatioGuard(c, ratArrClass, toRatio);
		MethodCode.Label toSlow = c.newLabel();
		emitLongLongGuard(c, longClass, toSlow);
		emitUnboxLong(c, 0, longClass, longValue);
		emitUnboxLong(c, 1, longClass, longValue);
		c.invokestatic(floorModLong.entry());
		c.invokestatic(longValueOf.entry());
		c.areturn();
		c.labelBinding(toSlow);
		// BigInteger A = _big(a); BigInteger B = _big(b); BigInteger r = A.remainder(B);
		c.aload(0);
		c.invokestatic(rBig.entry());
		c.astore(2);
		c.aload(1);
		c.invokestatic(rBig.entry());
		c.astore(3);
		c.aload(2);
		c.aload(3);
		c.invokevirtual(biRem.methodRefEntry());
		c.astore(4);
		emitDivisorSignCorrection(c, biSignum, biAdd);
		c.aload(4);
		c.invokestatic(rNorm.entry());
		c.areturn();
		c.labelBinding(toRatio);
		emitRatioRemainderPrefix(c, rRatNum, rRatDen, biMul, biRem);
		emitDivisorSignCorrection(c, biSignum, biAdd);
		c.aload(4);
		emitRatioRemainderDenominator(c, rRatDen, biMul, rRat);
		return new NumericMethod(name, desc, c);
	}

	// _rem(Object a, Object b): remainder whose result takes the sign of the dividend
	// (Java/BigInteger remainder). Long fast path, BigInteger.remainder otherwise.
	private static NumericMethod buildRem(Utf8Constant name, Utf8Constant desc, ClassConstant longClass,
			MethodrefConstant longValue, MethodrefConstant longValueOf, MethodrefConstant rBig, MethodrefConstant rNorm,
			MethodrefConstant biRem, ClassConstant doubleClass, MethodrefConstant rDbl, ClassConstant numberClass,
			MethodrefConstant numDoubleValue, MethodrefConstant doubleValueOf, MethodrefConstant rFrem,
			ClassConstant ratArrClass, MethodrefConstant rRatNum, MethodrefConstant rRatDen, MethodrefConstant rRat,
			MethodrefConstant biMul) {
		MethodCode c = new MethodCode();
		// A float operand keeps the dividend's sign -- _frem, which is DREM plus CLHS's
		// sign for a ZERO remainder, and is what the double-literal emission of
		// (rem ...) also calls (see buildMod for why the arm has to exist here too).
		emitDoubleBinaryPrologue(c, doubleClass, rDbl, numberClass, numDoubleValue, doubleValueOf, MethodCode::drem,
				rFrem);
		MethodCode.Label toRatio = c.newLabel();
		emitRatioGuard(c, ratArrClass, toRatio);
		MethodCode.Label toSlow = c.newLabel();
		emitLongLongGuard(c, longClass, toSlow);
		emitUnboxLong(c, 0, longClass, longValue);
		emitUnboxLong(c, 1, longClass, longValue);
		c.lrem();
		c.invokestatic(longValueOf.entry());
		c.areturn();
		c.labelBinding(toSlow);
		emitBigBinary(c, rBig, biRem, rNorm);
		c.labelBinding(toRatio);
		emitRatioRemainderPrefix(c, rRatNum, rRatDen, biMul, biRem);
		c.aload(4);
		emitRatioRemainderDenominator(c, rRatDen, biMul, rRat);
		return new NumericMethod(name, desc, c);
	}

	// _fmod(double a, double b): floating-point modulo whose result takes the sign of the
	// divisor. r = _frem(a, b), corrected by adding the divisor when the two have
	// OPPOSITE signs and r is not a zero -- so mod and rem share _frem's zero unchanged.
	//
	// The signs are compared as the two DCMPG results, not as `r * b < 0`: that product
	// UNDERFLOWS to a zero when both operands are tiny, and the correction then silently
	// did not fire -- (mod -1.2345678e-296 1d-300) answered the negative remainder
	// instead of the positive one, and (mod 4.9d-324 -0.1) answered the dividend.
	// DCMPG(x, 0.0) is -1 below zero and 1 above (and 1 for a NaN, which lands on
	// whichever arm leaves the NaN alone), so equal results mean equal signs.
	//
	// r = _frem(a, b); if (r == 0) return r;
	// return dcmpg(r, 0) == dcmpg(b, 0) ? r : r + b;
	private static NumericMethod buildFmod(Utf8Constant name, Utf8Constant desc, MethodrefConstant rFrem) {
		MethodCode c = new MethodCode();
		c.dload(0);
		c.dload(2);
		c.invokestatic(rFrem.entry());
		c.dstore(4);
		c.dload(4);
		c.dconst_0();
		c.dcmpl(); // NaN compares as -1, so a NaN remainder is not a zero
		MethodCode.Label ifNonZero = c.newLabel();
		c.ifne(ifNonZero);
		c.dload(4);
		c.dreturn();
		c.labelBinding(ifNonZero);
		c.dload(4);
		c.dconst_0();
		c.dcmpg();
		c.dload(2);
		c.dconst_0();
		c.dcmpg();
		MethodCode.Label ifSameSign = c.newLabel();
		c.if_icmpeq(ifSameSign);
		c.dload(4);
		c.dload(2);
		c.dadd();
		c.dreturn();
		c.labelBinding(ifSameSign);
		c.dload(4);
		c.dreturn();
		return new NumericMethod(name, desc, c);
	}

	// _frem(double a, double b): the float remainder shared by rem and _fmod. DREM,
	// except for the sign of a ZERO result. CLHS defines rem as the remainder of
	// truncate and mod as the remainder of floor, both `a - b*q` with an exact INTEGER
	// quotient -- not IEEE fmod, whose zero takes the dividend's sign whatever the
	// divisor. DREM is the exact value that formula denotes everywhere else, so only
	// the zero is re-derived: a zero dividend has q = +0, leaving b*q with the
	// DIVISOR's sign, and a nonzero dividend cancels against itself as IEEE's +0.0.
	//
	// r = a % b; if (r != 0) return r; // NaN too
	// if (a != 0) return 0.0; // a - a
	// return b < 0 ? a + 0.0 : a - 0.0; // a - copysign(0.0, b)
	private static NumericMethod buildFrem(Utf8Constant name, Utf8Constant desc) {
		MethodCode c = new MethodCode();
		c.dload(0);
		c.dload(2);
		c.drem();
		c.dstore(4);
		c.dload(4);
		c.dconst_0();
		c.dcmpl(); // NaN compares as -1, so a NaN remainder falls through
		MethodCode.Label ifZeroResult = c.newLabel();
		c.ifeq(ifZeroResult);
		c.dload(4);
		c.dreturn();
		c.labelBinding(ifZeroResult);
		c.dload(0);
		c.dconst_0();
		c.dcmpl();
		MethodCode.Label ifZeroDividend = c.newLabel();
		c.ifeq(ifZeroDividend);
		c.dconst_0();
		c.dreturn();
		c.labelBinding(ifZeroDividend);
		// b is neither NaN nor a zero here -- either would have made r a NaN.
		c.dload(2);
		c.dconst_0();
		c.dcmpg();
		MethodCode.Label ifPositiveDivisor = c.newLabel();
		c.ifge(ifPositiveDivisor);
		c.dload(0);
		c.dconst_0();
		c.dadd();
		c.dreturn();
		c.labelBinding(ifPositiveDivisor);
		c.dload(0);
		c.dconst_0();
		c.dsub();
		c.dreturn();
		return new NumericMethod(name, desc, c);
	}

	// The exact comparison of a (Double, exact) pair, shared by _cmp and _cmpb: the
	// operand loaded by dblLoad is a Double, the one loaded by othLoad is exact
	// (Long/BigInteger/ratio) or the float funnel's NUMBER operand-type report throw. A
	// finite
	// double compares its _frat decomposition against the exact operand's (_ratNum,
	// _ratDen) by cross-multiplication (every denominator is positive, so the
	// direction is preserved); an infinity outweighs every exact number on its side.
	// A NaN double either answers the unordered mask (bitmask mode, _cmpb) or jumps
	// back to a caller-recorded old path (signum mode, _cmp, where unordered is the
	// DCMPL collapse both callers already had). Locals 2/3 hold the double, local 4
	// the _frat pair. Every path returns.
	private static void emitExactFloatCompare(MethodCode c, int dblSlot, int othSlot, boolean dblIsA, boolean bitmask,
			ClassConstant numberClass, MethodrefConstant numDoubleValue, MethodrefConstant rFrat,
			ClassConstant ratArrClass, ClassConstant bigClass, ClassConstant longClass, MethodrefConstant rRatNum,
			MethodrefConstant rRatDen, MethodrefConstant biMul, MethodrefConstant biCompareTo,
			MethodrefConstant intSignum, TypeErrRefs typeErrRefs, MethodCode.@Nullable Label nanFallback) {
		c.aload(dblSlot);
		c.checkcast(numberClass.entry());
		c.invokevirtual(numDoubleValue.methodRefEntry());
		c.dstore(2);
		// NaN: DCMPL(d, d) falls out as -1, so IFEQ skips it.
		c.dload(2);
		c.dload(2);
		c.dcmpl();
		MethodCode.Label ifNotNaN = c.newLabel();
		c.ifeq(ifNotNaN);
		if (nanFallback != null) {
			c.goto_(nanFallback);
		}
		else {
			c.iconst_0();
			c.ireturn();
		}
		c.labelBinding(ifNotNaN);
		// The exact pair _frat answers for a finite double (the pair array class is
		// the ratio's: buildRational casts the same way). Null past the NaN check
		// above is an infinity.
		c.aload(dblSlot);
		c.invokestatic(rFrat.entry());
		c.dup();
		MethodCode.Label ifFinite = c.newLabel();
		c.ifnonnull(ifFinite);
		c.pop();
		// DCMPL(d, 0) is 1 or -1 here (a zero double decomposes, never nulls).
		c.dload(2);
		c.dconst_0();
		c.dcmpl();
		MethodCode.Label ifNegInf = c.newLabel();
		c.ifle(ifNegInf);
		emitMixedInfinite(c, dblIsA, bitmask, true);
		c.labelBinding(ifNegInf);
		emitMixedInfinite(c, dblIsA, bitmask, false);
		c.labelBinding(ifFinite);
		c.checkcast(ratArrClass.entry());
		c.astore(4);
		// The exact side's funnel: a non-number beside a float is "Expected
		// number", the _dbl text the mixed pair used to see.
		c.aload(othSlot);
		c.instanceOf(ratArrClass.entry());
		MethodCode.Label ifOthRat = c.newLabel();
		c.ifne(ifOthRat);
		c.aload(othSlot);
		c.instanceOf(longClass.entry());
		MethodCode.Label ifOthLong = c.newLabel();
		c.ifne(ifOthLong);
		c.aload(othSlot);
		c.instanceOf(bigClass.entry());
		MethodCode.Label ifOthBig = c.newLabel();
		c.ifne(ifOthBig);
		c.aload(othSlot);
		typeErrRefs.throwRefs().emitThrowLoaded(c, typeErrRefs.throwRefs().numberKind());
		c.labelBinding(ifOthRat);
		c.labelBinding(ifOthLong);
		c.labelBinding(ifOthBig);
		// left = numA*denB, right = numB*denA with (A, B) = (dbl, oth) or the
		// mirror, so the sign reads in (a, b) order without a flag local. The
		// bitmask shape leads with 1 for the 1 << (signum + 1) tail.
		if (bitmask) {
			c.iconst_1();
		}
		emitMixedNumDen(c, dblIsA, 0, othSlot, rRatNum, ratArrClass, bigClass);
		emitMixedNumDen(c, !dblIsA, 1, othSlot, rRatDen, ratArrClass, bigClass);
		c.invokevirtual(biMul.methodRefEntry());
		emitMixedNumDen(c, !dblIsA, 0, othSlot, rRatNum, ratArrClass, bigClass);
		emitMixedNumDen(c, dblIsA, 1, othSlot, rRatDen, ratArrClass, bigClass);
		c.invokevirtual(biMul.methodRefEntry());
		c.invokevirtual(biCompareTo.methodRefEntry());
		c.invokestatic(intSignum.entry());
		if (bitmask) {
			c.iconst_1();
			c.iadd();
			c.ishl();
		}
		c.ireturn();
	}

	// One numerator/denominator side of the mixed cross-multiplication: the _frat
	// pair's element when pairSide, else the exact operand's _ratNum/_ratDen (which
	// funnel Long/BigInteger through _big and answer ONE for a non-ratio
	// denominator, so the funnel check above is what rejects junk).
	private static void emitMixedNumDen(MethodCode c, boolean pairSide, int pairIndex, int othSlot,
			MethodrefConstant rRatPart, ClassConstant ratArrClass, ClassConstant bigClass) {
		if (pairSide) {
			c.aload(4);
			if (pairIndex == 0) {
				c.iconst_0();
			}
			else {
				c.iconst_1();
			}
			c.aaload();
			c.checkcast(bigClass.entry());
		}
		else {
			c.aload(othSlot);
			c.invokestatic(rRatPart.entry());
		}
	}

	// An infinite double against an exact number: beyond it on its side's sign.
	private static void emitMixedInfinite(MethodCode c, boolean dblIsA, boolean bitmask, boolean positive) {
		if (bitmask) {
			c.loadConstant(dblIsA == positive ? 4 : 1);
		}
		else {
			c.loadConstant(dblIsA == positive ? 1 : -1);
		}
		c.ireturn();
	}

	// _cmp(Object a, Object b): long comparison, BigInteger.compareTo, or rational
	// cross-multiplication (denominators are positive), returning -1/0/1.
	private static NumericMethod buildCmp(Utf8Constant name, Utf8Constant desc, ClassConstant longClass,
			MethodrefConstant longValue, MethodrefConstant rBig, MethodrefConstant biCompareTo,
			ClassConstant ratArrClass, MethodrefConstant rRatNum, MethodrefConstant rRatDen, MethodrefConstant biMul,
			ClassConstant doubleClass, MethodrefConstant rDbl, ClassConstant numberClass,
			MethodrefConstant numDoubleValue, ClassConstant bigClass, MethodrefConstant rFrat,
			MethodrefConstant intSignum, TypeErrRefs typeErrRefs) {
		MethodCode c = new MethodCode();
		// Double dispatch: both doubles take the old double comparison; exactly one
		// double takes the exact mixed comparison (a NaN jumps back to the old path,
		// which collapses it to -1 as before); neither reaches the exact body below.
		c.aload(0);
		c.instanceOf(doubleClass.entry());
		MethodCode.Label ifANotDouble = c.newLabel();
		c.ifeq(ifANotDouble);
		c.aload(1);
		c.instanceOf(doubleClass.entry());
		MethodCode.Label ifMixedA = c.newLabel();
		c.ifeq(ifMixedA);
		MethodCode.Label oldPath = c.newBoundLabel();
		emitToDouble(c, 0, rDbl, numberClass, numDoubleValue);
		emitToDouble(c, 1, rDbl, numberClass, numDoubleValue);
		c.dcmpl();
		c.ireturn();
		c.labelBinding(ifMixedA);
		emitExactFloatCompare(c, 0, 1, true, false, numberClass, numDoubleValue, rFrat, ratArrClass, bigClass,
				longClass, rRatNum, rRatDen, biMul, biCompareTo, intSignum, typeErrRefs, oldPath);
		c.labelBinding(ifANotDouble);
		c.aload(1);
		c.instanceOf(doubleClass.entry());
		MethodCode.Label ifExactRest = c.newLabel();
		c.ifeq(ifExactRest);
		emitExactFloatCompare(c, 1, 0, false, false, numberClass, numDoubleValue, rFrat, ratArrClass, bigClass,
				longClass, rRatNum, rRatDen, biMul, biCompareTo, intSignum, typeErrRefs, oldPath);
		c.labelBinding(ifExactRest);
		MethodCode.Label toRatio = c.newLabel();
		emitRatioGuard(c, ratArrClass, toRatio);
		MethodCode.Label toSlow = c.newLabel();
		emitLongLongGuard(c, longClass, toSlow);
		emitUnboxLong(c, 0, longClass, longValue);
		emitUnboxLong(c, 1, longClass, longValue);
		c.lcmp();
		c.ireturn();
		c.labelBinding(toSlow);
		c.aload(0);
		c.invokestatic(rBig.entry());
		c.aload(1);
		c.invokestatic(rBig.entry());
		c.invokevirtual(biCompareTo.methodRefEntry());
		c.ireturn();
		c.labelBinding(toRatio);
		c.aload(0);
		c.invokestatic(rRatNum.entry());
		c.aload(1);
		c.invokestatic(rRatDen.entry());
		c.invokevirtual(biMul.methodRefEntry());
		c.aload(1);
		c.invokestatic(rRatNum.entry());
		c.aload(0);
		c.invokestatic(rRatDen.entry());
		c.invokevirtual(biMul.methodRefEntry());
		c.invokevirtual(biCompareTo.methodRefEntry());
		c.ireturn();
		return new NumericMethod(name, desc, c);
	}

	// _cmpb(Object a, Object b): the comparison as a bitmask -- 1 = a<b, 2 = a=b,
	// 4 = a>b, 0 = unordered (a NaN operand). The comparison operators AND the mask
	// they accept and branch on nonzero, so NaN fails every one of = < > <= >= (IEEE),
	// which a -1/0/1 signum cannot express. Two doubles compare in f64; a double
	// beside an exact number compares exact values through emitExactFloatCompare;
	// exact pairs delegate to _cmp (exact, never unordered).
	private static NumericMethod buildCmpBits(Utf8Constant name, Utf8Constant desc, ClassConstant doubleClass,
			MethodrefConstant rDbl, ClassConstant numberClass, MethodrefConstant numDoubleValue, MethodrefConstant rCmp,
			MethodrefConstant intSignum, @Nullable ClassConstant rcClass, @Nullable FieldrefConstant rcReal,
			@Nullable FieldrefConstant rcImag, MethodrefConstant longValueOf, @Nullable FieldrefConstant hasComplex,
			MethodrefConstant rFrat, ClassConstant ratArrClass, ClassConstant longClass, ClassConstant bigClass,
			MethodrefConstant rRatNum, MethodrefConstant rRatDen, MethodrefConstant biMul,
			MethodrefConstant biCompareTo, TypeErrRefs typeErrRefs) {
		MethodCode c = new MethodCode();
		if (rcClass != null) {
			// A complex operand compares part-wise: equal exactly when both part
			// pairs are _cmp-equal (a real counts as a zero-imagined complex, so
			// (= 2.0 #C(2.0 0.0)) is true); anything else answers unordered, which
			// fails every operator. Ordering over a complex never reaches here --
			// the gated call sites use _ccmpb, which signals. Like the _abs arm,
			// emitted only for a complex-capable program. The presence probe first:
			// a lone class run without the travelling file must not resolve the
			// holder class it then never touches (.todo/757).
			MethodCode.Label noHolder = emitNoHolderJump(c, hasComplex);
			ClassConstant complexClass = Objects.requireNonNull(rcClass);
			FieldrefConstant complexReal = Objects.requireNonNull(rcReal);
			FieldrefConstant complexImag = Objects.requireNonNull(rcImag);
			c.aload(0);
			c.instanceOf(complexClass.entry());
			MethodCode.Label ifAReal = c.newLabel();
			c.ifeq(ifAReal);
			MethodCode.Label toComplex = c.newLabel();
			c.goto_(toComplex);
			c.labelBinding(ifAReal);
			c.aload(1);
			c.instanceOf(complexClass.entry());
			MethodCode.Label ifNotComplex = c.newLabel();
			c.ifeq(ifNotComplex);
			c.labelBinding(toComplex);
			emitComplexPart(c, 0, complexClass, complexReal);
			c.astore(2);
			emitComplexImag(c, 0, complexClass, complexImag, longValueOf);
			c.astore(3);
			emitComplexPart(c, 1, complexClass, complexReal);
			c.astore(4);
			emitComplexImag(c, 1, complexClass, complexImag, longValueOf);
			c.astore(5);
			c.aload(2);
			c.aload(4);
			c.invokestatic(rCmp.entry());
			MethodCode.Label ifReNe = c.newLabel();
			c.ifne(ifReNe);
			c.aload(3);
			c.aload(5);
			c.invokestatic(rCmp.entry());
			MethodCode.Label ifImNe = c.newLabel();
			c.ifne(ifImNe);
			c.iconst_2();
			c.ireturn();
			c.labelBinding(ifReNe);
			c.labelBinding(ifImNe);
			c.iconst_0();
			c.ireturn();
			c.labelBinding(ifNotComplex);
			c.labelBinding(noHolder);
		}
		c.aload(0);
		c.instanceOf(doubleClass.entry());
		MethodCode.Label ifANotDouble = c.newLabel();
		c.ifeq(ifANotDouble);
		c.aload(1);
		c.instanceOf(doubleClass.entry());
		MethodCode.Label ifMixedA = c.newLabel();
		c.ifeq(ifMixedA);
		// x -> locals 2/3, y -> locals 4/5
		emitToDouble(c, 0, rDbl, numberClass, numDoubleValue);
		c.dstore(2);
		emitToDouble(c, 1, rDbl, numberClass, numDoubleValue);
		c.dstore(4);
		// x < y -> 1 (DCMPG: NaN falls out as +1, so IFGE skips)
		c.dload(2);
		c.dload(4);
		c.dcmpg();
		MethodCode.Label notLt = c.newLabel();
		c.ifge(notLt);
		c.iconst_1();
		c.ireturn();
		c.labelBinding(notLt);
		// x > y -> 4 (DCMPL: NaN falls out as -1, so IFLE skips)
		c.dload(2);
		c.dload(4);
		c.dcmpl();
		MethodCode.Label notGt = c.newLabel();
		c.ifle(notGt);
		c.iconst_4();
		c.ireturn();
		c.labelBinding(notGt);
		// x == y -> 2, else unordered -> 0 (only NaN reaches here unequal)
		c.dload(2);
		c.dload(4);
		c.dcmpl();
		MethodCode.Label notEq = c.newLabel();
		c.ifne(notEq);
		c.iconst_2();
		c.ireturn();
		c.labelBinding(notEq);
		c.iconst_0();
		c.ireturn();
		// Exactly one double: the exact comparison (every path returns).
		c.labelBinding(ifMixedA);
		emitExactFloatCompare(c, 0, 1, true, true, numberClass, numDoubleValue, rFrat, ratArrClass, bigClass, longClass,
				rRatNum, rRatDen, biMul, biCompareTo, intSignum, typeErrRefs, null);
		c.labelBinding(ifANotDouble);
		c.aload(1);
		c.instanceOf(doubleClass.entry());
		MethodCode.Label ifExactTail = c.newLabel();
		c.ifeq(ifExactTail);
		emitExactFloatCompare(c, 1, 0, false, true, numberClass, numDoubleValue, rFrat, ratArrClass, bigClass,
				longClass, rRatNum, rRatDen, biMul, biCompareTo, intSignum, typeErrRefs, null);
		// exact types: 1 << (signum(_cmp(a, b)) + 1)
		c.labelBinding(ifExactTail);
		c.iconst_1();
		c.aload(0);
		c.aload(1);
		c.invokestatic(rCmp.entry());
		c.invokestatic(intSignum.entry());
		c.iconst_1();
		c.iadd();
		c.ishl();
		c.ireturn();
		// maxStack 5: the mixed float/exact cross holds 1, left and right (three
		// references) while loading the right denominator.
		return new NumericMethod(name, desc, c);
	}

	// _ccmpb(Object a, Object b): like _cmpb, but a complex operand signals the
	// interpreter's REAL operand-type report text instead of comparing -- the
	// ordering operators' comparison once a complex literal steered them off
	// the double path (`.kb/jvm-complex.md`).
	/**
	 * Emits {@code throw _teRaw(value, "REAL")} for the value in local {@code slot}.
	 */
	private static void emitRealErrThrow(MethodCode c, TypeErrRefs refs, int slot) {
		c.aload(slot);
		refs.throwRefs().emitThrowLoaded(c, refs.throwRefs().realKind());
	}

	/**
	 * Emits the real part of the value in local {@code slot}: the holder's field, or the
	 * value itself.
	 */
	private static void emitComplexPart(MethodCode c, int slot, ClassConstant rcClass, FieldrefConstant rcReal) {
		c.aload(slot);
		c.instanceOf(rcClass.entry());
		MethodCode.Label ifReal = c.newLabel();
		c.ifeq(ifReal);
		c.aload(slot);
		c.checkcast(rcClass.entry());
		c.getfield(rcReal.entry());
		MethodCode.Label done = c.newLabel();
		c.goto_(done);
		c.labelBinding(ifReal);
		c.aload(slot);
		c.labelBinding(done);
	}

	/**
	 * Emits the holder-presence probe for a holder arm: falls through when a holder
	 * instance can exist (the travelling class loaded), and returns the label to bind at
	 * the arm's end, where it jumps otherwise -- then the holder-less shape that follows
	 * is exact, because no holder instance can exist without its class (.todo/757).
	 */
	private static MethodCode.Label emitNoHolderJump(MethodCode c, @Nullable FieldrefConstant hasComplex) {
		c.getstatic(Objects.requireNonNull(hasComplex).entry());
		MethodCode.Label noHolder = c.newLabel();
		c.ifeq(noHolder);
		return noHolder;
	}

	/**
	 * Emits the imaginary part of the value in local {@code slot}: the holder's field, or
	 * an integer zero (float contagion is decided by the real parts in every caller, so
	 * the zero's own kind never matters).
	 */
	private static void emitComplexImag(MethodCode c, int slot, ClassConstant rcClass, FieldrefConstant rcImag,
			MethodrefConstant longValueOf) {
		c.aload(slot);
		c.instanceOf(rcClass.entry());
		MethodCode.Label ifReal = c.newLabel();
		c.ifeq(ifReal);
		c.aload(slot);
		c.checkcast(rcClass.entry());
		c.getfield(rcImag.entry());
		MethodCode.Label done = c.newLabel();
		c.goto_(done);
		c.labelBinding(ifReal);
		c.lconst_0();
		c.invokestatic(longValueOf.entry());
		c.labelBinding(done);
	}

	// _abs(Object a): Math.abs for a Double (float), Math.abs for Long (promoting
	// Long.MIN_VALUE), numerator.abs() for a ratio, BigInteger.abs otherwise. The Double
	// branch handles a float reaching abs through a variable (no compile-time literal),
	// the
	// way the binary ops' double prologue does.
	private static NumericMethod buildAbs(Utf8Constant name, Utf8Constant desc, ClassConstant longClass,
			ClassConstant bigClass, MethodrefConstant longValue, MethodrefConstant longValueOf,
			MethodrefConstant absLong, MethodrefConstant biValueOf, MethodrefConstant biNeg, MethodrefConstant biAbs,
			MethodrefConstant rNorm, LongConstant cMin, ClassConstant ratArrClass, MethodrefConstant rRatNum,
			MethodrefConstant rRatDen, MethodrefConstant rRat, ClassConstant doubleClass, MethodrefConstant rDbl,
			ClassConstant numberClass, MethodrefConstant numDoubleValue, MethodrefConstant doubleValueOf,
			MethodrefConstant absDouble, MethodrefConstant rBig, @Nullable ClassConstant rcClass,
			@Nullable FieldrefConstant rcReal, @Nullable FieldrefConstant rcImag, @Nullable MethodrefConstant mathHypot,
			@Nullable FieldrefConstant hasComplex) {
		MethodCode c = new MethodCode();
		if (rcClass != null) {
			// A complex operand answers its float modulus -- hypot over the double
			// parts, a real even for exact parts like the interpreter. Emitted
			// only for a complex-capable program, so the holder class the test
			// resolves stays out of every other constant pool. The presence probe
			// first, so a lone class without the file never resolves it
			// (.todo/757).
			MethodCode.Label noHolder = emitNoHolderJump(c, hasComplex);
			ClassConstant complexClass = Objects.requireNonNull(rcClass);
			FieldrefConstant complexReal = Objects.requireNonNull(rcReal);
			FieldrefConstant complexImag = Objects.requireNonNull(rcImag);
			MethodrefConstant hypot = Objects.requireNonNull(mathHypot);
			c.aload(0);
			c.instanceOf(complexClass.entry());
			MethodCode.Label ifNotComplex = c.newLabel();
			c.ifeq(ifNotComplex);
			c.aload(0);
			c.checkcast(complexClass.entry());
			c.getfield(complexReal.entry());
			c.invokestatic(rDbl.entry());
			c.checkcast(numberClass.entry());
			c.invokevirtual(numDoubleValue.methodRefEntry());
			c.aload(0);
			c.checkcast(complexClass.entry());
			c.getfield(complexImag.entry());
			c.invokestatic(rDbl.entry());
			c.checkcast(numberClass.entry());
			c.invokevirtual(numDoubleValue.methodRefEntry());
			c.invokestatic(hypot.entry());
			c.invokestatic(doubleValueOf.entry());
			c.areturn();
			c.labelBinding(ifNotComplex);
			c.labelBinding(noHolder);
		}
		// Double fast path: Math.abs((double) a) when a is a Double.
		c.aload(0);
		c.instanceOf(doubleClass.entry());
		MethodCode.Label ifNotDouble = c.newLabel();
		c.ifeq(ifNotDouble);
		emitToDouble(c, 0, rDbl, numberClass, numDoubleValue);
		c.invokestatic(absDouble.entry());
		c.invokestatic(doubleValueOf.entry());
		c.areturn();
		c.labelBinding(ifNotDouble);
		c.aload(0);
		c.instanceOf(ratArrClass.entry());
		MethodCode.Label ifRat = c.newLabel();
		c.ifne(ifRat);
		c.aload(0);
		c.instanceOf(longClass.entry());
		MethodCode.Label ifBig = c.newLabel();
		c.ifeq(ifBig);
		emitUnboxLong(c, 0, longClass, longValue);
		c.lstore(2);
		c.lload(2);
		c.ldc(cMin.entry());
		c.lcmp();
		MethodCode.Label ifNeMin = c.newLabel();
		c.ifne(ifNeMin);
		// Overflow: BigInteger.valueOf(Long.MIN_VALUE).negate().
		c.lload(2);
		c.invokestatic(biValueOf.entry());
		c.invokevirtual(biNeg.methodRefEntry());
		c.invokestatic(rNorm.entry());
		c.areturn();
		c.labelBinding(ifNeMin);
		c.lload(2);
		c.invokestatic(absLong.entry());
		c.invokestatic(longValueOf.entry());
		c.areturn();
		c.labelBinding(ifBig);
		// Through _big rather than a bare checkcast, so a non-number (which null-passes
		// a checkcast and NPEs inside BigInteger.abs) throws the INTEGER operand-type
		// report
		// text at the coercion like every other operator.
		c.aload(0);
		c.invokestatic(rBig.entry());
		c.invokevirtual(biAbs.methodRefEntry());
		c.invokestatic(rNorm.entry());
		c.areturn();
		c.labelBinding(ifRat);
		c.aload(0);
		c.invokestatic(rRatNum.entry());
		c.invokevirtual(biAbs.methodRefEntry());
		c.aload(0);
		c.invokestatic(rRatDen.entry());
		c.invokestatic(rRat.entry());
		c.areturn();
		return new NumericMethod(name, desc, c);
	}

	// _signum(Object a): Math.signum for a Double (float, -1.0/0.0/1.0), otherwise the
	// integer sign as a Long (the numerator's sign for a ratio). The Double branch
	// handles
	// a float reaching signum through a variable, mirroring _abs.
	private static NumericMethod buildSignum(Utf8Constant name, Utf8Constant desc, ClassConstant doubleClass,
			MethodrefConstant rDbl, ClassConstant numberClass, MethodrefConstant numDoubleValue,
			MethodrefConstant doubleValueOf, MethodrefConstant signumDouble, MethodrefConstant rRatNum,
			MethodrefConstant biSignum, MethodrefConstant longValueOf, @Nullable ClassConstant rcClass,
			@Nullable MethodrefConstant rCsignum, @Nullable FieldrefConstant hasComplex) {
		MethodCode c = new MethodCode();
		if (rcClass != null) {
			// A complex operand answers the gated _csignum unit vector, like the
			// interpreter. Emitted only for a complex-capable program, so the
			// holder class the test resolves stays out of every other constant
			// pool (the _abs arm pattern). The presence probe first, so a lone
			// class without the file never resolves it (.todo/757).
			MethodCode.Label noHolder = emitNoHolderJump(c, hasComplex);
			c.aload(0);
			c.instanceOf(rcClass.entry());
			MethodCode.Label ifNotComplex = c.newLabel();
			c.ifeq(ifNotComplex);
			c.aload(0);
			c.invokestatic(java.util.Objects.requireNonNull(rCsignum).entry());
			c.areturn();
			c.labelBinding(ifNotComplex);
			c.labelBinding(noHolder);
		}
		// Double fast path: Math.signum((double) a).
		c.aload(0);
		c.instanceOf(doubleClass.entry());
		MethodCode.Label ifNotDouble = c.newLabel();
		c.ifeq(ifNotDouble);
		emitToDouble(c, 0, rDbl, numberClass, numDoubleValue);
		c.invokestatic(signumDouble.entry());
		c.invokestatic(doubleValueOf.entry());
		c.areturn();
		c.labelBinding(ifNotDouble);
		// Integer/ratio path: (long) _ratnum(a).signum().
		c.aload(0);
		c.invokestatic(rRatNum.entry());
		c.invokevirtual(biSignum.methodRefEntry());
		c.i2l();
		c.invokestatic(longValueOf.entry());
		c.areturn();
		return new NumericMethod(name, desc, c);
	}

	// _random(Object limit): a non-negative random number below limit, of the same type
	// as
	// limit. d = Math.random() * (double) limit; a Double limit returns d, otherwise the
	// truncated (long) d. Dispatching on the runtime type handles a float limit reaching
	// random through a variable; using _dbl for the multiply also makes the integer path
	// robust to a BigInteger limit.
	//
	// CLHS's domain is (OR (INTEGER 1) (FLOAT (0.0))): a ratio limit (a BigInteger[]
	// here)
	// is real but neither, and an integer or float limit <= 0 is out of range either way.
	// Both are reported under RANDOM's own registered REAL type (like a non-real limit,
	// `.kb/error-handling.md` "A wrong-type argument names its operator") rather than
	// teaching the shared operand-type table a compound type for this one operator
	// (.todo/981).
	private static NumericMethod buildRandom(Utf8Constant name, Utf8Constant desc, ClassConstant doubleClass,
			MethodrefConstant rDbl, ClassConstant numberClass, MethodrefConstant numDoubleValue,
			MethodrefConstant doubleValueOf, MethodrefConstant longValueOf, MethodrefConstant tlrCurrent,
			MethodrefConstant tlrNextDouble, ClassConstant ratArrClass, TypeErrRefs typeErrRefs) {
		MethodCode c = new MethodCode();
		c.aload(0);
		c.instanceOf(ratArrClass.entry());
		MethodCode.Label ifNotRatio = c.newLabel();
		c.ifeq(ifNotRatio);
		emitRealErrThrow(c, typeErrRefs);
		c.labelBinding(ifNotRatio);
		// limitD = _dbl(limit); reject <= 0 (and NaN, DCMPL's -1) before drawing.
		emitToDouble(c, 0, rDbl, numberClass, numDoubleValue);
		c.dup2();
		c.dconst_0();
		c.dcmpl();
		MethodCode.Label ifPositive = c.newLabel();
		c.ifgt(ifPositive);
		emitRealErrThrow(c, typeErrRefs);
		c.labelBinding(ifPositive);
		// d = ThreadLocalRandom.current().nextDouble() * limitD. The per-thread
		// generator, not Math.random()'s single shared java.util.Random -- see
		// .kb/random.md.
		c.invokestatic(tlrCurrent.entry());
		c.invokevirtual(tlrNextDouble.methodRefEntry());
		c.dmul();
		// limit instanceof Double ? Double.valueOf(d) : Long.valueOf((long) d)
		c.aload(0);
		c.instanceOf(doubleClass.entry());
		MethodCode.Label ifNotDouble = c.newLabel();
		c.ifeq(ifNotDouble);
		c.invokestatic(doubleValueOf.entry());
		c.areturn();
		c.labelBinding(ifNotDouble);
		c.d2l();
		c.invokestatic(longValueOf.entry());
		c.areturn();
		return new NumericMethod(name, desc, c);
	}

	// _min/_max(Object a, Object b): keep a when the IEEE comparison holds, else take b
	// --
	// min is (a <= b) ? a : b and max is (a >= b) ? a : b.
	//
	// The test runs off the _cmpb BITMASK (1 = a<b, 2 = a=b, 4 = a>b, 0 = unordered), not
	// off _cmp's -1/0/1 signum, because only the mask can say "unordered". _cmp collapses
	// every NaN pair to -1, which made min(NaN, x) answer NaN while min(x, NaN) answered
	// x -- neither of them the IEEE comparison's answer.
	//
	// min accepts {lt, eq} = 3, max accepts {gt, eq} = 6. An equal-value tie is in both
	// masks, so the ACCUMULATOR survives it and the leftmost argument wins; unordered is
	// in neither, so a NaN operand always yields b. Both match upstream Common Lisp,
	// checked against SBCL over every ordered pair of {-0.0, 0.0, +/-1.0, NaN, +/-inf}.
	private static NumericMethod buildSelect(Utf8Constant name, Utf8Constant desc, MethodrefConstant rCmpb,
			int acceptMask, @Nullable ClassConstant rcClass, TypeErrRefs typeErrRefs,
			@Nullable FieldrefConstant hasComplex) {
		MethodCode c = new MethodCode();
		if (rcClass != null) {
			// Ordering over a complex signals a REAL operand-type report, like the
			// interpreter (min and max select over an
			// ordering, so both throw here). Emitted only for a
			// complex-capable program, like the _abs arm. The presence probe
			// first, so a lone class without the file never resolves it
			// (.todo/757).
			MethodCode.Label noHolder = emitNoHolderJump(c, hasComplex);
			ClassConstant complexClass = Objects.requireNonNull(rcClass);
			c.aload(0);
			c.instanceOf(complexClass.entry());
			MethodCode.Label ifAReal = c.newLabel();
			c.ifeq(ifAReal);
			emitRealErrThrow(c, typeErrRefs, 0);
			c.labelBinding(ifAReal);
			c.aload(1);
			c.instanceOf(complexClass.entry());
			MethodCode.Label ifBReal = c.newLabel();
			c.ifeq(ifBReal);
			emitRealErrThrow(c, typeErrRefs, 1);
			c.labelBinding(ifBReal);
			c.labelBinding(noHolder);
		}
		c.aload(0);
		c.aload(1);
		c.invokestatic(rCmpb.entry());
		c.loadConstant(acceptMask);
		c.iand();
		MethodCode.Label ifB = c.newLabel();
		c.ifeq(ifB);
		c.aload(0);
		c.areturn();
		c.labelBinding(ifB);
		c.aload(1);
		c.areturn();
		return new NumericMethod(name, desc, c);
	}

	// _fmin/_fmax(double a, double b): the same select on raw doubles, for the call sites
	// that already have both operands unboxed. min is (a <= b) ? a : b, max is
	// (a >= b) ? a : b.
	//
	// Deliberately NOT Math.min/Math.max, which resolve a signed-zero tie by SIGN
	// (Math.min(0.0, -0.0) is -0.0 whichever way round the arguments come) and propagate
	// NaN from either side. Those answers disagree with _min/_max above, so a program's
	// result used to depend on whether an operand happened to be a literal.
	//
	// DCMPG for min and DCMPL for max are what make NaN fall to b: each pushes the value
	// that fails its branch when an operand is unordered.
	private static NumericMethod buildFloatSelect(Utf8Constant name, Utf8Constant desc, Consumer<MethodCode> compare,
			BiConsumer<MethodCode, MethodCode.Label> keepA) {
		MethodCode c = new MethodCode();
		c.dload(0);
		c.dload(2);
		compare.accept(c);
		MethodCode.Label ifA = c.newLabel();
		keepA.accept(c, ifA);
		c.dload(2);
		c.dreturn();
		c.labelBinding(ifA);
		c.dload(0);
		c.dreturn();
		return new NumericMethod(name, desc, c);
	}

	// _dbl(Object x): boxed Double for any numeric value. A ratio converts through
	// _ratToDouble (correctly rounded, so huge components do not overflow to infinity
	// first and exact quotients stay exact); Long/BigInteger/Double go through
	// Number.doubleValue().
	//
	// A value that is ALREADY a Double is answered as-is rather than re-boxed. Every
	// caller (JvmEmitHelper.unboxDouble and the double branch of every helper below)
	// unboxes the result immediately, so the identity of the box is unobservable -- and
	// the re-box was an allocation on the hot path of every float program, one per
	// operand of every double-path operation.
	private static NumericMethod buildDbl(Utf8Constant name, Utf8Constant desc, ClassConstant ratArrClass,
			ClassConstant doubleClass, ClassConstant numberClass, MethodrefConstant doubleValueOf,
			MethodrefConstant numDoubleValue, MethodrefConstant rRatNum, MethodrefConstant rRatDen,
			MethodrefConstant rRatToDouble, TypeErrRefs typeErrRefs, @Nullable ClassConstant rcClass,
			@Nullable FieldrefConstant hasComplex) {
		MethodCode c = new MethodCode();
		if (rcClass != null) {
			// A complex reaching the f64 coercion is not silently reduced to its
			// real part: it throws the interpreter's REAL operand-type report text.
			// Emitted only for a complex-capable program, so the holder class stays
			// out of every other constant pool (the _abs arm pattern). The presence
			// probe first, so a lone class without the file never resolves it
			// (.todo/757).
			MethodCode.Label noHolder = emitNoHolderJump(c, hasComplex);
			c.aload(0);
			c.instanceOf(rcClass.entry());
			MethodCode.Label ifNotComplex = c.newLabel();
			c.ifeq(ifNotComplex);
			emitRealErrThrow(c, typeErrRefs);
			c.labelBinding(ifNotComplex);
			c.labelBinding(noHolder);
		}
		c.aload(0);
		c.instanceOf(doubleClass.entry());
		MethodCode.Label ifNotDouble = c.newLabel();
		c.ifeq(ifNotDouble);
		c.aload(0);
		c.areturn();
		c.labelBinding(ifNotDouble);
		c.aload(0);
		c.instanceOf(ratArrClass.entry());
		MethodCode.Label ifNotRat = c.newLabel();
		c.ifeq(ifNotRat);
		c.aload(0);
		c.invokestatic(rRatNum.entry());
		c.aload(0);
		c.invokestatic(rRatDen.entry());
		c.invokestatic(rRatToDouble.entry());
		c.invokestatic(doubleValueOf.entry());
		c.areturn();
		c.labelBinding(ifNotRat);
		// A Long or BigInteger widens through Number.doubleValue(); anything else throws
		// the interpreter's NUMBER operand-type report text (the checkcast alone let null
		// through
		// to an NPE naming Number internals). One instanceof on the non-double slow arm
		// only -- the Double fast arm above is byte-identical.
		c.aload(0);
		c.instanceOf(numberClass.entry());
		MethodCode.Label ifNotNumber = c.newLabel();
		c.ifeq(ifNotNumber);
		c.aload(0);
		c.checkcast(numberClass.entry());
		c.invokevirtual(numDoubleValue.methodRefEntry());
		c.invokestatic(doubleValueOf.entry());
		c.areturn();
		c.labelBinding(ifNotNumber);
		emitTypeErrThrow(c, typeErrRefs, true);
		return new NumericMethod(name, desc, c);
	}

	// _ratToDouble(BigInteger num, BigInteger den): the correctly-rounded double nearest
	// num/den (round-half-even, IEEE 754). The exact binary quotient is computed with
	// BigInteger arithmetic -- a 56-bit head plus the remainder as the sticky bit -- and
	// only then narrowed to a double, so no intermediate decimal or double rounding can
	// move the answer. Huge magnitudes answer signed infinity, tinies denormalize down
	// to signed zero. No arrays cross a branch (divide/remainder are separate calls, so
	// every reference merge is BigInteger-typed for the StackMapTable computation).
	//
	// Locals: 0=num, 1=den (params), 2=n, 3=d (magnitudes), 4=exp, 5=neg, 6=scratch
	// reference, 7=q (long), 9=aux int, 10=e. Every branch target starts with an empty
	// operand stack.
	private static NumericMethod buildRatToDouble(Utf8Constant name, Utf8Constant desc, MethodrefConstant biSignum,
			MethodrefConstant biNeg, MethodrefConstant biBitLength, MethodrefConstant biShiftLeft,
			MethodrefConstant biCompareTo, MethodrefConstant biDiv, MethodrefConstant biRem,
			MethodrefConstant biLongValue, MethodrefConstant longBitsToDouble, FieldrefConstant dblNegInf,
			FieldrefConstant dblPosInf, LongConstant c3, LongConstant c4, LongConstant c2p53, LongConstant cFracMask) {
		MethodCode c = new MethodCode();
		// n = num.signum() < 0 ? num.negate() : num
		c.aload(0);
		c.invokevirtual(biSignum.methodRefEntry());
		MethodCode.Label ifNumNonNeg = c.newLabel();
		c.ifge(ifNumNonNeg);
		c.aload(0);
		c.invokevirtual(biNeg.methodRefEntry());
		c.astore(2);
		MethodCode.Label toNAbs = c.newLabel();
		c.goto_(toNAbs);
		c.labelBinding(ifNumNonNeg);
		c.aload(0);
		c.astore(2);
		c.labelBinding(toNAbs);
		// d = den.signum() < 0 ? den.negate() : den
		c.aload(1);
		c.invokevirtual(biSignum.methodRefEntry());
		MethodCode.Label ifDenNonNeg = c.newLabel();
		c.ifge(ifDenNonNeg);
		c.aload(1);
		c.invokevirtual(biNeg.methodRefEntry());
		c.astore(3);
		MethodCode.Label toDAbs = c.newLabel();
		c.goto_(toDAbs);
		c.labelBinding(ifDenNonNeg);
		c.aload(1);
		c.astore(3);
		c.labelBinding(toDAbs);
		// neg = num.signum() < 0 ? 1 : 0
		c.aload(0);
		c.invokevirtual(biSignum.methodRefEntry());
		MethodCode.Label ifNegFalse = c.newLabel();
		c.ifge(ifNegFalse);
		c.iconst_1();
		c.istore(5);
		MethodCode.Label toNegEnd = c.newLabel();
		c.goto_(toNegEnd);
		c.labelBinding(ifNegFalse);
		c.iconst_0();
		c.istore(5);
		c.labelBinding(toNegEnd);
		// exp = n.bitLength() - d.bitLength()
		c.aload(2);
		c.invokevirtual(biBitLength.methodRefEntry());
		c.aload(3);
		c.invokevirtual(biBitLength.methodRefEntry());
		c.isub();
		c.istore(4);
		// exp = floor(log2(n/d)): decrement when the shifted denominator overshoots.
		c.iload(4);
		MethodCode.Label ifExpNeg = c.newLabel();
		c.iflt(ifExpNeg);
		c.aload(2);
		c.aload(3);
		c.iload(4);
		c.invokevirtual(biShiftLeft.methodRefEntry());
		c.invokevirtual(biCompareTo.methodRefEntry());
		MethodCode.Label ifNoDec = c.newLabel();
		c.ifge(ifNoDec);
		c.iload(4);
		c.iconst_1();
		c.isub();
		c.istore(4);
		MethodCode.Label toExpDone = c.newLabel();
		c.goto_(toExpDone);
		c.labelBinding(ifExpNeg);
		c.aload(2);
		c.iload(4);
		c.ineg();
		c.invokevirtual(biShiftLeft.methodRefEntry());
		c.aload(3);
		c.invokevirtual(biCompareTo.methodRefEntry());
		MethodCode.Label ifNoDecNeg = c.newLabel();
		c.ifge(ifNoDecNeg);
		c.iload(4);
		c.iconst_1();
		c.isub();
		c.istore(4);
		c.labelBinding(toExpDone);
		c.labelBinding(ifNoDec);
		c.labelBinding(ifNoDecNeg);
		// if (exp > 1023) return neg ? -Infinity : +Infinity
		c.iload(4);
		c.loadConstant(0x3FF);
		MethodCode.Label ifNoOverflow = c.newLabel();
		c.if_icmple(ifNoOverflow);
		emitSignedInfinity(c, 5, dblNegInf, dblPosInf);
		c.labelBinding(ifNoOverflow);
		// if (exp < -1022) goto the subnormal path
		c.iload(4);
		c.loadConstant(-1022);
		MethodCode.Label ifSubnormal = c.newLabel();
		c.if_icmplt(ifSubnormal);
		// Normal path: shift = 55 - exp; q = floor(scaled / divisor) holds 56 bits.
		c.loadConstant(55);
		c.iload(4);
		c.isub();
		c.istore(9);
		c.iload(9);
		MethodCode.Label ifShiftNeg = c.newLabel();
		c.iflt(ifShiftNeg);
		c.aload(2);
		c.iload(9);
		c.invokevirtual(biShiftLeft.methodRefEntry());
		c.astore(6);
		c.aload(6);
		c.aload(3);
		c.invokevirtual(biDiv.methodRefEntry());
		c.invokevirtual(biLongValue.methodRefEntry());
		c.lstore(7);
		c.aload(6);
		c.aload(3);
		c.invokevirtual(biRem.methodRefEntry());
		c.astore(6);
		MethodCode.Label toDivDone = c.newLabel();
		c.goto_(toDivDone);
		c.labelBinding(ifShiftNeg);
		c.aload(3);
		c.iload(9);
		c.ineg();
		c.invokevirtual(biShiftLeft.methodRefEntry());
		c.astore(6);
		c.aload(2);
		c.aload(6);
		c.invokevirtual(biDiv.methodRefEntry());
		c.invokevirtual(biLongValue.methodRefEntry());
		c.lstore(7);
		c.aload(2);
		c.aload(6);
		c.invokevirtual(biRem.methodRefEntry());
		c.astore(6);
		c.labelBinding(toDivDone);
		// round = (q & 4) != 0; round up when set and (sticky || the mantissa is odd).
		c.lload(7);
		c.ldc(c4.entry());
		c.land();
		c.lconst_0();
		c.lcmp();
		MethodCode.Label ifNoRound = c.newLabel();
		c.ifeq(ifNoRound);
		c.lload(7);
		c.ldc(c3.entry());
		c.land();
		c.lconst_0();
		c.lcmp();
		MethodCode.Label ifLowStickyRound = c.newLabel();
		c.ifne(ifLowStickyRound);
		c.aload(6);
		c.invokevirtual(biSignum.methodRefEntry());
		MethodCode.Label ifRemStickyRound = c.newLabel();
		c.ifne(ifRemStickyRound);
		c.lload(7);
		c.iconst_3();
		c.lushr();
		c.lconst_1();
		c.land();
		c.lconst_0();
		c.lcmp();
		MethodCode.Label ifNoRoundTie = c.newLabel();
		c.ifeq(ifNoRoundTie);
		// m = (q >>> 3) + 1, reached by a sticky bit or an odd tie.
		c.labelBinding(ifLowStickyRound);
		c.labelBinding(ifRemStickyRound);
		c.lload(7);
		c.iconst_3();
		c.lushr();
		c.lconst_1();
		c.ladd();
		c.lstore(7);
		MethodCode.Label toRounded = c.newLabel();
		c.goto_(toRounded);
		c.labelBinding(ifNoRound);
		c.labelBinding(ifNoRoundTie);
		// m = q >>> 3
		c.lload(7);
		c.iconst_3();
		c.lushr();
		c.lstore(7);
		c.labelBinding(toRounded);
		// e = exp; if (m == 2^53) { m >>>= 1; e++ }
		c.iload(4);
		c.istore(10);
		c.lload(7);
		c.ldc(c2p53.entry());
		c.lcmp();
		MethodCode.Label ifNoNorm = c.newLabel();
		c.ifne(ifNoNorm);
		c.lload(7);
		c.iconst_1();
		c.lushr();
		c.lstore(7);
		c.iload(10);
		c.iconst_1();
		c.iadd();
		c.istore(10);
		c.labelBinding(ifNoNorm);
		// if (e > 1023) return neg ? -Infinity : +Infinity
		c.iload(10);
		c.loadConstant(0x3FF);
		MethodCode.Label ifNoOverflow2 = c.newLabel();
		c.if_icmple(ifNoOverflow2);
		emitSignedInfinity(c, 5, dblNegInf, dblPosInf);
		c.labelBinding(ifNoOverflow2);
		// bits = (((long)(e + 1023)) << 52) | (m & mask)
		c.iload(10);
		c.loadConstant(0x3FF);
		c.iadd();
		c.i2l();
		c.loadConstant(52);
		c.lshl();
		c.lload(7);
		c.ldc(cFracMask.entry());
		c.land();
		c.lor();
		c.invokestatic(longBitsToDouble.entry());
		MethodCode.Label toSignTail = c.newLabel();
		c.goto_(toSignTail);
		// Subnormal path: k = round-half-even(n * 2^1074 / d), the mantissa directly.
		c.labelBinding(ifSubnormal);
		c.aload(2);
		c.loadConstant(0x432);
		c.invokevirtual(biShiftLeft.methodRefEntry());
		c.astore(6);
		c.aload(6);
		c.aload(3);
		c.invokevirtual(biDiv.methodRefEntry());
		c.invokevirtual(biLongValue.methodRefEntry());
		c.lstore(7);
		c.aload(6);
		c.aload(3);
		c.invokevirtual(biRem.methodRefEntry());
		c.astore(6);
		c.aload(6);
		c.iconst_1();
		c.invokevirtual(biShiftLeft.methodRefEntry());
		c.aload(3);
		c.invokevirtual(biCompareTo.methodRefEntry());
		c.istore(9);
		c.iload(9);
		MethodCode.Label ifSubUp = c.newLabel();
		c.ifgt(ifSubUp);
		c.iload(9);
		MethodCode.Label ifSubDone = c.newLabel();
		c.ifne(ifSubDone);
		c.lload(7);
		c.lconst_1();
		c.land();
		c.lconst_0();
		c.lcmp();
		MethodCode.Label ifSubDoneTie = c.newLabel();
		c.ifeq(ifSubDoneTie);
		c.labelBinding(ifSubUp);
		c.lload(7);
		c.lconst_1();
		c.ladd();
		c.lstore(7);
		c.labelBinding(ifSubDone);
		c.labelBinding(ifSubDoneTie);
		c.lload(7);
		c.invokestatic(longBitsToDouble.entry());
		c.labelBinding(toSignTail);
		// return neg ? -mag : mag
		c.iload(5);
		MethodCode.Label ifRet = c.newLabel();
		c.ifeq(ifRet);
		c.dneg();
		c.labelBinding(ifRet);
		c.dreturn();
		return new NumericMethod(name, desc, c);
	}

	// return neg != 0 ? -Infinity : +Infinity for _ratToDouble's overflow arms.
	private static void emitSignedInfinity(MethodCode c, int negSlot, FieldrefConstant dblNegInf,
			FieldrefConstant dblPosInf) {
		c.iload(negSlot);
		MethodCode.Label ifPos = c.newLabel();
		c.ifeq(ifPos);
		c.getstatic(dblNegInf.entry());
		c.dreturn();
		c.labelBinding(ifPos);
		c.getstatic(dblPosInf.entry());
		c.dreturn();
	}

	// _pow(Object base, Object e): exact rational power for an integer exponent --
	// (a/b)^e = a^e/b^e for e >= 0 and b^-e/a^-e for e < 0 (so an integer base with a
	// negative exponent yields a ratio) -- and Math.pow over the float contagion for
	// anything else. The compile-time double check (hasDoubleLiteral) only sees literals,
	// so a double or ratio arriving through a variable or a call is handled here rather
	// than cast: a Double base with an integer exponent short-circuits to Math.pow, and a
	// non-Long exponent (a Double, a ratio, a huge BigInteger) takes Math.pow(_dbl(base),
	// _dbl(e)) -- the interpreter's answer for (expt 4 1/2) = 2.0 and (expt 2 0.5). A
	// Long exponent beyond [-Integer.MAX_VALUE, Integer.MAX_VALUE] takes the same
	// Math.pow path: narrowing it with L2I would silently answer base^(e mod 2^32)
	// ((expt 2 4294967297) is Infinity, not 2), the interpreter's rule (.todo/849).
	private static NumericMethod buildPow(Utf8Constant name, Utf8Constant desc, MethodrefConstant rRatNum,
			MethodrefConstant rRatDen, MethodrefConstant rRat, MethodrefConstant biPow, ClassConstant doubleClass,
			ClassConstant longClass, MethodrefConstant longValue, ClassConstant numberClass,
			MethodrefConstant numDoubleValue, MethodrefConstant doubleValueOf, MethodrefConstant mathPow,
			MethodrefConstant rDbl, LongConstant cPowMax, LongConstant cPowMin) {
		MethodCode c = new MethodCode();
		// if (!(e instanceof Long)) return Double.valueOf(Math.pow(_dbl(base), _dbl(e)))
		c.aload(1);
		c.instanceOf(longClass.entry());
		MethodCode.Label ifLongExp = c.newLabel();
		c.ifne(ifLongExp);
		c.aload(0);
		c.invokestatic(rDbl.entry());
		c.checkcast(numberClass.entry());
		c.invokevirtual(numDoubleValue.methodRefEntry());
		c.aload(1);
		c.invokestatic(rDbl.entry());
		c.checkcast(numberClass.entry());
		c.invokevirtual(numDoubleValue.methodRefEntry());
		c.invokestatic(mathPow.entry());
		c.invokestatic(doubleValueOf.entry());
		c.areturn();
		c.labelBinding(ifLongExp);
		// if (e > Integer.MAX_VALUE || e < -Integer.MAX_VALUE) return
		// Double.valueOf(Math.pow(_dbl(base), _dbl(e)))
		c.aload(1);
		c.checkcast(longClass.entry());
		c.invokevirtual(longValue.methodRefEntry());
		c.ldc(cPowMax.entry());
		c.lcmp();
		MethodCode.Label ifTooBig = c.newLabel();
		c.ifgt(ifTooBig);
		c.aload(1);
		c.checkcast(longClass.entry());
		c.invokevirtual(longValue.methodRefEntry());
		c.ldc(cPowMin.entry());
		c.lcmp();
		MethodCode.Label ifTooSmall = c.newLabel();
		c.iflt(ifTooSmall);
		// local 2 = (int) e
		c.aload(1);
		c.checkcast(longClass.entry());
		c.invokevirtual(longValue.methodRefEntry());
		c.l2i();
		c.istore(2);
		// if (base instanceof Double) return Double.valueOf(Math.pow(base, (double) e))
		c.aload(0);
		c.instanceOf(doubleClass.entry());
		MethodCode.Label ifExact = c.newLabel();
		c.ifeq(ifExact);
		c.aload(0);
		c.checkcast(numberClass.entry());
		c.invokevirtual(numDoubleValue.methodRefEntry());
		c.iload(2);
		c.i2d();
		c.invokestatic(mathPow.entry());
		c.invokestatic(doubleValueOf.entry());
		c.areturn();
		c.labelBinding(ifExact);
		c.iload(2);
		MethodCode.Label ifNeg = c.newLabel();
		c.iflt(ifNeg);
		c.aload(0);
		c.invokestatic(rRatNum.entry());
		c.iload(2);
		c.invokevirtual(biPow.methodRefEntry());
		c.aload(0);
		c.invokestatic(rRatDen.entry());
		c.iload(2);
		c.invokevirtual(biPow.methodRefEntry());
		c.invokestatic(rRat.entry());
		c.areturn();
		c.labelBinding(ifNeg);
		c.aload(0);
		c.invokestatic(rRatDen.entry());
		c.iload(2);
		c.ineg();
		c.invokevirtual(biPow.methodRefEntry());
		c.aload(0);
		c.invokestatic(rRatNum.entry());
		c.iload(2);
		c.ineg();
		c.invokevirtual(biPow.methodRefEntry());
		c.invokestatic(rRat.entry());
		c.areturn();
		// A Long exponent beyond the int range: Math.pow over the widened doubles,
		// the same shape as the non-Long arm above.
		c.labelBinding(ifTooBig);
		c.labelBinding(ifTooSmall);
		c.aload(0);
		c.invokestatic(rDbl.entry());
		c.checkcast(numberClass.entry());
		c.invokevirtual(numDoubleValue.methodRefEntry());
		c.aload(1);
		c.invokestatic(rDbl.entry());
		c.checkcast(numberClass.entry());
		c.invokevirtual(numDoubleValue.methodRefEntry());
		c.invokestatic(mathPow.entry());
		c.invokestatic(doubleValueOf.entry());
		c.areturn();
		return new NumericMethod(name, desc, c);
	}

	// _eqv(Object a, Object b): value equality used by eq; ratios compare element-wise
	// (Object[].equals is reference equality), CHARACTERs (int[]{codePoint}) compare by
	// their sole code point (int[].equals is also reference equality, and the JVM does
	// not cache char literals like Character.valueOf(char) does), everything else uses
	// a.equals(b). A hash table (a Map) is the exception: Map.equals walks the entries,
	// so two empty tables were eq, while eql/equal on a table is identity. So are an
	// ARRAY (a List: a general vector, a mutable character vector, a string view) and a
	// STRING (a quote-framed java.lang.String), as ANSI has it: two distinct strings with
	// equal contents are not eql. Equal string LITERALS are still one object, because
	// ldc interns them -- the coalescing the interpreter and WASM reproduce. A SYMBOL is
	// a bare String and keeps comparing by name.
	private static NumericMethod buildEqv(Utf8Constant name, Utf8Constant desc, ClassConstant ratArrClass,
			ClassConstant intArrClass, ClassConstant mapClass, MethodrefConstant objEquals, StringRefs strings) {
		MethodCode c = new MethodCode();
		// CHARACTER compare (int[]{cp}): if both operands are length-1 int[], value
		// equality is (a[0] == b[0]). Emitted BEFORE the ratio and equals paths so a
		// character never falls through to Object.equals.
		c.aload(0);
		c.instanceOf(intArrClass.entry());
		MethodCode.Label ifNotChar1 = c.newLabel();
		c.ifeq(ifNotChar1);
		c.aload(1);
		c.instanceOf(intArrClass.entry());
		MethodCode.Label ifNotChar2 = c.newLabel();
		c.ifeq(ifNotChar2);
		c.aload(0);
		c.checkcast(intArrClass.entry());
		c.iconst_0();
		c.iaload();
		c.aload(1);
		c.checkcast(intArrClass.entry());
		c.iconst_0();
		c.iaload();
		MethodCode.Label ifCpNe = c.newLabel();
		c.if_icmpne(ifCpNe);
		c.iconst_1();
		c.ireturn();
		c.labelBinding(ifCpNe);
		c.iconst_0();
		c.ireturn();
		c.labelBinding(ifNotChar1);
		c.labelBinding(ifNotChar2);
		c.aload(0);
		c.instanceOf(ratArrClass.entry());
		MethodCode.Label ifObj1 = c.newLabel();
		c.ifeq(ifObj1);
		c.aload(1);
		c.instanceOf(ratArrClass.entry());
		MethodCode.Label ifObj2 = c.newLabel();
		c.ifeq(ifObj2);
		emitRatioElement(c, 0, ratArrClass, 0);
		emitRatioElement(c, 1, ratArrClass, 0);
		c.invokevirtual(objEquals.methodRefEntry());
		MethodCode.Label ifFalse1 = c.newLabel();
		c.ifeq(ifFalse1);
		emitRatioElement(c, 0, ratArrClass, 1);
		emitRatioElement(c, 1, ratArrClass, 1);
		c.invokevirtual(objEquals.methodRefEntry());
		MethodCode.Label ifFalse2 = c.newLabel();
		c.ifeq(ifFalse2);
		c.iconst_1();
		c.ireturn();
		c.labelBinding(ifFalse1);
		c.labelBinding(ifFalse2);
		c.iconst_0();
		c.ireturn();
		c.labelBinding(ifObj1);
		c.labelBinding(ifObj2);
		// if (a instanceof Map) return a == b
		c.aload(0);
		c.instanceOf(mapClass.entry());
		MethodCode.Label ifNotMap = c.newLabel();
		c.ifeq(ifNotMap);
		c.aload(0);
		c.aload(1);
		MethodCode.Label ifNotSame = c.newLabel();
		c.if_acmpne(ifNotSame);
		c.iconst_1();
		c.ireturn();
		c.labelBinding(ifNotSame);
		c.iconst_0();
		c.ireturn();
		c.labelBinding(ifNotMap);
		// if (a instanceof List || b instanceof List || isString(a)) return a == b
		MethodCode.Label toIdentity = c.newLabel();
		c.aload(0);
		c.instanceOf(strings.listClass().entry());
		c.ifne(toIdentity);
		c.aload(1);
		c.instanceOf(strings.listClass().entry());
		c.ifne(toIdentity);
		MethodCode.Label toEquals = c.newLabel();
		emitIsStringGuard(c, 0, strings, toEquals);
		c.labelBinding(toIdentity);
		c.aload(0);
		c.aload(1);
		MethodCode.Label ifNotSame2 = c.newLabel();
		c.if_acmpne(ifNotSame2);
		c.iconst_1();
		c.ireturn();
		c.labelBinding(ifNotSame2);
		c.iconst_0();
		c.ireturn();
		c.labelBinding(toEquals);
		c.aload(0);
		c.aload(1);
		c.invokevirtual(objEquals.methodRefEntry());
		c.ireturn();
		return new NumericMethod(name, desc, c);
	}

	/**
	 * The constant-pool references the string arms of {@code _eqv}/{@code _equal} read.
	 *
	 * @param stringClass {@code java/lang/String}
	 * @param listClass {@code java/util/List}, every array representation
	 * @param isEmpty {@code String.isEmpty()}
	 * @param charAt {@code String.charAt(int)}
	 */
	private record StringRefs(ClassConstant stringClass, ClassConstant listClass, MethodrefConstant isEmpty,
			MethodrefConstant charAt) {
	}

	// Falls through when the value in local slot is a STRING (a quote-framed
	// java.lang.String); otherwise branches to notString.
	private static void emitIsStringGuard(MethodCode c, int slot, StringRefs strings, MethodCode.Label notString) {
		c.aload(slot);
		c.instanceOf(strings.stringClass().entry());
		c.ifeq(notString);
		c.aload(slot);
		c.checkcast(strings.stringClass().entry());
		c.invokevirtual(strings.isEmpty().methodRefEntry());
		c.ifne(notString);
		c.aload(slot);
		c.checkcast(strings.stringClass().entry());
		c.iconst_0();
		c.invokevirtual(strings.charAt().methodRefEntry());
		c.loadConstant('"');
		c.if_icmpne(notString);
	}

	// _equal(Object a, Object b): structural equality. Two cons cells (Object[] of length
	// 2 whose head is not an Integer, distinguishing them from function references and
	// ratios) are equal when their cars and cdrs are recursively _equal; two STRINGS are
	// equal by content (a mutable character vector first rendered through _strv, when the
	// array helpers exist), which _eqv no longer answers; everything else (including
	// nil/null) delegates to _eqv, so numbers, symbols and nil compare by value. Returns
	// 1 for equal, 0 otherwise.
	private static NumericMethod buildEqual(Utf8Constant name, Utf8Constant desc, ClassConstant objArrClass,
			ClassConstant ratArrClass, ClassConstant integerClass, MethodrefConstant eqv, MethodrefConstant equal,
			@org.jspecify.annotations.Nullable ClassConstant strArrClass,
			@org.jspecify.annotations.Nullable MethodrefConstant strvMethod, StringRefs strings,
			MethodrefConstant objEquals) {
		MethodCode c = new MethodCode();
		// if (a == b) return 1 -- identity BEFORE any recursion, which is what makes a
		// cyclic value comparable to itself (a hash table storing and retrieving under
		// the SAME cyclic key terminates here). Two DISTINCT cyclic structures are
		// still undefined, as in ANSI.
		c.aload(0);
		c.aload(1);
		MethodCode.Label ifNotIdentical = c.newLabel();
		c.if_acmpne(ifNotIdentical);
		c.iconst_1();
		c.ireturn();
		c.labelBinding(ifNotIdentical);
		// if (a == null) return (b == null) ? 1 : 0;
		c.aload(0);
		MethodCode.Label ifANotNull = c.newLabel();
		c.ifnonnull(ifANotNull);
		c.aload(1);
		MethodCode.Label ifBNotNull = c.newLabel();
		c.ifnonnull(ifBNotNull);
		c.iconst_1();
		c.ireturn();
		c.labelBinding(ifBNotNull);
		c.iconst_0();
		c.ireturn();
		// a is not null
		c.labelBinding(ifANotNull);
		// Instances first (emitted only when the program can build one, so an
		// instance-free class is byte-identical): an instance is an Object[] with the
		// interned String[] layout in slot 0, and two of them are equal when they share
		// that layout and every slot is recursively _equal. This keeps compiled `equal`
		// structural over struct/CLOS instances, matching the interpreter's
		// LispInstance.equals -- and it must be checked BEFORE the cons branch, whose
		// Object[] shape an instance would otherwise satisfy.
		if (strArrClass != null) {
			emitInstanceEqual(c, objArrClass, strArrClass, equal);
		}
		// Detect both cons: instanceof Object[], not BigInteger[], head not Integer.
		MethodCode.Label notBothCons = c.newLabel();
		emitConsGuard(c, 0, objArrClass, ratArrClass, integerClass, notBothCons);
		emitConsGuard(c, 1, objArrClass, ratArrClass, integerClass, notBothCons);
		// both cons: return _equal(a[0], b[0]) && _equal(a[1], b[1])
		emitArrayElement(c, 0, objArrClass, 0);
		emitArrayElement(c, 1, objArrClass, 0);
		c.invokestatic(equal.entry());
		MethodCode.Label ifCarFalse = c.newLabel();
		c.ifeq(ifCarFalse);
		emitArrayElement(c, 0, objArrClass, 1);
		emitArrayElement(c, 1, objArrClass, 1);
		c.invokestatic(equal.entry());
		c.ireturn();
		c.labelBinding(ifCarFalse);
		c.iconst_0();
		c.ireturn();
		// not both cons: two strings compare by content, anything else through _eqv(a, b)
		c.labelBinding(notBothCons);
		if (strvMethod != null) {
			c.aload(0);
			c.invokestatic(strvMethod.entry());
			c.astore(0);
			c.aload(1);
			c.invokestatic(strvMethod.entry());
			c.astore(1);
		}
		MethodCode.Label notStrings = c.newLabel();
		emitIsStringGuard(c, 0, strings, notStrings);
		emitIsStringGuard(c, 1, strings, notStrings);
		c.aload(0);
		c.aload(1);
		c.invokevirtual(objEquals.methodRefEntry());
		c.ireturn();
		c.labelBinding(notStrings);
		c.aload(0);
		c.aload(1);
		c.invokestatic(eqv.entry());
		c.ireturn();
		return new NumericMethod(name, desc, c);
	}

	// The instance arm of _equal: if either argument is an instance, the whole answer is
	// decided here (t only when both are, over the same layout, with every slot equal),
	// so control falls through to the cons/eqv code only for two non-instances. Local 2
	// is the slot cursor.
	private static void emitInstanceEqual(MethodCode c, ClassConstant objArrClass, ClassConstant strArrClass,
			MethodrefConstant equal) {
		MethodCode.Label aNotInstance = c.newLabel();
		emitInstanceGuard(c, 0, objArrClass, strArrClass, aNotInstance);
		// a IS an instance: b must be one too, or they differ.
		MethodCode.Label toFalse = c.newLabel();
		emitInstanceGuard(c, 1, objArrClass, strArrClass, toFalse);
		// Same layout? The pool interns one String[] per tag, so identity IS tag
		// identity, and the slot count comes with it.
		emitArrayElement(c, 0, objArrClass, 0);
		emitArrayElement(c, 1, objArrClass, 0);
		c.if_acmpne(toFalse);
		// for (int i = 1; i < a.length; i++) if (!_equal(a[i], b[i])) return 0;
		c.iconst_1();
		c.istore(2);
		MethodCode.Label loopTop = c.newBoundLabel();
		c.iload(2);
		c.aload(0);
		c.checkcast(objArrClass.entry());
		c.arraylength();
		MethodCode.Label exitLoop = c.newLabel();
		c.if_icmpge(exitLoop);
		c.aload(0);
		c.checkcast(objArrClass.entry());
		c.iload(2);
		c.aaload();
		c.aload(1);
		c.checkcast(objArrClass.entry());
		c.iload(2);
		c.aaload();
		c.invokestatic(equal.entry());
		c.ifeq(toFalse);
		c.iinc(2, 1);
		c.goto_(loopTop);
		c.labelBinding(exitLoop);
		c.iconst_1();
		c.ireturn();
		c.labelBinding(toFalse);
		c.iconst_0();
		c.ireturn();
		// a is NOT an instance: b must not be either, or they differ.
		c.labelBinding(aNotInstance);
		MethodCode.Label bothPlain = c.newLabel();
		emitInstanceGuard(c, 1, objArrClass, strArrClass, bothPlain);
		c.iconst_0();
		c.ireturn();
		c.labelBinding(bothPlain);
	}

	// Branches to escape unless the value in local slot is an instance: a non-empty
	// Object[] carrying a String[] layout in slot 0.
	private static void emitInstanceGuard(MethodCode c, int slot, ClassConstant objArrClass, ClassConstant strArrClass,
			MethodCode.Label escape) {
		c.aload(slot);
		c.instanceOf(objArrClass.entry());
		c.ifeq(escape);
		c.aload(slot);
		c.checkcast(objArrClass.entry());
		c.arraylength();
		c.ifeq(escape);
		emitArrayElement(c, slot, objArrClass, 0);
		c.instanceOf(strArrClass.entry());
		c.ifeq(escape);
	}

	// Emits a cons-cell guard for the value in local slot: if it is not a cons cell (not
	// an Object[], or a BigInteger[] ratio, or an Object[] whose head is an Integer
	// function reference), branch to notCons.
	private static void emitConsGuard(MethodCode c, int slot, ClassConstant objArrClass, ClassConstant ratArrClass,
			ClassConstant integerClass, MethodCode.Label notCons) {
		c.aload(slot);
		c.instanceOf(objArrClass.entry());
		c.ifeq(notCons);
		c.aload(slot);
		c.instanceOf(ratArrClass.entry());
		c.ifne(notCons);
		c.aload(slot);
		c.checkcast(objArrClass.entry());
		c.iconst_0();
		c.aaload();
		c.instanceOf(integerClass.entry());
		c.ifne(notCons);
	}

	// Loads element 0 or 1 of the Object[] in local slot.
	private static void emitArrayElement(MethodCode c, int slot, ClassConstant objArrClass, int index) {
		c.aload(slot);
		c.checkcast(objArrClass.entry());
		c.loadConstant(index);
		c.aaload();
	}

	// _rtrunc(Object x): num/den truncating toward zero (BigInteger.divide).
	private static NumericMethod buildRatTrunc(Utf8Constant name, Utf8Constant desc, MethodrefConstant rRatNum,
			MethodrefConstant rRatDen, MethodrefConstant rNorm, MethodrefConstant biDiv) {
		MethodCode c = new MethodCode();
		c.aload(0);
		c.invokestatic(rRatNum.entry());
		c.aload(0);
		c.invokestatic(rRatDen.entry());
		c.invokevirtual(biDiv.methodRefEntry());
		c.invokestatic(rNorm.entry());
		c.areturn();
		return new NumericMethod(name, desc, c);
	}

	// _rfloor(Object x): (num - num.mod(den)) / den (the denominator is positive, so
	// mod() is non-negative). When ceilStep/ceilOp are given the result is floor + 1,
	// which is the ceiling of a (never-integer) normalized ratio.
	private static NumericMethod buildRatFloor(Utf8Constant name, Utf8Constant desc, MethodrefConstant rRatNum,
			MethodrefConstant rRatDen, MethodrefConstant rNorm, MethodrefConstant biMod, MethodrefConstant biSub,
			MethodrefConstant biDiv, @Nullable FieldrefConstant ceilOne, @Nullable MethodrefConstant ceilAdd) {
		MethodCode c = new MethodCode();
		c.aload(0);
		c.invokestatic(rRatNum.entry());
		c.astore(1);
		c.aload(0);
		c.invokestatic(rRatDen.entry());
		c.astore(2);
		c.aload(1);
		c.aload(1);
		c.aload(2);
		c.invokevirtual(biMod.methodRefEntry());
		c.invokevirtual(biSub.methodRefEntry());
		c.aload(2);
		c.invokevirtual(biDiv.methodRefEntry());
		if (ceilOne != null && ceilAdd != null) {
			c.getstatic(ceilOne.entry());
			c.invokevirtual(ceilAdd.methodRefEntry());
		}
		c.invokestatic(rNorm.entry());
		c.areturn();
		return new NumericMethod(name, desc, c);
	}

	// _rround(Object x): nearest integer, ties to even (Common Lisp round semantics).
	private static NumericMethod buildRatRound(Utf8Constant name, Utf8Constant desc, MethodrefConstant rRatNum,
			MethodrefConstant rRatDen, MethodrefConstant rNorm, MethodrefConstant biMod, MethodrefConstant biSub,
			MethodrefConstant biDiv, MethodrefConstant biMul, MethodrefConstant biShiftLeft,
			MethodrefConstant biCompareTo, MethodrefConstant biTestBit, FieldrefConstant biOne,
			MethodrefConstant biAdd) {
		MethodCode c = new MethodCode();
		// num=1, den=2, floor=3, remainder=4, cmp(int)=5
		c.aload(0);
		c.invokestatic(rRatNum.entry());
		c.astore(1);
		c.aload(0);
		c.invokestatic(rRatDen.entry());
		c.astore(2);
		c.aload(1);
		c.aload(1);
		c.aload(2);
		c.invokevirtual(biMod.methodRefEntry());
		c.invokevirtual(biSub.methodRefEntry());
		c.aload(2);
		c.invokevirtual(biDiv.methodRefEntry());
		c.astore(3);
		// remainder = num - floor * den (0 <= remainder < den)
		c.aload(1);
		c.aload(3);
		c.aload(2);
		c.invokevirtual(biMul.methodRefEntry());
		c.invokevirtual(biSub.methodRefEntry());
		c.astore(4);
		// cmp = (remainder << 1).compareTo(den)
		c.aload(4);
		c.iconst_1();
		c.invokevirtual(biShiftLeft.methodRefEntry());
		c.aload(2);
		c.invokevirtual(biCompareTo.methodRefEntry());
		c.istore(5);
		c.iload(5);
		MethodCode.Label ifUpOrTie = c.newLabel();
		c.ifge(ifUpOrTie);
		c.aload(3);
		c.invokestatic(rNorm.entry());
		c.areturn();
		c.labelBinding(ifUpOrTie);
		c.iload(5);
		MethodCode.Label ifUp1 = c.newLabel();
		c.ifne(ifUp1);
		// Tie: round to even (an odd floor rounds up).
		c.aload(3);
		c.iconst_0();
		c.invokevirtual(biTestBit.methodRefEntry());
		MethodCode.Label ifUp2 = c.newLabel();
		c.ifne(ifUp2);
		c.aload(3);
		c.invokestatic(rNorm.entry());
		c.areturn();
		c.labelBinding(ifUp1);
		c.labelBinding(ifUp2);
		c.aload(3);
		c.getstatic(biOne.entry());
		c.invokevirtual(biAdd.methodRefEntry());
		c.invokestatic(rNorm.entry());
		c.areturn();
		return new NumericMethod(name, desc, c);
	}

	// _frat(Object x): the exact rational a number IS -- a finite Double as the
	// BigInteger[2] {unscaled, 10^scale} of its exact decimal expansion (new
	// BigDecimal(double) is exact and never negatively scaled), an integer as itself,
	// and null for a NaN, an infinity, a ratio or a non-number, which decline the exact
	// route. Feeding the pair through _div is what makes the float floor family exact:
	// the quotient is then the mathematical one at any magnitude rather than the
	// rounded double a/b narrowed into a long.
	private static NumericMethod buildFrat(Utf8Constant name, Utf8Constant desc, ClassConstant doubleClass,
			ClassConstant longClass, ClassConstant bigClass, ClassConstant numberClass,
			MethodrefConstant numDoubleValue, MethodrefConstant dblIsFinite, ClassConstant bigDecClass,
			MethodrefConstant bdInitDouble, MethodrefConstant bdUnscaled, MethodrefConstant bdScale,
			FieldrefConstant biTen, MethodrefConstant biPow) {
		MethodCode c = new MethodCode();
		c.aload(0);
		c.instanceOf(doubleClass.entry());
		MethodCode.Label ifNotDouble = c.newLabel();
		c.ifeq(ifNotDouble);
		emitDoubleOfArg0(c, numberClass, numDoubleValue);
		c.invokestatic(dblIsFinite.entry());
		MethodCode.Label ifFinite = c.newLabel();
		c.ifne(ifFinite);
		c.aconst_null();
		c.areturn();
		c.labelBinding(ifFinite);
		// bd = new BigDecimal(d) in local 1, the pair in local 2.
		c.new_(bigDecClass.entry());
		c.dup();
		emitDoubleOfArg0(c, numberClass, numDoubleValue);
		c.invokespecial(bdInitDouble.entry());
		c.astore(1);
		c.iconst_2();
		c.anewarray(bigClass.entry());
		c.astore(2);
		c.aload(2);
		c.iconst_0();
		c.aload(1);
		c.invokevirtual(bdUnscaled.methodRefEntry());
		c.aastore();
		c.aload(2);
		c.iconst_1();
		c.getstatic(biTen.entry());
		c.aload(1);
		c.invokevirtual(bdScale.methodRefEntry());
		c.invokevirtual(biPow.methodRefEntry());
		c.aastore();
		c.aload(2);
		c.areturn();
		c.labelBinding(ifNotDouble);
		c.aload(0);
		c.instanceOf(longClass.entry());
		MethodCode.Label ifLong = c.newLabel();
		c.ifne(ifLong);
		c.aload(0);
		c.instanceOf(bigClass.entry());
		MethodCode.Label ifBig = c.newLabel();
		c.ifne(ifBig);
		c.aconst_null();
		c.areturn();
		c.labelBinding(ifLong);
		c.labelBinding(ifBig);
		c.aload(0);
		c.areturn();
		return new NumericMethod(name, desc, c);
	}

	// _rational(Object x): integers and ratios answer themselves; a finite Double
	// normalizes through _frat + _rat (the pair is NOT normalized, so it cannot be
	// answered directly -- _frat is the decomposition, _rat the normalization). A
	// complex or any other non-real takes the real funnel (a REAL operand-type
	// report, a type-error like the interpreter's throw); a NaN or an infinity throws the
	// interpreter's
	// non-finite text instead.
	private static NumericMethod buildRational(Utf8Constant name, Utf8Constant desc, ClassConstant longClass,
			ClassConstant bigClass, ClassConstant doubleClass, ClassConstant ratArrClass, MethodrefConstant rFrat,
			MethodrefConstant rRat, TypeErrRefs typeErrRefs, ConstantPool.StringConstant nonFiniteStr,
			@Nullable ClassConstant rcClass, @Nullable FieldrefConstant hasComplex) {
		MethodCode c = new MethodCode();
		if (rcClass != null) {
			MethodCode.Label noHolder = emitNoHolderJump(c, hasComplex);
			c.aload(0);
			c.instanceOf(rcClass.entry());
			MethodCode.Label ifNotComplex = c.newLabel();
			c.ifeq(ifNotComplex);
			emitRealErrThrow(c, typeErrRefs);
			c.labelBinding(ifNotComplex);
			c.labelBinding(noHolder);
		}
		// A ratio is already exact.
		c.aload(0);
		c.instanceOf(ratArrClass.entry());
		MethodCode.Label ifNotRat = c.newLabel();
		c.ifeq(ifNotRat);
		c.aload(0);
		c.areturn();
		c.labelBinding(ifNotRat);
		// So is an integer.
		c.aload(0);
		c.instanceOf(longClass.entry());
		MethodCode.Label ifLong = c.newLabel();
		c.ifne(ifLong);
		c.aload(0);
		c.instanceOf(bigClass.entry());
		MethodCode.Label ifNotInt = c.newLabel();
		c.ifeq(ifNotInt);
		c.labelBinding(ifLong);
		c.aload(0);
		c.areturn();
		c.labelBinding(ifNotInt);
		// A Double goes through _frat, which answers null for a NaN or an infinity.
		c.aload(0);
		c.instanceOf(doubleClass.entry());
		MethodCode.Label ifNotDouble = c.newLabel();
		c.ifeq(ifNotDouble);
		c.aload(0);
		c.invokestatic(rFrat.entry());
		c.dup();
		MethodCode.Label ifFinite = c.newLabel();
		c.ifnonnull(ifFinite);
		c.pop();
		c.new_(typeErrRefs.rte().entry());
		c.dup();
		c.ldc(nonFiniteStr.entry());
		c.invokespecial(typeErrRefs.rteInit().entry());
		c.athrow();
		c.labelBinding(ifFinite);
		// The verifier only sees _frat's Object descriptor, so the pair is cast
		// to its array class before the elements load (a bare aaload on the
		// merged Object is too lossy for the verifier).
		c.checkcast(ratArrClass.entry());
		c.astore(1);
		c.aload(1);
		c.iconst_0();
		c.aaload();
		c.checkcast(bigClass.entry());
		c.aload(1);
		c.iconst_1();
		c.aaload();
		c.checkcast(bigClass.entry());
		c.invokestatic(rRat.entry());
		c.areturn();
		c.labelBinding(ifNotDouble);
		emitRealErrThrow(c, typeErrRefs);
		return new NumericMethod(name, desc, c);
	}

	/** Pushes {@code ((Number) arg0).doubleValue()}. */
	private static void emitDoubleOfArg0(MethodCode c, ClassConstant numberClass, MethodrefConstant numDoubleValue) {
		c.aload(0);
		c.checkcast(numberClass.entry());
		c.invokevirtual(numDoubleValue.methodRefEntry());
	}

	// _fdiv(Object a, Object b, int mode): the floor family's quotient when a float is
	// involved, or null to decline (no float operand, a ratio, a non-finite divisor over
	// a
	// non-finite or zero dividend, a zero float divisor -- all of which keep the ordinary
	// route, the last so the non-trapping (/ x 0.0) policy stands). A NaN or infinite
	// dividend over a finite nonzero divisor signals: its quotient has no integer. Both
	// operands become the exact rationals
	// they are and divide through _div, which is exact at any magnitude; an even
	// division answers an integer already and everything else rounds through the
	// rational rounder the mode names.
	//
	// An INFINITE divisor is a separate case handled by sign alone, before the exact
	// rational route (which would decline: infinity is not a rational and _frat says so).
	// With a finite nonzero dividend, a/b is an infinitesimal whose magnitude is always
	// under 1/2 -- truncate and round are always 0, and floor/ceiling read off whether
	// the
	// dividend and the infinite divisor agree in sign (0 and 1 when they do, -1 and 0
	// when
	// they do not: floor rounds the infinitesimal down, ceiling up). An exact-zero or
	// non-finite dividend, or a ratio operand, still declines to the old f64 route, which
	// already answers correctly there (0/infinity is exactly zero, not an infinitesimal).
	// See .kb/linalg-simd.md, "mod/rem".
	private static NumericMethod buildFdiv(Utf8Constant name, Utf8Constant desc, ClassConstant doubleClass,
			ClassConstant numberClass, MethodrefConstant numDoubleValue, ClassConstant ratArrClass,
			MethodrefConstant rFrat, MethodrefConstant rDiv, MethodrefConstant rRatTrunc, MethodrefConstant rRatFloor,
			MethodrefConstant rRatCeil, MethodrefConstant rRatRound, MethodrefConstant dblIsInfinite,
			MethodrefConstant dblIsFinite, ClassConstant longClass, MethodrefConstant longValue, ClassConstant bigClass,
			MethodrefConstant biSignum, MethodrefConstant longValueOf, TypeErrRefs typeErrRefs,
			ConstantPool.StringConstant nonFiniteStr) {
		MethodCode c = new MethodCode();
		c.aload(0);
		c.instanceOf(doubleClass.entry());
		c.aload(1);
		c.instanceOf(doubleClass.entry());
		c.ior();
		MethodCode.Label ifFloat = c.newLabel();
		c.ifne(ifFloat);
		c.aconst_null();
		c.areturn();
		c.labelBinding(ifFloat);
		// A zero float divisor declines: (/ x 0.0) is an infinity here, not a signal.
		c.aload(1);
		c.instanceOf(doubleClass.entry());
		MethodCode.Label ifNotFloatDivisor = c.newLabel();
		c.ifeq(ifNotFloatDivisor);
		c.aload(1);
		c.checkcast(numberClass.entry());
		c.invokevirtual(numDoubleValue.methodRefEntry());
		// An infinite divisor: settle the quotient by sign (local 6/7 hold the two
		// signs) rather than falling through to _frat, which declines on a non-finite
		// operand. Every sub-path below returns, so control never merges back here.
		c.dup2();
		c.invokestatic(dblIsInfinite.entry());
		MethodCode.Label ifNotInfiniteDivisor = c.newLabel();
		c.ifeq(ifNotInfiniteDivisor);
		emitInfiniteDivisorQuotient(c, doubleClass, numDoubleValue, dblIsFinite, longClass, longValue, bigClass,
				biSignum, longValueOf);
		c.labelBinding(ifNotInfiniteDivisor);
		c.dconst_0();
		c.dcmpl();
		MethodCode.Label ifNonZeroDivisor = c.newLabel();
		c.ifne(ifNonZeroDivisor);
		c.aconst_null();
		c.areturn();
		c.labelBinding(ifNotFloatDivisor);
		c.labelBinding(ifNonZeroDivisor);
		c.aload(0);
		c.invokestatic(rFrat.entry());
		c.astore(3);
		c.aload(3);
		MethodCode.Label ifDividendOk = c.newLabel();
		c.ifnonnull(ifDividendOk);
		// A NaN or an infinite float dividend over a finite nonzero divisor has a
		// non-finite quotient: no integer to answer, so it signals -- the interpreter's
		// text. (A ratio dividend still declines.) The one-argument call site relies on
		// this: its out-of-long-range arm calls here over a divisor of one and never
		// sees a null.
		c.aload(0);
		c.instanceOf(doubleClass.entry());
		MethodCode.Label ifRatioDividend = c.newLabel();
		c.ifeq(ifRatioDividend);
		c.new_(typeErrRefs.rte().entry());
		c.dup();
		c.ldc(nonFiniteStr.entry());
		c.invokespecial(typeErrRefs.rteInit().entry());
		c.athrow();
		c.labelBinding(ifRatioDividend);
		c.aconst_null();
		c.areturn();
		c.labelBinding(ifDividendOk);
		c.aload(1);
		c.invokestatic(rFrat.entry());
		c.astore(4);
		c.aload(4);
		MethodCode.Label ifDivisorOk = c.newLabel();
		c.ifnonnull(ifDivisorOk);
		c.aconst_null();
		c.areturn();
		c.labelBinding(ifDivisorOk);
		c.aload(3);
		c.aload(4);
		c.invokestatic(rDiv.entry());
		c.astore(5);
		c.aload(5);
		c.instanceOf(ratArrClass.entry());
		MethodCode.Label ifRatio = c.newLabel();
		c.ifne(ifRatio);
		c.aload(5);
		c.areturn();
		c.labelBinding(ifRatio);
		emitModeArm(c, 1, rRatFloor);
		emitModeArm(c, 2, rRatCeil);
		emitModeArm(c, 3, rRatRound);
		c.aload(5);
		c.invokestatic(rRatTrunc.entry());
		c.areturn();
		// Locals 6 (dividend sign) and 7 (divisor sign) belong to the infinite-divisor
		// arm above; nothing past it uses a local higher than 5.
		return new NumericMethod(name, desc, c);
	}

	/**
	 * Settles {@code _fdiv}'s quotient for an infinite divisor by sign, with the divisor
	 * (a non-finite, non-zero double) still on the operand stack. Stores the divisor's
	 * sign in local 6, works out the dividend's sign into local 7 (declining -- returning
	 * {@code null}, which falls back to the ordinary f64 route -- for a ratio operand, a
	 * non-finite float dividend, or an exact-zero dividend: {@code 0/infinity} is exactly
	 * zero, not an infinitesimal, and the old route already answers that correctly), and
	 * returns {@code Long.valueOf} of the mode's answer: 0 for {@code TRUNCATE}/
	 * {@code ROUND} always, 0 (same sign) or -1 (different) for {@code FLOOR} (mode 1), 1
	 * (same) or 0 (different) for {@code CEILING} (mode 2). Every path returns, so the
	 * caller needs no goto back to its own flow.
	 */
	private static void emitInfiniteDivisorQuotient(MethodCode c, ClassConstant doubleClass,
			MethodrefConstant numDoubleValue, MethodrefConstant dblIsFinite, ClassConstant longClass,
			MethodrefConstant longValue, ClassConstant bigClass, MethodrefConstant biSignum,
			MethodrefConstant longValueOf) {
		// local 6 = signum(b), from the divisor double already on the stack.
		c.dconst_0();
		c.dcmpl();
		c.istore(6);
		// local 7 = signum(a). Each arm below stores it and jumps to afterSignA; a ratio
		// (the final catch-all) or a non-finite float dividend declines directly.
		MethodCode.Label afterSignA = c.newLabel();

		// Long.
		c.aload(0);
		c.instanceOf(longClass.entry());
		MethodCode.Label ifNotLong = c.newLabel();
		c.ifeq(ifNotLong);
		c.aload(0);
		c.checkcast(longClass.entry());
		c.invokevirtual(longValue.methodRefEntry());
		c.lconst_0();
		c.lcmp();
		c.istore(7);
		c.goto_(afterSignA);
		c.labelBinding(ifNotLong);

		// BigInteger.
		c.aload(0);
		c.instanceOf(bigClass.entry());
		MethodCode.Label ifNotBig = c.newLabel();
		c.ifeq(ifNotBig);
		c.aload(0);
		c.checkcast(bigClass.entry());
		c.invokevirtual(biSignum.methodRefEntry());
		c.istore(7);
		c.goto_(afterSignA);
		c.labelBinding(ifNotBig);

		// Double: finite required, else decline.
		c.aload(0);
		c.instanceOf(doubleClass.entry());
		MethodCode.Label ifNotDouble = c.newLabel();
		c.ifeq(ifNotDouble);
		c.aload(0);
		c.checkcast(doubleClass.entry());
		c.invokevirtual(numDoubleValue.methodRefEntry());
		c.dup2();
		c.invokestatic(dblIsFinite.entry());
		MethodCode.Label ifFiniteA = c.newLabel();
		c.ifne(ifFiniteA);
		c.pop2();
		c.aconst_null();
		c.areturn();
		c.labelBinding(ifFiniteA);
		c.dconst_0();
		c.dcmpl();
		c.istore(7);
		c.goto_(afterSignA);
		c.labelBinding(ifNotDouble);

		// Anything else (a ratio): decline.
		c.aconst_null();
		c.areturn();

		c.labelBinding(afterSignA);

		// An exact-zero dividend declines too.
		c.iload(7);
		MethodCode.Label signANonZero = c.newLabel();
		c.ifne(signANonZero);
		c.aconst_null();
		c.areturn();
		c.labelBinding(signANonZero);

		c.iload(7);
		c.iload(6);
		MethodCode.Label ifSameSign = c.newLabel();
		c.if_icmpeq(ifSameSign);

		// Different sign: floor (mode 1) is -1, everything else (truncate/round) is 0.
		c.iload(2);
		c.iconst_1();
		MethodCode.Label diffElse = c.newLabel();
		c.if_icmpne(diffElse);
		c.lconst_1();
		c.lneg();
		c.invokestatic(longValueOf.entry());
		c.areturn();
		c.labelBinding(diffElse);
		c.lconst_0();
		c.invokestatic(longValueOf.entry());
		c.areturn();

		c.labelBinding(ifSameSign);
		// Same sign: ceiling (mode 2) is 1, everything else (truncate/round) is 0.
		c.iload(2);
		c.iconst_2();
		MethodCode.Label sameElse = c.newLabel();
		c.if_icmpne(sameElse);
		c.lconst_1();
		c.invokestatic(longValueOf.entry());
		c.areturn();
		c.labelBinding(sameElse);
		c.lconst_0();
		c.invokestatic(longValueOf.entry());
		c.areturn();
	}

	/** {@code if (mode == n) return rounder(local 5);} inside {@code _fdiv}. */
	private static void emitModeArm(MethodCode c, int mode, MethodrefConstant rounder) {
		c.iload(2);
		c.loadConstant(mode);
		MethodCode.Label skip = c.newLabel();
		c.if_icmpne(skip);
		c.aload(5);
		c.invokestatic(rounder.entry());
		c.areturn();
		c.labelBinding(skip);
	}

	// Emits the two `instanceof BigInteger[]` guards that jump to the rational path.
	private static void emitRatioGuard(MethodCode c, ClassConstant ratArrClass, MethodCode.Label ratio) {
		c.aload(0);
		c.instanceOf(ratArrClass.entry());
		c.ifne(ratio);
		c.aload(1);
		c.instanceOf(ratArrClass.entry());
		c.ifne(ratio);
	}

	// _logand/_logior/_logxor(Object a, Object b): the two's-complement bitwise op. Two
	// Longs answer with the matching long opcode -- 64-bit two's complement agrees with
	// BigInteger's infinite two's complement on every value a long can hold -- so a
	// (unsigned-byte 32) mask costs no BigInteger allocation. Any other operand mix
	// falls back to the exact BigInteger operation.
	private static NumericMethod buildLogOp(Utf8Constant name, Utf8Constant desc, ClassConstant longClass,
			MethodrefConstant longValue, MethodrefConstant longValueOf, MethodrefConstant rBig, MethodrefConstant rNorm,
			MethodrefConstant biOp, Consumer<MethodCode> longOp) {
		MethodCode c = new MethodCode();
		MethodCode.Label toSlow = c.newLabel();
		emitLongLongGuard(c, longClass, toSlow);
		emitUnboxLong(c, 0, longClass, longValue);
		emitUnboxLong(c, 1, longClass, longValue);
		longOp.accept(c);
		c.invokestatic(longValueOf.entry());
		c.areturn();
		c.labelBinding(toSlow);
		emitBigBinary(c, rBig, biOp, rNorm);
		return new NumericMethod(name, desc, c);
	}

	// _lognot(Object a): ~a for a Long (emitted as `a xor -1`), BigInteger.not otherwise.
	private static NumericMethod buildLogNot(Utf8Constant name, Utf8Constant desc, ClassConstant longClass,
			MethodrefConstant longValue, MethodrefConstant longValueOf, MethodrefConstant rBig, MethodrefConstant rNorm,
			MethodrefConstant biNot) {
		MethodCode c = new MethodCode();
		c.aload(0);
		c.instanceOf(longClass.entry());
		MethodCode.Label ifSlow = c.newLabel();
		c.ifeq(ifSlow);
		emitUnboxLong(c, 0, longClass, longValue);
		c.iconst_m1();
		c.i2l();
		c.lxor();
		c.invokestatic(longValueOf.entry());
		c.areturn();
		c.labelBinding(ifSlow);
		c.aload(0);
		c.invokestatic(rBig.entry());
		c.invokevirtual(biNot.methodRefEntry());
		c.invokestatic(rNorm.entry());
		c.areturn();
		return new NumericMethod(name, desc, c);
	}

	// _ash(Object a, Object count): shift left for a non-negative count, arithmetic right
	// shift otherwise. Both operands Long: a right shift always fits (>= 64 saturates to
	// 0 or -1), a left shift is taken only when it round-trips back through the shift, so
	// an overflowing one falls to BigInteger.shiftLeft like every other operand mix. The
	// count is compared as a long FIRST and only narrowed once the comparison proves the
	// narrowing exact: narrowing first wraps a huge negative count positive and builds a
	// monster bignum (MISC.47/.48). Past Integer.MAX_VALUE a zero value stays zero and
	// anything else is a runaway allocation, which signals.
	//
	// Locals: 0=a, 1=count, 2/3=long a, 4=int count, 6/7=long result, 8/9=long count.
	// All are pre-initialized so every path reaching a slow tail carries the same frame.
	private static NumericMethod buildAsh(Utf8Constant name, Utf8Constant desc, ClassConstant longClass,
			MethodrefConstant longValue, MethodrefConstant longValueOf, MethodrefConstant rBig, MethodrefConstant rNorm,
			MethodrefConstant biShiftLeft, MethodrefConstant biSignum, ClassConstant arithEx, MethodrefConstant aeInit,
			ConstantPool.StringConstant tooLargeStr) {
		MethodCode c = new MethodCode();
		c.lconst_0();
		c.lstore(2);
		c.iconst_0();
		c.istore(4);
		c.lconst_0();
		c.lstore(6);
		c.lconst_0();
		c.lstore(8);
		// the count takes the ranged path only when it is a Long
		c.aload(1);
		c.instanceOf(longClass.entry());
		MethodCode.Label ifSlowCount = c.newLabel();
		c.ifeq(ifSlowCount);
		emitUnboxLong(c, 1, longClass, longValue);
		c.lstore(8);
		// ((long) (int) count) == count, else the narrowing below would wrap
		c.lload(8);
		c.l2i();
		c.i2l();
		c.lload(8);
		c.lcmp();
		MethodCode.Label ifCountExact = c.newLabel();
		c.ifeq(ifCountExact);
		// outside the int range the count's own sign decides the side
		c.lload(8);
		c.lconst_0();
		c.lcmp();
		MethodCode.Label ifHugeNeg = c.newLabel();
		c.iflt(ifHugeNeg);
		MethodCode.Label goHuge = c.newLabel();
		c.goto_(goHuge);
		c.labelBinding(ifCountExact);
		c.lload(8);
		c.l2i();
		c.istore(4);
		// the value takes the fast path only when it is a Long
		c.aload(0);
		c.instanceOf(longClass.entry());
		MethodCode.Label ifSlowValue = c.newLabel();
		c.ifeq(ifSlowValue);
		emitUnboxLong(c, 0, longClass, longValue);
		c.lstore(2);
		// if (count > 0) goto left
		c.iload(4);
		MethodCode.Label ifLeft = c.newLabel();
		c.ifgt(ifLeft);
		// count <= -64: the whole value shifts out, leaving 0 (or -1 when negative)
		c.iload(4);
		c.loadConstant(-64);
		MethodCode.Label ifRightShift = c.newLabel();
		c.if_icmpgt(ifRightShift);
		c.lload(2);
		c.lconst_0();
		c.lcmp();
		MethodCode.Label ifNegative = c.newLabel();
		c.iflt(ifNegative);
		c.lconst_0();
		c.invokestatic(longValueOf.entry());
		c.areturn();
		c.labelBinding(ifNegative);
		c.iconst_m1();
		c.i2l();
		c.invokestatic(longValueOf.entry());
		c.areturn();
		// -64 < count <= 0: a >> -count
		c.labelBinding(ifRightShift);
		c.lload(2);
		c.iconst_0();
		c.iload(4);
		c.isub();
		c.lshr();
		c.invokestatic(longValueOf.entry());
		c.areturn();
		// count > 0: shift left when the result round-trips (i.e. did not overflow)
		c.labelBinding(ifLeft);
		c.iload(4);
		c.loadConstant(64);
		MethodCode.Label ifWide = c.newLabel();
		c.if_icmpge(ifWide);
		c.lload(2);
		c.iload(4);
		c.lshl();
		c.lstore(6);
		c.lload(6);
		c.iload(4);
		c.lshr();
		c.lload(2);
		c.lcmp();
		MethodCode.Label ifOverflow = c.newLabel();
		c.ifne(ifOverflow);
		c.lload(6);
		c.invokestatic(longValueOf.entry());
		c.areturn();
		c.labelBinding(ifSlowValue);
		c.labelBinding(ifWide);
		c.labelBinding(ifOverflow);
		c.aload(0);
		c.invokestatic(rBig.entry());
		c.iload(4);
		c.invokevirtual(biShiftLeft.methodRefEntry());
		c.invokestatic(rNorm.entry());
		c.areturn();
		// a non-Long count: a bignum count is always past the saturation width, so
		// its sign routes to the huge arms (ASH.5 reaches (ash j j) with
		// j = -(2^64)); anything else is not an integer and rBig signals the type
		// error
		c.labelBinding(ifSlowCount);
		c.aload(1);
		c.invokestatic(rBig.entry());
		c.invokevirtual(biSignum.methodRefEntry());
		MethodCode.Label ifBigNegCount = c.newLabel();
		c.iflt(ifBigNegCount);
		MethodCode.Label goBigPosCount = c.newLabel();
		c.goto_(goBigPosCount);
		// a huge negative count shifts the whole value out, leaving its sign
		c.labelBinding(ifHugeNeg);
		c.labelBinding(ifBigNegCount);
		c.aload(0);
		c.invokestatic(rBig.entry());
		c.invokevirtual(biSignum.methodRefEntry());
		MethodCode.Label ifNegOne = c.newLabel();
		c.iflt(ifNegOne);
		c.lconst_0();
		c.invokestatic(longValueOf.entry());
		c.areturn();
		c.labelBinding(ifNegOne);
		c.iconst_m1();
		c.i2l();
		c.invokestatic(longValueOf.entry());
		c.areturn();
		// a huge positive count: zero stays zero, anything else is a runaway
		// allocation and signals
		c.labelBinding(goHuge);
		c.labelBinding(goBigPosCount);
		c.aload(0);
		c.invokestatic(rBig.entry());
		c.invokevirtual(biSignum.methodRefEntry());
		MethodCode.Label ifZero = c.newLabel();
		c.ifeq(ifZero);
		c.new_(arithEx.entry());
		c.dup();
		c.ldc(tooLargeStr.entry());
		c.invokespecial(aeInit.entry());
		c.athrow();
		c.labelBinding(ifZero);
		c.lconst_0();
		c.invokestatic(longValueOf.entry());
		c.areturn();
		return new NumericMethod(name, desc, c);
	}

	// _intlen(Object a): integer-length, i.e. BigInteger.bitLength -- the bit count of
	// the minimal two's-complement representation, sign bit excluded. For a Long that is
	// 64 - numberOfLeadingZeros of the value (of its complement when negative).
	// Locals: 0=a, 1/2=long a (pre-initialized so both paths share a frame).
	private static NumericMethod buildIntegerLength(Utf8Constant name, Utf8Constant desc, ClassConstant longClass,
			MethodrefConstant longValue, MethodrefConstant longValueOf, MethodrefConstant rBig,
			MethodrefConstant biBitLength, MethodrefConstant longNlz) {
		MethodCode c = new MethodCode();
		c.lconst_0();
		c.lstore(1);
		c.aload(0);
		c.instanceOf(longClass.entry());
		MethodCode.Label ifSlow = c.newLabel();
		c.ifeq(ifSlow);
		emitUnboxLong(c, 0, longClass, longValue);
		c.lstore(1);
		c.lload(1);
		c.lconst_0();
		c.lcmp();
		MethodCode.Label ifNonNegative = c.newLabel();
		c.ifge(ifNonNegative);
		c.lload(1);
		c.iconst_m1();
		c.i2l();
		c.lxor();
		c.lstore(1);
		c.labelBinding(ifNonNegative);
		c.loadConstant(64);
		c.lload(1);
		c.invokestatic(longNlz.entry());
		c.isub();
		c.i2l();
		c.invokestatic(longValueOf.entry());
		c.areturn();
		c.labelBinding(ifSlow);
		c.aload(0);
		c.invokestatic(rBig.entry());
		c.invokevirtual(biBitLength.methodRefEntry());
		c.i2l();
		c.invokestatic(longValueOf.entry());
		c.areturn();
		return new NumericMethod(name, desc, c);
	}

	// _lbitp(Object n, Object index): logbitp as an int 0/1. Both operands Long and the
	// index non-negative: an index at or beyond 63 reads the sign, anything below reads
	// the bit. A negative index (which BigInteger.testBit signals on) and every other
	// operand mix keep the BigInteger path, so the signalling behavior is unchanged.
	// Locals: 0=n, 1=index, 2=int index, 3/4=long n (pre-initialized to share a frame).
	private static NumericMethod buildLogbitp(Utf8Constant name, Utf8Constant desc, ClassConstant longClass,
			MethodrefConstant longValue, MethodrefConstant rBig, MethodrefConstant biTestBit) {
		MethodCode c = new MethodCode();
		c.iconst_0();
		c.istore(2);
		c.lconst_0();
		c.lstore(3);
		MethodCode.Label toSlow = c.newLabel();
		emitLongLongGuard(c, longClass, toSlow);
		emitUnboxLong(c, 1, longClass, longValue);
		c.l2i();
		c.istore(2);
		emitUnboxLong(c, 0, longClass, longValue);
		c.lstore(3);
		c.iload(2);
		MethodCode.Label ifNegativeIndex = c.newLabel();
		c.iflt(ifNegativeIndex);
		// An index at or past the sign bit reads the sign: clamp it to 63.
		c.iload(2);
		c.loadConstant(63);
		MethodCode.Label ifInRange = c.newLabel();
		c.if_icmplt(ifInRange);
		c.loadConstant(63);
		c.istore(2);
		c.labelBinding(ifInRange);
		c.lload(3);
		c.iload(2);
		c.lushr();
		c.lconst_1();
		c.land();
		c.l2i();
		c.ireturn();
		c.labelBinding(toSlow);
		c.labelBinding(ifNegativeIndex);
		c.aload(0);
		c.invokestatic(rBig.entry());
		emitUnboxLong(c, 1, longClass, longValue);
		c.l2i();
		c.invokevirtual(biTestBit.methodRefEntry());
		c.ireturn();
		return new NumericMethod(name, desc, c);
	}

	/**
	 * Builds {@code _fixdec(value, places, intDigits, plus) -> String}: the
	 * {@code %fixed-decimal} primitive, step for step the algorithm of
	 * {@link am.ik.rontolisp.compiler.FixedDecimal} (which is what the interpreter runs
	 * and what the WASM {@code _fixed_dec} emits), with the frame quotes a compiled
	 * string carries as storage added around the text.
	 *
	 * <p>
	 * Nothing here reaches for {@code String.format}: the scaling {@code 10^places} has
	 * to be the same repeated multiplication every backend does, the rounding the same
	 * half-to-even {@link Math#rint}, and the {@code double}-to-{@code long} conversion
	 * the same saturating one, or the four backends stop printing the same digits.
	 */
	private static NumericMethod buildFixedDec(ConstantPool cp, Utf8Constant name, Utf8Constant desc,
			ClassConstant mathClass, ClassConstant longClass, ClassConstant numberClass,
			MethodrefConstant numDoubleValue, MethodrefConstant rDbl) {
		ClassConstant stringClass = cp.addClass(cp.addUtf8("java/lang/String"));
		MethodrefConstant mathRint = cp.addMethodref(mathClass,
				cp.addNameAndType(cp.addUtf8("rint"), cp.addUtf8("(D)D")));
		MethodrefConstant mathAbsD = cp.addMethodref(mathClass,
				cp.addNameAndType(cp.addUtf8("abs"), cp.addUtf8("(D)D")));
		MethodrefConstant mathMaxI = cp.addMethodref(mathClass,
				cp.addNameAndType(cp.addUtf8("max"), cp.addUtf8("(II)I")));
		MethodrefConstant mathMinI = cp.addMethodref(mathClass,
				cp.addNameAndType(cp.addUtf8("min"), cp.addUtf8("(II)I")));
		MethodrefConstant longToString = cp.addMethodref(longClass,
				cp.addNameAndType(cp.addUtf8("toString"), cp.addUtf8("(J)Ljava/lang/String;")));
		MethodrefConstant numIntValue = cp.addMethodref(numberClass,
				cp.addNameAndType(cp.addUtf8("intValue"), cp.addUtf8("()I")));
		MethodrefConstant strLength = cp.addMethodref(stringClass,
				cp.addNameAndType(cp.addUtf8("length"), cp.addUtf8("()I")));
		MethodrefConstant strConcat = cp.addMethodref(stringClass,
				cp.addNameAndType(cp.addUtf8("concat"), cp.addUtf8("(Ljava/lang/String;)Ljava/lang/String;")));
		MethodrefConstant strSub2 = cp.addMethodref(stringClass,
				cp.addNameAndType(cp.addUtf8("substring"), cp.addUtf8("(II)Ljava/lang/String;")));
		MethodrefConstant strSub1 = cp.addMethodref(stringClass,
				cp.addNameAndType(cp.addUtf8("substring"), cp.addUtf8("(I)Ljava/lang/String;")));
		ConstantPool.DoubleConstant ten = cp.addDouble(10.0);

		final int x = 4, d = 6, n = 7, scale = 8, i = 10, s = 11, min = 12, out = 13, split = 14;
		MethodCode a = new MethodCode();
		// x = ((Number) _dbl(value)).doubleValue()
		a.aload(0);
		a.invokestatic(rDbl.entry());
		a.checkcast(numberClass.entry());
		a.invokevirtual(numDoubleValue.methodRefEntry());
		a.dstore(x);
		// d = min(max(places, 0), MAX_DIGITS); n likewise
		emitClampedIntArg(a, 1, d, numberClass, numIntValue, mathMaxI, mathMinI);
		emitClampedIntArg(a, 2, n, numberClass, numIntValue, mathMaxI, mathMinI);
		// scale = 1.0; for (i = 0; i < d; i++) scale *= 10.0
		a.dconst_1();
		a.dstore(scale);
		a.loadConstant(0);
		a.istore(i);
		MethodCode.Label scaleTop = a.newLabel(), scaleEnd = a.newLabel();
		a.labelBinding(scaleTop);
		a.iload(i);
		a.iload(d);
		a.if_icmpge(scaleEnd);
		a.dload(scale);
		a.ldc(ten.entry());
		a.dmul();
		a.dstore(scale);
		a.iinc(i, 1);
		a.goto_(scaleTop);
		a.labelBinding(scaleEnd);
		// s = Long.toString((long) Math.abs(Math.rint(x * scale)))
		a.dload(x);
		a.dload(scale);
		a.dmul();
		a.invokestatic(mathRint.entry());
		a.invokestatic(mathAbsD.entry());
		a.d2l();
		a.invokestatic(longToString.entry());
		a.astore(s);
		// min = max(d + 1, n + d); while (s.length() < min) s = "0".concat(s)
		a.iload(d);
		a.loadConstant(1);
		a.iadd();
		a.iload(n);
		a.iload(d);
		a.iadd();
		a.invokestatic(mathMaxI.entry());
		a.istore(min);
		MethodCode.Label padTop = a.newLabel(), padEnd = a.newLabel();
		a.labelBinding(padTop);
		a.aload(s);
		a.invokevirtual(strLength.methodRefEntry());
		a.iload(min);
		a.if_icmpge(padEnd);
		a.ldc(cp.addString("0").entry());
		a.aload(s);
		a.invokevirtual(strConcat.methodRefEntry());
		a.astore(s);
		a.goto_(padTop);
		a.labelBinding(padEnd);
		// split = s.length() - d
		a.aload(s);
		a.invokevirtual(strLength.methodRefEntry());
		a.iload(d);
		a.isub();
		a.istore(split);
		// out = (x < 0.0) ? "\"-" : (plus != null ? "\"+" : "\"") -- the opening frame
		// quote and the sign in one constant. dcmpg answers 1 for a NaN, which is not
		// negative, exactly as `value < 0.0` is false for one.
		MethodCode.Label negative = a.newLabel(), plain = a.newLabel(), haveSign = a.newLabel();
		a.dload(x);
		a.dconst_0();
		a.dcmpg();
		a.iflt(negative);
		a.aload(3);
		a.ifnull(plain);
		a.ldc(cp.addString("\"+").entry());
		a.goto_(haveSign);
		a.labelBinding(plain);
		a.ldc(cp.addString("\"").entry());
		a.goto_(haveSign);
		a.labelBinding(negative);
		a.ldc(cp.addString("\"-").entry());
		a.labelBinding(haveSign);
		a.astore(out);
		// out = out.concat(s.substring(0, split))
		a.aload(out);
		a.aload(s);
		a.loadConstant(0);
		a.iload(split);
		a.invokevirtual(strSub2.methodRefEntry());
		a.invokevirtual(strConcat.methodRefEntry());
		a.astore(out);
		// if (d > 0) out = out.concat(".").concat(s.substring(split))
		MethodCode.Label noPoint = a.newLabel();
		a.iload(d);
		a.ifle(noPoint);
		a.aload(out);
		a.ldc(cp.addString(".").entry());
		a.invokevirtual(strConcat.methodRefEntry());
		a.aload(s);
		a.iload(split);
		a.invokevirtual(strSub1.methodRefEntry());
		a.invokevirtual(strConcat.methodRefEntry());
		a.astore(out);
		a.labelBinding(noPoint);
		// return out.concat("\"") -- the closing frame quote
		a.aload(out);
		a.ldc(cp.addString("\"").entry());
		a.invokevirtual(strConcat.methodRefEntry());
		a.areturn();
		return new NumericMethod(name, desc, a);
	}

	// Loads argument slot `arg` as an int and stores it clamped into [0, MAX_DIGITS].
	private static void emitClampedIntArg(MethodCode a, int arg, int slot, ClassConstant numberClass,
			MethodrefConstant numIntValue, MethodrefConstant mathMaxI, MethodrefConstant mathMinI) {
		a.aload(arg);
		a.checkcast(numberClass.entry());
		a.invokevirtual(numIntValue.methodRefEntry());
		a.loadConstant(0);
		a.invokestatic(mathMaxI.entry());
		a.loadConstant(am.ik.rontolisp.compiler.FixedDecimal.MAX_DIGITS);
		a.invokestatic(mathMinI.entry());
		a.istore(slot);
	}

	// Emits the two `instanceof Long` guards shared by _mod and _cmp, which jump to the
	// slow (BigInteger) path.
	private static void emitLongLongGuard(MethodCode c, ClassConstant longClass, MethodCode.Label slow) {
		c.aload(0);
		c.instanceOf(longClass.entry());
		c.ifeq(slow);
		c.aload(1);
		c.instanceOf(longClass.entry());
		c.ifeq(slow);
	}

	// Emits: load slot, checkcast Long, Long.longValue() -> long on stack.
	private static void emitUnboxLong(MethodCode c, int slot, ClassConstant longClass, MethodrefConstant longValue) {
		c.aload(slot);
		c.checkcast(longClass.entry());
		c.invokevirtual(longValue.methodRefEntry());
	}

	// Emits: load slot, _dbl(x) (boxed Double), checkcast Number, Number.doubleValue() ->
	// double on stack. _dbl coerces Long/BigInteger/ratio/Double to a Double.
	private static void emitToDouble(MethodCode c, int slot, MethodrefConstant rDbl, ClassConstant numberClass,
			MethodrefConstant numDoubleValue) {
		c.aload(slot);
		c.invokestatic(rDbl.entry());
		c.checkcast(numberClass.entry());
		c.invokevirtual(numDoubleValue.methodRefEntry());
	}

	// Emits a Double fast path at the top of a binary numeric op: when either operand is
	// a
	// Double, computes _dbl(a) <doubleOp> _dbl(b) in double arithmetic, boxes it, and
	// returns. Otherwise falls through to the existing integer/ratio body. This gives
	// float
	// contagion for non-literal operands (variables, parameters, #'+/#'* as values),
	// which
	// the compile-site double-literal fast path cannot detect.
	private static void emitDoubleBinaryPrologue(MethodCode c, ClassConstant doubleClass, MethodrefConstant rDbl,
			ClassConstant numberClass, MethodrefConstant numDoubleValue, MethodrefConstant doubleValueOf,
			Consumer<MethodCode> doubleOp) {
		emitDoubleBinaryPrologue(c, doubleClass, rDbl, numberClass, numDoubleValue, doubleValueOf, doubleOp, null);
	}

	// The same, with an optional (DD)D HELPER standing in for the single opcode -- what
	// _mod needs, whose float case is CL's divisor-signed modulo (_fmod), not DREM.
	private static void emitDoubleBinaryPrologue(MethodCode c, ClassConstant doubleClass, MethodrefConstant rDbl,
			ClassConstant numberClass, MethodrefConstant numDoubleValue, MethodrefConstant doubleValueOf,
			Consumer<MethodCode> doubleOp, @Nullable MethodrefConstant doubleHelper) {
		c.aload(0);
		c.instanceOf(doubleClass.entry());
		MethodCode.Label ifADouble = c.newLabel();
		c.ifne(ifADouble);
		c.aload(1);
		c.instanceOf(doubleClass.entry());
		MethodCode.Label ifBNotDouble = c.newLabel();
		c.ifeq(ifBNotDouble);
		c.labelBinding(ifADouble);
		emitToDouble(c, 0, rDbl, numberClass, numDoubleValue);
		emitToDouble(c, 1, rDbl, numberClass, numDoubleValue);
		if (doubleHelper != null) {
			c.invokestatic(doubleHelper.entry());
		}
		else {
			doubleOp.accept(c);
		}
		c.invokestatic(doubleValueOf.entry());
		c.areturn();
		c.labelBinding(ifBNotDouble);
	}

	// Like emitDoubleBinaryPrologue, but for the unary _neg: negates _dbl(a) when a is a
	// Double.
	private static void emitDoubleUnaryPrologue(MethodCode c, ClassConstant doubleClass, MethodrefConstant rDbl,
			ClassConstant numberClass, MethodrefConstant numDoubleValue, MethodrefConstant doubleValueOf,
			Consumer<MethodCode> doubleOp) {
		c.aload(0);
		c.instanceOf(doubleClass.entry());
		MethodCode.Label ifNotDouble = c.newLabel();
		c.ifeq(ifNotDouble);
		emitToDouble(c, 0, rDbl, numberClass, numDoubleValue);
		doubleOp.accept(c);
		c.invokestatic(doubleValueOf.entry());
		c.areturn();
		c.labelBinding(ifNotDouble);
	}

	// Emits: _norm(_big(a).<biOp>(_big(b))) followed by areturn.
	private static void emitBigBinary(MethodCode c, MethodrefConstant rBig, MethodrefConstant biOp,
			MethodrefConstant rNorm) {
		c.aload(0);
		c.invokestatic(rBig.entry());
		c.aload(1);
		c.invokestatic(rBig.entry());
		c.invokevirtual(biOp.methodRefEntry());
		c.invokestatic(rNorm.entry());
		c.areturn();
	}

	// Emits the rational path for a binary operation followed by areturn. With a cross
	// operation (add/subtract) the result is
	// _rat(num(a)*den(b) <crossOp> num(b)*den(a), den(a)*den(b)); without one it is the
	// multiplication _rat(num(a)*num(b), den(a)*den(b)).
	private static void emitRatioBinary(MethodCode c, MethodrefConstant rRatNum, MethodrefConstant rRatDen,
			MethodrefConstant rRat, MethodrefConstant biMul, @Nullable MethodrefConstant crossOp) {
		if (crossOp != null) {
			c.aload(0);
			c.invokestatic(rRatNum.entry());
			c.aload(1);
			c.invokestatic(rRatDen.entry());
			c.invokevirtual(biMul.methodRefEntry());
			c.aload(1);
			c.invokestatic(rRatNum.entry());
			c.aload(0);
			c.invokestatic(rRatDen.entry());
			c.invokevirtual(biMul.methodRefEntry());
			c.invokevirtual(crossOp.methodRefEntry());
		}
		else {
			c.aload(0);
			c.invokestatic(rRatNum.entry());
			c.aload(1);
			c.invokestatic(rRatNum.entry());
			c.invokevirtual(biMul.methodRefEntry());
		}
		c.aload(0);
		c.invokestatic(rRatDen.entry());
		c.aload(1);
		c.invokestatic(rRatDen.entry());
		c.invokevirtual(biMul.methodRefEntry());
		c.invokestatic(rRat.entry());
		c.areturn();
	}

	// Corrects the remainder in local 4 to the sign of the divisor in local 3, which is
	// what turns a remainder into CL's mod: when the remainder is non-zero and its sign
	// differs from the divisor's, the divisor is added. Shared by _mod's BigInteger and
	// rational paths.
	private static void emitDivisorSignCorrection(MethodCode c, MethodrefConstant biSignum, MethodrefConstant biAdd) {
		// if (r.signum() == 0) goto done
		c.aload(4);
		c.invokevirtual(biSignum.methodRefEntry());
		MethodCode.Label ifZero = c.newLabel();
		c.ifeq(ifZero);
		// if (r.signum() == B.signum()) goto done
		c.aload(4);
		c.invokevirtual(biSignum.methodRefEntry());
		c.aload(3);
		c.invokevirtual(biSignum.methodRefEntry());
		MethodCode.Label ifSameSign = c.newLabel();
		c.if_icmpeq(ifSameSign);
		// r = r.add(B)
		c.aload(4);
		c.aload(3);
		c.invokevirtual(biAdd.methodRefEntry());
		c.astore(4);
		c.labelBinding(ifZero);
		c.labelBinding(ifSameSign);
	}

	// Emits the head of _mod/_rem's rational path. With a = an/ad and b = bn/bd the
	// quotient a/b is (an*bd)/(ad*bn), so the integer remainder of THAT division, read
	// over the common denominator ad*bd, is the answer -- the same computation the
	// integer path does, one level up. Leaves the quotient's denominator (which carries
	// the divisor's sign, denominators being positive) in local 3 and the remainder in
	// local 4, the same slots the BigInteger path uses, so _mod's sign correction is
	// shared.
	private static void emitRatioRemainderPrefix(MethodCode c, MethodrefConstant rRatNum, MethodrefConstant rRatDen,
			MethodrefConstant biMul, MethodrefConstant biRem) {
		// BigInteger d = _ratden(a).multiply(_ratnum(b));
		c.aload(0);
		c.invokestatic(rRatDen.entry());
		c.aload(1);
		c.invokestatic(rRatNum.entry());
		c.invokevirtual(biMul.methodRefEntry());
		c.astore(3);
		// BigInteger r = _ratnum(a).multiply(_ratden(b)).remainder(d);
		c.aload(0);
		c.invokestatic(rRatNum.entry());
		c.aload(1);
		c.invokestatic(rRatDen.entry());
		c.invokevirtual(biMul.methodRefEntry());
		c.aload(3);
		c.invokevirtual(biRem.methodRefEntry());
		c.astore(4);
	}

	// Emits the tail of _mod/_rem's rational path: consumes the numerator on the stack,
	// pushes the common denominator ad*bd, and returns the normalized _rat.
	private static void emitRatioRemainderDenominator(MethodCode c, MethodrefConstant rRatDen, MethodrefConstant biMul,
			MethodrefConstant rRat) {
		c.aload(0);
		c.invokestatic(rRatDen.entry());
		c.aload(1);
		c.invokestatic(rRatDen.entry());
		c.invokevirtual(biMul.methodRefEntry());
		c.invokestatic(rRat.entry());
		c.areturn();
	}

	// Emits: load slot, checkcast BigInteger[], push index, aaload.
	private static void emitRatioElement(MethodCode c, int slot, ClassConstant ratArrClass, int index) {
		c.aload(slot);
		c.checkcast(ratArrClass.entry());
		c.loadConstant(index);
		c.aaload();
	}

}
