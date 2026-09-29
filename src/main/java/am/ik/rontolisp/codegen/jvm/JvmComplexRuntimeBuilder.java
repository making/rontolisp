package am.ik.rontolisp.codegen.jvm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import am.ik.jvm.ConstantPool;
import am.ik.jvm.ConstantPool.ClassConstant;
import am.ik.jvm.ConstantPool.FieldrefConstant;
import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.ConstantPool.Utf8Constant;
import am.ik.jvm.MethodCode;

/**
 * Builds the gated complex-number runtime helpers of the JVM backend: the {@code _c*}
 * methods a compiled program calls when it can observe a complex value. Every helper
 * constructs (or threads) the travelling {@code am.ik.rontolisp.runtime.RontoComplex}
 * holder, so the whole group -- methods and holder class file alike -- is emitted only
 * when the program may create a complex, and a program without one compiles
 * byte-identically to a build that never knew about them (`.kb/jvm-complex.md`).
 *
 * <p>
 * The part-wise real arithmetic reuses the unconditional numeric helpers ({@code _add},
 * {@code _sub}, {@code _mul}, {@code _div}, {@code _neg}, {@code _dbl}, {@code _cmp}) by
 * name, so the coercion funnels and their error texts stay identical to real arithmetic.
 * Exactness follows the interpreter ({@code Environment}): all-rational parts compute
 * exactly, a float anywhere coerces the whole step to doubles, and every result
 * canonicalizes through {@code _ccomplex} (a rational zero imaginary part demotes to the
 * real, a float zero stays complex).
 */
final class JvmComplexRuntimeBuilder {

	/** The canonical constructor: {@code _ccomplex(real, imag)}. */
	static final String COMPLEX = "_ccomplex";

	/** Complex addition over real-or-complex operands. */
	static final String ADD = "_cadd";

	/** Complex subtraction over real-or-complex operands. */
	static final String SUB = "_csub";

	/** Complex multiplication over real-or-complex operands. */
	static final String MUL = "_cmul";

	/** Complex division over real-or-complex operands. */
	static final String DIV = "_cdiv";

	/**
	 * Complex negation. A separate helper (rather than {@code _csub} from zero) because
	 * {@code 0 - x} and {@code -x} differ on signed zeros: {@code 0.0 -
	 * 0.0} is {@code +0.0} while {@code -0.0} stays {@code -0.0}.
	 */
	static final String NEG = "_cneg";

	/** The principal square root, rooting negatives into the plane. */
	static final String SQRT = "_csqrt";

	/** Complex-capable power. */
	static final String POW = "_cpow";

	/**
	 * {@code expt} over two REAL operands whose answer can still be complex: a negative
	 * base to a non-integer power. Everything else delegates to the unconditional
	 * {@code _pow}, so the exact rational path and its error funnels are unduplicated.
	 */
	static final String POW_REAL = "_cpowr";

	/**
	 * The unary math functions over real-or-complex operands, selected by an {@code int}
	 * opcode ({@link #U1_EXP} and friends).
	 */
	static final String U1 = "_cu1";

	/** Complex conjugation. */
	static final String CONJUGATE = "_cconjugate";

	/**
	 * Like {@code _cmpb} but a complex operand signals a REAL operand-type report: the
	 * ordering operators' comparison once a complex literal steered them off the double
	 * path.
	 */
	static final String CCPMB = "_ccmpb";

	/** {@code phase} over real-or-complex operands (an angle, always a double). */
	static final String CPHASE = "_cphase";

	/**
	 * {@code signum} over a complex holder: the unit vector z/|z| in floats (a zero
	 * answers the canonicalization of its own parts, like the interpreter). Called from
	 * the unconditional {@code _signum}'s gated holder arm; never called with a real
	 * (those take {@code _signum}'s own arms).
	 */
	static final String SIGNUM = "_csignum";

	/** {@link #U1} selector for {@code exp}. */
	static final int U1_EXP = 0;

	/** {@link #U1} selector for {@code log}. */
	static final int U1_LOG = 1;

	/** {@link #U1} selector for {@code sin}. */
	static final int U1_SIN = 2;

	/** {@link #U1} selector for {@code cos}. */
	static final int U1_COS = 3;

	/** {@link #U1} selector for {@code tan}. */
	static final int U1_TAN = 4;

	/** {@link #U1} selector for {@code asin}. */
	static final int U1_ASIN = 5;

	/** {@link #U1} selector for {@code acos}. */
	static final int U1_ACOS = 6;

	/** {@link #U1} selector for {@code atan}. */
	static final int U1_ATAN = 7;

	/** {@link #U1} selector for {@code sinh}. */
	static final int U1_SINH = 8;

	/** {@link #U1} selector for {@code cosh}. */
	static final int U1_COSH = 9;

	/** {@link #U1} selector for {@code tanh}. */
	static final int U1_TANH = 10;

	/** {@link #U1} selector for {@code asinh}. */
	static final int U1_ASINH = 11;

	/**
	 * {@link #U1} selector for {@code acosh} (real arms below 1 cross into the plane).
	 */
	static final int U1_ACOSH = 12;

	/**
	 * {@link #U1} selector for {@code atanh} (real arms beyond +-1 cross into the plane).
	 */
	static final int U1_ATANH = 13;

	/** {@link #U1} selector for {@code cis} (answers a complex for every operand). */
	static final int U1_CIS = 14;

	/**
	 * The helper names this group owns, for {@code JvmLispCompiler.gateGroupFor}: a
	 * finished class calling one of these without it having been emitted re-runs with the
	 * complex group forced on.
	 */
	static final Set<String> METHOD_NAMES = Set.of(COMPLEX, ADD, SUB, MUL, DIV, NEG, SQRT, POW, POW_REAL, U1, CONJUGATE,
			CCPMB, CPHASE, SIGNUM);

	/**
	 * The class files that travel beside a compiled program using this group
	 * (`.kb/jvm-export.md`, "What travels").
	 */
	static final List<String> RUNTIME_CLASS_FILES = List.of("am/ik/rontolisp/runtime/RontoComplex.class");

	private static final String OBJ = "Ljava/lang/Object;";

	private static final String BINARY_DESC = "(" + OBJ + OBJ + ")" + OBJ;

	private static final String UNARY_DESC = "(" + OBJ + ")" + OBJ;

	private static final String U1_DESC = "(Ljava/lang/Object;I)Ljava/lang/Object;";

	private static final String CMP_DESC = "(Ljava/lang/Object;Ljava/lang/Object;)I";

	/**
	 * The method descriptor for a helper name: the one source of truth shared by the
	 * group emission and the call-site references (a mismatch fails the post-compile
	 * self-check loudly instead of linking).
	 */
	static String descFor(String op) {
		if (U1.equals(op)) {
			return U1_DESC;
		}
		if (CCPMB.equals(op)) {
			return CMP_DESC;
		}
		if (SQRT.equals(op) || CONJUGATE.equals(op) || NEG.equals(op) || CPHASE.equals(op) || SIGNUM.equals(op)) {
			return UNARY_DESC;
		}
		return BINARY_DESC;
	}

	private JvmComplexRuntimeBuilder() {
	}

	/**
	 * A generated complex helper method.
	 *
	 * @param nameUtf8 the method name constant
	 * @param descUtf8 the method descriptor constant
	 * @param code the body
	 */
	record ComplexMethod(Utf8Constant nameUtf8, Utf8Constant descUtf8, MethodCode code) {
	}

	/**
	 * The generated complex runtime: the helper methods to emit and the references that
	 * compiled code invokes.
	 *
	 * @param methods the helper methods to emit into the class
	 * @param ops the invokable helper references, keyed by operation
	 */
	record ComplexRuntime(List<ComplexMethod> methods, Map<String, MethodrefConstant> ops) {
	}

	/**
	 * Reads {@link #RUNTIME_CLASS_FILES} off the compiler's own classpath.
	 * @return each class file's path within an output tree (or jar), mapped to its bytes
	 */
	static Map<String, byte[]> runtimeClassFiles() {
		return JvmRuntimeClassFiles.read(RUNTIME_CLASS_FILES);
	}

	/** Shared constant-pool references for the complex helpers. */
	private record Refs(ClassConstant thisClass, ClassConstant rcClass, FieldrefConstant rcReal,
			FieldrefConstant rcImag, MethodrefConstant rcInit, ClassConstant longClass, ClassConstant doubleClass,
			ClassConstant bigClass, ClassConstant ratArrClass, ClassConstant numberClass, ClassConstant mathClass,
			ClassConstant rteClass, MethodrefConstant longValueOf, MethodrefConstant longValue,
			MethodrefConstant doubleValueOf, MethodrefConstant numDoubleValue, MethodrefConstant rteInit,
			JvmOperandTypeRuntime.ThrowRefs throwRefs, MethodrefConstant rAdd, MethodrefConstant rSub,
			MethodrefConstant rMul, MethodrefConstant rDiv, MethodrefConstant rNeg, MethodrefConstant rDbl,
			MethodrefConstant rCmp, MethodrefConstant rCComplex, MethodrefConstant rCMul, MethodrefConstant rCDiv,
			MethodrefConstant rPow) {
	}

