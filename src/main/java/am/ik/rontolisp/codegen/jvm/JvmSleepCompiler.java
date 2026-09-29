package am.ik.rontolisp.codegen.jvm;

import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.MethodRefEntry;
import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispVal;

/**
 * Compiles the {@code %sleep-ms} internal primitive: park the current thread for the
 * given (positive, whole) number of milliseconds and answer nil. The seconds-to-
 * milliseconds conversion and the non-positive guard live in
 * {@link am.ik.rontolisp.macro.LispMacroExpander#expandSleep}, so this emits a
 * straight-line {@code Thread.sleep(J)}.
 *
 * <p>
 * The two constant-pool entries are added HERE rather than in the compiler's fixed
 * {@code systemOps} table, so a program that never sleeps emits the same bytes as before.
 */
final class JvmSleepCompiler {

	private JvmSleepCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> parts = cons.toList();
		if (parts.size() != 2) {
			throw new UnsupportedOperationException(
					LispNames.SLEEP_MS + " expects 1 argument, got " + (parts.size() - 1));
		}
		JvmExprCompiler.compileExpr(parts.get(1), ctx, className);
		// Number.longValue rather than Long.longValue: round answers a BigInteger for a
		// duration past the fixnum range, and a ClassCastException is not the diagnostic
		// anybody wants out of (sleep <huge>).
		MethodRefEntry longValue = ctx.cp.methodRef(ctx.numberClass, "longValue", "()J");
		ctx.body.checkcast(ctx.numberClass).invokevirtual(longValue);
		ClassEntry threadClass = ctx.cp.classEntry("java/lang/Thread");
		MethodRefEntry sleep = ctx.cp.methodRef(threadClass, "sleep", "(J)V");
		ctx.body.invokestatic(sleep).aconst_null();
	}

}
