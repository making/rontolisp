package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;

/**
 * Compiles arithmetic operations ({@code +}, {@code -}, {@code *}, {@code /},
 * {@code mod}). The integer path of {@code + - * /} folds through the rational runtime
 * helpers, which keep an i31 fast path and fall back to exact ratio arithmetic;
 * {@code mod} stays a plain i32 operation.
 */
final class WasmArithCompiler {

	private WasmArithCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx, int i32Opcode, int f64Opcode, int ratioFunc) {
		List<LispVal> args = cons.toList();
		if (WasmLispCompiler.hasDoubleLiteral(args)) {
			// Unary (/ x) is the reciprocal: 1.0 / x.
			if (args.size() == 2 && ratioFunc == WasmLispCompiler.FUNC_RAT_DIV) {
				ctx.writer.write(Instruction.F64_CONST);
				ctx.writer.writeF64(1.0);
				WasmExprCompiler.compileExpr(args.get(1), ctx);
				WasmEmitHelper.castFloatGetF64(ctx);
				ctx.writer.write(f64Opcode);
				ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
				ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_FLOAT);
				return;
			}
			WasmExprCompiler.compileExpr(args.get(1), ctx);
			WasmEmitHelper.castFloatGetF64(ctx);
			for (int i = 2; i < args.size(); i++) {
				WasmExprCompiler.compileExpr(args.get(i), ctx);
				WasmEmitHelper.castFloatGetF64(ctx);
				ctx.writer.write(f64Opcode);
			}
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
			ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_FLOAT);
			return;
		}
		if (ratioFunc < 0) {
			// mod: plain i32 path (a ratio operand traps on the i31 cast).
			WasmExprCompiler.compileExpr(args.get(1), ctx);
			WasmEmitHelper.castI31GetS(ctx);
			for (int i = 2; i < args.size(); i++) {
				WasmExprCompiler.compileExpr(args.get(i), ctx);
				WasmEmitHelper.castI31GetS(ctx);
				ctx.writer.write(i32Opcode);
			}
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
			return;
		}
		// Common Lisp unary forms: (- x) negates, (/ x) is the reciprocal.
		if (args.size() == 2
				&& (ratioFunc == WasmLispCompiler.FUNC_RAT_SUB || ratioFunc == WasmLispCompiler.FUNC_RAT_DIV)) {
			ctx.writer.write(Instruction.I32_CONST);
			ctx.writer.writeSignedLeb128(ratioFunc == WasmLispCompiler.FUNC_RAT_SUB ? 0 : 1);
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
			WasmExprCompiler.compileExpr(args.get(1), ctx);
			ctx.writer.write(Instruction.CALL);
			ctx.writer.writeSignedLeb128(ratioFunc);
			return;
		}
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		for (int i = 2; i < args.size(); i++) {
			WasmExprCompiler.compileExpr(args.get(i), ctx);
			ctx.writer.write(Instruction.CALL);
			ctx.writer.writeSignedLeb128(ratioFunc);
		}
	}

}
