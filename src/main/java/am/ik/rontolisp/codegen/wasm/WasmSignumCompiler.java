package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;

/**
 * Compiles the {@code signum} built-in: the sign of a number as -1/0/1. The type is
 * preserved at runtime -- an integer or ratio argument yields an integer (the sign of a
 * ratio is the sign of its numerator) and a float argument yields a float (-1.0/0.0/1.0)
 * -- by testing whether the value is a float struct.
 */
final class WasmSignumCompiler {

	private WasmSignumCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		int slot = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeSignedLeb128(slot);

		// Branch on whether the argument is a float struct or an integer/ratio.
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(slot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_FLOAT);
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(Type.REFNULL.code());
		ctx.writer.writeHeapType(Type.EQ.code());

		// Float path: (x > 0.0) - (x < 0.0) converted to f64 and boxed.
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(slot);
		WasmEmitHelper.castFloatGetF64(ctx);
		ctx.writer.write(Instruction.F64_CONST);
		ctx.writer.writeF64(0.0);
		ctx.writer.write(Instruction.F64_GT);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(slot);
		WasmEmitHelper.castFloatGetF64(ctx);
		ctx.writer.write(Instruction.F64_CONST);
		ctx.writer.writeF64(0.0);
		ctx.writer.write(Instruction.F64_LT);
		ctx.writer.write(Instruction.I32_SUB);
		ctx.writer.write(Instruction.F64_CONVERT_S_I32);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_FLOAT);

		ctx.writer.write(Instruction.ELSE);

		// Integer/ratio path: (num > 0) - (num < 0) as an i31ref (_rat_num is the
		// value itself for an i31 integer).
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(slot);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.FUNC_RAT_NUM);
		WasmMathHelper.constI32(ctx, 0);
		ctx.writer.write(Instruction.I32_GT_S);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(slot);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.FUNC_RAT_NUM);
		WasmMathHelper.constI32(ctx, 0);
		ctx.writer.write(Instruction.I32_LT_S);
		ctx.writer.write(Instruction.I32_SUB);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);

		ctx.writer.write(Instruction.END);
	}

}
