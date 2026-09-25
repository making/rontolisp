package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispTrue;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;

/**
 * Compiles the {@code if} special form.
 */
final class WasmIfCompiler {

	private WasmIfCompiler() {
	}

	/**
	 * Whether a form continues the else-chain: an `if`-headed proper list, the only shape
	 * the loop above descends into. Anything else -- a value, a call, an `if`-headed but
	 * improper tail -- compiles through the ordinary recursive path, exactly as before.
	 * @param form the candidate else/tail form
	 * @return whether the loop descends into it
	 */
	private static boolean isChainableIf(LispVal form) {
		return form instanceof LispCons cons && cons.car() instanceof LispSymbol head
				&& LispNames.IF.equals(head.name()) && cons.isProperList();
	}

	/**
	 * What {@link WasmExprCompiler#compileExpr} consumes on entry -- the tail marker
	 * (handed on explicitly, so reset here) and the async spine markers -- mirrored for a
	 * chain level the loop descends into directly instead of through {@code compileExpr}.
	 * Without this a call nested in a deeper test would compile as a tail call the
	 * recursion never emitted.
	 * @param ctx the function context
	 */
	private static void consumeExprMarkers(WasmLispCompiler.Ctx ctx) {
		ctx.tailPosition = false;
		if (ctx.asyncResume != null) {
			ctx.asyncSpineCurrent = ctx.asyncSpine;
			ctx.asyncSpine = false;
		}
	}

	/**
	 * Statement-position {@code if} (its value is discarded): a void wasm {@code if}
	 * whose arms compile for effect, so neither arm materialises a value and no
	 * {@code drop} follows -- a {@code (when c (setq ...))} is the test, the store and
	 * nothing else. An arm that is a literal or nil is no arm at all: the test alone
	 * selects whether the other one runs, with the polarity chosen so no {@code else} is
	 * written. Not in state-machine mode, whose {@code if} routes resumes through its
	 * arms ({@code WasmExprCompiler.compileForEffect} does not reach here then).
	 * @param cons the if form
	 * @param ctx the function context
	 */
	static void compileForEffect(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> parts = cons.toList();
		LispVal test = parts.get(1);
		LispVal thenForm = parts.get(2);
		LispVal elseForm = parts.size() > 3 ? parts.get(3) : LispNil.INSTANCE;
		if (test instanceof LispTrue || test instanceof LispNil) {
			WasmExprCompiler.compileForEffect(test instanceof LispTrue ? thenForm : elseForm, ctx);
			return;
		}
		boolean thenEmpty = hasNoEffect(thenForm);
		boolean elseEmpty = hasNoEffect(elseForm);
		if (thenEmpty && elseEmpty) {
			WasmConditionCompiler.compile(test, ctx, false);
			ctx.writer.write(Instruction.DROP);
			return;
		}
		// With one arm empty the test's own polarity picks the live arm as the wasm
		// THEN; with both, the Lisp then-arm is the wasm then-arm.
		WasmConditionCompiler.compile(test, ctx, thenEmpty);
		ctx.writer.write(Instruction.IF, 0x40);
		ctx.wasmCtrlDepth++;
		WasmExprCompiler.compileForEffect(thenEmpty ? elseForm : thenForm, ctx);
		if (!thenEmpty && !elseEmpty) {
			ctx.writer.write(Instruction.ELSE);
			WasmExprCompiler.compileForEffect(elseForm, ctx);
		}
		ctx.wasmCtrlDepth--;
		ctx.writer.write(Instruction.END);
	}

	// A form whose evaluation in statement position does nothing: nil, t, a keyword, a
	// self-evaluating literal.
	private static boolean hasNoEffect(LispVal form) {
		return form instanceof LispNil || form instanceof LispTrue || form instanceof am.ik.rontolisp.LispString
				|| form instanceof am.ik.rontolisp.LispInteger || form instanceof am.ik.rontolisp.LispDouble
				|| form instanceof am.ik.rontolisp.LispChar || form instanceof am.ik.rontolisp.LispBigInteger
				|| form instanceof am.ik.rontolisp.LispRatio
				|| (form instanceof am.ik.rontolisp.LispSymbol sym && sym.isKeyword());
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		compile(cons, ctx, false);
	}

