package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.Opcode;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispVal;

/**
 * Compiles the {@code %host-exit} internal primitive: {@code System.exit(status)}. The
 * public {@code uiop:quit} is Lisp over this ({@code uiop-image.lisp}) and finishes the
 * output streams before calling it, so the four backends share one definition of what
 * quitting means.
 *
 * <p>
 * This is the one place a generated class ends the JVM it runs in, and it is deliberate:
 * the uncaught-condition handler ({@link JvmUncaughtHandler}) rethrows instead of exiting
 * because the program did NOT ask to stop the process, while {@code quit} is the program
 * asking for exactly that. A caller embedding a compiled {@code main} therefore has to
 * know that a program calling {@code quit} takes the JVM with it -- which is what
 * {@code quit} means on every other backend too.
 *
 * <p>
 * The constant-pool entries are minted HERE rather than in the compiler's fixed
 * {@code systemOps} table ({@link JvmSleepCompiler}'s rule), so a program that never
 * quits emits the same bytes as before.
 */
final class JvmExitCompiler {

	private JvmExitCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> parts = cons.toList();
		if (parts.size() != 2) {
			throw new UnsupportedOperationException(
					LispNames.HOST_EXIT + " expects 1 argument, got " + (parts.size() - 1));
		}
		JvmExprCompiler.compileExpr(parts.get(1), ctx, className);
		// Number.intValue rather than Long.intValue: uiop:quit's code is whatever
		// arithmetic produced it, and a ClassCastException is not the diagnostic anybody
		// wants out of an exit status.
		MethodrefConstant intValue = ctx.cp.addMethodref(ctx.numberClass,
				ctx.cp.addNameAndType(ctx.cp.addUtf8("intValue"), ctx.cp.addUtf8("()I")));
		ctx.emit(Opcode.CHECKCAST);
		ctx.emitU2(ctx.numberClass.index());
		ctx.emit(Opcode.INVOKEVIRTUAL);
		ctx.emitU2(intValue.index());
		MethodrefConstant exit = ctx.cp.addMethodref(ctx.cp.addClass(ctx.cp.addUtf8("java/lang/System")),
				ctx.cp.addNameAndType(ctx.cp.addUtf8("exit"), ctx.cp.addUtf8("(I)V")));
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(exit.index());
		// Never reached, but the verifier types this expression like any other: it leaves
		// one value behind.
		ctx.emit(Opcode.ACONST_NULL);
	}

}
