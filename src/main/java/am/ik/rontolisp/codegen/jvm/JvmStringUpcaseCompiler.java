package am.ik.rontolisp.codegen.jvm;

import am.ik.rontolisp.LispCons;

/**
 * Compiles {@code string-upcase} / {@code string-downcase} onto the shared per-code-point
 * walk in {@link JvmStringCaseFold}.
 */
final class JvmStringUpcaseCompiler {

	private JvmStringUpcaseCompiler() {
	}

	static void compileUpcase(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		JvmStringCaseFold.compile(cons, ctx, className, JvmStringCaseFold.Mode.UPCASE);
	}

	static void compileDowncase(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		JvmStringCaseFold.compile(cons, ctx, className, JvmStringCaseFold.Mode.DOWNCASE);
	}

}
