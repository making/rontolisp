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
			// Return value
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
	}

}