	/**
	 * Builds all complex helper methods and registers their constant-pool entries.
	 * @param cp the constant pool to populate
	 * @param thisClass the generated class
	 * @return the helper methods and the invokable references compiled code calls
	 */
	static ComplexRuntime build(ConstantPool cp, ClassConstant thisClass) {
		ClassConstant rcClass = cp.addClass(cp.addUtf8("am/ik/rontolisp/runtime/RontoComplex"));
		ClassConstant longClass = cp.addClass(cp.addUtf8("java/lang/Long"));
		ClassConstant doubleClass = cp.addClass(cp.addUtf8("java/lang/Double"));
		ClassConstant bigClass = cp.addClass(cp.addUtf8("java/math/BigInteger"));
		ClassConstant ratArrClass = cp.addClass(cp.addUtf8("[Ljava/math/BigInteger;"));
		ClassConstant numberClass = cp.addClass(cp.addUtf8("java/lang/Number"));
		ClassConstant mathClass = cp.addClass(cp.addUtf8("java/lang/Math"));
		ClassConstant stringClass = cp.addClass(cp.addUtf8("java/lang/String"));
		ClassConstant rteClass = cp.addClass(cp.addUtf8("java/lang/RuntimeException"));
		Refs refs = new Refs(thisClass, rcClass,
				cp.addFieldref(rcClass, cp.addNameAndType(cp.addUtf8("real"), cp.addUtf8(OBJ))),
				cp.addFieldref(rcClass, cp.addNameAndType(cp.addUtf8("imag"), cp.addUtf8(OBJ))),
				cp.addMethodref(rcClass, cp.addNameAndType(cp.addUtf8("<init>"), cp.addUtf8("(" + OBJ + OBJ + ")V"))),
				longClass, doubleClass, bigClass, ratArrClass, numberClass, mathClass, rteClass,
				cp.addMethodref(longClass, cp.addNameAndType(cp.addUtf8("valueOf"), cp.addUtf8("(J)Ljava/lang/Long;"))),
				cp.addMethodref(longClass, cp.addNameAndType(cp.addUtf8("longValue"), cp.addUtf8("()J"))),
				cp.addMethodref(doubleClass,
						cp.addNameAndType(cp.addUtf8("valueOf"), cp.addUtf8("(D)Ljava/lang/Double;"))),
				cp.addMethodref(numberClass, cp.addNameAndType(cp.addUtf8("doubleValue"), cp.addUtf8("()D"))),
				cp.addMethodref(rteClass, cp.addNameAndType(cp.addUtf8("<init>"), cp.addUtf8("(Ljava/lang/String;)V"))),
				JvmOperandTypeRuntime.ThrowRefs.of(cp, thisClass),
				self(cp, thisClass, JvmNumericRuntimeBuilder.ADD, BINARY_DESC),
				self(cp, thisClass, JvmNumericRuntimeBuilder.SUB, BINARY_DESC),
				self(cp, thisClass, JvmNumericRuntimeBuilder.MUL, BINARY_DESC),
				self(cp, thisClass, JvmNumericRuntimeBuilder.DIV, BINARY_DESC),
				self(cp, thisClass, JvmNumericRuntimeBuilder.NEG, UNARY_DESC),
				self(cp, thisClass, JvmNumericRuntimeBuilder.DBL, UNARY_DESC),
				self(cp, thisClass, JvmNumericRuntimeBuilder.CMP, CMP_DESC), self(cp, thisClass, COMPLEX, BINARY_DESC),
				self(cp, thisClass, MUL, BINARY_DESC), self(cp, thisClass, DIV, BINARY_DESC),
				self(cp, thisClass, JvmNumericRuntimeBuilder.POW, BINARY_DESC));
		List<ComplexMethod> methods = new ArrayList<>();
		Map<String, MethodrefConstant> ops = new LinkedHashMap<>();
		addMethod(cp, thisClass, methods, ops, COMPLEX, BINARY_DESC,
				buildComplex(refs, cp.addUtf8(COMPLEX), cp.addUtf8(BINARY_DESC)));
		addMethod(cp, thisClass, methods, ops, ADD, BINARY_DESC,
				buildAdd(refs, cp.addUtf8(ADD), cp.addUtf8(BINARY_DESC)));
		addMethod(cp, thisClass, methods, ops, SUB, BINARY_DESC,
				buildSub(refs, cp.addUtf8(SUB), cp.addUtf8(BINARY_DESC)));
		addMethod(cp, thisClass, methods, ops, MUL, BINARY_DESC,
				buildMul(refs, cp.addUtf8(MUL), cp.addUtf8(BINARY_DESC)));
		addMethod(cp, thisClass, methods, ops, DIV, BINARY_DESC,
				buildDiv(refs, cp, cp.addUtf8(DIV), cp.addUtf8(BINARY_DESC)));
		addMethod(cp, thisClass, methods, ops, NEG, UNARY_DESC,
				buildNeg(refs, cp.addUtf8(NEG), cp.addUtf8(UNARY_DESC)));
		addMethod(cp, thisClass, methods, ops, SQRT, UNARY_DESC,
				buildSqrt(refs, cp, cp.addUtf8(SQRT), cp.addUtf8(UNARY_DESC)));
		addMethod(cp, thisClass, methods, ops, POW, BINARY_DESC,
				buildPow(refs, cp, cp.addUtf8(POW), cp.addUtf8(BINARY_DESC)));
		addMethod(cp, thisClass, methods, ops, POW_REAL, BINARY_DESC,
				buildPowReal(refs, cp, cp.addUtf8(POW_REAL), cp.addUtf8(BINARY_DESC)));
		addMethod(cp, thisClass, methods, ops, U1, U1_DESC, buildU1(refs, cp, cp.addUtf8(U1), cp.addUtf8(U1_DESC)));
		addMethod(cp, thisClass, methods, ops, CONJUGATE, UNARY_DESC,
				buildConjugate(refs, cp.addUtf8(CONJUGATE), cp.addUtf8(UNARY_DESC)));
		addMethod(cp, thisClass, methods, ops, CCPMB, CMP_DESC,
				buildCCmpBits(refs, cp, cp.addUtf8(CCPMB), cp.addUtf8(CMP_DESC)));
		addMethod(cp, thisClass, methods, ops, CPHASE, UNARY_DESC,
				buildCPhase(refs, cp, cp.addUtf8(CPHASE), cp.addUtf8(UNARY_DESC)));
		addMethod(cp, thisClass, methods, ops, SIGNUM, UNARY_DESC,
				buildCSignum(refs, cp, cp.addUtf8(SIGNUM), cp.addUtf8(UNARY_DESC)));
		return new ComplexRuntime(methods, ops);
	}

	private static MethodrefConstant self(ConstantPool cp, ClassConstant thisClass, String name, String desc) {
		return cp.addMethodref(thisClass, cp.addNameAndType(cp.addUtf8(name), cp.addUtf8(desc)));
	}

	private static void addMethod(ConstantPool cp, ClassConstant thisClass, List<ComplexMethod> methods,
			Map<String, MethodrefConstant> ops, String name, String desc, ComplexMethod method) {
		methods.add(method);
		ops.put(name, cp.addMethodref(thisClass, cp.addNameAndType(cp.addUtf8(name), cp.addUtf8(desc))));
	}

	// ------------------------------------------------------------------
	// Emission helpers

	/**
	 * The transcendentals: {@code StrictMath} on every backend (.kb/transcendentals.md).
	 */
	private static final Set<String> STRICT_MATH = Set.of("exp", "log", "log1p", "sin", "cos", "tan", "asin", "acos",
			"atan", "atan2", "sinh", "cosh", "tanh", "pow", "hypot");

	private static void callMath(MethodCode c, Refs refs, ConstantPool cp, String name, String desc) {
		ClassConstant owner = STRICT_MATH.contains(name) ? cp.addClass(cp.addUtf8("java/lang/StrictMath"))
				: refs.mathClass();
		c.invokestatic(cp.addMethodref(owner, cp.addNameAndType(cp.addUtf8(name), cp.addUtf8(desc))).entry());
	}

	/**
	 * Unboxes the real value in {@code slot} to a raw double ({@code _dbl} plus
	 * {@code Number.doubleValue}, throwing the interpreter's NUMBER operand-type report
	 * text for a non-number).
	 */
	private static void emitToDouble(MethodCode c, Refs refs, int slot) {
		c.aload(slot);
		c.invokestatic(refs.rDbl().entry());
		c.checkcast(refs.numberClass().entry());
		c.invokevirtual(refs.numDoubleValue().methodRefEntry());
	}

	/** Boxes the raw double on top of the stack. */
	private static void emitBoxDouble(MethodCode c, Refs refs) {
		c.invokestatic(refs.doubleValueOf().entry());
	}

	/**
	 * Emits {@code throw _teRaw(value, "NUMBER")} for the value in {@code slot}.
	 */
	private static void emitNumberErrThrow(MethodCode c, Refs refs, int slot) {
		c.aload(slot);
		refs.throwRefs().emitThrowLoaded(c, refs.throwRefs().numberKind());
	}

	/**
	 * Emits the real-part test for the value in {@code slot}: falls through when it is a
	 * {@code Long}, {@code BigInteger}, {@code BigInteger[]} or {@code Double}, otherwise
	 * jumps to {@code onFailure}.
	 */
	private static void emitRequireReal(MethodCode c, Refs refs, int slot, MethodCode.Label onFailure) {
		c.aload(slot);
		c.instanceOf(refs.longClass().entry());
		c.ifne(onFailure);
		c.aload(slot);
		c.instanceOf(refs.bigClass().entry());
		c.ifne(onFailure);
		c.aload(slot);
		c.instanceOf(refs.ratArrClass().entry());
		c.ifne(onFailure);
		c.aload(slot);
		c.instanceOf(refs.doubleClass().entry());
		c.ifne(onFailure);
	}

	/**
	 * Extracts the complex parts of the value in {@code paramSlot} into
	 * {@code reSlot}/{@code imSlot}: a holder answers its fields, a {@code Double}
	 * answers itself and a {@code 0.0} imaginary part, any other value answers itself and
	 * an integer zero (the caller funnels non-reals through the real operators, exactly
	 * like {@code Environment.complexReal/complexImag}).
	 */
	private static void emitExtractParts(MethodCode c, Refs refs, int paramSlot, int reSlot, int imSlot) {
		c.aload(paramSlot);
		c.instanceOf(refs.rcClass().entry());
		MethodCode.Label notHolderRe = c.newLabel();
		c.ifeq(notHolderRe);
		c.aload(paramSlot);
		c.checkcast(refs.rcClass().entry());
		c.getfield(refs.rcReal().entry());
		c.astore(reSlot);
		MethodCode.Label doneRe = c.newLabel();
		c.goto_(doneRe);
		c.labelBinding(notHolderRe);
		c.aload(paramSlot);
		c.astore(reSlot);
		c.labelBinding(doneRe);
		c.aload(paramSlot);
		c.instanceOf(refs.rcClass().entry());
		MethodCode.Label notHolderIm = c.newLabel();
		c.ifeq(notHolderIm);
		c.aload(paramSlot);
		c.checkcast(refs.rcClass().entry());
		c.getfield(refs.rcImag().entry());
		c.astore(imSlot);
		MethodCode.Label doneIm = c.newLabel();
		c.goto_(doneIm);
		c.labelBinding(notHolderIm);
		c.aload(paramSlot);
		c.instanceOf(refs.doubleClass().entry());
		MethodCode.Label notDouble = c.newLabel();
		c.ifeq(notDouble);
		c.dconst_0();
		emitBoxDouble(c, refs);
		c.astore(imSlot);
		MethodCode.Label doneZero = c.newLabel();
		c.goto_(doneZero);
		c.labelBinding(notDouble);
		c.lconst_0();
		c.invokestatic(refs.longValueOf().entry());
		c.astore(imSlot);
		c.labelBinding(doneZero);
		c.labelBinding(doneIm);
	}

