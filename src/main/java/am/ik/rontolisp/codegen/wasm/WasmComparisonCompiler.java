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
			// _rat_cmp_bits returns the comparison as a bitmask (1 = lt, 2 = eq,
			// 4 = gt, 0 = unordered), so a NaN operand fails every operator. The old
			// signum _rat_cmp against zero answered "equal" for NaN (todo-108 group E).
			WasmExprCompiler.compileExpr(args.get(1), ctx);
			WasmExprCompiler.compileExpr(args.get(2), ctx);
			ctx.writer.write(am.ik.wasm.Instruction.CALL);
			ctx.writer.writeSignedLeb128(WasmLispCompiler.FUNC_RAT_CMP_BITS);
			ctx.writer.write(am.ik.wasm.Instruction.I32_CONST);
			ctx.writer.writeSignedLeb128(maskFor(i32Opcode));
			ctx.writer.write(am.ik.wasm.Instruction.I32_AND);
		}
		WasmEmitHelper.emitBoolFromI32(ctx);
	}

	private static int maskFor(int i32Opcode) {
		return switch (i32Opcode) {
			case am.ik.wasm.Instruction.I32_EQ -> 0b010;
			case am.ik.wasm.Instruction.I32_LT_S -> 0b001;
			case am.ik.wasm.Instruction.I32_GT_S -> 0b100;
			case am.ik.wasm.Instruction.I32_LE_S -> 0b011;
			case am.ik.wasm.Instruction.I32_GE_S -> 0b110;
			default -> throw new IllegalArgumentException("unexpected comparison opcode: " + i32Opcode);
		};
	}

}
