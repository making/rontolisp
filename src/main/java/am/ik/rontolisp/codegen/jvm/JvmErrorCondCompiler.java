package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.jvm.Opcode;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;

/**
 * Compiles the internal {@code (%error-cond condition message)} primitive: it throws a
 * {@link RuntimeException} with the message and records the condition object (a
 * CLOS-subset tagged-list instance) under that exception on the per-thread
 * {@code _condTl} channel ({@link JvmThrowableRecords}), so an enclosing
 * {@code handler-case} that catches THIS exception reads the typed condition while an
 * uncaught error prints exactly like a plain {@code %error}. The condition is evaluated
 * first, into a local, and recorded only once the exception exists. Using the channel
 * marks it in {@link JvmLispCompiler.ConditionChannel}, which makes the class writer emit
 * the field, its {@code <clinit>} and the helpers; a program without typed conditions
 * compiles without any of this machinery.
 */
final class JvmErrorCondCompiler {

	private JvmErrorCondCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		ctx.conditionChannel.ensure(ctx.cp, className);
		int savedNextLocal = ctx.nextLocal;
		int condSlot = ctx.allocTemp();
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		ctx.emit(Opcode.ASTORE);
		ctx.emit(condSlot);
		// throw _condPut(new RuntimeException(strip(message)), condition)
		JvmErrorCompiler.compileThrowRuntimeException(args.get(2), ctx, className, condSlot);
		ctx.nextLocal = savedNextLocal;
	}

}
