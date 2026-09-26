package am.ik.rontolisp.codegen.jvm;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import am.ik.jvm.ByteCodeWriter;
import am.ik.jvm.ConstantPool;
import am.ik.jvm.Opcode;
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
		int end = ctx.code.size();
		ctx.unwindScopes.pop();
		ctx.emit(Opcode.ASTORE);
		ctx.emit(resultSlot);
		// Normal completion: depth--, then the :no-error clause (outside the protected
		// region -- an error signaled by it is not caught by this handler-case).
		emitDepthAdjust(ctx, className, false);
		if (noErrorClause != null) {
			compileNoErrorClauseBody(noErrorClause, resultSlot, resultSlot, ctx, className);
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
		ConstantPool.MethodrefConstant landingPad = guardLandingPad(ctx, className);
		int savedNextLocal = ctx.nextLocal;
		JvmLispCompiler.Ctx.Spill spill = ctx.spillOperandStack();
		int resultSlot = ctx.allocTemp();
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
		// The pad itself is the shared method: it takes the caught throwable and answers
		// it, so the site is a call and a rethrow whatever the program's condition
		// classes are.
		int handler = ctx.code.size();
		ctx.stack.enterHandler();
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(landingPad.index());
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
	 * The descriptor of the shared landing-pad method: the caught throwable in, the same
	 * throwable out, so the call site rethrows it and the verifier sees a
	 * {@code Throwable} under the {@code athrow}.
	 */
	private static final String GUARD_PAD_DESC = "(Ljava/lang/Throwable;)Ljava/lang/Throwable;";

	/**
	 * {@return the shared {@code %hb-guard} landing-pad method, built on first use}
	 *
	 * Every {@code handler-bind} in a program emits the SAME pad -- read and clear the
	 * condition channel, synthesize the instance of a condition-less throw, run the
	 * cluster stack unless it already ran for this instance, restore the channel -- over
	 * nothing but the caught throwable, so it is emitted once per class and called from
	 * each site. It has to be: the pad is ~500 bytecodes (the classification switch
	 * builds one condition instance per raw-failure class), and in restart mode
	 * {@code restart-case} expands through {@code handler-bind}, so a function that
	 * writes {@code check-type} in a macro used forty times carried forty copies of it --
	 * 20 KB, most of {@code fast-http}'s {@code parse-header-field-and-value} being past
	 * HotSpot's {@code HugeMethodLimit} ({@code .kb/hot-path-method-size.md}).
	 */
	private static ConstantPool.MethodrefConstant guardLandingPad(JvmLispCompiler.Ctx ctx, String className) {
		JvmLispCompiler.ConditionChannel channel = ctx.conditionChannel;
		if (channel.hbGuardPad != null) {
			return channel.hbGuardPad;
		}
		String methodName = "_hbGuard";
		ConstantPool.Utf8Constant nameUtf8 = ctx.cp.addUtf8(methodName);
		ConstantPool.Utf8Constant descUtf8 = ctx.cp.addUtf8(GUARD_PAD_DESC);
		ConstantPool.MethodrefConstant ref = JvmEmitHelper.selfMethod(ctx, className, methodName, GUARD_PAD_DESC);
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
	 * throwable), answering that throwable so the call site can rethrow it.
	 */
	private static void emitGuardPadBody(JvmLispCompiler.Ctx ctx, String className) {
		JvmLispCompiler.ConditionChannel channel = ctx.conditionChannel;
		int excSlot = 0;
		int condSlot = ctx.allocTemp();
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
		// saw, a synthesized one included) and hand the throwable back to be rethrown.
		ctx.emit(Opcode.GETSTATIC);
		ctx.emitU2(Objects.requireNonNull(channel.condTlField).index());
		ctx.emit(Opcode.ALOAD);
		ctx.emit(condSlot);
		ctx.emit(Opcode.INVOKEVIRTUAL);
		ctx.emitU2(Objects.requireNonNull(channel.tlSet).index());
		ctx.emit(Opcode.ALOAD);
		ctx.emit(excSlot);
		ctx.emit(Opcode.ARETURN);
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
			ctx.emit(Opcode.ASTORE);
			ctx.emit(spillSlot);
			spill.emitClear(ctx);
		}
		else {
			ctx.emit(Opcode.ACONST_NULL);
			ctx.emit(Opcode.ASTORE);
			ctx.emit(spillSlot);
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
					ctx.emit(Opcode.ASTORE);
					ctx.emit(slot);
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
			if (i == 0 && ctx.numOps.containsKey(JvmOperandTypeRuntime.TE_SLOT)) {
				// The type-error arm: a wrong-type operand's datum and expected type come
				// from its record, nil for any other type failure.
				emitTypeErrorConstruction(excSlot, condSlot, classes.get(i), msgVar, ctx, className);
			}
			else {
				JvmExprCompiler.compileExpr(
						LispMacroExpander.reportingConditionForm(ctx.closRegistry, classes.get(i), msgVar), ctx,
						className);
				ctx.emit(Opcode.ASTORE);
				ctx.emit(condSlot);
			}
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
				new LispCons(quotedTag, new LispCons(LispMacroExpander.textControlForm(msgVar),
						new LispCons(LispNil.INSTANCE, LispNil.INSTANCE))));
		JvmExprCompiler.compileExpr(instance, ctx, className);
		ctx.emit(Opcode.ASTORE);
		ctx.emit(condSlot);
		for (int join : joins) {
			JvmEmitHelper.patchBranch(ctx, join, ctx.code.size());
		}
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
			ctx.emit(Opcode.ALOAD);
			ctx.emit(excSlot);
			JvmEmitHelper.emitIntConst(ctx, slot[0]);
			ctx.emit(Opcode.INVOKESTATIC);
			ctx.emitU2(ctx.numOp(JvmOperandTypeRuntime.TE_SLOT).index());
			ctx.emit(Opcode.ASTORE);
			ctx.emit(slot[1]);
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
		ctx.emit(Opcode.ASTORE);
		ctx.emit(condSlot);
	}

	/**
	 * Emits the test guarding raw-failure arm {@code index}, and answers the branch
	 * positions to patch to the arm's END (i.e. the "does not apply" exits). The order
	 * mirrors {@link LispMacroExpander#rawFailureConditionClasses()}: type-error,
	 * division-by-zero, arithmetic-error, unbound-variable, undefined-function,
	 * program-error.
	 */
	private static List<Integer> emitRawFailureTest(int index, int excSlot, int rawSlot, JvmLispCompiler.Ctx ctx) {
		return switch (index) {
			case 0 -> {
				// A cast failure, an out-of-range index, a wrong-type operand (its
				// exception recorded by identity, JvmOperandTypeRuntime), or a
				// dispatcher's "Not a function: " (JvmRuntimeBuilder.buildNotFnBody):
				// type-error. None is an ArithmeticException, so testing this arm first
				// costs the arithmetic arms nothing. The message test exists because that
				// throw site is a plain RuntimeException with no channel to carry a class
				// (the unbound-variable precedent).
				List<Integer> skips = new ArrayList<>();
				List<Integer> hits = new ArrayList<>();
				hits.add(emitInstanceOfJump(excSlot, "java/lang/ClassCastException", ctx, true));
				if (ctx.numOps.containsKey(JvmOperandTypeRuntime.TE_SLOT)) {
					// A wrong-type operand's exception is recorded under its identity
					// (JvmOperandTypeRuntime), so no message is parsed here.
					ctx.emit(Opcode.ALOAD);
					ctx.emit(excSlot);
					ctx.emit(Opcode.ICONST_0);
					ctx.emit(Opcode.INVOKESTATIC);
					ctx.emitU2(ctx.numOp(JvmOperandTypeRuntime.TE_SLOT).index());
					hits.add(ctx.code.size());
					ctx.emit(Opcode.IFNONNULL);
					ctx.emitU2(0);
				}
				else {
					hits.add(emitMessagePrefixHit(rawSlot, OperandTypes.VALUE_PREFIX, ctx));
				}
				hits.add(emitMessagePrefixHit(rawSlot, ClosRegistry.NOT_A_FUNCTION_MESSAGE_PREFIX, ctx));
				skips.add(emitInstanceOfJump(excSlot, "java/lang/IndexOutOfBoundsException", ctx, false));
				for (int hit : hits) {
					JvmEmitHelper.patchBranch(ctx, hit, ctx.code.size());
				}
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
			case 4 ->
				List.of(emitMessageTest(rawSlot, "startsWith", ClosRegistry.UNDEFINED_FUNCTION_MESSAGE_PREFIX, ctx),
						emitMessageTest(rawSlot, "endsWith", ClosRegistry.UNDEFINED_FUNCTION_MESSAGE_SUFFIX, ctx));
			// A dispatcher's or a count guard's wrong-argument-count throw
			// (JvmRuntimeBuilder.ARITY_EXCEPTION_CLASS): recognized by its class, which
			// no other throw site raises -- its text may name any operator, and a user
			// error's may begin like it.
			default -> List.of(emitInstanceOfJump(excSlot, JvmRuntimeBuilder.ARITY_EXCEPTION_CLASS, ctx, false));
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
	 * Emits a {@code startsWith} test over the RAW message and a jump taken when it HOLDS
	 * (the OR-shaped twin of {@link #emitMessageTest}, whose jump is the does-not-hold
	 * one) -- a null message falls through. Answers the branch position to patch to the
	 * arm.
	 */
	private static int emitMessagePrefixHit(int rawSlot, String prefix, JvmLispCompiler.Ctx ctx) {
		ctx.emit(Opcode.ALOAD);
		ctx.emit(rawSlot);
		int ifNullPos = ctx.code.size();
		ctx.emit(Opcode.IFNULL);
		ctx.emitU2(0);
		ctx.emit(Opcode.ALOAD);
		ctx.emit(rawSlot);
		JvmEmitHelper.compileStringLiteral(prefix, ctx);
		ctx.emit(Opcode.INVOKEVIRTUAL);
		ctx.emitU2(JvmEmitHelper.stringMethod(ctx, "startsWith", "(Ljava/lang/String;)Z").index());
		int pos = ctx.code.size();
		ctx.emit(Opcode.IFNE);
		ctx.emitU2(0);
		JvmEmitHelper.patchBranch(ctx, ifNullPos, ctx.code.size());
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
