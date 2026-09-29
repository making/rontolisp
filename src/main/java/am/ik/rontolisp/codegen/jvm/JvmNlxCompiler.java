package am.ik.rontolisp.codegen.jvm;

import java.util.List;
import java.util.Objects;

import am.ik.jvm.ConstantPool;
import am.ik.jvm.MethodCode;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;

/**
 * Compiles the three internal non-local-exit primitives the compile-path
 * {@code CrossLambdaExitLowering} injects to make a {@code return-from} exit a function
 * from inside a nested lambda (a separately compiled method a lexical goto cannot cross):
 *
 * <ul>
 * <li>{@code (%nlx-tag)} mints a fresh {@code new Object()} -- the dynamic block-instance
 * id. A {@code let} binds one per establishing block activation, and the existing closure
 * machinery captures it into the lambda, so recursion targets the right frame.</li>
 * <li>{@code (%nlx-throw id value)} PUSHES {@code {throwable, id, value, previous}} onto
 * the per-thread {@code _nleTl} channel and {@code athrow}s a plain
 * {@code RuntimeException}; the real stack unwind runs every intervening
 * {@code unwind-protect}/{@code handler-case} handler.</li>
 * <li>{@code (%nlx-catch id body...)} runs {@code body} inside a catch-any region; when
 * the caught throwable is the pending NLE whose id matches {@code id}, its value becomes
 * the form's value and the channel is POPPED back to {@code previous}, otherwise it is
 * rethrown (a real condition, or an outer block's exit).</li>
 * </ul>
 *
 * The channel is a STACK rather than a single slot because an {@code unwind-protect}
 * cleanup runs while the exit that triggered it is still travelling, and that cleanup may
 * complete a non-local exit of its own -- a nested {@code catch}/{@code throw} pair, a
 * {@code return-from} out of an inner block. Overwriting-and-clearing left the outer
 * exit's entry gone by the time it reached its own landing pad, so it was rethrown past
 * its block and then synthesized into a message-less {@code simple-error} by the first
 * enclosing {@code handler-case}. Each entry also keys on the throwable's identity
 * ({@code entry[0] == caught}) so a cleanup that threw a NEW exception, abandoning the
 * pending exit, never misfires. {@link JvmHandlerCaseCompiler} rethrows a matching NLE
 * before dispatching, so a cross-lambda exit is never swallowed as a synthesized
 * condition.
 * <p>
 * The user-level {@code catch}/{@code throw} pair rides the same channel through
 * {@link #compileTagCatch}/{@link #compileTagThrow}, differing only in the tag test: the
 * carried value is an ordinary Lisp tag compared with {@code eq} instead of a
 * block-instance id compared by identity. The two kinds pass through each other because a
 * block-instance id is a fresh {@code new Object()} -- never {@code eq} to a Lisp value,
 * and never identical to one.
 */
final class JvmNlxCompiler {

	private JvmNlxCompiler() {
	}

	/** {@code (%nlx-tag)} -- a fresh unique identity object. */
	static void compileTag(JvmLispCompiler.Ctx ctx) {
		ConstantPool.MethodrefConstant objectCtor = ctx.cp.addMethodref(ctx.objectClass,
				ctx.cp.addNameAndType(ctx.cp.addUtf8("<init>"), ctx.cp.addUtf8("()V")));
		ctx.body.new_(ctx.objectClass.entry()).dup().invokespecial(objectCtor.entry());
	}

	/** {@code (%nlx-throw id value)} -- throw a non-local exit carrying (id, value). */
	static void compileThrow(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> parts = cons.toList();
		if (parts.size() != 3) {
			throw new IllegalArgumentException(LispNames.NLX_THROW_INTERNAL + " expects (%nlx-throw id value)");
		}
		emitThrow(parts.get(1), parts.get(2), null, ctx, className);
	}

	/**
	 * {@code (throw tag [result])} -- the user-level dynamic non-local exit, riding the
	 * same channel and plain {@code RuntimeException} as {@code %nlx-throw}. The two
	 * never confuse each other: a block-instance id is a fresh {@code new Object()}, so
	 * an {@code eq} tag test against it is always false, and the identity test a
	 * {@code %nlx-catch} runs is always false against a Lisp tag value.
	 */
	static void compileTagThrow(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> parts = cons.toList();
		if (parts.size() < 2 || parts.size() > 3) {
			throw new IllegalArgumentException(LispNames.THROW + " expects (throw tag result)");
		}
		emitThrow(parts.get(1), parts.size() == 3 ? parts.get(2) : am.ik.rontolisp.LispNil.INSTANCE,
				UNMATCHED_THROW_MESSAGE, ctx, className);
	}

	/**
	 * What an uncaught {@code throw} reports. A constant (the tag is not printed): the
	 * message is built at COMPILE time, so a caught throw -- the whole point of the form
	 * -- costs nothing at runtime.
	 */
	private static final String UNMATCHED_THROW_MESSAGE = LispNames.THROW + ": no enclosing catch for the tag";

