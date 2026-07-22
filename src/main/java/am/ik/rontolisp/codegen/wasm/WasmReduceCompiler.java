package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.FunctionDesignators;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;

/**
 * Compiles the {@code reduce} built-in function. Generates a block/loop that applies a
 * binary function to accumulate elements of a list into a single value (left fold).
 * Supports both 2-arg {@code (reduce f list)} and the Common Lisp keyword form
 * {@code (reduce f list :initial-value init)}.
 */
final class WasmReduceCompiler {

	private WasmReduceCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		ctx.indirectCallArities.add(2);
		int dispatchFuncIdx = WasmLispCompiler.FUNC_DISPATCH_BASE + 2;

		boolean withInit = hasInitialValue(args);

		// Compile function expression
		WasmExprCompiler.compileExpr(FunctionDesignators.normalize(args.get(1)), ctx);
		int funcSlot = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeSignedLeb128(funcSlot);

		int accSlot = ctx.allocTemp();
		int listSlot = ctx.allocTemp();

		if (withInit) {
			// (reduce fn list :initial-value init)
			WasmExprCompiler.compileExpr(args.get(4), ctx);
			ctx.writer.write(Instruction.SET_LOCAL);
			ctx.writer.writeSignedLeb128(accSlot);

			WasmExprCompiler.compileExpr(args.get(2), ctx);
			ctx.writer.write(Instruction.SET_LOCAL);
			ctx.writer.writeSignedLeb128(listSlot);
		}
		else {
			// 2-arg: (reduce fn list) - first element becomes accumulator
			WasmExprCompiler.compileExpr(args.get(2), ctx);
			ctx.writer.write(Instruction.SET_LOCAL);
			ctx.writer.writeSignedLeb128(listSlot);

			// acc = car(list)
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeSignedLeb128(listSlot);
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
			ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CONS);
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
			ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
			ctx.writer.writeSignedLeb128(0); // car
			ctx.writer.write(Instruction.SET_LOCAL);
			ctx.writer.writeSignedLeb128(accSlot);

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
		}

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

		// acc = func(acc, car(list))
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(funcSlot);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(accSlot);
		// car(list)
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(listSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CONS);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
		ctx.writer.writeSignedLeb128(0); // car
		// Call dispatch_2(func, acc, car)
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeSignedLeb128(dispatchFuncIdx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeSignedLeb128(accSlot);

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

		// Result: accumulator
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(accSlot);
	}

	/**
	 * Determines whether the {@code reduce} form carries a literal {@code :initial-value}
	 * keyword. Args are {@code [reduce, fn, list]} (2-arg) or
	 * {@code [reduce, fn, list, :initial-value, init]}.
	 * @param args the full reduce form parts
	 * @return {@code true} for the keyword initial-value form
	 */
	static boolean hasInitialValue(List<LispVal> args) {
		if (args.size() == 3) {
			return false;
		}
		if (args.size() == 5 && args.get(3) instanceof LispSymbol kw
				&& LispNames.INITIAL_VALUE_KEYWORD.equals(kw.name())) {
			return true;
		}
		throw new UnsupportedOperationException(
				"reduce expects (reduce fn list) or (reduce fn list :initial-value init)");
	}

}
