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
 * {@code atan}, {@code sinh}, {@code cosh}, {@code tanh}). Each delegates to the matching
 * {@code java.lang.Math} method and always returns a double.
 */
final class JvmMathFnCompiler {

	/** Key for {@code Math.pow(D,D)D} in the math ops map. */
	static final String POW = "pow";

	/** Key for {@code Math.signum(D)D} in the math ops map. */
	static final String SIGNUM_D = "signum.d";

	/** Key for {@code Math.random()D} in the math ops map. */
	static final String RANDOM = "random";

	/**
	 * The Lisp names handled by this compiler, each mapping to {@code Math.<name>(D)D}.
	 */
	private static final List<String> UNARY_NAMES = List.of(LispNames.SQRT, LispNames.EXP, LispNames.LOG, LispNames.SIN,
			LispNames.COS, LispNames.TAN, LispNames.ASIN, LispNames.ACOS, LispNames.ATAN, LispNames.SINH,
			LispNames.COSH, LispNames.TANH);

	private JvmMathFnCompiler() {
	}

	/**
	 * Builds the {@code java.lang.Math} method references used by the math compilers.
	 * @param cp the constant pool to populate
	 * @param mathClass the {@code java/lang/Math} class constant
	 * @return references keyed by Lisp name (for the unary functions), plus {@link #POW}
	 * and {@link #SIGNUM_D}
	 */
	static Map<String, MethodrefConstant> buildOps(ConstantPool cp, ClassConstant mathClass) {
		Map<String, MethodrefConstant> ops = new LinkedHashMap<>();
		for (String name : UNARY_NAMES) {
			ops.put(name, cp.addMethodref(mathClass, cp.addNameAndType(cp.addUtf8(name), cp.addUtf8("(D)D"))));
		}
		ops.put(POW, cp.addMethodref(mathClass, cp.addNameAndType(cp.addUtf8("pow"), cp.addUtf8("(DD)D"))));
		ops.put(SIGNUM_D, cp.addMethodref(mathClass, cp.addNameAndType(cp.addUtf8("signum"), cp.addUtf8("(D)D"))));
		ops.put(RANDOM, cp.addMethodref(mathClass, cp.addNameAndType(cp.addUtf8("random"), cp.addUtf8("()D"))));
		return ops;
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className, String name) {
		List<LispVal> args = cons.toList();
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		// Number.doubleValue() coerces both Long and Double arguments to double.
		JvmEmitHelper.unboxDouble(ctx);
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(ctx.mathOp(name).index());
		JvmEmitHelper.boxDouble(ctx);
	}

}
