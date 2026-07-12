package am.ik.rontolisp.codegen.jvm;

import java.util.List;
import java.util.Objects;

import am.ik.jvm.Opcode;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;

/**
 * Compiles the internal {@code (%error-cond condition message)} primitive: it stores the
 * condition object (a CLOS-subset tagged-list instance) into the per-thread
 * {@code _condTl} ThreadLocal and throws a {@link RuntimeException} with the message, so
 * an enclosing {@code handler-case} can read the typed condition from the same thread of
 * control while an uncaught error prints exactly like a plain {@code %error}. Using the
 * channel marks it in {@link JvmLispCompiler.ConditionChannel}, which makes the class
 * writer emit the field and its {@code <clinit>}; a program without typed conditions
 * compiles without any of this machinery.
 */
final class JvmErrorCondCompiler {

	private JvmErrorCondCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		JvmLispCompiler.ConditionChannel channel = ctx.conditionChannel;
		channel.ensure(ctx.cp, className);
		// _condTl.set(condition)
		ctx.emit(Opcode.GETSTATIC);
		ctx.emitU2(Objects.requireNonNull(channel.condTlField).index());
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		ctx.emit(Opcode.INVOKEVIRTUAL);
		ctx.emitU2(Objects.requireNonNull(channel.tlSet).index());
		// throw new RuntimeException(strip(message))
		JvmErrorCompiler.compileThrowRuntimeException(args.get(2), ctx, className);
	}

}
