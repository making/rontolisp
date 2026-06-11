package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.jvm.Opcode;

/**
 * Compiles {@code string-upcase} / {@code string-downcase}. The runtime string carries
 * surrounding quotes ({@code "abc"}); since the quote byte is not a letter, transforming
 * the whole string leaves the quotes untouched, so {@code String.toUpperCase} /
 * {@code String.toLowerCase} produces the correct quoted result directly.
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
		List<LispVal> args = cons.toList();
		int op = JvmEmitHelper.stringMethod(ctx, method, "()Ljava/lang/String;").index();
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		ctx.emit(Opcode.CHECKCAST);
		ctx.emitU2(ctx.stringClass.index());
		ctx.emit(Opcode.INVOKEVIRTUAL);
		ctx.emitU2(op);
	}

}