	/**
	 * Tests whether any of the four parts in the given slots is a {@code Double}, jumping
	 * to {@code toFloat}, the float path, when one is (execution falls through to the
	 * exact path).
	 */
	private static void emitFloatTest(MethodCode c, Refs refs, int[] slots, MethodCode.Label toFloat) {
		for (int slot : slots) {
			c.aload(slot);
			c.instanceOf(refs.doubleClass().entry());
			c.ifne(toFloat);
		}
	}

	/**
	 * Constructs a holder from the two parts in {@code reSlot}/{@code imSlot}.
	 */
	private static void emitNewHolderFromSlots(MethodCode c, Refs refs, int reSlot, int imSlot) {
		c.new_(refs.rcClass().entry());
		c.dup();
		c.aload(reSlot);
		c.aload(imSlot);
		c.invokespecial(refs.rcInit().entry());
	}

	// _ccomplex(Object real, Object imag): the canonical value. A non-real part
	// signals NUMBER operand-type report; a float anywhere coerces both parts to floats
	// (a
	// float zero never demotes); a rational zero imaginary part demotes to the
	// real itself; otherwise a fresh holder.
	private static ComplexMethod buildComplex(Refs refs, Utf8Constant name, Utf8Constant desc) {
		MethodCode c = new MethodCode();
		MethodCode.Label realOk = c.newLabel();
		emitRequireReal(c, refs, 0, realOk);
		emitNumberErrThrow(c, refs, 0);
		c.labelBinding(realOk);
		MethodCode.Label imagOk = c.newLabel();
		emitRequireReal(c, refs, 1, imagOk);
		emitNumberErrThrow(c, refs, 1);
		c.labelBinding(imagOk);
		c.aload(0);
		c.instanceOf(refs.doubleClass().entry());
		MethodCode.Label realIsDouble = c.newLabel();
		c.ifne(realIsDouble);
		c.aload(1);
		c.instanceOf(refs.doubleClass().entry());
		MethodCode.Label imagIsDouble = c.newLabel();
		c.ifne(imagIsDouble);
		// Exact path: a rational zero imaginary part demotes to the real.
		c.aload(1);
		c.lconst_0();
		c.invokestatic(refs.longValueOf().entry());
		c.invokestatic(refs.rCmp().entry());
		c.iconst_0();
		MethodCode.Label notZero = c.newLabel();
		c.if_icmpne(notZero);
		c.aload(0);
		c.areturn();
		c.labelBinding(notZero);
		emitNewHolderFromSlots(c, refs, 0, 1);
		c.areturn();
		// Float path: both parts through _dbl (a Double answers as-is).
		c.labelBinding(realIsDouble);
		c.labelBinding(imagIsDouble);
		c.aload(0);
		c.invokestatic(refs.rDbl().entry());
		c.astore(0);
		c.aload(1);
		c.invokestatic(refs.rDbl().entry());
		c.astore(1);
		emitNewHolderFromSlots(c, refs, 0, 1);
		c.areturn();
		return new ComplexMethod(name, desc, c);
	}

	// _cadd/_csub over real-or-complex operands. All-rational parts compute
	// exactly through _add/_sub; a float anywhere coerces the step to doubles.
	// Slots: params 0-1, parts 2-5, boxed results 6-7.
	private static ComplexMethod buildAdd(Refs refs, Utf8Constant name, Utf8Constant desc) {
		return buildAddSub(refs, name, desc, MethodCode::dadd, JvmNumericRuntimeBuilder.ADD);
	}

	private static ComplexMethod buildSub(Refs refs, Utf8Constant name, Utf8Constant desc) {
		return buildAddSub(refs, name, desc, MethodCode::dsub, JvmNumericRuntimeBuilder.SUB);
	}

	private static ComplexMethod buildAddSub(Refs refs, Utf8Constant name, Utf8Constant desc,
			Consumer<MethodCode> doubleOp, String exactOp) {
		MethodCode c = new MethodCode();
		emitExtractParts(c, refs, 0, 2, 3);
		emitExtractParts(c, refs, 1, 4, 5);
		MethodCode.Label toFloat = c.newLabel();
		emitFloatTest(c, refs, new int[] { 2, 3, 4, 5 }, toFloat);
		c.aload(2);
		c.aload(4);
		c.invokestatic(exactOpRef(refs, exactOp).entry());
		c.aload(3);
		c.aload(5);
		c.invokestatic(exactOpRef(refs, exactOp).entry());
		c.invokestatic(refs.rCComplex().entry());
		c.areturn();
		c.labelBinding(toFloat);
		emitToDouble(c, refs, 2);
		emitToDouble(c, refs, 4);
		doubleOp.accept(c);
		emitBoxDouble(c, refs);
		c.astore(6);
		emitToDouble(c, refs, 3);
		emitToDouble(c, refs, 5);
		doubleOp.accept(c);
		emitBoxDouble(c, refs);
		c.astore(7);
		emitNewHolderFromSlots(c, refs, 6, 7);
		c.areturn();
		return new ComplexMethod(name, desc, c);
	}

	private static MethodrefConstant exactOpRef(Refs refs, String exactOp) {
		if (JvmNumericRuntimeBuilder.ADD.equals(exactOp)) {
			return refs.rAdd();
		}
		if (JvmNumericRuntimeBuilder.SUB.equals(exactOp)) {
			return refs.rSub();
		}
		if (JvmNumericRuntimeBuilder.MUL.equals(exactOp)) {
			return refs.rMul();
		}
		return refs.rDiv();
	}

	// _cmul over real-or-complex operands: (a+bi)(c+di) = (ac-bd, ad+bc).
	// Slots: params 0-1, parts 2-5, boxed results 6-7, doubles 8-15.
	private static ComplexMethod buildMul(Refs refs, Utf8Constant name, Utf8Constant desc) {
		MethodCode c = new MethodCode();
		emitExtractParts(c, refs, 0, 2, 3);
		emitExtractParts(c, refs, 1, 4, 5);
		MethodCode.Label toFloat = c.newLabel();
		emitFloatTest(c, refs, new int[] { 2, 3, 4, 5 }, toFloat);
		c.aload(2);
		c.aload(4);
		c.invokestatic(refs.rMul().entry());
		c.aload(3);
		c.aload(5);
		c.invokestatic(refs.rMul().entry());
		c.invokestatic(refs.rSub().entry());
		c.astore(6);
		c.aload(2);
		c.aload(5);
		c.invokestatic(refs.rMul().entry());
		c.aload(3);
		c.aload(4);
		c.invokestatic(refs.rMul().entry());
		c.invokestatic(refs.rAdd().entry());
		c.astore(7);
		c.aload(6);
		c.aload(7);
		c.invokestatic(refs.rCComplex().entry());
		c.areturn();
		c.labelBinding(toFloat);
		emitToDouble(c, refs, 2);
		c.dstore(8);
		emitToDouble(c, refs, 3);
		c.dstore(10);
		emitToDouble(c, refs, 4);
		c.dstore(12);
		emitToDouble(c, refs, 5);
		c.dstore(14);
		c.dload(8);
		c.dload(12);
		c.dmul();
		c.dload(10);
		c.dload(14);
		c.dmul();
		c.dsub();
		emitBoxDouble(c, refs);
		c.astore(6);
		c.dload(8);
		c.dload(14);
		c.dmul();
		c.dload(10);
		c.dload(12);
		c.dmul();
		c.dadd();
		emitBoxDouble(c, refs);
		c.astore(7);
		emitNewHolderFromSlots(c, refs, 6, 7);
		c.areturn();
		return new ComplexMethod(name, desc, c);
	}

	// _cdiv over real-or-complex operands. The exact path divides by the
	// c^2+d^2 denominator through _div, whose _rat landing throws the same
	// ArithmeticException("Division by zero") a real division throws; the FLOAT tail
	// is Smith's form, the interpreter's smithDivide (see there for why).
	// Slots: params 0-1, parts 2-5, temps 6-8, doubles 10-21
	// (10=a, 12=b, 14=c, 16=d, 18=r, 20=den).
	private static ComplexMethod buildDiv(Refs refs, ConstantPool cp, Utf8Constant name, Utf8Constant desc) {
		MethodCode c = new MethodCode();
		// Neither operand a holder: a plain real division, which the ungated _div
		// answers -- exactly, without the c^2+d^2 denominator's two extra roundings
		// and without manufacturing a zero-imagined float holder the float path
		// cannot canonicalize away. The arm is reachable because a complex-capable
		// site only knows at RUN time whether it holds a complex: (log n base)
		// divides two logarithms, either of which may have stayed real, and this is
		// what keeps that quotient EQUAL to (/ (log n) (log base)). The WASM twin
		// (_c_div's own head) is the same arm.
		c.aload(0);
		c.instanceOf(refs.rcClass().entry());
		MethodCode.Label firstIsHolder = c.newLabel();
		c.ifne(firstIsHolder);
		c.aload(1);
		c.instanceOf(refs.rcClass().entry());
		MethodCode.Label secondIsHolder = c.newLabel();
		c.ifne(secondIsHolder);
		c.aload(0);
		c.aload(1);
		c.invokestatic(refs.rDiv().entry());
		c.areturn();
		c.labelBinding(firstIsHolder);
		c.labelBinding(secondIsHolder);
		emitExtractParts(c, refs, 0, 2, 3);
		emitExtractParts(c, refs, 1, 4, 5);
		MethodCode.Label toFloat = c.newLabel();
		emitFloatTest(c, refs, new int[] { 2, 3, 4, 5 }, toFloat);
		c.aload(4);
		c.aload(4);
		c.invokestatic(refs.rMul().entry());
		c.aload(5);
		c.aload(5);
		c.invokestatic(refs.rMul().entry());
		c.invokestatic(refs.rAdd().entry());
		c.astore(6);
		c.aload(2);
		c.aload(4);
		c.invokestatic(refs.rMul().entry());
		c.aload(3);
		c.aload(5);
		c.invokestatic(refs.rMul().entry());
		c.invokestatic(refs.rAdd().entry());
		c.aload(6);
		c.invokestatic(refs.rDiv().entry());
		c.astore(7);
		c.aload(3);
		c.aload(4);
		c.invokestatic(refs.rMul().entry());
		c.aload(2);
		c.aload(5);
		c.invokestatic(refs.rMul().entry());
		c.invokestatic(refs.rSub().entry());
		c.aload(6);
		c.invokestatic(refs.rDiv().entry());
		c.astore(8);
		c.aload(7);
		c.aload(8);
		c.invokestatic(refs.rCComplex().entry());
		c.areturn();
		c.labelBinding(toFloat);
		emitToDouble(c, refs, 2);
		c.dstore(10);
		emitToDouble(c, refs, 3);
		c.dstore(12);
		emitToDouble(c, refs, 4);
		c.dstore(14);
		emitToDouble(c, refs, 5);
		c.dstore(16);
		// Smith's fold: |c| >= |d| ? -- DCMPL answers -1 for a NaN, so a NaN operand
		// takes the mirrored arm, exactly what Java's >= does in smithDivide.
		c.dload(14);
		callMath(c, refs, cp, "abs", "(D)D");
		c.dload(16);
		callMath(c, refs, cp, "abs", "(D)D");
		c.dcmpl();
		MethodCode.Label realFold = c.newLabel();
		c.ifge(realFold);
		// |c| < |d|: r = c/d, den = c*r + d, re = (a*r + b)/den, im = (b*r - a)/den.
		c.dload(14);
		c.dload(16);
		c.ddiv();
		c.dstore(18);
		c.dload(14);
		c.dload(18);
		c.dmul();
		c.dload(16);
		c.dadd();
		c.dstore(20);
		c.dload(10);
		c.dload(18);
		c.dmul();
		c.dload(12);
		c.dadd();
		c.dload(20);
		c.ddiv();
		emitBoxDouble(c, refs);
		c.astore(6);
		c.dload(12);
		c.dload(18);
		c.dmul();
		c.dload(10);
		c.dsub();
		c.dload(20);
		c.ddiv();
		emitBoxDouble(c, refs);
		c.astore(7);
		MethodCode.Label built = c.newLabel();
		c.goto_(built);
		c.labelBinding(realFold);
		// |c| >= |d|: r = d/c, den = c + d*r, re = (a + b*r)/den, im = (b - a*r)/den.
		// A REAL divisor lands here with d zero, so both parts are ONE division.
		c.dload(16);
		c.dload(14);
		c.ddiv();
		c.dstore(18);
		c.dload(14);
		c.dload(16);
		c.dload(18);
		c.dmul();
		c.dadd();
		c.dstore(20);
		c.dload(10);
		c.dload(12);
		c.dload(18);
		c.dmul();
		c.dadd();
		c.dload(20);
		c.ddiv();
		emitBoxDouble(c, refs);
		c.astore(6);
		c.dload(12);
		c.dload(10);
		c.dload(18);
		c.dmul();
		c.dsub();
		c.dload(20);
		c.ddiv();
		emitBoxDouble(c, refs);
		c.astore(7);
		c.labelBinding(built);
		emitNewHolderFromSlots(c, refs, 6, 7);
		c.areturn();
		return new ComplexMethod(name, desc, c);
	}

