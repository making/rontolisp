package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.jvm.Opcode;

/**
 * Compiles the {@code progn} special form.
 */
final class JvmPrognCompiler {

	private JvmPrognCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> parts = cons.toList();
		if (parts.size() == 1) {
			// (progn) is nil; a value must be pushed even with no body forms.
			ctx.emit(Opcode.ACONST_NULL);
			return;
		}
		for (int i = 1; i < parts.size() - 1; i++) {
			JvmExprCompiler.compileForEffect(parts.get(i), ctx, className);
		}
		JvmExprCompiler.compileExpr(parts.get(parts.size() - 1), ctx, className);
	}

}
