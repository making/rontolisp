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
					ctx.writer.writeUnsignedLeb128(WasmAsyncEmit.RT_SLOT);
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

	/**
	 * Statement-position {@code setq} (the value is discarded): an unboxed-local pair
	 * skips the boxed re-read entirely -- the hot-loop store of a round temp then
	 * allocates nothing (todo 194 stage 3). Every other pair compiles normally with its
	 * value dropped.
	 */
	static void compileForEffect(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> parts = cons.toList();
		if (ctx.asyncResume != null || (parts.size() - 1) % 2 != 0) {
			compile(cons, ctx);
			ctx.writer.write(Instruction.DROP);
			return;
		}
		if (parts.size() == 1) {
			return;
		}
		for (int p = 0; p < (parts.size() - 1) / 2; p++) {
			String name = ((LispSymbol) parts.get(1 + 2 * p)).name();
			LispVal valueExpr = parts.get(2 + 2 * p);
			WasmIntFusionCompiler.RawLocal raw = ctx.rawLocals.get(name);
			if (raw != null) {
				WasmIntFusionCompiler.compileRawStore(valueExpr, ctx, raw);
			}
			else {
				compilePair(name, valueExpr, ctx);
				ctx.writer.write(Instruction.DROP);
			}
		}
	}

	private static void compilePair(String name, LispVal valueExpr, WasmLispCompiler.Ctx ctx) {
		// An unboxed (dual-representation) local: raw store, then re-read boxed as the
		// form's value (i31 for the fixnum range -- allocation-free).
		WasmIntFusionCompiler.RawLocal raw = ctx.rawLocals.get(name);
		if (raw != null) {
			WasmIntFusionCompiler.compileRawStore(valueExpr, ctx, raw);
			WasmIntFusionCompiler.emitRawLocalBoxedRead(raw, ctx);
			return;
		}
		// Check if variable is a boxed local
		Integer slot = ctx.locals.get(name);
		if (slot != null && ctx.boxedVars.contains(name)) {
			// Write to cell: compile value, save to temp, then set cell
			WasmExprCompiler.compileExpr(valueExpr, ctx);
			int tmpSlot = ctx.allocTemp();
			ctx.writer.write(Instruction.SET_LOCAL);
			ctx.writer.writeUnsignedLeb128(tmpSlot);
			// Load cell
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeUnsignedLeb128(slot);
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
			ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CELL);
			// Push value
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeUnsignedLeb128(tmpSlot);
			// Set cell field
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_SET);
			ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_CELL);
			ctx.writer.writeUnsignedLeb128(0);
			dualWriteSpecialGlobal(name, tmpSlot, ctx);
			// Return value
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeUnsignedLeb128(tmpSlot);
			return;
		}

		// Check if variable is a captured var
		Integer captureIdx = ctx.captures.get(name);
		if (captureIdx != null) {
			// Write to captured cell
			WasmExprCompiler.compileExpr(valueExpr, ctx);
			int tmpSlot = ctx.allocTemp();
			ctx.writer.write(Instruction.SET_LOCAL);
			ctx.writer.writeUnsignedLeb128(tmpSlot);
			// Navigate to cell in env
			WasmEmitHelper.emitLoadCaptureCell(ctx, captureIdx);
			// Push value
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeUnsignedLeb128(tmpSlot);
			// Set cell
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_SET);
			ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_CELL);
			ctx.writer.writeUnsignedLeb128(0);
			dualWriteSpecialGlobal(name, tmpSlot, ctx);
			// Return value
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeUnsignedLeb128(tmpSlot);
			return;
		}

		// A top-level global variable (not shadowed by a lexical here): store into its
		// module-level wasm global. Works from any function body, so a defun/lambda can
		// assign a global. The eval mirror still runs at top level (no-op elsewhere).
		// --reentrant: a dynamically-bound special's setq assigns the ACTIVE binding in
		// this call's task record when there is one -- the CL rule -- and the global
		// default otherwise (WasmDynVars.emitWrite).
		Integer globalIndex = ctx.globalIndices.get(name);
		if (slot == null && globalIndex != null) {
			WasmExprCompiler.compileExpr(valueExpr, ctx);
			int tmpSlot = ctx.allocTemp();
			if (WasmDynVars.handles(ctx, name)) {
				ctx.writer.write(Instruction.SET_LOCAL);
				ctx.writer.writeUnsignedLeb128(tmpSlot);
				WasmDynVars.emitWrite(ctx, name, globalIndex, tmpSlot);
			}
			else {
				ctx.writer.write(Instruction.TEE_LOCAL);
				ctx.writer.writeUnsignedLeb128(tmpSlot);
				ctx.writer.write(Instruction.SET_GLOBAL);
				ctx.writer.writeUnsignedLeb128(globalIndex);
			}
			mirrorTopLevelGlobal(name, tmpSlot, ctx);
			// Leave the assigned value on the stack as the form's result.
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeUnsignedLeb128(tmpSlot);
			return;
		}

		// Plain local (not boxed)
		WasmExprCompiler.compileExpr(valueExpr, ctx);
		if (slot == null) {
			slot = ctx.allocLocal(name);
		}
		ctx.writer.write(Instruction.TEE_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
		mirrorTopLevelGlobal(name, slot, ctx);
		dualWriteSpecialGlobal(name, slot, ctx);
	}

	// A special that is dual-bound here (a lexical slot/capture established by a
	// special-named let, see WasmLetCompiler): the assignment must reach the DYNAMIC
	// binding too, so a called function reading the special sees it. Stack-neutral.
	// --reentrant: the dynamic half lives in the per-call task record (WasmDynVars).
	private static void dualWriteSpecialGlobal(String name, int valueSlot, WasmLispCompiler.Ctx ctx) {
		Integer globalIndex = ctx.globalIndices.get(name);
		if (globalIndex == null || !ctx.specialVars.contains(name)) {
			return;
		}
		if (WasmDynVars.handles(ctx, name)) {
			WasmDynVars.emitWrite(ctx, name, globalIndex, valueSlot);
			return;
		}
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(valueSlot);
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
		if (!mirrorsTopLevelGlobal(ctx)) {
			return;
		}
		// _store(place, value, GLOBAL_ENV) -> value ; drop the returned value (the result
		// already sits below it on the stack).
		WasmEmitHelper.compileStringLiteral(name, ctx); // symbol place
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
		ctx.writer.write(Instruction.GET_GLOBAL);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.GLOBAL_ENV);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_STORE);
		ctx.writer.write(Instruction.DROP);
	}

	/**
	 * Whether {@link #mirrorTopLevelGlobal} would emit anything here. A caller that has
	 * to stage the assigned value in a local ONLY so the mirror can read it back asks
	 * first, so a program that never evals does not pay a {@code local.tee} -- and a
	 * local -- per top-level binding.
	 * @param ctx the context the assignment is being emitted into
	 * @return {@code true} when the mirror emits
	 */
	static boolean mirrorsTopLevelGlobal(WasmLispCompiler.Ctx ctx) {
		return ctx.topLevel && ctx.usesEval;
	}

}
