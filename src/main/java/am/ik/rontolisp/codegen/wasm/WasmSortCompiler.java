package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;

/**
 * Compiles the {@code sort} built-in function when the program does not carry the shared
 * {@code %sort-runtime} merge sort -- which only a program that defines that name itself
 * does not (see {@code .kb/sort.md}). Generates an inline selection sort over the cons
 * cells of the list, swapping car values (not relinking) according to the comparison
 * predicate, so the original list head is returned in sorted order: correct, and
 * quadratic, which is why every other program calls the helper instead.
 */
final class WasmSortCompiler {

	private WasmSortCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();

		// Compile list, then predicate (left-to-right evaluation order). A literal
		// predicate is called directly, anything else goes through the arity-2
		// dispatcher.
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		int listSlot = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(listSlot);

		WasmDesignatorCall call = WasmDesignatorCall.prepare(args.get(2), 2, () -> {
			ctx.indirectCallArities.add(2);
			return WasmLispCompiler.FUNC_DISPATCH_BASE + 2;
		}, ctx);

		int iSlot = ctx.allocTemp();
		int jSlot = ctx.allocTemp();
		int tmpSlot = ctx.allocTemp();

		// i = list
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(listSlot);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(iSlot);

		// block $outer / loop $outerLoop
		ctx.writer.write(Instruction.BLOCK, 0x40);
		ctx.writer.write(Instruction.LOOP, 0x40);

		// if !consp(i) br $outer
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(iSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CONS);
		ctx.writer.write(Instruction.I32_EQZ);
		ctx.writer.write(Instruction.BR_IF, 1); // exit to $outer

		// j = cdr(i)
		emitCdr(ctx, iSlot);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(jSlot);

		// block $inner / loop $innerLoop
		ctx.writer.write(Instruction.BLOCK, 0x40);
		ctx.writer.write(Instruction.LOOP, 0x40);

		// if !consp(j) br $inner
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(jSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CONS);
		ctx.writer.write(Instruction.I32_EQZ);
		ctx.writer.write(Instruction.BR_IF, 1); // exit to $inner

		// if truthy(pred(car(j), car(i))) swap car(i) and car(j)
		call.emitCall(ctx, List.of(() -> emitCar(ctx, jSlot), () -> emitCar(ctx, iSlot)));
		ctx.writer.write(Instruction.REF_IS_NULL);
		ctx.writer.write(Instruction.I32_EQZ); // 1 when the predicate result is truthy
		ctx.writer.write(Instruction.IF, 0x40);

		// tmp = car(i)
		emitCar(ctx, iSlot);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(tmpSlot);
		// car(i) = car(j)
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(iSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CONS);
		emitCar(ctx, jSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_SET);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		ctx.writer.writeUnsignedLeb128(0); // car
		// car(j) = tmp
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(jSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CONS);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(tmpSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_SET);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		ctx.writer.writeUnsignedLeb128(0); // car

		ctx.writer.write(Instruction.END); // end if

		// j = cdr(j)
		emitCdr(ctx, jSlot);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(jSlot);
		ctx.writer.write(Instruction.BR, 0); // continue innerLoop
		ctx.writer.write(Instruction.END); // end innerLoop
		ctx.writer.write(Instruction.END); // end $inner

		// i = cdr(i)
		emitCdr(ctx, iSlot);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(iSlot);
		ctx.writer.write(Instruction.BR, 0); // continue outerLoop
		ctx.writer.write(Instruction.END); // end outerLoop
		ctx.writer.write(Instruction.END); // end $outer

		// result = list (original head, now sorted)
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(listSlot);
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

	private static void emitCdr(WasmLispCompiler.Ctx ctx, int slot) {
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CONS);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		ctx.writer.writeUnsignedLeb128(1); // cdr
	}

}
