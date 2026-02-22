package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.jvm.Opcode;

/**
 * Compiles the {@code append} built-in function.
 */
final class JvmAppendCompiler {

	private JvmAppendCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		int argCount = args.size() - 1; // exclude 'append' itself
		if (argCount == 0) {
			ctx.emit(Opcode.ACONST_NULL);
			return;
		}
		if (argCount == 1) {
			JvmExprCompiler.compileExpr(args.get(1), ctx, className);
			return;
		}
		// Compile all arguments
		for (int i = 1; i <= argCount; i++) {
			JvmExprCompiler.compileExpr(args.get(i), ctx, className);
		}
		// Right-fold: call _append N-1 times from right to left
		for (int i = 0; i < argCount - 1; i++) {
			ctx.emit(Opcode.INVOKESTATIC);
			ctx.emitU2(ctx.appendMethod.index());
		}
	}

}