	// _cneg(Object x): (-re, -im), each part through _neg (which keeps doubles
	// unboxed-negated, so signed zeros survive exactly like the interpreter's
	// negateReal/exactNeg).
	private static ComplexMethod buildNeg(Refs refs, Utf8Constant name, Utf8Constant desc) {
		MethodCode c = new MethodCode();
		emitExtractParts(c, refs, 0, 1, 2);
		c.aload(1);
		c.invokestatic(refs.rNeg().entry());
		c.aload(2);
		c.invokestatic(refs.rNeg().entry());
		c.invokestatic(refs.rCComplex().entry());
		c.areturn();
		return new ComplexMethod(name, desc, c);
	}

	/** Pushes a double constant (with the -0.0 guard of the shared emitters). */
	private static void emitDoubleConst(MethodCode c, ConstantPool cp, double value) {
		if (value == 0.0 && Double.doubleToRawLongBits(value) == 0L) {
			c.dconst_0();
		}
		else if (value == 1.0) {
			c.dconst_1();
		}
		else {
			ConstantPool.DoubleConstant dc = cp.addDouble(value);
			c.ldc(dc.entry());
		}
	}

	/**
	 * The principal square root of the doubles in {@code reSlot}/{@code imSlot}, into
	 * {@code r0Slot}/{@code r1Slot} (using {@code tSlot} for t). A double-zero answers
	 * its inputs unchanged, like {@code Environment.complexSqrt}.
	 */
	private static void emitComplexSqrtInto(MethodCode c, Refs refs, ConstantPool cp, int reSlot, int imSlot,
			int r0Slot, int r1Slot, int tSlot) {
		c.dload(reSlot);
		c.dconst_0();
		c.dcmpl();
		MethodCode.Label compute = c.newLabel();
		c.ifne(compute);
		c.dload(imSlot);
		c.dconst_0();
		c.dcmpl();
		MethodCode.Label compute2 = c.newLabel();
		c.ifne(compute2);
		c.dload(reSlot);
		c.dstore(r0Slot);
		c.dload(imSlot);
		c.dstore(r1Slot);
		MethodCode.Label done = c.newLabel();
		c.goto_(done);
		c.labelBinding(compute);
		c.labelBinding(compute2);
		c.dload(reSlot);
		callMath(c, refs, cp, "abs", "(D)D");
		c.dload(reSlot);
		c.dload(imSlot);
		callMath(c, refs, cp, "hypot", "(DD)D");
		c.dadd();
		emitDoubleConst(c, cp, 2.0);
		c.ddiv();
		callMath(c, refs, cp, "sqrt", "(D)D");
		c.dstore(tSlot);
		c.dload(reSlot);
		c.dconst_0();
		c.dcmpl();
		MethodCode.Label negative = c.newLabel();
		c.iflt(negative);
		c.dload(tSlot);
		c.dstore(r0Slot);
		c.dload(imSlot);
		c.dload(tSlot);
		emitDoubleConst(c, cp, 2.0);
		c.dmul();
		c.ddiv();
		c.dstore(r1Slot);
		MethodCode.Label done2 = c.newLabel();
		c.goto_(done2);
		c.labelBinding(negative);
		c.dload(imSlot);
		callMath(c, refs, cp, "abs", "(D)D");
		c.dload(tSlot);
		emitDoubleConst(c, cp, 2.0);
		c.dmul();
		c.ddiv();
		c.dstore(r0Slot);
		c.dload(tSlot);
		c.dload(imSlot);
		callMath(c, refs, cp, "copySign", "(DD)D");
		c.dstore(r1Slot);
		c.labelBinding(done2);
		c.labelBinding(done);
	}

	/**
	 * The complex logarithm of the doubles in {@code reSlot}/{@code imSlot}, leaving the
	 * two result doubles on the stack.
	 */
	private static void emitComplexLog(MethodCode c, Refs refs, ConstantPool cp, int reSlot, int imSlot) {
		c.dload(reSlot);
		c.dload(imSlot);
		callMath(c, refs, cp, "hypot", "(DD)D");
		callMath(c, refs, cp, "log", "(D)D");
		c.dload(imSlot);
		c.dload(reSlot);
		callMath(c, refs, cp, "atan2", "(DD)D");
	}

	// _csqrt(Object x): the principal square root. A complex operand answers the
	// float formula; a negative real roots into the plane as (0, sqrt(-d)); any
	// other real answers Math.sqrt. Slots: param 0, boxed temps 1-2, doubles
	// 3-8.
	private static ComplexMethod buildSqrt(Refs refs, ConstantPool cp, Utf8Constant name, Utf8Constant desc) {
		MethodCode c = new MethodCode();
		c.aload(0);
		c.instanceOf(refs.rcClass().entry());
		MethodCode.Label realPath = c.newLabel();
		c.ifeq(realPath);
		c.aload(0);
		c.checkcast(refs.rcClass().entry());
		c.getfield(refs.rcReal().entry());
		c.astore(1);
		c.aload(0);
		c.checkcast(refs.rcClass().entry());
		c.getfield(refs.rcImag().entry());
		c.astore(2);
		emitToDouble(c, refs, 1);
		c.dstore(3);
		emitToDouble(c, refs, 2);
		c.dstore(5);
		emitComplexSqrtInto(c, refs, cp, 3, 5, 3, 5, 7);
		c.dload(3);
		emitBoxDouble(c, refs);
		c.astore(1);
		c.dload(5);
		emitBoxDouble(c, refs);
		c.astore(2);
		emitNewHolderFromSlots(c, refs, 1, 2);
		c.areturn();
		c.labelBinding(realPath);
		emitToDouble(c, refs, 0);
		c.dstore(3);
		c.dload(3);
		c.dconst_0();
		c.dcmpg();
		MethodCode.Label nonNegative = c.newLabel();
		c.ifge(nonNegative);
		c.dconst_0();
		emitBoxDouble(c, refs);
		c.astore(1);
		c.dload(3);
		c.dneg();
		callMath(c, refs, cp, "sqrt", "(D)D");
		emitBoxDouble(c, refs);
		c.astore(2);
		emitNewHolderFromSlots(c, refs, 1, 2);
		c.areturn();
		c.labelBinding(nonNegative);
		c.dload(3);
		callMath(c, refs, cp, "sqrt", "(D)D");
		emitBoxDouble(c, refs);
		c.areturn();
		return new ComplexMethod(name, desc, c);
	}

