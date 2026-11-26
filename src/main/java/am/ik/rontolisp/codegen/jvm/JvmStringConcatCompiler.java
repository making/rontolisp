package am.ik.rontolisp.codegen.jvm;

import java.lang.classfile.constantpool.MethodRefEntry;
import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;

/**
 * Compiles the internal {@code %string-concat} built-in function. Runtime strings carry
 * surrounding quotes ({@code "abc"}), so the concatenation of {@code a} and {@code b} is
 * {@code a.substring(0, a.length() - 1).concat(b.substring(1, b.length()))}: drop the
 * closing quote of {@code a} and the opening quote of {@code b}.
 */
final class JvmStringConcatCompiler {

	private JvmStringConcatCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		MethodRefEntry length = JvmEmitHelper.stringMethod(ctx, "length", "()I");
		MethodRefEntry substring = JvmEmitHelper.stringMethod(ctx, "substring", "(II)Ljava/lang/String;");
		MethodRefEntry concat = JvmEmitHelper.stringMethod(ctx, "concat", "(Ljava/lang/String;)Ljava/lang/String;");
		// a.substring(0, a.length() - 1)
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		JvmArrayCompiler.emitStrvNormalize(ctx, className);
		ctx.body.checkcast(ctx.stringClass).dup().invokevirtual(length);
		ctx.body.iconst_1().isub().iconst_0().swap().invokevirtual(substring);
		// b.substring(1, b.length())
		JvmExprCompiler.compileExpr(args.get(2), ctx, className);
		JvmArrayCompiler.emitStrvNormalize(ctx, className);
		ctx.body.checkcast(ctx.stringClass).dup().invokevirtual(length);
		ctx.body.iconst_1().swap().invokevirtual(substring).invokevirtual(concat);
	}

}
