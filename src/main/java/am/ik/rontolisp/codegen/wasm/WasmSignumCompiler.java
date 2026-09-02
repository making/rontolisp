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

	/** Emits {@code (f64 of the float in slot) <cmp> 0.0} as an i32 truth value. */
	private static void floatCompareWithZero(WasmLispCompiler.Ctx ctx, int slot, int cmp) {
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
		WasmEmitHelper.castFloatGetF64(ctx);
		ctx.writer.write(Instruction.F64_CONST);
		ctx.writer.writeF64(0.0);
		ctx.writer.write(cmp);
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		int slot = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);

		// Branch on whether the argument is a float struct or an integer/ratio.
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_FLOAT);
		ctx.writer.write(Instruction.IF);
		ctx.writer.writeRefType(true, Type.EQ.code());

		// Float path: (x > 0.0) - (x < 0.0) converted to f64 and boxed -- but ONLY once
		// x is known to be nonzero and ordered. Both comparisons are false for +0.0,
		// -0.0 and NaN alike, so that subtraction alone would answer +0.0 for all three,
		// flattening the sign of a negative zero and swallowing a NaN. Guarding on
		// (x > 0 || x < 0) and otherwise handing back the ARGUMENT keeps -0.0 as -0.0
		// and NaN as NaN, which is what Math.signum gives the interpreter and the JVM
		// backend, and what upstream Common Lisp answers for the two zeros.
		floatCompareWithZero(ctx, slot, Instruction.F64_GT);
		floatCompareWithZero(ctx, slot, Instruction.F64_LT);
		ctx.writer.write(Instruction.I32_OR);
		ctx.writer.write(Instruction.IF);
		ctx.writer.writeRefType(true, Type.EQ.code());

		floatCompareWithZero(ctx, slot, Instruction.F64_GT);
		floatCompareWithZero(ctx, slot, Instruction.F64_LT);
		ctx.writer.write(Instruction.I32_SUB);
		ctx.writer.write(Instruction.F64_CONVERT_S_I32);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_FLOAT);

		ctx.writer.write(Instruction.ELSE);
		// Zero or NaN: the argument itself, unboxed and reboxed by nobody.
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
		ctx.writer.write(Instruction.END);

		ctx.writer.write(Instruction.ELSE);

		// Integer/ratio path: _rat_cmp(x, 0) already answers -1/0/1 and dispatches on
		// the exact-integer (i31 or boxed i64) and ratio representations alike, where
		// the old _rat_num shape would wrap a boxed integer's sign.
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
		WasmMathHelper.constI32(ctx, 0);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_RAT_CMP);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);

		ctx.writer.write(Instruction.END);
	}

}