	// _cpow(Object base, Object exp): an int-range integer exponent over
	// non-float parts stays exact by repeated squaring (a negative one through
	// the exact reciprocal); anything else goes through exp(w*log(z)) in
	// floats. Slots: params 0-1, base parts 2-3, exp parts 4-5, power 6,
	// accumulators 7-8, temps 9-10, doubles 11-26.
	private static ComplexMethod buildPow(Refs refs, ConstantPool cp, Utf8Constant name, Utf8Constant desc) {
		MethodCode c = new MethodCode();
		c.aload(0);
		c.instanceOf(refs.doubleClass().entry());
		MethodCode.Label floatPathEarly = c.newLabel();
		c.ifne(floatPathEarly);
		c.aload(0);
		c.instanceOf(refs.rcClass().entry());
		MethodCode.Label baseNotHolder = c.newLabel();
		c.ifeq(baseNotHolder);
		c.aload(0);
		c.checkcast(refs.rcClass().entry());
		c.getfield(refs.rcReal().entry());
		c.instanceOf(refs.doubleClass().entry());
		MethodCode.Label floatPathEarly2 = c.newLabel();
		c.ifne(floatPathEarly2);
		c.aload(0);
		c.checkcast(refs.rcClass().entry());
		c.getfield(refs.rcImag().entry());
		c.instanceOf(refs.doubleClass().entry());
		MethodCode.Label floatPathEarly3 = c.newLabel();
		c.ifne(floatPathEarly3);
		c.labelBinding(baseNotHolder);
		c.aload(1);
		c.instanceOf(refs.longClass().entry());
		MethodCode.Label floatPathExp = c.newLabel();
		c.ifeq(floatPathExp);
		c.aload(1);
		c.checkcast(refs.longClass().entry());
		c.invokevirtual(refs.longValue().methodRefEntry());
		c.lstore(9);
		c.lload(9);
		ConstantPool.LongConstant maxPow = cp.addLong(Integer.MAX_VALUE);
		c.ldc(maxPow.entry());
		c.lcmp();
		MethodCode.Label floatPathRange = c.newLabel();
		c.ifgt(floatPathRange);
		c.lload(9);
		ConstantPool.LongConstant minPow = cp.addLong(-(long) Integer.MAX_VALUE);
		c.ldc(minPow.entry());
		c.lcmp();
		MethodCode.Label floatPathRange2 = c.newLabel();
		c.iflt(floatPathRange2);
		c.lload(9);
		c.l2i();
		c.istore(6);
		// Exact path.
		emitExtractParts(c, refs, 0, 2, 3);
		c.lconst_1();
		c.invokestatic(refs.longValueOf().entry());
		c.astore(7);
		c.lconst_0();
		c.invokestatic(refs.longValueOf().entry());
		c.astore(8);
		c.iload(6);
		MethodCode.Label noRecip = c.newLabel();
		c.ifge(noRecip);
		c.aload(2);
		c.aload(2);
		c.invokestatic(refs.rMul().entry());
		c.aload(3);
		c.aload(3);
		c.invokestatic(refs.rMul().entry());
		c.invokestatic(refs.rAdd().entry());
		c.astore(9);
		c.aload(2);
		c.aload(9);
		c.invokestatic(refs.rDiv().entry());
		c.astore(2);
		c.aload(3);
		c.invokestatic(refs.rNeg().entry());
		c.aload(9);
		c.invokestatic(refs.rDiv().entry());
		c.astore(3);
		c.iload(6);
		c.ineg();
		c.istore(6);
		c.labelBinding(noRecip);
		MethodCode.Label loopTop = c.newBoundLabel();
		c.iload(6);
		MethodCode.Label loopEnd = c.newLabel();
		c.ifle(loopEnd);
		c.iload(6);
		c.iconst_1();
		c.iand();
		MethodCode.Label skipMul = c.newLabel();
		c.ifeq(skipMul);
		c.aload(7);
		c.aload(2);
		c.invokestatic(refs.rMul().entry());
		c.aload(8);
		c.aload(3);
		c.invokestatic(refs.rMul().entry());
		c.invokestatic(refs.rSub().entry());
		c.astore(9);
		c.aload(7);
		c.aload(3);
		c.invokestatic(refs.rMul().entry());
		c.aload(8);
		c.aload(2);
		c.invokestatic(refs.rMul().entry());
		c.invokestatic(refs.rAdd().entry());
		c.astore(8);
		c.aload(9);
		c.astore(7);
		c.labelBinding(skipMul);
		c.aload(2);
		c.aload(2);
		c.invokestatic(refs.rMul().entry());
		c.aload(3);
		c.aload(3);
		c.invokestatic(refs.rMul().entry());
		c.invokestatic(refs.rSub().entry());
		c.astore(9);
		c.aload(2);
		c.aload(3);
		c.invokestatic(refs.rMul().entry());
		c.aload(3);
		c.aload(2);
		c.invokestatic(refs.rMul().entry());
		c.invokestatic(refs.rAdd().entry());
		c.astore(3);
		c.aload(9);
		c.astore(2);
		c.iload(6);
		c.iconst_1();
		c.ishr();
		c.istore(6);
		c.goto_(loopTop);
		c.labelBinding(loopEnd);
		c.aload(7);
		c.aload(8);
		c.invokestatic(refs.rCComplex().entry());
		c.areturn();
		// Float path through exp(w*log(z)).
		c.labelBinding(floatPathEarly);
		c.labelBinding(floatPathEarly2);
		c.labelBinding(floatPathEarly3);
		c.labelBinding(floatPathExp);
		c.labelBinding(floatPathRange);
		c.labelBinding(floatPathRange2);
		emitExtractParts(c, refs, 0, 2, 3);
		emitExtractParts(c, refs, 1, 4, 5);
		emitToDouble(c, refs, 2);
		c.dstore(11);
		emitToDouble(c, refs, 3);
		c.dstore(13);
		emitToDouble(c, refs, 4);
		c.dstore(15);
		emitToDouble(c, refs, 5);
		c.dstore(17);
		c.dload(11);
		c.dload(13);
		callMath(c, refs, cp, "hypot", "(DD)D");
		callMath(c, refs, cp, "log", "(D)D");
		c.dstore(19);
		c.dload(13);
		c.dload(11);
		callMath(c, refs, cp, "atan2", "(DD)D");
		c.dstore(21);
		c.dload(13);
		c.dload(11);
		callMath(c, refs, cp, "atan2", "(DD)D");
		c.dstore(21);
		c.dload(15);
		c.dload(19);
		c.dmul();
		c.dload(17);
		c.dload(21);
		c.dmul();
		c.dsub();
		callMath(c, refs, cp, "exp", "(D)D");
		c.dstore(23);
		c.dload(15);
		c.dload(21);
		c.dmul();
		c.dload(17);
		c.dload(19);
		c.dmul();
		c.dadd();
		c.dstore(25);
		c.dload(23);
		c.dload(25);
		callMath(c, refs, cp, "cos", "(D)D");
		c.dmul();
		emitBoxDouble(c, refs);
		c.astore(7);
		c.dload(23);
		c.dload(25);
		callMath(c, refs, cp, "sin", "(D)D");
		c.dmul();
		emitBoxDouble(c, refs);
		c.astore(8);
		emitNewHolderFromSlots(c, refs, 7, 8);
		c.areturn();
		return new ComplexMethod(name, desc, c);
	}

	/**
	 * {@code _cpowr(base, exp)}: the REAL {@code expt} with the one answer that leaves
	 * the real line. A negative base to a non-integer power is {@code |base|^exp} turned
	 * through {@code exp*pi} radians -- one {@code Math.pow} and one rotation, which is
	 * the interpreter's {@code negativeBasePow} term for term (and NOT {@code _cpow}'s
	 * {@code exp(w*log z)}: a real base's phase is exactly pi, so nothing has to be
	 * recovered from a logarithm). Every other operand pair -- a non-negative base, an
	 * integer-valued power, a NaN, an infinite power -- delegates to the unconditional
	 * {@code _pow}, which keeps the exact rational path and the error funnels
	 * unduplicated. Slots: base 0, exp 1, x 2, y 4, modulus 6, boxed parts 8 and 9.
	 */
	private static ComplexMethod buildPowReal(Refs refs, ConstantPool cp, Utf8Constant name, Utf8Constant desc) {
		MethodCode c = new MethodCode();
		emitToDouble(c, refs, 0);
		c.dstore(2);
		emitToDouble(c, refs, 1);
		c.dstore(4);
		// x < 0 ? -- DCMPG answers 1 for a NaN, which takes the real path with it.
		c.dload(2);
		c.dconst_0();
		c.dcmpg();
		MethodCode.Label realPathSign = c.newLabel();
		c.ifge(realPathSign);
		c.dload(4);
		c.invokestatic(
				cp.addMethodref(refs.doubleClass(), cp.addNameAndType(cp.addUtf8("isFinite"), cp.addUtf8("(D)Z")))
					.entry());
		MethodCode.Label realPathInfinite = c.newLabel();
		c.ifeq(realPathInfinite);
		c.dload(4);
		c.dload(4);
		callMath(c, refs, cp, "rint", "(D)D");
		c.dcmpl();
		MethodCode.Label realPathInteger = c.newLabel();
		c.ifeq(realPathInteger);
		c.dload(2);
		c.dneg();
		c.dload(4);
		callMath(c, refs, cp, "pow", "(DD)D");
		c.dstore(6);
		c.dload(4);
		emitDoubleConst(c, cp, Math.PI);
		c.dmul();
		c.dstore(4);
		c.dload(6);
		c.dload(4);
		callMath(c, refs, cp, "cos", "(D)D");
		c.dmul();
		emitBoxDouble(c, refs);
		c.astore(8);
		c.dload(6);
		c.dload(4);
		callMath(c, refs, cp, "sin", "(D)D");
		c.dmul();
		emitBoxDouble(c, refs);
		c.astore(9);
		emitNewHolderFromSlots(c, refs, 8, 9);
		c.areturn();
		c.labelBinding(realPathSign);
		c.labelBinding(realPathInfinite);
		c.labelBinding(realPathInteger);
		c.aload(0);
		c.aload(1);
		c.invokestatic(refs.rPow().entry());
		c.areturn();
		return new ComplexMethod(name, desc, c);
	}

	/**
	 * The two roots Kahan's asin and acos are both assembled from: u = sqrt(1 - z) into
	 * {@code u0Slot}/{@code u1Slot} and v = sqrt(1 + z) into {@code v0Slot}/
	 * {@code v1Slot}, each rooted in place through {@code tSlot}. Both imaginary parts
	 * are taken against a {@code +0.0} exactly as the interpreter's {@code 0.0 - im} /
	 * {@code 0.0 + im} do: that is what leaves an imaginary zero of EITHER sign on the
	 * same sheet, so the side of the branch cut is decided by the real part alone.
	 */
	private static void emitAsinAcosRootsInto(MethodCode c, Refs refs, ConstantPool cp, int reSlot, int imSlot,
			int u0Slot, int u1Slot, int v0Slot, int v1Slot, int tSlot) {
		c.dconst_1();
		c.dload(reSlot);
		c.dsub();
		c.dstore(u0Slot);
		c.dconst_0();
		c.dload(imSlot);
		c.dsub();
		c.dstore(u1Slot);
		emitComplexSqrtInto(c, refs, cp, u0Slot, u1Slot, u0Slot, u1Slot, tSlot);
		c.dconst_1();
		c.dload(reSlot);
		c.dadd();
		c.dstore(v0Slot);
		c.dconst_0();
		c.dload(imSlot);
		c.dadd();
		c.dstore(v1Slot);
		emitComplexSqrtInto(c, refs, cp, v0Slot, v1Slot, v0Slot, v1Slot, tSlot);
	}

