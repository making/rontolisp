package am.ik.rontolisp.codegen.jvm;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispVal;
import am.ik.jvm.ConstantPool;
import am.ik.jvm.ConstantPool.ClassConstant;
import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.Opcode;

/**
 * Compiles the unary floating-point math built-ins ({@code sqrt}, {@code exp},
 * {@code log}, {@code sin}, {@code cos}, {@code tan}, {@code asin}, {@code acos},
 * {@code atan}, {@code sinh}, {@code cosh}, {@code tanh}). {@code sqrt} is
 * {@code Math.sqrt} (correctly rounded, so one value everywhere); every transcendental
 * delegates to the matching {@code java.lang.StrictMath} method -- fdlibm, the one
 * algorithm the interpreter, the JVM and the WASM backends share, so the bits agree on
 * every backend and every CPU ({@code .kb/transcendentals.md}). Each always returns a
 * double.
 *
 * <p>
 * Two of them carry an optional SECOND argument: {@code (atan y x)} is
 * {@code StrictMath.atan2(y, x)} and {@code (log n base)} the quotient of the two
 * logarithms. Both sit in {@link #compileBinary}, ahead of the one-argument path, which
 * stays exactly as it was.
 */
final class JvmMathFnCompiler {

	/** Key for {@code StrictMath.pow(D,D)D} in the math ops map. */
	static final String POW = "pow";

	/**
	 * Key for {@code StrictMath.atan2(D,D)D} in the math ops map -- the two-argument
	 * {@code atan}. It is the same quadrant assembly {@code phase} answers with, so the
	 * signed zeros and the full circle come for free.
	 */
	static final String ATAN2 = "atan2";

	/** Key for {@code Math.signum(D)D} in the math ops map. */
	static final String SIGNUM_D = "signum.d";

	/**
	 * Key for {@code ThreadLocalRandom.current()} in the math ops map -- the first half
	 * of the entropy source behind {@code random} (see {@link #TLR_NEXT_DOUBLE}).
	 */
	static final String TLR_CURRENT = "tlr.current";

	/**
	 * Key for {@code ThreadLocalRandom.nextDouble()D} in the math ops map. Together with
	 * {@link #TLR_CURRENT} this is what {@code random} draws from, on the compile path
	 * and in the interpreter alike: {@code Math.random()} is one process-wide
	 * {@code java.util.Random} whose 48-bit seed advances by a {@code compareAndSet} on a
	 * shared {@code AtomicLong}, so every draw pays a CAS and a memory fence for state
	 * nothing shares. The per-thread generator costs neither and is a strictly better
	 * generator; {@code .kb/random.md} records why CL's contract allows the swap.
	 */
	static final String TLR_NEXT_DOUBLE = "tlr.nextDouble";

	/**
	 * The Lisp names handled by this compiler, each mapping to {@code Math.<name>(D)D}.
	 */
	private static final List<String> UNARY_NAMES = List.of(LispNames.SQRT, LispNames.EXP, LispNames.LOG, LispNames.SIN,
			LispNames.COS, LispNames.TAN, LispNames.ASIN, LispNames.ACOS, LispNames.ATAN, LispNames.SINH,
			LispNames.COSH, LispNames.TANH);

	private JvmMathFnCompiler() {
	}

