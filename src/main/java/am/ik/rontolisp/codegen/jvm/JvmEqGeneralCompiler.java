package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;

/**
 * Compiles the {@code eq} and {@code eql} built-in functions, which are one predicate
 * ({@code .kb/eq-numbers.md}): reference equality for Object[] (cons cells) and value
 * equality for Long, Double, ratios, String, etc. Both share the per-class {@code _pEql}
 * helper over the null-safe {@code _eqv}.
 */
final class JvmEqGeneralCompiler {

	private JvmEqGeneralCompiler() {
	}

	/**
	 * Compiles {@code eql} and {@code eq} (numbers compared by type and value).
	 * @param cons the call form
	 * @param ctx the compilation context
	 * @param className the class being compiled
	 */
	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		// Evaluate both args
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		JvmExprCompiler.compileExpr(args.get(2), ctx, className);
		emitCall(ctx, className);
	}

	/**
	 * Replaces the two values on the operand stack with their {@code eq}/{@code eql}
	 * answer, {@code t} or nil -- the one comparison every compiled {@code eq} site
	 * shares, for a walk that compares inline (the {@code %remf-tail} key scan).
	 * @param ctx the compilation context
	 * @param className the class being compiled
	 */
	static void emitCall(JvmLispCompiler.Ctx ctx, String className) {
		JvmEmitHelper.emitSharedCall(ctx, className, "_pEql", 2, JvmEqGeneralCompiler::emitCompare);
	}

	// _eqv answers nil (null) itself, so the shared method only turns its int into t/nil.
	private static void emitCompare(JvmLispCompiler.Ctx ctx) {
		ctx.body.aload(0).aload(1);
		ctx.body.invokestatic(ctx.numOp(JvmNumericRuntimeBuilder.EQV));
		JvmEmitHelper.emitBoolFromInt(ctx);
	}

}