	// _cu1(Object x, int op): the unary math functions. A complex operand
	// answers the float formula; any other operand answers Math.<fn> of its
	// double. Slots: params 0-1, rd/d 2-3, id 4-5, t0 6-7, t1 8-9, r0 10-11,
	// r1 12-13, denom 14-15.
	private static ComplexMethod buildU1(Refs refs, ConstantPool cp, Utf8Constant name, Utf8Constant desc) {
		MethodCode c = new MethodCode();
		c.aload(0);
		c.instanceOf(refs.rcClass().entry());
		MethodCode.Label realPath = c.newLabel();
		c.ifeq(realPath);
		c.aload(0);
		c.checkcast(refs.rcClass().entry());
		c.getfield(refs.rcReal().entry());
		c.astore(6);
		c.aload(0);
		c.checkcast(refs.rcClass().entry());
		c.getfield(refs.rcImag().entry());
		c.astore(8);
		emitToDouble(c, refs, 6);
		c.dstore(2);
		emitToDouble(c, refs, 8);
		c.dstore(4);
		MethodCode.Label toTail = c.newLabel();
		for (int op = 0; op <= U1_CIS; op++) {
			MethodCode.Label nextArm = c.newLabel();
			if (op < U1_CIS) {
				c.iload(1);
				c.loadConstant(op);
				c.if_icmpne(nextArm);
			}
			emitComplexArm(c, refs, cp, op);
			if (op < U1_CIS) {
				c.goto_(toTail);
				c.labelBinding(nextArm);
			}
		}
		c.labelBinding(toTail);
		c.dstore(12);
		c.dstore(10);
		c.dload(10);
		emitBoxDouble(c, refs);
		c.astore(6);
		c.dload(12);
		emitBoxDouble(c, refs);
		c.astore(8);
		emitNewHolderFromSlots(c, refs, 6, 8);
		c.areturn();
		c.labelBinding(realPath);
		emitToDouble(c, refs, 0);
		c.dstore(2);
		String[] mathFns = { "exp", "log", "sin", "cos", "tan", "asin", "acos", "atan", "sinh", "cosh", "tanh" };
		for (int op = 0; op <= U1_TANH; op++) {
			c.iload(1);
			c.loadConstant(op);
			MethodCode.Label next = c.newLabel();
			c.if_icmpne(next);
			// log of a negative and asin/acos beyond [-1, 1] leave the real line, where
			// java.lang.Math answers NaN: they run the SAME complex arm at (x, +0.0),
			// like acosh and atanh below. The tests are written as the conditions that
			// KEEP the real arm and jump over the escape, so a NaN -- which fails every
			// comparison -- needs only the DCMP variant that answers "not less"
			// (dcmpg) to come back out as itself. asin/acos additionally keep an
			// INFINITY real: it has no complex value either, and the formula would
			// manufacture a #C(NaN Infinity). log's -Infinity is a real point of the
			// plane (#C(Infinity pi)) and escapes like any other negative.
			MethodCode.Label toReal = c.newLabel();
			boolean escapes = op == U1_LOG || op == U1_ASIN || op == U1_ACOS;
			if (op == U1_LOG) {
				c.dload(2);
				c.dconst_0();
				c.dcmpg();
				c.ifge(toReal);
			}
			else if (op == U1_ASIN || op == U1_ACOS) {
				c.dload(2);
				callMath(c, refs, cp, "abs", "(D)D");
				c.dconst_1();
				c.dcmpg();
				c.ifle(toReal);
				c.dload(2);
				callMath(c, refs, cp, "abs", "(D)D");
				emitDoubleConst(c, cp, Double.POSITIVE_INFINITY);
				c.dcmpg();
				c.ifge(toReal);
			}
			if (escapes) {
				emitRealAsComplex(c, refs, cp, op);
			}
			c.labelBinding(toReal);
			c.dload(2);
			callMath(c, refs, cp, mathFns[op], "(D)D");
			emitBoxDouble(c, refs);
			c.areturn();
			c.labelBinding(next);
		}
		// asinh/acosh/atanh have no java.lang.Math counterpart -- the real arms are the
		// interpreter's formulas, bytecode term for term, so both answer identical bits.
		c.iload(1);
		c.loadConstant(U1_ASINH);
		MethodCode.Label notAsinh = c.newLabel();
		c.if_icmpne(notAsinh);
		emitAsinhRealF64(c, refs, cp, 2);
		emitBoxDouble(c, refs);
		c.areturn();
		c.labelBinding(notAsinh);
		// acosh: x >= 1 stays real (a NaN stays a NaN double, like the interpreter's
		// !(d < 1.0) test -- which is why the compare is DCMPG, whose NaN answers 1);
		// x < 1 escapes into the plane at (x, +0.0).
		c.iload(1);
		c.loadConstant(U1_ACOSH);
		MethodCode.Label notAcosh = c.newLabel();
		c.if_icmpne(notAcosh);
		c.dload(2);
		c.dconst_1();
		c.dcmpg();
		MethodCode.Label acoshEscape = c.newLabel();
		c.iflt(acoshEscape);
		emitAcoshRealF64(c, refs, cp, 2);
		emitBoxDouble(c, refs);
		c.areturn();
		c.labelBinding(acoshEscape);
		emitRealAsComplex(c, refs, cp, U1_ACOSH);
		c.labelBinding(notAcosh);
		// atanh: |x| <= 1 stays real (a NaN too, like !(d > 1) && !(d < -1) -- DCMPL for
		// the > and DCMPG for the <, so a NaN fails both); beyond that it escapes at
		// (x, +0.0).
		c.iload(1);
		c.loadConstant(U1_ATANH);
		MethodCode.Label notAtanh = c.newLabel();
		c.if_icmpne(notAtanh);
		c.dload(2);
		c.dconst_1();
		c.dcmpl();
		MethodCode.Label atanhEscape = c.newLabel();
		c.ifgt(atanhEscape);
		c.dload(2);
		emitDoubleConst(c, cp, -1.0);
		c.dcmpg();
		MethodCode.Label atanhEscape2 = c.newLabel();
		c.iflt(atanhEscape2);
		// (log1p(x) - log1p(-x)) * 0.5
		c.dload(2);
		callMath(c, refs, cp, "log1p", "(D)D");
		c.dload(2);
		c.dneg();
		callMath(c, refs, cp, "log1p", "(D)D");
		c.dsub();
		emitDoubleConst(c, cp, 0.5);
		c.dmul();
		emitBoxDouble(c, refs);
		c.areturn();
		c.labelBinding(atanhEscape);
		c.labelBinding(atanhEscape2);
		emitRealAsComplex(c, refs, cp, U1_ATANH);
		c.labelBinding(notAtanh);
		// cis answers a complex for every operand: the +0.0 arm is exact (e^0 = 1, and
		// 1.0 * is the identity), matching the interpreter's direct (cos x, sin x).
		emitRealAsComplex(c, refs, cp, U1_CIS);
		// 12 stack slots: the asin/acos arms hold their finished real part (2) while
		// the asinh sub-formula runs (8 of its own) before the pair is boxed.
		return new ComplexMethod(name, desc, c);
	}

	/**
	 * The real arm of {@code asinh} -- the double in {@code slot} as its argument, the
	 * result on the stack, {@code slot 6} as its scratch. The interpreter's three
	 * branches: the |x| &lt;= 1 log1p form (accurate near zero), one log over |x| +
	 * hypot(|x|, 1) beyond (the sum cancels nothing above 1, and the hypot cannot
	 * overflow), and log |x| + log 2 past 8.5e307, where the SUM would.
	 */
	private static void emitAsinhRealF64(MethodCode c, Refs refs, ConstantPool cp, int slot) {
		c.dload(slot);
		callMath(c, refs, cp, "abs", "(D)D");
		c.dstore(6);
		c.dload(6);
		c.dconst_1();
		c.dcmpl();
		MethodCode.Label large = c.newLabel();
		c.ifgt(large);
		c.dload(6);
		c.dload(6);
		c.dmul();
		c.dconst_1();
		c.dload(6);
		c.dconst_1();
		callMath(c, refs, cp, "hypot", "(DD)D");
		c.dadd();
		c.ddiv();
		c.dload(6);
		c.dadd();
		callMath(c, refs, cp, "log1p", "(D)D");
		MethodCode.Label sign = c.newLabel();
		c.goto_(sign);
		c.labelBinding(large);
		c.dload(6);
		emitDoubleConst(c, cp, 8.5e307);
		c.dcmpl();
		MethodCode.Label huge = c.newLabel();
		c.ifge(huge);
		c.dload(6);
		c.dload(6);
		c.dconst_1();
		callMath(c, refs, cp, "hypot", "(DD)D");
		c.dadd();
		callMath(c, refs, cp, "log", "(D)D");
		MethodCode.Label sign2 = c.newLabel();
		c.goto_(sign2);
		c.labelBinding(huge);
		c.dload(6);
		callMath(c, refs, cp, "log", "(D)D");
		emitDoubleConst(c, cp, 2.0);
		callMath(c, refs, cp, "log", "(D)D");
		c.dadd();
		c.labelBinding(sign2);
		c.labelBinding(sign);
		c.dload(slot);
		callMath(c, refs, cp, "copySign", "(DD)D");
	}

	/**
	 * The real arm of {@code acosh} (x &gt;= 1) -- the interpreter's three branches: the
	 * near-1 log1p form, the glibc-grouped log(2x) + log1p((r-1)/2) mid range (whose
	 * double bits are correctly rounded across the table), and a bare log(x) + log 2 so
	 * 2*x cannot overflow.
	 */
	private static void emitAcoshRealF64(MethodCode c, Refs refs, ConstantPool cp, int slot) {
		c.dload(slot);
		emitDoubleConst(c, cp, 2.0);
		c.dcmpl();
		MethodCode.Label mid = c.newLabel();
		c.ifgt(mid);
		c.dload(slot);
		c.dconst_1();
		c.dsub();
		c.dstore(6);
		c.dload(6);
		c.dload(6);
		c.dload(slot);
		c.dconst_1();
		c.dadd();
		c.dmul();
		callMath(c, refs, cp, "sqrt", "(D)D");
		c.dadd();
		callMath(c, refs, cp, "log1p", "(D)D");
		MethodCode.Label done = c.newLabel();
		c.goto_(done);
		c.labelBinding(mid);
		c.dload(slot);
		emitDoubleConst(c, cp, 8.5e307);
		c.dcmpl();
		MethodCode.Label huge = c.newLabel();
		c.ifgt(huge);
		c.dload(slot);
		emitDoubleConst(c, cp, 2.0);
		c.dmul();
		callMath(c, refs, cp, "log", "(D)D");
		c.dstore(8);
		c.dconst_1();
		c.dload(slot);
		c.ddiv();
		c.dstore(6);
		c.dconst_1();
		c.dload(6);
		c.dload(6);
		c.dmul();
		c.dsub();
		callMath(c, refs, cp, "sqrt", "(D)D");
		c.dconst_1();
		c.dsub();
		emitDoubleConst(c, cp, 2.0);
		c.ddiv();
		callMath(c, refs, cp, "log1p", "(D)D");
		c.dload(8);
		c.dadd();
		MethodCode.Label done2 = c.newLabel();
		c.goto_(done2);
		c.labelBinding(huge);
		c.dload(slot);
		callMath(c, refs, cp, "log", "(D)D");
		emitDoubleConst(c, cp, 2.0);
		callMath(c, refs, cp, "log", "(D)D");
		c.dadd();
		c.labelBinding(done2);
		c.labelBinding(done);
	}

