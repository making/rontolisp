package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;

/**
 * Compiles {@code (%elt-cell list index)}, the list arm of {@code elt} and of its
 * {@code setf} place: a block/loop that walks the list counting the cells it passes and
 * answers the cell at {@code index}.
 *
 * <p>
 * An index outside the list -- past its end, negative, wider than a fixnum -- matches no
 * cell, so the walk meets nil having counted the list's length and hands the index and
 * that count to {@code _idx_in}, the bound check an {@code aref} subscript goes through:
 * in EH mode {@code ELT}'s {@code type-error} with expected type
 * {@code (INTEGER 0 (length))}, outside it (or in a module whose landing has no index
 * arm) a trap. A non-list met on the way is {@code ELT}'s {@code LIST} type-error in EH
 * mode, a trap outside it. The counters are i31refs, since all locals are typed
 * {@code (ref null eq)}.
 */
final class WasmEltCellCompiler {

	private WasmEltCellCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		int listSlot = ctx.allocTemp();
		setLocal(ctx, listSlot);
		// The index, checked as an integer in EH mode (ELT's type-error otherwise).
		WasmExprCompiler.compileExpr(args.get(2), ctx);
		WasmEmitHelper.emitIndexCheck(ctx);
		int indexSlot = ctx.allocTemp();
		setLocal(ctx, indexSlot);
		// target = the index when a non-negative fixnum, else -1: a negative or a wide
		// index matches no cell. The walk counts DOWN from it, as nthcdr's does, so the
		// cells passed are target - remaining, a -1 target only moving further from 0.
		int targetSlot = ctx.allocTemp();
		getLocal(ctx, indexSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(Type.I31.code());
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(Type.I32);
		getLocal(ctx, indexSlot);
		WasmEmitHelper.castI31GetS(ctx);
		ctx.writer.write(Instruction.ELSE);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(-1);
		ctx.writer.write(Instruction.END);
		int remainingSlot = ctx.allocTemp();
		// (max target -1): select(target, -1, target >= 0)
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		ctx.writer.write(Instruction.TEE_LOCAL);
		ctx.writer.writeUnsignedLeb128(targetSlot);
		WasmEmitHelper.castI31GetS(ctx);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(0);
		ctx.writer.write(Instruction.I32_LT_S);
		ctx.writer.write(Instruction.IF, WasmLispCompiler.BLOCKTYPE_EMPTY);
		i31Const(ctx, -1);
		setLocal(ctx, targetSlot);
		ctx.writer.write(Instruction.END);
		getLocal(ctx, targetSlot);
		setLocal(ctx, remainingSlot);
		// (block $found (loop $walk
		ctx.writer.write(Instruction.BLOCK, 0x40);
		ctx.writer.write(Instruction.LOOP, 0x40);
		// nil: the index is outside the list, whose length is target - remaining
		getLocal(ctx, listSlot);
		ctx.writer.write(Instruction.REF_IS_NULL);
		ctx.writer.write(Instruction.IF, WasmLispCompiler.BLOCKTYPE_EMPTY);
		getLocal(ctx, indexSlot);
		getLocal(ctx, targetSlot);
		WasmEmitHelper.castI31GetS(ctx);
		getLocal(ctx, remainingSlot);
		WasmEmitHelper.castI31GetS(ctx);
		ctx.writer.write(Instruction.I32_SUB);
		WasmOperandTypes.emitCall(ctx, WasmLispCompiler.FUNC_IDX_IN);
		ctx.writer.write(Instruction.DROP);
		ctx.writer.write(Instruction.UNREACHABLE);
		ctx.writer.write(Instruction.END);
		// the cell at the index: br $found
		getLocal(ctx, remainingSlot);
		WasmEmitHelper.castI31GetS(ctx);
		ctx.writer.write(Instruction.I32_EQZ);
		ctx.writer.write(Instruction.BR_IF, 1);
		// list = cdr(list): in EH mode a non-list is ELT's type-error, in the one type
		// test the step makes anyway; outside it the cast traps
		getLocal(ctx, listSlot);
		if (WasmEmitHelper.checksConsFields(ctx)) {
			WasmEmitHelper.emitCheckedConsField(ctx.writer, 1, () -> WasmEmitHelper.emitListTypeError(ctx));
		}
		else {
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
			ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CONS);
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
			ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
			ctx.writer.writeUnsignedLeb128(1); // cdr
		}
		setLocal(ctx, listSlot);
		// remaining = remaining - 1, br $walk
		getLocal(ctx, remainingSlot);
		WasmEmitHelper.castI31GetS(ctx);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(1);
		ctx.writer.write(Instruction.I32_SUB);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		setLocal(ctx, remainingSlot);
		ctx.writer.write(Instruction.BR, 0);
		ctx.writer.write(Instruction.END); // end loop
		ctx.writer.write(Instruction.END); // end block
		// The cell itself must be a cons: (elt '(1 . 2) 1) meets 2 there.
		WasmEmitHelper.emitListCheck(ctx, listSlot, false);
		getLocal(ctx, listSlot);
	}

	private static void getLocal(WasmLispCompiler.Ctx ctx, int slot) {
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
	}

	private static void setLocal(WasmLispCompiler.Ctx ctx, int slot) {
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
	}

	private static void i31Const(WasmLispCompiler.Ctx ctx, int value) {
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(value);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
	}

}
