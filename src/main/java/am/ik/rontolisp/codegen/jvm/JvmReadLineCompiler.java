package am.ik.rontolisp.codegen.jvm;

import am.ik.rontolisp.LispCons;
import am.ik.jvm.Opcode;

/**
 * Compiles the {@code read-line} built-in function.
 */
final class JvmReadLineCompiler {

	private JvmReadLineCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(ctx.readLineHelper.index());
	}

}