	/**
	 * Answer the {@link #U1} complex arm for {@code op} with the real in {@code slot 2}
	 * promoted to (re, +0.0), boxing the pair into a holder and returning it: the
	 * acosh/atanh domain escape and the always-complex {@code cis}.
	 */
	private static void emitRealAsComplex(MethodCode c, Refs refs, ConstantPool cp, int op) {
		c.dconst_0();
		c.dstore(4);
		emitComplexArm(c, refs, cp, op);
		c.dstore(12);
		c.dstore(10);
		c.dload(10);
		emitBoxDouble(c, refs);
		c.astore(6);
		c.dload(12);
		emitBoxDouble(c, refs);
		c.astore(8);
		emitNewHolderFromSlots(c, refs, 6, 8);
		c.areturn();
	}

	/**
	 * One complex arm of {@link #buildU1}, leaving its two result doubles on the stack.
	 * Slots: rd 2-3, id 4-5, t0 6-7, t1 8-9, r0 10-11, r1 12-13, denom 14-15.
	 */
	private static void emitComplexArm(MethodCode c, Refs refs, ConstantPool cp, int op) {
		if (op == U1_EXP) {
			c.dload(2);
			callMath(c, refs, cp, "exp", "(D)D");
			c.dstore(6);
			c.dload(6);
			c.dload(4);
			callMath(c, refs, cp, "cos", "(D)D");
			c.dmul();
			c.dload(6);
			c.dload(4);
			callMath(c, refs, cp, "sin", "(D)D");
			c.dmul();
		}
		else if (op == U1_LOG) {
			emitComplexLog(c, refs, cp, 2, 4);
		}
		else if (op == U1_SIN) {
			c.dload(2);
			callMath(c, refs, cp, "sin", "(D)D");
			c.dload(4);
			callMath(c, refs, cp, "cosh", "(D)D");
			c.dmul();
			c.dload(2);
			callMath(c, refs, cp, "cos", "(D)D");
			c.dload(4);
			callMath(c, refs, cp, "sinh", "(D)D");
			c.dmul();
		}
		else if (op == U1_COS) {
			c.dload(2);
			callMath(c, refs, cp, "cos", "(D)D");
			c.dload(4);
			callMath(c, refs, cp, "cosh", "(D)D");
			c.dmul();
			c.dload(2);
			callMath(c, refs, cp, "sin", "(D)D");
			c.dload(4);
			callMath(c, refs, cp, "sinh", "(D)D");
			c.dmul();
			c.dneg();
		}
		else if (op == U1_TAN || op == U1_TANH) {
			// s = (hyp1(re)*trig1(im), hyp2(re)*trig2(im)),
			// c = (hyp2(re)*trig1(im), -/+hyp1(re)*cis(im)) with the hyperbolic
			// sine on the imaginary side for tan and the circular sine for tanh,
			// matching complexSin/complexCos over complexSinh/complexCosh.
			// Slots: s.re 6, s.im 8, c.re 14, c.im 2 (the operand's real part is dead by
			// then), |c|^2 10 -- five live quantities, five distinct slots.
			String hyp1 = op == U1_TAN ? "sin" : "sinh";
			String hyp2 = op == U1_TAN ? "cos" : "cosh";
			String trig1 = op == U1_TAN ? "cosh" : "cos";
			String cisIm = op == U1_TAN ? "sinh" : "sin";
			String trig2 = op == U1_TAN ? "sinh" : "sin";
			c.dload(2);
			callMath(c, refs, cp, hyp1, "(D)D");
			c.dload(4);
			callMath(c, refs, cp, trig1, "(D)D");
			c.dmul();
			c.dstore(6);
			c.dload(2);
			callMath(c, refs, cp, hyp2, "(D)D");
			c.dload(4);
			callMath(c, refs, cp, trig2, "(D)D");
			c.dmul();
			c.dstore(8);
			c.dload(2);
			callMath(c, refs, cp, hyp2, "(D)D");
			c.dload(4);
			callMath(c, refs, cp, trig1, "(D)D");
			c.dmul();
			c.dstore(14);
			c.dload(2);
			callMath(c, refs, cp, hyp1, "(D)D");
			c.dload(4);
			callMath(c, refs, cp, cisIm, "(D)D");
			c.dmul();
			if (op == U1_TAN) {
				c.dneg();
			}
			c.dstore(2);
			// |c|^2 goes to slot 10, NOT over c.re in slot 14: the quotient below reads
			// c.re four more times, and writing the modulus there turned both parts into
			// (s.re*|c|^2 + s.im*c.im)/|c|^2, which degenerates to the NUMERATOR
			// whenever c.im is zero -- tan of a real answered sin of it (.todo/765).
			c.dload(14);
			c.dload(14);
			c.dmul();
			c.dload(2);
			c.dload(2);
			c.dmul();
			c.dadd();
			c.dstore(10);
			c.dload(6);
			c.dload(14);
			c.dmul();
			c.dload(8);
			c.dload(2);
			c.dmul();
			c.dadd();
			c.dload(10);
			c.ddiv();
			c.dload(8);
			c.dload(14);
			c.dmul();
			c.dload(6);
			c.dload(2);
			c.dmul();
			c.dsub();
			c.dload(10);
			c.ddiv();
		}
		else if (op == U1_ASIN) {
			// asin(z) = (atan2(re, Re(u*v)), asinh(Im(conj(u)*v))): the interpreter's
			// Kahan form, term for term. The asinh argument goes to slot 14 first --
			// emitAsinhRealF64 keeps its own scratch in slot 6, which is u's real part.
			emitAsinAcosRootsInto(c, refs, cp, 2, 4, 6, 8, 10, 12, 14);
			c.dload(2);
			c.dload(6);
			c.dload(10);
			c.dmul();
			c.dload(8);
			c.dload(12);
			c.dmul();
			c.dsub();
			callMath(c, refs, cp, "atan2", "(DD)D");
			c.dload(6);
			c.dload(12);
			c.dmul();
			c.dload(8);
			c.dload(10);
			c.dmul();
			c.dsub();
			c.dstore(14);
			emitAsinhRealF64(c, refs, cp, 14);
		}
		else if (op == U1_ACOS) {
			// acos(z) = (2*atan2(Re(u), Re(v)), asinh(Im(conj(v)*u))) over the same two
			// roots -- not pi/2 - asin(z), which would carry asin's real part into a
			// quantity that is exactly 0 or pi on the cut.
			emitAsinAcosRootsInto(c, refs, cp, 2, 4, 6, 8, 10, 12, 14);
			emitDoubleConst(c, cp, 2.0);
			c.dload(6);
			c.dload(10);
			callMath(c, refs, cp, "atan2", "(DD)D");
			c.dmul();
			c.dload(10);
			c.dload(8);
			c.dmul();
			c.dload(12);
			c.dload(6);
			c.dmul();
			c.dsub();
			c.dstore(14);
			emitAsinhRealF64(c, refs, cp, 14);
		}
		else if (op == U1_ATAN) {
			emitDoubleConst(c, cp, 1.0);
			c.dload(4);
			c.dadd();
			c.dstore(6);
			c.dload(2);
			c.dneg();
			c.dstore(8);
			emitComplexLog(c, refs, cp, 6, 8);
			c.dstore(8);
			c.dstore(6);
			c.dload(2);
			c.dstore(10);
			emitDoubleConst(c, cp, 1.0);
			c.dload(4);
			c.dsub();
			c.dstore(2);
			c.dload(10);
			c.dstore(4);
			emitComplexLog(c, refs, cp, 2, 4);
			c.dstore(4);
			c.dstore(2);
			c.dload(4);
			c.dload(8);
			c.dsub();
			emitDoubleConst(c, cp, 2.0);
			c.ddiv();
			c.dload(6);
			c.dload(2);
			c.dsub();
			emitDoubleConst(c, cp, 2.0);
			c.ddiv();
		}
		else if (op == U1_SINH) {
			c.dload(2);
			callMath(c, refs, cp, "sinh", "(D)D");
			c.dload(4);
			callMath(c, refs, cp, "cos", "(D)D");
			c.dmul();
			c.dload(2);
			callMath(c, refs, cp, "cosh", "(D)D");
			c.dload(4);
			callMath(c, refs, cp, "sin", "(D)D");
			c.dmul();
		}
		else if (op == U1_COSH) {
			c.dload(2);
			callMath(c, refs, cp, "cosh", "(D)D");
			c.dload(4);
			callMath(c, refs, cp, "cos", "(D)D");
			c.dmul();
			c.dload(2);
			callMath(c, refs, cp, "sinh", "(D)D");
			c.dload(4);
			callMath(c, refs, cp, "sin", "(D)D");
			c.dmul();
		}
		else if (op == U1_ASINH) {
			// asinh(z) = log(z + sqrt(z^2 + 1)) with the interpreter's two
			// refinements: the +0.0 that normalizes a -0.0 imaginary part of z^2 so
			// the cut sqrt takes its +i root, and the sheet flip (a |z + s| < 1
			// answers -log(s - z), whose sum adds without cancellation).
			c.dload(2);
			c.dload(2);
			c.dmul();
			c.dload(4);
			c.dload(4);
			c.dmul();
			c.dsub();
			c.dstore(6);
			c.dload(2);
			c.dload(4);
			c.dmul();
			emitDoubleConst(c, cp, 2.0);
			c.dmul();
			c.dconst_0();
			c.dadd();
			c.dstore(8);
			c.dload(6);
			c.dconst_1();
			c.dadd();
			c.dstore(14);
			emitComplexSqrtInto(c, refs, cp, 14, 8, 10, 12, 6);
			c.dload(2);
			c.dload(10);
			c.dadd();
			c.dstore(6);
			c.dload(4);
			c.dload(12);
			c.dadd();
			c.dstore(8);
			c.dload(6);
			c.dload(6);
			c.dmul();
			c.dload(8);
			c.dload(8);
			c.dmul();
			c.dadd();
			c.dconst_1();
			c.dcmpl();
			MethodCode.Label direct = c.newLabel();
			c.ifge(direct);
			c.dload(10);
			c.dload(2);
			c.dsub();
			c.dstore(6);
			c.dload(12);
			c.dload(4);
			c.dsub();
			c.dstore(8);
			emitComplexLog(c, refs, cp, 6, 8);
			c.dstore(8);
			c.dstore(6);
			c.dload(6);
			c.dneg();
			c.dload(8);
			c.dneg();
			MethodCode.Label joined = c.newLabel();
			c.goto_(joined);
			c.labelBinding(direct);
			emitComplexLog(c, refs, cp, 6, 8);
			c.labelBinding(joined);
		}
		else if (op == U1_ACOSH) {
			// acosh(z) = 2*log(sqrt((z+1)/2) + sqrt((z-1)/2)) -- the ANSI form; the
			// /2 of the imaginary part keeps its sign, which picks the sheet at the
			// cut. The second sqrt lands in 8/2 (rd is spent by now), scratch 4.
			c.dload(4);
			emitDoubleConst(c, cp, 2.0);
			c.ddiv();
			c.dstore(6);
			c.dload(2);
			c.dconst_1();
			c.dadd();
			emitDoubleConst(c, cp, 2.0);
			c.ddiv();
			c.dstore(14);
			emitComplexSqrtInto(c, refs, cp, 14, 6, 10, 12, 8);
			c.dload(2);
			c.dconst_1();
			c.dsub();
			emitDoubleConst(c, cp, 2.0);
			c.ddiv();
			c.dstore(14);
			emitComplexSqrtInto(c, refs, cp, 14, 6, 8, 2, 4);
			c.dload(10);
			c.dload(8);
			c.dadd();
			c.dstore(6);
			c.dload(12);
			c.dload(2);
			c.dadd();
			c.dstore(8);
			emitComplexLog(c, refs, cp, 6, 8);
			c.dstore(8);
			c.dstore(6);
			c.dload(6);
			emitDoubleConst(c, cp, 2.0);
			c.dmul();
			c.dload(8);
			emitDoubleConst(c, cp, 2.0);
			c.dmul();
		}
		else if (op == U1_ATANH) {
			// atanh(z) = (log(1+z) - log(1-z)) / 2, the difference of the principal
			// logs -- the log of the quotient answers the other edge of the cut.
			c.dconst_1();
			c.dload(2);
			c.dadd();
			c.dstore(6);
			emitComplexLog(c, refs, cp, 6, 4);
			c.dstore(8);
			c.dstore(6);
			c.dconst_1();
			c.dload(2);
			c.dsub();
			c.dstore(14);
			c.dload(4);
			c.dneg();
			c.dstore(4);
			emitComplexLog(c, refs, cp, 14, 4);
			c.dstore(4);
			c.dstore(14);
			c.dload(6);
			c.dload(14);
			c.dsub();
			emitDoubleConst(c, cp, 2.0);
			c.ddiv();
			c.dload(8);
			c.dload(4);
			c.dsub();
			emitDoubleConst(c, cp, 2.0);
			c.ddiv();
		}
		else if (op == U1_CIS) {
			c.dload(4);
			c.dneg();
			callMath(c, refs, cp, "exp", "(D)D");
			c.dstore(6);
			c.dload(6);
			c.dload(2);
			callMath(c, refs, cp, "cos", "(D)D");
			c.dmul();
			c.dload(6);
			c.dload(2);
			callMath(c, refs, cp, "sin", "(D)D");
			c.dmul();
		}
		else {
			throw new IllegalArgumentException("unknown U1 op: " + op);
		}
	}

