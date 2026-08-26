package am.ik.rontolisp.codegen.jvm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

	static final String CMP = "_cmp";

	static final String CMPB = "_cmpb";

	static final String ABS = "_abs";

	static final String SIGNUM = "_signum";

	static final String RANDOM = "_random";

	static final String MIN = "_min";

	static final String MAX = "_max";

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

	/** Raises a rational base to an integer power, keeping an exact result. */
	static final String POW = "_pow";

	/** Value equality that compares ratios element-wise ({@code eql} semantics). */
	static final String EQV = "_eqv";

	/**
	 * Object identity ({@code eq} semantics): like {@link #EQV} but floats and ratios are
	 * never equal (they are distinct boxed objects, not interned).
	 */
	static final String EQ_STRICT = "_eq";

	/**
	 * Structural equality ({@code equal} semantics): cons cells are compared recursively
	 * by car and cdr, everything else delegates to {@link #EQV}.
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
	 * Builds all numeric helper methods and registers their constant-pool entries.
	 * @param cp the constant pool to populate
	 * @param thisClass the generated class
	 * @param strvMethod the {@code _strv} character-vector normalizer emitted with the
	 * array runtime helpers, or null when the program uses no arrays; when present,
	 * {@code _eqv}'s final fallback normalizes both operands through it so a mutable
	 * character vector compares equal to the string with the same content
	 * @return the helper methods and the invokable references compiled code calls
	 */
	static NumericRuntime build(ConstantPool cp, ClassConstant thisClass,
			@org.jspecify.annotations.Nullable MethodrefConstant strvMethod,
			@org.jspecify.annotations.Nullable ClassConstant strArrClass) {
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
		ClassConstant mathCtxClass = cp.addClass(cp.addUtf8("java/math/MathContext"));

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
		MethodrefConstant mathPow = cp.addMethodref(mathClass,
				cp.addNameAndType(cp.addUtf8("pow"), cp.addUtf8("(DD)D")));
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

		MethodrefConstant bdInit = cp.addMethodref(bigDecClass,
				cp.addNameAndType(cp.addUtf8("<init>"), cp.addUtf8("(" + BIG + ")V")));
		MethodrefConstant bdDivide = cp.addMethodref(bigDecClass, cp.addNameAndType(cp.addUtf8("divide"),
				cp.addUtf8("(Ljava/math/BigDecimal;Ljava/math/MathContext;)Ljava/math/BigDecimal;")));
		MethodrefConstant bdDoubleValue = cp.addMethodref(bigDecClass,
				cp.addNameAndType(cp.addUtf8("doubleValue"), cp.addUtf8("()D")));
		FieldrefConstant mcDecimal64 = cp.addFieldref(mathCtxClass,
				cp.addNameAndType(cp.addUtf8("DECIMAL64"), cp.addUtf8("Ljava/math/MathContext;")));
		MethodrefConstant doubleValueOf = cp.addMethodref(doubleClass,
				cp.addNameAndType(cp.addUtf8("valueOf"), cp.addUtf8("(D)Ljava/lang/Double;")));
		MethodrefConstant numDoubleValue = cp.addMethodref(numberClass,
				cp.addNameAndType(cp.addUtf8("doubleValue"), cp.addUtf8("()D")));

		LongConstant cMin = cp.addLong(Long.MIN_VALUE);

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
		Utf8Constant nCmp = cp.addUtf8(CMP);
		Utf8Constant nCmpb = cp.addUtf8(CMPB);
		Utf8Constant nAbs = cp.addUtf8(ABS);
		Utf8Constant nSignum = cp.addUtf8(SIGNUM);
		Utf8Constant nRandom = cp.addUtf8(RANDOM);
		Utf8Constant nMin = cp.addUtf8(MIN);
		Utf8Constant nMax = cp.addUtf8(MAX);
		Utf8Constant nDbl = cp.addUtf8(DBL);
		Utf8Constant nPow = cp.addUtf8(POW);
		Utf8Constant nEqv = cp.addUtf8(EQV);
		Utf8Constant nEqStrict = cp.addUtf8(EQ_STRICT);
		Utf8Constant nEqual = cp.addUtf8(EQUAL);
		Utf8Constant nRatTrunc = cp.addUtf8(RAT_TRUNC);
		Utf8Constant nRatFloor = cp.addUtf8(RAT_FLOOR);
		Utf8Constant nRatCeil = cp.addUtf8(RAT_CEIL);
		Utf8Constant nRatRound = cp.addUtf8(RAT_ROUND);
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
		MethodrefConstant rCmp = cp.addMethodref(thisClass, cp.addNameAndType(nCmp, dCmp));
		MethodrefConstant rCmpb = cp.addMethodref(thisClass, cp.addNameAndType(nCmpb, dCmp));
		MethodrefConstant rAbs = cp.addMethodref(thisClass, cp.addNameAndType(nAbs, dUnary));
		MethodrefConstant rSignum = cp.addMethodref(thisClass, cp.addNameAndType(nSignum, dUnary));
		MethodrefConstant rRandom = cp.addMethodref(thisClass, cp.addNameAndType(nRandom, dUnary));
		MethodrefConstant rMin = cp.addMethodref(thisClass, cp.addNameAndType(nMin, dBinary));
		MethodrefConstant rMax = cp.addMethodref(thisClass, cp.addNameAndType(nMax, dBinary));
		MethodrefConstant rDbl = cp.addMethodref(thisClass, cp.addNameAndType(nDbl, dUnary));
		MethodrefConstant rPow = cp.addMethodref(thisClass, cp.addNameAndType(nPow, dBinary));
		MethodrefConstant rEqv = cp.addMethodref(thisClass, cp.addNameAndType(nEqv, dCmp));
		MethodrefConstant rEqStrict = cp.addMethodref(thisClass, cp.addNameAndType(nEqStrict, dCmp));
		MethodrefConstant rEqual = cp.addMethodref(thisClass, cp.addNameAndType(nEqual, dCmp));
		MethodrefConstant rRatTrunc = cp.addMethodref(thisClass, cp.addNameAndType(nRatTrunc, dUnary));
		MethodrefConstant rRatFloor = cp.addMethodref(thisClass, cp.addNameAndType(nRatFloor, dUnary));
		MethodrefConstant rRatCeil = cp.addMethodref(thisClass, cp.addNameAndType(nRatCeil, dUnary));
		MethodrefConstant rRatRound = cp.addMethodref(thisClass, cp.addNameAndType(nRatRound, dUnary));
		MethodrefConstant rLogand = cp.addMethodref(thisClass, cp.addNameAndType(nLogand, dBinary));
		MethodrefConstant rLogior = cp.addMethodref(thisClass, cp.addNameAndType(nLogior, dBinary));
		MethodrefConstant rLogxor = cp.addMethodref(thisClass, cp.addNameAndType(nLogxor, dBinary));
		MethodrefConstant rLognot = cp.addMethodref(thisClass, cp.addNameAndType(nLognot, dUnary));
		MethodrefConstant rAsh = cp.addMethodref(thisClass, cp.addNameAndType(nAsh, dBinary));
		MethodrefConstant rIntLen = cp.addMethodref(thisClass, cp.addNameAndType(nIntLen, dUnary));
		MethodrefConstant rLogbitp = cp.addMethodref(thisClass, cp.addNameAndType(nLogbitp, dCmp));
		MethodrefConstant rFixDec = cp.addMethodref(thisClass, cp.addNameAndType(nFixDec, dFixDec));

		List<NumericMethod> methods = new ArrayList<>();
		methods.add(buildBig(nBig, dBig, longClass, bigClass, longValue, biValueOf));
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
				biSignum, biAdd));
		methods.add(buildRem(nRem, dBinary, longClass, longValue, longValueOf, rBig, rNorm, biRem));
		methods.add(buildFmod(nFmod, dFmod));
		methods.add(buildCmp(nCmp, dCmp, longClass, longValue, rBig, biCompareTo, ratArrClass, rRatNum, rRatDen, biMul,
				doubleClass, rDbl, numberClass, numDoubleValue));
		methods.add(buildCmpBits(nCmpb, dCmp, doubleClass, rDbl, numberClass, numDoubleValue, rCmp, intSignum));
		methods.add(buildAbs(nAbs, dUnary, longClass, bigClass, longValue, longValueOf, absLong, biValueOf, biNeg,
				biAbs, rNorm, cMin, ratArrClass, rRatNum, rRatDen, rRat, doubleClass, rDbl, numberClass, numDoubleValue,
				doubleValueOf, absDouble));
		methods.add(buildSignum(nSignum, dUnary, doubleClass, rDbl, numberClass, numDoubleValue, doubleValueOf,
				signumDouble, rRatNum, biSignum, longValueOf));
		methods.add(buildRandom(nRandom, dUnary, doubleClass, rDbl, numberClass, numDoubleValue, doubleValueOf,
				longValueOf, tlrCurrent, tlrNextDouble));
		methods.add(buildSelect(nMin, dBinary, rCmp, Opcode.IFGT));
		methods.add(buildSelect(nMax, dBinary, rCmp, Opcode.IFLT));
		methods.add(buildDbl(nDbl, dUnary, ratArrClass, numberClass, bigDecClass, bdInit, bdDivide, bdDoubleValue,
				mcDecimal64, doubleValueOf, numDoubleValue, rRatNum, rRatDen));
		methods.add(buildPow(nPow, dBinary, rRatNum, rRatDen, rRat, biPow, doubleClass, longClass, longValue,
				numberClass, numDoubleValue, doubleValueOf, mathPow, rDbl));
		methods.add(buildEqv(nEqv, dCmp, ratArrClass, intArrClass, objEquals, strvMethod));
		methods.add(buildEqStrict(nEqStrict, dCmp, doubleClass, ratArrClass, rEqv));
		methods.add(buildEqual(nEqual, dCmp, objArrClass, ratArrClass, integerClass, rEqv, rEqual, strArrClass));
		methods.add(buildRatTrunc(nRatTrunc, dUnary, rRatNum, rRatDen, rNorm, biDiv));
		methods.add(buildRatFloor(nRatFloor, dUnary, rRatNum, rRatDen, rNorm, biMod, biSub, biDiv, null, null));
		methods.add(buildRatFloor(nRatCeil, dUnary, rRatNum, rRatDen, rNorm, biMod, biSub, biDiv, biOne, biAdd));
		methods.add(buildRatRound(nRatRound, dUnary, rRatNum, rRatDen, rNorm, biMod, biSub, biDiv, biMul, biShiftLeft,
				biCompareTo, biTestBit, biOne, biAdd));
		methods.add(buildLogOp(nLogand, dBinary, longClass, longValue, longValueOf, rBig, rNorm, biAnd, Opcode.LAND));
		methods.add(buildLogOp(nLogior, dBinary, longClass, longValue, longValueOf, rBig, rNorm, biOr, Opcode.LOR));
		methods.add(buildLogOp(nLogxor, dBinary, longClass, longValue, longValueOf, rBig, rNorm, biXor, Opcode.LXOR));
		methods.add(buildLogNot(nLognot, dUnary, longClass, longValue, longValueOf, rBig, rNorm, biNot));
		methods.add(buildAsh(nAsh, dBinary, longClass, longValue, longValueOf, rBig, rNorm, biShiftLeft));
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
		ops.put(CMP, rCmp);
		ops.put(CMPB, rCmpb);
		ops.put(ABS, rAbs);
		ops.put(SIGNUM, rSignum);
		ops.put(RANDOM, rRandom);
		ops.put(MIN, rMin);
		ops.put(MAX, rMax);
		ops.put(BIG_OP, rBig);
		ops.put(NORM_OP, rNorm);
		ops.put(RAT_NUM, rRatNum);
		ops.put(RAT_DEN, rRatDen);
		ops.put(RAT, rRat);
		ops.put(DBL, rDbl);
		ops.put(POW, rPow);
		ops.put(EQV, rEqv);
		ops.put(EQ_STRICT, rEqStrict);
		ops.put(EQUAL, rEqual);
		ops.put(RAT_TRUNC, rRatTrunc);
		ops.put(RAT_FLOOR, rRatFloor);
		ops.put(RAT_CEIL, rRatCeil);
		ops.put(RAT_ROUND, rRatRound);
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
			ClassConstant bigClass, MethodrefConstant longValue, MethodrefConstant biValueOf) {
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
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.CHECKCAST);
		JvmRuntimeBuilder.emitU2(c, bigClass.index());
		c.add(Opcode.ARETURN);
		return new NumericMethod(name, desc, c, 2, 1, List.of());
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
			MethodrefConstant biAdd) {
		List<Integer> c = new ArrayList<>();
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
		c.add(Opcode.ALOAD);
		c.add(4);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, rNorm.index());
		c.add(Opcode.ARETURN);
		return new NumericMethod(name, desc, c, 4, 5, List.of());
	}

	// _rem(Object a, Object b): remainder whose result takes the sign of the dividend
	// (Java/BigInteger remainder). Long fast path, BigInteger.remainder otherwise.
	private static NumericMethod buildRem(Utf8Constant name, Utf8Constant desc, ClassConstant longClass,
			MethodrefConstant longValue, MethodrefConstant longValueOf, MethodrefConstant rBig, MethodrefConstant rNorm,
			MethodrefConstant biRem) {
		List<Integer> c = new ArrayList<>();
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
		return new NumericMethod(name, desc, c, 4, 2, List.of());
	}

	// _fmod(double a, double b): floating-point modulo whose result takes the sign of the
	// divisor. r = a % b; if (r * b < 0) r += b (opposite signs and r != 0).
	private static NumericMethod buildFmod(Utf8Constant name, Utf8Constant desc) {
		List<Integer> c = new ArrayList<>();
		c.add(Opcode.DLOAD_0);
		c.add(Opcode.DLOAD_2);
		c.add(Opcode.DREM);
		c.add(Opcode.DSTORE);
		c.add(4);
		c.add(Opcode.DLOAD);
		c.add(4);
		c.add(Opcode.DLOAD_2);
		c.add(Opcode.DMUL);
		c.add(Opcode.DCONST_0);
		c.add(Opcode.DCMPG);
		int ifNonNeg = c.size();
		c.add(Opcode.IFGE);
		JvmRuntimeBuilder.emitU2(c, 0);
		c.add(Opcode.DLOAD);
		c.add(4);
		c.add(Opcode.DLOAD_2);
		c.add(Opcode.DADD);
		c.add(Opcode.DSTORE);
		c.add(4);
		int done = c.size();
		JvmRuntimeBuilder.patchBranch(c, ifNonNeg, done);
		c.add(Opcode.DLOAD);
		c.add(4);
		c.add(Opcode.DRETURN);
		return new NumericMethod(name, desc, c, 4, 6, List.of());
	}

	// _cmp(Object a, Object b): long comparison, BigInteger.compareTo, or rational
	// cross-multiplication (denominators are positive), returning -1/0/1.
	private static NumericMethod buildCmp(Utf8Constant name, Utf8Constant desc, ClassConstant longClass,
			MethodrefConstant longValue, MethodrefConstant rBig, MethodrefConstant biCompareTo,
			ClassConstant ratArrClass, MethodrefConstant rRatNum, MethodrefConstant rRatDen, MethodrefConstant biMul,
			ClassConstant doubleClass, MethodrefConstant rDbl, ClassConstant numberClass,
			MethodrefConstant numDoubleValue) {
		List<Integer> c = new ArrayList<>();
		emitDoubleCmpPrologue(c, doubleClass, rDbl, numberClass, numDoubleValue);
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
		return new NumericMethod(name, desc, c, 4, 2, List.of());
	}

	// _cmpb(Object a, Object b): the comparison as a bitmask -- 1 = a<b, 2 = a=b,
	// 4 = a>b, 0 = unordered (a NaN operand). The comparison operators AND the mask
	// they accept and branch on nonzero, so NaN fails every one of = < > <= >= (IEEE),
	// which a -1/0/1 signum cannot express. Non-double operands delegate to _cmp
	// (exact, never unordered).
	private static NumericMethod buildCmpBits(Utf8Constant name, Utf8Constant desc, ClassConstant doubleClass,
			MethodrefConstant rDbl, ClassConstant numberClass, MethodrefConstant numDoubleValue, MethodrefConstant rCmp,
			MethodrefConstant intSignum) {
		List<Integer> c = new ArrayList<>();
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
		// exact types: 1 << (signum(_cmp(a, b)) + 1)
		JvmRuntimeBuilder.patchBranch(c, ifBNotDouble, c.size());
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
		return new NumericMethod(name, desc, c, 4, 6, List.of());
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
			MethodrefConstant absDouble) {
		List<Integer> c = new ArrayList<>();
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
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.CHECKCAST);
		JvmRuntimeBuilder.emitU2(c, bigClass.index());
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
			MethodrefConstant biSignum, MethodrefConstant longValueOf) {
		List<Integer> c = new ArrayList<>();
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

	// _min/_max(Object a, Object b): pick an argument by the sign of _cmp(a, b).
	private static NumericMethod buildSelect(Utf8Constant name, Utf8Constant desc, MethodrefConstant rCmp,
			int branchToB) {
		List<Integer> c = new ArrayList<>();
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.ALOAD_1);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, rCmp.index());
		int ifB = c.size();
		c.add(branchToB);
		JvmRuntimeBuilder.emitU2(c, 0);
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.ARETURN);
		JvmRuntimeBuilder.patchBranch(c, ifB, c.size());
		c.add(Opcode.ALOAD_1);
		c.add(Opcode.ARETURN);
		return new NumericMethod(name, desc, c, 2, 2, List.of());
	}

	// _dbl(Object x): boxed Double for any numeric value. A ratio divides numerator by
	// denominator via BigDecimal so huge components do not overflow to infinity first;
	// Long/BigInteger/Double go through Number.doubleValue().
	private static NumericMethod buildDbl(Utf8Constant name, Utf8Constant desc, ClassConstant ratArrClass,
			ClassConstant numberClass, ClassConstant bigDecClass, MethodrefConstant bdInit, MethodrefConstant bdDivide,
			MethodrefConstant bdDoubleValue, FieldrefConstant mcDecimal64, MethodrefConstant doubleValueOf,
			MethodrefConstant numDoubleValue, MethodrefConstant rRatNum, MethodrefConstant rRatDen) {
		List<Integer> c = new ArrayList<>();
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.INSTANCEOF);
		JvmRuntimeBuilder.emitU2(c, ratArrClass.index());
		int ifNotRat = c.size();
		c.add(Opcode.IFEQ);
		JvmRuntimeBuilder.emitU2(c, 0);
		c.add(Opcode.NEW);
		JvmRuntimeBuilder.emitU2(c, bigDecClass.index());
		c.add(Opcode.DUP);
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, rRatNum.index());
		c.add(Opcode.INVOKESPECIAL);
		JvmRuntimeBuilder.emitU2(c, bdInit.index());
		c.add(Opcode.NEW);
		JvmRuntimeBuilder.emitU2(c, bigDecClass.index());
		c.add(Opcode.DUP);
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, rRatDen.index());
		c.add(Opcode.INVOKESPECIAL);
		JvmRuntimeBuilder.emitU2(c, bdInit.index());
		c.add(Opcode.GETSTATIC);
		JvmRuntimeBuilder.emitU2(c, mcDecimal64.index());
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, bdDivide.index());
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, bdDoubleValue.index());
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, doubleValueOf.index());
		c.add(Opcode.ARETURN);
		JvmRuntimeBuilder.patchBranch(c, ifNotRat, c.size());
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.CHECKCAST);
		JvmRuntimeBuilder.emitU2(c, numberClass.index());
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, numDoubleValue.index());
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, doubleValueOf.index());
		c.add(Opcode.ARETURN);
		return new NumericMethod(name, desc, c, 5, 1, List.of());
	}

	// _pow(Object base, Object e): exact rational power for an integer exponent --
	// (a/b)^e = a^e/b^e for e >= 0 and b^-e/a^-e for e < 0 (so an integer base with a
	// negative exponent yields a ratio) -- and Math.pow over the float contagion for
	// anything else. The compile-time double check (hasDoubleLiteral) only sees literals,
	// so a double or ratio arriving through a variable or a call is handled here rather
	// than cast: a Double base with an integer exponent short-circuits to Math.pow, and a
	// non-Long exponent (a Double, a ratio, a huge BigInteger) takes Math.pow(_dbl(base),
	// _dbl(e)) -- the interpreter's answer for (expt 4 1/2) = 2.0 and (expt 2 0.5).
	private static NumericMethod buildPow(Utf8Constant name, Utf8Constant desc, MethodrefConstant rRatNum,
			MethodrefConstant rRatDen, MethodrefConstant rRat, MethodrefConstant biPow, ClassConstant doubleClass,
			ClassConstant longClass, MethodrefConstant longValue, ClassConstant numberClass,
			MethodrefConstant numDoubleValue, MethodrefConstant doubleValueOf, MethodrefConstant mathPow,
			MethodrefConstant rDbl) {
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
		return new NumericMethod(name, desc, c, 5, 3, List.of());
	}

	// _eqv(Object a, Object b): value equality used by eq; ratios compare element-wise
	// (Object[].equals is reference equality), CHARACTERs (int[]{codePoint}) compare by
	// their sole code point (int[].equals is also reference equality, and the JVM does
	// not cache char literals like Character.valueOf(char) does), everything else uses
	// a.equals(b). When the array helpers are emitted (strvMethod non-null) the fallback
	// first normalizes both operands through _strv so a mutable character vector
	// compares equal to a string with the same content.
	private static NumericMethod buildEqv(Utf8Constant name, Utf8Constant desc, ClassConstant ratArrClass,
			ClassConstant intArrClass, MethodrefConstant objEquals,
			@org.jspecify.annotations.Nullable MethodrefConstant strvMethod) {
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
		c.add(Opcode.ALOAD_0);
		if (strvMethod != null) {
			c.add(Opcode.INVOKESTATIC);
			JvmRuntimeBuilder.emitU2(c, strvMethod.index());
		}
		c.add(Opcode.ALOAD_1);
		if (strvMethod != null) {
			c.add(Opcode.INVOKESTATIC);
			JvmRuntimeBuilder.emitU2(c, strvMethod.index());
		}
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, objEquals.index());
		c.add(Opcode.IRETURN);
		return new NumericMethod(name, desc, c, 3, 2, List.of());
	}

	// _eq(Object a, Object b): eq semantics. Floats (Double) and ratios (BigInteger[])
	// are
	// distinct boxed objects, so two of them are never eq; everything else delegates to
	// _eqv (so integers and symbols still compare by value/name).
	private static NumericMethod buildEqStrict(Utf8Constant name, Utf8Constant desc, ClassConstant doubleClass,
			ClassConstant ratArrClass, MethodrefConstant eqv) {
		List<Integer> c = new ArrayList<>();
		// if (a instanceof Double && b instanceof Double) return 0;
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.INSTANCEOF);
		JvmRuntimeBuilder.emitU2(c, doubleClass.index());
		int ifNotDoubleA = c.size();
		c.add(Opcode.IFEQ);
		JvmRuntimeBuilder.emitU2(c, 0);
		c.add(Opcode.ALOAD_1);
		c.add(Opcode.INSTANCEOF);
		JvmRuntimeBuilder.emitU2(c, doubleClass.index());
		int ifNotDoubleB = c.size();
		c.add(Opcode.IFEQ);
		JvmRuntimeBuilder.emitU2(c, 0);
		c.add(Opcode.ICONST_0);
		c.add(Opcode.IRETURN);
		// if (a instanceof BigInteger[] && b instanceof BigInteger[]) return 0;
		JvmRuntimeBuilder.patchBranch(c, ifNotDoubleA, c.size());
		JvmRuntimeBuilder.patchBranch(c, ifNotDoubleB, c.size());
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.INSTANCEOF);
		JvmRuntimeBuilder.emitU2(c, ratArrClass.index());
		int ifNotRatA = c.size();
		c.add(Opcode.IFEQ);
		JvmRuntimeBuilder.emitU2(c, 0);
		c.add(Opcode.ALOAD_1);
		c.add(Opcode.INSTANCEOF);
		JvmRuntimeBuilder.emitU2(c, ratArrClass.index());
		int ifNotRatB = c.size();
		c.add(Opcode.IFEQ);
		JvmRuntimeBuilder.emitU2(c, 0);
		c.add(Opcode.ICONST_0);
		c.add(Opcode.IRETURN);
		// return _eqv(a, b);
		JvmRuntimeBuilder.patchBranch(c, ifNotRatA, c.size());
		JvmRuntimeBuilder.patchBranch(c, ifNotRatB, c.size());
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.ALOAD_1);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, eqv.index());
		c.add(Opcode.IRETURN);
		return new NumericMethod(name, desc, c, 2, 2, List.of());
	}

	// _equal(Object a, Object b): structural equality. Two cons cells (Object[] of length
	// 2 whose head is not an Integer, distinguishing them from function references and
	// ratios) are equal when their cars and cdrs are recursively _equal; everything else
	// (including nil/null) delegates to _eqv, so numbers, symbols, strings and nil
	// compare
	// by value. Returns 1 for equal, 0 otherwise.
	private static NumericMethod buildEqual(Utf8Constant name, Utf8Constant desc, ClassConstant objArrClass,
			ClassConstant ratArrClass, ClassConstant integerClass, MethodrefConstant eqv, MethodrefConstant equal,
			@org.jspecify.annotations.Nullable ClassConstant strArrClass) {
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
		// not both cons: delegate to _eqv(a, b)
		int notCons = c.size();
		for (int pos : notBothCons) {
			JvmRuntimeBuilder.patchBranch(c, pos, notCons);
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
	// count is narrowed to an int up front, exactly as the BigInteger path does.
	//
	// Locals: 0=a, 1=count, 2/3=long a, 4=int count, 6/7=long result. The three are
	// pre-initialized so every path reaching the slow tail carries the same frame.
	private static NumericMethod buildAsh(Utf8Constant name, Utf8Constant desc, ClassConstant longClass,
			MethodrefConstant longValue, MethodrefConstant longValueOf, MethodrefConstant rBig, MethodrefConstant rNorm,
			MethodrefConstant biShiftLeft) {
		List<Integer> c = new ArrayList<>();
		c.add(Opcode.LCONST_0);
		c.add(Opcode.LSTORE_2);
		c.add(Opcode.ICONST_0);
		c.add(Opcode.ISTORE);
		c.add(4);
		c.add(Opcode.LCONST_0);
		c.add(Opcode.LSTORE);
		c.add(6);
		int[] slowJumps = emitLongLongGuard(c, longClass);
		emitUnboxLong(c, Opcode.ALOAD_0, longClass, longValue);
		c.add(Opcode.LSTORE_2);
		emitUnboxLong(c, Opcode.ALOAD_1, longClass, longValue);
		c.add(Opcode.L2I);
		c.add(Opcode.ISTORE);
		c.add(4);
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
		int slow = c.size();
		JvmRuntimeBuilder.patchBranch(c, slowJumps[0], slow);
		JvmRuntimeBuilder.patchBranch(c, slowJumps[1], slow);
		JvmRuntimeBuilder.patchBranch(c, ifWide, slow);
		JvmRuntimeBuilder.patchBranch(c, ifOverflow, slow);
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, rBig.index());
		emitUnboxLong(c, Opcode.ALOAD_1, longClass, longValue);
		c.add(Opcode.L2I);
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, biShiftLeft.index());
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, rNorm.index());
		c.add(Opcode.ARETURN);
		return new NumericMethod(name, desc, c, 6, 8, List.of());
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
		c.add(doubleOpcode);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, doubleValueOf.index());
		c.add(Opcode.ARETURN);
		JvmRuntimeBuilder.patchBranch(c, ifBNotDouble, c.size());
	}

	// Like emitDoubleBinaryPrologue, but for _cmp: the Double path leaves an int (-1/0/1)
	// via dcmpl and returns it directly, matching the integer/ratio path's int result.
	private static void emitDoubleCmpPrologue(List<Integer> c, ClassConstant doubleClass, MethodrefConstant rDbl,
			ClassConstant numberClass, MethodrefConstant numDoubleValue) {
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
		c.add(Opcode.DCMPL);
		c.add(Opcode.IRETURN);
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
