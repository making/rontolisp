package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;

/**
 * Compiles the {@code prin1-to-string} built-in function: the string that {@code prin1}
 * would print (readable form, strings quoted).
 */
final class JvmPrin1ToStringCompiler {

	private JvmPrin1ToStringCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		JvmPrincToStringCompiler.emitToString(args.get(1), ctx.lispToString.index(), ctx, className);
	}

}
