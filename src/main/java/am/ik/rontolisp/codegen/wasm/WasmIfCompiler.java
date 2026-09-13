package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNil;
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
		List<LispVal> parts = cons.toList();
		if (ctx.asyncResume != null && WasmAwaitAnalysis.countAwaits(cons) > 0) {
			compileAsync(parts, ctx);
			return;
		}
		LispVal test = parts.get(1);
		if (test instanceof LispTrue || test instanceof LispNil) {
			// A constant test selects its arm at compile time: (cond ... (t x)) and the
			// (and ...) chain end both spell one, and the dead arm would otherwise be
			// emitted behind a _t_sym call that is tested and never false.
			LispVal live = test instanceof LispTrue ? parts.get(2) : parts.size() > 3 ? parts.get(3) : null;
			if (live == null) {
				ctx.writer.write(Instruction.REF_NULL);
				ctx.writer.writeHeapType(Type.EQ.code());
			}
			else {
				WasmExprCompiler.compileExpr(live, ctx);
			}
			return;
		}
		// The test as a raw i32 (a predicate's own ref.test / ref.eq, a comparison's
		// mask bit, an and/or chain of those; ref.is_null over anything else), non-0
		// when it is FALSE: the wasm-if arms stay THEN-on-nil.
		WasmConditionCompiler.compile(test, ctx, true);
		ctx.writer.write(Instruction.IF);
		ctx.writer.writeRefType(true, Type.EQ.code());
		// The branches are compiled inside the if structure; track the depth so a return
		// nested in a branch computes the correct br depth to its enclosing %block.
		ctx.wasmCtrlDepth++;
		if (parts.size() > 3) {
			WasmExprCompiler.compileExpr(parts.get(3), ctx);
		}
		else {
			ctx.writer.write(Instruction.REF_NULL);
			ctx.writer.writeHeapType(Type.EQ.code());
		}
		ctx.writer.write(Instruction.ELSE);
		WasmExprCompiler.compileExpr(parts.get(2), ctx);
		ctx.wasmCtrlDepth--;
		ctx.writer.write(Instruction.END);
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
