package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;

/**
 * Compiles the {@code log} (natural logarithm) built-in for WASM. WASM has no native
 * transcendental instruction, so this emits a software approximation entirely in f64
 * arithmetic, always returning a float.
 *
 * <p>
 * The approximation uses the standard exponent/mantissa argument reduction: {@code x = m
 * * 2^e} with the mantissa normalized into {@code (sqrt(2)/2, sqrt(2)]} (so the series
 * argument is centered on 1 and {@code ln(x)} near 1 suffers no cancellation), giving
 * {@code ln(x) = e*ln(2) + ln(m)}. {@code ln(m)} is the atanh series {@code 2s*(1 + u/3 +
 * u^2/5 + ... + u^5/11)} with {@code s = (m-1)/(m+1)}, {@code u = s^2} (|s| &le; 0.172),
 * evaluated in Horner form -- relative error around 1e-10, i.e. close to but not
 * bit-identical to the interpreter/JVM {@code Math.log}, so cross-backend output for
 * {@code log} of a non-trivial argument differs in the low-order digits ({@code (log 1)}
 * is exactly {@code 0.0}). Denormal inputs are pre-scaled by {@code 2^54}; the IEEE edges
 * match {@code Math.log}: {@code log(NaN) = NaN}, {@code log(±0.0) = -inf}, {@code log(x
 * < 0) = NaN}, {@code log(+inf) = +inf}.
 *
 * <p>
 * Intermediate f64 values are boxed as {@code TYPE_FLOAT} structs in ref-typed
 * temporaries because the WASM compiler only allocates {@code (ref null eq)} locals; the
 * {@code --simd} unary kernels ({@code WasmVecSimdRuntimeBuilder.emitLogF64}) reproduce
 * the SAME constants and operation order on raw f64 locals, so they stay bit-identical to
 * this defun path by construction.
 */
final class WasmLogCompiler {

	// All constants package-private for the --simd kernel mirror (see the class javadoc).

	/** Inputs below this (positive) bound are denormal and pre-scaled by 2^54. */
	static final double MIN_NORMAL = Double.MIN_NORMAL;

	static final double SCALE_UP = 0x1p54;

	/** The exponent correction the 2^54 pre-scale introduces. */
	static final double SCALE_E = -54.0;

	/** The f64 mantissa bits. */
	static final long MANT_MASK = 0x000F_FFFF_FFFF_FFFFL;

	/**
	 * The f64 bit pattern of 1.0: OR-ing it onto masked mantissa bits yields m in [1,2).
	 */
	static final long ONE_BITS = 0x3FF0_0000_0000_0000L;

	static final double SQRT2 = Math.sqrt(2.0);

	static final double LN2 = 0.6931471805599453;

	// atanh series coefficients over u = s^2, from the highest degree down (Horner
	// order): 1/11, 1/9, 1/7, 1/5, 1/3, 1.
	static final double[] HORNER_COEFFS = { 1.0 / 11.0, 1.0 / 9.0, 1.0 / 7.0, 1.0 / 5.0, 1.0 / 3.0, 1.0 };

	private WasmLogCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		if (args.size() != 2) {
			throw new UnsupportedOperationException("log expects 1 argument, got " + (args.size() - 1));
		}
		int xSlot = ctx.allocTemp(); // x, later reused for s
		int mSlot = ctx.allocTemp(); // m, later reused for u = s^2
		int eSlot = ctx.allocTemp(); // the accumulated exponent, as f64

