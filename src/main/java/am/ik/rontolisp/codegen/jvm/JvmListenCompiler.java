package am.ik.rontolisp.codegen.jvm;

import java.lang.classfile.constantpool.MethodRefEntry;
import java.lang.classfile.constantpool.Utf8Entry;
import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;

/**
 * Compiles the {@code listen} built-in: whether input is immediately available on the
 * designated input stream without blocking. The optional stream designator is compiled
 * normally (absent = null = standard input) and passed to the {@code _listen} runtime
 * helper.
 */
final class JvmListenCompiler {

	private JvmListenCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> parts = cons.toList();
		if (parts.size() > 2) {
			throw new UnsupportedOperationException("listen expects 0 or 1 arguments, got " + (parts.size() - 1));
		}
		// The source designator: an omitted argument and an explicit nil both mean the
		// current *standard-input* (JvmStringStreamCompiler.inputStreamArg).
		LispVal stream = JvmStringStreamCompiler.inputStreamArg(ctx, parts.size() == 2 ? parts.get(1) : null);
		if (stream != null) {
			JvmExprCompiler.compileExpr(stream, ctx, className);
		}
		else {
			ctx.body.aconst_null();
		}
		Utf8Entry nameUtf8 = ctx.cp.utf8Entry(JvmIoRuntimeBuilder.LISTEN_METHOD);
		Utf8Entry descUtf8 = ctx.cp.utf8Entry(JvmIoRuntimeBuilder.LISTEN_DESC);
		MethodRefEntry ref = ctx.cp.methodRef(ctx.cp.classEntry(className), nameUtf8, descUtf8);
		ctx.body.invokestatic(ref);
	}

}
