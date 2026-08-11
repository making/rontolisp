package am.ik.rontolisp.codegen.wasm;

import java.util.ArrayList;
import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;

/**
 * Compiles the {@code mapc} built-in function. Generates a block/loop that applies a
 * function to the parallel elements of one or more lists for its side effects, discards
 * the results, and leaves the FIRST list on the stack (Common Lisp {@code mapc}
 * semantics). With multiple lists the loop stops at the shortest one.
 */
final class WasmMapcCompiler {

	private WasmMapcCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		int nLists = args.size() - 2;
		if (nLists < 1) {
			throw new UnsupportedOperationException(LispNames.MAPC
					+ " expects at least 2 arguments (a function and one list), got " + (args.size() - 1));
		}
		// Compile the function designator: a literal one is called directly, anything
		// else goes through the arity dispatcher.
		WasmDesignatorCall call = WasmDesignatorCall.prepare(args.get(1), nLists,
				() -> WasmLispCompiler.mapDispatchFuncIndex(LispNames.MAPC, nLists, ctx), ctx);

		// Compile each list expression; mapc operates on lists, so a non-list (e.g. a
		// string) traps.
		List<Integer> listSlots = new ArrayList<>();
		for (int i = 0; i < nLists; i++) {
			WasmExprCompiler.compileExpr(args.get(2 + i), ctx);
			int listSlot = ctx.allocTemp();
			ctx.writer.write(Instruction.SET_LOCAL);
			ctx.writer.writeUnsignedLeb128(listSlot);
			WasmEmitHelper.emitRequireListGuard(ctx, listSlot);
			listSlots.add(listSlot);
		}

		// cursor_i = list_i (the first list is returned, so it keeps its own slot)
		List<Integer> cursorSlots = new ArrayList<>();
		for (int listSlot : listSlots) {
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeUnsignedLeb128(listSlot);
			int cursorSlot = ctx.allocTemp();
			ctx.writer.write(Instruction.SET_LOCAL);
			ctx.writer.writeUnsignedLeb128(cursorSlot);
			cursorSlots.add(cursorSlot);
		}

		// block $exit / loop $cont
		ctx.writer.write(Instruction.BLOCK, 0x40);
		ctx.writer.write(Instruction.LOOP, 0x40);

		// Break to $exit as soon as any cursor is no longer a cons (shortest list stops).
		for (int cursorSlot : cursorSlots) {
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeUnsignedLeb128(cursorSlot);
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
			ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CONS);
			ctx.writer.write(Instruction.I32_EQZ);
			ctx.writer.write(Instruction.BR_IF, 1); // break to $exit
		}

		// Call the function with the car of each cursor
		List<Runnable> cars = new ArrayList<>();
		for (int cursorSlot : cursorSlots) {
			cars.add(() -> emitCar(ctx, cursorSlot));
		}
		call.emitCall(ctx, cars);
		// Discard the mapped result
		ctx.writer.write(Instruction.DROP);

		// advance each cursor: cursor = cdr(cursor)
		for (int cursorSlot : cursorSlots) {
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeUnsignedLeb128(cursorSlot);
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
			ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CONS);
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
			ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
			ctx.writer.writeUnsignedLeb128(1); // cdr
			ctx.writer.write(Instruction.SET_LOCAL);
			ctx.writer.writeUnsignedLeb128(cursorSlot);
		}

		// br 0 (continue loop)
		ctx.writer.write(Instruction.BR, 0);
		ctx.writer.write(Instruction.END); // end loop
		ctx.writer.write(Instruction.END); // end block

		// Result: the first list
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(listSlots.get(0));
	}

	private static void emitCar(WasmLispCompiler.Ctx ctx, int slot) {
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CONS);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		ctx.writer.writeUnsignedLeb128(0); // car
	}

}