		WasmExprCompiler.compileExpr(args.get(1), ctx);
		WasmEmitHelper.castFloatGetF64(ctx);
		WasmExpCompiler.boxF64(ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeSignedLeb128(xSlot);

		// The IEEE edges, then the finite positive main path.
		// if (x != x) -> NaN
		unbox(ctx, xSlot);
		unbox(ctx, xSlot);
		ctx.writer.write(Instruction.F64_NE);
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(Type.F64);
		unbox(ctx, xSlot);
		ctx.writer.write(Instruction.ELSE);
		// if (x == 0.0) -> -inf (covers -0.0 too)
		unbox(ctx, xSlot);
		f64Const(ctx, 0.0);
		ctx.writer.write(Instruction.F64_EQ);
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(Type.F64);
		f64Const(ctx, Double.NEGATIVE_INFINITY);
		ctx.writer.write(Instruction.ELSE);
		// if (x < 0.0) -> NaN (covers -inf)
		unbox(ctx, xSlot);
		f64Const(ctx, 0.0);
		ctx.writer.write(Instruction.F64_LT);
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(Type.F64);
		f64Const(ctx, Double.NaN);
		ctx.writer.write(Instruction.ELSE);
		// if (x == +inf) -> +inf
		unbox(ctx, xSlot);
		f64Const(ctx, Double.POSITIVE_INFINITY);
		ctx.writer.write(Instruction.F64_EQ);
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(Type.F64);
		f64Const(ctx, Double.POSITIVE_INFINITY);
		ctx.writer.write(Instruction.ELSE);
		emitMain(ctx, xSlot, mSlot, eSlot);
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.END);
		WasmExpCompiler.boxF64(ctx);
	}

	// The finite positive path; leaves the f64 result on the stack.
	private static void emitMain(WasmLispCompiler.Ctx ctx, int xSlot, int mSlot, int eSlot) {
		// e = 0.0; if (x < MIN_NORMAL) { x *= 2^54; e = -54.0 }
		f64Const(ctx, 0.0);
		WasmExpCompiler.boxF64(ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeSignedLeb128(eSlot);
		unbox(ctx, xSlot);
		f64Const(ctx, MIN_NORMAL);
		ctx.writer.write(Instruction.F64_LT);
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(0x40);
		unbox(ctx, xSlot);
		f64Const(ctx, SCALE_UP);
		ctx.writer.write(Instruction.F64_MUL);
		boxInto(ctx, xSlot);
		f64Const(ctx, SCALE_E);
		boxInto(ctx, eSlot);
		ctx.writer.write(Instruction.END);

		// e += (f64)((bits(x) >> 52) - 1023) -- x > 0, so the sign bit is clear and the
		// logical shift yields the biased exponent directly.
		unbox(ctx, eSlot);
		unbox(ctx, xSlot);
		ctx.writer.write(Instruction.I64_REINTERPRET_F64);
		ctx.writer.write(Instruction.I64_CONST);
		ctx.writer.writeSignedLeb128(52);
		ctx.writer.write(Instruction.I64_SHR_U);
		ctx.writer.write(Instruction.I64_CONST);
		ctx.writer.writeSignedLeb128(1023);
		ctx.writer.write(Instruction.I64_SUB);
		ctx.writer.write(Instruction.F64_CONVERT_S_I64);
		ctx.writer.write(Instruction.F64_ADD);
		boxInto(ctx, eSlot);

		// m = reinterpret((bits(x) & MANT_MASK) | ONE_BITS), in [1, 2).
		unbox(ctx, xSlot);
		ctx.writer.write(Instruction.I64_REINTERPRET_F64);
		ctx.writer.write(Instruction.I64_CONST);
		ctx.writer.writeSignedLeb128(MANT_MASK);
		ctx.writer.write(Instruction.I64_AND);
		ctx.writer.write(Instruction.I64_CONST);
		ctx.writer.writeSignedLeb128(ONE_BITS);
		ctx.writer.write(Instruction.I64_OR);
		ctx.writer.write(Instruction.F64_REINTERPRET_I64);
		boxInto(ctx, mSlot);

		// if (m > sqrt(2)) { m *= 0.5; e += 1 } -- centers m on 1.
		unbox(ctx, mSlot);
		f64Const(ctx, SQRT2);
		ctx.writer.write(Instruction.F64_GT);
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(0x40);
		unbox(ctx, mSlot);
		f64Const(ctx, 0.5);
		ctx.writer.write(Instruction.F64_MUL);
		boxInto(ctx, mSlot);
		unbox(ctx, eSlot);
		f64Const(ctx, 1.0);
		ctx.writer.write(Instruction.F64_ADD);
		boxInto(ctx, eSlot);
		ctx.writer.write(Instruction.END);

		// s = (m - 1) / (m + 1), reusing xSlot; u = s^2, reusing mSlot.
		unbox(ctx, mSlot);
		f64Const(ctx, 1.0);
		ctx.writer.write(Instruction.F64_SUB);
		unbox(ctx, mSlot);
		f64Const(ctx, 1.0);
		ctx.writer.write(Instruction.F64_ADD);
		ctx.writer.write(Instruction.F64_DIV);
		boxInto(ctx, xSlot);
		unbox(ctx, xSlot);
		unbox(ctx, xSlot);
		ctx.writer.write(Instruction.F64_MUL);
		boxInto(ctx, mSlot);

		// Horner over u, then ln(m) = (poly * s) * 2, then + e*ln2.
		f64Const(ctx, HORNER_COEFFS[0]);
		for (int i = 1; i < HORNER_COEFFS.length; i++) {
			unbox(ctx, mSlot);
			ctx.writer.write(Instruction.F64_MUL);
			f64Const(ctx, HORNER_COEFFS[i]);
			ctx.writer.write(Instruction.F64_ADD);
		}
		unbox(ctx, xSlot);
		ctx.writer.write(Instruction.F64_MUL);
		f64Const(ctx, 2.0);
		ctx.writer.write(Instruction.F64_MUL);
		unbox(ctx, eSlot);
		f64Const(ctx, LN2);
		ctx.writer.write(Instruction.F64_MUL);
		ctx.writer.write(Instruction.F64_ADD);
	}

	private static void f64Const(WasmLispCompiler.Ctx ctx, double value) {
		ctx.writer.write(Instruction.F64_CONST);
		ctx.writer.writeF64(value);
	}

	private static void unbox(WasmLispCompiler.Ctx ctx, int slot) {
		WasmExpCompiler.unboxF64Local(ctx, slot);
	}

	// Boxes the f64 on the stack and stores it into the given slot.
	private static void boxInto(WasmLispCompiler.Ctx ctx, int slot) {
		WasmExpCompiler.boxF64(ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeSignedLeb128(slot);
	}

}
