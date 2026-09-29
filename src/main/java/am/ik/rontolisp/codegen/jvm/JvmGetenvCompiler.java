package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispVal;
import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.MethodCode;

/**
 * Compiles the {@code %host-getenv} internal primitive: the HOST's value for an
 * environment variable as a string, or {@code nil} (a {@code null} reference at runtime)
 * when it is unset. The argument is a runtime string, which carries surrounding quotes
 * ({@code "PATH"}); the quotes are stripped before calling {@code System.getenv} and
 * re-applied to a non-null result so it is a proper Lisp string. The public
 * {@code uiop:getenv} is Lisp over this ({@code uiop-os.lisp}), consulting the override
 * map a {@code (setf (uiop:getenv ...))} wrote first.
 */
final class JvmGetenvCompiler {

	private JvmGetenvCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		if (args.size() != 2) {
			throw new UnsupportedOperationException(
					LispNames.HOST_GETENV + " expects 1 argument, got " + (args.size() - 1));
		}
		final MethodrefConstant length = JvmEmitHelper.stringMethod(ctx, "length", "()I");
		final MethodrefConstant substring = JvmEmitHelper.stringMethod(ctx, "substring", "(II)Ljava/lang/String;");
		final MethodrefConstant concat = JvmEmitHelper.stringMethod(ctx, "concat",
				"(Ljava/lang/String;)Ljava/lang/String;");

		JvmExprCompiler.compileExpr(args.get(1), ctx, className); // [s]
		// A variable name built by a string producer (concatenate, format nil) is a
		// mutable character vector: render it before the (String) cast (a no-op
		// without the array runtime).
		JvmArrayCompiler.emitStrvNormalize(ctx, className);
		ctx.body.checkcast(ctx.stringClass.entry());
		// name = s.substring(1, s.length() - 1)
		ctx.body.dup(); // [s, s]
		ctx.body.invokevirtual(length.methodRefEntry()); // [s, len]
		ctx.body.iconst_1();
		ctx.body.isub(); // [s, len-1]
		ctx.body.iconst_1();
		ctx.body.swap(); // [s, 1, len-1]
		ctx.body.invokevirtual(substring.methodRefEntry()); // [name]
		// System.getenv(name)
		ctx.body.invokestatic(ctx.systemOp("getenv").entry()); // [value|null]
		ctx.body.dup(); // [value, value]
		MethodCode.Label end = ctx.body.newLabel();
		ctx.body.ifnull(end);
		// non-null: wrap as "\"" + value + "\""
		JvmEmitHelper.compileStringLiteral("\"", ctx); // [value, q]
		ctx.body.swap(); // [q, value]
		ctx.body.invokevirtual(concat.methodRefEntry()); // [q+value]
		JvmEmitHelper.compileStringLiteral("\"", ctx); // [.., q]
		ctx.body.invokevirtual(concat.methodRefEntry()); // [quoted]
		ctx.body.goto_(end);
		// null path: leave the null (nil) on the stack
		ctx.body.labelBinding(end);
	}

}