	private static void emitThrow(LispVal tagForm, LispVal valueForm, @org.jspecify.annotations.Nullable String message,
			JvmLispCompiler.Ctx ctx, String className) {
		JvmLispCompiler.ConditionChannel channel = ctx.conditionChannel;
		channel.ensureNle(ctx.cp, className);
		ConstantPool.ClassConstant runtimeEx = ctx.cp.addClass(ctx.cp.addUtf8("java/lang/RuntimeException"));
		ConstantPool.MethodrefConstant exCtor = ctx.cp.addMethodref(runtimeEx, ctx.cp.addNameAndType(
				ctx.cp.addUtf8("<init>"), ctx.cp.addUtf8(message == null ? "()V" : "(Ljava/lang/String;)V")));
		int savedNextLocal = ctx.nextLocal;
		int exSlot = ctx.allocTemp();
		// ex = new RuntimeException([message])
		ctx.body.new_(runtimeEx.entry()).dup();
		if (message != null) {
			JvmEmitHelper.compileStringLiteral(message, ctx);
		}
		ctx.body.invokespecial(exCtor.entry()).astore(exSlot);
		// _nleTl.set(new Object[]{ex, id, value, previous}) -- pushing onto the channel
		// rather than overwriting it. The previous entry is read LAST, after the tag and
		// value forms have run, so an exit those forms complete themselves is already
		// popped by the time it is captured.
		ctx.body.getstatic(Objects.requireNonNull(channel.nleTlField).entry()).iconst_4();
		ctx.body.anewarray(ctx.objectClass.entry()).dup().iconst_0().aload(exSlot).aastore().dup();
		ctx.body.iconst_1();
		JvmExprCompiler.compileExpr(tagForm, ctx, className);
		ctx.body.aastore().dup().iconst_2();
		JvmExprCompiler.compileExpr(valueForm, ctx, className);
		ctx.body.aastore().dup().iconst_3();
		ctx.body.getstatic(Objects.requireNonNull(channel.nleTlField).entry());
		ctx.body.invokevirtual(Objects.requireNonNull(channel.tlGet).methodRefEntry()).aastore();
		ctx.body.invokevirtual(Objects.requireNonNull(channel.tlSet).methodRefEntry());
		// throw ex
		ctx.body.aload(exSlot).athrow();
		ctx.nextLocal = savedNextLocal;
	}

	/**
	 * {@code (%nlx-catch id body...)} -- catch a matching non-local exit; else rethrow.
	 */
	static void compileCatch(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> parts = cons.toList();
		if (parts.size() < 2) {
			throw new IllegalArgumentException(LispNames.NLX_CATCH_INTERNAL + " expects (%nlx-catch id body...)");
		}
		emitCatch(parts, ctx, className, false);
	}

	/**
	 * {@code (catch tag body...)} -- the user-level dynamic catcher. Same region shape as
	 * {@code %nlx-catch}, with two differences: the tag is an arbitrary expression
	 * evaluated ONCE before the protected region (CL evaluates it on entry, not on the
	 * unwind), and the landing compares it to the thrown tag with {@code eq} instead of
	 * object identity.
	 */
	static void compileTagCatch(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> parts = cons.toList();
		if (parts.size() < 2) {
			throw new IllegalArgumentException(LispNames.CATCH + " expects (catch tag body...)");
		}
		emitCatch(parts, ctx, className, true);
	}

