package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;

/**
 * Compiles the {@code exp} built-in for WASM. WASM has no native transcendental
 * instruction, so this emits a software approximation of {@code e^x} entirely in f64
 * arithmetic, always returning a float.
 *
 * <p>
 * The approximation uses argument reduction by repeated squaring -
 * {@code exp(x) = (exp(x / 2^M))^(2^M)} with {@code M = 8} - so the polynomial only has
 * to be accurate on the small reduced argument {@code t = x / 256}. {@code exp(t)} is a
 * degree-5 Taylor polynomial evaluated in Horner form, then squared {@code M} times. Over
 * the range exercised by the sigmoid in the example networks this matches
 * {@code Math.exp} to roughly 1e-6 relative error (it is not bit-identical to the
 * interpreter/JVM {@code Math.exp}, so cross-backend output for {@code exp} of a
 * non-trivial argument differs in the low-order digits).
 *
 * <p>
 * Intermediate f64 values are boxed as {@code TYPE_FLOAT} structs in ref-typed
 * temporaries because the WASM compiler only allocates {@code (ref null eq)} locals; this
 * keeps the implementation inline, with no new function or type indices.
 */
final class WasmExpCompiler {

	// Argument reduction: exp(x) = (exp(x / 2^SQUARINGS))^(2^SQUARINGS).
	private static final int SQUARINGS = 8;

	private static final double INV_SCALE = 1.0 / (1 << SQUARINGS); // 1/256

	// Taylor coefficients of exp around 0, from the highest degree down (Horner order):
	// 1/120, 1/24, 1/6, 1/2, 1, 1.
	private static final double[] HORNER_COEFFS = { 1.0 / 120.0, 1.0 / 24.0, 1.0 / 6.0, 0.5, 1.0, 1.0 };

	private WasmExpCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		if (args.size() != 2) {
			throw new UnsupportedOperationException("exp expects 1 argument, got " + (args.size() - 1));
		}
		int tSlot = ctx.allocTemp();
		int accSlot = ctx.allocTemp();

		// t = x / 256, boxed into tSlot.
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		WasmEmitHelper.castFloatGetF64(ctx);
		ctx.writer.write(Instruction.F64_CONST);
		ctx.writer.writeF64(INV_SCALE);
		ctx.writer.write(Instruction.F64_MUL);
		boxF64(ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeSignedLeb128(tSlot);

		// Horner evaluation of the Taylor polynomial: acc = (((((c0)*t + c1)*t + ...)*t.
		ctx.writer.write(Instruction.F64_CONST);
		ctx.writer.writeF64(HORNER_COEFFS[0]);
		for (int i = 1; i < HORNER_COEFFS.length; i++) {
			unboxF64Local(ctx, tSlot);
			ctx.writer.write(Instruction.F64_MUL);
			ctx.writer.write(Instruction.F64_CONST);
			ctx.writer.writeF64(HORNER_COEFFS[i]);
			ctx.writer.write(Instruction.F64_ADD);
		}
		boxF64(ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeSignedLeb128(accSlot);

		// Square the reduced result SQUARINGS times: acc = acc * acc.
		for (int i = 0; i < SQUARINGS; i++) {
			unboxF64Local(ctx, accSlot);
			unboxF64Local(ctx, accSlot);
			ctx.writer.write(Instruction.F64_MUL);
			boxF64(ctx);
			ctx.writer.write(Instruction.SET_LOCAL);
			ctx.writer.writeSignedLeb128(accSlot);
		}

		// The result is the boxed TYPE_FLOAT already in accSlot.
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(accSlot);
	}

	// Boxes the f64 on the stack into a TYPE_FLOAT struct.
	private static void boxF64(WasmLispCompiler.Ctx ctx) {
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_FLOAT);
	}

	// Loads local[slot] (a TYPE_FLOAT struct) and extracts its f64 field onto the stack.
	private static void unboxF64Local(WasmLispCompiler.Ctx ctx, int slot) {
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeSignedLeb128(slot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_FLOAT);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_FLOAT);
		ctx.writer.writeSignedLeb128(0);
	}

}
