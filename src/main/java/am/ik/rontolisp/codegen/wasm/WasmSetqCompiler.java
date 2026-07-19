package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;

/**
 * Compiles the {@code setq} special form.
 */
final class WasmSetqCompiler {

	private WasmSetqCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> parts = cons.toList();
		if ((parts.size() - 1) % 2 != 0) {
			throw new IllegalArgumentException("setq requires an even number of arguments");
		}
		if (parts.size() == 1) {
			// (setq) -> nil
			ctx.writer.write(Instruction.REF_NULL);
			ctx.writer.writeHeapType(am.ik.wasm.Type.EQ.code());
			return;
		}
		int pairCount = (parts.size() - 1) / 2;
		if (ctx.asyncResume != null && WasmAwaitAnalysis.countAwaits(cons) > 0) {
			// State-machine mode: guard each pair like a sequence statement so a resume
			// does not re-run earlier pairs' values (their locals are restored).
			for (int p = 0; p < pairCount; p++) {
				String name = ((LispSymbol) parts.get(1 + 2 * p)).name();
				LispVal valueExpr = parts.get(2 + 2 * p);
				if (p == pairCount - 1) {
					ctx.asyncSpine = true;
					compilePair(name, valueExpr, ctx);
					continue;
				}
				int n = WasmAwaitAnalysis.countAwaits(valueExpr);
				if (n == 0) {
					ctx.writer.write(Instruction.GET_LOCAL);
					ctx.writer.writeSignedLeb128(WasmAsyncEmit.RT_SLOT);
					ctx.writer.write(Instruction.I32_EQZ);
					ctx.writer.write(Instruction.IF, WasmLispCompiler.BLOCKTYPE_EMPTY);
					ctx.wasmCtrlDepth++;
					ctx.asyncSpine = true;
					compilePair(name, valueExpr, ctx);
					ctx.writer.write(Instruction.DROP);
					ctx.wasmCtrlDepth--;
					ctx.writer.write(Instruction.END);
					continue;
				}
				int lo = ctx.asyncResume.nextState;
				ctx.writer.write(Instruction.BLOCK, WasmLispCompiler.BLOCKTYPE_EMPTY);
				ctx.wasmCtrlDepth++;
				WasmAsyncEmit.emitRangeGuard(ctx, lo, lo + n - 1);
				ctx.writer.write(Instruction.I32_EQZ);
				ctx.writer.write(Instruction.BR_IF, 0);
				ctx.asyncSpine = true;
				compilePair(name, valueExpr, ctx);
				ctx.writer.write(Instruction.DROP);
				ctx.wasmCtrlDepth--;
				ctx.writer.write(Instruction.END);
				WasmAsyncEmit.assertStates(ctx, lo, n, valueExpr);
			}
			return;
		}
		for (int p = 0; p < pairCount; p++) {
			compilePair(((LispSymbol) parts.get(1 + 2 * p)).name(), parts.get(2 + 2 * p), ctx);
			if (p < pairCount - 1) {
				// Discard the intermediate value; only the last pair's value is the
				// result
				ctx.writer.write(Instruction.DROP);
			}
		}
	}

	private static void compilePair(String name, LispVal valueExpr, WasmLispCompiler.Ctx ctx) {
		// Check if variable is a boxed local
		Integer slot = ctx.locals.get(name);
		if (slot != null && ctx.boxedVars.contains(name)) {
			// Write to cell: compile value, save to temp, then set cell
			WasmExprCompiler.compileExpr(valueExpr, ctx);
			int tmpSlot = ctx.allocTemp();
			ctx.writer.write(Instruction.SET_LOCAL);
			ctx.writer.writeSignedLeb128(tmpSlot);
			// Load cell
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeSignedLeb128(slot);
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
			ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CELL);
			// Push value
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeSignedLeb128(tmpSlot);
			// Set cell field
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_SET);
			ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_CELL);
			ctx.writer.writeSignedLeb128(0);
			dualWriteSpecialGlobal(name, tmpSlot, ctx);
			// Return value
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeSignedLeb128(tmpSlot);
			return;
		}

		// Check if variable is a captured var
		Integer captureIdx = ctx.captures.get(name);
		if (captureIdx != null) {
			// Write to captured cell
			WasmExprCompiler.compileExpr(valueExpr, ctx);
			int tmpSlot = ctx.allocTemp();
			ctx.writer.write(Instruction.SET_LOCAL);
			ctx.writer.writeSignedLeb128(tmpSlot);
			// Navigate to cell in env
			WasmEmitHelper.emitLoadCaptureCell(ctx, captureIdx);
			// Push value
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeSignedLeb128(tmpSlot);
			// Set cell
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_SET);
			ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_CELL);
			ctx.writer.writeSignedLeb128(0);
			dualWriteSpecialGlobal(name, tmpSlot, ctx);
			// Return value
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeSignedLeb128(tmpSlot);
			return;
		}

		// A top-level global variable (not shadowed by a lexical here): store into its
		// module-level wasm global. Works from any function body, so a defun/lambda can
		// assign a global. The eval mirror still runs at top level (no-op elsewhere).
		Integer globalIndex = ctx.globalIndices.get(name);
		if (slot == null && globalIndex != null) {
			WasmExprCompiler.compileExpr(valueExpr, ctx);
			int tmpSlot = ctx.allocTemp();
			ctx.writer.write(Instruction.TEE_LOCAL);
			ctx.writer.writeSignedLeb128(tmpSlot);
			ctx.writer.write(Instruction.SET_GLOBAL);
			ctx.writer.writeUnsignedLeb128(globalIndex);
			mirrorTopLevelGlobal(name, tmpSlot, ctx);
			// Leave the assigned value on the stack as the form's result.
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeSignedLeb128(tmpSlot);
			return;
		}

		// Plain local (not boxed)
		WasmExprCompiler.compileExpr(valueExpr, ctx);
		if (slot == null) {
			slot = ctx.allocLocal(name);
		}
		ctx.writer.write(Instruction.TEE_LOCAL);
		ctx.writer.writeSignedLeb128(slot);
		mirrorTopLevelGlobal(name, slot, ctx);
		dualWriteSpecialGlobal(name, slot, ctx);
	}

	// A special that is dual-bound here (a lexical slot/capture established by a
	// special-named let, see WasmLetCompiler): the assignment must reach the DYNAMIC
	// binding too, so a called function reading the special sees it. Stack-neutral.
	private static void dualWriteSpecialGlobal(String name, int valueSlot, WasmLispCompiler.Ctx ctx) {
		Integer globalIndex = ctx.globalIndices.get(name);
		if (globalIndex == null || !ctx.specialVars.contains(name)) {
			return;
		}
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(valueSlot);
		ctx.writer.write(Instruction.SET_GLOBAL);
		ctx.writer.writeUnsignedLeb128(globalIndex);
	}

	/**
	 * Mirrors a top-level global variable binding into the embedded {@code eval}
	 * runtime's global environment ({@code GLOBAL_ENV}), so an eval'd expression can
	 * resolve a variable that compiled code defined via {@code setq}/{@code defvar} (the
	 * compiled value otherwise lives only in a {@code _start} local the interpreter
	 * cannot see). No-op unless the program uses {@code eval} and this is the top-level
	 * context. Reads the assigned value back from {@code slot}; the value already on the
	 * stack (left there by the {@code local.tee}) is preserved as the form's result.
	 */
	static void mirrorTopLevelGlobal(String name, int slot, WasmLispCompiler.Ctx ctx) {
		if (!ctx.topLevel || !ctx.usesEval) {
			return;
		}
		// _store(place, value, GLOBAL_ENV) -> value ; drop the returned value (the result
		// already sits below it on the stack).
		WasmEmitHelper.compileStringLiteral(name, ctx); // symbol place
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(slot);
		ctx.writer.write(Instruction.GET_GLOBAL);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.GLOBAL_ENV);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.FUNC_STORE);
		ctx.writer.write(Instruction.DROP);
	}

}