	/**
	 * Builds the {@code java.lang.Math} / {@code java.lang.StrictMath} method references
	 * used by the math compilers: {@code sqrt} and {@code signum} on {@code Math}, every
	 * transcendental on {@code StrictMath}.
	 * @param cp the constant pool to populate
	 * @param mathClass the {@code java/lang/Math} class constant
	 * @return references keyed by Lisp name (for the unary functions), plus {@link #POW},
	 * {@link #ATAN2}, {@link #SIGNUM_D} and the two {@code ThreadLocalRandom} halves
	 */
	static Map<String, MethodrefConstant> buildOps(ConstantPool cp, ClassConstant mathClass) {
		ClassConstant strictMathClass = cp.addClass(cp.addUtf8("java/lang/StrictMath"));
		Map<String, MethodrefConstant> ops = new LinkedHashMap<>();
		for (String name : UNARY_NAMES) {
			// The map key is the (uppercase-canonical) Lisp name; the Java method name
			// is its lowercase spelling (StrictMath.sin, not StrictMath.SIN).
			ops.put(name, cp.addMethodref(LispNames.SQRT.equals(name) ? mathClass : strictMathClass,
					cp.addNameAndType(cp.addUtf8(name.toLowerCase(java.util.Locale.ROOT)), cp.addUtf8("(D)D"))));
		}
		ops.put(POW, cp.addMethodref(strictMathClass, cp.addNameAndType(cp.addUtf8("pow"), cp.addUtf8("(DD)D"))));
		ops.put(ATAN2, cp.addMethodref(strictMathClass, cp.addNameAndType(cp.addUtf8("atan2"), cp.addUtf8("(DD)D"))));
		ops.put(SIGNUM_D, cp.addMethodref(mathClass, cp.addNameAndType(cp.addUtf8("signum"), cp.addUtf8("(D)D"))));
		ClassConstant tlrClass = cp.addClass(cp.addUtf8("java/util/concurrent/ThreadLocalRandom"));
		ops.put(TLR_CURRENT, cp.addMethodref(tlrClass,
				cp.addNameAndType(cp.addUtf8("current"), cp.addUtf8("()Ljava/util/concurrent/ThreadLocalRandom;"))));
		ops.put(TLR_NEXT_DOUBLE,
				cp.addMethodref(tlrClass, cp.addNameAndType(cp.addUtf8("nextDouble"), cp.addUtf8("()D"))));
		return ops;
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className, String name) {
		List<LispVal> args = cons.toList();
		if (args.size() == 3 && (LispNames.ATAN.equals(name) || LispNames.LOG.equals(name))) {
			compileBinary(args, ctx, className, name);
			return;
		}
		if (args.size() != 2) {
			throw new UnsupportedOperationException(
					name + " expects " + (LispNames.ATAN.equals(name) || LispNames.LOG.equals(name) ? "1 or 2" : "1")
							+ " arguments, got " + (args.size() - 1));
		}
		if (JvmLispCompiler.hasComplexOperand(args)
				|| am.ik.rontolisp.macro.LispMacroExpander.escapesToComplex(name, args)) {
			// A complex operand answers the float complex formula through the
			// gated _cu1 helper (`.kb/jvm-complex.md`); so does a log/asin/acos
			// whose argument may leave the real domain at run time, because _cu1
			// carries the escape and java.lang.Math would answer NaN. Anything else
			// keeps the inline Math call below.
			JvmExprCompiler.compileExpr(args.get(1), ctx, className);
			ctx.emit(Opcode.BIPUSH);
			ctx.emit(u1Op(name));
			ctx.emit(Opcode.INVOKESTATIC);
			ctx.emitU2(JvmComplexCompiler.complexOp(ctx, className, JvmComplexRuntimeBuilder.U1).index());
			return;
		}
		// Number.doubleValue() coerces both Long and Double arguments to double.
		JvmArithCompiler.compileUnboxedOperand(args.get(1), ctx, className);
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(ctx.mathOp(name).index());
		JvmEmitHelper.boxDouble(ctx);
	}

	/**
	 * The two-argument forms: {@code (atan y x)} is {@code Math.atan2}, and
	 * {@code (log n base)} the quotient of the two logarithms. Neither disturbs the
	 * one-argument path above, which is the hot one.
	 * @param args the whole call form as a list, the head included
	 * @param ctx the compile context
	 * @param className the class being emitted
	 * @param name {@code ATAN} or {@code LOG}
	 */
	private static void compileBinary(List<LispVal> args, JvmLispCompiler.Ctx ctx, String className, String name) {
		if (LispNames.ATAN.equals(name)) {
			// Both arguments must be REAL (CLHS). A complex reaching the f64
			// coercion is not silently reduced to its real part: _dbl throws the
			// interpreter's "Expected real number" there.
			JvmArithCompiler.compileUnboxedOperand(args.get(1), ctx, className);
			JvmArithCompiler.compileUnboxedOperand(args.get(2), ctx, className);
			ctx.emit(Opcode.INVOKESTATIC);
			ctx.emitU2(ctx.mathOp(ATAN2).index());
			JvmEmitHelper.boxDouble(ctx);
			return;
		}
		if (JvmLispCompiler.hasComplexOperand(args)
				|| am.ik.rontolisp.macro.LispMacroExpander.escapesToComplex(name, args)) {
			// Either logarithm may itself have left the real domain, so both go
			// through the gated _cu1 and the quotient through _cdiv -- the same pair
			// of helpers (/ (log n) (log base)) would reach with a complex operand.
			compileLogThroughU1(args.get(1), ctx, className);
			compileLogThroughU1(args.get(2), ctx, className);
			ctx.emit(Opcode.INVOKESTATIC);
			ctx.emitU2(JvmComplexCompiler.complexOp(ctx, className, JvmComplexRuntimeBuilder.DIV).index());
			return;
		}
		JvmArithCompiler.compileUnboxedOperand(args.get(1), ctx, className);
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(ctx.mathOp(LispNames.LOG).index());
		JvmArithCompiler.compileUnboxedOperand(args.get(2), ctx, className);
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(ctx.mathOp(LispNames.LOG).index());
		ctx.emit(Opcode.DDIV);
		JvmEmitHelper.boxDouble(ctx);
	}