	private static void emitCatch(List<LispVal> parts, JvmLispCompiler.Ctx ctx, String className, boolean eqTags) {
		LispVal idForm = parts.get(1);
		JvmLispCompiler.ConditionChannel channel = ctx.conditionChannel;
		channel.ensureNle(ctx.cp, className);
		ConstantPool.ClassConstant objectArrayClass = ctx.objectArrayClass;
		int savedNextLocal = ctx.nextLocal;
		// Entering the handler discards the operand stack, so enclosing operands are
		// spilled and reloaded past the merge (a statement-position catch spills nothing
		// and stays byte-neutral relative to a bare block).
		JvmLispCompiler.Ctx.Spill spill = ctx.spillOperandStack();
		int resultSlot = ctx.allocTemp();
		int excSlot = ctx.allocTemp();
		int arrSlot = ctx.allocTemp();
		// A user catch tag is an arbitrary expression CL evaluates ONCE, on entry:
		// snapshot
		// it into a slot outside the protected region (so a throw raised while evaluating
		// it is not caught here) and compare the landing against the snapshot. The
		// internal
		// id form is a plain lexical read, kept re-evaluated on the unwind so an existing
		// %nlx-catch stays byte-identical.
		int tagSlot = -1;
		if (eqTags) {
			tagSlot = ctx.allocTemp();
			JvmExprCompiler.compileExpr(idForm, ctx, className);
			ctx.body.astore(tagSlot);
		}
		if (!spill.live().isEmpty()) {
			ctx.spillScopes.push(new JvmLispCompiler.SpillScope(spill, ctx.blockTargets.size()));
		}
		MethodCode.Label start = ctx.body.newBoundLabel();
		// Body as an implicit progn.
		if (parts.size() <= 2) {
			ctx.body.aconst_null();
		}
		else {
			for (int i = 2; i < parts.size(); i++) {
				if (i > 2) {
					ctx.body.pop();
				}
				JvmExprCompiler.compileExpr(parts.get(i), ctx, className);
			}
		}
		MethodCode.Label end = ctx.body.newBoundLabel();
		MethodCode.Label done = ctx.body.newLabel();
		MethodCode.Label rethrow = ctx.body.newLabel();
		ctx.body.astore(resultSlot).goto_(done);
		// Handler: read the pending NLE; deliver on an id match, else rethrow.
		MethodCode.Label handler = ctx.body.newBoundLabel();
		ctx.stack.enterHandler();
		ctx.body.astore(excSlot).getstatic(Objects.requireNonNull(channel.nleTlField).entry());
		ctx.body.invokevirtual(Objects.requireNonNull(channel.tlGet).methodRefEntry()).astore(arrSlot);
		// if (_nleTl == null) rethrow
		ctx.body.aload(arrSlot).ifnull(rethrow);
		// if (triple[0] != caught) rethrow -- identity guards against a stale channel
		emitArrayElement(ctx, arrSlot, objectArrayClass, 0);
		ctx.body.aload(excSlot).if_acmpne(rethrow);
		// if the carried tag is not ours, rethrow -- an outer catcher's exit. The
		// internal
		// form compares the block-instance id by identity; a user catch compares the tag
		// with eq, through pseudo-locals so the nil-safe eq compiler is reused verbatim.
		if (eqTags) {
			int thrownSlot = ctx.allocTemp();
			emitArrayElement(ctx, arrSlot, objectArrayClass, 1);
			ctx.body.astore(thrownSlot);
			String thrownVar = "__throw_tag$" + thrownSlot;
			String wantVar = "__catch_tag$" + tagSlot;
			ctx.locals.put(thrownVar, thrownSlot);
			ctx.locals.put(wantVar, tagSlot);
			try {
				JvmExprCompiler.compileExpr(eqForm(thrownVar, wantVar), ctx, className);
			}
			finally {
				ctx.locals.remove(thrownVar);
				ctx.locals.remove(wantVar);
			}
			ctx.body.ifnull(rethrow);
		}
		else {
			emitArrayElement(ctx, arrSlot, objectArrayClass, 1);
			JvmExprCompiler.compileExpr(idForm, ctx, className);
			ctx.body.if_acmpne(rethrow);
		}
		// Matched: pop the channel back to the entry this one was pushed over (null at
		// the bottom), deliver entry[2]. Popping rather than clearing is what keeps an
		// exit that was already travelling -- this catch may be running inside an
		// unwind-protect cleanup on ITS way out -- findable by its own landing pad.
		ctx.body.getstatic(Objects.requireNonNull(channel.nleTlField).entry());
		emitArrayElement(ctx, arrSlot, objectArrayClass, 3);
		ctx.body.invokevirtual(Objects.requireNonNull(channel.tlSet).methodRefEntry());
		emitArrayElement(ctx, arrSlot, objectArrayClass, 2);
		ctx.body.astore(resultSlot).goto_(done);
		// Rethrow: a real condition, a stale channel, or an outer block's exit.
		ctx.body.labelBinding(rethrow);
		ctx.body.aload(excSlot).athrow();
		// Done.
		ctx.body.labelBinding(done);
		if (!spill.live().isEmpty()) {
			ctx.spillScopes.pop();
			spill.restore(ctx);
		}
		ctx.body.aload(resultSlot).exceptionCatch(start, end, handler, null);
		ctx.nextLocal = savedNextLocal;
	}

	/** The form {@code (eq <thrownVar> <wantVar>)} over two pseudo-locals. */
	private static LispVal eqForm(String thrownVar, String wantVar) {
		return new LispCons(new LispSymbol(LispNames.EQ_GENERAL), new LispCons(new LispSymbol(thrownVar),
				new LispCons(new LispSymbol(wantVar), am.ik.rontolisp.LispNil.INSTANCE)));
	}

	/** Loads {@code ((Object[]) arrSlot)[index]} onto the stack. */
	private static void emitArrayElement(JvmLispCompiler.Ctx ctx, int arrSlot, ConstantPool.ClassConstant arrayClass,
			int index) {
		ctx.body.aload(arrSlot).checkcast(arrayClass.entry()).loadConstant(index).aaload();
	}

}
