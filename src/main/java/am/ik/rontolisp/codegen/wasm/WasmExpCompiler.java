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

	// Argument reduction: exp(x) = (exp(x / 2^SQUARINGS))^(2^SQUARINGS). Package-private
	// so the --simd unary kernels (WasmVecSimdRuntimeBuilder) reproduce the SAME
	// approximation on raw f64 locals -- bit-identity to this defun path by shared
	// constants and operation order.
	static final int SQUARINGS = 8;

	static final double INV_SCALE = 1.0 / (1 << SQUARINGS); // 1/256

	// Taylor coefficients of exp around 0, from the highest degree down (Horner order):
	// 1/120, 1/24, 1/6, 1/2, 1, 1.
	static final double[] HORNER_COEFFS = { 1.0 / 120.0, 1.0 / 24.0, 1.0 / 6.0, 0.5, 1.0, 1.0 };

	/**
	 * The underflow clamp {@code f64.max(p(t), 0.0)} applied to the reduced polynomial
	 * before the squarings. The odd-degree Taylor polynomial {@code p} has one real root
	 * (around {@code t = -2.18}, i.e. {@code x = -558}), and below it {@code p(t)} is
	 * NEGATIVE - so the even number of squarings turned every sufficiently negative
	 * argument into a huge POSITIVE value ({@code (exp -1000)} was {@code 2.4e125}, and
	 * {@code (exp -inf)} was {@code +inf}, because {@code p(-inf) = -inf}). Clamping the
	 * polynomial at zero maps that whole region to {@code 0.0} - what {@code Math.exp}
	 * returns there to within {@code 1e-217} - and is a no-op wherever {@code p(t) >= 0},
	 * so every value the approximation already got right stays BIT-IDENTICAL. NaN
	 * propagates ({@code f64.max} returns NaN if either operand is NaN) and {@code +inf}
	 * is unaffected. This is what lets a {@code -infinity} attention mask reach
	 * {@code linalg:softmax} as a weight of exactly {@code 0.0} on every backend.
	 */
	static final double UNDERFLOW_CLAMP = 0.0;

	private WasmExpCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		if (args.size() != 2) {
			throw new UnsupportedOperationException("exp expects 1 argument, got " + (args.size() - 1));
		}
		int tSlot = ctx.allocTemp();
		int accSlot = ctx.allocTemp();
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		WasmEmitHelper.castFloatGetF64(ctx);
		emitExpCore(ctx, tSlot, accSlot);
	}

	/**
	 * Consumes an f64 {@code x} on the stack and leaves the boxed {@code TYPE_FLOAT}
	 * {@code exp(x)}. Package-private so {@link WasmTanhCompiler} derives {@code tanh}
	 * from the same approximation (the same arithmetic order the {@code --simd} kernels
	 * mirror on raw f64 locals via {@code WasmVecSimdRuntimeBuilder.emitExpF64}).
	 */
	static void emitExpCore(WasmLispCompiler.Ctx ctx, int tSlot, int accSlot) {
		// t = x / 256, boxed into tSlot.
		ctx.writer.write(Instruction.F64_CONST);
		ctx.writer.writeF64(INV_SCALE);
		ctx.writer.write(Instruction.F64_MUL);
		boxF64(ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(tSlot);

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
		// The underflow clamp: see UNDERFLOW_CLAMP.
		ctx.writer.write(Instruction.F64_CONST);
		ctx.writer.writeF64(UNDERFLOW_CLAMP);
		ctx.writer.write(Instruction.F64_MAX);
		boxF64(ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(accSlot);

		// Square the reduced result SQUARINGS times: acc = acc * acc.
		for (int i = 0; i < SQUARINGS; i++) {
			unboxF64Local(ctx, accSlot);
			unboxF64Local(ctx, accSlot);
			ctx.writer.write(Instruction.F64_MUL);
			boxF64(ctx);
			ctx.writer.write(Instruction.SET_LOCAL);
			ctx.writer.writeUnsignedLeb128(accSlot);
		}

		// The result is the boxed TYPE_FLOAT already in accSlot.
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(accSlot);
	}

	// Boxes the f64 on the stack into a TYPE_FLOAT struct. Package-private for
	// WasmTanhCompiler / WasmLogCompiler, which build on the same boxed-temp idiom.
	static void boxF64(WasmLispCompiler.Ctx ctx) {
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_FLOAT);
	}

	// Loads local[slot] (a TYPE_FLOAT struct) and extracts its f64 field onto the stack.
	// Package-private for WasmTanhCompiler / WasmLogCompiler.
	static void unboxF64Local(WasmLispCompiler.Ctx ctx, int slot) {
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_FLOAT);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_FLOAT);
		ctx.writer.writeUnsignedLeb128(0);
	}

}
