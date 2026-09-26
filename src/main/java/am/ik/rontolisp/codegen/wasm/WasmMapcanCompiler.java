package am.ik.rontolisp.codegen.wasm;

import java.util.ArrayList;
import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;

/**
 * Compiles the {@code mapcan} built-in function. Generates a block/loop that applies a
 * function to the parallel elements of one or more lists and concatenates the resulting
 * lists. With multiple lists the loop stops at the shortest one.
 *
 * <p>
 * The concatenation is a tail-pointer splice of a FRESH copy of each piece, not a left
 * fold over the shared {@code _append} helper: folding copied the whole accumulator per
 * piece (quadratic) through a call that itself recursed per element (linear stack depth),
 * so a long input list was a slow crash rather than a slow call (.todo/749). The result
 * is fully fresh, matching the interpreter's right fold piece for piece.
 */
final class WasmMapcanCompiler {

	private WasmMapcanCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		int nLists = args.size() - 2;
		if (nLists < 1) {
			throw new UnsupportedOperationException(LispNames.MAPCAN
					+ " expects at least 2 arguments (a function and one list), got " + (args.size() - 1));
		}
		// Compile the function designator: a literal one is called directly, anything
		// else goes through the arity dispatcher.
		WasmDesignatorCall call = WasmDesignatorCall.prepare(args.get(1), nLists,
				() -> WasmLispCompiler.mapDispatchFuncIndex(LispNames.MAPCAN, nLists, ctx), ctx);

		// Compile each list expression; mapcan operates on lists, so a non-list (e.g. a
		// string) traps. The slots double as the cursors -- only the concatenation is
		// returned, so no list has to survive the walk.
		List<Integer> listSlots = new ArrayList<>();
		for (int i = 0; i < nLists; i++) {
			WasmExprCompiler.compileExpr(args.get(2 + i), ctx);
			int listSlot = ctx.allocTemp();
			ctx.writer.write(Instruction.SET_LOCAL);
			ctx.writer.writeUnsignedLeb128(listSlot);
			// A non-list is MAPCAN's type-error (EH mode; a trap outside it).
			WasmEmitHelper.emitListCheck(ctx, listSlot, true);
			listSlots.add(listSlot);
		}

		// Sentinel head and its tail: every piece's fresh copy is linked here, so
		// the walk is linear in the total output rather than quadratic in it.
		ctx.writer.write(Instruction.REF_NULL);
		ctx.writer.writeHeapType(Type.EQ.code());
		ctx.writer.write(Instruction.REF_NULL);
		ctx.writer.writeHeapType(Type.EQ.code());
		WasmEmitHelper.emitNewCons(ctx);
		int headSlot = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(headSlot);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(headSlot);
		int tailSlot = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(tailSlot);
		int cursorSlot = ctx.allocTemp();
		int freshSlot = ctx.allocTemp();

		int mappedSlot = ctx.allocTemp();

		// block $exit / loop $cont
		ctx.writer.write(Instruction.BLOCK, 0x40);
		ctx.writer.write(Instruction.LOOP, 0x40);

		// Break to $exit as soon as any list is no longer a cons (shortest list stops).
		for (int listSlot : listSlots) {
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeUnsignedLeb128(listSlot);
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
			ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CONS);
			ctx.writer.write(Instruction.I32_EQZ);
			ctx.writer.write(Instruction.BR_IF, 1); // break to $exit
		}

		// mapped = func(car(list)...)
		List<Runnable> cars = new ArrayList<>();
		for (int listSlot : listSlots) {
			cars.add(() -> emitCar(ctx, listSlot));
		}
		call.emitCall(ctx, cars);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(mappedSlot);

		// A nil piece contributes nothing; anything else is spliced in as a fresh
		// copy below. A non-list piece fails the copy's ref.cast, trapping like the
		// interpreter's append over it signals.
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(mappedSlot);
		ctx.writer.write(Instruction.REF_IS_NULL);
		ctx.writer.write(Instruction.IF, 0x40);
		ctx.writer.write(Instruction.ELSE);
		// cursor = mapped
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(mappedSlot);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(cursorSlot);
		// block $piece / loop $copy: fresh = cons(cursor.car, null);
		// tail.cdr = fresh; tail = fresh; cursor = cursor.cdr
		ctx.writer.write(Instruction.BLOCK, 0x40); // $piece
		ctx.writer.write(Instruction.LOOP, 0x40); // $copy
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(cursorSlot);
		ctx.writer.write(Instruction.REF_IS_NULL);
		ctx.writer.write(Instruction.BR_IF, 1); // break to $piece
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(cursorSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CONS);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		ctx.writer.writeUnsignedLeb128(0); // car
		ctx.writer.write(Instruction.REF_NULL);
		ctx.writer.writeHeapType(Type.EQ.code());
		WasmEmitHelper.emitNewCons(ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(freshSlot);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(tailSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CONS);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(freshSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_SET);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		ctx.writer.writeUnsignedLeb128(1); // cdr
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(freshSlot);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(tailSlot);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(cursorSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CONS);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		ctx.writer.writeUnsignedLeb128(1); // cdr
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(cursorSlot);
		ctx.writer.write(Instruction.BR, 0); // continue $copy
		ctx.writer.write(Instruction.END); // end loop $copy
		ctx.writer.write(Instruction.END); // end block $piece
		ctx.writer.write(Instruction.END); // end if (nil piece)

		// advance each list: list = cdr(list)
		for (int listSlot : listSlots) {
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeUnsignedLeb128(listSlot);
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
			ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CONS);
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
			ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
			ctx.writer.writeUnsignedLeb128(1); // cdr
			ctx.writer.write(Instruction.SET_LOCAL);
			ctx.writer.writeUnsignedLeb128(listSlot);
		}

		// br 0 (continue loop)
		ctx.writer.write(Instruction.BR, 0);
		ctx.writer.write(Instruction.END); // end loop
		ctx.writer.write(Instruction.END); // end block

		// Result: head.cdr (cdr of sentinel)
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(headSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CONS);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		ctx.writer.writeUnsignedLeb128(1); // cdr
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
