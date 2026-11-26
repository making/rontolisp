package am.ik.rontolisp.codegen.jvm;

import java.lang.classfile.constantpool.MethodRefEntry;
import java.lang.classfile.constantpool.Utf8Entry;
import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;

/**
 * Compiles the {@code eval} built-in. The argument expression is compiled normally to
 * produce a runtime Lisp value, then the {@code _eval} runtime interpreter is invoked to
 * evaluate it in the empty (global) lexical environment.
 */
final class JvmEvalCompiler {

	private JvmEvalCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> parts = cons.toList();
		if (parts.size() != 2) {
			throw new UnsupportedOperationException("eval expects 1 argument, got " + (parts.size() - 1));
		}
		JvmExprCompiler.compileExpr(parts.get(1), ctx, className);
		// env = null (empty/global lexical environment)
		ctx.body.aconst_null();
		Utf8Entry nameUtf8 = ctx.cp.utf8Entry("_eval");
		Utf8Entry descUtf8 = ctx.cp.utf8Entry("(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
		MethodRefEntry evalRef = ctx.cp.methodRef(ctx.cp.classEntry(className), nameUtf8, descUtf8);
		ctx.body.invokestatic(evalRef);
	}

}
