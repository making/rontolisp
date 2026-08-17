package am.ik.rontolisp.codegen.jvm;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import am.ik.jvm.ByteCodeWriter;
import am.ik.jvm.ConstantPool;
import am.ik.jvm.Opcode;
import am.ik.rontolisp.ClosRegistry;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.macro.LispMacroExpander;
import am.ik.rontolisp.LispLayout;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;

/**
 * Compiles {@code (handler-case expr (type ([var]) body...)... [(:no-error ([var])
 * body...)])}: the expression runs inside a catch-any exception-table region (the
 * unwind-protect machinery); the handler reads the typed condition from the per-thread
 * {@code _condTl} channel (set by {@code %error-cond}), synthesizes an instance from the
 * exception message when the channel is empty (a plain {@code %error} or a raw runtime
 * exception -- the CLASS then comes from what the throwable is, see
 * {@code emitSynthesizeCondition}), dispatches it through the clauses' type tests --
 * ordinary compiled Lisp forms over a pseudo-local holding the condition -- and rethrows
 * when none matches. The per-thread handler depth is incremented around the protected
 * region so {@code signal} raises only under an established handler; a {@code return}
 * exiting the region decrements it through the {@code UnwindScope} cleanup channel
 * ({@code %hc-depth-dec}).
 */
final class JvmHandlerCaseCompiler {

