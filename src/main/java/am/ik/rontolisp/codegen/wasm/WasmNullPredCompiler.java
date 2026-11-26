package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.macro.LispMacroExpander;
import am.ik.wasm.Instruction;

/**
 * Compiles the {@code null} / {@code not} predicate in VALUE position: the argument as a
 * test answering its complement ({@link WasmConditionCompiler}, so {@code (not (consp
 * x))} is one {@code ref.test} and one box rather than two boxes), then the box; and
 * {@code endp}.
 */
final class WasmNullPredCompiler {

	private WasmNullPredCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		WasmConditionCompiler.compile(args.get(1), ctx, true);
		WasmEmitHelper.emitBoolFromI32(ctx);
	}

	/**
	 * Compiles {@code (%check-list x 'op)}: {@code x}, checked to be a list under
	 * {@code op} ({@link WasmEmitHelper#emitListCheck}).
	 */
	static void compileCheckList(LispCons cons, WasmLispCompiler.Ctx ctx) {
		WasmExprCompiler.compileExpr(cons.toList().get(1), ctx);
		int slot = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
		WasmOperandTypes.withOperator(ctx, LispMacroExpander.checkListOperator(cons),
				() -> WasmEmitHelper.emitListCheck(ctx, slot, true));
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
	}

	/**
	 * Compiles {@code endp}: {@code null} of a value checked to be a list -- anything
	 * else is {@code ENDP}'s type-error in EH mode and a trap outside it
	 * ({@link WasmEmitHelper#emitListCheck}).
	 */
	static void compileEndp(LispCons cons, WasmLispCompiler.Ctx ctx) {
		WasmExprCompiler.compileExpr(cons.toList().get(1), ctx);
		int slot = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
		WasmEmitHelper.emitListCheck(ctx, slot, true);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
		ctx.writer.write(Instruction.REF_IS_NULL);
		WasmEmitHelper.emitBoolFromI32(ctx);
	}

}
