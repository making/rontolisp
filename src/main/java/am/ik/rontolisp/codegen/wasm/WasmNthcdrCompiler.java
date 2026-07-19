package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;

/**
 * Compiles the {@code nthcdr} built-in function. Generates a block/loop that applies
 * {@code cdr} n times to the list argument. The counter is kept as an i31ref since all
 * locals are typed {@code (ref null eq)}.
 */
final class WasmNthcdrCompiler {

	private WasmNthcdrCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		// Evaluate n
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		int nSlot = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeSignedLeb128(nSlot);
		// Evaluate list
		WasmExprCompiler.compileExpr(args.get(2), ctx);
		int listSlot = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeSignedLeb128(listSlot);
		// (block $exit (loop $cont
		ctx.writer.write(Instruction.BLOCK, 0x40);
		ctx.writer.write(Instruction.LOOP, 0x40);
		// if n <= 0, break to $exit (br_if 1)
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(nSlot);
		WasmEmitHelper.castI31GetS(ctx);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(0);
		ctx.writer.write(Instruction.I32_LE_S);
		ctx.writer.write(Instruction.BR_IF, 1);
		// nil short-circuits: (nthcdr n lst) past the end is nil, like CL.
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(listSlot);
		ctx.writer.write(Instruction.REF_IS_NULL);
		ctx.writer.write(Instruction.BR_IF, 1);
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
		// n = n - 1 (unbox, sub, rebox)
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(nSlot);
		WasmEmitHelper.castI31GetS(ctx);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(1);
		ctx.writer.write(Instruction.I32_SUB);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeSignedLeb128(nSlot);
		// br 0 (continue loop)
		ctx.writer.write(Instruction.BR, 0);
		ctx.writer.write(Instruction.END); // end loop
		ctx.writer.write(Instruction.END); // end block
		// result: load list
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(listSlot);
	}

}
