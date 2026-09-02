package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;

/**
 * Compiles the {@code abs} built-in function.
 */
final class WasmAbsCompiler {

	private WasmAbsCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		if (WasmLispCompiler.hasDoubleLiteral(args)) {
			// Double path: f64.abs is a native WASM instruction
			WasmEmitHelper.castFloatGetF64(ctx);
			ctx.writer.write(Instruction.F64_ABS);
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
			ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_FLOAT);
		}
		else {
			int tmpSlot = ctx.allocTemp();
			ctx.writer.write(Instruction.SET_LOCAL);
			ctx.writer.writeUnsignedLeb128(tmpSlot);
			// Float path FIRST, even without a literal in the argument form: |x| on a
			// float is the sign bit cleared, which is what f64.abs and Math.abs both
			// do. The compare-and-subtract ladder below returns -0.0 unchanged (the
			// comparison is false), so a signed zero reaching abs through a VARIABLE
			// used to print differently here than on the interpreter and the JVM.
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeUnsignedLeb128(tmpSlot);
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
			ctx.writer.writeHeapType(WasmLispCompiler.TYPE_FLOAT);
			ctx.writer.write(Instruction.IF);
			ctx.writer.writeRefType(true, Type.EQ.code());
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeUnsignedLeb128(tmpSlot);
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
			ctx.writer.writeHeapType(WasmLispCompiler.TYPE_FLOAT);
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
			ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_FLOAT);
			ctx.writer.writeUnsignedLeb128(0);
			ctx.writer.write(Instruction.F64_ABS);
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
			ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_FLOAT);
			ctx.writer.write(Instruction.ELSE);
			// Integer/ratio path: x < 0 (via _rat_cmp against 0) ? 0 - x : x
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeUnsignedLeb128(tmpSlot);
			ctx.writer.write(Instruction.I32_CONST);
			ctx.writer.writeSignedLeb128(0);
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
			ctx.writer.write(Instruction.CALL);
			ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_RAT_CMP);
			ctx.writer.write(Instruction.I32_CONST);
			ctx.writer.writeSignedLeb128(0);
			ctx.writer.write(Instruction.I32_LT_S);
			// if x < 0: return (0 - x)
			ctx.writer.write(Instruction.IF);
			ctx.writer.writeRefType(true, Type.EQ.code());
			ctx.writer.write(Instruction.I32_CONST);
			ctx.writer.writeSignedLeb128(0);
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeUnsignedLeb128(tmpSlot);
			ctx.writer.write(Instruction.CALL);
			ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_RAT_SUB);
			// else: return x as-is
			ctx.writer.write(Instruction.ELSE);
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeUnsignedLeb128(tmpSlot);
			ctx.writer.write(Instruction.END);
			ctx.writer.write(Instruction.END);
		}
	}

}
