package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;

/**
 * Compiles the {@code if} special form.
 */
final class WasmIfCompiler {

	private WasmIfCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> parts = cons.toList();
		if (ctx.asyncResume != null && WasmAwaitAnalysis.countAwaits(cons) > 0) {
			compileAsync(parts, ctx);
			return;
		}
		// A fusable binary comparison keeps its truth value as a raw i32 (no t/nil
		// boxing); the wasm-if arms stay THEN-on-nil either way.
		if (WasmComparisonCompiler.tryCompileConditionI32(parts.get(1), ctx)) {
			ctx.writer.write(Instruction.I32_EQZ);
		}
		else {
			WasmExprCompiler.compileExpr(parts.get(1), ctx);
			ctx.writer.write(Instruction.REF_IS_NULL);
		}
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
