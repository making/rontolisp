package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;

/**
 * Compiles the {@code single-float} pair, {@code %single-float-p} and
 * {@code %single-float}: the floats a binary32 holds, and the binary32 nearest a float
 * ({@code am.ik.rontolisp.Binary32}, which the interpreter runs and the JVM emits as
 * {@code d2f}/{@code f2d}). A demotion may invent any NaN, so the type test answers a NaN
 * by its own {@code f64.ne} rather than by the round trip, as {@code Double.compare} does
 * on the other two.
 */
final class WasmSingleFloatCompiler {

	private WasmSingleFloatCompiler() {
	}

	/**
	 * {@code (%single-float-p x)}: a float whose {@code f32.demote_f64} /
	 * {@code f64.promote_f32} round trip is itself, or a NaN; nil for anything else. The
	 * argument is compiled ahead of every control structure this opens, so an exit inside
	 * it branches by the depths it was compiled for.
	 */
	static void compileSingleFloatP(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		int value = ctx.allocTemp();
		ctx.writer.write(Instruction.TEE_LOCAL);
		ctx.writer.writeUnsignedLeb128(value);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_FLOAT);
		ctx.writer.write(Instruction.IF, 0x7F); // (result i32)
		int savedI64Locals = ctx.nextI64Local;
		int bits = ctx.allocI64Temp();
		WasmEmitHelper.unboxF64Local(ctx, value);
		ctx.writer.write(Instruction.I64_REINTERPRET_F64);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writeI64LocalIndex(bits);
		getF64(ctx, bits);
		ctx.writer.write(Instruction.F32_DEMOTE_F64);
		ctx.writer.write(Instruction.F64_PROMOTE_F32);
		getF64(ctx, bits);
		ctx.writer.write(Instruction.F64_EQ);
		getF64(ctx, bits);
		getF64(ctx, bits);
		ctx.writer.write(Instruction.F64_NE);
		ctx.writer.write(Instruction.I32_OR);
		ctx.nextI64Local = savedI64Locals;
		ctx.writer.write(Instruction.ELSE);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(0);
		ctx.writer.write(Instruction.END);
		WasmEmitHelper.emitBoolFromI32(ctx);
	}

	/** {@code (%single-float x)}: the binary32 nearest the float, as a float. */
	static void compileSingleFloat(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		WasmEmitHelper.castFloatGetF64(ctx);
		ctx.writer.write(Instruction.F32_DEMOTE_F64);
		ctx.writer.write(Instruction.F64_PROMOTE_F32);
		WasmEmitHelper.boxF64(ctx);
	}

	private static void getF64(WasmLispCompiler.Ctx ctx, int slot) {
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writeI64LocalIndex(slot);
		ctx.writer.write(Instruction.F64_REINTERPRET_I64);
	}

}
