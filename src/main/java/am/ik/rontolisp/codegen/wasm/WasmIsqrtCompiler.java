package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;

/**
 * Compiles the {@code isqrt} built-in: the integer square root (floor of the real square
 * root). Computed as {@code trunc(floor(sqrt((f64) x)))}; for the i31 integer range the
 * f64 result is exact, so no integer-domain correction is needed.
 */
final class WasmIsqrtCompiler {

	private WasmIsqrtCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		WasmEmitHelper.castFloatGetF64(ctx);
		ctx.writer.write(Instruction.F64_SQRT);
		ctx.writer.write(Instruction.F64_FLOOR);
		ctx.writer.write(Instruction.I32_TRUNC_S_F64);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
	}

}
