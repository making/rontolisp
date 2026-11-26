package am.ik.rontolisp.codegen.jvm;

import am.ik.rontolisp.LispCons;

/**
 * Compiles {@code string-capitalize}: capitalizes the first letter of each alphanumeric
 * word and lowercases the rest, on the shared per-code-point walk in
 * {@link JvmStringCaseFold}.
 */
final class JvmStringCapitalizeCompiler {

	private JvmStringCapitalizeCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		JvmStringCaseFold.compile(cons, ctx, className, JvmStringCaseFold.Mode.CAPITALIZE);
	}

}
