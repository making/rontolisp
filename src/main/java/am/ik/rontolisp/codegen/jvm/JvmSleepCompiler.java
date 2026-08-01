package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.jvm.ConstantPool.ClassConstant;
import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.Opcode;
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
		MethodrefConstant longValue = ctx.cp.addMethodref(ctx.numberClass,
				ctx.cp.addNameAndType(ctx.cp.addUtf8("longValue"), ctx.cp.addUtf8("()J")));
		ctx.emit(Opcode.CHECKCAST);
		ctx.emitU2(ctx.numberClass.index());
		ctx.emit(Opcode.INVOKEVIRTUAL);
		ctx.emitU2(longValue.index());
		ClassConstant threadClass = ctx.cp.addClass(ctx.cp.addUtf8("java/lang/Thread"));
		MethodrefConstant sleep = ctx.cp.addMethodref(threadClass,
				ctx.cp.addNameAndType(ctx.cp.addUtf8("sleep"), ctx.cp.addUtf8("(J)V")));
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(sleep.index());
		ctx.emit(Opcode.ACONST_NULL);
	}

}
