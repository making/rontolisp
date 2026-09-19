package am.ik.rontolisp.codegen.wasm;

import java.util.ArrayList;
import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.macro.LispMacroExpander;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispLayout;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;

/**
 * Compiles {@code (handler-case expr (type ([var]) body...)... [(:no-error ([var])
 * body...)])} on the wasm-GC backend (EH mode only), mirroring
 * {@code JvmHandlerCaseCompiler}.
 *
 * <p>
 * Layout: an outer {@code block $done (result (ref null eq))} holds the whole form's
 * value; an inner {@code block $h (result (ref null eq))} is the landing pad target of a
 * {@code try_table (catch $lisp-cond $h)} over the protected expression. The tag payload
 * is a cons {@code (condition-instance . message-string)} (see
 * {@code WasmErrorCompiler}); at the landing pad a {@code simple-error} instance is
 * synthesized from the message when the instance is nil (a plain {@code %error}), then
 * the clauses' type tests ({@code LispMacroExpander.makeHandlerTypeTest}) and bodies
 * compile as ordinary Lisp forms over a pseudo-local holding the condition (the
 * {@code __hc_cond$<slot>} trick). No matching clause rethrows the original payload on
 * the same tag, so an outer handler-case sees the typed instance. The {@code :no-error}
 * clause runs on normal completion outside the protected region.
 *
 * <p>
 * The handler-depth global is incremented around the protected region (JVM
 * {@code _hcDepthTl} parity) so {@code signal} raises only under an established handler;
 * a {@code return} exiting the region decrements it through the unwind-scope cleanup
 * channel ({@code %hc-depth-dec}).
 */
final class WasmHandlerCaseCompiler {

