package am.ik.rontolisp.codegen.jvm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import am.ik.jvm.ConstantPool;
import am.ik.jvm.ConstantPool.ClassConstant;
import am.ik.jvm.ConstantPool.LongConstant;
import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.ConstantPool.Utf8Constant;
import am.ik.jvm.Opcode;

/**
 * Builds JVM bytecode for the numeric runtime helpers that give compiled programs
 * automatic {@code long}-to-{@link java.math.BigInteger} promotion. Integers are
 * represented at runtime as either {@code Long} or {@code BigInteger}; every integer
 * operation goes through one of these {@code private static} helpers, which perform the
 * {@code long} fast path with overflow detection and fall back to {@code BigInteger}
 * arithmetic on overflow. Results that fit back in a {@code long} are demoted to
 * {@code Long} by {@code _norm}, so a {@code BigInteger} value always holds a magnitude
 * outside the {@code long} range.
 *
 * <p>
 * The {@code _add}, {@code _sub}, {@code _mul} and {@code _neg} helpers use
 * {@code Math.*Exact} guarded by a {@code try/catch} on {@code ArithmeticException}; the
 * generated methods therefore carry an exception table. The remaining helpers detect the
 * single overflowing {@code long} division case ({@code Long.MIN_VALUE / -1}) explicitly.
 */
final class JvmNumericRuntimeBuilder {

	/** Operation keys used to look up the invokable helper method references. */
	static final String ADD = "_add";

	static final String SUB = "_sub";

	static final String MUL = "_mul";

	static final String NEG = "_neg";

	static final String DIV = "_div";

	static final String MOD = "_mod";

	static final String CMP = "_cmp";

	static final String ABS = "_abs";

	static final String MIN = "_min";

	static final String MAX = "_max";

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

		LongConstant cMin = cp.addLong(Long.MIN_VALUE);
		LongConstant cNeg1 = cp.addLong(-1L);

		// Self method references (name+descriptor against the generated class).
		Utf8Constant nBig = cp.addUtf8("_big");
		Utf8Constant dBig = cp.addUtf8("(" + OBJ + ")" + BIG);
		MethodrefConstant rBig = cp.addMethodref(thisClass, cp.addNameAndType(nBig, dBig));
		Utf8Constant nNorm = cp.addUtf8("_norm");
		Utf8Constant dNorm = cp.addUtf8("(" + BIG + ")" + OBJ);
		MethodrefConstant rNorm = cp.addMethodref(thisClass, cp.addNameAndType(nNorm, dNorm));

		Utf8Constant nAdd = cp.addUtf8(ADD);
		Utf8Constant nSub = cp.addUtf8(SUB);
		Utf8Constant nMul = cp.addUtf8(MUL);
		Utf8Constant nNeg = cp.addUtf8(NEG);
		Utf8Constant nDiv = cp.addUtf8(DIV);
		Utf8Constant nMod = cp.addUtf8(MOD);
		Utf8Constant nCmp = cp.addUtf8(CMP);
		Utf8Constant nAbs = cp.addUtf8(ABS);
		Utf8Constant nMin = cp.addUtf8(MIN);
		Utf8Constant nMax = cp.addUtf8(MAX);
		Utf8Constant dBinary = cp.addUtf8(BINARY_DESC);
		Utf8Constant dUnary = cp.addUtf8(UNARY_DESC);
		Utf8Constant dCmp = cp.addUtf8("(" + OBJ + OBJ + ")I");

		MethodrefConstant rAdd = cp.addMethodref(thisClass, cp.addNameAndType(nAdd, dBinary));
		MethodrefConstant rSub = cp.addMethodref(thisClass, cp.addNameAndType(nSub, dBinary));
		MethodrefConstant rMul = cp.addMethodref(thisClass, cp.addNameAndType(nMul, dBinary));
		MethodrefConstant rNeg = cp.addMethodref(thisClass, cp.addNameAndType(nNeg, dUnary));
		MethodrefConstant rDiv = cp.addMethodref(thisClass, cp.addNameAndType(nDiv, dBinary));
		MethodrefConstant rMod = cp.addMethodref(thisClass, cp.addNameAndType(nMod, dBinary));
		MethodrefConstant rCmp = cp.addMethodref(thisClass, cp.addNameAndType(nCmp, dCmp));
		MethodrefConstant rAbs = cp.addMethodref(thisClass, cp.addNameAndType(nAbs, dUnary));
		MethodrefConstant rMin = cp.addMethodref(thisClass, cp.addNameAndType(nMin, dBinary));
		MethodrefConstant rMax = cp.addMethodref(thisClass, cp.addNameAndType(nMax, dBinary));

