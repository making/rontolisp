package am.ik.rontolisp.codegen.jvm;

import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.FieldRefEntry;
import java.lang.classfile.constantpool.MethodRefEntry;
import java.lang.classfile.constantpool.Utf8Entry;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import am.ik.jvm.ConstantPool;
import am.ik.jvm.MethodCode;
import am.ik.rontolisp.ClosRegistry;
import am.ik.rontolisp.compiler.OperandTypes;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.macro.LispMacroExpander;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispLayout;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;

/**
 * Compiles {@code (handler-case expr (type ([var]) body...)... [(:no-error ([var])
 * body...)])}: the expression runs inside a catch-any exception-table region (the
 * unwind-protect machinery); the handler takes the typed condition recorded for the
 * throwable it caught off the per-thread {@code _condTl} channel (recorded by
 * {@code %error-cond}, keyed by the throwable: {@link JvmThrowableRecords}), synthesizes
 * an instance from the exception message when there is none (a plain {@code %error} or a
 * raw runtime exception -- the CLASS then comes from what the throwable is, see
 * {@code emitSynthesizeCondition}), dispatches it through the clauses' type tests --
 * ordinary compiled Lisp forms over a pseudo-local holding the condition -- and rethrows,
 * the condition recorded again, when none matches. The per-thread handler depth is
 * incremented around the protected region so {@code signal} raises only under an
 * established handler; a {@code return} exiting the region decrements it through the
 * {@code UnwindScope} cleanup channel ({@code %hc-depth-dec}).
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
		// In restart mode what a landing takes may be a record saying the handler-bind
		// handlers ran (JvmThrowableRecords.COND_RAN): the clauses dispatch on the
		// condition it names, and a rethrow puts the record back as it came, so the
		// fact rides on to the pads further out.
		boolean records = ctx.restartMode;
		if (records) {
			channel.ensureHandlersRan(ctx.cp, className);
		}
		else {
			channel.ensure(ctx.cp, className);
		}
		int savedNextLocal = ctx.nextLocal;
		// Entering the handler discards the operand stack, so the values the enclosing
		// form had already evaluated are saved into locals and reloaded past the merge:
		// both edges into it then arrive with the same (empty) stack. A handler-case
		// compiled as a statement spills nothing and is byte-identical to before.
		JvmLispCompiler.Ctx.Spill spill = ctx.spillOperandStack();
		int resultSlot = ctx.allocTemp();
		int excSlot = ctx.allocTemp();
		int condSlot = ctx.allocTemp();
		// The record rides the result slot: on the landing path nothing stores there
		// until a clause body answers, and the no-match rethrow reads it before any
		// does. A slot of its own would lengthen later stack-map frames of the method.
		int recordSlot = records ? resultSlot : condSlot;
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
		MethodCode.Label start = ctx.body.newBoundLabel();
		// In restart mode -- and under the signal-point clause match -- the clause
		// types also go on the DYNAMIC handler stack for the protected extent, so
		// %run-handlers stops at this handler-case instead of running an enclosing
		// handler-bind's handler for a condition this form is nearer to, and
		// %signal-cond can decline this handler-case when no clause matches
		// (%hc-match-p). With both gates off the form is unchanged, byte for byte.
		//
		// The protected form is ALSO rewritten through spillEscapingMvProducers when
		// the form has a :no-error clause: the consumer binds the protected form's
		// full VALUES list (a (values ...) tail, a values-list, a producing call), so
		// a syntactic producer (gethash, floor-family, find-symbol, intern,
		// array-displacement) must publish its secondary value to %mv-spill for the
		// no-error clause to read it. Programs with no :no-error clause keep the
		// unchanged protected form, byte for byte.
		LispVal protectedForm = LispMacroExpander.handlerCaseProtectedForm(parts.get(1),
				errorClauses.stream().map(clauseParts -> clauseParts.get(0)).toList(), ctx.closRegistry,
				ctx.restartMode || ctx.signalClauseMatch);
		if (noErrorClause != null) {
			protectedForm = LispMacroExpander.settleMvTail(protectedForm);
		}
		JvmExprCompiler.compileExpr(protectedForm, ctx, className);
		MethodCode.Label end = ctx.body.newBoundLabel();
		ctx.unwindScopes.pop();
		ctx.body.astore(resultSlot);
		// Normal completion: depth--, then the :no-error clause (outside the protected
		// region -- an error signaled by it is not caught by this handler-case).
		emitDepthAdjust(ctx, className, false);
		if (noErrorClause != null) {
			compileNoErrorClauseBody(noErrorClause, resultSlot, resultSlot, ctx, className);
		}
		MethodCode.Label done = ctx.body.newLabel();
		ctx.body.goto_(done);
		// Handler: depth--, take the condition the caught throwable carries, synthesize a
		// simple-error from the message when it carries none, dispatch through the
		// clauses.
		MethodCode.Label handler = ctx.body.newBoundLabel();
		ctx.stack.enterHandler();
		ctx.body.astore(excSlot);
		emitDepthAdjust(ctx, className, false);
		// A cross-lambda non-local exit unwinding through this region rides a plain
		// RuntimeException that this catch-any handler would otherwise swallow as a
		// synthesized condition. Rethrow it (identity-checked against the pending _nleTl)
		// before dispatching, so return-from is never intercepted by handler-case. Gated
		// so a program without a cross-lambda exit stays byte-identical.
		if (ctx.blockExitChannel) {
			emitRethrowPendingNle(ctx, className, excSlot);
		}
		emitTakeCondition(excSlot, condSlot, ctx, className);
		if (records) {
			// What the landing took is the record -- a condition-less throw's being the
			// instance just synthesized, as outside restart mode: keep it for a rethrow
			// and dispatch on the condition it names. Unwrapped only past the synthesis:
			// while it was emitted inline, its many branch targets would otherwise all
			// have carried the record slot in their stack-map frames (+804 B of
			// StackMapTable on the ci-spec pin's program before the synthesis, +40 B
			// after it, 2026-09-27).
			ctx.body.aload(condSlot).dup().astore(recordSlot);
			ctx.body.invokestatic(Objects.requireNonNull(channel.condOf)).astore(condSlot);
		}
		// Dispatch: the condition rides a pseudo-local so the type tests and clause
		// bodies compile as ordinary Lisp forms.
		String condVarName = "__hc_cond$" + condSlot;
		LispSymbol condVarSym = new LispSymbol(condVarName);
		ctx.locals.put(condVarName, condSlot);
		try {
			for (List<LispVal> clauseParts : errorClauses) {
				LispVal test = LispMacroExpander.makeHandlerTypeTest(condVarSym, clauseParts.get(0), ctx.closRegistry);
				JvmExprCompiler.compileExpr(test, ctx, className);
				MethodCode.Label ifNoMatchPos = ctx.body.newLabel();
				ctx.body.ifnull(ifNoMatchPos);
				compileClauseBody(clauseParts, condSlot, resultSlot, ctx, className);
				ctx.body.goto_(done);
				ctx.body.labelBinding(ifNoMatchPos);
			}
		}
		finally {
			ctx.locals.remove(condVarName);
		}
		// No clause matched: record the condition under the throwable again (an outer
		// handler-case must see the typed instance, not a re-synthesized
		// simple-error) and rethrow it.
		ctx.body.aload(excSlot).aload(recordSlot);
		ctx.body.invokestatic(Objects.requireNonNull(channel.condPut)).athrow();
		ctx.body.labelBinding(done);
		if (!spill.live().isEmpty()) {
			ctx.spillScopes.pop();
			spill.restore(ctx);
		}
		ctx.body.aload(resultSlot);
		scope.catchAny(ctx.body, start, end, handler);
		ctx.nextLocal = savedNextLocal;
	}

	/**
	 * Compiles the internal {@code (%hb-guard body)} landing pad the {@code handler-bind}
	 * expansion wraps its body in: a catch-any region over the body whose handler takes
	 * the condition the caught throwable carries, synthesizes the {@code simple-error} of
	 * a condition-less throw (a raw runtime failure, a plain {@code %error}), runs the
	 * {@code handler-bind} cluster stack through {@code %run-handlers} unless the record
	 * says they already ran (the restart-mode signal hook runs handlers at the signal
	 * point and its terminal records {@code _condRan}; so does a pad nearer the signal),
	 * records the instance under the throwable again, saying the handlers ran, so an
	 * outer {@code handler-case} or guard sees the same one, and rethrows. Unlike
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
		MethodRefEntry landingPad = guardLandingPad(ctx, className);
		int savedNextLocal = ctx.nextLocal;
		JvmLispCompiler.Ctx.Spill spill = ctx.spillOperandStack();
		int resultSlot = ctx.allocTemp();
		if (!spill.live().isEmpty()) {
			ctx.spillScopes.push(new JvmLispCompiler.SpillScope(spill, ctx.blockTargets.size()));
		}
		MethodCode.Label start = ctx.body.newBoundLabel();
		JvmExprCompiler.compileExpr(parts.get(1), ctx, className);
		MethodCode.Label end = ctx.body.newBoundLabel();
		MethodCode.Label done = ctx.body.newLabel();
		ctx.body.astore(resultSlot).goto_(done);
		// The pad itself is the shared method: it takes the caught throwable and answers
		// it, so the site is a call and a rethrow whatever the program's condition
		// classes are.
		MethodCode.Label handler = ctx.body.newBoundLabel();
		ctx.stack.enterHandler();
		ctx.body.invokestatic(landingPad).athrow();
		ctx.body.labelBinding(done);
		if (!spill.live().isEmpty()) {
			ctx.spillScopes.pop();
			spill.restore(ctx);
		}
		ctx.body.aload(resultSlot);
		if (start.position() < end.position()) {
			ctx.body.exceptionCatch(start, end, handler, null);
		}
		ctx.nextLocal = savedNextLocal;
	}

	/**
	 * The descriptor of the shared landing-pad method: the caught throwable in, the same
	 * throwable out, so the call site rethrows it and the verifier sees a
	 * {@code Throwable} under the {@code athrow}.
	 */
	private static final String GUARD_PAD_DESC = "(Ljava/lang/Throwable;)Ljava/lang/Throwable;";

	/**
	 * {@return the shared {@code %hb-guard} landing-pad method, built on first use}
	 *
	 * Every {@code handler-bind} in a program emits the SAME pad -- take the caught
	 * throwable's condition, synthesize the instance of a condition-less throw, run the
	 * cluster stack unless the record says it already ran, record the instance again --
	 * over nothing but the caught throwable, so it is emitted once per class and called
	 * from each site. It had to be: the pad was ~500 bytecodes while it held the
	 * synthesis (now the shared {@link #conditionSynthesizer}, which leaves it ~80), and
	 * in restart mode {@code restart-case} expands through {@code handler-bind}, so a
	 * function that writes {@code check-type} in a macro used forty times carried forty
	 * copies of it -- 20 KB, most of {@code fast-http}'s
	 * {@code parse-header-field-and-value} being past HotSpot's {@code HugeMethodLimit}
	 * ({@code .kb/hot-path-method-size.md}).
	 */
	private static MethodRefEntry guardLandingPad(JvmLispCompiler.Ctx ctx, String className) {
		JvmLispCompiler.ConditionChannel channel = ctx.conditionChannel;
		if (channel.hbGuardPad != null) {
			return channel.hbGuardPad;
		}
		String methodName = "_hbGuard";
		Utf8Entry nameUtf8 = ctx.cp.utf8Entry(methodName);
		Utf8Entry descUtf8 = ctx.cp.utf8Entry(GUARD_PAD_DESC);
		MethodRefEntry ref = JvmEmitHelper.selfMethod(ctx, className, methodName, GUARD_PAD_DESC);
		// Recorded BEFORE the body is emitted: the body compiles ordinary Lisp forms,
		// and a nested handler-bind in one of them must find the pad already claimed
		// rather than start a second.
		channel.hbGuardPad = ref;
		JvmLispCompiler.Ctx pad = ctx.ctxBuilder.build();
		pad.evalStoreRef = ctx.evalStoreRef;
		pad.nextLocal = 1;
		pad.maxLocals = 1;
		emitGuardPadBody(pad, className);
		ctx.outlinedBodies.add(new JvmBodyOutliner.OutlinedBody(methodName, nameUtf8, descUtf8, pad));
		return ref;
	}

	/**
	 * Emits the landing pad's body over its single parameter (slot 0, the caught
	 * throwable), answering that throwable so the call site can rethrow it. A record
	 * saying the handlers already ran goes back as it came; otherwise the pad runs them
	 * and records that it did ({@code _condRan}), so every pad further out -- and only
	 * this flight's -- passes the throwable on.
	 */
	private static void emitGuardPadBody(JvmLispCompiler.Ctx ctx, String className) {
		JvmLispCompiler.ConditionChannel channel = ctx.conditionChannel;
		channel.ensureHandlersRan(ctx.cp, className);
		int excSlot = 0;
		int recordSlot = ctx.allocTemp();
		int condSlot = ctx.allocTemp();
		// A cross-lambda non-local exit must pass through untouched (same gate as
		// handler-case).
		if (ctx.blockExitChannel) {
			emitRethrowPendingNle(ctx, className, excSlot);
		}
		emitTakeRecord(excSlot, recordSlot, condSlot, ctx);
		// A record that is not its own condition is a _condRan record: the handlers ran
		// (at the signal point, or at a pad nearer it). Put it back and pass it on.
		ctx.body.aload(condSlot).aload(recordSlot);
		MethodCode.Label ifNotRanPos = ctx.body.newLabel();
		ctx.body.if_acmpeq(ifNotRanPos);
		ctx.body.aload(excSlot).aload(recordSlot);
		ctx.body.invokestatic(Objects.requireNonNull(channel.condPut)).areturn();
		ctx.body.labelBinding(ifNotRanPos);
		emitSynthesizeUnlessRecorded(excSlot, condSlot, ctx, className);
		// Run the cluster stack, as an ordinary Lisp form over the condition
		// pseudo-local.
		String condVarName = "__hb_cond$" + condSlot;
		ctx.locals.put(condVarName, condSlot);
		try {
			JvmExprCompiler.compileExpr(LispMacroExpander.hbGuardHandlerForm(new LispSymbol(condVarName)), ctx,
					className);
		}
		finally {
			ctx.locals.remove(condVarName);
		}
		ctx.body.pop();
		// Record the instance under the throwable again, saying the handlers ran (the
		// outer catcher must see the instance the handlers saw, a synthesized one
		// included), and hand the throwable back to be rethrown.
		ctx.body.aload(excSlot).aload(condSlot);
		ctx.body.invokestatic(Objects.requireNonNull(channel.condRan)).areturn();
	}

	/**
	 * Emits a landing's read of what it caught: the condition recorded for the throwable
	 * in {@code excSlot}, taken off the channel, into {@code condSlot} -- or, when it
	 * carries none, the instance {@link #emitSynthesizeCondition} makes of it.
	 */
	private static void emitTakeCondition(int excSlot, int condSlot, JvmLispCompiler.Ctx ctx, String className) {
		ctx.body.aload(excSlot).invokestatic(Objects.requireNonNull(ctx.conditionChannel.condTake));
		ctx.body.astore(condSlot);
		emitSynthesizeUnlessRecorded(excSlot, condSlot, ctx, className);
	}

	/**
	 * Emits a restart-mode landing's read of what it caught, where a record may say the
	 * {@code handler-bind} handlers ran ({@link JvmThrowableRecords#COND_RAN}): the
	 * record taken off the channel into {@code recordSlot}, and the condition it names --
	 * null when there is none -- into {@code condSlot}.
	 */
	private static void emitTakeRecord(int excSlot, int recordSlot, int condSlot, JvmLispCompiler.Ctx ctx) {
		ctx.body.aload(excSlot);
		ctx.body.invokestatic(Objects.requireNonNull(ctx.conditionChannel.condTake)).dup();
		ctx.body.astore(recordSlot);
		ctx.body.invokestatic(Objects.requireNonNull(ctx.conditionChannel.condOf)).astore(condSlot);
	}

	/**
	 * Emits the synthesis of the instance a condition-less throw stands for into
	 * {@code condSlot} when that slot holds null: a call of the shared
	 * {@link #conditionSynthesizer}.
	 */
	private static void emitSynthesizeUnlessRecorded(int excSlot, int condSlot, JvmLispCompiler.Ctx ctx,
			String className) {
		MethodRefEntry synthesizer = conditionSynthesizer(ctx, className);
		ctx.body.aload(condSlot);
		MethodCode.Label ifHaveCondPos = ctx.body.newLabel();
		ctx.body.ifnonnull(ifHaveCondPos);
		ctx.body.aload(excSlot).invokestatic(synthesizer).astore(condSlot);
		ctx.body.labelBinding(ifHaveCondPos);
	}

	/**
	 * The descriptor of the shared synthesis method: the caught throwable in, the
	 * condition instance it stands for out.
	 */
	private static final String SYNTHESIZER_DESC = "(Ljava/lang/Throwable;)Ljava/lang/Object;";

	/**
	 * {@return the shared synthesis method ({@code _hcSynth}), built on first use}
	 *
	 * The synthesis ({@link #emitSynthesizeCondition}) depends on nothing but the caught
	 * throwable and is ~450 bytecodes with a dozen branch targets -- the host-text
	 * overrides, the quote framing, one construction per raw-failure class. Emitted
	 * inline it made every {@code handler-case} / {@code ignore-errors} landing that
	 * size: a one-call {@code ignore-errors} function was 945 bytes of code and 211 of
	 * StackMapTable. One copy per class serves every landing and the {@code _hbGuard}
	 * pad; the site is a null test, a call and a store.
	 */
	private static MethodRefEntry conditionSynthesizer(JvmLispCompiler.Ctx ctx, String className) {
		JvmLispCompiler.ConditionChannel channel = ctx.conditionChannel;
		if (channel.conditionSynthesizer != null) {
			return channel.conditionSynthesizer;
		}
		String methodName = "_hcSynth";
		Utf8Entry nameUtf8 = ctx.cp.utf8Entry(methodName);
		Utf8Entry descUtf8 = ctx.cp.utf8Entry(SYNTHESIZER_DESC);
		MethodRefEntry ref = JvmEmitHelper.selfMethod(ctx, className, methodName, SYNTHESIZER_DESC);
		// Recorded BEFORE the body is emitted, as for the guard pad: the arms compile
		// ordinary Lisp forms.
		channel.conditionSynthesizer = ref;
		JvmLispCompiler.Ctx synth = ctx.ctxBuilder.build();
		synth.evalStoreRef = ctx.evalStoreRef;
		synth.nextLocal = 1;
		synth.maxLocals = 1;
		int condSlot = synth.allocTemp();
		emitSynthesizeCondition(0, condSlot, synth, className);
		synth.body.aload(condSlot).areturn();
		ctx.outlinedBodies.add(new JvmBodyOutliner.OutlinedBody(methodName, nameUtf8, descUtf8, synth));
		return ref;
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
		ctx.body.getstatic(Objects.requireNonNull(channel.nleTlField));
		ctx.body.invokevirtual(Objects.requireNonNull(channel.tlGet)).astore(nleSlot);
		MethodCode.Label proceed = ctx.body.newLabel();
		ctx.body.aload(nleSlot).ifnull(proceed);
		ctx.body.aload(nleSlot).checkcast(ctx.objectArrayClass).iconst_0().aaload();
		ctx.body.aload(excSlot).if_acmpne(proceed);
		ctx.body.aload(excSlot).athrow();
		ctx.body.labelBinding(proceed);
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
		JvmIntFusionCompiler.RawLocal shadowedRaw = null;
		Integer shadowedRawDouble = null;
		java.util.Set<String> savedBoxedVars = ctx.boxedVars;
		if (clauseParts.get(1) instanceof LispCons varList && varList.car() instanceof LispSymbol var) {
			varName = var.name();
			shadowedSlot = ctx.locals.put(varName, valueSlot);
			// The clause variable is a fresh plain binding of the name: an outer
			// unboxed dual-representation local, raw double local or boxed (captured)
			// binding of the same name must not answer reads inside the clause body --
			// compileSymbolRef resolves those representations before the plain slot.
			shadowedRaw = ctx.rawLocals.remove(varName);
			shadowedRawDouble = ctx.rawDoubleLocals.remove(varName);
			if (ctx.boxedVars.contains(varName)) {
				ctx.boxedVars = new java.util.HashSet<>(savedBoxedVars);
				ctx.boxedVars.remove(varName);
			}
		}
		try {
			if (clauseParts.size() <= 2) {
				ctx.body.aconst_null();
			}
			else {
				for (int i = 2; i < clauseParts.size(); i++) {
					if (i > 2) {
						ctx.body.pop();
					}
					JvmExprCompiler.compileExpr(clauseParts.get(i), ctx, className);
				}
			}
			ctx.body.astore(resultSlot);
		}
		finally {
			if (varName != null) {
				if (shadowedSlot != null) {
					ctx.locals.put(varName, shadowedSlot);
				}
				else {
					ctx.locals.remove(varName);
				}
				if (shadowedRaw != null) {
					ctx.rawLocals.put(varName, shadowedRaw);
				}
				if (shadowedRawDouble != null) {
					ctx.rawDoubleLocals.put(varName, shadowedRawDouble);
				}
				ctx.boxedVars = savedBoxedVars;
			}
		}
	}

	/**
	 * Compiles a {@code :no-error} clause body -- {@code (:no-error ([var...]) body...)}
	 * -- binding each variable to the protected form's VALUES, the same shape
	 * {@code multiple-value-bind} uses (.kb/multiple-values.md, "missing -> nil, surplus
	 * evaluated and dropped"). The variable list here is the required-only shape:
	 * {@code &optional}/{@code &rest}/{@code &key} are not accepted.
	 *
	 * <p>
	 * The primary value is already in {@code valueSlot} (the protected form's result),
	 * and the {@code %mv-spill} channel carries the secondary values (a list, or nil when
	 * the program never publishes). The spill global exists only when the program uses a
	 * multiple-value operator ({@link LispMacroExpander#injectMvSpillGlobal}); when it
	 * does not, no spill was ever published, so we initialize the local to nil and every
	 * extra variable binds to nil. Either way the consumer reads {@code (nth i spill)},
	 * which is well-defined on nil (returns nil) -- so a missing value is nil and a
	 * surplus value (beyond the variable count) is simply not read, never observed.
	 *
	 * <p>
	 * The spill global is cleared after the snapshot so the clause body runs on a clean
	 * channel (a clause that itself publishes through the spill starts fresh).
	 */
	private static void compileNoErrorClauseBody(List<LispVal> clauseParts, int valueSlot, int resultSlot,
			JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> varVals = clauseParts.get(1) instanceof LispCons varList ? varList.toList() : List.of();
		// The spill snapshot: the protected form's secondary values list. Allocated
		// unconditionally so the (nth N spill) form below has a real local to read;
		// when the program has no spill global we seed it with nil directly.
		int spillSlot = ctx.allocTemp();
		JvmMvChannel spill = ctx.mvChannel;
		if (spill != null) {
			spill.emitLoad(ctx);
			ctx.body.astore(spillSlot);
			spill.emitClear(ctx);
		}
		else {
			ctx.body.aconst_null().astore(spillSlot);
		}
		String spillVarName = "__hc_ne_spill$" + spillSlot;
		// Save any shadowed bindings (one per clause variable) and a fresh boxed-vars
		// set, restore them on the way out.
		java.util.Map<String, Integer> shadowedSlots = new java.util.HashMap<>();
		java.util.Map<String, JvmIntFusionCompiler.RawLocal> shadowedRaws = new java.util.HashMap<>();
		java.util.Map<String, Integer> shadowedRawDoubles = new java.util.HashMap<>();
		java.util.Set<String> savedBoxedVars = ctx.boxedVars;
		ctx.boxedVars = new java.util.HashSet<>(savedBoxedVars);
		ctx.locals.put(spillVarName, spillSlot);
		try {
			for (int i = 0; i < varVals.size(); i++) {
				LispVal varVal = varVals.get(i);
				if (!(varVal instanceof LispSymbol sym)) {
					throw new IllegalArgumentException(
							LispNames.HANDLER_CASE + " :no-error variable must be a symbol: " + varVal.print());
				}
				String varName = sym.name();
				int slot;
				if (i == 0) {
					// Primary value: the protected form's resultSlot already holds it.
					slot = valueSlot;
				}
				else {
					slot = ctx.allocTemp();
					// (nth (i-1) spill) -- nth on nil returns nil, the missing-value
					// fill; the zero-values marker (t) reads as the empty list.
					LispVal nthCall = new LispCons(new LispSymbol(LispNames.NTH),
							new LispCons(new LispInteger(i - 1), new LispCons(
									LispMacroExpander.spillAsList(new LispSymbol(spillVarName)), LispNil.INSTANCE)));
					JvmExprCompiler.compileExpr(nthCall, ctx, className);
					ctx.body.astore(slot);
				}
				// Same shadowing discipline as the error-clause path: an outer unboxed /
				// raw-double / boxed binding of the same name must not answer reads
				// inside the clause body.
				shadowedSlots.put(varName, ctx.locals.put(varName, slot));
				JvmIntFusionCompiler.RawLocal raw = ctx.rawLocals.remove(varName);
				if (raw != null) {
					shadowedRaws.put(varName, raw);
				}
				Integer rawDouble = ctx.rawDoubleLocals.remove(varName);
				if (rawDouble != null) {
					shadowedRawDoubles.put(varName, rawDouble);
				}
				if (ctx.boxedVars.contains(varName)) {
					ctx.boxedVars.remove(varName);
				}
			}
			if (clauseParts.size() <= 2) {
				ctx.body.aconst_null();
			}
			else {
				for (int i = 2; i < clauseParts.size(); i++) {
					if (i > 2) {
						ctx.body.pop();
					}
					JvmExprCompiler.compileExpr(clauseParts.get(i), ctx, className);
				}
			}
			ctx.body.astore(resultSlot);
		}
		finally {
			ctx.locals.remove(spillVarName);
			for (java.util.Map.Entry<String, Integer> e : shadowedSlots.entrySet()) {
				Integer prev = e.getValue();
				if (prev != null) {
					ctx.locals.put(e.getKey(), prev);
				}
				else {
					ctx.locals.remove(e.getKey());
				}
			}
			for (java.util.Map.Entry<String, JvmIntFusionCompiler.RawLocal> e : shadowedRaws.entrySet()) {
				ctx.rawLocals.put(e.getKey(), e.getValue());
			}
			for (java.util.Map.Entry<String, Integer> e : shadowedRawDoubles.entrySet()) {
				ctx.rawDoubleLocals.put(e.getKey(), e.getValue());
			}
			ctx.boxedVars = savedBoxedVars;
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
		ClassEntry throwableClass = ctx.cp.classEntry("java/lang/Throwable");
		MethodRefEntry getMessage = ctx.cp.methodRef(throwableClass, "getMessage", "()Ljava/lang/String;");
		MethodRefEntry concat = JvmEmitHelper.stringMethod(ctx, "concat", "(Ljava/lang/String;)Ljava/lang/String;");
		int rawSlot = ctx.allocTemp();
		ctx.body.aload(excSlot).invokevirtual(getMessage).astore(rawSlot);
		emitHostTextOverride(excSlot, rawSlot, "java/lang/ClassCastException", ClosRegistry.TYPE_ERROR_MESSAGE, ctx);
		emitHostTextOverride(excSlot, rawSlot, "java/lang/IndexOutOfBoundsException",
				ClosRegistry.INDEX_OUT_OF_BOUNDS_MESSAGE, ctx);
		int msgSlot = ctx.allocTemp();
		ctx.body.aload(rawSlot).dup();
		MethodCode.Label ifNullMsgPos = ctx.body.newLabel();
		ctx.body.ifnull(ifNullMsgPos);
		// "\"" + msg + "\"" -- the quote-framed runtime string representation.
		JvmEmitHelper.compileStringLiteral("\"", ctx);
		ctx.body.swap().invokevirtual(concat);
		JvmEmitHelper.compileStringLiteral("\"", ctx);
		ctx.body.invokevirtual(concat);
		MethodCode.Label gotoHavePos = ctx.body.newLabel();
		ctx.body.goto_(gotoHavePos);
		ctx.body.labelBinding(ifNullMsgPos);
		ctx.body.pop().aconst_null();
		ctx.body.labelBinding(gotoHavePos);
		ctx.body.astore(msgSlot);
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
		MethodCode.Label skip = ctx.body.newLabel();
		emitInstanceOfJump(excSlot, type, ctx, false, skip);
		JvmEmitHelper.compileStringLiteral(text, ctx);
		ctx.body.astore(rawSlot);
		ctx.body.labelBinding(skip);
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
		MethodCode.Label joins = ctx.body.newLabel();
		if (ctx.javaSites != null && ctx.closRegistry
			.findLayoutByTag(LispLayout.CLASS_TAG_PREFIX + ClosRegistry.JAVA_EXCEPTION_CLASS_NAME) != null) {
			emitJavaExceptionArm(excSlot, condSlot, msgVar, ctx, className, joins);
		}
		for (int i = 0; i < classes.size(); i++) {
			MethodCode.Label skip = ctx.body.newLabel();
			emitRawFailureTest(i, excSlot, rawSlot, ctx, skip);
			if (i == 0 && ctx.numOps.containsKey(JvmOperandTypeRuntime.TE_SLOT)) {
				// The type-error arm: a wrong-type operand's datum and expected type come
				// from its record, nil for any other type failure.
				emitTypeErrorConstruction(excSlot, condSlot, classes.get(i), msgVar, ctx, className);
			}
			else if (ClosRegistry.UNDEFINED_FUNCTION_CLASS_NAME.equals(classes.get(i))) {
				emitUndefinedFunctionConstruction(rawSlot, condSlot, msgVar, ctx, className);
			}
			else {
				JvmExprCompiler.compileExpr(
						LispMacroExpander.reportingConditionForm(ctx.closRegistry, classes.get(i), msgVar), ctx,
						className);
				ctx.body.astore(condSlot);
			}
			ctx.body.goto_(joins);
			ctx.body.labelBinding(skip);
		}
		LispVal quotedTag = new LispCons(new LispSymbol(LispNames.QUOTE),
				new LispCons(new LispSymbol(LispLayout.CLASS_TAG_PREFIX + "SIMPLE-ERROR"), LispNil.INSTANCE));
		LispVal instance = new LispCons(new LispSymbol(LispNames.OBJ_NEW),
				new LispCons(quotedTag, new LispCons(LispMacroExpander.textControlForm(msgVar),
						new LispCons(LispNil.INSTANCE, LispNil.INSTANCE))));
		JvmExprCompiler.compileExpr(instance, ctx, className);
		ctx.body.astore(condSlot);
		ctx.body.labelBinding(joins);
	}

	/**
	 * Emits the arm of a {@code java:} member that threw, ahead of the raw-failure arms:
	 * the throwable {@code _jfail} recorded for the caught exception ({@code _jexMap}),
	 * when there is one, makes the instance a {@code java:java-exception} carrying it --
	 * the interpreter's {@code JavaInterop.fail}, synthesized at its landing alike.
	 */
	private static void emitJavaExceptionArm(int excSlot, int condSlot, LispSymbol msgVar, JvmLispCompiler.Ctx ctx,
			String className, MethodCode.Label joins) {
		FieldRefEntry map = ctx.conditionChannel.ensureJavaExceptions(ctx.cp, className);
		int causeSlot = ctx.allocTemp();
		MethodCode.Label skip = ctx.body.newLabel();
		ctx.body.getstatic(map).aload(excSlot);
		ctx.body.invokeinterface(
				ctx.cp.interfaceMethodRef("java/util/Map", "get", "(Ljava/lang/Object;)Ljava/lang/Object;"));
		ctx.body.astore(causeSlot);
		ctx.body.aload(causeSlot).ifnull(skip);
		String causeVar = "__hc_jex$" + causeSlot;
		ctx.locals.put(causeVar, causeSlot);
		try {
			JvmExprCompiler.compileExpr(
					LispMacroExpander.reportingConditionForm(ctx.closRegistry, ClosRegistry.JAVA_EXCEPTION_CLASS_NAME,
							msgVar, java.util.Map.of(ClosRegistry.JAVA_EXCEPTION_CAUSE_SLOT, new LispSymbol(causeVar))),
					ctx, className);
		}
		finally {
			ctx.locals.remove(causeVar);
		}
		ctx.body.astore(condSlot);
		ctx.body.goto_(joins);
		ctx.body.labelBinding(skip);
	}

	/**
	 * Emits the {@code undefined-function} arm's construction with {@code name} read back
	 * out of the raw message the arm's test matched: every throw site spells it
	 * {@code The function <name> is undefined} around the symbol itself, which is its
	 * bare name here (a keyword with its colon), and NIL -- null here -- as
	 * {@code "NIL"}. The text is this compiler's own, the same text the class is
	 * recovered from.
	 */
	private static void emitUndefinedFunctionConstruction(int rawSlot, int condSlot, LispSymbol msgVar,
			JvmLispCompiler.Ctx ctx, String className) {
		int nameSlot = ctx.allocTemp();
		ctx.body.aload(rawSlot);
		JvmEmitHelper.emitIntConst(ctx, ClosRegistry.UNDEFINED_FUNCTION_MESSAGE_PREFIX.length());
		ctx.body.aload(rawSlot).invokevirtual(JvmEmitHelper.stringMethod(ctx, "length", "()I"));
		JvmEmitHelper.emitIntConst(ctx, ClosRegistry.UNDEFINED_FUNCTION_MESSAGE_SUFFIX.length());
		ctx.body.isub().invokevirtual(JvmEmitHelper.stringMethod(ctx, "substring", "(II)Ljava/lang/String;"));
		ctx.body.astore(nameSlot);
		MethodCode.Label named = ctx.body.newLabel();
		ctx.body.aload(nameSlot);
		JvmEmitHelper.compileStringLiteral("NIL", ctx);
		ctx.body.invokevirtual(JvmEmitHelper.stringMethod(ctx, "equals", "(Ljava/lang/Object;)Z")).ifeq(named);
		ctx.body.aconst_null().astore(nameSlot);
		ctx.body.labelBinding(named);
		String nameVar = "__hc_name$" + nameSlot;
		ctx.locals.put(nameVar, nameSlot);
		try {
			JvmExprCompiler.compileExpr(LispMacroExpander.reportingConditionForm(ctx.closRegistry,
					ClosRegistry.UNDEFINED_FUNCTION_CLASS_NAME, msgVar,
					java.util.Map.of("NAME", new LispSymbol(nameVar))), ctx, className);
		}
		finally {
			ctx.locals.remove(nameVar);
		}
		ctx.body.astore(condSlot);
	}

	/**
	 * Emits the {@code type-error} arm's construction with {@code datum} and
	 * {@code expected-type} read through {@code _teSlot}, bound as pseudo-locals so the
	 * construction stays an ordinary compiled Lisp form.
	 */
	private static void emitTypeErrorConstruction(int excSlot, int condSlot, String conditionClass, LispSymbol msgVar,
			JvmLispCompiler.Ctx ctx, String className) {
		int datumSlot = ctx.allocTemp();
		int typeSlot = ctx.allocTemp();
		for (int[] slot : new int[][] { { 1, datumSlot }, { 2, typeSlot } }) {
			ctx.body.aload(excSlot);
			JvmEmitHelper.emitIntConst(ctx, slot[0]);
			ctx.body.invokestatic(ctx.numOp(JvmOperandTypeRuntime.TE_SLOT)).astore(slot[1]);
		}
		String datumVar = "__hc_datum$" + datumSlot;
		String typeVar = "__hc_etype$" + typeSlot;
		ctx.locals.put(datumVar, datumSlot);
		ctx.locals.put(typeVar, typeSlot);
		try {
			JvmExprCompiler.compileExpr(
					LispMacroExpander.reportingConditionForm(ctx.closRegistry, conditionClass, msgVar, java.util.Map
						.of("DATUM", new LispSymbol(datumVar), "EXPECTED-TYPE", new LispSymbol(typeVar))),
					ctx, className);
		}
		finally {
			ctx.locals.remove(datumVar);
			ctx.locals.remove(typeVar);
		}
		ctx.body.astore(condSlot);
	}

	/**
	 * Emits the test guarding raw-failure arm {@code index}, its "does not apply" exits
	 * jumping to {@code skip}, which the caller binds at the arm's END. The order mirrors
	 * {@link LispMacroExpander#rawFailureConditionClasses()}: type-error,
	 * division-by-zero, arithmetic-error, unbound-variable, undefined-function,
	 * program-error.
	 */
	private static void emitRawFailureTest(int index, int excSlot, int rawSlot, JvmLispCompiler.Ctx ctx,
			MethodCode.Label skip) {
		switch (index) {
			case 0 -> {
				// A cast failure, an out-of-range index, a wrong-type operand (its
				// exception recorded by identity, JvmOperandTypeRuntime), or a
				// dispatcher's "Not a function: " (JvmRuntimeBuilder.buildNotFnBody):
				// type-error. None is an ArithmeticException, so testing this arm first
				// costs the arithmetic arms nothing. The message test exists because that
				// throw site is a plain RuntimeException with no channel to carry a class
				// (the unbound-variable precedent).
				MethodCode.Label hits = ctx.body.newLabel();
				emitInstanceOfJump(excSlot, "java/lang/ClassCastException", ctx, true, hits);
				if (ctx.numOps.containsKey(JvmOperandTypeRuntime.TE_SLOT)) {
					// A wrong-type operand's exception is recorded under its identity
					// (JvmOperandTypeRuntime), so no message is parsed here.
					ctx.body.aload(excSlot).iconst_0();
					ctx.body.invokestatic(ctx.numOp(JvmOperandTypeRuntime.TE_SLOT));
					ctx.body.ifnonnull(hits);
				}
				else {
					emitMessagePrefixHit(rawSlot, OperandTypes.VALUE_PREFIX, ctx, hits);
				}
				emitMessagePrefixHit(rawSlot, ClosRegistry.NOT_A_FUNCTION_MESSAGE_PREFIX, ctx, hits);
				emitInstanceOfJump(excSlot, "java/lang/IndexOutOfBoundsException", ctx, false, skip);
				ctx.body.labelBinding(hits);
			}
			case 1 -> {
				emitInstanceOfJump(excSlot, "java/lang/ArithmeticException", ctx, false, skip);
				emitMessageTest(rawSlot, "contains", ClosRegistry.DIVISION_BY_ZERO_MESSAGE_TOKEN, ctx, skip);
			}
			case 2 -> emitInstanceOfJump(excSlot, "java/lang/ArithmeticException", ctx, false, skip);
			case 3 -> {
				emitMessageTest(rawSlot, "startsWith", ClosRegistry.UNBOUND_VARIABLE_MESSAGE_PREFIX, ctx, skip);
				emitMessageTest(rawSlot, "endsWith", ClosRegistry.UNBOUND_VARIABLE_MESSAGE_SUFFIX, ctx, skip);
			}
			case 4 -> {
				emitMessageTest(rawSlot, "startsWith", ClosRegistry.UNDEFINED_FUNCTION_MESSAGE_PREFIX, ctx, skip);
				emitMessageTest(rawSlot, "endsWith", ClosRegistry.UNDEFINED_FUNCTION_MESSAGE_SUFFIX, ctx, skip);
			}
			// A dispatcher's or a count guard's wrong-argument-count throw
			// (JvmRuntimeBuilder.ARITY_EXCEPTION_CLASS): recognized by its class, which
			// no other throw site raises -- its text may name any operator, and a user
			// error's may begin like it.
			default -> emitInstanceOfJump(excSlot, JvmRuntimeBuilder.ARITY_EXCEPTION_CLASS, ctx, false, skip);
		}
	}

	/**
	 * Emits {@code exc instanceof <type>} and a jump to {@code target} on the given
	 * outcome.
	 */
	private static void emitInstanceOfJump(int excSlot, String type, JvmLispCompiler.Ctx ctx, boolean jumpWhenTrue,
			MethodCode.Label target) {
		ctx.body.aload(excSlot).instanceOf(ctx.cp.classEntry(type));
		if (jumpWhenTrue) {
			ctx.body.ifne(target);
		}
		else {
			ctx.body.ifeq(target);
		}
	}

	/**
	 * Emits a {@code startsWith} test over the RAW message and a jump taken when it HOLDS
	 * (the OR-shaped twin of {@link #emitMessageTest}, whose jump is the does-not-hold
	 * one), to {@code hit} -- a null message falls through.
	 */
	private static void emitMessagePrefixHit(int rawSlot, String prefix, JvmLispCompiler.Ctx ctx,
			MethodCode.Label hit) {
		ctx.body.aload(rawSlot);
		MethodCode.Label ifNullPos = ctx.body.newLabel();
		ctx.body.ifnull(ifNullPos);
		ctx.body.aload(rawSlot);
		JvmEmitHelper.compileStringLiteral(prefix, ctx);
		ctx.body.invokevirtual(JvmEmitHelper.stringMethod(ctx, "startsWith", "(Ljava/lang/String;)Z"));
		ctx.body.ifne(hit);
		ctx.body.labelBinding(ifNullPos);
	}

	/**
	 * Emits a {@code String} predicate over the RAW message ({@code startsWith} /
	 * {@code endsWith} / {@code contains}) and a jump taken when it does NOT hold -- a
	 * null message counts as not holding. The jump goes to {@code fail}.
	 */
	private static void emitMessageTest(int rawSlot, String method, String argument, JvmLispCompiler.Ctx ctx,
			MethodCode.Label fail) {
		String descriptor = "contains".equals(method) ? "(Ljava/lang/CharSequence;)Z" : "(Ljava/lang/String;)Z";
		ctx.body.aload(rawSlot);
		MethodCode.Label ifNullPos = ctx.body.newLabel();
		ctx.body.ifnull(ifNullPos);
		ctx.body.aload(rawSlot);
		JvmEmitHelper.compileStringLiteral(argument, ctx);
		ctx.body.invokevirtual(JvmEmitHelper.stringMethod(ctx, method, descriptor));
		MethodCode.Label pos = ctx.body.newLabel();
		ctx.body.ifne(pos);
		ctx.body.labelBinding(ifNullPos);
		ctx.body.goto_(fail);
		ctx.body.labelBinding(pos);
	}

	/**
	 * Emits an increment ({@code up}) or decrement of the per-thread handler-depth
	 * counter: {@code _hcDepthTl.set(Integer.valueOf(read() +/- 1))}, where a null value
	 * reads as 0.
	 */
	static void emitDepthAdjust(JvmLispCompiler.Ctx ctx, String className, boolean up) {
		JvmLispCompiler.ConditionChannel channel = ctx.conditionChannel;
		channel.ensure(ctx.cp, className);
		ctx.body.getstatic(Objects.requireNonNull(channel.depthTlField));
		emitReadDepth(ctx, className);
		ctx.body.iconst_1();
		if (up) {
			ctx.body.iadd();
		}
		else {
			ctx.body.isub();
		}
		ctx.body.invokestatic(ctx.integerValueOf);
		ctx.body.invokevirtual(Objects.requireNonNull(channel.tlSet));
	}

	/**
	 * Emits the read of the per-thread handler depth as an {@code int} on the operand
	 * stack (null = 0).
	 */
	static void emitReadDepth(JvmLispCompiler.Ctx ctx, String className) {
		JvmLispCompiler.ConditionChannel channel = ctx.conditionChannel;
		channel.ensure(ctx.cp, className);
		ctx.body.getstatic(Objects.requireNonNull(channel.depthTlField));
		ctx.body.invokevirtual(Objects.requireNonNull(channel.tlGet)).dup();
		MethodCode.Label ifNullPos = ctx.body.newLabel();
		ctx.body.ifnull(ifNullPos);
		ctx.body.checkcast(ctx.integerClass).invokevirtual(ctx.integerValue);
		MethodCode.Label gotoHavePos = ctx.body.newLabel();
		ctx.body.goto_(gotoHavePos);
		ctx.body.labelBinding(ifNullPos);
		ctx.body.pop().iconst_0();
		ctx.body.labelBinding(gotoHavePos);
	}

	/**
	 * Emits the {@code %hc-depth-dec} internal form: decrements the handler depth and
	 * yields nil (the {@code UnwindScope} cleanup channel pops the value).
	 */
	static void compileDepthDec(JvmLispCompiler.Ctx ctx, String className) {
		emitDepthAdjust(ctx, className, false);
		ctx.body.aconst_null();
	}

}