	// _ccmpb(Object a, Object b): like _cmpb, but a complex operand signals the
	// interpreter's REAL operand-type report text instead of comparing.
	private static ComplexMethod buildCCmpBits(Refs refs, ConstantPool cp, Utf8Constant name, Utf8Constant desc) {
		MethodrefConstant rCmpb = self(cp, refs.thisClass(), JvmNumericRuntimeBuilder.CMPB, CMP_DESC);
		MethodCode c = new MethodCode();
		c.aload(0);
		c.instanceOf(refs.rcClass().entry());
		MethodCode.Label ifAReal = c.newLabel();
		c.ifeq(ifAReal);
		emitRealErrThrow(c, refs, 0);
		c.labelBinding(ifAReal);
		c.aload(1);
		c.instanceOf(refs.rcClass().entry());
		MethodCode.Label ifBReal = c.newLabel();
		c.ifeq(ifBReal);
		emitRealErrThrow(c, refs, 1);
		c.labelBinding(ifBReal);
		c.aload(0);
		c.aload(1);
		c.invokestatic(rCmpb.entry());
		c.ireturn();
		return new ComplexMethod(name, desc, c);
	}

	/**
	 * Emits {@code throw _teRaw(value, "REAL")} for the value in {@code slot}.
	 */
	private static void emitRealErrThrow(MethodCode c, Refs refs, int slot) {
		c.aload(slot);
		refs.throwRefs().emitThrowLoaded(c, refs.throwRefs().realKind());
	}

	// _cphase(Object x): the angle of a complex value, 0.0 for a non-negative
	// real and pi for a negative one. A non-number signals through the _dbl
	// funnel, like the interpreter's requireReal.
	private static ComplexMethod buildCPhase(Refs refs, ConstantPool cp, Utf8Constant name, Utf8Constant desc) {
		MethodCode c = new MethodCode();
		c.aload(0);
		c.instanceOf(refs.rcClass().entry());
		MethodCode.Label ifReal = c.newLabel();
		c.ifeq(ifReal);
		c.aload(0);
		c.checkcast(refs.rcClass().entry());
		c.getfield(refs.rcImag().entry());
		c.invokestatic(refs.rDbl().entry());
		c.checkcast(refs.numberClass().entry());
		c.invokevirtual(refs.numDoubleValue().methodRefEntry());
		c.aload(0);
		c.checkcast(refs.rcClass().entry());
		c.getfield(refs.rcReal().entry());
		c.invokestatic(refs.rDbl().entry());
		c.checkcast(refs.numberClass().entry());
		c.invokevirtual(refs.numDoubleValue().methodRefEntry());
		callMath(c, refs, cp, "atan2", "(DD)D");
		emitBoxDouble(c, refs);
		c.areturn();
		c.labelBinding(ifReal);
		emitToDouble(c, refs, 0);
		c.dstore(1);
		c.dload(1);
		c.dconst_0();
		c.dcmpg();
		MethodCode.Label ifNonNeg = c.newLabel();
		c.ifge(ifNonNeg);
		emitDoubleConst(c, cp, Math.PI);
		emitBoxDouble(c, refs);
		c.areturn();
		c.labelBinding(ifNonNeg);
		c.dconst_0();
		emitBoxDouble(c, refs);
		c.areturn();
		return new ComplexMethod(name, desc, c);
	}

	// _csignum(Object x): the unit vector of a complex holder (re/|z| + (im/|z|)i
	// in floats, like the interpreter). A zero answers the canonicalization of its
	// own parts through _ccomplex (0 for exact parts, #C(0.0 0.0) for float
	// parts). Only the unconditional _signum's gated holder arm calls this, after
	// its own instanceof, so the argument is always a holder here.
	private static ComplexMethod buildCSignum(Refs refs, ConstantPool cp, Utf8Constant name, Utf8Constant desc) {
		MethodCode c = new MethodCode();
		// re = _dbl(real), im = _dbl(imag).
		c.aload(0);
		c.checkcast(refs.rcClass().entry());
		c.getfield(refs.rcReal().entry());
		c.invokestatic(refs.rDbl().entry());
		c.checkcast(refs.numberClass().entry());
		c.invokevirtual(refs.numDoubleValue().methodRefEntry());
		c.dstore(1);
		c.aload(0);
		c.checkcast(refs.rcClass().entry());
		c.getfield(refs.rcImag().entry());
		c.invokestatic(refs.rDbl().entry());
		c.checkcast(refs.numberClass().entry());
		c.invokevirtual(refs.numDoubleValue().methodRefEntry());
		c.dstore(3);
		// abs = hypot(re, im); a zero takes the canonicalize-own-parts exit.
		c.dload(1);
		c.dload(3);
		callMath(c, refs, cp, "hypot", "(DD)D");
		c.dstore(5);
		c.dload(5);
		c.dconst_0();
		c.dcmpg();
		MethodCode.Label ifNonZero = c.newLabel();
		c.ifne(ifNonZero);
		c.aload(0);
		c.checkcast(refs.rcClass().entry());
		c.getfield(refs.rcReal().entry());
		c.aload(0);
		c.checkcast(refs.rcClass().entry());
		c.getfield(refs.rcImag().entry());
		c.invokestatic(refs.rCComplex().entry());
		c.areturn();
		c.labelBinding(ifNonZero);
		// _ccomplex(Double(re/abs), Double(im/abs)).
		c.dload(1);
		c.dload(5);
		c.ddiv();
		emitBoxDouble(c, refs);
		c.dload(3);
		c.dload(5);
		c.ddiv();
		emitBoxDouble(c, refs);
		c.invokestatic(refs.rCComplex().entry());
		c.areturn();
		return new ComplexMethod(name, desc, c);
	}

	// _cconjugate(Object x): (re, -im) for a holder, the value itself for a
	// real (signalling NUMBER operand-type report otherwise).
	private static ComplexMethod buildConjugate(Refs refs, Utf8Constant name, Utf8Constant desc) {
		MethodCode c = new MethodCode();
		c.aload(0);
		c.instanceOf(refs.rcClass().entry());
		MethodCode.Label realOnly = c.newLabel();
		c.ifeq(realOnly);
		c.aload(0);
		c.checkcast(refs.rcClass().entry());
		c.getfield(refs.rcReal().entry());
		c.astore(1);
		c.aload(0);
		c.checkcast(refs.rcClass().entry());
		c.getfield(refs.rcImag().entry());
		c.invokestatic(refs.rNeg().entry());
		c.astore(2);
		c.aload(1);
		c.aload(2);
		c.invokestatic(refs.rCComplex().entry());
		c.areturn();
		c.labelBinding(realOnly);
		MethodCode.Label realOk = c.newLabel();
		emitRequireReal(c, refs, 0, realOk);
		emitNumberErrThrow(c, refs, 0);
		c.labelBinding(realOk);
		c.aload(0);
		c.areturn();
		return new ComplexMethod(name, desc, c);
	}

}
