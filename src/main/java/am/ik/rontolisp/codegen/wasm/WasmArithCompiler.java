package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;

/**
 * Compiles arithmetic operations ({@code +}, {@code -}, {@code *}, {@code /},
 * {@code mod}).
 */
final class WasmArithCompiler {

	private WasmArithCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx, int i32Opcode, int f64Opcode) {
		List<LispVal> args = cons.toList();
		if (WasmLispCompiler.hasDoubleLiteral(args)) {
			WasmExprCompiler.compileExpr(args.get(1), ctx);
			WasmEmitHelper.castFloatGetF64(ctx);
			for (int i = 2; i < args.size(); i++) {
				WasmExprCompiler.compileExpr(args.get(i), ctx);
				WasmEmitHelper.castFloatGetF64(ctx);
				ctx.writer.write(f64Opcode);
			}
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
			ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_FLOAT);
		}
		else {
			WasmExprCompiler.compileExpr(args.get(1), ctx);
			WasmEmitHelper.castI31GetS(ctx);
			for (int i = 2; i < args.size(); i++) {
				WasmExprCompiler.compileExpr(args.get(i), ctx);
				WasmEmitHelper.castI31GetS(ctx);
				ctx.writer.write(i32Opcode);
			}
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		}
	}

}
