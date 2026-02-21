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
			WasmExprCompiler.compileExpr(args.get(1), ctx);
			WasmEmitHelper.castI31GetS(ctx);
			WasmExprCompiler.compileExpr(args.get(2), ctx);
			WasmEmitHelper.castI31GetS(ctx);
			ctx.writer.write(i32Opcode);
		}
		WasmEmitHelper.emitBoolFromI32(ctx);
	}

}