	/** One {@code log} of the argument through the gated {@code _cu1} helper. */
	private static void compileLogThroughU1(LispVal arg, JvmLispCompiler.Ctx ctx, String className) {
		JvmExprCompiler.compileExpr(arg, ctx, className);
		ctx.emit(Opcode.BIPUSH);
		ctx.emit(JvmComplexRuntimeBuilder.U1_LOG);
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(JvmComplexCompiler.complexOp(ctx, className, JvmComplexRuntimeBuilder.U1).index());
	}

	/**
	 * The four functions whose REAL arguments can answer a complex (acosh/atanh escape
	 * their real domain, cis always does), plus the asinh whose real arm is hand-rolled
	 * because {@code java.lang.Math} has no inverse hyperbolic: every call site goes
	 * through {@code _cu1}, like {@code sqrt} through {@code _csqrt}.
	 * @param cons the call form
	 * @param ctx the compile context
	 * @param className the class being emitted
	 * @param name one of {@code ASINH}, {@code ACOSH}, {@code ATANH}, {@code CIS}
	 */
	static void compileAlwaysComplex(LispCons cons, JvmLispCompiler.Ctx ctx, String className, String name) {
		List<LispVal> args = cons.toList();
		int got = args.size() - 1;
		if (got != 1) {
			throw new UnsupportedOperationException(name + " expects 1 argument(s), got " + got);
		}
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		ctx.emit(Opcode.BIPUSH);
		ctx.emit(switch (name) {
			case LispNames.ASINH -> JvmComplexRuntimeBuilder.U1_ASINH;
			case LispNames.ACOSH -> JvmComplexRuntimeBuilder.U1_ACOSH;
			case LispNames.ATANH -> JvmComplexRuntimeBuilder.U1_ATANH;
			case LispNames.CIS -> JvmComplexRuntimeBuilder.U1_CIS;
			default -> throw new IllegalArgumentException("not an always-complex-capable unary: " + name);
		});
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(JvmComplexCompiler.complexOp(ctx, className, JvmComplexRuntimeBuilder.U1).index());
	}

	/** The {@code _cu1} opcode selecting the formula for a Lisp name. */
	private static int u1Op(String name) {
		return switch (name) {
			case LispNames.EXP -> JvmComplexRuntimeBuilder.U1_EXP;
			case LispNames.LOG -> JvmComplexRuntimeBuilder.U1_LOG;
			case LispNames.SIN -> JvmComplexRuntimeBuilder.U1_SIN;
			case LispNames.COS -> JvmComplexRuntimeBuilder.U1_COS;
			case LispNames.TAN -> JvmComplexRuntimeBuilder.U1_TAN;
			case LispNames.ASIN -> JvmComplexRuntimeBuilder.U1_ASIN;
			case LispNames.ACOS -> JvmComplexRuntimeBuilder.U1_ACOS;
			case LispNames.ATAN -> JvmComplexRuntimeBuilder.U1_ATAN;
			case LispNames.SINH -> JvmComplexRuntimeBuilder.U1_SINH;
			case LispNames.COSH -> JvmComplexRuntimeBuilder.U1_COSH;
			case LispNames.TANH -> JvmComplexRuntimeBuilder.U1_TANH;
			default -> throw new IllegalArgumentException("not a unary math function: " + name);
		};
	}

}
