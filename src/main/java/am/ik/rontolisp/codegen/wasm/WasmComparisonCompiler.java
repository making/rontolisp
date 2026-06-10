package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;

/**
 * Compiles comparison operations ({@code =}, {@code <}, {@code >}, {@code <=},
 * {@code >=}).
 */
final class WasmComparisonCompiler {

	private WasmComparisonCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx, int i32Opcode, int f64Opcode) {
		List<LispVal> args = cons.toList();
		if (WasmLispCompiler.hasDoubleLiteral(args)) {
			WasmExprCompiler.compileExpr(args.get(1), ctx);
			WasmEmitHelper.castFloatGetF64(ctx);
			WasmExprCompiler.compileExpr(args.get(2), ctx);
			WasmEmitHelper.castFloatGetF64(ctx);
			ctx.writer.write(f64Opcode);
		}
		else {
			// _rat_cmp returns -1/0/1 for any mix of integers and ratios, so the
			// original comparison opcode is applied against zero.
			WasmExprCompiler.compileExpr(args.get(1), ctx);
			WasmExprCompiler.compileExpr(args.get(2), ctx);
			ctx.writer.write(am.ik.wasm.Instruction.CALL);
			ctx.writer.writeSignedLeb128(WasmLispCompiler.FUNC_RAT_CMP);
			ctx.writer.write(am.ik.wasm.Instruction.I32_CONST);
			ctx.writer.writeSignedLeb128(0);
			ctx.writer.write(i32Opcode);
		}
		WasmEmitHelper.emitBoolFromI32(ctx);
	}

}
