package am.ik.rontolisp.codegen.jvm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import am.ik.jvm.ConstantPool;
import am.ik.jvm.ConstantPool.ClassConstant;
import am.ik.jvm.ConstantPool.FieldrefConstant;
import am.ik.jvm.ConstantPool.LongConstant;
import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.ConstantPool.Utf8Constant;
import am.ik.jvm.Opcode;
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

	private JvmNumericRuntimeBuilder() {
	}

	/**
	 * A generated numeric helper method, including any exception-table entries it needs.
	 *
	 * @param nameUtf8 the method name constant
	 * @param descUtf8 the method descriptor constant
	 * @param code the method bytecode
	 * @param maxStack the operand stack size
	 * @param maxLocals the local variable slot count
	 * @param exceptionTable the exception table entries, each as {@code {startPc, endPc,
	 * handlerPc, catchTypeIndex}}
	 */
	record NumericMethod(Utf8Constant nameUtf8, Utf8Constant descUtf8, List<Integer> code, int maxStack, int maxLocals,
			List<int[]> exceptionTable) {
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
	 * constructor, {@code String.concat}, the generated {@code _lispToString} (the prin1
	 * renderer, emitted unconditionally), and the two message-prefix constants.
	 *
	 * @param rte {@code java/lang/RuntimeException}
	 * @param rteInit its {@code (String)} constructor
	 * @param strConcat {@code String.concat(String)}
	 * @param lispToString the generated class's {@code _lispToString(Object)}
	 * @param intPrefix
	 * {@link am.ik.rontolisp.ClosRegistry#EXPECTED_INTEGER_MESSAGE_PREFIX}
	 * @param numPrefix
	 * {@link am.ik.rontolisp.ClosRegistry#EXPECTED_NUMBER_MESSAGE_PREFIX}
	 */
	record TypeErrRefs(ClassConstant rte, MethodrefConstant rteInit, MethodrefConstant strConcat,
			MethodrefConstant lispToString, ConstantPool.StringConstant intPrefix,
			ConstantPool.StringConstant numPrefix, ConstantPool.StringConstant realPrefix) {
	}

	/**
	 * Emits {@code throw new RuntimeException(prefix + _lispToString(local0))}. Peak
	 * operand stack: 4.
	 * @param c the bytecode sink
	 * @param refs the shared references
	 * @param numberContext whether to use the "Expected number" prefix (the "Expected
	 * integer" one otherwise)
	 */
	private static void emitTypeErrThrow(List<Integer> c, TypeErrRefs refs, boolean numberContext) {
		c.add(Opcode.NEW);
		JvmRuntimeBuilder.emitU2(c, refs.rte().index());
		c.add(Opcode.DUP);
		JvmRuntimeBuilder.emitLdc(c, (numberContext ? refs.numPrefix() : refs.intPrefix()).index());
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, refs.lispToString().index());
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, refs.strConcat().index());
		c.add(Opcode.INVOKESPECIAL);
		JvmRuntimeBuilder.emitU2(c, refs.rteInit().index());
		c.add(Opcode.ATHROW);
	}

	// The real-context twin of emitTypeErrThrow: a complex reaching a real-only
	// funnel throws the interpreter's "Expected real number" text (catchable as a
	// type-error by the handler-case prefix classification, like _ccmpb's).
	private static void emitRealErrThrow(List<Integer> c, TypeErrRefs refs) {
		c.add(Opcode.NEW);
		JvmRuntimeBuilder.emitU2(c, refs.rte().index());
		c.add(Opcode.DUP);
		JvmRuntimeBuilder.emitLdc(c, refs.realPrefix().index());
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, refs.lispToString().index());
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, refs.strConcat().index());
		c.add(Opcode.INVOKESPECIAL);
		JvmRuntimeBuilder.emitU2(c, refs.rteInit().index());
		c.add(Opcode.ATHROW);
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

		// The non-number landing (_big / _dbl / _abs's BigInteger arm): a plain
		// RuntimeException carrying "Expected integer|number, got: <prin1>" -- the
		// interpreter's exact text, rendered through the unconditional _lispToString.
		// A checkcast cannot be the check here: null PASSES a checkcast and the failure
		// then surfaces later as a Java NPE naming BigInteger internals. The landing-pad
		// classification of the prefix as a type-error is JvmHandlerCaseCompiler's.
		ClassConstant rteClass = cp.addClass(cp.addUtf8("java/lang/RuntimeException"));
		ClassConstant stringClass = cp.addClass(cp.addUtf8("java/lang/String"));
		TypeErrRefs typeErrRefs = new TypeErrRefs(rteClass,
				cp.addMethodref(rteClass, cp.addNameAndType(cp.addUtf8("<init>"), cp.addUtf8("(Ljava/lang/String;)V"))),
				cp.addMethodref(stringClass,
						cp.addNameAndType(cp.addUtf8("concat"), cp.addUtf8("(Ljava/lang/String;)Ljava/lang/String;"))),
				cp.addMethodref(thisClass,
						cp.addNameAndType(cp.addUtf8("_lispToString"),
								cp.addUtf8("(Ljava/lang/Object;)Ljava/lang/String;"))),
				cp.addString(am.ik.rontolisp.ClosRegistry.EXPECTED_INTEGER_MESSAGE_PREFIX),
				cp.addString(am.ik.rontolisp.ClosRegistry.EXPECTED_NUMBER_MESSAGE_PREFIX),
				cp.addString(am.ik.rontolisp.ClosRegistry.EXPECTED_REAL_MESSAGE_PREFIX));

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
				numDoubleValue, doubleValueOf, Opcode.DADD));
		methods.add(buildExactBinary(nSub, dBinary, longClass, subExact, longValue, longValueOf, rBig, rNorm, biSub,
				arithEx, ratArrClass, rRatNum, rRatDen, rRat, biMul, biSub, doubleClass, rDbl, numberClass,
				numDoubleValue, doubleValueOf, Opcode.DSUB));
		methods.add(buildExactBinary(nMul, dBinary, longClass, mulExact, longValue, longValueOf, rBig, rNorm, biMul,
				arithEx, ratArrClass, rRatNum, rRatDen, rRat, biMul, null, doubleClass, rDbl, numberClass,
				numDoubleValue, doubleValueOf, Opcode.DMUL));
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
				longValueOf, tlrCurrent, tlrNextDouble));
		methods.add(buildSelect(nMin, dBinary, rCmpb, CMPB_LT | CMPB_EQ, rcClass, typeErrRefs, hasComplex));
		methods.add(buildSelect(nMax, dBinary, rCmpb, CMPB_GT | CMPB_EQ, rcClass, typeErrRefs, hasComplex));
		methods.add(buildFloatSelect(nFmin, dFmod, Opcode.DCMPG, Opcode.IFLE));
		methods.add(buildFloatSelect(nFmax, dFmod, Opcode.DCMPL, Opcode.IFGE));
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
				biSignum, longValueOf));
		methods.add(buildLogOp(nLogand, dBinary, longClass, longValue, longValueOf, rBig, rNorm, biAnd, Opcode.LAND));
		methods.add(buildLogOp(nLogior, dBinary, longClass, longValue, longValueOf, rBig, rNorm, biOr, Opcode.LOR));
		methods.add(buildLogOp(nLogxor, dBinary, longClass, longValue, longValueOf, rBig, rNorm, biXor, Opcode.LXOR));
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
		List<Integer> c = new ArrayList<>();
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.INSTANCEOF);
		JvmRuntimeBuilder.emitU2(c, longClass.index());
		int ifNotLong = c.size();
		c.add(Opcode.IFEQ);
		JvmRuntimeBuilder.emitU2(c, 0);
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.CHECKCAST);
		JvmRuntimeBuilder.emitU2(c, longClass.index());
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, longValue.index());
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, biValueOf.index());
		c.add(Opcode.ARETURN);
		JvmRuntimeBuilder.patchBranch(c, ifNotLong, c.size());
		// Anything but a BigInteger throws the interpreter's "Expected integer" text: a
		// bare checkcast is not a check here (null passes it and fails later as a Java
		// NPE naming BigInteger internals, and a cast failure's own text names Java
		// classes). One instanceof on the widening (out-of-long) arm only.
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.INSTANCEOF);
		JvmRuntimeBuilder.emitU2(c, bigClass.index());
		int ifNotBig = c.size();
		c.add(Opcode.IFEQ);
		JvmRuntimeBuilder.emitU2(c, 0);
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.CHECKCAST);
		JvmRuntimeBuilder.emitU2(c, bigClass.index());
		c.add(Opcode.ARETURN);
		JvmRuntimeBuilder.patchBranch(c, ifNotBig, c.size());
		emitTypeErrThrow(c, typeErrRefs, false);
		return new NumericMethod(name, desc, c, 4, 1, List.of());
	}

	// _norm(BigInteger b): demote to Long when it fits in a long, else keep BigInteger.
	private static NumericMethod buildNorm(Utf8Constant name, Utf8Constant desc, MethodrefConstant longValueOf,
			MethodrefConstant biBitLength, MethodrefConstant biLongValue) {
		List<Integer> c = new ArrayList<>();
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, biBitLength.index());
		JvmRuntimeBuilder.emitIntConstStatic(c, 64);
		int ifGe = c.size();
		c.add(Opcode.IF_ICMPGE);
		JvmRuntimeBuilder.emitU2(c, 0);
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, biLongValue.index());
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, longValueOf.index());
		c.add(Opcode.ARETURN);
		JvmRuntimeBuilder.patchBranch(c, ifGe, c.size());
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.ARETURN);
		return new NumericMethod(name, desc, c, 2, 1, List.of());
	}

	// _ratnum(Object x): ratio -> x[0], otherwise _big(x).
	private static NumericMethod buildRatNum(Utf8Constant name, Utf8Constant desc, ClassConstant ratArrClass,
			MethodrefConstant rBig) {
		List<Integer> c = new ArrayList<>();
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.INSTANCEOF);
		JvmRuntimeBuilder.emitU2(c, ratArrClass.index());
		int ifNotRat = c.size();
		c.add(Opcode.IFEQ);
		JvmRuntimeBuilder.emitU2(c, 0);
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.CHECKCAST);
		JvmRuntimeBuilder.emitU2(c, ratArrClass.index());
		c.add(Opcode.ICONST_0);
		c.add(Opcode.AALOAD);
		c.add(Opcode.ARETURN);
		JvmRuntimeBuilder.patchBranch(c, ifNotRat, c.size());
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, rBig.index());
		c.add(Opcode.ARETURN);
		return new NumericMethod(name, desc, c, 2, 1, List.of());
	}

	// _ratden(Object x): ratio -> x[1], otherwise BigInteger.ONE.
	private static NumericMethod buildRatDen(Utf8Constant name, Utf8Constant desc, ClassConstant ratArrClass,
			FieldrefConstant biOne) {
		List<Integer> c = new ArrayList<>();
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.INSTANCEOF);
		JvmRuntimeBuilder.emitU2(c, ratArrClass.index());
		int ifNotRat = c.size();
		c.add(Opcode.IFEQ);
		JvmRuntimeBuilder.emitU2(c, 0);
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.CHECKCAST);
		JvmRuntimeBuilder.emitU2(c, ratArrClass.index());
		c.add(Opcode.ICONST_1);
		c.add(Opcode.AALOAD);
		c.add(Opcode.ARETURN);
		JvmRuntimeBuilder.patchBranch(c, ifNotRat, c.size());
		c.add(Opcode.GETSTATIC);
		JvmRuntimeBuilder.emitU2(c, biOne.index());
		c.add(Opcode.ARETURN);
		return new NumericMethod(name, desc, c, 2, 1, List.of());
	}

	// _rat(BigInteger num, BigInteger den): builds a normalized rational value. Moves
	// the sign to the numerator, reduces by the gcd, and demotes a denominator-one
	// result to an integer via _norm. A zero denominator throws ArithmeticException.
	private static NumericMethod buildRat(Utf8Constant name, Utf8Constant desc, ClassConstant bigClass,
			ClassConstant arithEx, MethodrefConstant aeInit, ConstantPool.StringConstant divZeroStr,
			MethodrefConstant biSignum, MethodrefConstant biNeg, MethodrefConstant biGcd, MethodrefConstant biDiv,
			FieldrefConstant biOne, MethodrefConstant objEquals, MethodrefConstant rNorm) {
		List<Integer> c = new ArrayList<>();
		// if (den.signum() == 0) throw new ArithmeticException("Division by zero");
		c.add(Opcode.ALOAD_1);
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, biSignum.index());
		int ifNonZero = c.size();
		c.add(Opcode.IFNE);
		JvmRuntimeBuilder.emitU2(c, 0);
		c.add(Opcode.NEW);
		JvmRuntimeBuilder.emitU2(c, arithEx.index());
		c.add(Opcode.DUP);
		JvmRuntimeBuilder.emitLdc(c, divZeroStr.index());
		c.add(Opcode.INVOKESPECIAL);
		JvmRuntimeBuilder.emitU2(c, aeInit.index());
		c.add(Opcode.ATHROW);
		// if (den.signum() < 0) { num = num.negate(); den = den.negate(); }
		JvmRuntimeBuilder.patchBranch(c, ifNonZero, c.size());
		c.add(Opcode.ALOAD_1);
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, biSignum.index());
		int ifPositive = c.size();
		c.add(Opcode.IFGE);
		JvmRuntimeBuilder.emitU2(c, 0);
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, biNeg.index());
		c.add(Opcode.ASTORE_0);
		c.add(Opcode.ALOAD_1);
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, biNeg.index());
		c.add(Opcode.ASTORE_1);
		// BigInteger g = num.gcd(den); num = num.divide(g); den = den.divide(g);
		JvmRuntimeBuilder.patchBranch(c, ifPositive, c.size());
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.ALOAD_1);
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, biGcd.index());
		c.add(Opcode.ASTORE_2);
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.ALOAD_2);
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, biDiv.index());
		c.add(Opcode.ASTORE_0);
		c.add(Opcode.ALOAD_1);
		c.add(Opcode.ALOAD_2);
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, biDiv.index());
		c.add(Opcode.ASTORE_1);
		// if (den.equals(BigInteger.ONE)) return _norm(num);
		c.add(Opcode.ALOAD_1);
		c.add(Opcode.GETSTATIC);
		JvmRuntimeBuilder.emitU2(c, biOne.index());
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, objEquals.index());
		int ifNotOne = c.size();
		c.add(Opcode.IFEQ);
		JvmRuntimeBuilder.emitU2(c, 0);
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, rNorm.index());
		c.add(Opcode.ARETURN);
		// return new BigInteger[] { num, den };
		JvmRuntimeBuilder.patchBranch(c, ifNotOne, c.size());
		c.add(Opcode.ICONST_2);
		c.add(Opcode.ANEWARRAY);
		JvmRuntimeBuilder.emitU2(c, bigClass.index());
		c.add(Opcode.DUP);
		c.add(Opcode.ICONST_0);
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.AASTORE);
		c.add(Opcode.DUP);
		c.add(Opcode.ICONST_1);
		c.add(Opcode.ALOAD_1);
		c.add(Opcode.AASTORE);
		c.add(Opcode.ARETURN);
		return new NumericMethod(name, desc, c, 4, 3, List.of());
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
			int doubleOpcode) {
		List<Integer> c = new ArrayList<>();
		emitDoubleBinaryPrologue(c, doubleClass, rDbl, numberClass, numDoubleValue, doubleValueOf, doubleOpcode);
		int[] ratJumps = emitRatioGuard(c, ratArrClass);
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.INSTANCEOF);
		JvmRuntimeBuilder.emitU2(c, longClass.index());
		int ifSlow1 = c.size();
		c.add(Opcode.IFEQ);
		JvmRuntimeBuilder.emitU2(c, 0);
		c.add(Opcode.ALOAD_1);
		c.add(Opcode.INSTANCEOF);
		JvmRuntimeBuilder.emitU2(c, longClass.index());
		int ifSlow2 = c.size();
		c.add(Opcode.IFEQ);
		JvmRuntimeBuilder.emitU2(c, 0);
		int tryStart = c.size();
		emitUnboxLong(c, Opcode.ALOAD_0, longClass, longValue);
		emitUnboxLong(c, Opcode.ALOAD_1, longClass, longValue);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, exact.index());
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, longValueOf.index());
		c.add(Opcode.ARETURN);
		int handler = c.size();
		c.add(Opcode.POP);
		int slow = c.size();
		JvmRuntimeBuilder.patchBranch(c, ifSlow1, slow);
		JvmRuntimeBuilder.patchBranch(c, ifSlow2, slow);
		emitBigBinary(c, rBig, biOp, rNorm);
		int rat = c.size();
		JvmRuntimeBuilder.patchBranch(c, ratJumps[0], rat);
		JvmRuntimeBuilder.patchBranch(c, ratJumps[1], rat);
		emitRatioBinary(c, rRatNum, rRatDen, rRat, biMul, ratioCross);
		return new NumericMethod(name, desc, c, 4, 2,
				List.of(new int[] { tryStart, handler, handler, arithEx.index() }));
	}

	// _neg(Object a): negate via Math.negateExact, promoting to BigInteger on overflow;
	// a ratio negates its numerator.
	private static NumericMethod buildNeg(Utf8Constant name, Utf8Constant desc, ClassConstant longClass,
			MethodrefConstant negExact, MethodrefConstant longValue, MethodrefConstant longValueOf,
			MethodrefConstant rBig, MethodrefConstant rNorm, MethodrefConstant biNeg, ClassConstant arithEx,
			ClassConstant ratArrClass, MethodrefConstant rRatNum, MethodrefConstant rRatDen, MethodrefConstant rRat,
			ClassConstant doubleClass, MethodrefConstant rDbl, ClassConstant numberClass,
			MethodrefConstant numDoubleValue, MethodrefConstant doubleValueOf) {
		List<Integer> c = new ArrayList<>();
		emitDoubleUnaryPrologue(c, doubleClass, rDbl, numberClass, numDoubleValue, doubleValueOf, Opcode.DNEG);
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.INSTANCEOF);
		JvmRuntimeBuilder.emitU2(c, ratArrClass.index());
		int ifRat = c.size();
		c.add(Opcode.IFNE);
		JvmRuntimeBuilder.emitU2(c, 0);
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.INSTANCEOF);
		JvmRuntimeBuilder.emitU2(c, longClass.index());
		int ifSlow = c.size();
		c.add(Opcode.IFEQ);
		JvmRuntimeBuilder.emitU2(c, 0);
		int tryStart = c.size();
		emitUnboxLong(c, Opcode.ALOAD_0, longClass, longValue);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, negExact.index());
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, longValueOf.index());
		c.add(Opcode.ARETURN);
		int handler = c.size();
		c.add(Opcode.POP);
		int slow = c.size();
		JvmRuntimeBuilder.patchBranch(c, ifSlow, slow);
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, rBig.index());
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, biNeg.index());
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, rNorm.index());
		c.add(Opcode.ARETURN);
		JvmRuntimeBuilder.patchBranch(c, ifRat, c.size());
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, rRatNum.index());
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, biNeg.index());
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, rRatDen.index());
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, rRat.index());
		c.add(Opcode.ARETURN);
		return new NumericMethod(name, desc, c, 2, 1,
				List.of(new int[] { tryStart, handler, handler, arithEx.index() }));
	}

	// _div(Object a, Object b): Common Lisp exact rational division for any mix of
	// integers and ratios: _rat(num(a)*den(b), den(a)*num(b)). The result demotes to an
	// integer when the division is exact; division by zero throws inside _rat.
	private static NumericMethod buildDiv(Utf8Constant name, Utf8Constant desc, MethodrefConstant rRatNum,
			MethodrefConstant rRatDen, MethodrefConstant rRat, MethodrefConstant biMul, ClassConstant doubleClass,
			MethodrefConstant rDbl, ClassConstant numberClass, MethodrefConstant numDoubleValue,
			MethodrefConstant doubleValueOf) {
		List<Integer> c = new ArrayList<>();
		emitDoubleBinaryPrologue(c, doubleClass, rDbl, numberClass, numDoubleValue, doubleValueOf, Opcode.DDIV);
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, rRatNum.index());
		c.add(Opcode.ALOAD_1);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, rRatDen.index());
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, biMul.index());
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, rRatDen.index());
		c.add(Opcode.ALOAD_1);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, rRatNum.index());
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, biMul.index());
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, rRat.index());
		c.add(Opcode.ARETURN);
		return new NumericMethod(name, desc, c, 4, 2, List.of());
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
		List<Integer> c = new ArrayList<>();
		// A float operand takes CL's divisor-signed float modulo, the same _fmod the
		// double-literal emission calls -- without this arm a Double reaching the
		// generic helper (a fused site's bail, an argument the emitter could not see
		// the type of) fell through to _big and died casting Double to BigInteger.
		emitDoubleBinaryPrologue(c, doubleClass, rDbl, numberClass, numDoubleValue, doubleValueOf, Opcode.DREM, rFmod);
		int[] ratJumps = emitRatioGuard(c, ratArrClass);
		int[] slowJumps = emitLongLongGuard(c, longClass);
		emitUnboxLong(c, Opcode.ALOAD_0, longClass, longValue);
		emitUnboxLong(c, Opcode.ALOAD_1, longClass, longValue);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, floorModLong.index());
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, longValueOf.index());
		c.add(Opcode.ARETURN);
		int slow = c.size();
		JvmRuntimeBuilder.patchBranch(c, slowJumps[0], slow);
		JvmRuntimeBuilder.patchBranch(c, slowJumps[1], slow);
		// BigInteger A = _big(a); BigInteger B = _big(b); BigInteger r = A.remainder(B);
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, rBig.index());
		c.add(Opcode.ASTORE_2);
		c.add(Opcode.ALOAD_1);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, rBig.index());
		c.add(Opcode.ASTORE_3);
		c.add(Opcode.ALOAD_2);
		c.add(Opcode.ALOAD_3);
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, biRem.index());
		c.add(Opcode.ASTORE);
		c.add(4);
		emitDivisorSignCorrection(c, biSignum, biAdd);
		c.add(Opcode.ALOAD);
		c.add(4);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, rNorm.index());
		c.add(Opcode.ARETURN);
		int rat = c.size();
		JvmRuntimeBuilder.patchBranch(c, ratJumps[0], rat);
		JvmRuntimeBuilder.patchBranch(c, ratJumps[1], rat);
		emitRatioRemainderPrefix(c, rRatNum, rRatDen, biMul, biRem);
		emitDivisorSignCorrection(c, biSignum, biAdd);
		c.add(Opcode.ALOAD);
		c.add(4);
		emitRatioRemainderDenominator(c, rRatDen, biMul, rRat);
		return new NumericMethod(name, desc, c, 4, 5, List.of());
	}

	// _rem(Object a, Object b): remainder whose result takes the sign of the dividend
	// (Java/BigInteger remainder). Long fast path, BigInteger.remainder otherwise.
	private static NumericMethod buildRem(Utf8Constant name, Utf8Constant desc, ClassConstant longClass,
			MethodrefConstant longValue, MethodrefConstant longValueOf, MethodrefConstant rBig, MethodrefConstant rNorm,
			MethodrefConstant biRem, ClassConstant doubleClass, MethodrefConstant rDbl, ClassConstant numberClass,
			MethodrefConstant numDoubleValue, MethodrefConstant doubleValueOf, MethodrefConstant rFrem,
			ClassConstant ratArrClass, MethodrefConstant rRatNum, MethodrefConstant rRatDen, MethodrefConstant rRat,
			MethodrefConstant biMul) {
		List<Integer> c = new ArrayList<>();
		// A float operand keeps the dividend's sign -- _frem, which is DREM plus CLHS's
		// sign for a ZERO remainder, and is what the double-literal emission of
		// (rem ...) also calls (see buildMod for why the arm has to exist here too).
		emitDoubleBinaryPrologue(c, doubleClass, rDbl, numberClass, numDoubleValue, doubleValueOf, Opcode.DREM, rFrem);
		int[] ratJumps = emitRatioGuard(c, ratArrClass);
		int[] slowJumps = emitLongLongGuard(c, longClass);
		emitUnboxLong(c, Opcode.ALOAD_0, longClass, longValue);
		emitUnboxLong(c, Opcode.ALOAD_1, longClass, longValue);
		c.add(Opcode.LREM);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, longValueOf.index());
		c.add(Opcode.ARETURN);
		int slow = c.size();
		JvmRuntimeBuilder.patchBranch(c, slowJumps[0], slow);
		JvmRuntimeBuilder.patchBranch(c, slowJumps[1], slow);
		emitBigBinary(c, rBig, biRem, rNorm);
		int rat = c.size();
		JvmRuntimeBuilder.patchBranch(c, ratJumps[0], rat);
		JvmRuntimeBuilder.patchBranch(c, ratJumps[1], rat);
		emitRatioRemainderPrefix(c, rRatNum, rRatDen, biMul, biRem);
		c.add(Opcode.ALOAD);
		c.add(4);
		emitRatioRemainderDenominator(c, rRatDen, biMul, rRat);
		return new NumericMethod(name, desc, c, 4, 5, List.of());
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
		List<Integer> c = new ArrayList<>();
		c.add(Opcode.DLOAD_0);
		c.add(Opcode.DLOAD_2);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, rFrem.index());
		c.add(Opcode.DSTORE);
		c.add(4);
		c.add(Opcode.DLOAD);
		c.add(4);
		c.add(Opcode.DCONST_0);
		c.add(Opcode.DCMPL); // NaN compares as -1, so a NaN remainder is not a zero
		int ifNonZero = c.size();
		c.add(Opcode.IFNE);
		JvmRuntimeBuilder.emitU2(c, 0);
		c.add(Opcode.DLOAD);
		c.add(4);
		c.add(Opcode.DRETURN);
		JvmRuntimeBuilder.patchBranch(c, ifNonZero, c.size());
		c.add(Opcode.DLOAD);
		c.add(4);
		c.add(Opcode.DCONST_0);
		c.add(Opcode.DCMPG);
		c.add(Opcode.DLOAD_2);
		c.add(Opcode.DCONST_0);
		c.add(Opcode.DCMPG);
		int ifSameSign = c.size();
		c.add(Opcode.IF_ICMPEQ);
		JvmRuntimeBuilder.emitU2(c, 0);
		c.add(Opcode.DLOAD);
		c.add(4);
		c.add(Opcode.DLOAD_2);
		c.add(Opcode.DADD);
		c.add(Opcode.DRETURN);
		JvmRuntimeBuilder.patchBranch(c, ifSameSign, c.size());
		c.add(Opcode.DLOAD);
		c.add(4);
		c.add(Opcode.DRETURN);
		return new NumericMethod(name, desc, c, 5, 6, List.of());
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
		List<Integer> c = new ArrayList<>();
		c.add(Opcode.DLOAD_0);
		c.add(Opcode.DLOAD_2);
		c.add(Opcode.DREM);
		c.add(Opcode.DSTORE);
		c.add(4);
		c.add(Opcode.DLOAD);
		c.add(4);
		c.add(Opcode.DCONST_0);
		c.add(Opcode.DCMPL); // NaN compares as -1, so a NaN remainder falls through
		int ifZeroResult = c.size();
		c.add(Opcode.IFEQ);
		JvmRuntimeBuilder.emitU2(c, 0);
		c.add(Opcode.DLOAD);
		c.add(4);
		c.add(Opcode.DRETURN);
		JvmRuntimeBuilder.patchBranch(c, ifZeroResult, c.size());
		c.add(Opcode.DLOAD_0);
		c.add(Opcode.DCONST_0);
		c.add(Opcode.DCMPL);
		int ifZeroDividend = c.size();
		c.add(Opcode.IFEQ);
		JvmRuntimeBuilder.emitU2(c, 0);
		c.add(Opcode.DCONST_0);
		c.add(Opcode.DRETURN);
		JvmRuntimeBuilder.patchBranch(c, ifZeroDividend, c.size());
		// b is neither NaN nor a zero here -- either would have made r a NaN.
		c.add(Opcode.DLOAD_2);
		c.add(Opcode.DCONST_0);
		c.add(Opcode.DCMPG);
		int ifPositiveDivisor = c.size();
		c.add(Opcode.IFGE);
		JvmRuntimeBuilder.emitU2(c, 0);
		c.add(Opcode.DLOAD_0);
		c.add(Opcode.DCONST_0);
		c.add(Opcode.DADD);
		c.add(Opcode.DRETURN);
		JvmRuntimeBuilder.patchBranch(c, ifPositiveDivisor, c.size());
		c.add(Opcode.DLOAD_0);
		c.add(Opcode.DCONST_0);
		c.add(Opcode.DSUB);
		c.add(Opcode.DRETURN);
		return new NumericMethod(name, desc, c, 4, 6, List.of());
	}

	// The exact comparison of a (Double, exact) pair, shared by _cmp and _cmpb: the
	// operand loaded by dblLoad is a Double, the one loaded by othLoad is exact
	// (Long/BigInteger/ratio) or the float funnel's "Expected number" throw. A finite
	// double compares its _frat decomposition against the exact operand's (_ratNum,
	// _ratDen) by cross-multiplication (every denominator is positive, so the
	// direction is preserved); an infinity outweighs every exact number on its side.
	// A NaN double either answers the unordered mask (bitmask mode, _cmpb) or jumps
	// back to a caller-recorded old path (signum mode, _cmp, where unordered is the
	// DCMPL collapse both callers already had). Locals 2/3 hold the double, local 4
	// the _frat pair. Every path returns.
	private static void emitExactFloatCompare(List<Integer> c, int dblLoad, int othLoad, boolean dblIsA,
			boolean bitmask, ClassConstant numberClass, MethodrefConstant numDoubleValue, MethodrefConstant rFrat,
			ClassConstant ratArrClass, ClassConstant bigClass, ClassConstant longClass, MethodrefConstant rRatNum,
			MethodrefConstant rRatDen, MethodrefConstant biMul, MethodrefConstant biCompareTo,
			MethodrefConstant intSignum, TypeErrRefs typeErrRefs, @Nullable List<Integer> nanFallbacks) {
		c.add(dblLoad);
		c.add(Opcode.CHECKCAST);
		JvmRuntimeBuilder.emitU2(c, numberClass.index());
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, numDoubleValue.index());
		c.add(Opcode.DSTORE_2);
		// NaN: DCMPL(d, d) falls out as -1, so IFEQ skips it.
		c.add(Opcode.DLOAD_2);
		c.add(Opcode.DLOAD_2);
		c.add(Opcode.DCMPL);
		int ifNotNaN = c.size();
		c.add(Opcode.IFEQ);
		JvmRuntimeBuilder.emitU2(c, 0);
		if (nanFallbacks != null) {
			int gotoOld = c.size();
			c.add(Opcode.GOTO);
			JvmRuntimeBuilder.emitU2(c, 0);
			nanFallbacks.add(gotoOld);
		}
		else {
			c.add(Opcode.ICONST_0);
			c.add(Opcode.IRETURN);
		}
		JvmRuntimeBuilder.patchBranch(c, ifNotNaN, c.size());
		// The exact pair _frat answers for a finite double (the pair array class is
		// the ratio's: buildRational casts the same way). Null past the NaN check
		// above is an infinity.
		c.add(dblLoad);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, rFrat.index());
		c.add(Opcode.DUP);
		int ifFinite = c.size();
		c.add(Opcode.IFNONNULL);
		JvmRuntimeBuilder.emitU2(c, 0);
		c.add(Opcode.POP);
		// DCMPL(d, 0) is 1 or -1 here (a zero double decomposes, never nulls).
		c.add(Opcode.DLOAD_2);
		c.add(Opcode.DCONST_0);
		c.add(Opcode.DCMPL);
		int ifNegInf = c.size();
		c.add(Opcode.IFLE);
		JvmRuntimeBuilder.emitU2(c, 0);
		emitMixedInfinite(c, dblIsA, bitmask, true);
		JvmRuntimeBuilder.patchBranch(c, ifNegInf, c.size());
		emitMixedInfinite(c, dblIsA, bitmask, false);
		JvmRuntimeBuilder.patchBranch(c, ifFinite, c.size());
		c.add(Opcode.CHECKCAST);
		JvmRuntimeBuilder.emitU2(c, ratArrClass.index());
		c.add(Opcode.ASTORE);
		c.add(4);
		// The exact side's funnel: a non-number beside a float is "Expected
		// number", the _dbl text the mixed pair used to see.
		c.add(othLoad);
		c.add(Opcode.INSTANCEOF);
		JvmRuntimeBuilder.emitU2(c, ratArrClass.index());
		int ifOthRat = c.size();
		c.add(Opcode.IFNE);
		JvmRuntimeBuilder.emitU2(c, 0);
		c.add(othLoad);
		c.add(Opcode.INSTANCEOF);
		JvmRuntimeBuilder.emitU2(c, longClass.index());
		int ifOthLong = c.size();
		c.add(Opcode.IFNE);
		JvmRuntimeBuilder.emitU2(c, 0);
		c.add(othLoad);
		c.add(Opcode.INSTANCEOF);
		JvmRuntimeBuilder.emitU2(c, bigClass.index());
		int ifOthBig = c.size();
		c.add(Opcode.IFNE);
		JvmRuntimeBuilder.emitU2(c, 0);
		c.add(Opcode.NEW);
		JvmRuntimeBuilder.emitU2(c, typeErrRefs.rte().index());
		c.add(Opcode.DUP);
		JvmRuntimeBuilder.emitLdc(c, typeErrRefs.numPrefix().index());
		c.add(othLoad);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, typeErrRefs.lispToString().index());
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, typeErrRefs.strConcat().index());
		c.add(Opcode.INVOKESPECIAL);
		JvmRuntimeBuilder.emitU2(c, typeErrRefs.rteInit().index());
		c.add(Opcode.ATHROW);
		JvmRuntimeBuilder.patchBranch(c, ifOthRat, c.size());
		JvmRuntimeBuilder.patchBranch(c, ifOthLong, c.size());
		JvmRuntimeBuilder.patchBranch(c, ifOthBig, c.size());
		// left = numA*denB, right = numB*denA with (A, B) = (dbl, oth) or the
		// mirror, so the sign reads in (a, b) order without a flag local. The
		// bitmask shape leads with 1 for the 1 << (signum + 1) tail.
		if (bitmask) {
			c.add(Opcode.ICONST_1);
		}
		emitMixedNumDen(c, dblIsA, 0, othLoad, rRatNum, ratArrClass, bigClass);
		emitMixedNumDen(c, !dblIsA, 1, othLoad, rRatDen, ratArrClass, bigClass);
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, biMul.index());
		emitMixedNumDen(c, !dblIsA, 0, othLoad, rRatNum, ratArrClass, bigClass);
		emitMixedNumDen(c, dblIsA, 1, othLoad, rRatDen, ratArrClass, bigClass);
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, biMul.index());
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, biCompareTo.index());
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, intSignum.index());
		if (bitmask) {
			c.add(Opcode.ICONST_1);
			c.add(Opcode.IADD);
			c.add(Opcode.ISHL);
		}
		c.add(Opcode.IRETURN);
	}

	// One numerator/denominator side of the mixed cross-multiplication: the _frat
	// pair's element when pairSide, else the exact operand's _ratNum/_ratDen (which
	// funnel Long/BigInteger through _big and answer ONE for a non-ratio
	// denominator, so the funnel check above is what rejects junk).
	private static void emitMixedNumDen(List<Integer> c, boolean pairSide, int pairIndex, int othLoad,
			MethodrefConstant rRatPart, ClassConstant ratArrClass, ClassConstant bigClass) {
		if (pairSide) {
			c.add(Opcode.ALOAD);
			c.add(4);
			if (pairIndex == 0) {
				c.add(Opcode.ICONST_0);
			}
			else {
				c.add(Opcode.ICONST_1);
			}
			c.add(Opcode.AALOAD);
			c.add(Opcode.CHECKCAST);
			JvmRuntimeBuilder.emitU2(c, bigClass.index());
		}
		else {
			c.add(othLoad);
			c.add(Opcode.INVOKESTATIC);
			JvmRuntimeBuilder.emitU2(c, rRatPart.index());
		}
	}

	// An infinite double against an exact number: beyond it on its side's sign.
	private static void emitMixedInfinite(List<Integer> c, boolean dblIsA, boolean bitmask, boolean positive) {
		if (bitmask) {
			c.add(dblIsA == positive ? Opcode.ICONST_4 : Opcode.ICONST_1);
		}
		else {
			JvmRuntimeBuilder.emitIntConstStatic(c, dblIsA == positive ? 1 : -1);
		}
		c.add(Opcode.IRETURN);
	}

	// _cmp(Object a, Object b): long comparison, BigInteger.compareTo, or rational
	// cross-multiplication (denominators are positive), returning -1/0/1.
	private static NumericMethod buildCmp(Utf8Constant name, Utf8Constant desc, ClassConstant longClass,
			MethodrefConstant longValue, MethodrefConstant rBig, MethodrefConstant biCompareTo,
			ClassConstant ratArrClass, MethodrefConstant rRatNum, MethodrefConstant rRatDen, MethodrefConstant biMul,
			ClassConstant doubleClass, MethodrefConstant rDbl, ClassConstant numberClass,
			MethodrefConstant numDoubleValue, ClassConstant bigClass, MethodrefConstant rFrat,
			MethodrefConstant intSignum, TypeErrRefs typeErrRefs) {
		List<Integer> c = new ArrayList<>();
		// Double dispatch: both doubles take the old double comparison; exactly one
		// double takes the exact mixed comparison (a NaN jumps back to the old path,
		// which collapses it to -1 as before); neither reaches the exact body below.
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.INSTANCEOF);
		JvmRuntimeBuilder.emitU2(c, doubleClass.index());
		int ifANotDouble = c.size();
		c.add(Opcode.IFEQ);
		JvmRuntimeBuilder.emitU2(c, 0);
		c.add(Opcode.ALOAD_1);
		c.add(Opcode.INSTANCEOF);
		JvmRuntimeBuilder.emitU2(c, doubleClass.index());
		int ifMixedA = c.size();
		c.add(Opcode.IFEQ);
		JvmRuntimeBuilder.emitU2(c, 0);
		int oldPath = c.size();
		emitToDouble(c, Opcode.ALOAD_0, rDbl, numberClass, numDoubleValue);
		emitToDouble(c, Opcode.ALOAD_1, rDbl, numberClass, numDoubleValue);
		c.add(Opcode.DCMPL);
		c.add(Opcode.IRETURN);
		int mixedA = c.size();
		JvmRuntimeBuilder.patchBranch(c, ifMixedA, mixedA);
		List<Integer> nanOld = new ArrayList<>();
		emitExactFloatCompare(c, Opcode.ALOAD_0, Opcode.ALOAD_1, true, false, numberClass, numDoubleValue, rFrat,
				ratArrClass, bigClass, longClass, rRatNum, rRatDen, biMul, biCompareTo, intSignum, typeErrRefs, nanOld);
		int aNotDouble = c.size();
		JvmRuntimeBuilder.patchBranch(c, ifANotDouble, aNotDouble);
		c.add(Opcode.ALOAD_1);
		c.add(Opcode.INSTANCEOF);
		JvmRuntimeBuilder.emitU2(c, doubleClass.index());
		int ifExactRest = c.size();
		c.add(Opcode.IFEQ);
		JvmRuntimeBuilder.emitU2(c, 0);
		emitExactFloatCompare(c, Opcode.ALOAD_1, Opcode.ALOAD_0, false, false, numberClass, numDoubleValue, rFrat,
				ratArrClass, bigClass, longClass, rRatNum, rRatDen, biMul, biCompareTo, intSignum, typeErrRefs, nanOld);
		int exactRest = c.size();
		JvmRuntimeBuilder.patchBranch(c, ifExactRest, exactRest);
		for (int pos : nanOld) {
			JvmRuntimeBuilder.patchBranch(c, pos, oldPath);
		}
		int[] ratJumps = emitRatioGuard(c, ratArrClass);
		int[] slowJumps = emitLongLongGuard(c, longClass);
		emitUnboxLong(c, Opcode.ALOAD_0, longClass, longValue);
		emitUnboxLong(c, Opcode.ALOAD_1, longClass, longValue);
		c.add(Opcode.LCMP);
		c.add(Opcode.IRETURN);
		int slow = c.size();
		JvmRuntimeBuilder.patchBranch(c, slowJumps[0], slow);
		JvmRuntimeBuilder.patchBranch(c, slowJumps[1], slow);
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, rBig.index());
		c.add(Opcode.ALOAD_1);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, rBig.index());
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, biCompareTo.index());
		c.add(Opcode.IRETURN);
		int rat = c.size();
		JvmRuntimeBuilder.patchBranch(c, ratJumps[0], rat);
		JvmRuntimeBuilder.patchBranch(c, ratJumps[1], rat);
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, rRatNum.index());
		c.add(Opcode.ALOAD_1);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, rRatDen.index());
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, biMul.index());
		c.add(Opcode.ALOAD_1);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, rRatNum.index());
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, rRatDen.index());
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, biMul.index());
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, biCompareTo.index());
		c.add(Opcode.IRETURN);
		return new NumericMethod(name, desc, c, 4, 5, List.of());
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
		List<Integer> c = new ArrayList<>();
		if (rcClass != null) {
			// A complex operand compares part-wise: equal exactly when both part
			// pairs are _cmp-equal (a real counts as a zero-imagined complex, so
			// (= 2.0 #C(2.0 0.0)) is true); anything else answers unordered, which
			// fails every operator. Ordering over a complex never reaches here --
			// the gated call sites use _ccmpb, which signals. Like the _abs arm,
			// emitted only for a complex-capable program. The presence probe first:
			// a lone class run without the travelling file must not resolve the
			// holder class it then never touches (.todo/757).
			int noHolder = emitNoHolderJump(c, hasComplex);
			ClassConstant complexClass = Objects.requireNonNull(rcClass);
			FieldrefConstant complexReal = Objects.requireNonNull(rcReal);
			FieldrefConstant complexImag = Objects.requireNonNull(rcImag);
			c.add(Opcode.ALOAD_0);
			c.add(Opcode.INSTANCEOF);
			JvmRuntimeBuilder.emitU2(c, complexClass.index());
			int ifAReal = c.size();
			c.add(Opcode.IFEQ);
			JvmRuntimeBuilder.emitU2(c, 0);
			int toComplex = c.size();
			c.add(Opcode.GOTO);
			JvmRuntimeBuilder.emitU2(c, 0);
			JvmRuntimeBuilder.patchBranch(c, ifAReal, c.size());
			c.add(Opcode.ALOAD_1);
			c.add(Opcode.INSTANCEOF);
			JvmRuntimeBuilder.emitU2(c, complexClass.index());
			int ifNotComplex = c.size();
			c.add(Opcode.IFEQ);
			JvmRuntimeBuilder.emitU2(c, 0);
			JvmRuntimeBuilder.patchBranch(c, toComplex, c.size());
			emitComplexPart(c, Opcode.ALOAD_0, complexClass, complexReal);
			c.add(Opcode.ASTORE_2);
			emitComplexImag(c, Opcode.ALOAD_0, complexClass, complexImag, longValueOf);
			c.add(Opcode.ASTORE_3);
			emitComplexPart(c, Opcode.ALOAD_1, complexClass, complexReal);
			c.add(Opcode.ASTORE);
			c.add(4);
			emitComplexImag(c, Opcode.ALOAD_1, complexClass, complexImag, longValueOf);
			c.add(Opcode.ASTORE);
			c.add(5);
			c.add(Opcode.ALOAD_2);
			c.add(Opcode.ALOAD);
			c.add(4);
			c.add(Opcode.INVOKESTATIC);
			JvmRuntimeBuilder.emitU2(c, rCmp.index());
			int ifReNe = c.size();
			c.add(Opcode.IFNE);
			JvmRuntimeBuilder.emitU2(c, 0);
			c.add(Opcode.ALOAD_3);
			c.add(Opcode.ALOAD);
			c.add(5);
			c.add(Opcode.INVOKESTATIC);
			JvmRuntimeBuilder.emitU2(c, rCmp.index());
			int ifImNe = c.size();
			c.add(Opcode.IFNE);
			JvmRuntimeBuilder.emitU2(c, 0);
			c.add(Opcode.ICONST_2);
			c.add(Opcode.IRETURN);
			JvmRuntimeBuilder.patchBranch(c, ifReNe, c.size());
			JvmRuntimeBuilder.patchBranch(c, ifImNe, c.size());
			c.add(Opcode.ICONST_0);
			c.add(Opcode.IRETURN);
			JvmRuntimeBuilder.patchBranch(c, ifNotComplex, c.size());
			JvmRuntimeBuilder.patchBranch(c, noHolder, c.size());
		}
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.INSTANCEOF);
		JvmRuntimeBuilder.emitU2(c, doubleClass.index());
		int ifANotDouble = c.size();
		c.add(Opcode.IFEQ);
		JvmRuntimeBuilder.emitU2(c, 0);
		c.add(Opcode.ALOAD_1);
		c.add(Opcode.INSTANCEOF);
		JvmRuntimeBuilder.emitU2(c, doubleClass.index());
		int ifMixedA = c.size();
		c.add(Opcode.IFEQ);
		JvmRuntimeBuilder.emitU2(c, 0);
		// x -> locals 2/3, y -> locals 4/5
		emitToDouble(c, Opcode.ALOAD_0, rDbl, numberClass, numDoubleValue);
		c.add(Opcode.DSTORE_2);
		emitToDouble(c, Opcode.ALOAD_1, rDbl, numberClass, numDoubleValue);
		c.add(Opcode.DSTORE);
		c.add(4);
		// x < y -> 1 (DCMPG: NaN falls out as +1, so IFGE skips)
		c.add(Opcode.DLOAD_2);
		c.add(Opcode.DLOAD);
		c.add(4);
		c.add(Opcode.DCMPG);
		int notLt = c.size();
		c.add(Opcode.IFGE);
		JvmRuntimeBuilder.emitU2(c, 0);
		c.add(Opcode.ICONST_1);
		c.add(Opcode.IRETURN);
		JvmRuntimeBuilder.patchBranch(c, notLt, c.size());
		// x > y -> 4 (DCMPL: NaN falls out as -1, so IFLE skips)
		c.add(Opcode.DLOAD_2);
		c.add(Opcode.DLOAD);
		c.add(4);
		c.add(Opcode.DCMPL);
		int notGt = c.size();
		c.add(Opcode.IFLE);
		JvmRuntimeBuilder.emitU2(c, 0);
		c.add(Opcode.ICONST_4);
		c.add(Opcode.IRETURN);
		JvmRuntimeBuilder.patchBranch(c, notGt, c.size());
		// x == y -> 2, else unordered -> 0 (only NaN reaches here unequal)
		c.add(Opcode.DLOAD_2);
		c.add(Opcode.DLOAD);
		c.add(4);
		c.add(Opcode.DCMPL);
		int notEq = c.size();
		c.add(Opcode.IFNE);
		JvmRuntimeBuilder.emitU2(c, 0);
		c.add(Opcode.ICONST_2);
		c.add(Opcode.IRETURN);
		JvmRuntimeBuilder.patchBranch(c, notEq, c.size());
		c.add(Opcode.ICONST_0);
		c.add(Opcode.IRETURN);
		// Exactly one double: the exact comparison (every path returns).
		int mixedA = c.size();
		JvmRuntimeBuilder.patchBranch(c, ifMixedA, mixedA);
		emitExactFloatCompare(c, Opcode.ALOAD_0, Opcode.ALOAD_1, true, true, numberClass, numDoubleValue, rFrat,
				ratArrClass, bigClass, longClass, rRatNum, rRatDen, biMul, biCompareTo, intSignum, typeErrRefs, null);
		int aNotDouble = c.size();
		JvmRuntimeBuilder.patchBranch(c, ifANotDouble, aNotDouble);
		c.add(Opcode.ALOAD_1);
		c.add(Opcode.INSTANCEOF);
		JvmRuntimeBuilder.emitU2(c, doubleClass.index());
		int ifExactTail = c.size();
		c.add(Opcode.IFEQ);
		JvmRuntimeBuilder.emitU2(c, 0);
		emitExactFloatCompare(c, Opcode.ALOAD_1, Opcode.ALOAD_0, false, true, numberClass, numDoubleValue, rFrat,
				ratArrClass, bigClass, longClass, rRatNum, rRatDen, biMul, biCompareTo, intSignum, typeErrRefs, null);
		// exact types: 1 << (signum(_cmp(a, b)) + 1)
		int exactTail = c.size();
		JvmRuntimeBuilder.patchBranch(c, ifExactTail, exactTail);
		c.add(Opcode.ICONST_1);
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.ALOAD_1);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, rCmp.index());
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, intSignum.index());
		c.add(Opcode.ICONST_1);
		c.add(Opcode.IADD);
		c.add(Opcode.ISHL);
		c.add(Opcode.IRETURN);
		// maxStack 5: the mixed float/exact cross holds 1, left and right (three
		// references) while loading the right denominator.
		return new NumericMethod(name, desc, c, 5, 6, List.of());
	}

	// _ccmpb(Object a, Object b): like _cmpb, but a complex operand signals the
	// interpreter's "Expected real number" text instead of comparing -- the
	// ordering operators' comparison once a complex literal steered them off
	// the double path (`.kb/jvm-complex.md`).
	/**
	 * Emits {@code throw new RuntimeException("Expected real number, got: " +
	 * _lispToString(value))} for the value loaded by {@code loadOpcode}.
	 */
	private static void emitRealErrThrow(List<Integer> c, TypeErrRefs refs, int loadOpcode) {
		c.add(Opcode.NEW);
		JvmRuntimeBuilder.emitU2(c, refs.rte().index());
		c.add(Opcode.DUP);
		JvmRuntimeBuilder.emitLdc(c, refs.realPrefix().index());
		c.add(loadOpcode);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, refs.lispToString().index());
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, refs.strConcat().index());
		c.add(Opcode.INVOKESPECIAL);
		JvmRuntimeBuilder.emitU2(c, refs.rteInit().index());
		c.add(Opcode.ATHROW);
	}

	/**
	 * Emits the real part of the value loaded by {@code loadOpcode}: the holder's field,
	 * or the value itself.
	 */
	private static void emitComplexPart(List<Integer> c, int loadOpcode, ClassConstant rcClass,
			FieldrefConstant rcReal) {
		c.add(loadOpcode);
		c.add(Opcode.INSTANCEOF);
		JvmRuntimeBuilder.emitU2(c, rcClass.index());
		int ifReal = c.size();
		c.add(Opcode.IFEQ);
		JvmRuntimeBuilder.emitU2(c, 0);
		c.add(loadOpcode);
		c.add(Opcode.CHECKCAST);
		JvmRuntimeBuilder.emitU2(c, rcClass.index());
		c.add(Opcode.GETFIELD);
		JvmRuntimeBuilder.emitU2(c, rcReal.index());
		int done = c.size();
		c.add(Opcode.GOTO);
		JvmRuntimeBuilder.emitU2(c, 0);
		JvmRuntimeBuilder.patchBranch(c, ifReal, c.size());
		c.add(loadOpcode);
		JvmRuntimeBuilder.patchBranch(c, done, c.size());
	}

	/**
	 * Emits the holder-presence probe for a holder arm: falls through when a holder
	 * instance can exist (the travelling class loaded), and returns the branch position
	 * to patch to the arm's end otherwise -- then the holder-less shape that follows is
	 * exact, because no holder instance can exist without its class (.todo/757).
	 */
	private static int emitNoHolderJump(List<Integer> c, @Nullable FieldrefConstant hasComplex) {
		c.add(Opcode.GETSTATIC);
		JvmRuntimeBuilder.emitU2(c, Objects.requireNonNull(hasComplex).index());
		int pos = c.size();
		c.add(Opcode.IFEQ);
		JvmRuntimeBuilder.emitU2(c, 0);
		return pos;
	}

	/**
	 * Emits the imaginary part of the value loaded by {@code loadOpcode}: the holder's
	 * field, or an integer zero (float contagion is decided by the real parts in every
	 * caller, so the zero's own kind never matters).
	 */
	private static void emitComplexImag(List<Integer> c, int loadOpcode, ClassConstant rcClass, FieldrefConstant rcImag,
			MethodrefConstant longValueOf) {
		c.add(loadOpcode);
		c.add(Opcode.INSTANCEOF);
		JvmRuntimeBuilder.emitU2(c, rcClass.index());
		int ifReal = c.size();
		c.add(Opcode.IFEQ);
		JvmRuntimeBuilder.emitU2(c, 0);
		c.add(loadOpcode);
		c.add(Opcode.CHECKCAST);
		JvmRuntimeBuilder.emitU2(c, rcClass.index());
		c.add(Opcode.GETFIELD);
		JvmRuntimeBuilder.emitU2(c, rcImag.index());
		int done = c.size();
		c.add(Opcode.GOTO);
		JvmRuntimeBuilder.emitU2(c, 0);
		JvmRuntimeBuilder.patchBranch(c, ifReal, c.size());
		c.add(Opcode.LCONST_0);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, longValueOf.index());
		JvmRuntimeBuilder.patchBranch(c, done, c.size());
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
		List<Integer> c = new ArrayList<>();
		if (rcClass != null) {
			// A complex operand answers its float modulus -- hypot over the double
			// parts, a real even for exact parts like the interpreter. Emitted
			// only for a complex-capable program, so the holder class the test
			// resolves stays out of every other constant pool. The presence probe
			// first, so a lone class without the file never resolves it
			// (.todo/757).
			int noHolder = emitNoHolderJump(c, hasComplex);
			ClassConstant complexClass = Objects.requireNonNull(rcClass);
			FieldrefConstant complexReal = Objects.requireNonNull(rcReal);
			FieldrefConstant complexImag = Objects.requireNonNull(rcImag);
			MethodrefConstant hypot = Objects.requireNonNull(mathHypot);
			c.add(Opcode.ALOAD_0);
			c.add(Opcode.INSTANCEOF);
			JvmRuntimeBuilder.emitU2(c, complexClass.index());
			int ifNotComplex = c.size();
			c.add(Opcode.IFEQ);
			JvmRuntimeBuilder.emitU2(c, 0);
			c.add(Opcode.ALOAD_0);
			c.add(Opcode.CHECKCAST);
			JvmRuntimeBuilder.emitU2(c, complexClass.index());
			c.add(Opcode.GETFIELD);
			JvmRuntimeBuilder.emitU2(c, complexReal.index());
			c.add(Opcode.INVOKESTATIC);
			JvmRuntimeBuilder.emitU2(c, rDbl.index());
			c.add(Opcode.CHECKCAST);
			JvmRuntimeBuilder.emitU2(c, numberClass.index());
			c.add(Opcode.INVOKEVIRTUAL);
			JvmRuntimeBuilder.emitU2(c, numDoubleValue.index());
			c.add(Opcode.ALOAD_0);
			c.add(Opcode.CHECKCAST);
			JvmRuntimeBuilder.emitU2(c, complexClass.index());
			c.add(Opcode.GETFIELD);
			JvmRuntimeBuilder.emitU2(c, complexImag.index());
			c.add(Opcode.INVOKESTATIC);
			JvmRuntimeBuilder.emitU2(c, rDbl.index());
			c.add(Opcode.CHECKCAST);
			JvmRuntimeBuilder.emitU2(c, numberClass.index());
			c.add(Opcode.INVOKEVIRTUAL);
			JvmRuntimeBuilder.emitU2(c, numDoubleValue.index());
			c.add(Opcode.INVOKESTATIC);
			JvmRuntimeBuilder.emitU2(c, hypot.index());
			c.add(Opcode.INVOKESTATIC);
			JvmRuntimeBuilder.emitU2(c, doubleValueOf.index());
			c.add(Opcode.ARETURN);
			JvmRuntimeBuilder.patchBranch(c, ifNotComplex, c.size());
			JvmRuntimeBuilder.patchBranch(c, noHolder, c.size());
		}
		// Double fast path: Math.abs((double) a) when a is a Double.
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.INSTANCEOF);
		JvmRuntimeBuilder.emitU2(c, doubleClass.index());
		int ifNotDouble = c.size();
		c.add(Opcode.IFEQ);
		JvmRuntimeBuilder.emitU2(c, 0);
		emitToDouble(c, Opcode.ALOAD_0, rDbl, numberClass, numDoubleValue);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, absDouble.index());
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, doubleValueOf.index());
		c.add(Opcode.ARETURN);
		JvmRuntimeBuilder.patchBranch(c, ifNotDouble, c.size());
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.INSTANCEOF);
		JvmRuntimeBuilder.emitU2(c, ratArrClass.index());
		int ifRat = c.size();
		c.add(Opcode.IFNE);
		JvmRuntimeBuilder.emitU2(c, 0);
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.INSTANCEOF);
		JvmRuntimeBuilder.emitU2(c, longClass.index());
		int ifBig = c.size();
		c.add(Opcode.IFEQ);
		JvmRuntimeBuilder.emitU2(c, 0);
		emitUnboxLong(c, Opcode.ALOAD_0, longClass, longValue);
		c.add(Opcode.LSTORE);
		c.add(2);
		emitLload(c, 2);
		emitLdc2(c, cMin);
		c.add(Opcode.LCMP);
		int ifNeMin = c.size();
		c.add(Opcode.IFNE);
		JvmRuntimeBuilder.emitU2(c, 0);
		// Overflow: BigInteger.valueOf(Long.MIN_VALUE).negate().
		emitLload(c, 2);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, biValueOf.index());
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, biNeg.index());
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, rNorm.index());
		c.add(Opcode.ARETURN);
		int pos = c.size();
		JvmRuntimeBuilder.patchBranch(c, ifNeMin, pos);
		emitLload(c, 2);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, absLong.index());
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, longValueOf.index());
		c.add(Opcode.ARETURN);
		int big = c.size();
		JvmRuntimeBuilder.patchBranch(c, ifBig, big);
		// Through _big rather than a bare checkcast, so a non-number (which null-passes
		// a checkcast and NPEs inside BigInteger.abs) throws the "Expected integer"
		// text at the coercion like every other operator.
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, rBig.index());
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, biAbs.index());
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, rNorm.index());
		c.add(Opcode.ARETURN);
		JvmRuntimeBuilder.patchBranch(c, ifRat, c.size());
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, rRatNum.index());
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, biAbs.index());
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, rRatDen.index());
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, rRat.index());
		c.add(Opcode.ARETURN);
		return new NumericMethod(name, desc, c, 4, 4, List.of());
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
		List<Integer> c = new ArrayList<>();
		if (rcClass != null) {
			// A complex operand answers the gated _csignum unit vector, like the
			// interpreter. Emitted only for a complex-capable program, so the
			// holder class the test resolves stays out of every other constant
			// pool (the _abs arm pattern). The presence probe first, so a lone
			// class without the file never resolves it (.todo/757).
			int noHolder = emitNoHolderJump(c, hasComplex);
			c.add(Opcode.ALOAD_0);
			c.add(Opcode.INSTANCEOF);
			JvmRuntimeBuilder.emitU2(c, rcClass.index());
			int ifNotComplex = c.size();
			c.add(Opcode.IFEQ);
			JvmRuntimeBuilder.emitU2(c, 0);
			c.add(Opcode.ALOAD_0);
			c.add(Opcode.INVOKESTATIC);
			JvmRuntimeBuilder.emitU2(c, java.util.Objects.requireNonNull(rCsignum).index());
			c.add(Opcode.ARETURN);
			JvmRuntimeBuilder.patchBranch(c, ifNotComplex, c.size());
			JvmRuntimeBuilder.patchBranch(c, noHolder, c.size());
		}
		// Double fast path: Math.signum((double) a).
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.INSTANCEOF);
		JvmRuntimeBuilder.emitU2(c, doubleClass.index());
		int ifNotDouble = c.size();
		c.add(Opcode.IFEQ);
		JvmRuntimeBuilder.emitU2(c, 0);
		emitToDouble(c, Opcode.ALOAD_0, rDbl, numberClass, numDoubleValue);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, signumDouble.index());
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, doubleValueOf.index());
		c.add(Opcode.ARETURN);
		JvmRuntimeBuilder.patchBranch(c, ifNotDouble, c.size());
		// Integer/ratio path: (long) _ratnum(a).signum().
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, rRatNum.index());
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, biSignum.index());
		c.add(Opcode.I2L);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, longValueOf.index());
		c.add(Opcode.ARETURN);
		return new NumericMethod(name, desc, c, 2, 1, List.of());
	}

	// _random(Object limit): a non-negative random number below limit, of the same type
	// as
	// limit. d = Math.random() * (double) limit; a Double limit returns d, otherwise the
	// truncated (long) d. Dispatching on the runtime type handles a float limit reaching
	// random through a variable; using _dbl for the multiply also makes the integer path
	// robust to a BigInteger / ratio limit.
	private static NumericMethod buildRandom(Utf8Constant name, Utf8Constant desc, ClassConstant doubleClass,
			MethodrefConstant rDbl, ClassConstant numberClass, MethodrefConstant numDoubleValue,
			MethodrefConstant doubleValueOf, MethodrefConstant longValueOf, MethodrefConstant tlrCurrent,
			MethodrefConstant tlrNextDouble) {
		List<Integer> c = new ArrayList<>();
		// d = ThreadLocalRandom.current().nextDouble() * _dbl(limit). The per-thread
		// generator, not Math.random()'s single shared java.util.Random -- see
		// .kb/random.md.
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, tlrCurrent.index());
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, tlrNextDouble.index());
		emitToDouble(c, Opcode.ALOAD_0, rDbl, numberClass, numDoubleValue);
		c.add(Opcode.DMUL);
		// limit instanceof Double ? Double.valueOf(d) : Long.valueOf((long) d)
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.INSTANCEOF);
		JvmRuntimeBuilder.emitU2(c, doubleClass.index());
		int ifNotDouble = c.size();
		c.add(Opcode.IFEQ);
		JvmRuntimeBuilder.emitU2(c, 0);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, doubleValueOf.index());
		c.add(Opcode.ARETURN);
		JvmRuntimeBuilder.patchBranch(c, ifNotDouble, c.size());
		c.add(Opcode.D2L);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, longValueOf.index());
		c.add(Opcode.ARETURN);
		return new NumericMethod(name, desc, c, 4, 1, List.of());
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
		List<Integer> c = new ArrayList<>();
		if (rcClass != null) {
			// Ordering over a complex signals the interpreter's "Expected real
			// number" text, like the interpreter (min and max select over an
			// ordering, so both throw here). Emitted only for a
			// complex-capable program, like the _abs arm. The presence probe
			// first, so a lone class without the file never resolves it
			// (.todo/757).
			int noHolder = emitNoHolderJump(c, hasComplex);
			ClassConstant complexClass = Objects.requireNonNull(rcClass);
			c.add(Opcode.ALOAD_0);
			c.add(Opcode.INSTANCEOF);
			JvmRuntimeBuilder.emitU2(c, complexClass.index());
			int ifAReal = c.size();
			c.add(Opcode.IFEQ);
			JvmRuntimeBuilder.emitU2(c, 0);
			emitRealErrThrow(c, typeErrRefs, Opcode.ALOAD_0);
			JvmRuntimeBuilder.patchBranch(c, ifAReal, c.size());
			c.add(Opcode.ALOAD_1);
			c.add(Opcode.INSTANCEOF);
			JvmRuntimeBuilder.emitU2(c, complexClass.index());
			int ifBReal = c.size();
			c.add(Opcode.IFEQ);
			JvmRuntimeBuilder.emitU2(c, 0);
			emitRealErrThrow(c, typeErrRefs, Opcode.ALOAD_1);
			JvmRuntimeBuilder.patchBranch(c, ifBReal, c.size());
			JvmRuntimeBuilder.patchBranch(c, noHolder, c.size());
		}
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.ALOAD_1);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, rCmpb.index());
		c.add(Opcode.BIPUSH);
		c.add(acceptMask);
		c.add(Opcode.IAND);
		int ifB = c.size();
		c.add(Opcode.IFEQ);
		JvmRuntimeBuilder.emitU2(c, 0);
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.ARETURN);
		JvmRuntimeBuilder.patchBranch(c, ifB, c.size());
		c.add(Opcode.ALOAD_1);
		c.add(Opcode.ARETURN);
		return new NumericMethod(name, desc, c, 4, 2, List.of());
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
	private static NumericMethod buildFloatSelect(Utf8Constant name, Utf8Constant desc, int cmpOp, int keepA) {
		List<Integer> c = new ArrayList<>();
		c.add(Opcode.DLOAD_0);
		c.add(Opcode.DLOAD_2);
		c.add(cmpOp);
		int ifA = c.size();
		c.add(keepA);
		JvmRuntimeBuilder.emitU2(c, 0);
		c.add(Opcode.DLOAD_2);
		c.add(Opcode.DRETURN);
		JvmRuntimeBuilder.patchBranch(c, ifA, c.size());
		c.add(Opcode.DLOAD_0);
		c.add(Opcode.DRETURN);
		return new NumericMethod(name, desc, c, 4, 4, List.of());
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
		List<Integer> c = new ArrayList<>();
		if (rcClass != null) {
			// A complex reaching the f64 coercion is not silently reduced to its
			// real part: it throws the interpreter's "Expected real number" text.
			// Emitted only for a complex-capable program, so the holder class stays
			// out of every other constant pool (the _abs arm pattern). The presence
			// probe first, so a lone class without the file never resolves it
			// (.todo/757).
			int noHolder = emitNoHolderJump(c, hasComplex);
			c.add(Opcode.ALOAD_0);
			c.add(Opcode.INSTANCEOF);
			JvmRuntimeBuilder.emitU2(c, rcClass.index());
			int ifNotComplex = c.size();
			c.add(Opcode.IFEQ);
			JvmRuntimeBuilder.emitU2(c, 0);
			emitRealErrThrow(c, typeErrRefs);
			JvmRuntimeBuilder.patchBranch(c, ifNotComplex, c.size());
			JvmRuntimeBuilder.patchBranch(c, noHolder, c.size());
		}
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.INSTANCEOF);
		JvmRuntimeBuilder.emitU2(c, doubleClass.index());
		int ifNotDouble = c.size();
		c.add(Opcode.IFEQ);
		JvmRuntimeBuilder.emitU2(c, 0);
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.ARETURN);
		JvmRuntimeBuilder.patchBranch(c, ifNotDouble, c.size());
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.INSTANCEOF);
		JvmRuntimeBuilder.emitU2(c, ratArrClass.index());
		int ifNotRat = c.size();
		c.add(Opcode.IFEQ);
		JvmRuntimeBuilder.emitU2(c, 0);
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, rRatNum.index());
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, rRatDen.index());
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, rRatToDouble.index());
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, doubleValueOf.index());
		c.add(Opcode.ARETURN);
		JvmRuntimeBuilder.patchBranch(c, ifNotRat, c.size());
		// A Long or BigInteger widens through Number.doubleValue(); anything else throws
		// the interpreter's "Expected number" text (the checkcast alone let null through
		// to an NPE naming Number internals). One instanceof on the non-double slow arm
		// only -- the Double fast arm above is byte-identical.
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.INSTANCEOF);
		JvmRuntimeBuilder.emitU2(c, numberClass.index());
		int ifNotNumber = c.size();
		c.add(Opcode.IFEQ);
		JvmRuntimeBuilder.emitU2(c, 0);
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.CHECKCAST);
		JvmRuntimeBuilder.emitU2(c, numberClass.index());
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, numDoubleValue.index());
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, doubleValueOf.index());
		c.add(Opcode.ARETURN);
		JvmRuntimeBuilder.patchBranch(c, ifNotNumber, c.size());
		emitTypeErrThrow(c, typeErrRefs, true);
		return new NumericMethod(name, desc, c, 5, 1, List.of());
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
		List<Integer> c = new ArrayList<>();
		// n = num.signum() < 0 ? num.negate() : num
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, biSignum.index());
		int ifNumNonNeg = c.size();
		c.add(Opcode.IFGE);
		JvmRuntimeBuilder.emitU2(c, 0);
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, biNeg.index());
		c.add(Opcode.ASTORE_2);
		int toNAbs = c.size();
		c.add(Opcode.GOTO);
		JvmRuntimeBuilder.emitU2(c, 0);
		JvmRuntimeBuilder.patchBranch(c, ifNumNonNeg, c.size());
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.ASTORE_2);
		JvmRuntimeBuilder.patchBranch(c, toNAbs, c.size());
		// d = den.signum() < 0 ? den.negate() : den
		c.add(Opcode.ALOAD_1);
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, biSignum.index());
		int ifDenNonNeg = c.size();
		c.add(Opcode.IFGE);
		JvmRuntimeBuilder.emitU2(c, 0);
		c.add(Opcode.ALOAD_1);
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, biNeg.index());
		c.add(Opcode.ASTORE_3);
		int toDAbs = c.size();
		c.add(Opcode.GOTO);
		JvmRuntimeBuilder.emitU2(c, 0);
		JvmRuntimeBuilder.patchBranch(c, ifDenNonNeg, c.size());
		c.add(Opcode.ALOAD_1);
		c.add(Opcode.ASTORE_3);
		JvmRuntimeBuilder.patchBranch(c, toDAbs, c.size());
		// neg = num.signum() < 0 ? 1 : 0
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, biSignum.index());
		int ifNegFalse = c.size();
		c.add(Opcode.IFGE);
		JvmRuntimeBuilder.emitU2(c, 0);
		c.add(Opcode.ICONST_1);
		c.add(Opcode.ISTORE);
		c.add(5);
		int toNegEnd = c.size();
		c.add(Opcode.GOTO);
		JvmRuntimeBuilder.emitU2(c, 0);
		JvmRuntimeBuilder.patchBranch(c, ifNegFalse, c.size());
		c.add(Opcode.ICONST_0);
		c.add(Opcode.ISTORE);
		c.add(5);
		JvmRuntimeBuilder.patchBranch(c, toNegEnd, c.size());
		// exp = n.bitLength() - d.bitLength()
		c.add(Opcode.ALOAD_2);
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, biBitLength.index());
		c.add(Opcode.ALOAD_3);
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, biBitLength.index());
		c.add(Opcode.ISUB);
		c.add(Opcode.ISTORE);
		c.add(4);
		// exp = floor(log2(n/d)): decrement when the shifted denominator overshoots.
		c.add(Opcode.ILOAD);
		c.add(4);
		int ifExpNeg = c.size();
		c.add(Opcode.IFLT);
		JvmRuntimeBuilder.emitU2(c, 0);
		c.add(Opcode.ALOAD_2);
		c.add(Opcode.ALOAD_3);
		c.add(Opcode.ILOAD);
		c.add(4);
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, biShiftLeft.index());
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, biCompareTo.index());
		int ifNoDecPos = c.size();
		c.add(Opcode.IFGE);
		JvmRuntimeBuilder.emitU2(c, 0);
		c.add(Opcode.ILOAD);
		c.add(4);
		c.add(Opcode.ICONST_1);
		c.add(Opcode.ISUB);
		c.add(Opcode.ISTORE);
		c.add(4);
		int toExpDonePos = c.size();
		c.add(Opcode.GOTO);
		JvmRuntimeBuilder.emitU2(c, 0);
		JvmRuntimeBuilder.patchBranch(c, ifExpNeg, c.size());
		c.add(Opcode.ALOAD_2);
		c.add(Opcode.ILOAD);
		c.add(4);
		c.add(Opcode.INEG);
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, biShiftLeft.index());
		c.add(Opcode.ALOAD_3);
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, biCompareTo.index());
		int ifNoDecNeg = c.size();
		c.add(Opcode.IFGE);
		JvmRuntimeBuilder.emitU2(c, 0);
		c.add(Opcode.ILOAD);
		c.add(4);
		c.add(Opcode.ICONST_1);
		c.add(Opcode.ISUB);
		c.add(Opcode.ISTORE);
		c.add(4);
		JvmRuntimeBuilder.patchBranch(c, toExpDonePos, c.size());
		JvmRuntimeBuilder.patchBranch(c, ifNoDecPos, c.size());
		JvmRuntimeBuilder.patchBranch(c, ifNoDecNeg, c.size());
		// if (exp > 1023) return neg ? -Infinity : +Infinity
		c.add(Opcode.ILOAD);
		c.add(4);
		c.add(Opcode.SIPUSH);
		c.add(0x03);
		c.add(0xFF);
		int ifNoOverflow = c.size();
		c.add(Opcode.IF_ICMPLE);
		JvmRuntimeBuilder.emitU2(c, 0);
		emitSignedInfinity(c, 5, dblNegInf, dblPosInf);
		JvmRuntimeBuilder.patchBranch(c, ifNoOverflow, c.size());
		// if (exp < -1022) goto the subnormal path
		c.add(Opcode.ILOAD);
		c.add(4);
		c.add(Opcode.SIPUSH);
		c.add(0xFC);
		c.add(0x02);
		int ifSubnormal = c.size();
		c.add(Opcode.IF_ICMPLT);
		JvmRuntimeBuilder.emitU2(c, 0);
		// Normal path: shift = 55 - exp; q = floor(scaled / divisor) holds 56 bits.
		c.add(Opcode.BIPUSH);
		c.add(55);
		c.add(Opcode.ILOAD);
		c.add(4);
		c.add(Opcode.ISUB);
		c.add(Opcode.ISTORE);
		c.add(9);
		c.add(Opcode.ILOAD);
		c.add(9);
		int ifShiftNeg = c.size();
		c.add(Opcode.IFLT);
		JvmRuntimeBuilder.emitU2(c, 0);
		c.add(Opcode.ALOAD_2);
		c.add(Opcode.ILOAD);
		c.add(9);
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, biShiftLeft.index());
		c.add(Opcode.ASTORE);
		c.add(6);
		c.add(Opcode.ALOAD);
		c.add(6);
		c.add(Opcode.ALOAD_3);
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, biDiv.index());
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, biLongValue.index());
		c.add(Opcode.LSTORE);
		c.add(7);
		c.add(Opcode.ALOAD);
		c.add(6);
		c.add(Opcode.ALOAD_3);
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, biRem.index());
		c.add(Opcode.ASTORE);
		c.add(6);
		int toDivDone = c.size();
		c.add(Opcode.GOTO);
		JvmRuntimeBuilder.emitU2(c, 0);
		JvmRuntimeBuilder.patchBranch(c, ifShiftNeg, c.size());
		c.add(Opcode.ALOAD_3);
		c.add(Opcode.ILOAD);
		c.add(9);
		c.add(Opcode.INEG);
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, biShiftLeft.index());
		c.add(Opcode.ASTORE);
		c.add(6);
		c.add(Opcode.ALOAD_2);
		c.add(Opcode.ALOAD);
		c.add(6);
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, biDiv.index());
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, biLongValue.index());
		c.add(Opcode.LSTORE);
		c.add(7);
		c.add(Opcode.ALOAD_2);
		c.add(Opcode.ALOAD);
		c.add(6);
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, biRem.index());
		c.add(Opcode.ASTORE);
		c.add(6);
		JvmRuntimeBuilder.patchBranch(c, toDivDone, c.size());
		// round = (q & 4) != 0; round up when set and (sticky || the mantissa is odd).
		emitLload(c, 7);
		emitLdc2(c, c4);
		c.add(Opcode.LAND);
		c.add(Opcode.LCONST_0);
		c.add(Opcode.LCMP);
		int ifNoRound = c.size();
		c.add(Opcode.IFEQ);
		JvmRuntimeBuilder.emitU2(c, 0);
		emitLload(c, 7);
		emitLdc2(c, c3);
		c.add(Opcode.LAND);
		c.add(Opcode.LCONST_0);
		c.add(Opcode.LCMP);
		int ifLowStickyRound = c.size();
		c.add(Opcode.IFNE);
		JvmRuntimeBuilder.emitU2(c, 0);
		c.add(Opcode.ALOAD);
		c.add(6);
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, biSignum.index());
		int ifRemStickyRound = c.size();
		c.add(Opcode.IFNE);
		JvmRuntimeBuilder.emitU2(c, 0);
		emitLload(c, 7);
		c.add(Opcode.ICONST_3);
		c.add(Opcode.LUSHR);
		c.add(Opcode.LCONST_1);
		c.add(Opcode.LAND);
		c.add(Opcode.LCONST_0);
		c.add(Opcode.LCMP);
		int ifNoRoundTie = c.size();
		c.add(Opcode.IFEQ);
		JvmRuntimeBuilder.emitU2(c, 0);
		// m = (q >>> 3) + 1, reached by a sticky bit or an odd tie.
		int doRoundUp = c.size();
		JvmRuntimeBuilder.patchBranch(c, ifLowStickyRound, doRoundUp);
		JvmRuntimeBuilder.patchBranch(c, ifRemStickyRound, doRoundUp);
		emitLload(c, 7);
		c.add(Opcode.ICONST_3);
		c.add(Opcode.LUSHR);
		c.add(Opcode.LCONST_1);
		c.add(Opcode.LADD);
		c.add(Opcode.LSTORE);
		c.add(7);
		int toRounded = c.size();
		c.add(Opcode.GOTO);
		JvmRuntimeBuilder.emitU2(c, 0);
		JvmRuntimeBuilder.patchBranch(c, ifNoRound, c.size());
		JvmRuntimeBuilder.patchBranch(c, ifNoRoundTie, c.size());
		// m = q >>> 3
		emitLload(c, 7);
		c.add(Opcode.ICONST_3);
		c.add(Opcode.LUSHR);
		c.add(Opcode.LSTORE);
		c.add(7);
		JvmRuntimeBuilder.patchBranch(c, toRounded, c.size());
		// e = exp; if (m == 2^53) { m >>>= 1; e++ }
		c.add(Opcode.ILOAD);
		c.add(4);
		c.add(Opcode.ISTORE);
		c.add(10);
		emitLload(c, 7);
		emitLdc2(c, c2p53);
		c.add(Opcode.LCMP);
		int ifNoNorm = c.size();
		c.add(Opcode.IFNE);
		JvmRuntimeBuilder.emitU2(c, 0);
		emitLload(c, 7);
		c.add(Opcode.ICONST_1);
		c.add(Opcode.LUSHR);
		c.add(Opcode.LSTORE);
		c.add(7);
		c.add(Opcode.ILOAD);
		c.add(10);
		c.add(Opcode.ICONST_1);
		c.add(Opcode.IADD);
		c.add(Opcode.ISTORE);
		c.add(10);
		JvmRuntimeBuilder.patchBranch(c, ifNoNorm, c.size());
		// if (e > 1023) return neg ? -Infinity : +Infinity
		c.add(Opcode.ILOAD);
		c.add(10);
		c.add(Opcode.SIPUSH);
		c.add(0x03);
		c.add(0xFF);
		int ifNoOverflow2 = c.size();
		c.add(Opcode.IF_ICMPLE);
		JvmRuntimeBuilder.emitU2(c, 0);
		emitSignedInfinity(c, 5, dblNegInf, dblPosInf);
		JvmRuntimeBuilder.patchBranch(c, ifNoOverflow2, c.size());
		// bits = (((long)(e + 1023)) << 52) | (m & mask)
		c.add(Opcode.ILOAD);
		c.add(10);
		c.add(Opcode.SIPUSH);
		c.add(0x03);
		c.add(0xFF);
		c.add(Opcode.IADD);
		c.add(Opcode.I2L);
		c.add(Opcode.BIPUSH);
		c.add(52);
		c.add(Opcode.LSHL);
		emitLload(c, 7);
		emitLdc2(c, cFracMask);
		c.add(Opcode.LAND);
		c.add(Opcode.LOR);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, longBitsToDouble.index());
		int toSignTail = c.size();
		c.add(Opcode.GOTO);
		JvmRuntimeBuilder.emitU2(c, 0);
		// Subnormal path: k = round-half-even(n * 2^1074 / d), the mantissa directly.
		JvmRuntimeBuilder.patchBranch(c, ifSubnormal, c.size());
		c.add(Opcode.ALOAD_2);
		c.add(Opcode.SIPUSH);
		c.add(0x04);
		c.add(0x32);
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, biShiftLeft.index());
		c.add(Opcode.ASTORE);
		c.add(6);
		c.add(Opcode.ALOAD);
		c.add(6);
		c.add(Opcode.ALOAD_3);
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, biDiv.index());
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, biLongValue.index());
		c.add(Opcode.LSTORE);
		c.add(7);
		c.add(Opcode.ALOAD);
		c.add(6);
		c.add(Opcode.ALOAD_3);
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, biRem.index());
		c.add(Opcode.ASTORE);
		c.add(6);
		c.add(Opcode.ALOAD);
		c.add(6);
		c.add(Opcode.ICONST_1);
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, biShiftLeft.index());
		c.add(Opcode.ALOAD_3);
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, biCompareTo.index());
		c.add(Opcode.ISTORE);
		c.add(9);
		c.add(Opcode.ILOAD);
		c.add(9);
		int ifSubUp = c.size();
		c.add(Opcode.IFGT);
		JvmRuntimeBuilder.emitU2(c, 0);
		c.add(Opcode.ILOAD);
		c.add(9);
		int ifSubDone = c.size();
		c.add(Opcode.IFNE);
		JvmRuntimeBuilder.emitU2(c, 0);
		emitLload(c, 7);
		c.add(Opcode.LCONST_1);
		c.add(Opcode.LAND);
		c.add(Opcode.LCONST_0);
		c.add(Opcode.LCMP);
		int ifSubDoneTie = c.size();
		c.add(Opcode.IFEQ);
		JvmRuntimeBuilder.emitU2(c, 0);
		JvmRuntimeBuilder.patchBranch(c, ifSubUp, c.size());
		emitLload(c, 7);
		c.add(Opcode.LCONST_1);
		c.add(Opcode.LADD);
		c.add(Opcode.LSTORE);
		c.add(7);
		JvmRuntimeBuilder.patchBranch(c, ifSubDone, c.size());
		JvmRuntimeBuilder.patchBranch(c, ifSubDoneTie, c.size());
		emitLload(c, 7);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, longBitsToDouble.index());
		JvmRuntimeBuilder.patchBranch(c, toSignTail, c.size());
		// return neg ? -mag : mag
		c.add(Opcode.ILOAD);
		c.add(5);
		int ifRetPos = c.size();
		c.add(Opcode.IFEQ);
		JvmRuntimeBuilder.emitU2(c, 0);
		c.add(Opcode.DNEG);
		JvmRuntimeBuilder.patchBranch(c, ifRetPos, c.size());
		c.add(Opcode.DRETURN);
		return new NumericMethod(name, desc, c, 6, 11, List.of());
	}

	// return neg != 0 ? -Infinity : +Infinity for _ratToDouble's overflow arms.
	private static void emitSignedInfinity(List<Integer> c, int negSlot, FieldrefConstant dblNegInf,
			FieldrefConstant dblPosInf) {
		c.add(Opcode.ILOAD);
		c.add(negSlot);
		int ifPos = c.size();
		c.add(Opcode.IFEQ);
		JvmRuntimeBuilder.emitU2(c, 0);
		c.add(Opcode.GETSTATIC);
		JvmRuntimeBuilder.emitU2(c, dblNegInf.index());
		c.add(Opcode.DRETURN);
		JvmRuntimeBuilder.patchBranch(c, ifPos, c.size());
		c.add(Opcode.GETSTATIC);
		JvmRuntimeBuilder.emitU2(c, dblPosInf.index());
		c.add(Opcode.DRETURN);
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
		List<Integer> c = new ArrayList<>();
		// if (!(e instanceof Long)) return Double.valueOf(Math.pow(_dbl(base), _dbl(e)))
		c.add(Opcode.ALOAD_1);
		c.add(Opcode.INSTANCEOF);
		JvmRuntimeBuilder.emitU2(c, longClass.index());
		int ifLongExp = c.size();
		c.add(Opcode.IFNE);
		JvmRuntimeBuilder.emitU2(c, 0);
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, rDbl.index());
		c.add(Opcode.CHECKCAST);
		JvmRuntimeBuilder.emitU2(c, numberClass.index());
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, numDoubleValue.index());
		c.add(Opcode.ALOAD_1);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, rDbl.index());
		c.add(Opcode.CHECKCAST);
		JvmRuntimeBuilder.emitU2(c, numberClass.index());
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, numDoubleValue.index());
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, mathPow.index());
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, doubleValueOf.index());
		c.add(Opcode.ARETURN);
		JvmRuntimeBuilder.patchBranch(c, ifLongExp, c.size());
		// if (e > Integer.MAX_VALUE || e < -Integer.MAX_VALUE) return
		// Double.valueOf(Math.pow(_dbl(base), _dbl(e)))
		c.add(Opcode.ALOAD_1);
		c.add(Opcode.CHECKCAST);
		JvmRuntimeBuilder.emitU2(c, longClass.index());
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, longValue.index());
		emitLdc2(c, cPowMax);
		c.add(Opcode.LCMP);
		int ifTooBig = c.size();
		c.add(Opcode.IFGT);
		JvmRuntimeBuilder.emitU2(c, 0);
		c.add(Opcode.ALOAD_1);
		c.add(Opcode.CHECKCAST);
		JvmRuntimeBuilder.emitU2(c, longClass.index());
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, longValue.index());
		emitLdc2(c, cPowMin);
		c.add(Opcode.LCMP);
		int ifTooSmall = c.size();
		c.add(Opcode.IFLT);
		JvmRuntimeBuilder.emitU2(c, 0);
		// local 2 = (int) e
		c.add(Opcode.ALOAD_1);
		c.add(Opcode.CHECKCAST);
		JvmRuntimeBuilder.emitU2(c, longClass.index());
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, longValue.index());
		c.add(Opcode.L2I);
		c.add(Opcode.ISTORE_2);
		// if (base instanceof Double) return Double.valueOf(Math.pow(base, (double) e))
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.INSTANCEOF);
		JvmRuntimeBuilder.emitU2(c, doubleClass.index());
		int ifExact = c.size();
		c.add(Opcode.IFEQ);
		JvmRuntimeBuilder.emitU2(c, 0);
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.CHECKCAST);
		JvmRuntimeBuilder.emitU2(c, numberClass.index());
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, numDoubleValue.index());
		c.add(Opcode.ILOAD_2);
		c.add(Opcode.I2D);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, mathPow.index());
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, doubleValueOf.index());
		c.add(Opcode.ARETURN);
		JvmRuntimeBuilder.patchBranch(c, ifExact, c.size());
		c.add(Opcode.ILOAD_2);
		int ifNeg = c.size();
		c.add(Opcode.IFLT);
		JvmRuntimeBuilder.emitU2(c, 0);
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, rRatNum.index());
		c.add(Opcode.ILOAD_2);
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, biPow.index());
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, rRatDen.index());
		c.add(Opcode.ILOAD_2);
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, biPow.index());
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, rRat.index());
		c.add(Opcode.ARETURN);
		JvmRuntimeBuilder.patchBranch(c, ifNeg, c.size());
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, rRatDen.index());
		c.add(Opcode.ILOAD_2);
		c.add(Opcode.INEG);
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, biPow.index());
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, rRatNum.index());
		c.add(Opcode.ILOAD_2);
		c.add(Opcode.INEG);
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, biPow.index());
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, rRat.index());
		c.add(Opcode.ARETURN);
		// A Long exponent beyond the int range: Math.pow over the widened doubles,
		// the same shape as the non-Long arm above.
		int outOfRange = c.size();
		JvmRuntimeBuilder.patchBranch(c, ifTooBig, outOfRange);
		JvmRuntimeBuilder.patchBranch(c, ifTooSmall, outOfRange);
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, rDbl.index());
		c.add(Opcode.CHECKCAST);
		JvmRuntimeBuilder.emitU2(c, numberClass.index());
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, numDoubleValue.index());
		c.add(Opcode.ALOAD_1);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, rDbl.index());
		c.add(Opcode.CHECKCAST);
		JvmRuntimeBuilder.emitU2(c, numberClass.index());
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, numDoubleValue.index());
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, mathPow.index());
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, doubleValueOf.index());
		c.add(Opcode.ARETURN);
		return new NumericMethod(name, desc, c, 5, 3, List.of());
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
		List<Integer> c = new ArrayList<>();
		// CHARACTER compare (int[]{cp}): if both operands are length-1 int[], value
		// equality is (a[0] == b[0]). Emitted BEFORE the ratio and equals paths so a
		// character never falls through to Object.equals.
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.INSTANCEOF);
		JvmRuntimeBuilder.emitU2(c, intArrClass.index());
		int ifNotChar1 = c.size();
		c.add(Opcode.IFEQ);
		JvmRuntimeBuilder.emitU2(c, 0);
		c.add(Opcode.ALOAD_1);
		c.add(Opcode.INSTANCEOF);
		JvmRuntimeBuilder.emitU2(c, intArrClass.index());
		int ifNotChar2 = c.size();
		c.add(Opcode.IFEQ);
		JvmRuntimeBuilder.emitU2(c, 0);
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.CHECKCAST);
		JvmRuntimeBuilder.emitU2(c, intArrClass.index());
		c.add(Opcode.ICONST_0);
		c.add(Opcode.IALOAD);
		c.add(Opcode.ALOAD_1);
		c.add(Opcode.CHECKCAST);
		JvmRuntimeBuilder.emitU2(c, intArrClass.index());
		c.add(Opcode.ICONST_0);
		c.add(Opcode.IALOAD);
		int ifCpNe = c.size();
		c.add(Opcode.IF_ICMPNE);
		JvmRuntimeBuilder.emitU2(c, 0);
		c.add(Opcode.ICONST_1);
		c.add(Opcode.IRETURN);
		JvmRuntimeBuilder.patchBranch(c, ifCpNe, c.size());
		c.add(Opcode.ICONST_0);
		c.add(Opcode.IRETURN);
		JvmRuntimeBuilder.patchBranch(c, ifNotChar1, c.size());
		JvmRuntimeBuilder.patchBranch(c, ifNotChar2, c.size());
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.INSTANCEOF);
		JvmRuntimeBuilder.emitU2(c, ratArrClass.index());
		int ifObj1 = c.size();
		c.add(Opcode.IFEQ);
		JvmRuntimeBuilder.emitU2(c, 0);
		c.add(Opcode.ALOAD_1);
		c.add(Opcode.INSTANCEOF);
		JvmRuntimeBuilder.emitU2(c, ratArrClass.index());
		int ifObj2 = c.size();
		c.add(Opcode.IFEQ);
		JvmRuntimeBuilder.emitU2(c, 0);
		emitRatioElement(c, Opcode.ALOAD_0, ratArrClass, Opcode.ICONST_0);
		emitRatioElement(c, Opcode.ALOAD_1, ratArrClass, Opcode.ICONST_0);
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, objEquals.index());
		int ifFalse1 = c.size();
		c.add(Opcode.IFEQ);
		JvmRuntimeBuilder.emitU2(c, 0);
		emitRatioElement(c, Opcode.ALOAD_0, ratArrClass, Opcode.ICONST_1);
		emitRatioElement(c, Opcode.ALOAD_1, ratArrClass, Opcode.ICONST_1);
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, objEquals.index());
		int ifFalse2 = c.size();
		c.add(Opcode.IFEQ);
		JvmRuntimeBuilder.emitU2(c, 0);
		c.add(Opcode.ICONST_1);
		c.add(Opcode.IRETURN);
		JvmRuntimeBuilder.patchBranch(c, ifFalse1, c.size());
		JvmRuntimeBuilder.patchBranch(c, ifFalse2, c.size());
		c.add(Opcode.ICONST_0);
		c.add(Opcode.IRETURN);
		JvmRuntimeBuilder.patchBranch(c, ifObj1, c.size());
		JvmRuntimeBuilder.patchBranch(c, ifObj2, c.size());
		// if (a instanceof Map) return a == b
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.INSTANCEOF);
		JvmRuntimeBuilder.emitU2(c, mapClass.index());
		int ifNotMap = c.size();
		c.add(Opcode.IFEQ);
		JvmRuntimeBuilder.emitU2(c, 0);
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.ALOAD_1);
		int ifNotSame = c.size();
		c.add(Opcode.IF_ACMPNE);
		JvmRuntimeBuilder.emitU2(c, 0);
		c.add(Opcode.ICONST_1);
		c.add(Opcode.IRETURN);
		JvmRuntimeBuilder.patchBranch(c, ifNotSame, c.size());
		c.add(Opcode.ICONST_0);
		c.add(Opcode.IRETURN);
		JvmRuntimeBuilder.patchBranch(c, ifNotMap, c.size());
		// if (a instanceof List || b instanceof List || isString(a)) return a == b
		List<Integer> toIdentity = new ArrayList<>();
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.INSTANCEOF);
		JvmRuntimeBuilder.emitU2(c, strings.listClass().index());
		toIdentity.add(c.size());
		c.add(Opcode.IFNE);
		JvmRuntimeBuilder.emitU2(c, 0);
		c.add(Opcode.ALOAD_1);
		c.add(Opcode.INSTANCEOF);
		JvmRuntimeBuilder.emitU2(c, strings.listClass().index());
		toIdentity.add(c.size());
		c.add(Opcode.IFNE);
		JvmRuntimeBuilder.emitU2(c, 0);
		List<Integer> toEquals = new ArrayList<>();
		emitIsStringGuard(c, Opcode.ALOAD_0, strings, toEquals);
		for (int pos : toIdentity) {
			JvmRuntimeBuilder.patchBranch(c, pos, c.size());
		}
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.ALOAD_1);
		int ifNotSame2 = c.size();
		c.add(Opcode.IF_ACMPNE);
		JvmRuntimeBuilder.emitU2(c, 0);
		c.add(Opcode.ICONST_1);
		c.add(Opcode.IRETURN);
		JvmRuntimeBuilder.patchBranch(c, ifNotSame2, c.size());
		c.add(Opcode.ICONST_0);
		c.add(Opcode.IRETURN);
		for (int pos : toEquals) {
			JvmRuntimeBuilder.patchBranch(c, pos, c.size());
		}
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.ALOAD_1);
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, objEquals.index());
		c.add(Opcode.IRETURN);
		return new NumericMethod(name, desc, c, 3, 2, List.of());
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

	// Falls through when the value loaded by loadOp is a STRING (a quote-framed
	// java.lang.String); otherwise branches, each branch recorded in notString.
	private static void emitIsStringGuard(List<Integer> c, int loadOp, StringRefs strings, List<Integer> notString) {
		c.add(loadOp);
		c.add(Opcode.INSTANCEOF);
		JvmRuntimeBuilder.emitU2(c, strings.stringClass().index());
		notString.add(c.size());
		c.add(Opcode.IFEQ);
		JvmRuntimeBuilder.emitU2(c, 0);
		c.add(loadOp);
		c.add(Opcode.CHECKCAST);
		JvmRuntimeBuilder.emitU2(c, strings.stringClass().index());
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, strings.isEmpty().index());
		notString.add(c.size());
		c.add(Opcode.IFNE);
		JvmRuntimeBuilder.emitU2(c, 0);
		c.add(loadOp);
		c.add(Opcode.CHECKCAST);
		JvmRuntimeBuilder.emitU2(c, strings.stringClass().index());
		c.add(Opcode.ICONST_0);
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, strings.charAt().index());
		c.add(Opcode.BIPUSH);
		c.add((int) '"');
		notString.add(c.size());
		c.add(Opcode.IF_ICMPNE);
		JvmRuntimeBuilder.emitU2(c, 0);
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
		List<Integer> c = new ArrayList<>();
		// if (a == b) return 1 -- identity BEFORE any recursion, which is what makes a
		// cyclic value comparable to itself (a hash table storing and retrieving under
		// the SAME cyclic key terminates here). Two DISTINCT cyclic structures are
		// still undefined, as in ANSI.
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.ALOAD_1);
		int ifNotIdentical = c.size();
		c.add(Opcode.IF_ACMPNE);
		JvmRuntimeBuilder.emitU2(c, 0);
		c.add(Opcode.ICONST_1);
		c.add(Opcode.IRETURN);
		JvmRuntimeBuilder.patchBranch(c, ifNotIdentical, c.size());
		// if (a == null) return (b == null) ? 1 : 0;
		c.add(Opcode.ALOAD_0);
		int ifANotNull = c.size();
		c.add(Opcode.IFNONNULL);
		JvmRuntimeBuilder.emitU2(c, 0);
		c.add(Opcode.ALOAD_1);
		int ifBNotNull = c.size();
		c.add(Opcode.IFNONNULL);
		JvmRuntimeBuilder.emitU2(c, 0);
		c.add(Opcode.ICONST_1);
		c.add(Opcode.IRETURN);
		JvmRuntimeBuilder.patchBranch(c, ifBNotNull, c.size());
		c.add(Opcode.ICONST_0);
		c.add(Opcode.IRETURN);
		// a is not null
		JvmRuntimeBuilder.patchBranch(c, ifANotNull, c.size());
		// Instances first (emitted only when the program can build one, so an
		// instance-free class is byte-identical): an instance is an Object[] with the
		// interned String[] layout in slot 0, and two of them are equal when they share
		// that layout and every slot is recursively _equal. This keeps compiled `equal`
		// structural over struct/CLOS instances, matching the interpreter's
		// LispInstance.equals -- and it must be checked BEFORE the cons branch, whose
		// Object[] shape an instance would otherwise satisfy.
		int maxLocals = 2;
		if (strArrClass != null) {
			maxLocals = 3;
			emitInstanceEqual(c, objArrClass, strArrClass, equal);
		}
		// Detect both cons: instanceof Object[], not BigInteger[], head not Integer.
		List<Integer> notBothCons = new ArrayList<>();
		emitConsGuard(c, Opcode.ALOAD_0, objArrClass, ratArrClass, integerClass, notBothCons);
		emitConsGuard(c, Opcode.ALOAD_1, objArrClass, ratArrClass, integerClass, notBothCons);
		// both cons: return _equal(a[0], b[0]) && _equal(a[1], b[1])
		emitArrayElement(c, Opcode.ALOAD_0, objArrClass, Opcode.ICONST_0);
		emitArrayElement(c, Opcode.ALOAD_1, objArrClass, Opcode.ICONST_0);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, equal.index());
		int ifCarFalse = c.size();
		c.add(Opcode.IFEQ);
		JvmRuntimeBuilder.emitU2(c, 0);
		emitArrayElement(c, Opcode.ALOAD_0, objArrClass, Opcode.ICONST_1);
		emitArrayElement(c, Opcode.ALOAD_1, objArrClass, Opcode.ICONST_1);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, equal.index());
		c.add(Opcode.IRETURN);
		JvmRuntimeBuilder.patchBranch(c, ifCarFalse, c.size());
		c.add(Opcode.ICONST_0);
		c.add(Opcode.IRETURN);
		// not both cons: two strings compare by content, anything else through _eqv(a, b)
		int notCons = c.size();
		for (int pos : notBothCons) {
			JvmRuntimeBuilder.patchBranch(c, pos, notCons);
		}
		if (strvMethod != null) {
			c.add(Opcode.ALOAD_0);
			c.add(Opcode.INVOKESTATIC);
			JvmRuntimeBuilder.emitU2(c, strvMethod.index());
			c.add(Opcode.ASTORE_0);
			c.add(Opcode.ALOAD_1);
			c.add(Opcode.INVOKESTATIC);
			JvmRuntimeBuilder.emitU2(c, strvMethod.index());
			c.add(Opcode.ASTORE_1);
		}
		List<Integer> notStrings = new ArrayList<>();
		emitIsStringGuard(c, Opcode.ALOAD_0, strings, notStrings);
		emitIsStringGuard(c, Opcode.ALOAD_1, strings, notStrings);
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.ALOAD_1);
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, objEquals.index());
		c.add(Opcode.IRETURN);
		for (int pos : notStrings) {
			JvmRuntimeBuilder.patchBranch(c, pos, c.size());
		}
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.ALOAD_1);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, eqv.index());
		c.add(Opcode.IRETURN);
		return new NumericMethod(name, desc, c, 3, maxLocals, List.of());
	}

	// The instance arm of _equal: if either argument is an instance, the whole answer is
	// decided here (t only when both are, over the same layout, with every slot equal),
	// so control falls through to the cons/eqv code only for two non-instances. Local 2
	// is the slot cursor.
	private static void emitInstanceEqual(List<Integer> c, ClassConstant objArrClass, ClassConstant strArrClass,
			MethodrefConstant equal) {
		List<Integer> aNotInstance = new ArrayList<>();
		emitInstanceGuard(c, Opcode.ALOAD_0, objArrClass, strArrClass, aNotInstance);
		// a IS an instance: b must be one too, or they differ.
		List<Integer> toFalse = new ArrayList<>();
		emitInstanceGuard(c, Opcode.ALOAD_1, objArrClass, strArrClass, toFalse);
		// Same layout? The pool interns one String[] per tag, so identity IS tag
		// identity, and the slot count comes with it.
		emitArrayElement(c, Opcode.ALOAD_0, objArrClass, Opcode.ICONST_0);
		emitArrayElement(c, Opcode.ALOAD_1, objArrClass, Opcode.ICONST_0);
		toFalse.add(c.size());
		c.add(Opcode.IF_ACMPNE);
		JvmRuntimeBuilder.emitU2(c, 0);
		// for (int i = 1; i < a.length; i++) if (!_equal(a[i], b[i])) return 0;
		c.add(Opcode.ICONST_1);
		c.add(Opcode.ISTORE_2);
		int loopTop = c.size();
		c.add(Opcode.ILOAD_2);
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.CHECKCAST);
		JvmRuntimeBuilder.emitU2(c, objArrClass.index());
		c.add(Opcode.ARRAYLENGTH);
		int exitLoop = c.size();
		c.add(Opcode.IF_ICMPGE);
		JvmRuntimeBuilder.emitU2(c, 0);
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.CHECKCAST);
		JvmRuntimeBuilder.emitU2(c, objArrClass.index());
		c.add(Opcode.ILOAD_2);
		c.add(Opcode.AALOAD);
		c.add(Opcode.ALOAD_1);
		c.add(Opcode.CHECKCAST);
		JvmRuntimeBuilder.emitU2(c, objArrClass.index());
		c.add(Opcode.ILOAD_2);
		c.add(Opcode.AALOAD);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, equal.index());
		toFalse.add(c.size());
		c.add(Opcode.IFEQ);
		JvmRuntimeBuilder.emitU2(c, 0);
		c.add(Opcode.IINC);
		c.add(2);
		c.add(1);
		int gotoTop = c.size();
		c.add(Opcode.GOTO);
		JvmRuntimeBuilder.emitU2(c, 0);
		JvmRuntimeBuilder.patchBranch(c, gotoTop, loopTop);
		JvmRuntimeBuilder.patchBranch(c, exitLoop, c.size());
		c.add(Opcode.ICONST_1);
		c.add(Opcode.IRETURN);
		for (int pos : toFalse) {
			JvmRuntimeBuilder.patchBranch(c, pos, c.size());
		}
		c.add(Opcode.ICONST_0);
		c.add(Opcode.IRETURN);
		// a is NOT an instance: b must not be either, or they differ.
		for (int pos : aNotInstance) {
			JvmRuntimeBuilder.patchBranch(c, pos, c.size());
		}
		List<Integer> bothPlain = new ArrayList<>();
		emitInstanceGuard(c, Opcode.ALOAD_1, objArrClass, strArrClass, bothPlain);
		c.add(Opcode.ICONST_0);
		c.add(Opcode.IRETURN);
		for (int pos : bothPlain) {
			JvmRuntimeBuilder.patchBranch(c, pos, c.size());
		}
	}

	// Branches to the recorded escape positions unless the value loaded by loadOpcode is
	// an instance: a non-empty Object[] carrying a String[] layout in slot 0.
	private static void emitInstanceGuard(List<Integer> c, int loadOpcode, ClassConstant objArrClass,
			ClassConstant strArrClass, List<Integer> escapes) {
		c.add(loadOpcode);
		c.add(Opcode.INSTANCEOF);
		JvmRuntimeBuilder.emitU2(c, objArrClass.index());
		escapes.add(c.size());
		c.add(Opcode.IFEQ);
		JvmRuntimeBuilder.emitU2(c, 0);
		c.add(loadOpcode);
		c.add(Opcode.CHECKCAST);
		JvmRuntimeBuilder.emitU2(c, objArrClass.index());
		c.add(Opcode.ARRAYLENGTH);
		escapes.add(c.size());
		c.add(Opcode.IFEQ);
		JvmRuntimeBuilder.emitU2(c, 0);
		emitArrayElement(c, loadOpcode, objArrClass, Opcode.ICONST_0);
		c.add(Opcode.INSTANCEOF);
		JvmRuntimeBuilder.emitU2(c, strArrClass.index());
		escapes.add(c.size());
		c.add(Opcode.IFEQ);
		JvmRuntimeBuilder.emitU2(c, 0);
	}

	// Emits a cons-cell guard for the value loaded by loadOpcode: if it is not a cons
	// cell
	// (not an Object[], or a BigInteger[] ratio, or an Object[] whose head is an Integer
	// function reference), branch to the not-cons target (the position is recorded so the
	// caller can patch it).
	private static void emitConsGuard(List<Integer> c, int loadOpcode, ClassConstant objArrClass,
			ClassConstant ratArrClass, ClassConstant integerClass, List<Integer> notBothCons) {
		c.add(loadOpcode);
		c.add(Opcode.INSTANCEOF);
		JvmRuntimeBuilder.emitU2(c, objArrClass.index());
		notBothCons.add(c.size());
		c.add(Opcode.IFEQ);
		JvmRuntimeBuilder.emitU2(c, 0);
		c.add(loadOpcode);
		c.add(Opcode.INSTANCEOF);
		JvmRuntimeBuilder.emitU2(c, ratArrClass.index());
		notBothCons.add(c.size());
		c.add(Opcode.IFNE);
		JvmRuntimeBuilder.emitU2(c, 0);
		c.add(loadOpcode);
		c.add(Opcode.CHECKCAST);
		JvmRuntimeBuilder.emitU2(c, objArrClass.index());
		c.add(Opcode.ICONST_0);
		c.add(Opcode.AALOAD);
		c.add(Opcode.INSTANCEOF);
		JvmRuntimeBuilder.emitU2(c, integerClass.index());
		notBothCons.add(c.size());
		c.add(Opcode.IFNE);
		JvmRuntimeBuilder.emitU2(c, 0);
	}

	// Loads element at the given index (ICONST_0/ICONST_1) of the Object[] loaded by
	// loadOpcode.
	private static void emitArrayElement(List<Integer> c, int loadOpcode, ClassConstant objArrClass, int indexOpcode) {
		c.add(loadOpcode);
		c.add(Opcode.CHECKCAST);
		JvmRuntimeBuilder.emitU2(c, objArrClass.index());
		c.add(indexOpcode);
		c.add(Opcode.AALOAD);
	}

	// _rtrunc(Object x): num/den truncating toward zero (BigInteger.divide).
	private static NumericMethod buildRatTrunc(Utf8Constant name, Utf8Constant desc, MethodrefConstant rRatNum,
			MethodrefConstant rRatDen, MethodrefConstant rNorm, MethodrefConstant biDiv) {
		List<Integer> c = new ArrayList<>();
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, rRatNum.index());
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, rRatDen.index());
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, biDiv.index());
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, rNorm.index());
		c.add(Opcode.ARETURN);
		return new NumericMethod(name, desc, c, 2, 1, List.of());
	}

	// _rfloor(Object x): (num - num.mod(den)) / den (the denominator is positive, so
	// mod() is non-negative). When ceilStep/ceilOp are given the result is floor + 1,
	// which is the ceiling of a (never-integer) normalized ratio.
	private static NumericMethod buildRatFloor(Utf8Constant name, Utf8Constant desc, MethodrefConstant rRatNum,
			MethodrefConstant rRatDen, MethodrefConstant rNorm, MethodrefConstant biMod, MethodrefConstant biSub,
			MethodrefConstant biDiv, @Nullable FieldrefConstant ceilOne, @Nullable MethodrefConstant ceilAdd) {
		List<Integer> c = new ArrayList<>();
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, rRatNum.index());
		c.add(Opcode.ASTORE_1);
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, rRatDen.index());
		c.add(Opcode.ASTORE_2);
		c.add(Opcode.ALOAD_1);
		c.add(Opcode.ALOAD_1);
		c.add(Opcode.ALOAD_2);
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, biMod.index());
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, biSub.index());
		c.add(Opcode.ALOAD_2);
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, biDiv.index());
		if (ceilOne != null && ceilAdd != null) {
			c.add(Opcode.GETSTATIC);
			JvmRuntimeBuilder.emitU2(c, ceilOne.index());
			c.add(Opcode.INVOKEVIRTUAL);
			JvmRuntimeBuilder.emitU2(c, ceilAdd.index());
		}
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, rNorm.index());
		c.add(Opcode.ARETURN);
		return new NumericMethod(name, desc, c, 3, 3, List.of());
	}

	// _rround(Object x): nearest integer, ties to even (Common Lisp round semantics).
	private static NumericMethod buildRatRound(Utf8Constant name, Utf8Constant desc, MethodrefConstant rRatNum,
			MethodrefConstant rRatDen, MethodrefConstant rNorm, MethodrefConstant biMod, MethodrefConstant biSub,
			MethodrefConstant biDiv, MethodrefConstant biMul, MethodrefConstant biShiftLeft,
			MethodrefConstant biCompareTo, MethodrefConstant biTestBit, FieldrefConstant biOne,
			MethodrefConstant biAdd) {
		List<Integer> c = new ArrayList<>();
		// num=1, den=2, floor=3, remainder=4, cmp(int)=5
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, rRatNum.index());
		c.add(Opcode.ASTORE_1);
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, rRatDen.index());
		c.add(Opcode.ASTORE_2);
		c.add(Opcode.ALOAD_1);
		c.add(Opcode.ALOAD_1);
		c.add(Opcode.ALOAD_2);
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, biMod.index());
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, biSub.index());
		c.add(Opcode.ALOAD_2);
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, biDiv.index());
		c.add(Opcode.ASTORE_3);
		// remainder = num - floor * den (0 <= remainder < den)
		c.add(Opcode.ALOAD_1);
		c.add(Opcode.ALOAD_3);
		c.add(Opcode.ALOAD_2);
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, biMul.index());
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, biSub.index());
		c.add(Opcode.ASTORE);
		c.add(4);
		// cmp = (remainder << 1).compareTo(den)
		c.add(Opcode.ALOAD);
		c.add(4);
		c.add(Opcode.ICONST_1);
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, biShiftLeft.index());
		c.add(Opcode.ALOAD_2);
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, biCompareTo.index());
		c.add(Opcode.ISTORE);
		c.add(5);
		c.add(Opcode.ILOAD);
		c.add(5);
		int ifUpOrTie = c.size();
		c.add(Opcode.IFGE);
		JvmRuntimeBuilder.emitU2(c, 0);
		c.add(Opcode.ALOAD_3);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, rNorm.index());
		c.add(Opcode.ARETURN);
		JvmRuntimeBuilder.patchBranch(c, ifUpOrTie, c.size());
		c.add(Opcode.ILOAD);
		c.add(5);
		int ifUp1 = c.size();
		c.add(Opcode.IFNE);
		JvmRuntimeBuilder.emitU2(c, 0);
		// Tie: round to even (an odd floor rounds up).
		c.add(Opcode.ALOAD_3);
		c.add(Opcode.ICONST_0);
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, biTestBit.index());
		int ifUp2 = c.size();
		c.add(Opcode.IFNE);
		JvmRuntimeBuilder.emitU2(c, 0);
		c.add(Opcode.ALOAD_3);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, rNorm.index());
		c.add(Opcode.ARETURN);
		JvmRuntimeBuilder.patchBranch(c, ifUp1, c.size());
		JvmRuntimeBuilder.patchBranch(c, ifUp2, c.size());
		c.add(Opcode.ALOAD_3);
		c.add(Opcode.GETSTATIC);
		JvmRuntimeBuilder.emitU2(c, biOne.index());
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, biAdd.index());
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, rNorm.index());
		c.add(Opcode.ARETURN);
		return new NumericMethod(name, desc, c, 3, 6, List.of());
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
		List<Integer> c = new ArrayList<>();
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.INSTANCEOF);
		JvmRuntimeBuilder.emitU2(c, doubleClass.index());
		int ifNotDouble = c.size();
		c.add(Opcode.IFEQ);
		JvmRuntimeBuilder.emitU2(c, 0);
		emitDoubleOfArg0(c, numberClass, numDoubleValue);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, dblIsFinite.index());
		int ifFinite = c.size();
		c.add(Opcode.IFNE);
		JvmRuntimeBuilder.emitU2(c, 0);
		c.add(Opcode.ACONST_NULL);
		c.add(Opcode.ARETURN);
		JvmRuntimeBuilder.patchBranch(c, ifFinite, c.size());
		// bd = new BigDecimal(d) in local 1, the pair in local 2.
		c.add(Opcode.NEW);
		JvmRuntimeBuilder.emitU2(c, bigDecClass.index());
		c.add(Opcode.DUP);
		emitDoubleOfArg0(c, numberClass, numDoubleValue);
		c.add(Opcode.INVOKESPECIAL);
		JvmRuntimeBuilder.emitU2(c, bdInitDouble.index());
		c.add(Opcode.ASTORE_1);
		c.add(Opcode.ICONST_2);
		c.add(Opcode.ANEWARRAY);
		JvmRuntimeBuilder.emitU2(c, bigClass.index());
		c.add(Opcode.ASTORE_2);
		c.add(Opcode.ALOAD_2);
		c.add(Opcode.ICONST_0);
		c.add(Opcode.ALOAD_1);
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, bdUnscaled.index());
		c.add(Opcode.AASTORE);
		c.add(Opcode.ALOAD_2);
		c.add(Opcode.ICONST_1);
		c.add(Opcode.GETSTATIC);
		JvmRuntimeBuilder.emitU2(c, biTen.index());
		c.add(Opcode.ALOAD_1);
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, bdScale.index());
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, biPow.index());
		c.add(Opcode.AASTORE);
		c.add(Opcode.ALOAD_2);
		c.add(Opcode.ARETURN);
		JvmRuntimeBuilder.patchBranch(c, ifNotDouble, c.size());
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.INSTANCEOF);
		JvmRuntimeBuilder.emitU2(c, longClass.index());
		int ifLong = c.size();
		c.add(Opcode.IFNE);
		JvmRuntimeBuilder.emitU2(c, 0);
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.INSTANCEOF);
		JvmRuntimeBuilder.emitU2(c, bigClass.index());
		int ifBig = c.size();
		c.add(Opcode.IFNE);
		JvmRuntimeBuilder.emitU2(c, 0);
		c.add(Opcode.ACONST_NULL);
		c.add(Opcode.ARETURN);
		JvmRuntimeBuilder.patchBranch(c, ifLong, c.size());
		JvmRuntimeBuilder.patchBranch(c, ifBig, c.size());
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.ARETURN);
		return new NumericMethod(name, desc, c, 5, 3, List.of());
	}

	// _rational(Object x): integers and ratios answer themselves; a finite Double
	// normalizes through _frat + _rat (the pair is NOT normalized, so it cannot be
	// answered directly -- _frat is the decomposition, _rat the normalization). A
	// complex or any other non-real takes the real funnel (the interpreter's
	// "Expected real number" text, prefix-classified as a type-error like the
	// interpreter's throw); a NaN or an infinity throws the interpreter's
	// non-finite text instead.
	private static NumericMethod buildRational(Utf8Constant name, Utf8Constant desc, ClassConstant longClass,
			ClassConstant bigClass, ClassConstant doubleClass, ClassConstant ratArrClass, MethodrefConstant rFrat,
			MethodrefConstant rRat, TypeErrRefs typeErrRefs, ConstantPool.StringConstant nonFiniteStr,
			@Nullable ClassConstant rcClass, @Nullable FieldrefConstant hasComplex) {
		List<Integer> c = new ArrayList<>();
		if (rcClass != null) {
			int noHolder = emitNoHolderJump(c, hasComplex);
			c.add(Opcode.ALOAD_0);
			c.add(Opcode.INSTANCEOF);
			JvmRuntimeBuilder.emitU2(c, rcClass.index());
			int ifNotComplex = c.size();
			c.add(Opcode.IFEQ);
			JvmRuntimeBuilder.emitU2(c, 0);
			emitRealErrThrow(c, typeErrRefs);
			JvmRuntimeBuilder.patchBranch(c, ifNotComplex, c.size());
			JvmRuntimeBuilder.patchBranch(c, noHolder, c.size());
		}
		// A ratio is already exact.
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.INSTANCEOF);
		JvmRuntimeBuilder.emitU2(c, ratArrClass.index());
		int ifNotRat = c.size();
		c.add(Opcode.IFEQ);
		JvmRuntimeBuilder.emitU2(c, 0);
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.ARETURN);
		JvmRuntimeBuilder.patchBranch(c, ifNotRat, c.size());
		// So is an integer.
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.INSTANCEOF);
		JvmRuntimeBuilder.emitU2(c, longClass.index());
		int ifLong = c.size();
		c.add(Opcode.IFNE);
		JvmRuntimeBuilder.emitU2(c, 0);
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.INSTANCEOF);
		JvmRuntimeBuilder.emitU2(c, bigClass.index());
		int ifNotInt = c.size();
		c.add(Opcode.IFEQ);
		JvmRuntimeBuilder.emitU2(c, 0);
		JvmRuntimeBuilder.patchBranch(c, ifLong, c.size());
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.ARETURN);
		JvmRuntimeBuilder.patchBranch(c, ifNotInt, c.size());
		// A Double goes through _frat, which answers null for a NaN or an infinity.
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.INSTANCEOF);
		JvmRuntimeBuilder.emitU2(c, doubleClass.index());
		int ifNotDouble = c.size();
		c.add(Opcode.IFEQ);
		JvmRuntimeBuilder.emitU2(c, 0);
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, rFrat.index());
		c.add(Opcode.DUP);
		int ifFinite = c.size();
		c.add(Opcode.IFNONNULL);
		JvmRuntimeBuilder.emitU2(c, 0);
		c.add(Opcode.POP);
		c.add(Opcode.NEW);
		JvmRuntimeBuilder.emitU2(c, typeErrRefs.rte().index());
		c.add(Opcode.DUP);
		JvmRuntimeBuilder.emitLdc(c, nonFiniteStr.index());
		c.add(Opcode.INVOKESPECIAL);
		JvmRuntimeBuilder.emitU2(c, typeErrRefs.rteInit().index());
		c.add(Opcode.ATHROW);
		JvmRuntimeBuilder.patchBranch(c, ifFinite, c.size());
		// The verifier only sees _frat's Object descriptor, so the pair is cast
		// to its array class before the elements load (a bare aaload on the
		// merged Object is too lossy for the StackMapAugmenter).
		c.add(Opcode.CHECKCAST);
		JvmRuntimeBuilder.emitU2(c, ratArrClass.index());
		c.add(Opcode.ASTORE_1);
		c.add(Opcode.ALOAD_1);
		c.add(Opcode.ICONST_0);
		c.add(Opcode.AALOAD);
		c.add(Opcode.CHECKCAST);
		JvmRuntimeBuilder.emitU2(c, bigClass.index());
		c.add(Opcode.ALOAD_1);
		c.add(Opcode.ICONST_1);
		c.add(Opcode.AALOAD);
		c.add(Opcode.CHECKCAST);
		JvmRuntimeBuilder.emitU2(c, bigClass.index());
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, rRat.index());
		c.add(Opcode.ARETURN);
		JvmRuntimeBuilder.patchBranch(c, ifNotDouble, c.size());
		emitRealErrThrow(c, typeErrRefs);
		return new NumericMethod(name, desc, c, 4, 2, List.of());
	}

	/** Pushes {@code ((Number) arg0).doubleValue()}. */
	private static void emitDoubleOfArg0(List<Integer> c, ClassConstant numberClass, MethodrefConstant numDoubleValue) {
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.CHECKCAST);
		JvmRuntimeBuilder.emitU2(c, numberClass.index());
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, numDoubleValue.index());
	}

	// _fdiv(Object a, Object b, int mode): the floor family's quotient when a float is
	// involved, or null to decline (no float operand, a ratio, a non-finite float, a
	// zero float divisor -- all of which keep the ordinary route, the last so the
	// non-trapping (/ x 0.0) policy stands). Both operands become the exact rationals
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
			MethodrefConstant biSignum, MethodrefConstant longValueOf) {
		List<Integer> c = new ArrayList<>();
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.INSTANCEOF);
		JvmRuntimeBuilder.emitU2(c, doubleClass.index());
		c.add(Opcode.ALOAD_1);
		c.add(Opcode.INSTANCEOF);
		JvmRuntimeBuilder.emitU2(c, doubleClass.index());
		c.add(Opcode.IOR);
		int ifFloat = c.size();
		c.add(Opcode.IFNE);
		JvmRuntimeBuilder.emitU2(c, 0);
		c.add(Opcode.ACONST_NULL);
		c.add(Opcode.ARETURN);
		JvmRuntimeBuilder.patchBranch(c, ifFloat, c.size());
		// A zero float divisor declines: (/ x 0.0) is an infinity here, not a signal.
		c.add(Opcode.ALOAD_1);
		c.add(Opcode.INSTANCEOF);
		JvmRuntimeBuilder.emitU2(c, doubleClass.index());
		int ifNotFloatDivisor = c.size();
		c.add(Opcode.IFEQ);
		JvmRuntimeBuilder.emitU2(c, 0);
		c.add(Opcode.ALOAD_1);
		c.add(Opcode.CHECKCAST);
		JvmRuntimeBuilder.emitU2(c, numberClass.index());
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, numDoubleValue.index());
		// An infinite divisor: settle the quotient by sign (local 6/7 hold the two
		// signs) rather than falling through to _frat, which declines on a non-finite
		// operand. Every sub-path below returns, so control never merges back here.
		c.add(Opcode.DUP2);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, dblIsInfinite.index());
		int ifNotInfiniteDivisor = c.size();
		c.add(Opcode.IFEQ);
		JvmRuntimeBuilder.emitU2(c, 0);
		emitInfiniteDivisorQuotient(c, doubleClass, numDoubleValue, dblIsFinite, longClass, longValue, bigClass,
				biSignum, longValueOf);
		JvmRuntimeBuilder.patchBranch(c, ifNotInfiniteDivisor, c.size());
		c.add(Opcode.DCONST_0);
		c.add(Opcode.DCMPL);
		int ifNonZeroDivisor = c.size();
		c.add(Opcode.IFNE);
		JvmRuntimeBuilder.emitU2(c, 0);
		c.add(Opcode.ACONST_NULL);
		c.add(Opcode.ARETURN);
		JvmRuntimeBuilder.patchBranch(c, ifNotFloatDivisor, c.size());
		JvmRuntimeBuilder.patchBranch(c, ifNonZeroDivisor, c.size());
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, rFrat.index());
		c.add(Opcode.ASTORE_3);
		c.add(Opcode.ALOAD_3);
		int ifDividendOk = c.size();
		c.add(Opcode.IFNONNULL);
		JvmRuntimeBuilder.emitU2(c, 0);
		c.add(Opcode.ACONST_NULL);
		c.add(Opcode.ARETURN);
		JvmRuntimeBuilder.patchBranch(c, ifDividendOk, c.size());
		c.add(Opcode.ALOAD_1);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, rFrat.index());
		c.add(Opcode.ASTORE);
		c.add(4);
		c.add(Opcode.ALOAD);
		c.add(4);
		int ifDivisorOk = c.size();
		c.add(Opcode.IFNONNULL);
		JvmRuntimeBuilder.emitU2(c, 0);
		c.add(Opcode.ACONST_NULL);
		c.add(Opcode.ARETURN);
		JvmRuntimeBuilder.patchBranch(c, ifDivisorOk, c.size());
		c.add(Opcode.ALOAD_3);
		c.add(Opcode.ALOAD);
		c.add(4);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, rDiv.index());
		c.add(Opcode.ASTORE);
		c.add(5);
		c.add(Opcode.ALOAD);
		c.add(5);
		c.add(Opcode.INSTANCEOF);
		JvmRuntimeBuilder.emitU2(c, ratArrClass.index());
		int ifRatio = c.size();
		c.add(Opcode.IFNE);
		JvmRuntimeBuilder.emitU2(c, 0);
		c.add(Opcode.ALOAD);
		c.add(5);
		c.add(Opcode.ARETURN);
		JvmRuntimeBuilder.patchBranch(c, ifRatio, c.size());
		emitModeArm(c, 1, rRatFloor);
		emitModeArm(c, 2, rRatCeil);
		emitModeArm(c, 3, rRatRound);
		c.add(Opcode.ALOAD);
		c.add(5);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, rRatTrunc.index());
		c.add(Opcode.ARETURN);
		// Locals 6 (dividend sign) and 7 (divisor sign) belong to the infinite-divisor
		// arm above; nothing past it uses a local higher than 5.
		return new NumericMethod(name, desc, c, 4, 8, List.of());
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
	private static void emitInfiniteDivisorQuotient(List<Integer> c, ClassConstant doubleClass,
			MethodrefConstant numDoubleValue, MethodrefConstant dblIsFinite, ClassConstant longClass,
			MethodrefConstant longValue, ClassConstant bigClass, MethodrefConstant biSignum,
			MethodrefConstant longValueOf) {
		// local 6 = signum(b), from the divisor double already on the stack.
		c.add(Opcode.DCONST_0);
		c.add(Opcode.DCMPL);
		c.add(Opcode.ISTORE);
		c.add(6);
		// local 7 = signum(a). Each arm below stores it and jumps to afterSignA; a ratio
		// (the final catch-all) or a non-finite float dividend declines directly.
		List<Integer> gotoAfterSignA = new ArrayList<>();

		// Long.
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.INSTANCEOF);
		JvmRuntimeBuilder.emitU2(c, longClass.index());
		int ifNotLong = c.size();
		c.add(Opcode.IFEQ);
		JvmRuntimeBuilder.emitU2(c, 0);
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.CHECKCAST);
		JvmRuntimeBuilder.emitU2(c, longClass.index());
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, longValue.index());
		c.add(Opcode.LCONST_0);
		c.add(Opcode.LCMP);
		c.add(Opcode.ISTORE);
		c.add(7);
		gotoAfterSignA.add(c.size());
		c.add(Opcode.GOTO);
		JvmRuntimeBuilder.emitU2(c, 0);
		JvmRuntimeBuilder.patchBranch(c, ifNotLong, c.size());

		// BigInteger.
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.INSTANCEOF);
		JvmRuntimeBuilder.emitU2(c, bigClass.index());
		int ifNotBig = c.size();
		c.add(Opcode.IFEQ);
		JvmRuntimeBuilder.emitU2(c, 0);
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.CHECKCAST);
		JvmRuntimeBuilder.emitU2(c, bigClass.index());
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, biSignum.index());
		c.add(Opcode.ISTORE);
		c.add(7);
		gotoAfterSignA.add(c.size());
		c.add(Opcode.GOTO);
		JvmRuntimeBuilder.emitU2(c, 0);
		JvmRuntimeBuilder.patchBranch(c, ifNotBig, c.size());

		// Double: finite required, else decline.
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.INSTANCEOF);
		JvmRuntimeBuilder.emitU2(c, doubleClass.index());
		int ifNotDouble = c.size();
		c.add(Opcode.IFEQ);
		JvmRuntimeBuilder.emitU2(c, 0);
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.CHECKCAST);
		JvmRuntimeBuilder.emitU2(c, doubleClass.index());
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, numDoubleValue.index());
		c.add(Opcode.DUP2);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, dblIsFinite.index());
		int ifFiniteA = c.size();
		c.add(Opcode.IFNE);
		JvmRuntimeBuilder.emitU2(c, 0);
		c.add(Opcode.POP2);
		c.add(Opcode.ACONST_NULL);
		c.add(Opcode.ARETURN);
		JvmRuntimeBuilder.patchBranch(c, ifFiniteA, c.size());
		c.add(Opcode.DCONST_0);
		c.add(Opcode.DCMPL);
		c.add(Opcode.ISTORE);
		c.add(7);
		gotoAfterSignA.add(c.size());
		c.add(Opcode.GOTO);
		JvmRuntimeBuilder.emitU2(c, 0);
		JvmRuntimeBuilder.patchBranch(c, ifNotDouble, c.size());

		// Anything else (a ratio): decline.
		c.add(Opcode.ACONST_NULL);
		c.add(Opcode.ARETURN);

		int afterSignA = c.size();
		for (int g : gotoAfterSignA) {
			JvmRuntimeBuilder.patchBranch(c, g, afterSignA);
		}

		// An exact-zero dividend declines too.
		c.add(Opcode.ILOAD);
		c.add(7);
		int signANonZero = c.size();
		c.add(Opcode.IFNE);
		JvmRuntimeBuilder.emitU2(c, 0);
		c.add(Opcode.ACONST_NULL);
		c.add(Opcode.ARETURN);
		JvmRuntimeBuilder.patchBranch(c, signANonZero, c.size());

		c.add(Opcode.ILOAD);
		c.add(7);
		c.add(Opcode.ILOAD);
		c.add(6);
		int ifSameSign = c.size();
		c.add(Opcode.IF_ICMPEQ);
		JvmRuntimeBuilder.emitU2(c, 0);

		// Different sign: floor (mode 1) is -1, everything else (truncate/round) is 0.
		c.add(Opcode.ILOAD_2);
		c.add(Opcode.ICONST_1);
		int diffElse = c.size();
		c.add(Opcode.IF_ICMPNE);
		JvmRuntimeBuilder.emitU2(c, 0);
		c.add(Opcode.LCONST_1);
		c.add(Opcode.LNEG);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, longValueOf.index());
		c.add(Opcode.ARETURN);
		JvmRuntimeBuilder.patchBranch(c, diffElse, c.size());
		c.add(Opcode.LCONST_0);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, longValueOf.index());
		c.add(Opcode.ARETURN);

		JvmRuntimeBuilder.patchBranch(c, ifSameSign, c.size());
		// Same sign: ceiling (mode 2) is 1, everything else (truncate/round) is 0.
		c.add(Opcode.ILOAD_2);
		c.add(Opcode.ICONST_2);
		int sameElse = c.size();
		c.add(Opcode.IF_ICMPNE);
		JvmRuntimeBuilder.emitU2(c, 0);
		c.add(Opcode.LCONST_1);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, longValueOf.index());
		c.add(Opcode.ARETURN);
		JvmRuntimeBuilder.patchBranch(c, sameElse, c.size());
		c.add(Opcode.LCONST_0);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, longValueOf.index());
		c.add(Opcode.ARETURN);
	}

	/** {@code if (mode == n) return rounder(local 5);} inside {@code _fdiv}. */
	private static void emitModeArm(List<Integer> c, int mode, MethodrefConstant rounder) {
		c.add(Opcode.ILOAD_2);
		c.add(Opcode.ICONST_0 + mode);
		int skip = c.size();
		c.add(Opcode.IF_ICMPNE);
		JvmRuntimeBuilder.emitU2(c, 0);
		c.add(Opcode.ALOAD);
		c.add(5);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, rounder.index());
		c.add(Opcode.ARETURN);
		JvmRuntimeBuilder.patchBranch(c, skip, c.size());
	}

	// Emits the two `instanceof BigInteger[]` guards that jump to the rational path,
	// returning the two branch positions to patch.
	private static int[] emitRatioGuard(List<Integer> c, ClassConstant ratArrClass) {
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.INSTANCEOF);
		JvmRuntimeBuilder.emitU2(c, ratArrClass.index());
		int ifRat1 = c.size();
		c.add(Opcode.IFNE);
		JvmRuntimeBuilder.emitU2(c, 0);
		c.add(Opcode.ALOAD_1);
		c.add(Opcode.INSTANCEOF);
		JvmRuntimeBuilder.emitU2(c, ratArrClass.index());
		int ifRat2 = c.size();
		c.add(Opcode.IFNE);
		JvmRuntimeBuilder.emitU2(c, 0);
		return new int[] { ifRat1, ifRat2 };
	}

	// _logand/_logior/_logxor(Object a, Object b): the two's-complement bitwise op. Two
	// Longs answer with the matching long opcode -- 64-bit two's complement agrees with
	// BigInteger's infinite two's complement on every value a long can hold -- so a
	// (unsigned-byte 32) mask costs no BigInteger allocation. Any other operand mix
	// falls back to the exact BigInteger operation.
	private static NumericMethod buildLogOp(Utf8Constant name, Utf8Constant desc, ClassConstant longClass,
			MethodrefConstant longValue, MethodrefConstant longValueOf, MethodrefConstant rBig, MethodrefConstant rNorm,
			MethodrefConstant biOp, int longOpcode) {
		List<Integer> c = new ArrayList<>();
		int[] slowJumps = emitLongLongGuard(c, longClass);
		emitUnboxLong(c, Opcode.ALOAD_0, longClass, longValue);
		emitUnboxLong(c, Opcode.ALOAD_1, longClass, longValue);
		c.add(longOpcode);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, longValueOf.index());
		c.add(Opcode.ARETURN);
		int slow = c.size();
		JvmRuntimeBuilder.patchBranch(c, slowJumps[0], slow);
		JvmRuntimeBuilder.patchBranch(c, slowJumps[1], slow);
		emitBigBinary(c, rBig, biOp, rNorm);
		return new NumericMethod(name, desc, c, 4, 2, List.of());
	}

	// _lognot(Object a): ~a for a Long (emitted as `a xor -1`), BigInteger.not otherwise.
	private static NumericMethod buildLogNot(Utf8Constant name, Utf8Constant desc, ClassConstant longClass,
			MethodrefConstant longValue, MethodrefConstant longValueOf, MethodrefConstant rBig, MethodrefConstant rNorm,
			MethodrefConstant biNot) {
		List<Integer> c = new ArrayList<>();
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.INSTANCEOF);
		JvmRuntimeBuilder.emitU2(c, longClass.index());
		int ifSlow = c.size();
		c.add(Opcode.IFEQ);
		JvmRuntimeBuilder.emitU2(c, 0);
		emitUnboxLong(c, Opcode.ALOAD_0, longClass, longValue);
		c.add(Opcode.ICONST_M1);
		c.add(Opcode.I2L);
		c.add(Opcode.LXOR);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, longValueOf.index());
		c.add(Opcode.ARETURN);
		JvmRuntimeBuilder.patchBranch(c, ifSlow, c.size());
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, rBig.index());
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, biNot.index());
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, rNorm.index());
		c.add(Opcode.ARETURN);
		return new NumericMethod(name, desc, c, 4, 1, List.of());
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
		List<Integer> c = new ArrayList<>();
		c.add(Opcode.LCONST_0);
		c.add(Opcode.LSTORE_2);
		c.add(Opcode.ICONST_0);
		c.add(Opcode.ISTORE);
		c.add(4);
		c.add(Opcode.LCONST_0);
		c.add(Opcode.LSTORE);
		c.add(6);
		c.add(Opcode.LCONST_0);
		c.add(Opcode.LSTORE);
		c.add(8);
		// the count takes the ranged path only when it is a Long
		c.add(Opcode.ALOAD_1);
		c.add(Opcode.INSTANCEOF);
		JvmRuntimeBuilder.emitU2(c, longClass.index());
		int ifSlowCount = c.size();
		c.add(Opcode.IFEQ);
		JvmRuntimeBuilder.emitU2(c, 0);
		emitUnboxLong(c, Opcode.ALOAD_1, longClass, longValue);
		c.add(Opcode.LSTORE);
		c.add(8);
		// ((long) (int) count) == count, else the narrowing below would wrap
		c.add(Opcode.LLOAD);
		c.add(8);
		c.add(Opcode.L2I);
		c.add(Opcode.I2L);
		c.add(Opcode.LLOAD);
		c.add(8);
		c.add(Opcode.LCMP);
		int ifCountExact = c.size();
		c.add(Opcode.IFEQ);
		JvmRuntimeBuilder.emitU2(c, 0);
		// outside the int range the count's own sign decides the side
		c.add(Opcode.LLOAD);
		c.add(8);
		c.add(Opcode.LCONST_0);
		c.add(Opcode.LCMP);
		int ifHugeNeg = c.size();
		c.add(Opcode.IFLT);
		JvmRuntimeBuilder.emitU2(c, 0);
		int goHugePos = c.size();
		c.add(Opcode.GOTO);
		JvmRuntimeBuilder.emitU2(c, 0);
		JvmRuntimeBuilder.patchBranch(c, ifCountExact, c.size());
		c.add(Opcode.LLOAD);
		c.add(8);
		c.add(Opcode.L2I);
		c.add(Opcode.ISTORE);
		c.add(4);
		// the value takes the fast path only when it is a Long
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.INSTANCEOF);
		JvmRuntimeBuilder.emitU2(c, longClass.index());
		int ifSlowValue = c.size();
		c.add(Opcode.IFEQ);
		JvmRuntimeBuilder.emitU2(c, 0);
		emitUnboxLong(c, Opcode.ALOAD_0, longClass, longValue);
		c.add(Opcode.LSTORE_2);
		// if (count > 0) goto left
		c.add(Opcode.ILOAD);
		c.add(4);
		int ifLeft = c.size();
		c.add(Opcode.IFGT);
		JvmRuntimeBuilder.emitU2(c, 0);
		// count <= -64: the whole value shifts out, leaving 0 (or -1 when negative)
		c.add(Opcode.ILOAD);
		c.add(4);
		JvmRuntimeBuilder.emitIntConstStatic(c, -64);
		int ifRightShift = c.size();
		c.add(Opcode.IF_ICMPGT);
		JvmRuntimeBuilder.emitU2(c, 0);
		c.add(Opcode.LLOAD_2);
		c.add(Opcode.LCONST_0);
		c.add(Opcode.LCMP);
		int ifNegative = c.size();
		c.add(Opcode.IFLT);
		JvmRuntimeBuilder.emitU2(c, 0);
		c.add(Opcode.LCONST_0);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, longValueOf.index());
		c.add(Opcode.ARETURN);
		JvmRuntimeBuilder.patchBranch(c, ifNegative, c.size());
		c.add(Opcode.ICONST_M1);
		c.add(Opcode.I2L);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, longValueOf.index());
		c.add(Opcode.ARETURN);
		// -64 < count <= 0: a >> -count
		JvmRuntimeBuilder.patchBranch(c, ifRightShift, c.size());
		c.add(Opcode.LLOAD_2);
		c.add(Opcode.ICONST_0);
		c.add(Opcode.ILOAD);
		c.add(4);
		c.add(Opcode.ISUB);
		c.add(Opcode.LSHR);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, longValueOf.index());
		c.add(Opcode.ARETURN);
		// count > 0: shift left when the result round-trips (i.e. did not overflow)
		JvmRuntimeBuilder.patchBranch(c, ifLeft, c.size());
		c.add(Opcode.ILOAD);
		c.add(4);
		JvmRuntimeBuilder.emitIntConstStatic(c, 64);
		int ifWide = c.size();
		c.add(Opcode.IF_ICMPGE);
		JvmRuntimeBuilder.emitU2(c, 0);
		c.add(Opcode.LLOAD_2);
		c.add(Opcode.ILOAD);
		c.add(4);
		c.add(Opcode.LSHL);
		c.add(Opcode.LSTORE);
		c.add(6);
		c.add(Opcode.LLOAD);
		c.add(6);
		c.add(Opcode.ILOAD);
		c.add(4);
		c.add(Opcode.LSHR);
		c.add(Opcode.LLOAD_2);
		c.add(Opcode.LCMP);
		int ifOverflow = c.size();
		c.add(Opcode.IFNE);
		JvmRuntimeBuilder.emitU2(c, 0);
		c.add(Opcode.LLOAD);
		c.add(6);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, longValueOf.index());
		c.add(Opcode.ARETURN);
		int slowBig = c.size();
		JvmRuntimeBuilder.patchBranch(c, ifSlowValue, slowBig);
		JvmRuntimeBuilder.patchBranch(c, ifWide, slowBig);
		JvmRuntimeBuilder.patchBranch(c, ifOverflow, slowBig);
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, rBig.index());
		c.add(Opcode.ILOAD);
		c.add(4);
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, biShiftLeft.index());
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, rNorm.index());
		c.add(Opcode.ARETURN);
		// a non-Long count: a bignum count is always past the saturation width, so
		// its sign routes to the huge arms (ASH.5 reaches (ash j j) with
		// j = -(2^64)); anything else is not an integer and rBig signals the type
		// error
		int slowObjects = c.size();
		JvmRuntimeBuilder.patchBranch(c, ifSlowCount, slowObjects);
		c.add(Opcode.ALOAD_1);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, rBig.index());
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, biSignum.index());
		int ifBigNegCount = c.size();
		c.add(Opcode.IFLT);
		JvmRuntimeBuilder.emitU2(c, 0);
		int goBigPosCount = c.size();
		c.add(Opcode.GOTO);
		JvmRuntimeBuilder.emitU2(c, 0);
		// a huge negative count shifts the whole value out, leaving its sign
		int hugeNegPos = c.size();
		JvmRuntimeBuilder.patchBranch(c, ifHugeNeg, hugeNegPos);
		JvmRuntimeBuilder.patchBranch(c, ifBigNegCount, hugeNegPos);
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, rBig.index());
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, biSignum.index());
		int ifNegOne = c.size();
		c.add(Opcode.IFLT);
		JvmRuntimeBuilder.emitU2(c, 0);
		c.add(Opcode.LCONST_0);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, longValueOf.index());
		c.add(Opcode.ARETURN);
		JvmRuntimeBuilder.patchBranch(c, ifNegOne, c.size());
		c.add(Opcode.ICONST_M1);
		c.add(Opcode.I2L);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, longValueOf.index());
		c.add(Opcode.ARETURN);
		// a huge positive count: zero stays zero, anything else is a runaway
		// allocation and signals
		int hugePosPos = c.size();
		JvmRuntimeBuilder.patchBranch(c, goHugePos, hugePosPos);
		JvmRuntimeBuilder.patchBranch(c, goBigPosCount, hugePosPos);
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, rBig.index());
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, biSignum.index());
		int ifZeroPos = c.size();
		c.add(Opcode.IFEQ);
		JvmRuntimeBuilder.emitU2(c, 0);
		c.add(Opcode.NEW);
		JvmRuntimeBuilder.emitU2(c, arithEx.index());
		c.add(Opcode.DUP);
		JvmRuntimeBuilder.emitLdc(c, tooLargeStr.index());
		c.add(Opcode.INVOKESPECIAL);
		JvmRuntimeBuilder.emitU2(c, aeInit.index());
		c.add(Opcode.ATHROW);
		JvmRuntimeBuilder.patchBranch(c, ifZeroPos, c.size());
		c.add(Opcode.LCONST_0);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, longValueOf.index());
		c.add(Opcode.ARETURN);
		return new NumericMethod(name, desc, c, 6, 10, List.of());
	}

	// _intlen(Object a): integer-length, i.e. BigInteger.bitLength -- the bit count of
	// the minimal two's-complement representation, sign bit excluded. For a Long that is
	// 64 - numberOfLeadingZeros of the value (of its complement when negative).
	// Locals: 0=a, 1/2=long a (pre-initialized so both paths share a frame).
	private static NumericMethod buildIntegerLength(Utf8Constant name, Utf8Constant desc, ClassConstant longClass,
			MethodrefConstant longValue, MethodrefConstant longValueOf, MethodrefConstant rBig,
			MethodrefConstant biBitLength, MethodrefConstant longNlz) {
		List<Integer> c = new ArrayList<>();
		c.add(Opcode.LCONST_0);
		c.add(Opcode.LSTORE_1);
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.INSTANCEOF);
		JvmRuntimeBuilder.emitU2(c, longClass.index());
		int ifSlow = c.size();
		c.add(Opcode.IFEQ);
		JvmRuntimeBuilder.emitU2(c, 0);
		emitUnboxLong(c, Opcode.ALOAD_0, longClass, longValue);
		c.add(Opcode.LSTORE_1);
		c.add(Opcode.LLOAD_1);
		c.add(Opcode.LCONST_0);
		c.add(Opcode.LCMP);
		int ifNonNegative = c.size();
		c.add(Opcode.IFGE);
		JvmRuntimeBuilder.emitU2(c, 0);
		c.add(Opcode.LLOAD_1);
		c.add(Opcode.ICONST_M1);
		c.add(Opcode.I2L);
		c.add(Opcode.LXOR);
		c.add(Opcode.LSTORE_1);
		JvmRuntimeBuilder.patchBranch(c, ifNonNegative, c.size());
		JvmRuntimeBuilder.emitIntConstStatic(c, 64);
		c.add(Opcode.LLOAD_1);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, longNlz.index());
		c.add(Opcode.ISUB);
		c.add(Opcode.I2L);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, longValueOf.index());
		c.add(Opcode.ARETURN);
		JvmRuntimeBuilder.patchBranch(c, ifSlow, c.size());
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, rBig.index());
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, biBitLength.index());
		c.add(Opcode.I2L);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, longValueOf.index());
		c.add(Opcode.ARETURN);
		return new NumericMethod(name, desc, c, 4, 3, List.of());
	}

	// _lbitp(Object n, Object index): logbitp as an int 0/1. Both operands Long and the
	// index non-negative: an index at or beyond 63 reads the sign, anything below reads
	// the bit. A negative index (which BigInteger.testBit signals on) and every other
	// operand mix keep the BigInteger path, so the signalling behavior is unchanged.
	// Locals: 0=n, 1=index, 2=int index, 3/4=long n (pre-initialized to share a frame).
	private static NumericMethod buildLogbitp(Utf8Constant name, Utf8Constant desc, ClassConstant longClass,
			MethodrefConstant longValue, MethodrefConstant rBig, MethodrefConstant biTestBit) {
		List<Integer> c = new ArrayList<>();
		c.add(Opcode.ICONST_0);
		c.add(Opcode.ISTORE_2);
		c.add(Opcode.LCONST_0);
		c.add(Opcode.LSTORE_3);
		int[] slowJumps = emitLongLongGuard(c, longClass);
		emitUnboxLong(c, Opcode.ALOAD_1, longClass, longValue);
		c.add(Opcode.L2I);
		c.add(Opcode.ISTORE_2);
		emitUnboxLong(c, Opcode.ALOAD_0, longClass, longValue);
		c.add(Opcode.LSTORE_3);
		c.add(Opcode.ILOAD_2);
		int ifNegativeIndex = c.size();
		c.add(Opcode.IFLT);
		JvmRuntimeBuilder.emitU2(c, 0);
		// An index at or past the sign bit reads the sign: clamp it to 63.
		c.add(Opcode.ILOAD_2);
		JvmRuntimeBuilder.emitIntConstStatic(c, 63);
		int ifInRange = c.size();
		c.add(Opcode.IF_ICMPLT);
		JvmRuntimeBuilder.emitU2(c, 0);
		JvmRuntimeBuilder.emitIntConstStatic(c, 63);
		c.add(Opcode.ISTORE_2);
		JvmRuntimeBuilder.patchBranch(c, ifInRange, c.size());
		c.add(Opcode.LLOAD_3);
		c.add(Opcode.ILOAD_2);
		c.add(Opcode.LUSHR);
		c.add(Opcode.LCONST_1);
		c.add(Opcode.LAND);
		c.add(Opcode.L2I);
		c.add(Opcode.IRETURN);
		int slow = c.size();
		JvmRuntimeBuilder.patchBranch(c, slowJumps[0], slow);
		JvmRuntimeBuilder.patchBranch(c, slowJumps[1], slow);
		JvmRuntimeBuilder.patchBranch(c, ifNegativeIndex, slow);
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, rBig.index());
		emitUnboxLong(c, Opcode.ALOAD_1, longClass, longValue);
		c.add(Opcode.L2I);
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, biTestBit.index());
		c.add(Opcode.IRETURN);
		return new NumericMethod(name, desc, c, 4, 5, List.of());
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
		JvmAsm a = new JvmAsm();
		// x = ((Number) _dbl(value)).doubleValue()
		a.aload(0);
		a.invokestatic(rDbl);
		a.checkcast(numberClass);
		a.invokevirtual(numDoubleValue);
		a.dstore(x);
		// d = min(max(places, 0), MAX_DIGITS); n likewise
		emitClampedIntArg(a, 1, d, numberClass, numIntValue, mathMaxI, mathMinI);
		emitClampedIntArg(a, 2, n, numberClass, numIntValue, mathMaxI, mathMinI);
		// scale = 1.0; for (i = 0; i < d; i++) scale *= 10.0
		a.op(Opcode.DCONST_1);
		a.dstore(scale);
		a.iconst(0);
		a.istore(i);
		int scaleTop = a.label(), scaleEnd = a.label();
		a.bind(scaleTop);
		a.iload(i);
		a.iload(d);
		a.branch(Opcode.IF_ICMPGE, scaleEnd);
		a.dload(scale);
		a.ldc2Double(ten);
		a.dmul();
		a.dstore(scale);
		a.iinc(i, 1);
		a.branch(Opcode.GOTO, scaleTop);
		a.bind(scaleEnd);
		// s = Long.toString((long) Math.abs(Math.rint(x * scale)))
		a.dload(x);
		a.dload(scale);
		a.dmul();
		a.invokestatic(mathRint);
		a.invokestatic(mathAbsD);
		a.op(Opcode.D2L);
		a.invokestatic(longToString);
		a.astore(s);
		// min = max(d + 1, n + d); while (s.length() < min) s = "0".concat(s)
		a.iload(d);
		a.iconst(1);
		a.op(Opcode.IADD);
		a.iload(n);
		a.iload(d);
		a.op(Opcode.IADD);
		a.invokestatic(mathMaxI);
		a.istore(min);
		int padTop = a.label(), padEnd = a.label();
		a.bind(padTop);
		a.aload(s);
		a.invokevirtual(strLength);
		a.iload(min);
		a.branch(Opcode.IF_ICMPGE, padEnd);
		a.ldcString(cp.addString("0"));
		a.aload(s);
		a.invokevirtual(strConcat);
		a.astore(s);
		a.branch(Opcode.GOTO, padTop);
		a.bind(padEnd);
		// split = s.length() - d
		a.aload(s);
		a.invokevirtual(strLength);
		a.iload(d);
		a.op(Opcode.ISUB);
		a.istore(split);
		// out = (x < 0.0) ? "\"-" : (plus != null ? "\"+" : "\"") -- the opening frame
		// quote and the sign in one constant. dcmpg answers 1 for a NaN, which is not
		// negative, exactly as `value < 0.0` is false for one.
		int negative = a.label(), plain = a.label(), haveSign = a.label();
		a.dload(x);
		a.op(Opcode.DCONST_0);
		a.op(Opcode.DCMPG);
		a.branch(Opcode.IFLT, negative);
		a.aload(3);
		a.branch(Opcode.IFNULL, plain);
		a.ldcString(cp.addString("\"+"));
		a.branch(Opcode.GOTO, haveSign);
		a.bind(plain);
		a.ldcString(cp.addString("\""));
		a.branch(Opcode.GOTO, haveSign);
		a.bind(negative);
		a.ldcString(cp.addString("\"-"));
		a.bind(haveSign);
		a.astore(out);
		// out = out.concat(s.substring(0, split))
		a.aload(out);
		a.aload(s);
		a.iconst(0);
		a.iload(split);
		a.invokevirtual(strSub2);
		a.invokevirtual(strConcat);
		a.astore(out);
		// if (d > 0) out = out.concat(".").concat(s.substring(split))
		int noPoint = a.label();
		a.iload(d);
		a.branch(Opcode.IFLE, noPoint);
		a.aload(out);
		a.ldcString(cp.addString("."));
		a.invokevirtual(strConcat);
		a.aload(s);
		a.iload(split);
		a.invokevirtual(strSub1);
		a.invokevirtual(strConcat);
		a.astore(out);
		a.bind(noPoint);
		// return out.concat("\"") -- the closing frame quote
		a.aload(out);
		a.ldcString(cp.addString("\""));
		a.invokevirtual(strConcat);
		a.areturn();
		return new NumericMethod(name, desc, a.finish(), 6, 16, List.of());
	}

	// Loads argument slot `arg` as an int and stores it clamped into [0, MAX_DIGITS].
	private static void emitClampedIntArg(JvmAsm a, int arg, int slot, ClassConstant numberClass,
			MethodrefConstant numIntValue, MethodrefConstant mathMaxI, MethodrefConstant mathMinI) {
		a.aload(arg);
		a.checkcast(numberClass);
		a.invokevirtual(numIntValue);
		a.iconst(0);
		a.invokestatic(mathMaxI);
		a.iconst(am.ik.rontolisp.compiler.FixedDecimal.MAX_DIGITS);
		a.invokestatic(mathMinI);
		a.istore(slot);
	}

	// Emits the two `instanceof Long` guards shared by _mod and _cmp, returning the two
	// branch positions that must be patched to the slow (BigInteger) path.
	private static int[] emitLongLongGuard(List<Integer> c, ClassConstant longClass) {
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.INSTANCEOF);
		JvmRuntimeBuilder.emitU2(c, longClass.index());
		int ifSlow1 = c.size();
		c.add(Opcode.IFEQ);
		JvmRuntimeBuilder.emitU2(c, 0);
		c.add(Opcode.ALOAD_1);
		c.add(Opcode.INSTANCEOF);
		JvmRuntimeBuilder.emitU2(c, longClass.index());
		int ifSlow2 = c.size();
		c.add(Opcode.IFEQ);
		JvmRuntimeBuilder.emitU2(c, 0);
		return new int[] { ifSlow1, ifSlow2 };
	}

	// Emits: load slot, checkcast Long, Long.longValue() -> long on stack.
	private static void emitUnboxLong(List<Integer> c, int loadOpcode, ClassConstant longClass,
			MethodrefConstant longValue) {
		c.add(loadOpcode);
		c.add(Opcode.CHECKCAST);
		JvmRuntimeBuilder.emitU2(c, longClass.index());
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, longValue.index());
	}

	// Emits: load slot, _dbl(x) (boxed Double), checkcast Number, Number.doubleValue() ->
	// double on stack. _dbl coerces Long/BigInteger/ratio/Double to a Double.
	private static void emitToDouble(List<Integer> c, int loadOpcode, MethodrefConstant rDbl, ClassConstant numberClass,
			MethodrefConstant numDoubleValue) {
		c.add(loadOpcode);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, rDbl.index());
		c.add(Opcode.CHECKCAST);
		JvmRuntimeBuilder.emitU2(c, numberClass.index());
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, numDoubleValue.index());
	}

	// Emits a Double fast path at the top of a binary numeric op: when either operand is
	// a
	// Double, computes _dbl(a) <doubleOpcode> _dbl(b) in double arithmetic, boxes it, and
	// returns. Otherwise falls through to the existing integer/ratio body. This gives
	// float
	// contagion for non-literal operands (variables, parameters, #'+/#'* as values),
	// which
	// the compile-site double-literal fast path cannot detect.
	private static void emitDoubleBinaryPrologue(List<Integer> c, ClassConstant doubleClass, MethodrefConstant rDbl,
			ClassConstant numberClass, MethodrefConstant numDoubleValue, MethodrefConstant doubleValueOf,
			int doubleOpcode) {
		emitDoubleBinaryPrologue(c, doubleClass, rDbl, numberClass, numDoubleValue, doubleValueOf, doubleOpcode, null);
	}

	// The same, with an optional (DD)D HELPER standing in for the single opcode -- what
	// _mod needs, whose float case is CL's divisor-signed modulo (_fmod), not DREM.
	private static void emitDoubleBinaryPrologue(List<Integer> c, ClassConstant doubleClass, MethodrefConstant rDbl,
			ClassConstant numberClass, MethodrefConstant numDoubleValue, MethodrefConstant doubleValueOf,
			int doubleOpcode, @Nullable MethodrefConstant doubleHelper) {
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.INSTANCEOF);
		JvmRuntimeBuilder.emitU2(c, doubleClass.index());
		int ifADouble = c.size();
		c.add(Opcode.IFNE);
		JvmRuntimeBuilder.emitU2(c, 0);
		c.add(Opcode.ALOAD_1);
		c.add(Opcode.INSTANCEOF);
		JvmRuntimeBuilder.emitU2(c, doubleClass.index());
		int ifBNotDouble = c.size();
		c.add(Opcode.IFEQ);
		JvmRuntimeBuilder.emitU2(c, 0);
		JvmRuntimeBuilder.patchBranch(c, ifADouble, c.size());
		emitToDouble(c, Opcode.ALOAD_0, rDbl, numberClass, numDoubleValue);
		emitToDouble(c, Opcode.ALOAD_1, rDbl, numberClass, numDoubleValue);
		if (doubleHelper != null) {
			c.add(Opcode.INVOKESTATIC);
			JvmRuntimeBuilder.emitU2(c, doubleHelper.index());
		}
		else {
			c.add(doubleOpcode);
		}
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, doubleValueOf.index());
		c.add(Opcode.ARETURN);
		JvmRuntimeBuilder.patchBranch(c, ifBNotDouble, c.size());
	}

	// Like emitDoubleBinaryPrologue, but for the unary _neg: negates _dbl(a) when a is a
	// Double.
	private static void emitDoubleUnaryPrologue(List<Integer> c, ClassConstant doubleClass, MethodrefConstant rDbl,
			ClassConstant numberClass, MethodrefConstant numDoubleValue, MethodrefConstant doubleValueOf,
			int doubleOpcode) {
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.INSTANCEOF);
		JvmRuntimeBuilder.emitU2(c, doubleClass.index());
		int ifNotDouble = c.size();
		c.add(Opcode.IFEQ);
		JvmRuntimeBuilder.emitU2(c, 0);
		emitToDouble(c, Opcode.ALOAD_0, rDbl, numberClass, numDoubleValue);
		c.add(doubleOpcode);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, doubleValueOf.index());
		c.add(Opcode.ARETURN);
		JvmRuntimeBuilder.patchBranch(c, ifNotDouble, c.size());
	}

	// Emits: _norm(_big(a).<biOp>(_big(b))) followed by areturn.
	private static void emitBigBinary(List<Integer> c, MethodrefConstant rBig, MethodrefConstant biOp,
			MethodrefConstant rNorm) {
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, rBig.index());
		c.add(Opcode.ALOAD_1);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, rBig.index());
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, biOp.index());
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, rNorm.index());
		c.add(Opcode.ARETURN);
	}

	// Emits the rational path for a binary operation followed by areturn. With a cross
	// operation (add/subtract) the result is
	// _rat(num(a)*den(b) <crossOp> num(b)*den(a), den(a)*den(b)); without one it is the
	// multiplication _rat(num(a)*num(b), den(a)*den(b)).
	private static void emitRatioBinary(List<Integer> c, MethodrefConstant rRatNum, MethodrefConstant rRatDen,
			MethodrefConstant rRat, MethodrefConstant biMul, @Nullable MethodrefConstant crossOp) {
		if (crossOp != null) {
			c.add(Opcode.ALOAD_0);
			c.add(Opcode.INVOKESTATIC);
			JvmRuntimeBuilder.emitU2(c, rRatNum.index());
			c.add(Opcode.ALOAD_1);
			c.add(Opcode.INVOKESTATIC);
			JvmRuntimeBuilder.emitU2(c, rRatDen.index());
			c.add(Opcode.INVOKEVIRTUAL);
			JvmRuntimeBuilder.emitU2(c, biMul.index());
			c.add(Opcode.ALOAD_1);
			c.add(Opcode.INVOKESTATIC);
			JvmRuntimeBuilder.emitU2(c, rRatNum.index());
			c.add(Opcode.ALOAD_0);
			c.add(Opcode.INVOKESTATIC);
			JvmRuntimeBuilder.emitU2(c, rRatDen.index());
			c.add(Opcode.INVOKEVIRTUAL);
			JvmRuntimeBuilder.emitU2(c, biMul.index());
			c.add(Opcode.INVOKEVIRTUAL);
			JvmRuntimeBuilder.emitU2(c, crossOp.index());
		}
		else {
			c.add(Opcode.ALOAD_0);
			c.add(Opcode.INVOKESTATIC);
			JvmRuntimeBuilder.emitU2(c, rRatNum.index());
			c.add(Opcode.ALOAD_1);
			c.add(Opcode.INVOKESTATIC);
			JvmRuntimeBuilder.emitU2(c, rRatNum.index());
			c.add(Opcode.INVOKEVIRTUAL);
			JvmRuntimeBuilder.emitU2(c, biMul.index());
		}
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, rRatDen.index());
		c.add(Opcode.ALOAD_1);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, rRatDen.index());
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, biMul.index());
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, rRat.index());
		c.add(Opcode.ARETURN);
	}

	// Corrects the remainder in local 4 to the sign of the divisor in local 3, which is
	// what turns a remainder into CL's mod: when the remainder is non-zero and its sign
	// differs from the divisor's, the divisor is added. Shared by _mod's BigInteger and
	// rational paths.
	private static void emitDivisorSignCorrection(List<Integer> c, MethodrefConstant biSignum,
			MethodrefConstant biAdd) {
		// if (r.signum() == 0) goto done
		c.add(Opcode.ALOAD);
		c.add(4);
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, biSignum.index());
		int ifZero = c.size();
		c.add(Opcode.IFEQ);
		JvmRuntimeBuilder.emitU2(c, 0);
		// if (r.signum() == B.signum()) goto done
		c.add(Opcode.ALOAD);
		c.add(4);
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, biSignum.index());
		c.add(Opcode.ALOAD_3);
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, biSignum.index());
		int ifSameSign = c.size();
		c.add(Opcode.IF_ICMPEQ);
		JvmRuntimeBuilder.emitU2(c, 0);
		// r = r.add(B)
		c.add(Opcode.ALOAD);
		c.add(4);
		c.add(Opcode.ALOAD_3);
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, biAdd.index());
		c.add(Opcode.ASTORE);
		c.add(4);
		int done = c.size();
		JvmRuntimeBuilder.patchBranch(c, ifZero, done);
		JvmRuntimeBuilder.patchBranch(c, ifSameSign, done);
	}

	// Emits the head of _mod/_rem's rational path. With a = an/ad and b = bn/bd the
	// quotient a/b is (an*bd)/(ad*bn), so the integer remainder of THAT division, read
	// over the common denominator ad*bd, is the answer -- the same computation the
	// integer path does, one level up. Leaves the quotient's denominator (which carries
	// the divisor's sign, denominators being positive) in local 3 and the remainder in
	// local 4, the same slots the BigInteger path uses, so _mod's sign correction is
	// shared.
	private static void emitRatioRemainderPrefix(List<Integer> c, MethodrefConstant rRatNum, MethodrefConstant rRatDen,
			MethodrefConstant biMul, MethodrefConstant biRem) {
		// BigInteger d = _ratden(a).multiply(_ratnum(b));
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, rRatDen.index());
		c.add(Opcode.ALOAD_1);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, rRatNum.index());
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, biMul.index());
		c.add(Opcode.ASTORE_3);
		// BigInteger r = _ratnum(a).multiply(_ratden(b)).remainder(d);
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, rRatNum.index());
		c.add(Opcode.ALOAD_1);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, rRatDen.index());
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, biMul.index());
		c.add(Opcode.ALOAD_3);
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, biRem.index());
		c.add(Opcode.ASTORE);
		c.add(4);
	}

	// Emits the tail of _mod/_rem's rational path: consumes the numerator on the stack,
	// pushes the common denominator ad*bd, and returns the normalized _rat.
	private static void emitRatioRemainderDenominator(List<Integer> c, MethodrefConstant rRatDen,
			MethodrefConstant biMul, MethodrefConstant rRat) {
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, rRatDen.index());
		c.add(Opcode.ALOAD_1);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, rRatDen.index());
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, biMul.index());
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, rRat.index());
		c.add(Opcode.ARETURN);
	}

	// Emits: load slot, checkcast BigInteger[], push index, aaload.
	private static void emitRatioElement(List<Integer> c, int loadOpcode, ClassConstant ratArrClass, int indexConst) {
		c.add(loadOpcode);
		c.add(Opcode.CHECKCAST);
		JvmRuntimeBuilder.emitU2(c, ratArrClass.index());
		c.add(indexConst);
		c.add(Opcode.AALOAD);
	}

	private static void emitLload(List<Integer> c, int slot) {
		c.add(Opcode.LLOAD);
		c.add(slot);
	}

	private static void emitLdc2(List<Integer> c, LongConstant constant) {
		c.add(Opcode.LDC2_W);
		JvmRuntimeBuilder.emitU2(c, constant.index());
	}

}
