package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.jvm.ConstantPool.ClassConstant;

/**
 * Compiles the {@code integerp} predicate. An integer is represented at runtime as either
 * a {@code Long} or a {@code BigInteger}.
 */
final class JvmIntegerpCompiler {

	private JvmIntegerpCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		ClassConstant bigClass = ctx.cp.addClass(ctx.cp.addUtf8("java/math/BigInteger"));
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		int temp = ctx.allocTemp();
		ctx.body.astore(temp).aload(temp).instanceOf(ctx.longClass.entry()).aload(temp);
		ctx.body.instanceOf(bigClass.entry()).ior();
		JvmEmitHelper.emitBoolFromInt(ctx);
	}

}
