package am.ik.rontolisp.codegen.jvm;

import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.MethodRefEntry;
import java.util.List;
import java.util.Objects;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;

/**
 * Throws a signal whose text is its condition's report: a {@code RontoSignal}, which
 * renders it when {@code getMessage} first asks, whoever asks -- the entry handler, or an
 * embedder holding what a call threw ({@code .kb/error-handling.md}, "A signal renders
 * nothing"). The site compiles only what it alone can say of a class that reports
 * nothing, and a call of {@code _signalNew}: one {@code ldc} of the handle naming the
 * program's {@code _signalText}, which is {@code %signal-text} over the display string.
 */
final class JvmSignalThrow {

	static final String CLASS = "am/ik/rontolisp/runtime/RontoSignal";

	/** The runtime class file a program with such a signal carries beside it. */
	static final List<String> RUNTIME_CLASS_FILES = List.of(CLASS + ".class");

	private static final String NEW = "_signalNew";

	private static final String NEW_DESC = "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Throwable;";

	private static final String TEXT = "_signalText";

	private static final String TEXT_DESC = "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/String;";

	private static final String CTOR_DESC = "(Ljava/lang/invoke/MethodHandle;Ljava/lang/Object;Ljava/lang/Object;)V";

	private JvmSignalThrow() {
	}

	/**
	 * Emits {@code throw _condPut(_signalNew(condition, fallback), condition)}, through
	 * {@code _condRan} when {@code handlersRan}.
	 * @param fallback the fallback form
	 * @param ctx the method context
	 * @param className the generated class
	 * @param conditionSlot the local holding the condition
	 * @param handlersRan whether the record says the handlers ran
	 */
	static void compile(LispVal fallback, JvmLispCompiler.Ctx ctx, String className, int conditionSlot,
			boolean handlersRan) {
		int fallbackSlot = ctx.allocTemp();
		JvmExprCompiler.compileExpr(fallback, ctx, className);
		ctx.body.astore(fallbackSlot);
		ctx.body.aload(conditionSlot).aload(fallbackSlot).invokestatic(signalNew(ctx, className));
		ctx.body.aload(conditionSlot);
		ctx.body.invokestatic(
				Objects.requireNonNull(handlersRan ? ctx.conditionChannel.condRan : ctx.conditionChannel.condPut));
		ctx.body.athrow();
	}

	private static MethodRefEntry signalNew(JvmLispCompiler.Ctx ctx, String className) {
		JvmLispCompiler.ConditionChannel channel = ctx.conditionChannel;
		if (channel.signalNew != null) {
			return channel.signalNew;
		}
		MethodRefEntry newRef = JvmEmitHelper.selfMethod(ctx, className, NEW, NEW_DESC);
		channel.signalNew = newRef;
		MethodRefEntry textRef = JvmEmitHelper.selfMethod(ctx, className, TEXT, TEXT_DESC);
		JvmLispCompiler.Ctx text = helper(ctx);
		String condition = "__stc$0";
		String fallback = "__stm$1";
		text.locals.put(condition, 0);
		text.locals.put(fallback, 1);
		JvmExprCompiler.compileExpr(new LispCons(new LispSymbol(LispNames.SIGNAL_TEXT_INTERNAL),
				new LispCons(new LispSymbol(condition), new LispCons(new LispSymbol(fallback), LispNil.INSTANCE))),
				text, className);
		text.body.invokestatic(ctx.lispToDisplayString).areturn();
		ctx.outlinedBodies
			.add(new JvmBodyOutliner.OutlinedBody(TEXT, ctx.cp.utf8Entry(TEXT), ctx.cp.utf8Entry(TEXT_DESC), text));
		ClassEntry signal = ctx.cp.classEntry(CLASS);
		JvmLispCompiler.Ctx make = helper(ctx);
		// The cast keeps the verifier from loading the class to see that it is a
		// Throwable: a class copied without its siblings still runs up to such a signal.
		make.body.new_(signal)
			.dup()
			.ldc(ctx.cp.staticMethodHandle(textRef))
			.aload(0)
			.aload(1)
			.invokespecial(ctx.cp.methodRef(signal, "<init>", CTOR_DESC))
			.checkcast(ctx.cp.classEntry("java/lang/Throwable"))
			.areturn();
		ctx.outlinedBodies
			.add(new JvmBodyOutliner.OutlinedBody(NEW, ctx.cp.utf8Entry(NEW), ctx.cp.utf8Entry(NEW_DESC), make));
		return newRef;
	}

	private static JvmLispCompiler.Ctx helper(JvmLispCompiler.Ctx ctx) {
		JvmLispCompiler.Ctx helper = ctx.ctxBuilder.build();
		helper.evalStoreRef = ctx.evalStoreRef;
		helper.nextLocal = 2;
		helper.maxLocals = 2;
		return helper;
	}

}
