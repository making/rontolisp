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

	static final String ABS = "_abs";

	static final String SIGNUM = "_signum";

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

	/** Truncates a rational toward zero. */
	static final String RAT_TRUNC = "_rtrunc";

	/** Floor of a rational (toward negative infinity). */
	static final String RAT_FLOOR = "_rfloor";

	/** Ceiling of a rational (toward positive infinity). */
	static final String RAT_CEIL = "_rceil";

	/** Rounds a rational to the nearest integer, ties to even. */
	static final String RAT_ROUND = "_rround";

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
	 * @return the helper methods and the invokable references compiled code calls
	 */
	static NumericRuntime build(ConstantPool cp, ClassConstant thisClass) {
		ClassConstant longClass = cp.addClass(cp.addUtf8("java/lang/Long"));
		ClassConstant bigClass = cp.addClass(cp.addUtf8("java/math/BigInteger"));
		ClassConstant arithEx = cp.addClass(cp.addUtf8("java/lang/ArithmeticException"));
		ClassConstant mathClass = cp.addClass(cp.addUtf8("java/lang/Math"));
		ClassConstant objectClass = cp.addClass(cp.addUtf8("java/lang/Object"));
		ClassConstant numberClass = cp.addClass(cp.addUtf8("java/lang/Number"));
		ClassConstant doubleClass = cp.addClass(cp.addUtf8("java/lang/Double"));
		ClassConstant ratArrClass = cp.addClass(cp.addUtf8("[Ljava/math/BigInteger;"));
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
		MethodrefConstant biShiftLeft = cp.addMethodref(bigClass,
				cp.addNameAndType(cp.addUtf8("shiftLeft"), cp.addUtf8("(I)" + BIG)));
		MethodrefConstant biTestBit = cp.addMethodref(bigClass,
				cp.addNameAndType(cp.addUtf8("testBit"), cp.addUtf8("(I)Z")));
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
		Utf8Constant nAbs = cp.addUtf8(ABS);
		Utf8Constant nSignum = cp.addUtf8(SIGNUM);
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
		Utf8Constant dBinary = cp.addUtf8(BINARY_DESC);
		Utf8Constant dUnary = cp.addUtf8(UNARY_DESC);
		Utf8Constant dCmp = cp.addUtf8("(" + OBJ + OBJ + ")I");
		Utf8Constant dFmod = cp.addUtf8("(DD)D");
		Utf8Constant dPow = cp.addUtf8("(" + OBJ + "I)" + OBJ);

		MethodrefConstant rAdd = cp.addMethodref(thisClass, cp.addNameAndType(nAdd, dBinary));
		MethodrefConstant rSub = cp.addMethodref(thisClass, cp.addNameAndType(nSub, dBinary));
		MethodrefConstant rMul = cp.addMethodref(thisClass, cp.addNameAndType(nMul, dBinary));
		MethodrefConstant rNeg = cp.addMethodref(thisClass, cp.addNameAndType(nNeg, dUnary));
		MethodrefConstant rDiv = cp.addMethodref(thisClass, cp.addNameAndType(nDiv, dBinary));
		MethodrefConstant rMod = cp.addMethodref(thisClass, cp.addNameAndType(nMod, dBinary));
		MethodrefConstant rRem = cp.addMethodref(thisClass, cp.addNameAndType(nRem, dBinary));
		MethodrefConstant rFmod = cp.addMethodref(thisClass, cp.addNameAndType(nFmod, dFmod));
		MethodrefConstant rCmp = cp.addMethodref(thisClass, cp.addNameAndType(nCmp, dCmp));
		MethodrefConstant rAbs = cp.addMethodref(thisClass, cp.addNameAndType(nAbs, dUnary));
		MethodrefConstant rSignum = cp.addMethodref(thisClass, cp.addNameAndType(nSignum, dUnary));
		MethodrefConstant rMin = cp.addMethodref(thisClass, cp.addNameAndType(nMin, dBinary));
		MethodrefConstant rMax = cp.addMethodref(thisClass, cp.addNameAndType(nMax, dBinary));
		MethodrefConstant rDbl = cp.addMethodref(thisClass, cp.addNameAndType(nDbl, dUnary));
		MethodrefConstant rPow = cp.addMethodref(thisClass, cp.addNameAndType(nPow, dPow));
		MethodrefConstant rEqv = cp.addMethodref(thisClass, cp.addNameAndType(nEqv, dCmp));
		MethodrefConstant rEqStrict = cp.addMethodref(thisClass, cp.addNameAndType(nEqStrict, dCmp));
		MethodrefConstant rEqual = cp.addMethodref(thisClass, cp.addNameAndType(nEqual, dCmp));
		MethodrefConstant rRatTrunc = cp.addMethodref(thisClass, cp.addNameAndType(nRatTrunc, dUnary));
		MethodrefConstant rRatFloor = cp.addMethodref(thisClass, cp.addNameAndType(nRatFloor, dUnary));
		MethodrefConstant rRatCeil = cp.addMethodref(thisClass, cp.addNameAndType(nRatCeil, dUnary));
		MethodrefConstant rRatRound = cp.addMethodref(thisClass, cp.addNameAndType(nRatRound, dUnary));

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
		methods.add(buildAbs(nAbs, dUnary, longClass, bigClass, longValue, longValueOf, absLong, biValueOf, biNeg,
				biAbs, rNorm, cMin, ratArrClass, rRatNum, rRatDen, rRat, doubleClass, rDbl, numberClass, numDoubleValue,
				doubleValueOf, absDouble));
		methods.add(buildSignum(nSignum, dUnary, doubleClass, rDbl, numberClass, numDoubleValue, doubleValueOf,
				signumDouble, rRatNum, biSignum, longValueOf));
		methods.add(buildSelect(nMin, dBinary, rCmp, Opcode.IFGT));
		methods.add(buildSelect(nMax, dBinary, rCmp, Opcode.IFLT));
		methods.add(buildDbl(nDbl, dUnary, ratArrClass, numberClass, bigDecClass, bdInit, bdDivide, bdDoubleValue,
				mcDecimal64, doubleValueOf, numDoubleValue, rRatNum, rRatDen));
		methods.add(buildPow(nPow, dPow, rRatNum, rRatDen, rRat, biPow));
		methods.add(buildEqv(nEqv, dCmp, ratArrClass, objEquals));
		methods.add(buildEqStrict(nEqStrict, dCmp, doubleClass, ratArrClass, rEqv));
		methods.add(buildEqual(nEqual, dCmp, objArrClass, ratArrClass, integerClass, rEqv, rEqual));
		methods.add(buildRatTrunc(nRatTrunc, dUnary, rRatNum, rRatDen, rNorm, biDiv));
		methods.add(buildRatFloor(nRatFloor, dUnary, rRatNum, rRatDen, rNorm, biMod, biSub, biDiv, null, null));
		methods.add(buildRatFloor(nRatCeil, dUnary, rRatNum, rRatDen, rNorm, biMod, biSub, biDiv, biOne, biAdd));
		methods.add(buildRatRound(nRatRound, dUnary, rRatNum, rRatDen, rNorm, biMod, biSub, biDiv, biMul, biShiftLeft,
				biCompareTo, biTestBit, biOne, biAdd));

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
		ops.put(ABS, rAbs);
		ops.put(SIGNUM, rSignum);
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

	// _pow(Object base, int e): exact rational power. (a/b)^e = a^e/b^e for e >= 0 and
	// b^-e/a^-e for e < 0 (so an integer base with a negative exponent yields a ratio).
	private static NumericMethod buildPow(Utf8Constant name, Utf8Constant desc, MethodrefConstant rRatNum,
			MethodrefConstant rRatDen, MethodrefConstant rRat, MethodrefConstant biPow) {
		List<Integer> c = new ArrayList<>();
		c.add(Opcode.ILOAD_1);
		int ifNeg = c.size();
		c.add(Opcode.IFLT);
		JvmRuntimeBuilder.emitU2(c, 0);
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, rRatNum.index());
		c.add(Opcode.ILOAD_1);
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, biPow.index());
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, rRatDen.index());
		c.add(Opcode.ILOAD_1);
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, biPow.index());
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, rRat.index());
		c.add(Opcode.ARETURN);
		JvmRuntimeBuilder.patchBranch(c, ifNeg, c.size());
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, rRatDen.index());
		c.add(Opcode.ILOAD_1);
		c.add(Opcode.INEG);
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, biPow.index());
		c.add(Opcode.ALOAD_0);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, rRatNum.index());
		c.add(Opcode.ILOAD_1);
		c.add(Opcode.INEG);
		c.add(Opcode.INVOKEVIRTUAL);
		JvmRuntimeBuilder.emitU2(c, biPow.index());
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, rRat.index());
		c.add(Opcode.ARETURN);
		return new NumericMethod(name, desc, c, 3, 2, List.of());
	}

	// _eqv(Object a, Object b): value equality used by eq; ratios compare element-wise
	// (Object[].equals is reference equality), everything else uses a.equals(b).
	private static NumericMethod buildEqv(Utf8Constant name, Utf8Constant desc, ClassConstant ratArrClass,
			MethodrefConstant objEquals) {
		List<Integer> c = new ArrayList<>();
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
		c.add(Opcode.ALOAD_1);
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
			ClassConstant ratArrClass, ClassConstant integerClass, MethodrefConstant eqv, MethodrefConstant equal) {
		List<Integer> c = new ArrayList<>();
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
		return new NumericMethod(name, desc, c, 3, 2, List.of());
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