	private JvmHandlerCaseCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> parts = cons.toList();
		if (parts.size() < 2) {
			throw new IllegalArgumentException(LispNames.HANDLER_CASE + " expects an expression");
		}
		List<List<LispVal>> errorClauses = new ArrayList<>();
		List<LispVal> noErrorClause = null;
		for (int i = 2; i < parts.size(); i++) {
			if (!(parts.get(i) instanceof LispCons clause) || !(clause.cdr() instanceof LispCons)) {
				throw new IllegalArgumentException(
						LispNames.HANDLER_CASE + " expects (type (var) body...) clauses: " + parts.get(i).print());
			}
			List<LispVal> clauseParts = clause.toList();
			if (clause.car() instanceof LispSymbol head && ":NO-ERROR".equals(head.name())) {
				noErrorClause = clauseParts;
			}
			else {
				errorClauses.add(clauseParts);
			}
		}
		JvmLispCompiler.ConditionChannel channel = ctx.conditionChannel;
		channel.ensure(ctx.cp, className);
		int savedNextLocal = ctx.nextLocal;
		// Entering the handler discards the operand stack, so the values the enclosing
		// form had already evaluated are saved into locals and reloaded past the merge:
		// both edges into it then arrive with the same (empty) stack. A handler-case
		// compiled as a statement spills nothing and is byte-identical to before.
		JvmLispCompiler.Ctx.Spill spill = ctx.spillOperandStack();
		int resultSlot = ctx.allocTemp();
		int excSlot = ctx.allocTemp();
		int condSlot = ctx.allocTemp();
		if (!spill.live().isEmpty()) {
			// A return escaping the form cannot leave the enclosing block's operands on
			// the stack: they are in the spill now, and JvmReturnCompiler reloads them.
			ctx.spillScopes.push(new JvmLispCompiler.SpillScope(spill, ctx.blockTargets.size()));
		}
		// depth++ so signal raises inside the protected region (incl. called functions).
		emitDepthAdjust(ctx, className, true);
		LispVal depthDecForm = new LispCons(new LispSymbol(LispNames.HC_DEPTH_DEC_INTERNAL), LispNil.INSTANCE);
		JvmLispCompiler.UnwindScope scope = new JvmLispCompiler.UnwindScope(List.of(depthDecForm),
				ctx.blockTargets.size());
		ctx.unwindScopes.push(scope);
		int start = ctx.code.size();
		// In restart mode -- and under the signal-point clause match -- the clause
		// types also go on the DYNAMIC handler stack for the protected extent, so
		// %run-handlers stops at this handler-case instead of running an enclosing
		// handler-bind's handler for a condition this form is nearer to, and
		// %signal-cond can decline this handler-case when no clause matches
		// (%hc-match-p). With both gates off the form is unchanged, byte for byte.
		JvmExprCompiler.compileExpr(LispMacroExpander.handlerCaseProtectedForm(parts.get(1),
				errorClauses.stream().map(clauseParts -> clauseParts.get(0)).toList(), ctx.closRegistry,
				ctx.restartMode || ctx.signalClauseMatch), ctx, className);
		int end = ctx.code.size();
		ctx.unwindScopes.pop();
		ctx.emit(Opcode.ASTORE);
		ctx.emit(resultSlot);
		// Normal completion: depth--, then the :no-error clause (outside the protected
		// region -- an error signaled by it is not caught by this handler-case).
		emitDepthAdjust(ctx, className, false);
		if (noErrorClause != null) {
			compileClauseBody(noErrorClause, resultSlot, resultSlot, ctx, className);
		}
		int gotoDonePos = ctx.code.size();
		ctx.emit(Opcode.GOTO);
		ctx.emitU2(0);
		// Handler: depth--, read (and clear) the condition channel, synthesize a
		// simple-error from the message when it is empty, dispatch through the clauses.
		int handler = ctx.code.size();
		ctx.stack.enterHandler();
		ctx.emit(Opcode.ASTORE);
		ctx.emit(excSlot);
		emitDepthAdjust(ctx, className, false);
		// A cross-lambda non-local exit unwinding through this region rides a plain
		// RuntimeException that this catch-any handler would otherwise swallow as a
		// synthesized condition. Rethrow it (identity-checked against the pending _nleTl)
		// before dispatching, so return-from is never intercepted by handler-case. Gated
		// so a program without a cross-lambda exit stays byte-identical.
		if (ctx.blockExitChannel) {
			emitRethrowPendingNle(ctx, className, excSlot);
		}
		ctx.emit(Opcode.GETSTATIC);
		ctx.emitU2(Objects.requireNonNull(channel.condTlField).index());
		ctx.emit(Opcode.INVOKEVIRTUAL);
		ctx.emitU2(Objects.requireNonNull(channel.tlGet).index());
		ctx.emit(Opcode.ASTORE);
		ctx.emit(condSlot);
		ctx.emit(Opcode.GETSTATIC);
		ctx.emitU2(Objects.requireNonNull(channel.condTlField).index());
		ctx.emit(Opcode.ACONST_NULL);
		ctx.emit(Opcode.INVOKEVIRTUAL);
		ctx.emitU2(Objects.requireNonNull(channel.tlSet).index());
		ctx.emit(Opcode.ALOAD);
		ctx.emit(condSlot);
		int ifHaveCondPos = ctx.code.size();
		ctx.emit(Opcode.IFNONNULL);
		ctx.emitU2(0);
		emitSynthesizeCondition(excSlot, condSlot, ctx, className);
		JvmEmitHelper.patchBranch(ctx, ifHaveCondPos, ctx.code.size());
		// Dispatch: the condition rides a pseudo-local so the type tests and clause
		// bodies compile as ordinary Lisp forms.
		String condVarName = "__hc_cond$" + condSlot;
		LispSymbol condVarSym = new LispSymbol(condVarName);
		ctx.locals.put(condVarName, condSlot);
		List<Integer> donePatches = new ArrayList<>();
		try {
			for (List<LispVal> clauseParts : errorClauses) {
				LispVal test = LispMacroExpander.makeHandlerTypeTest(condVarSym, clauseParts.get(0), ctx.closRegistry);
				JvmExprCompiler.compileExpr(test, ctx, className);
				int ifNoMatchPos = ctx.code.size();
				ctx.emit(Opcode.IFNULL);
				ctx.emitU2(0);
				compileClauseBody(clauseParts, condSlot, resultSlot, ctx, className);
				donePatches.add(ctx.code.size());
				ctx.emit(Opcode.GOTO);
				ctx.emitU2(0);
				JvmEmitHelper.patchBranch(ctx, ifNoMatchPos, ctx.code.size());
			}
		}
		finally {
			ctx.locals.remove(condVarName);
		}
		// No clause matched: restore the condition into the channel (an outer
		// handler-case must see the typed instance, not a re-synthesized
		// simple-error) and rethrow.
		ctx.emit(Opcode.GETSTATIC);
		ctx.emitU2(Objects.requireNonNull(channel.condTlField).index());
		ctx.emit(Opcode.ALOAD);
		ctx.emit(condSlot);
		ctx.emit(Opcode.INVOKEVIRTUAL);
		ctx.emitU2(Objects.requireNonNull(channel.tlSet).index());
		ctx.emit(Opcode.ALOAD);
		ctx.emit(excSlot);
		ctx.emit(Opcode.ATHROW);
		int done = ctx.code.size();
		JvmEmitHelper.patchBranch(ctx, gotoDonePos, done);
		for (int patch : donePatches) {
			JvmEmitHelper.patchBranch(ctx, patch, done);
		}
		if (!spill.live().isEmpty()) {
			ctx.spillScopes.pop();
			spill.restore(ctx);
		}
		ctx.emit(Opcode.ALOAD);
		ctx.emit(resultSlot);
		addExceptionEntries(ctx, scope, start, end, handler);
		ctx.nextLocal = savedNextLocal;
	}

	/**
	 * Compiles the internal {@code (%hb-guard body)} landing pad the {@code handler-bind}
	 * expansion wraps its body in: a catch-any region over the body whose handler reads
	 * (and clears) the condition channel, synthesizes the {@code simple-error} of a
	 * condition-less throw (a raw runtime failure, a plain {@code %error}), runs the
	 * {@code handler-bind} cluster stack through {@code %run-handlers} unless a walk
	 * already completed for the identical instance (the {@code %handlers-ran%} mark --
	 * the restart-mode signal hook runs handlers at the signal point and its terminals
	 * carry the instance they ran for), restores the channel so an outer
	 * {@code handler-case} or guard sees the same instance, and rethrows. Unlike
	 * {@code handler-case} it never touches the handler depth (so {@code signal}
	 * semantics are unchanged) and has no cleanup, so it pushes no {@code UnwindScope}.
	 */
	static void compileGuard(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> parts = cons.toList();
		if (parts.size() != 2) {
			throw new IllegalArgumentException(LispNames.HB_GUARD_INTERNAL + " expects a body: " + cons.print());
		}
		JvmLispCompiler.ConditionChannel channel = ctx.conditionChannel;
		channel.ensure(ctx.cp, className);
		int savedNextLocal = ctx.nextLocal;
		JvmLispCompiler.Ctx.Spill spill = ctx.spillOperandStack();
		int resultSlot = ctx.allocTemp();
		int excSlot = ctx.allocTemp();
		int condSlot = ctx.allocTemp();
		if (!spill.live().isEmpty()) {
			ctx.spillScopes.push(new JvmLispCompiler.SpillScope(spill, ctx.blockTargets.size()));
		}
		int start = ctx.code.size();
		JvmExprCompiler.compileExpr(parts.get(1), ctx, className);
		int end = ctx.code.size();
		ctx.emit(Opcode.ASTORE);
		ctx.emit(resultSlot);
		int gotoDonePos = ctx.code.size();
		ctx.emit(Opcode.GOTO);
		ctx.emitU2(0);
		int handler = ctx.code.size();
		ctx.stack.enterHandler();
		ctx.emit(Opcode.ASTORE);
		ctx.emit(excSlot);
		// A cross-lambda non-local exit must pass through untouched (same gate as
		// handler-case).
		if (ctx.blockExitChannel) {
			emitRethrowPendingNle(ctx, className, excSlot);
		}
		ctx.emit(Opcode.GETSTATIC);
		ctx.emitU2(Objects.requireNonNull(channel.condTlField).index());
		ctx.emit(Opcode.INVOKEVIRTUAL);
		ctx.emitU2(Objects.requireNonNull(channel.tlGet).index());
		ctx.emit(Opcode.ASTORE);
		ctx.emit(condSlot);
		ctx.emit(Opcode.GETSTATIC);
		ctx.emitU2(Objects.requireNonNull(channel.condTlField).index());
		ctx.emit(Opcode.ACONST_NULL);
		ctx.emit(Opcode.INVOKEVIRTUAL);
		ctx.emitU2(Objects.requireNonNull(channel.tlSet).index());
		ctx.emit(Opcode.ALOAD);
		ctx.emit(condSlot);
		int ifHaveCondPos = ctx.code.size();
		ctx.emit(Opcode.IFNONNULL);
		ctx.emitU2(0);
		emitSynthesizeCondition(excSlot, condSlot, ctx, className);
		JvmEmitHelper.patchBranch(ctx, ifHaveCondPos, ctx.code.size());
		// Run the cluster stack unless already run, as an ordinary Lisp form over the
		// condition pseudo-local.
		String condVarName = "__hb_cond$" + condSlot;
		ctx.locals.put(condVarName, condSlot);
		try {
			JvmExprCompiler.compileExpr(LispMacroExpander.hbGuardHandlerForm(new LispSymbol(condVarName)), ctx,
					className);
		}
		finally {
			ctx.locals.remove(condVarName);
		}
		ctx.emit(Opcode.POP);
		// Restore the channel (the outer catcher must see the instance the handlers
		// saw, a synthesized one included) and rethrow.
		ctx.emit(Opcode.GETSTATIC);
		ctx.emitU2(Objects.requireNonNull(channel.condTlField).index());
		ctx.emit(Opcode.ALOAD);
		ctx.emit(condSlot);
		ctx.emit(Opcode.INVOKEVIRTUAL);
		ctx.emitU2(Objects.requireNonNull(channel.tlSet).index());
		ctx.emit(Opcode.ALOAD);
		ctx.emit(excSlot);
		ctx.emit(Opcode.ATHROW);
		int done = ctx.code.size();
		JvmEmitHelper.patchBranch(ctx, gotoDonePos, done);
		if (!spill.live().isEmpty()) {
			ctx.spillScopes.pop();
			spill.restore(ctx);
		}
		ctx.emit(Opcode.ALOAD);
		ctx.emit(resultSlot);
		if (start < end) {
			ctx.exceptionTable.add(new ByteCodeWriter.ExceptionTableEntry(start, end, handler, 0));
		}
		ctx.nextLocal = savedNextLocal;
	}

	/**
	 * Emits, at the handler entry, a guard that rethrows the caught throwable when it is
	 * the pending cross-lambda non-local exit (its {@code _nleTl} triple's throwable
	 * equals the caught one), so a {@code return-from} crossing a lambda is never
	 * intercepted here. A null channel or a mismatched throwable (a real condition, or a
	 * stale channel from an unwind-protect cleanup that itself threw) falls through to
	 * the normal dispatch.
	 */
	private static void emitRethrowPendingNle(JvmLispCompiler.Ctx ctx, String className, int excSlot) {
		JvmLispCompiler.ConditionChannel channel = ctx.conditionChannel;
		channel.ensureNle(ctx.cp, className);
		int nleSlot = ctx.allocTemp();
		ctx.emit(Opcode.GETSTATIC);
		ctx.emitU2(Objects.requireNonNull(channel.nleTlField).index());
		ctx.emit(Opcode.INVOKEVIRTUAL);
		ctx.emitU2(Objects.requireNonNull(channel.tlGet).index());
		ctx.emit(Opcode.ASTORE);
		ctx.emit(nleSlot);
		ctx.emit(Opcode.ALOAD);
		ctx.emit(nleSlot);
		int ifNullPos = ctx.code.size();
		ctx.emit(Opcode.IFNULL);
		ctx.emitU2(0);
		ctx.emit(Opcode.ALOAD);
		ctx.emit(nleSlot);
		ctx.emit(Opcode.CHECKCAST);
		ctx.emitU2(ctx.objectArrayClass.index());
		ctx.emit(Opcode.ICONST_0);
		ctx.emit(Opcode.AALOAD);
		ctx.emit(Opcode.ALOAD);
		ctx.emit(excSlot);
		int ifNotSamePos = ctx.code.size();
		ctx.emit(Opcode.IF_ACMPNE);
		ctx.emitU2(0);
		ctx.emit(Opcode.ALOAD);
		ctx.emit(excSlot);
		ctx.emit(Opcode.ATHROW);
		int proceed = ctx.code.size();
		JvmEmitHelper.patchBranch(ctx, ifNullPos, proceed);
		JvmEmitHelper.patchBranch(ctx, ifNotSamePos, proceed);
	}

	/**
	 * Compiles a clause body -- {@code (type ([var]) body...)} or {@code (:no-error
	 * ([var]) body...)} -- binding the optional variable to the value in
	 * {@code valueSlot} through a pseudo-local, and stores the body's value into
	 * {@code resultSlot}.
	 */
	private static void compileClauseBody(List<LispVal> clauseParts, int valueSlot, int resultSlot,
			JvmLispCompiler.Ctx ctx, String className) {
		String varName = null;
		Integer shadowedSlot = null;
		if (clauseParts.get(1) instanceof LispCons varList && varList.car() instanceof LispSymbol var) {
			varName = var.name();
			shadowedSlot = ctx.locals.put(varName, valueSlot);
		}
		try {
			if (clauseParts.size() <= 2) {
				ctx.emit(Opcode.ACONST_NULL);
			}
			else {
				for (int i = 2; i < clauseParts.size(); i++) {
					if (i > 2) {
						ctx.emit(Opcode.POP);
					}
					JvmExprCompiler.compileExpr(clauseParts.get(i), ctx, className);
				}
			}
			ctx.emit(Opcode.ASTORE);
			ctx.emit(resultSlot);
		}
		finally {
			if (varName != null) {
				if (shadowedSlot != null) {
					ctx.locals.put(varName, shadowedSlot);
				}
				else {
					ctx.locals.remove(varName);
				}
			}
		}
	}

	/**
	 * Synthesizes the condition instance of a condition-less throw: the message is
	 * {@code Throwable.getMessage()} quote-framed (nil when it carries none), and the
	 * CLASS is decided by what the throwable IS. A cast or an out-of-range index is a
	 * {@code type-error} (CLHS says so for {@code aref}), an arithmetic failure a
	 * {@code division-by-zero} or its parent {@code arithmetic-error}, and the two
	 * failures this compiler itself reports as text -- an unbound variable, an undefined
	 * function -- are recognized by the very message it emitted, because their throw
	 * sites are plain {@code RuntimeException}s with no channel to carry a class. The
	 * rule is the interpreter's ({@code LispEvaluator.rawFailureConditionClass} plus the
	 * typed built-in throw sites), so {@code (handler-case (car 1) (type-error ...))}
	 * behaves the same interpreted and compiled; change the two together.
	 */
	private static void emitSynthesizeCondition(int excSlot, int condSlot, JvmLispCompiler.Ctx ctx, String className) {
		ConstantPool.ClassConstant throwableClass = ctx.cp.addClass(ctx.cp.addUtf8("java/lang/Throwable"));
		ConstantPool.MethodrefConstant getMessage = ctx.cp.addMethodref(throwableClass,
				ctx.cp.addNameAndType(ctx.cp.addUtf8("getMessage"), ctx.cp.addUtf8("()Ljava/lang/String;")));
		int concat = JvmEmitHelper.stringMethod(ctx, "concat", "(Ljava/lang/String;)Ljava/lang/String;").index();
		int rawSlot = ctx.allocTemp();
		ctx.emit(Opcode.ALOAD);
		ctx.emit(excSlot);
		ctx.emit(Opcode.INVOKEVIRTUAL);
		ctx.emitU2(getMessage.index());
		ctx.emit(Opcode.ASTORE);
		ctx.emit(rawSlot);
		emitHostTextOverride(excSlot, rawSlot, "java/lang/ClassCastException", ClosRegistry.TYPE_ERROR_MESSAGE, ctx);
		emitHostTextOverride(excSlot, rawSlot, "java/lang/IndexOutOfBoundsException",
				ClosRegistry.INDEX_OUT_OF_BOUNDS_MESSAGE, ctx);
		int msgSlot = ctx.allocTemp();
		ctx.emit(Opcode.ALOAD);
		ctx.emit(rawSlot);
		ctx.emit(Opcode.DUP);
		int ifNullMsgPos = ctx.code.size();
		ctx.emit(Opcode.IFNULL);
		ctx.emitU2(0);
		// "\"" + msg + "\"" -- the quote-framed runtime string representation.
		JvmEmitHelper.compileStringLiteral("\"", ctx);
		ctx.emit(Opcode.SWAP);
		ctx.emit(Opcode.INVOKEVIRTUAL);
		ctx.emitU2(concat);
		JvmEmitHelper.compileStringLiteral("\"", ctx);
		ctx.emit(Opcode.INVOKEVIRTUAL);
		ctx.emitU2(concat);
		int gotoHavePos = ctx.code.size();
		ctx.emit(Opcode.GOTO);
		ctx.emitU2(0);
		JvmEmitHelper.patchBranch(ctx, ifNullMsgPos, ctx.code.size());
		ctx.emit(Opcode.POP);
		ctx.emit(Opcode.ACONST_NULL);
		JvmEmitHelper.patchBranch(ctx, gotoHavePos, ctx.code.size());
		ctx.emit(Opcode.ASTORE);
		ctx.emit(msgSlot);
		String msgVarName = "__hc_msg$" + msgSlot;
		ctx.locals.put(msgVarName, msgSlot);
		try {
			emitClassifiedConstruction(excSlot, rawSlot, condSlot, new LispSymbol(msgVarName), ctx, className);
		}
		finally {
			ctx.locals.remove(msgVarName);
		}
	}

	/**
	 * Replaces the raw message with a rontolisp-level one when the throwable is a host
	 * failure whose own text names Java internals -- a cast (Java class names) or an
	 * out-of-range index (a length that counts the instance's layout cell). Every other
	 * message is the text a rontolisp built-in wrote itself and is kept verbatim.
	 */
	private static void emitHostTextOverride(int excSlot, int rawSlot, String type, String text,
			JvmLispCompiler.Ctx ctx) {
		int skip = emitInstanceOfJump(excSlot, type, ctx, false);
		JvmEmitHelper.compileStringLiteral(text, ctx);
		ctx.emit(Opcode.ASTORE);
		ctx.emit(rawSlot);
		JvmEmitHelper.patchBranch(ctx, skip, ctx.code.size());
	}

	/**
	 * Emits the class dispatch of {@link #emitSynthesizeCondition}: one guarded arm per
	 * {@link LispMacroExpander#rawFailureConditionClasses()} entry, each storing its
	 * construction into {@code condSlot} and jumping to the join, with
	 * {@code simple-error} as the fallthrough. The arms are ordinary compiled Lisp forms,
	 * so the slot layout of each class comes from the registry rather than from a baked
	 * index here.
	 */
	private static void emitClassifiedConstruction(int excSlot, int rawSlot, int condSlot, LispSymbol msgVar,
			JvmLispCompiler.Ctx ctx, String className) {
		List<String> classes = LispMacroExpander.rawFailureConditionClasses();
		List<Integer> joins = new ArrayList<>();
		for (int i = 0; i < classes.size(); i++) {
			List<Integer> skips = emitRawFailureTest(i, excSlot, rawSlot, ctx);
			JvmExprCompiler.compileExpr(
					LispMacroExpander.reportingConditionForm(ctx.closRegistry, classes.get(i), msgVar), ctx, className);
			ctx.emit(Opcode.ASTORE);
			ctx.emit(condSlot);
			joins.add(ctx.code.size());
			ctx.emit(Opcode.GOTO);
			ctx.emitU2(0);
			for (int skip : skips) {
				JvmEmitHelper.patchBranch(ctx, skip, ctx.code.size());
			}
		}
		LispVal quotedTag = new LispCons(new LispSymbol(LispNames.QUOTE),
				new LispCons(new LispSymbol(LispLayout.CLASS_TAG_PREFIX + "SIMPLE-ERROR"), LispNil.INSTANCE));
		LispVal instance = new LispCons(new LispSymbol(LispNames.OBJ_NEW),
				new LispCons(quotedTag, new LispCons(msgVar, new LispCons(LispNil.INSTANCE, LispNil.INSTANCE))));
		JvmExprCompiler.compileExpr(instance, ctx, className);
		ctx.emit(Opcode.ASTORE);
		ctx.emit(condSlot);
		for (int join : joins) {
			JvmEmitHelper.patchBranch(ctx, join, ctx.code.size());
		}
	}

	/**
	 * Emits the test guarding raw-failure arm {@code index}, and answers the branch
	 * positions to patch to the arm's END (i.e. the "does not apply" exits). The order
	 * mirrors {@link LispMacroExpander#rawFailureConditionClasses()}: type-error,
	 * division-by-zero, arithmetic-error, unbound-variable, undefined-function.
	 */
	private static List<Integer> emitRawFailureTest(int index, int excSlot, int rawSlot, JvmLispCompiler.Ctx ctx) {
		return switch (index) {
			case 0 -> {
				// A cast failure or an out-of-range index: type-error. Neither is an
				// ArithmeticException, so testing this arm first costs the arithmetic
				// arms nothing.
				List<Integer> skips = new ArrayList<>();
				int hit = emitInstanceOfJump(excSlot, "java/lang/ClassCastException", ctx, true);
				skips.add(emitInstanceOfJump(excSlot, "java/lang/IndexOutOfBoundsException", ctx, false));
				JvmEmitHelper.patchBranch(ctx, hit, ctx.code.size());
				yield skips;
			}
			case 1 -> {
				List<Integer> skips = new ArrayList<>();
				skips.add(emitInstanceOfJump(excSlot, "java/lang/ArithmeticException", ctx, false));
				skips.add(emitMessageTest(rawSlot, "contains", ClosRegistry.DIVISION_BY_ZERO_MESSAGE_TOKEN, ctx));
				yield skips;
			}
			case 2 -> List.of(emitInstanceOfJump(excSlot, "java/lang/ArithmeticException", ctx, false));
			case 3 -> List.of(emitMessageTest(rawSlot, "startsWith", ClosRegistry.UNBOUND_VARIABLE_MESSAGE_PREFIX, ctx),
					emitMessageTest(rawSlot, "endsWith", ClosRegistry.UNBOUND_VARIABLE_MESSAGE_SUFFIX, ctx));
			default ->
				List.of(emitMessageTest(rawSlot, "startsWith", ClosRegistry.UNDEFINED_FUNCTION_MESSAGE_PREFIX, ctx),
						emitMessageTest(rawSlot, "endsWith", ClosRegistry.UNDEFINED_FUNCTION_MESSAGE_SUFFIX, ctx));
		};
	}

	/**
	 * Emits {@code exc instanceof <type>} and a jump on the given outcome, answering the
	 * branch position to patch.
	 */
	private static int emitInstanceOfJump(int excSlot, String type, JvmLispCompiler.Ctx ctx, boolean jumpWhenTrue) {
		ctx.emit(Opcode.ALOAD);
		ctx.emit(excSlot);
		ctx.emit(Opcode.INSTANCEOF);
		ctx.emitU2(ctx.cp.addClass(ctx.cp.addUtf8(type)).index());
		int pos = ctx.code.size();
		ctx.emit(jumpWhenTrue ? Opcode.IFNE : Opcode.IFEQ);
		ctx.emitU2(0);
		return pos;
	}

	/**
	 * Emits a {@code String} predicate over the RAW message ({@code startsWith} /
	 * {@code endsWith} / {@code contains}) and a jump taken when it does NOT hold -- a
	 * null message counts as not holding. Answers the branch position to patch.
	 */
	private static int emitMessageTest(int rawSlot, String method, String argument, JvmLispCompiler.Ctx ctx) {
		String descriptor = "contains".equals(method) ? "(Ljava/lang/CharSequence;)Z" : "(Ljava/lang/String;)Z";
		ctx.emit(Opcode.ALOAD);
		ctx.emit(rawSlot);
		int ifNullPos = ctx.code.size();
		ctx.emit(Opcode.IFNULL);
		ctx.emitU2(0);
		ctx.emit(Opcode.ALOAD);
		ctx.emit(rawSlot);
		JvmEmitHelper.compileStringLiteral(argument, ctx);
		ctx.emit(Opcode.INVOKEVIRTUAL);
		ctx.emitU2(JvmEmitHelper.stringMethod(ctx, method, descriptor).index());
		int pos = ctx.code.size();
		ctx.emit(Opcode.IFNE);
		ctx.emitU2(0);
		JvmEmitHelper.patchBranch(ctx, ifNullPos, ctx.code.size());
		int fail = ctx.code.size();
		ctx.emit(Opcode.GOTO);
		ctx.emitU2(0);
		JvmEmitHelper.patchBranch(ctx, pos, ctx.code.size());
		return fail;
	}

	/**
	 * Emits an increment ({@code up}) or decrement of the per-thread handler-depth
	 * counter: {@code _hcDepthTl.set(Integer.valueOf(read() +/- 1))}, where a null value
	 * reads as 0.
	 */
	static void emitDepthAdjust(JvmLispCompiler.Ctx ctx, String className, boolean up) {
		JvmLispCompiler.ConditionChannel channel = ctx.conditionChannel;
		channel.ensure(ctx.cp, className);
		ctx.emit(Opcode.GETSTATIC);
		ctx.emitU2(Objects.requireNonNull(channel.depthTlField).index());
		emitReadDepth(ctx, className);
		ctx.emit(Opcode.ICONST_1);
		ctx.emit(up ? Opcode.IADD : Opcode.ISUB);
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(ctx.integerValueOf.index());
		ctx.emit(Opcode.INVOKEVIRTUAL);
		ctx.emitU2(Objects.requireNonNull(channel.tlSet).index());
	}

	/**
	 * Emits the read of the per-thread handler depth as an {@code int} on the operand
	 * stack (null = 0).
	 */
	static void emitReadDepth(JvmLispCompiler.Ctx ctx, String className) {
		JvmLispCompiler.ConditionChannel channel = ctx.conditionChannel;
		channel.ensure(ctx.cp, className);
		ctx.emit(Opcode.GETSTATIC);
		ctx.emitU2(Objects.requireNonNull(channel.depthTlField).index());
		ctx.emit(Opcode.INVOKEVIRTUAL);
		ctx.emitU2(Objects.requireNonNull(channel.tlGet).index());
		ctx.emit(Opcode.DUP);
		int ifNullPos = ctx.code.size();
		ctx.emit(Opcode.IFNULL);
		ctx.emitU2(0);
		ctx.emit(Opcode.CHECKCAST);
		ctx.emitU2(ctx.integerClass.index());
		ctx.emit(Opcode.INVOKEVIRTUAL);
		ctx.emitU2(ctx.integerValue.index());
		int gotoHavePos = ctx.code.size();
		ctx.emit(Opcode.GOTO);
		ctx.emitU2(0);
		JvmEmitHelper.patchBranch(ctx, ifNullPos, ctx.code.size());
		ctx.emit(Opcode.POP);
		ctx.emit(Opcode.ICONST_0);
		JvmEmitHelper.patchBranch(ctx, gotoHavePos, ctx.code.size());
	}

	/**
	 * Emits the {@code %hc-depth-dec} internal form: decrements the handler depth and
	 * yields nil (the {@code UnwindScope} cleanup channel pops the value).
	 */
	static void compileDepthDec(JvmLispCompiler.Ctx ctx, String className) {
		emitDepthAdjust(ctx, className, false);
		ctx.emit(Opcode.ACONST_NULL);
	}

	/**
	 * Appends the catch-any exception-table entries of the protected region, excluding
	 * the recorded return-site cleanup holes (same sweep as
	 * {@code JvmUnwindProtectCompiler}).
	 */
	private static void addExceptionEntries(JvmLispCompiler.Ctx ctx, JvmLispCompiler.UnwindScope scope, int start,
			int end, int handler) {
		int cur = start;
		for (int[] hole : scope.holes) {
			if (hole[0] > cur) {
				ctx.exceptionTable.add(new ByteCodeWriter.ExceptionTableEntry(cur, hole[0], handler, 0));
			}
			cur = Math.max(cur, hole[1]);
		}
		if (cur < end) {
			ctx.exceptionTable.add(new ByteCodeWriter.ExceptionTableEntry(cur, end, handler, 0));
		}
	}

}