	private WasmHandlerCaseCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
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
			if (ctx.asyncResume != null && WasmAwaitAnalysis.countAwaits(parts.get(i)) > 0) {
				// The protected expression may await (the state dispatch re-enters the
				// try_table from the top); a clause body cannot yet -- its landing pad
				// is only reachable through a catch.
				throw new UnsupportedOperationException("rontolisp:await inside a " + LispNames.HANDLER_CASE
						+ " clause body is not supported on the --component backend;"
						+ " move the await outside the handler clause");
			}
			List<LispVal> clauseParts = clause.toList();
			if (clause.car() instanceof LispSymbol head && ":NO-ERROR".equals(head.name())) {
				noErrorClause = clauseParts;
			}
			else {
				errorClauses.add(clauseParts);
			}
		}
		// depth++ so signal raises inside the protected region (incl. called functions).
		emitDepthAdjust(ctx, true);
		// block $done (result (ref null eq)) -- the handler-case value.
		ctx.writer.write(Instruction.BLOCK);
		ctx.writer.writeRefType(true, Type.EQ.code());
		ctx.wasmCtrlDepth++;
		int doneDepth = ctx.wasmCtrlDepth;
		// block $tramp (result (ref null eq)) -- the return-exit trampoline (see
		// WasmUnwindProtectCompiler), whose only cleanup is the depth decrement; emitted
		// only when an enclosing plain-return boundary exists, i.e. a return could
		// escape the region (a named return-from inlines the decrement instead).
		boolean needTrampoline = WasmReturnCompiler.findPlainTarget(ctx) != null;
		int trampolineDepth = -1;
		int continueDepth = -1;
		if (needTrampoline) {
			continueDepth = WasmUnwindProtectCompiler.continueTargetDepth(ctx);
			ctx.writer.write(Instruction.BLOCK);
			ctx.writer.writeRefType(true, Type.EQ.code());
			ctx.wasmCtrlDepth++;
			trampolineDepth = ctx.wasmCtrlDepth;
		}
		// block $bx (result (ref null eq)) -- the block-exit passthrough landing, only
		// when the program lowers a cross-lambda return-from. A block-exit unwinding
		// through this region carries a different tag, so handler-case would otherwise
		// not
		// see it and its depth increment would leak; catching it here restores the depth
		// and rethrows, matching the JVM (whose catch-any handler already does).
		int blockExitDepth = -1;
		if (ctx.blockExitTag) {
			ctx.writer.write(Instruction.BLOCK);
			ctx.writer.writeRefType(true, Type.EQ.code());
			ctx.wasmCtrlDepth++;
			blockExitDepth = ctx.wasmCtrlDepth;
		}
		// The landing-pad discipline (WasmLandingPad): the live locals ride the operand
		// stack beneath block $h and the pad pops them back before it reads anything.
		int kept = WasmLandingPad.keepLocalsAlive(ctx);
		// Allocated AFTER the push: a slot among the kept ones would be popped back over
		// the payload just stashed in it.
		int payloadSlot = ctx.allocTemp();
		int condSlot = ctx.allocTemp();
		// block $h (result (ref null eq)) -- the landing pad, receiving the payload.
		ctx.writer.write(Instruction.BLOCK);
		ctx.writer.writeRefType(true, Type.EQ.code());
		ctx.wasmCtrlDepth++;
		int handlerDepth = ctx.wasmCtrlDepth;
		// try_table (result (ref null eq)) (catch $lisp-cond $h) [(catch $block-exit
		// $bx)].
		// Catch labels are resolved without the try_table's own label, so label 0 is
		// block
		// $h; $bx (one level out) is label 1.
		ctx.writer.write(Instruction.TRY_TABLE);
		ctx.writer.writeRefType(true, Type.EQ.code());
		ctx.writer.writeUnsignedLeb128(ctx.blockExitTag ? 2 : 1);
		ctx.writer.write(Instruction.CATCH);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TAG_LISP_COND);
		ctx.writer.writeUnsignedLeb128(ctx.wasmCtrlDepth - handlerDepth);
		if (ctx.blockExitTag) {
			ctx.writer.write(Instruction.CATCH);
			ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TAG_BLOCK_EXIT);
			ctx.writer.writeUnsignedLeb128(ctx.wasmCtrlDepth - blockExitDepth);
		}
		ctx.wasmCtrlDepth++;
		LispVal depthDecForm = new LispCons(new LispSymbol(LispNames.HC_DEPTH_DEC_INTERNAL), LispNil.INSTANCE);
		ctx.unwindScopes
			.push(new WasmLispCompiler.UnwindScope(List.of(depthDecForm), ctx.blockMarkers.size(), trampolineDepth));
		// State-machine mode: the protected expression is a spine child (an await there
		// suspends; the resume routes back through this try_table, re-arming it, and
		// the suspension undid the depth increment this form's head re-runs).
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
		WasmAsyncEmit.spine(protectedForm, ctx);
		ctx.unwindScopes.pop();
		ctx.wasmCtrlDepth--;
		ctx.writer.write(Instruction.END); // try_table
		// Normal completion: depth--, then the :no-error clause (outside the protected
		// region -- an error signaled by it is not caught by this handler-case).
		int resultSlot = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(resultSlot);
		emitDepthAdjust(ctx, false);
		if (noErrorClause != null) {
			compileNoErrorClauseBody(noErrorClause, resultSlot, resultSlot, ctx);
		}
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(resultSlot);
		ctx.writer.write(Instruction.BR, ctx.wasmCtrlDepth - doneDepth);
		ctx.wasmCtrlDepth--;
		ctx.writer.write(Instruction.END); // block $h
		// Landing pad: the payload cons is on the stack. Stash it, pop the kept locals
		// back, depth--, split it into the condition instance (car) and the message
		// (cdr), synthesize a simple-error from the message when the instance is nil.
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(payloadSlot);
		WasmLandingPad.refreshLocals(ctx, kept);
		emitDepthAdjust(ctx, false);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(payloadSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CONS);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		ctx.writer.writeUnsignedLeb128(0);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(condSlot);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(condSlot);
		ctx.writer.write(Instruction.REF_IS_NULL);
		ctx.writer.write(Instruction.IF, WasmLispCompiler.BLOCKTYPE_EMPTY);
		ctx.wasmCtrlDepth++;
		emitSynthesizeSimpleError(payloadSlot, condSlot, ctx);
		ctx.wasmCtrlDepth--;
		ctx.writer.write(Instruction.END);
		// Dispatch: the condition rides a pseudo-local so the type tests and clause
		// bodies compile as ordinary Lisp forms.
		String condVarName = "__hc_cond$" + condSlot;
		LispSymbol condVarSym = new LispSymbol(condVarName);
		ctx.locals.put(condVarName, condSlot);
		try {
			for (List<LispVal> clauseParts : errorClauses) {
				LispVal test = LispMacroExpander.makeHandlerTypeTest(condVarSym, clauseParts.get(0), ctx.closRegistry);
				WasmExprCompiler.compileExpr(test, ctx);
				ctx.writer.write(Instruction.REF_IS_NULL);
				ctx.writer.write(Instruction.I32_EQZ);
				ctx.writer.write(Instruction.IF, WasmLispCompiler.BLOCKTYPE_EMPTY);
				ctx.wasmCtrlDepth++;
				compileClauseBody(clauseParts, condSlot, resultSlot, ctx);
				ctx.writer.write(Instruction.GET_LOCAL);
				ctx.writer.writeUnsignedLeb128(resultSlot);
				ctx.writer.write(Instruction.BR, ctx.wasmCtrlDepth - doneDepth);
				ctx.wasmCtrlDepth--;
				ctx.writer.write(Instruction.END);
			}
		}
		finally {
			ctx.locals.remove(condVarName);
		}
		// No clause matched: rethrow the original payload on the same tag (an outer
		// handler-case must see the typed instance, not a re-synthesized simple-error).
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(payloadSlot);
		ctx.writer.write(Instruction.THROW);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TAG_LISP_COND);
		if (ctx.blockExitTag) {
			ctx.wasmCtrlDepth--;
			ctx.writer.write(Instruction.END); // block $bx
			// Block-exit landing: the (id . value) payload is on the stack; a
			// cross-lambda
			// return-from is unwinding through this handler-case, so undo the depth
			// increment its head added and rethrow it for the establishing %nlx-catch.
			emitDepthAdjust(ctx, false);
			ctx.writer.write(Instruction.THROW);
			ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TAG_BLOCK_EXIT);
		}
		if (needTrampoline) {
			ctx.wasmCtrlDepth--;
			ctx.writer.write(Instruction.END); // block $tramp
			// Return landing: the escaped return's value is on the stack; restore the
			// handler depth and continue the exit toward the target %block.
			WasmUnwindProtectCompiler.compileCleanups(List.of(depthDecForm), ctx);
			ctx.writer.write(Instruction.BR, ctx.wasmCtrlDepth - continueDepth);
		}
		ctx.wasmCtrlDepth--;
		ctx.writer.write(Instruction.END); // block $done
	}

	/**
	 * Compiles the internal {@code (%hb-guard body)} landing pad the {@code handler-bind}
	 * expansion wraps its body in (EH mode is implied: restart mode forces it). A
	 * {@code $lisp-cond} throw escaping the body lands here; the pad synthesizes the
	 * {@code simple-error} of a plain {@code %error} payload, runs the
	 * {@code handler-bind} cluster stack through {@code %run-handlers} unless a walk
	 * already completed for the identical instance (the {@code %handlers-ran%} mark set
	 * by the restart-mode signal hook), and rethrows {@code (instance . message)} so an
	 * outer catcher dispatches on the same instance the handlers saw. Unlike
	 * {@code handler-case} it never touches the handler depth ({@code signal} semantics
	 * unchanged), has no cleanup (no unwind scope, no return trampoline), and does not
	 * catch the block-exit tag -- a cross-lambda exit passes through the
	 * {@code try_table} untouched. Raw wasm TRAPS (a failed cast, integer division by
	 * zero) do not ride the tag and stay uncatchable: the documented three-point-
	 * spectrum divergence.
	 */
	static void compileGuard(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> parts = cons.toList();
		if (parts.size() != 2) {
			throw new IllegalArgumentException(LispNames.HB_GUARD_INTERNAL + " expects a body: " + cons.print());
		}
		if (!ctx.ehMode) {
			// Defensive: without the tag nothing throws catchably, so the pad is
			// meaningless (restart mode implies EH mode, so this is unreachable today).
			WasmAsyncEmit.spine(parts.get(1), ctx);
			return;
		}
		// block $done (result (ref null eq)) -- the guarded body's value.
		ctx.writer.write(Instruction.BLOCK);
		ctx.writer.writeRefType(true, Type.EQ.code());
		ctx.wasmCtrlDepth++;
		int doneDepth = ctx.wasmCtrlDepth;
		// The landing-pad discipline (WasmLandingPad), as in compile(); the payload and
		// condition temps are allocated after the push so the pops leave them alone.
		int kept = WasmLandingPad.keepLocalsAlive(ctx);
		int payloadSlot = ctx.allocTemp();
		int condSlot = ctx.allocTemp();
		// block $h (result (ref null eq)) -- the landing pad, receiving the payload.
		ctx.writer.write(Instruction.BLOCK);
		ctx.writer.writeRefType(true, Type.EQ.code());
		ctx.wasmCtrlDepth++;
		int handlerDepth = ctx.wasmCtrlDepth;
		// try_table (result (ref null eq)) (catch $lisp-cond $h).
		ctx.writer.write(Instruction.TRY_TABLE);
		ctx.writer.writeRefType(true, Type.EQ.code());
		ctx.writer.writeUnsignedLeb128(1);
		ctx.writer.write(Instruction.CATCH);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TAG_LISP_COND);
		ctx.writer.writeUnsignedLeb128(ctx.wasmCtrlDepth - handlerDepth);
		ctx.wasmCtrlDepth++;
		WasmAsyncEmit.spine(parts.get(1), ctx);
		ctx.wasmCtrlDepth--;
		ctx.writer.write(Instruction.END); // try_table
		// Normal completion: the body's value is on the stack.
		ctx.writer.write(Instruction.BR, ctx.wasmCtrlDepth - doneDepth);
		ctx.wasmCtrlDepth--;
		ctx.writer.write(Instruction.END); // block $h
		// Landing pad: stash the payload, pop the kept locals back, split the payload,
		// synthesize when the instance is nil.
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(payloadSlot);
		WasmLandingPad.refreshLocals(ctx, kept);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(payloadSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CONS);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		ctx.writer.writeUnsignedLeb128(0);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(condSlot);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(condSlot);
		ctx.writer.write(Instruction.REF_IS_NULL);
		ctx.writer.write(Instruction.IF, WasmLispCompiler.BLOCKTYPE_EMPTY);
		ctx.wasmCtrlDepth++;
		emitSynthesizeSimpleError(payloadSlot, condSlot, ctx);
		ctx.wasmCtrlDepth--;
		ctx.writer.write(Instruction.END);
		// Run the cluster stack unless already run, as an ordinary Lisp form over the
		// condition pseudo-local.
		String condVarName = "__hc_cond$" + condSlot;
		ctx.locals.put(condVarName, condSlot);
		try {
			WasmExprCompiler.compileExpr(LispMacroExpander.hbGuardHandlerForm(new LispSymbol(condVarName)), ctx);
		}
		finally {
			ctx.locals.remove(condVarName);
		}
		ctx.writer.write(Instruction.DROP);
		// Rethrow (instance . message): the instance slot is filled (a synthesized one
		// included) so an outer catcher sees the instance the handlers saw.
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(condSlot);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(payloadSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CONS);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		ctx.writer.writeUnsignedLeb128(1);
		WasmErrorCompiler.emitThrowPayload(ctx);
		ctx.wasmCtrlDepth--;
		ctx.writer.write(Instruction.END); // block $done
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
	 * and the {@code %mv-spill} global carries the secondary values (or nil when no
	 * secondary values were published). The spill global exists only when the program
	 * uses a multiple-value operator (or a {@code handler-case} with a {@code :no-error}
	 * clause, .kb/multiple-values.md); when it does not, no spill was ever published, so
	 * we initialize the local to nil and every extra variable binds to nil. Either way
	 * the consumer reads {@code (nth i spill)}, which is well-defined on nil (returns
	 * nil) -- so a missing value is nil and a surplus value (beyond the variable count)
	 * is simply not read, never observed.
	 *
	 * <p>
	 * The spill global is cleared after the snapshot so the clause body runs on a clean
	 * channel (a clause that itself publishes through the spill starts fresh).
	 */
	private static void compileNoErrorClauseBody(List<LispVal> clauseParts, int valueSlot, int resultSlot,
			WasmLispCompiler.Ctx ctx) {
		List<LispVal> varVals = clauseParts.get(1) instanceof LispCons varList ? varList.toList() : List.of();
		int spillSlot = ctx.allocTemp();
		Integer spillGlobal = ctx.globalIndices.get(LispNames.MV_SPILL);
		if (spillGlobal != null) {
			ctx.writer.write(Instruction.GET_GLOBAL);
			ctx.writer.writeUnsignedLeb128(spillGlobal);
			ctx.writer.write(Instruction.SET_LOCAL);
			ctx.writer.writeUnsignedLeb128(spillSlot);
			ctx.writer.write(Instruction.REF_NULL);
			ctx.writer.writeHeapType(Type.EQ.code());
			ctx.writer.write(Instruction.SET_GLOBAL);
			ctx.writer.writeUnsignedLeb128(spillGlobal);
		}
		else {
			ctx.writer.write(Instruction.REF_NULL);
			ctx.writer.writeHeapType(Type.EQ.code());
			ctx.writer.write(Instruction.SET_LOCAL);
			ctx.writer.writeUnsignedLeb128(spillSlot);
		}
		String spillVarName = "__hc_ne_spill$" + spillSlot;
		java.util.Map<String, Integer> shadowedSlots = new java.util.HashMap<>();
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
					slot = valueSlot;
				}
				else {
					slot = ctx.allocTemp();
					// (nth (i-1) spill): nth on nil is the missing-value fill, and the
					// zero-values marker (t) reads as the empty list.
					LispVal nthCall = new LispCons(new LispSymbol(LispNames.NTH),
							new LispCons(new LispInteger(i - 1), new LispCons(
									LispMacroExpander.spillAsList(new LispSymbol(spillVarName)), LispNil.INSTANCE)));
					WasmExprCompiler.compileExpr(nthCall, ctx);
					ctx.writer.write(Instruction.SET_LOCAL);
					ctx.writer.writeUnsignedLeb128(slot);
				}
				shadowedSlots.put(varName, ctx.locals.put(varName, slot));
			}
			if (clauseParts.size() <= 2) {
				ctx.writer.write(Instruction.REF_NULL);
				ctx.writer.writeHeapType(Type.EQ.code());
			}
			else {
				for (int i = 2; i < clauseParts.size(); i++) {
					if (i > 2) {
						ctx.writer.write(Instruction.DROP);
					}
					WasmExprCompiler.compileExpr(clauseParts.get(i), ctx);
				}
			}
			ctx.writer.write(Instruction.SET_LOCAL);
			ctx.writer.writeUnsignedLeb128(resultSlot);
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
		}
	}

	/**
	 * Compiles a clause body -- {@code (type ([var]) body...)} or {@code (:no-error
	 * ([var]) body...)} -- binding the optional variable to the value in
	 * {@code valueSlot} through a pseudo-local, and stores the body's value into
	 * {@code resultSlot}.
	 */
	private static void compileClauseBody(List<LispVal> clauseParts, int valueSlot, int resultSlot,
			WasmLispCompiler.Ctx ctx) {
		String varName = null;
		Integer shadowedSlot = null;
		if (clauseParts.get(1) instanceof LispCons varList && varList.car() instanceof LispSymbol var) {
			varName = var.name();
			shadowedSlot = ctx.locals.put(varName, valueSlot);
		}
		try {
			if (clauseParts.size() <= 2) {
				ctx.writer.write(Instruction.REF_NULL);
				ctx.writer.writeHeapType(Type.EQ.code());
			}
			else {
				for (int i = 2; i < clauseParts.size(); i++) {
					if (i > 2) {
						ctx.writer.write(Instruction.DROP);
					}
					WasmExprCompiler.compileExpr(clauseParts.get(i), ctx);
				}
			}
			ctx.writer.write(Instruction.SET_LOCAL);
			ctx.writer.writeUnsignedLeb128(resultSlot);
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
	 * Synthesizes the {@code simple-error} instance of a condition-less throw:
	 * {@code (%obj-new '%class-SIMPLE-ERROR message nil)} over the payload's message (the
	 * cdr, already a quote-framed runtime string -- or nil), stored into
	 * {@code condSlot}.
	 */
	private static void emitSynthesizeSimpleError(int payloadSlot, int condSlot, WasmLispCompiler.Ctx ctx) {
		int msgSlot = ctx.allocTemp();
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(payloadSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CONS);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		ctx.writer.writeUnsignedLeb128(1);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(msgSlot);
		String msgVarName = "__hc_msg$" + msgSlot;
		ctx.locals.put(msgVarName, msgSlot);
		try {
			// (%obj-new '%class-SIMPLE-ERROR __hc_msg nil)
			LispVal quotedTag = new LispCons(new LispSymbol(LispNames.QUOTE),
					new LispCons(new LispSymbol(LispLayout.CLASS_TAG_PREFIX + "SIMPLE-ERROR"), LispNil.INSTANCE));
			LispVal instance = new LispCons(new LispSymbol(LispNames.OBJ_NEW), new LispCons(quotedTag,
					new LispCons(new LispSymbol(msgVarName), new LispCons(LispNil.INSTANCE, LispNil.INSTANCE))));
			WasmExprCompiler.compileExpr(instance, ctx);
		}
		finally {
			ctx.locals.remove(msgVarName);
		}
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(condSlot);
	}

	/**
	 * Emits an increment ({@code up}) or decrement of the handler-depth global.
	 */
	static void emitDepthAdjust(WasmLispCompiler.Ctx ctx, boolean up) {
		ctx.writer.write(Instruction.GET_GLOBAL);
		ctx.writer.writeUnsignedLeb128(ctx.ehDepthGlobalIndex);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(1);
		ctx.writer.write(up ? Instruction.I32_ADD : Instruction.I32_SUB);
		ctx.writer.write(Instruction.SET_GLOBAL);
		ctx.writer.writeUnsignedLeb128(ctx.ehDepthGlobalIndex);
	}

	/**
	 * Emits the {@code %hc-depth-dec} internal form: decrements the handler depth and
	 * yields nil (the unwind-scope cleanup channel drops the value).
	 */
	static void compileDepthDec(WasmLispCompiler.Ctx ctx) {
		emitDepthAdjust(ctx, false);
		ctx.writer.write(Instruction.REF_NULL);
		ctx.writer.writeHeapType(Type.EQ.code());
	}

}