		List<NumericMethod> methods = new ArrayList<>();
		methods.add(buildBig(nBig, dBig, longClass, bigClass, longValue, biValueOf));
		methods.add(buildNorm(nNorm, dNorm, longValueOf, biBitLength, biLongValue));
		methods.add(buildExactBinary(nAdd, dBinary, longClass, addExact, longValue, longValueOf, rBig, rNorm, biAdd,
				arithEx));
		methods.add(buildExactBinary(nSub, dBinary, longClass, subExact, longValue, longValueOf, rBig, rNorm, biSub,
				arithEx));
		methods.add(buildExactBinary(nMul, dBinary, longClass, mulExact, longValue, longValueOf, rBig, rNorm, biMul,
				arithEx));
		methods.add(buildNeg(nNeg, dUnary, longClass, negExact, longValue, longValueOf, rBig, rNorm, biNeg, arithEx));
		methods.add(buildDiv(nDiv, dBinary, longClass, longValue, longValueOf, rBig, rNorm, biDiv, cMin, cNeg1));
		methods.add(buildMod(nMod, dBinary, longClass, longValue, longValueOf, rBig, rNorm, biRem));
		methods.add(buildCmp(nCmp, dCmp, longClass, longValue, rBig, biCompareTo));
		methods.add(buildAbs(nAbs, dUnary, longClass, bigClass, longValue, longValueOf, absLong, biValueOf, biNeg,
				biAbs, rNorm, cMin));
		methods.add(buildSelect(nMin, dBinary, rCmp, Opcode.IFGT));
		methods.add(buildSelect(nMax, dBinary, rCmp, Opcode.IFLT));

		Map<String, MethodrefConstant> ops = new LinkedHashMap<>();
		ops.put(ADD, rAdd);
		ops.put(SUB, rSub);
		ops.put(MUL, rMul);
		ops.put(NEG, rNeg);
		ops.put(DIV, rDiv);
		ops.put(MOD, rMod);
		ops.put(CMP, rCmp);
		ops.put(ABS, rAbs);
		ops.put(MIN, rMin);
		ops.put(MAX, rMax);
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

	// _add/_sub/_mul(Object a, Object b): long fast path via Math.*Exact, promoting to
	// BigInteger on overflow (caught) or when an operand is already a BigInteger.
	private static NumericMethod buildExactBinary(Utf8Constant name, Utf8Constant desc, ClassConstant longClass,
			MethodrefConstant exact, MethodrefConstant longValue, MethodrefConstant longValueOf, MethodrefConstant rBig,
			MethodrefConstant rNorm, MethodrefConstant biOp, ClassConstant arithEx) {
		List<Integer> c = new ArrayList<>();
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
		return new NumericMethod(name, desc, c, 4, 2,
				List.of(new int[] { tryStart, handler, handler, arithEx.index() }));
	}

	// _neg(Object a): negate via Math.negateExact, promoting to BigInteger on overflow.
	private static NumericMethod buildNeg(Utf8Constant name, Utf8Constant desc, ClassConstant longClass,
			MethodrefConstant negExact, MethodrefConstant longValue, MethodrefConstant longValueOf,
			MethodrefConstant rBig, MethodrefConstant rNorm, MethodrefConstant biNeg, ClassConstant arithEx) {
		List<Integer> c = new ArrayList<>();
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
		return new NumericMethod(name, desc, c, 2, 1,
				List.of(new int[] { tryStart, handler, handler, arithEx.index() }));
	}

