package am.ik.rontolisp.codegen.jvm;

import java.lang.classfile.constantpool.MethodRefEntry;
import java.lang.classfile.constantpool.Utf8Entry;
import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.macro.LispMacroExpander;

/**
 * Compiles the {@code load} built-in. The path argument is compiled to a runtime string
 * value, then the {@code _load} runtime helper reads the file, parses every top-level
 * datum, and evaluates each in the global environment via the {@code _eval} runtime.
 */
final class JvmLoadCompiler {

	private JvmLoadCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		LispVal options = LispMacroExpander.lowerLoadOptions(cons);
		if (options != null) {
			// CL's keyword options: bound in order, then dropped except
			// :if-does-not-exist, which becomes the probe-file guard.
			JvmExprCompiler.compileExpr(options, ctx, className);
			return;
		}
		List<LispVal> parts = cons.toList();
		if (parts.size() != 2) {
			throw new UnsupportedOperationException("load expects 1 argument, got " + (parts.size() - 1));
		}
		JvmExprCompiler.compileExpr(parts.get(1), ctx, className);
		// A path built by a flipped producer (concatenate, format nil) is a mutable
		// character vector: render it before _load's (String) cast (a no-op without
		// the array runtime).
		JvmArrayCompiler.emitStrvNormalize(ctx, className);
		Utf8Entry nameUtf8 = ctx.cp.utf8Entry("_load");
		Utf8Entry descUtf8 = ctx.cp.utf8Entry("(Ljava/lang/Object;)Ljava/lang/Object;");
		MethodRefEntry loadRef = ctx.cp.methodRef(ctx.cp.classEntry(className), nameUtf8, descUtf8);
		ctx.body.invokestatic(loadRef);
	}

}
