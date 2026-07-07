package am.ik.rontolisp.codegen.jvm;

import am.ik.rontolisp.LispCons;
import am.ik.jvm.Opcode;

/**
 * Compiles {@code string-upcase} / {@code string-downcase}. The argument is coerced to a
 * quoted string designator ({@code "abc"}); since the quote byte is not a letter,
 * transforming the whole string leaves the quotes untouched, so
 * {@code String.toUpperCase} / {@code String.toLowerCase} produces the correct quoted
 * result directly.
 */
final class JvmStringUpcaseCompiler {

	private JvmStringUpcaseCompiler() {
	}

	static void compileUpcase(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		compile(cons, ctx, className, "toUpperCase");
	}

	static void compileDowncase(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		compile(cons, ctx, className, "toLowerCase");
	}

	private static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className, String method) {
		int op = JvmEmitHelper.stringMethod(ctx, method, "()Ljava/lang/String;").index();
		// Coerce a string designator (string / symbol / keyword) to a quoted string
		// first;
		// case-folding the whole quoted value leaves the quote bytes untouched.
		JvmStringDesignatorHelper.emitCoerce(cons, ctx, className);
		ctx.emit(Opcode.INVOKEVIRTUAL);
		ctx.emitU2(op);
	}

}
