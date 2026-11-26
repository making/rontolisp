package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispVal;

/**
 * Compiles the {@code %host-environ} internal primitive: a call of the {@code _environ}
 * runtime helper ({@link JvmEnvironRuntimeBuilder}), which answers the host's whole
 * environment as a list of {@code "NAME=value"} strings.
 */
final class JvmEnvironCompiler {

	private JvmEnvironCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> parts = cons.toList();
		if (parts.size() != 1) {
			throw new UnsupportedOperationException(
					LispNames.HOST_ENVIRON + " expects no arguments, got " + (parts.size() - 1));
		}
		ctx.body.invokestatic(ctx.cp.methodRef(ctx.cp.classEntry(className), JvmEnvironRuntimeBuilder.METHOD,
				JvmEnvironRuntimeBuilder.DESC));
	}

}
