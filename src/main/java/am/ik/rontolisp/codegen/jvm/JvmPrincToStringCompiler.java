package am.ik.rontolisp.codegen.jvm;

import java.lang.classfile.constantpool.MethodRefEntry;
import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;

/**
 * Compiles the {@code princ-to-string} built-in function: the string that {@code princ}
 * would print. The runtime string representation carries surrounding quotes, so the
 * display text from {@code _lispToDisplayString} is wrapped in {@code "}.
 */
final class JvmPrincToStringCompiler {

	private JvmPrincToStringCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		emitToString(args.get(1), ctx.lispToDisplayString, ctx, className);
	}

	/**
	 * Emits {@code "\"".concat(toString(value)).concat("\"")} where {@code toString} is
	 * the given static helper ({@code _lispToDisplayString} or {@code _lispToString}).
	 */
	static void emitToString(LispVal value, MethodRefEntry toString, JvmLispCompiler.Ctx ctx, String className) {
		MethodRefEntry concat = JvmEmitHelper.stringMethod(ctx, "concat", "(Ljava/lang/String;)Ljava/lang/String;");
		JvmEmitHelper.compileStringLiteral("\"", ctx);
		JvmExprCompiler.compileExpr(value, ctx, className);
		ctx.body.invokestatic(toString).invokevirtual(concat);
		JvmEmitHelper.compileStringLiteral("\"", ctx);
		ctx.body.invokevirtual(concat);
	}

}
