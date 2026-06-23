package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispVal;
import am.ik.jvm.ConstantPool;
import am.ik.jvm.Opcode;

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
				ctx.emit(Opcode.INVOKESTATIC);
				ctx.emitU2(ctx.systemOp("currentTimeMillis").index());
				pushRawLong(1000L, ctx);
				ctx.emit(Opcode.LDIV);
				pushRawLong(UNIVERSAL_TIME_OFFSET, ctx);
				ctx.emit(Opcode.LADD);
			}
			case LispNames.GET_INTERNAL_REAL_TIME -> {
				ctx.emit(Opcode.INVOKESTATIC);
				ctx.emitU2(ctx.systemOp("currentTimeMillis").index());
			}
			case LispNames.GET_INTERNAL_RUN_TIME -> {
				ctx.emit(Opcode.INVOKESTATIC);
				ctx.emitU2(ctx.systemOp("nanoTime").index());
				pushRawLong(1000000L, ctx);
				ctx.emit(Opcode.LDIV);
			}
			default -> throw new UnsupportedOperationException("Not a time function: " + name);
		}
		// Box the long result.
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(ctx.longValueOf.index());
	}

	private static void pushRawLong(long value, JvmLispCompiler.Ctx ctx) {
		final ConstantPool.LongConstant lc = ctx.cp.addLong(value);
		ctx.emit(Opcode.LDC2_W);
		ctx.emitU2(lc.index());
	}

}