	/**
	 * As {@link #compile(LispCons, WasmLispCompiler.Ctx)}; with {@code tail}, both arms
	 * are in tail position ({@code Ctx.tailPosition}) -- the test never is.
	 */
	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx, boolean tail) {
		// An else-chain (`cond`/`case`/type-dispatch lowering, one `if` per clause)
		// compiles iteratively: one Java frame per level instead of six, so a chain
		// hundreds deep no longer overflows the compile stack. A 315-level chain (the
		// progv `symbol-value` dispatch over the ci-spec special set) overflowed a 1
		// MiB stack cold (2026-09-25); the loop below holds it on any stack. The
		// emission is exactly the recursion's -- tests and IF opens stream out in
		// order, the innermost value lands, then each deferred then-arm closes its
		// level with ELSE/arm/END -- so the output is byte-identical at every depth.
		// Only the chain links collapse: a level with awaits still routes through the
		// state machine, and anything that is not a well-formed `if` tail compiles
		// through the ordinary recursive path, exactly as before.
		java.util.ArrayDeque<LispVal> thens = new java.util.ArrayDeque<>();
		LispVal form = cons;
		while (true) {
			if (!(form instanceof LispCons ifCons)) {
				// A non-`if` tail (a variable, a literal): the innermost value,
				// exactly as the recursion compiled it.
				ctx.tailPosition = tail;
				WasmExprCompiler.compileExpr(form, ctx);
				break;
			}
			List<LispVal> parts = ifCons.toList();
			if (ctx.asyncResume != null && WasmAwaitAnalysis.countAwaits(ifCons) > 0) {
				compileAsync(parts, ctx);
				break;
			}
			LispVal test = parts.get(1);
			if (test instanceof LispTrue || test instanceof LispNil) {
				// A constant test selects its arm at compile time: (cond ... (t x))
				// and the (and ...) chain end both spell one, and the dead arm would
				// otherwise be emitted behind a _t_sym call that is tested and never
				// false.
				LispVal live = test instanceof LispTrue ? parts.get(2) : parts.size() > 3 ? parts.get(3) : null;
				if (live == null) {
					ctx.writer.write(Instruction.REF_NULL);
					ctx.writer.writeHeapType(Type.EQ.code());
					break;
				}
				ctx.tailPosition = tail;
				if (isChainableIf(live)) {
					// The recursion reached the live arm through `compileExpr`,
					// which hands the tail marker on explicitly and resets the
					// context fields: mirror that consume here, then descend.
					consumeExprMarkers(ctx);
					form = live;
					continue;
				}
				// Not another `if`: the value, exactly as the recursion compiled
				// it (`tailPosition` above is what `compileExpr` consumes).
				WasmExprCompiler.compileExpr(live, ctx);
				break;
			}
			// The test as a raw i32 (a predicate's own ref.test / ref.eq, a
			// comparison's mask bit, an and/or chain of those; ref.is_null over
			// anything else), non-0 when it is FALSE: the wasm-if arms stay THEN-on-nil.
			WasmConditionCompiler.compile(test, ctx, true);
			ctx.writer.write(Instruction.IF);
			ctx.writer.writeRefType(true, Type.EQ.code());
			// The branches are compiled inside the if structure; track the depth so
			// a return nested in a branch computes the correct br depth to its
			// enclosing %block.
			ctx.wasmCtrlDepth++;
			if (parts.size() > 3 && isChainableIf(parts.get(3))) {
				// Another `if` in else position: defer this level's then-arm and
				// descend, mirroring `compileExpr`'s consume for the nested level
				// (below). The nested level's `tail` is this call's, exactly as
				// the recursion threaded it.
				consumeExprMarkers(ctx);
				thens.push(parts.get(2));
				form = parts.get(3);
				continue;
			}
			thens.push(parts.get(2));
			if (parts.size() > 3) {
				ctx.tailPosition = tail;
				WasmExprCompiler.compileExpr(parts.get(3), ctx);
			}
			else {
				ctx.writer.write(Instruction.REF_NULL);
				ctx.writer.writeHeapType(Type.EQ.code());
			}
			break;
		}
		while (!thens.isEmpty()) {
			ctx.writer.write(Instruction.ELSE);
			ctx.tailPosition = tail;
			WasmExprCompiler.compileExpr(thens.pop(), ctx);
			ctx.wasmCtrlDepth--;
			ctx.writer.write(Instruction.END);
		}
	}

	/**
	 * State-machine mode: an {@code if} containing awaits. The test runs when executing
	 * normally or when the resume target lies inside the test itself (it dispatches
	 * there); a resume targeting a branch skips the test and selects the branch by its
	 * state range. Emission (and state-number) order follows the plain compiler: test,
	 * then the ELSE branch (the wasm-if THEN arm, entered on a nil test), then the THEN
	 * branch.
	 */
	private static void compileAsync(List<LispVal> parts, WasmLispCompiler.Ctx ctx) {
		WasmLispCompiler.AsyncResume ar = java.util.Objects.requireNonNull(ctx.asyncResume);
		LispVal test = parts.get(1);
		LispVal thenExpr = parts.get(2);
		LispVal elseExpr = parts.size() > 3 ? parts.get(3) : null;
		int testN = WasmAwaitAnalysis.countAwaits(test);
		int elseN = elseExpr == null ? 0 : WasmAwaitAnalysis.countAwaits(elseExpr);
		int thenN = WasmAwaitAnalysis.countAwaits(thenExpr);
		int testLo = ar.nextState;
		int elseLo = testLo + testN;
		int thenLo = elseLo + elseN;
		// selector: i32 "is nil" -- evaluated from the test on the normal path (or when
		// resuming into the test), derived from the target's branch range on a resume.
		if (testN == 0) {
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeUnsignedLeb128(WasmAsyncEmit.RT_SLOT);
			ctx.writer.write(Instruction.I32_EQZ);
		}
		else {
			WasmAsyncEmit.emitRangeGuard(ctx, testLo, testLo + testN - 1);
		}
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(Type.I32);
		ctx.wasmCtrlDepth++;
		WasmAsyncEmit.spine(test, ctx);
		ctx.writer.write(Instruction.REF_IS_NULL);
		ctx.writer.write(Instruction.ELSE);
		if (elseN == 0) {
			ctx.writer.write(Instruction.I32_CONST);
			ctx.writer.writeSignedLeb128(0);
		}
		else {
			WasmAsyncEmit.emitInRange(ctx, elseLo, elseLo + elseN - 1);
		}
		ctx.wasmCtrlDepth--;
		ctx.writer.write(Instruction.END);
		WasmAsyncEmit.assertStates(ctx, testLo, testN, test);
		ctx.writer.write(Instruction.IF);
		ctx.writer.writeRefType(true, Type.EQ.code());
		ctx.wasmCtrlDepth++;
		if (elseExpr != null) {
			WasmAsyncEmit.spine(elseExpr, ctx);
		}
		else {
			ctx.writer.write(Instruction.REF_NULL);
			ctx.writer.writeHeapType(Type.EQ.code());
		}
		WasmAsyncEmit.assertStates(ctx, elseLo, elseN, parts.get(0));
		ctx.writer.write(Instruction.ELSE);
		WasmAsyncEmit.spine(thenExpr, ctx);
		WasmAsyncEmit.assertStates(ctx, thenLo, thenN, thenExpr);
		ctx.wasmCtrlDepth--;
		ctx.writer.write(Instruction.END);
	}

}
