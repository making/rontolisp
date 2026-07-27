package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;

/**
 * Compiles the {@code while} special form. Uses a {@code block}/{@code loop} pair: the
 * test is re-evaluated at the top of the loop and {@code br_if} exits the block when it
 * is nil; otherwise the body runs and {@code br} jumps back. The loop leaves nil on the
 * stack as its value.
 */
final class WasmWhileCompiler {

	private WasmWhileCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> parts = cons.toList();
		if (ctx.asyncResume != null && WasmAwaitAnalysis.countAwaits(cons) > 0) {
			compileAsync(parts, ctx);
			return;
		}
		ctx.writer.write(Instruction.BLOCK, 0x40);
		ctx.writer.write(Instruction.LOOP, 0x40);
		// The test and body are compiled inside the block/loop pair; track the two extra
		// levels so a return nested in the body computes the correct br depth.
		ctx.wasmCtrlDepth += 2;
		// Evaluate the test; exit the block (depth 1) when it is nil.
		WasmExprCompiler.compileExpr(parts.get(1), ctx);
		ctx.writer.write(Instruction.REF_IS_NULL);
		ctx.writer.write(Instruction.BR_IF, 1);
		// Body: statement position (values discarded; a packed integer-vector store
		// skips its value-as-stored box, see compileForEffect).
		for (int i = 2; i < parts.size(); i++) {
			WasmExprCompiler.compileForEffect(parts.get(i), ctx);
		}
		// Jump back to the top of the loop (depth 0).
		ctx.writer.write(Instruction.BR, 0);
		ctx.wasmCtrlDepth -= 2;
		ctx.writer.write(Instruction.END); // loop
		ctx.writer.write(Instruction.END); // block
		// Push nil as the result of the while form.
		ctx.writer.write(Instruction.REF_NULL);
		ctx.writer.writeHeapType(Type.EQ.code());
	}

	/**
	 * State-machine mode: a {@code while} containing awaits. The loop-top test runs when
	 * executing normally or when resuming into the test itself; a resume targeting a body
	 * statement skips the test (the iteration state lives in restored locals) and routes
	 * through the body guards. Once a landing completes, {@code $rt} is 0 and later
	 * iterations run plainly.
	 */
	private static void compileAsync(List<LispVal> parts, WasmLispCompiler.Ctx ctx) {
		WasmLispCompiler.AsyncResume ar = java.util.Objects.requireNonNull(ctx.asyncResume);
		LispVal test = parts.get(1);
		int testN = WasmAwaitAnalysis.countAwaits(test);
		int testLo = ar.nextState;
		ctx.writer.write(Instruction.BLOCK, 0x40);
		ctx.writer.write(Instruction.LOOP, 0x40);
		ctx.wasmCtrlDepth += 2;
		if (testN == 0) {
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeSignedLeb128(WasmAsyncEmit.RT_SLOT);
			ctx.writer.write(Instruction.I32_EQZ);
		}
		else {
			WasmAsyncEmit.emitRangeGuard(ctx, testLo, testLo + testN - 1);
		}
		ctx.writer.write(Instruction.IF, 0x40);
		ctx.wasmCtrlDepth++;
		WasmAsyncEmit.spine(test, ctx);
		ctx.writer.write(Instruction.REF_IS_NULL);
		// Exit the outer block: if (0), loop (1), block (2).
		ctx.writer.write(Instruction.BR_IF, 2);
		ctx.wasmCtrlDepth--;
		ctx.writer.write(Instruction.END); // if
		WasmAsyncEmit.assertStates(ctx, testLo, testN, test);
		for (int i = 2; i < parts.size(); i++) {
			WasmAsyncEmit.compileGuardedStatement(parts.get(i), ctx);
		}
		ctx.writer.write(Instruction.BR, 0);
		ctx.wasmCtrlDepth -= 2;
		ctx.writer.write(Instruction.END); // loop
		ctx.writer.write(Instruction.END); // block
		ctx.writer.write(Instruction.REF_NULL);
		ctx.writer.writeHeapType(Type.EQ.code());
	}

}
