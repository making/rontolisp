package am.ik.rontolisp.codegen.jvm;

import java.lang.classfile.constantpool.LongEntry;
import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispVal;
import am.ik.jvm.ConstantPool;

/**
 * Compiles the time built-in functions for the JVM, each taking no arguments and
 * returning a boxed {@code Long}: {@code get-universal-time} (seconds since 1900-01-01
 * GMT, the Common Lisp epoch), {@code get-internal-real-time} (wall-clock milliseconds)
 * and {@code get-internal-run-time} (run-time milliseconds). They read {@code
 * System.currentTimeMillis} / {@code System.nanoTime}.
 */
final class JvmTimeCompiler {

	// Seconds between the Common Lisp epoch (1900-01-01) and the Unix epoch (1970-01-01).
	private static final long UNIVERSAL_TIME_OFFSET = 2208988800L;

	private JvmTimeCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String name) {
		List<LispVal> args = cons.toList();
		if (args.size() != 1) {
			throw new UnsupportedOperationException(name + " expects 0 arguments, got " + (args.size() - 1));
		}
		switch (name) {
			case LispNames.GET_UNIVERSAL_TIME -> {
				ctx.body.invokestatic(ctx.systemOp("currentTimeMillis"));
				pushRawLong(1000L, ctx);
				ctx.body.ldiv();
				pushRawLong(UNIVERSAL_TIME_OFFSET, ctx);
				ctx.body.ladd();
			}
			case LispNames.GET_INTERNAL_REAL_TIME -> {
				ctx.body.invokestatic(ctx.systemOp("currentTimeMillis"));
			}
			case LispNames.GET_INTERNAL_RUN_TIME -> {
				ctx.body.invokestatic(ctx.systemOp("nanoTime"));
				pushRawLong(1000000L, ctx);
				ctx.body.ldiv();
			}
			default -> throw new UnsupportedOperationException("Not a time function: " + name);
		}
		// Box the long result.
		ctx.body.invokestatic(ctx.longValueOf);
	}

	private static void pushRawLong(long value, JvmLispCompiler.Ctx ctx) {
		final LongEntry lc = ctx.cp.entries().longEntry(value);
		ctx.body.ldc(lc);
	}

}
