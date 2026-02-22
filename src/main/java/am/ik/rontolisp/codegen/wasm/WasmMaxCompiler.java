package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;

/**
 * Compiles the {@code max} built-in function.
 */
final class WasmMaxCompiler {

	private WasmMaxCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		if (WasmLispCompiler.hasDoubleLiteral(args)) {
			// Double path: f64.max is a native WASM instruction
			WasmExprCompiler.compileExpr(args.get(1), ctx);
			WasmEmitHelper.castFloatGetF64(ctx);
			WasmExprCompiler.compileExpr(args.get(2), ctx);
			WasmEmitHelper.castFloatGetF64(ctx);
			ctx.writer.write(Instruction.F64_MAX);
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
			ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_FLOAT);
		}
		else {
			// Integer path: store boxed values, use if/else
			WasmExprCompiler.compileExpr(args.get(1), ctx);
			int aSlot = ctx.allocTemp();
			ctx.writer.write(Instruction.SET_LOCAL);
			ctx.writer.writeSignedLeb128(aSlot);
			WasmExprCompiler.compileExpr(args.get(2), ctx);
			int bSlot = ctx.allocTemp();
			ctx.writer.write(Instruction.SET_LOCAL);
			ctx.writer.writeSignedLeb128(bSlot);
			// Condition: a > b
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeSignedLeb128(aSlot);
			WasmEmitHelper.castI31GetS(ctx);
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeSignedLeb128(bSlot);
			WasmEmitHelper.castI31GetS(ctx);
			ctx.writer.write(Instruction.I32_GT_S);
			// if a > b: return a
			ctx.writer.write(Instruction.IF);
			ctx.writer.write(Type.REFNULL.code());
			ctx.writer.writeHeapType(Type.EQ.code());
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeSignedLeb128(aSlot);
			// else: return b
			ctx.writer.write(Instruction.ELSE);
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeSignedLeb128(bSlot);
			ctx.writer.write(Instruction.END);
		}
	}

}
