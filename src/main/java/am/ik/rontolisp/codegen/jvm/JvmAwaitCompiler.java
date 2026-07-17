package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.jvm.Opcode;

/**
 * Compiles {@code rontolisp:await}: resolves a future to its settled value (blocking the
 * current -- possibly virtual -- thread while it is pending). The actual work is
 * performed by the {@code _await} runtime helper emitted by
 * {@code JvmAsyncRuntimeBuilder}; this compiler only evaluates the future argument and
 * calls it.
 */
final class JvmAwaitCompiler {

	private JvmAwaitCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		if (args.size() != 2) {
			throw new UnsupportedOperationException("await expects 1 argument, got " + (args.size() - 1));
		}
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		if (ctx.awaitHelper == null) {
			throw new IllegalStateException("await helper method was not emitted");
		}
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(ctx.awaitHelper.index());
	}

}
