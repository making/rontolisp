package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.jvm.Opcode;

/**
 * Compiles {@code rontolisp:await}: blocks until the promise returned by
 * {@code rontolisp:fetch} settles and yields the property list
 * {@code (:status <int> :body <string> :headers <alist>)}. The actual work is performed
 * by the {@code _await} runtime helper emitted by {@link JvmFetchRuntimeBuilder}; this
 * compiler only evaluates the promise argument and calls it.
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
