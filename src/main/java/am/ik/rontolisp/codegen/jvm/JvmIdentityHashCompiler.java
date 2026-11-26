package am.ik.rontolisp.codegen.jvm;

import java.lang.classfile.constantpool.MethodRefEntry;
import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;

/**
 * Compiles {@code (%identity-hash x)}: {@code System.identityHashCode} of the value, the
 * hash an {@code eq} table places an aggregate by, as a {@code Long}.
 */
final class JvmIdentityHashCompiler {

	private JvmIdentityHashCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		MethodRefEntry identityHashCode = ctx.cp.methodRef(ctx.cp.classEntry("java/lang/System"), "identityHashCode",
				"(Ljava/lang/Object;)I");
		ctx.body.invokestatic(identityHashCode);
		ctx.body.i2l();
		JvmEmitHelper.boxLong(ctx);
	}

}
