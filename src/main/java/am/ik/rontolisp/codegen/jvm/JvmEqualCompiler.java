package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;

/**
 * Compiles the {@code equal} built-in function (structural equality). Cons cells are
 * compared recursively by car and cdr; everything else (numbers, symbols, strings, nil)
 * falls back to {@code eql} semantics. The recursive {@code _equal} runtime method
 * handles null (nil) itself, so this compiler only evaluates the two arguments and calls
 * it.
 */
final class JvmEqualCompiler {

	private JvmEqualCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		JvmExprCompiler.compileExpr(args.get(2), ctx, className);
		ctx.body.invokestatic(ctx.numOp(JvmNumericRuntimeBuilder.EQUAL));
		JvmEmitHelper.emitBoolFromInt(ctx);
	}

}
