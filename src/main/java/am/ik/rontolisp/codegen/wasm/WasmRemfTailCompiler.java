package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;

/**
 * Compiles the {@code %remf-tail} built-in function. Walks a property list from the first
 * key, looking ahead at the next key-value pair. When a matching key is found, splices it
 * out by mutating the cdr of the preceding value cell. Uses a block/loop structure
 * similar to {@link WasmNthcdrCompiler}.
 */
final class WasmRemfTailCompiler {

	private WasmRemfTailCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		// Evaluate plist
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		int currentSlot = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeSignedLeb128(currentSlot);
		// Evaluate indicator
		WasmExprCompiler.compileExpr(args.get(2), ctx);
		int indicatorSlot = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeSignedLeb128(indicatorSlot);

		int valueCellSlot = ctx.allocTemp();
		int nextKeyCellSlot = ctx.allocTemp();

		// block $result (result (ref null eq))
		ctx.writer.write(Instruction.BLOCK);
		ctx.writer.write(Type.REFNULL.code());
		ctx.writer.writeHeapType(Type.EQ.code());
		// block $nil (void)
		ctx.writer.write(Instruction.BLOCK, 0x40);
		// loop $cont (void)
		ctx.writer.write(Instruction.LOOP, 0x40);

		// Check current is cons
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(currentSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CONS);
		ctx.writer.write(Instruction.I32_EQZ);
		ctx.writer.write(Instruction.BR_IF, 1); // break to $nil

		// valueCell = cdr(current)
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(currentSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CONS);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
		ctx.writer.writeSignedLeb128(1);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeSignedLeb128(valueCellSlot);

		// Check valueCell is cons
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(valueCellSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CONS);
		ctx.writer.write(Instruction.I32_EQZ);
		ctx.writer.write(Instruction.BR_IF, 1); // break to $nil

		// nextKeyCell = cdr(valueCell)
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(valueCellSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CONS);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
		ctx.writer.writeSignedLeb128(1);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeSignedLeb128(nextKeyCellSlot);

		// Check nextKeyCell is cons
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(nextKeyCellSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CONS);
		ctx.writer.write(Instruction.I32_EQZ);
		ctx.writer.write(Instruction.BR_IF, 1); // break to $nil

		// Compare car(nextKeyCell) with indicator using eq
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(nextKeyCellSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CONS);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
		ctx.writer.writeSignedLeb128(0); // car
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(indicatorSlot);
		WasmEmitHelper.emitEqComparison(ctx);

		ctx.writer.write(Instruction.IF, 0x40);
		// Match! splice: rplacd(valueCell, cddr(nextKeyCell))
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(valueCellSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CONS);
		// cddr(nextKeyCell): cdr(cdr(nextKeyCell))
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(nextKeyCellSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CONS);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
		ctx.writer.writeSignedLeb128(1); // cdr(nextKeyCell)
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CONS);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
		ctx.writer.writeSignedLeb128(1); // cddr(nextKeyCell)
		// struct.set valueCell.cdr
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_SET);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
		ctx.writer.writeSignedLeb128(1);
		// Push t and break to $result
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(1);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		ctx.writer.write(Instruction.BR, 2); // break to $result
		ctx.writer.write(Instruction.END); // end if (match)

		// No match: current = nextKeyCell, continue loop
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(nextKeyCellSlot);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeSignedLeb128(currentSlot);
		ctx.writer.write(Instruction.BR, 0); // continue loop

		ctx.writer.write(Instruction.END); // end loop
		ctx.writer.write(Instruction.END); // end $nil block
		// Fall-through: nil
		ctx.writer.write(Instruction.REF_NULL);
		ctx.writer.writeHeapType(Type.EQ.code());
		ctx.writer.write(Instruction.END); // end $result block
	}

}
