package am.ik.rontolisp.codegen.wasm;

import java.util.ArrayList;
import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.FunctionDesignators;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;

/**
 * Compiles the {@code mapcar} built-in function. Generates a block/loop that applies a
 * function to the parallel elements of one or more lists, building a new list using the
 * sentinel/tail-mutation pattern. With multiple lists the loop stops at the shortest list
 * (Common Lisp semantics).
 */
final class WasmMapcarCompiler {

	private WasmMapcarCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		int nLists = args.size() - 2;
		ctx.indirectCallArities.add(nLists);
		int dispatchFuncIdx = WasmLispCompiler.FUNC_DISPATCH_BASE + nLists;

		// Compile function expression
		WasmExprCompiler.compileExpr(FunctionDesignators.normalize(args.get(1)), ctx);
		int funcSlot = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeSignedLeb128(funcSlot);

		// Compile each list expression, guarding it is a list.
		List<Integer> listSlots = new ArrayList<>();
		for (int i = 0; i < nLists; i++) {
			WasmExprCompiler.compileExpr(args.get(2 + i), ctx);
			int listSlot = ctx.allocTemp();
			ctx.writer.write(Instruction.SET_LOCAL);
			ctx.writer.writeSignedLeb128(listSlot);
			WasmEmitHelper.emitRequireListGuard(ctx, listSlot);
			listSlots.add(listSlot);
		}

		// Create sentinel cons: struct.new TYPE_CONS (null, null)
		ctx.writer.write(Instruction.REF_NULL);
		ctx.writer.writeHeapType(Type.EQ.code());
		ctx.writer.write(Instruction.REF_NULL);
		ctx.writer.writeHeapType(Type.EQ.code());
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
		int headSlot = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeSignedLeb128(headSlot);

		// tail = head (sentinel)
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(headSlot);
		int tailSlot = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeSignedLeb128(tailSlot);

		// block $exit / loop $cont
		ctx.writer.write(Instruction.BLOCK, 0x40);
		ctx.writer.write(Instruction.LOOP, 0x40);

		// Break to $exit as soon as any list is no longer a cons (shortest list stops).
		for (int listSlot : listSlots) {
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeSignedLeb128(listSlot);
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
			ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CONS);
			ctx.writer.write(Instruction.I32_EQZ);
			ctx.writer.write(Instruction.BR_IF, 1); // break to $exit
		}

		// Push func and car of each list for dispatch
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(funcSlot);
		for (int listSlot : listSlots) {
			// car(list)
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeSignedLeb128(listSlot);
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
			ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CONS);
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
			ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
			ctx.writer.writeSignedLeb128(0); // car
		}
		// Call dispatch_<nLists>(func, car...)
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeSignedLeb128(dispatchFuncIdx);

		// Create new cons: struct.new TYPE_CONS (mapped, null)
		ctx.writer.write(Instruction.REF_NULL);
		ctx.writer.writeHeapType(Type.EQ.code());
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
		int newConsSlot = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeSignedLeb128(newConsSlot);

		// tail.cdr = newCons (struct.set)
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(tailSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CONS);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(newConsSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_SET);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
		ctx.writer.writeSignedLeb128(1); // cdr

		// tail = newCons
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(newConsSlot);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeSignedLeb128(tailSlot);

		// advance each list: list = cdr(list)
		for (int listSlot : listSlots) {
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeSignedLeb128(listSlot);
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
			ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CONS);
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
			ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
			ctx.writer.writeSignedLeb128(1); // cdr
			ctx.writer.write(Instruction.SET_LOCAL);
			ctx.writer.writeSignedLeb128(listSlot);
		}

		// br 0 (continue loop)
		ctx.writer.write(Instruction.BR, 0);
		ctx.writer.write(Instruction.END); // end loop
		ctx.writer.write(Instruction.END); // end block

		// Result: head.cdr (cdr of sentinel)
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(headSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CONS);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
		ctx.writer.writeSignedLeb128(1); // cdr
	}

}
