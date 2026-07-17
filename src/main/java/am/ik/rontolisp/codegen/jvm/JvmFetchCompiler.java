package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.jvm.Opcode;

/**
 * Compiles {@code rontolisp:fetch}: starts an outgoing HTTP request (JavaScript
 * {@code fetch}-style) and immediately returns a future whose result plist
 * {@code (:status <int> :headers <alist> :body <stream>)} is obtained via
 * {@code rontolisp:await}. The request URL is the first argument; an optional second
 * argument is an options property list ({@code :method}, {@code :headers},
 * {@code :body}). The actual work is performed by the {@code _fetch} runtime helper
 * emitted by {@link JvmFetchRuntimeBuilder}; this compiler only evaluates the arguments
 * and calls it.
 */
final class JvmFetchCompiler {

	private JvmFetchCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		if (args.size() < 2 || args.size() > 3) {
			throw new UnsupportedOperationException("fetch expects 1 or 2 arguments, got " + (args.size() - 1));
		}
		// url argument
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		// options argument (defaults to nil = null)
		if (args.size() == 3) {
			JvmExprCompiler.compileExpr(args.get(2), ctx, className);
		}
		else {
			ctx.emit(Opcode.ACONST_NULL);
		}
		if (ctx.fetchHelper == null) {
			throw new IllegalStateException("http-get helper method was not emitted");
		}
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(ctx.fetchHelper.index());
	}

}
