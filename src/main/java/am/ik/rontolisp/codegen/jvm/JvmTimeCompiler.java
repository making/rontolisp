package am.ik.rontolisp.codegen.jvm;

import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.LongEntry;
import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispVal;
import am.ik.jvm.ConstantPool;

/**
 * Compiles the time built-in functions for the JVM, each taking no arguments and
 * returning a boxed {@code Long}: {@code get-universal-time} (seconds since 1900-01-01
 * GMT, the Common Lisp epoch), {@code get-internal-real-time} (wall-clock milliseconds),
 * {@code get-internal-run-time} (run-time milliseconds), {@code %nano-time} (the
 * monotonic nanoseconds) and {@code %unix-nano-time} (the wall clock's nanoseconds since
 * the Unix epoch). They read {@code System.currentTimeMillis} / {@code System.nanoTime} /
 * {@code Instant.now}.
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
			case LispNames.NANO_TIME -> ctx.body.invokestatic(ctx.systemOp("nanoTime"));
			case LispNames.UNIX_NANO_TIME -> {
				// ChronoUnit.NANOS.between(Instant.EPOCH, Instant.now()): exact in a long
				// until 2262.
				ClassEntry chronoUnit = ctx.cp.classEntry("java/time/temporal/ChronoUnit");
				ClassEntry instant = ctx.cp.classEntry("java/time/Instant");
				ctx.body.getstatic(ctx.cp.fieldRef(chronoUnit, "NANOS", "Ljava/time/temporal/ChronoUnit;"));
				ctx.body.getstatic(ctx.cp.fieldRef(instant, "EPOCH", "Ljava/time/Instant;"));
				ctx.body.invokestatic(ctx.cp.methodRef(instant, "now", "()Ljava/time/Instant;"));
				ctx.body.invokevirtual(ctx.cp.methodRef(chronoUnit, "between",
						"(Ljava/time/temporal/Temporal;Ljava/time/temporal/Temporal;)J"));
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
