package am.ik.rontolisp.codegen.jvm;

import java.lang.classfile.constantpool.MethodRefEntry;
import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispVal;

/**
 * Compiles the {@code %host-argv} internal primitive: a call of the {@code _argv} runtime
 * helper, which answers the argument vector main stored (argv0 -- the class name --
 * first) as a Lisp list of strings. The five public {@code uiop/image} names are Lisp
 * over it ({@code uiop-image.lisp}); see {@link JvmArgvRuntimeBuilder} for what the
 * vector is.
 */
final class JvmArgvCompiler {

	private JvmArgvCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> parts = cons.toList();
		if (parts.size() != 1) {
			throw new UnsupportedOperationException(
					LispNames.HOST_ARGV + " expects no arguments, got " + (parts.size() - 1));
		}
		MethodRefEntry ref = ctx.cp.methodRef(ctx.cp.classEntry(className), JvmArgvRuntimeBuilder.METHOD,
				JvmArgvRuntimeBuilder.DESC);
		ctx.body.invokestatic(ref);
	}

}
