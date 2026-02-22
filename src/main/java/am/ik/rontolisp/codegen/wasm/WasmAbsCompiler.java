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
			ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_FLOAT);
		}
		else {
			// Integer path: store boxed value, use if/else with unbox on demand
			int tmpSlot = ctx.allocTemp();
			ctx.writer.write(Instruction.SET_LOCAL);
			ctx.writer.writeSignedLeb128(tmpSlot);
			// Condition: x < 0
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeSignedLeb128(tmpSlot);
			WasmEmitHelper.castI31GetS(ctx);
			ctx.writer.write(Instruction.I32_CONST);
			ctx.writer.writeSignedLeb128(0);
			ctx.writer.write(Instruction.I32_LT_S);
			// if x < 0: return (0 - x) as i31ref
			ctx.writer.write(Instruction.IF);
			ctx.writer.write(Type.REFNULL.code());
			ctx.writer.writeHeapType(Type.EQ.code());
			ctx.writer.write(Instruction.I32_CONST);
			ctx.writer.writeSignedLeb128(0);
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeSignedLeb128(tmpSlot);
			WasmEmitHelper.castI31GetS(ctx);
			ctx.writer.write(Instruction.I32_SUB);
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
			// else: return x as-is
			ctx.writer.write(Instruction.ELSE);
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeSignedLeb128(tmpSlot);
			ctx.writer.write(Instruction.END);
		}
	}

}
