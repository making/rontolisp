package am.ik.rontolisp.codegen.wasm;

import java.util.ArrayList;
import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispMacroExpander;
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
			if (clause.car() instanceof LispSymbol head && LispNames.keywordMatches(head.name(), ":no-error")) {
				noErrorClause = clauseParts;
			}
			else {
				errorClauses.add(clauseParts);
			}
		}
		int payloadSlot = ctx.allocTemp();
		int condSlot = ctx.allocTemp();
		// depth++ so signal raises inside the protected region (incl. called functions).
		emitDepthAdjust(ctx, true);
		// block $done (result (ref null eq)) -- the handler-case value.
		ctx.writer.write(Instruction.BLOCK);
		ctx.writer.write(Type.REFNULL.code());
		ctx.writer.writeHeapType(Type.EQ.code());
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
			ctx.writer.write(Type.REFNULL.code());
			ctx.writer.writeHeapType(Type.EQ.code());
			ctx.wasmCtrlDepth++;
			trampolineDepth = ctx.wasmCtrlDepth;
		}
		// block $h (result (ref null eq)) -- the landing pad, receiving the payload.
		ctx.writer.write(Instruction.BLOCK);
		ctx.writer.write(Type.REFNULL.code());
		ctx.writer.writeHeapType(Type.EQ.code());
		ctx.wasmCtrlDepth++;
		int handlerDepth = ctx.wasmCtrlDepth;
		// try_table (result (ref null eq)) (catch $lisp-cond $h). Catch labels are
		// resolved without the try_table's own label, so label 0 is block $h here.
		ctx.writer.write(Instruction.TRY_TABLE);
		ctx.writer.write(Type.REFNULL.code());
		ctx.writer.writeHeapType(Type.EQ.code());
		ctx.writer.writeUnsignedLeb128(1);
		ctx.writer.write(Instruction.CATCH);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TAG_LISP_COND);
		ctx.writer.writeUnsignedLeb128(ctx.wasmCtrlDepth - handlerDepth);
		ctx.wasmCtrlDepth++;
		LispVal depthDecForm = new LispCons(new LispSymbol(LispNames.HC_DEPTH_DEC_INTERNAL), LispNil.INSTANCE);
		ctx.unwindScopes
			.push(new WasmLispCompiler.UnwindScope(List.of(depthDecForm), ctx.blockMarkers.size(), trampolineDepth));
		// State-machine mode: the protected expression is a spine child (an await there
		// suspends; the resume routes back through this try_table, re-arming it, and
		// the suspension undid the depth increment this form's head re-runs).
		WasmAsyncEmit.spine(parts.get(1), ctx);
		ctx.unwindScopes.pop();
		ctx.wasmCtrlDepth--;
		ctx.writer.write(Instruction.END); // try_table
		// Normal completion: depth--, then the :no-error clause (outside the protected
		// region -- an error signaled by it is not caught by this handler-case).
		int resultSlot = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeSignedLeb128(resultSlot);
		emitDepthAdjust(ctx, false);
		if (noErrorClause != null) {
			compileClauseBody(noErrorClause, resultSlot, resultSlot, ctx);
		}
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(resultSlot);
		ctx.writer.write(Instruction.BR, ctx.wasmCtrlDepth - doneDepth);
		ctx.wasmCtrlDepth--;
		ctx.writer.write(Instruction.END); // block $h
		// Landing pad: the payload cons is on the stack. depth--, split it into the
		// condition instance (car) and the message (cdr), synthesize a simple-error
		// from the message when the instance is nil.
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeSignedLeb128(payloadSlot);
		emitDepthAdjust(ctx, false);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(payloadSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CONS);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
		ctx.writer.writeSignedLeb128(0);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeSignedLeb128(condSlot);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(condSlot);
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
				ctx.writer.writeSignedLeb128(resultSlot);
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
		ctx.writer.writeSignedLeb128(payloadSlot);
		ctx.writer.write(Instruction.THROW);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TAG_LISP_COND);
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
			ctx.writer.writeSignedLeb128(resultSlot);
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
	 * {@code (list '%class-simple-error message nil)} over the payload's message (the
	 * cdr, already a quote-framed runtime string -- or nil), stored into
	 * {@code condSlot}.
	 */
	private static void emitSynthesizeSimpleError(int payloadSlot, int condSlot, WasmLispCompiler.Ctx ctx) {
		int msgSlot = ctx.allocTemp();
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(payloadSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CONS);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
		ctx.writer.writeSignedLeb128(1);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeSignedLeb128(msgSlot);
		String msgVarName = "__hc_msg$" + msgSlot;
		ctx.locals.put(msgVarName, msgSlot);
		try {
			// (list '%class-simple-error __hc_msg nil)
			LispVal quotedTag = new LispCons(new LispSymbol(LispNames.QUOTE),
					new LispCons(new LispSymbol("%class-simple-error"), LispNil.INSTANCE));
			LispVal instance = new LispCons(new LispSymbol(LispNames.LIST), new LispCons(quotedTag,
					new LispCons(new LispSymbol(msgVarName), new LispCons(LispNil.INSTANCE, LispNil.INSTANCE))));
			WasmExprCompiler.compileExpr(instance, ctx);
		}
		finally {
			ctx.locals.remove(msgVarName);
		}
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeSignedLeb128(condSlot);
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
