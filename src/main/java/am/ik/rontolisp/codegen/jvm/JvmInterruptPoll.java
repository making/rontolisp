package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.macro.LispMacroExpander;

/**
 * SIGINT in a compiled program ({@code .kb/error-handling.md}, "SIGINT"): compiled code
 * has no other place to take it than a poll of its own, a call of the injected
 * {@code %interrupt-poll} defun at every function entry, tagbody label and loop head.
 * {@code Ctx.interruptPolls} gates all of it, so a program not naming the condition keeps
 * its bytes.
 */
final class JvmInterruptPoll {

	/** The runtime class, which travels with such a program. */
	static final String CLASS = "am/ik/rontolisp/runtime/RontoInterruptSignal";

	/** The runtime class file a program with polls carries beside it. */
	static final List<String> RUNTIME_CLASS_FILES = List.of(CLASS + ".class");

	private static final LispVal POLL = new LispCons(new LispSymbol(LispNames.INTERRUPT_POLL_INTERNAL),
			LispNil.INSTANCE);

	private JvmInterruptPoll() {
	}

	/**
	 * Emits a poll, leaving the operand stack as it was.
	 * @param ctx the method being emitted
	 * @param className the class being generated
	 */
	static void emit(JvmLispCompiler.Ctx ctx, String className) {
		if (ctx.interruptPolls) {
			JvmExprCompiler.compileForEffect(POLL, ctx, className);
		}
	}

	/**
	 * Emits the poll at a function's entry, except in the poll's own function.
	 * @param function the function's name
	 * @param ctx the function's method
	 * @param className the class being generated
	 */
	static void emitAtEntry(String function, JvmLispCompiler.Ctx ctx, String className) {
		if (!LispNames.INTERRUPT_POLL_INTERNAL.equals(function)) {
			emit(ctx, className);
		}
	}

	/**
	 * Emits the listen of a {@code handler-case} or {@code handler-bind} being
	 * established when one of its clauses names the condition.
	 * @param clauseTypes the clauses' type specifiers
	 * @param ctx the method being emitted
	 */
	static void emitListen(List<LispVal> clauseTypes, JvmLispCompiler.Ctx ctx) {
		if (ctx.interruptPolls && clauseTypes.stream().anyMatch(LispMacroExpander::namesInterruptCondition)) {
			ctx.body.invokestatic(ctx.cp.methodRef(ctx.cp.classEntry(CLASS), "listen", "()V"));
		}
	}

	/**
	 * {@link #emitListen(List, JvmLispCompiler.Ctx)} for a {@code handler-bind} form.
	 * @param handlerBind the form
	 * @param ctx the method being emitted
	 */
	static void emitListen(LispCons handlerBind, JvmLispCompiler.Ctx ctx) {
		if (ctx.interruptPolls && handlerBind.cdr() instanceof LispCons rest
				&& rest.car() instanceof LispCons bindings) {
			emitListen(bindings.toList()
				.stream()
				.<LispVal>map((binding) -> binding instanceof LispCons cons ? cons.car() : LispNil.INSTANCE)
				.toList(), ctx);
		}
	}

	/**
	 * Compiles the {@code %interrupt-take} internal primitive.
	 * @param ctx the method being emitted
	 */
	static void compileTake(JvmLispCompiler.Ctx ctx) {
		ctx.body.invokestatic(ctx.cp.methodRef(ctx.cp.classEntry(CLASS), "take", "()Ljava/lang/Object;"));
	}

}
