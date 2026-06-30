package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.FunctionDesignators;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;

/**
 * Compiles the {@code mapcan} built-in function. Generates a block/loop that applies a
 * function to each element of a list and concatenates the resulting lists using the
 * shared {@code _append} runtime helper (non-destructive append rather than nconc).
 */
final class WasmMapcanCompiler {

	private WasmMapcanCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		ctx.indirectCallArities.add(1);
		int dispatchFuncIdx = WasmLispCompiler.FUNC_DISPATCH_BASE + 1;

		// Compile function expression
		WasmExprCompiler.compileExpr(FunctionDesignators.normalize(args.get(1)), ctx);
		int funcSlot = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeSignedLeb128(funcSlot);

		// Compile list expression
		WasmExprCompiler.compileExpr(args.get(2), ctx);
		int listSlot = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeSignedLeb128(listSlot);

		// mapcan operates on lists; a non-list (e.g. a string) traps.
		WasmEmitHelper.emitRequireListGuard(ctx, listSlot);

		// result = null
		int resultSlot = ctx.allocTemp();
		ctx.writer.write(Instruction.REF_NULL);
		ctx.writer.writeHeapType(Type.EQ.code());
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeSignedLeb128(resultSlot);

		int mappedSlot = ctx.allocTemp();

		// block $exit / loop $cont
		ctx.writer.write(Instruction.BLOCK, 0x40);
		ctx.writer.write(Instruction.LOOP, 0x40);

		// Check if list is cons; if not, break to $exit
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(listSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CONS);
		ctx.writer.write(Instruction.I32_EQZ);
		ctx.writer.write(Instruction.BR_IF, 1); // break to $exit

		// mapped = dispatch_1(func, car(list))
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(funcSlot);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(listSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CONS);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
		ctx.writer.writeSignedLeb128(0); // car
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeSignedLeb128(dispatchFuncIdx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeSignedLeb128(mappedSlot);

		// result = _append(result, mapped)
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(resultSlot);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(mappedSlot);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.FUNC_APPEND);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeSignedLeb128(resultSlot);

		// list = cdr(list)
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(listSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CONS);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
		ctx.writer.writeSignedLeb128(1); // cdr
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeSignedLeb128(listSlot);

		// br 0 (continue loop)
		ctx.writer.write(Instruction.BR, 0);
		ctx.writer.write(Instruction.END); // end loop
		ctx.writer.write(Instruction.END); // end block

		// Result
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(resultSlot);
	}

}