	// _div(Object a, Object b): truncating long division; only Long.MIN_VALUE / -1 needs
	// BigInteger promotion. Division by zero propagates as ArithmeticException.
	private static NumericMethod buildDiv(Utf8Constant name, Utf8Constant desc, ClassConstant longClass,
			MethodrefConstant longValue, MethodrefConstant longValueOf, MethodrefConstant rBig, MethodrefConstant rNorm,
			MethodrefConstant biDiv, LongConstant cMin, LongConstant cNeg1) {
		List<Integer> c = new ArrayList<>();
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
		emitUnboxLong(c, Opcode.ALOAD_0, longClass, longValue);
		c.add(Opcode.LSTORE);
		c.add(2);
		emitUnboxLong(c, Opcode.ALOAD_1, longClass, longValue);
		c.add(Opcode.LSTORE);
		c.add(4);
		emitLload(c, 2);
		emitLdc2(c, cMin);
		c.add(Opcode.LCMP);
		int ifNeMin = c.size();
		c.add(Opcode.IFNE);
		JvmRuntimeBuilder.emitU2(c, 0);
		emitLload(c, 4);
		emitLdc2(c, cNeg1);
		c.add(Opcode.LCMP);
		int ifNeNeg1 = c.size();
		c.add(Opcode.IFNE);
		JvmRuntimeBuilder.emitU2(c, 0);
		int gotoSlow = c.size();
		c.add(Opcode.GOTO);
		JvmRuntimeBuilder.emitU2(c, 0);
		int doDiv = c.size();
		JvmRuntimeBuilder.patchBranch(c, ifNeMin, doDiv);
		JvmRuntimeBuilder.patchBranch(c, ifNeNeg1, doDiv);
		emitLload(c, 2);
		emitLload(c, 4);
		c.add(Opcode.LDIV);
		c.add(Opcode.INVOKESTATIC);
		JvmRuntimeBuilder.emitU2(c, longValueOf.index());
		c.add(Opcode.ARETURN);
		int slow = c.size();
		JvmRuntimeBuilder.patchBranch(c, ifSlow1, slow);
		JvmRuntimeBuilder.patchBranch(c, ifSlow2, slow);
		JvmRuntimeBuilder.patchBranch(c, gotoSlow, slow);
		emitBigBinary(c, rBig, biDiv, rNorm);
		return new NumericMethod(name, desc, c, 4, 6, List.of());
	}

	// _mod(Object a, Object b): long remainder, BigInteger.remainder otherwise.
	private static NumericMethod buildMod(Utf8Constant name, Utf8Constant desc, ClassConstant longClass,
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

	// _cmp(Object a, Object b): long comparison or BigInteger.compareTo, returning
	// -1/0/1.
	private static NumericMethod buildCmp(Utf8Constant name, Utf8Constant desc, ClassConstant longClass,
			MethodrefConstant longValue, MethodrefConstant rBig, MethodrefConstant biCompareTo) {
		List<Integer> c = new ArrayList<>();
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
		return new NumericMethod(name, desc, c, 4, 2, List.of());
	}

	// _abs(Object a): Math.abs for Long (promoting Long.MIN_VALUE), BigInteger.abs else.
	private static NumericMethod buildAbs(Utf8Constant name, Utf8Constant desc, ClassConstant longClass,
			ClassConstant bigClass, MethodrefConstant longValue, MethodrefConstant longValueOf,
			MethodrefConstant absLong, MethodrefConstant biValueOf, MethodrefConstant biNeg, MethodrefConstant biAbs,
			MethodrefConstant rNorm, LongConstant cMin) {
		List<Integer> c = new ArrayList<>();
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
		return new NumericMethod(name, desc, c, 4, 4, List.of());
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

	private static void emitLload(List<Integer> c, int slot) {
		c.add(Opcode.LLOAD);
		c.add(slot);
	}

	private static void emitLdc2(List<Integer> c, LongConstant constant) {
		c.add(Opcode.LDC2_W);
		JvmRuntimeBuilder.emitU2(c, constant.index());
	}

}
