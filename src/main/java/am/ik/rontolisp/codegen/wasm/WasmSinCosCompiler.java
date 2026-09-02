package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;

/**
 * Compiles the {@code sin} / {@code cos} / {@code tan} built-ins for WASM. WASM has no
 * native trigonometric instruction, so this emits a software approximation entirely in
 * f64 arithmetic, always returning a float.
 *
 * <p>
 * The approximation uses Cody-Waite argument reduction over quadrants: {@code k =
 * nearest(x * 2/pi)}, {@code r = (x - k*PIO2_1) - k*PIO2_1T} (the fdlibm two-part split
 * of pi/2, accurate to ~86 bits), then degree-11/12 Taylor polynomials for {@code sin(r)}
 * / {@code cos(r)} on {@code |r| <= pi/4} evaluated in Horner form over {@code z = r*r},
 * with the quadrant {@code q = trunc(k) & 3} selecting the sign/swap. {@code tan}
 * computes both polynomials once and takes the ratio ({@code s/c} on even quadrants,
 * {@code -(c/s)} on odd ones).
 *
 * <p>
 * Accuracy: ~1e-11 relative for {@code |x|} up to ~1e6 (close to but not bit-identical to
 * the interpreter/JVM {@code Math.sin} family, so cross-backend output for a non-trivial
 * argument differs in the low-order digits); beyond that the {@code
 * k*PIO2_1} product rounds and the ABSOLUTE error grows like {@code |x| * 2^-53} (~6e-8
 * at {@code |x| = 2^30}). Above {@code |x| > 2^30} a crude {@code 2*pi} pre-fold plus a
 * clamp keeps the result finite and in range with no trap, but the value progressively
 * loses all significance (documented, like the {@code exp}/{@code log} low-digit
 * divergence). {@code tan} additionally amplifies the reduction error near its poles
 * ({@code cos(x)} near 0). The IEEE edges match {@code Math.sin}/{@code cos}/{@code
 * tan} except the signum-class {@code -0.0} edge: {@code NaN -> NaN}, {@code +/-inf ->
 * NaN}, but {@code (sin -0.0)} and {@code (tan -0.0)} are {@code 0.0} here where the JVM
 * keeps {@code -0.0} (the reduction's {@code -0.0 - (-0.0)} yields {@code +0.0}, the same
 * class of edge as the wasm {@code signum} and {@code tanh}). Exact anchors:
 * {@code (sin 0) = 0.0}, {@code (cos 0) = 1.0}, {@code (tan 0) = 0.0}, {@code (sin
 * (/ pi 2)) = 1.0}, {@code (cos pi) = -1.0}.
 *
 * <p>
 * Intermediate f64 values are boxed as {@code TYPE_FLOAT} structs in ref-typed
 * temporaries because the WASM compiler only allocates {@code (ref null eq)} locals; the
 * {@code --simd} unary kernels ({@code WasmVecSimdRuntimeBuilder.emitSinF64} /
 * {@code emitCosF64} / {@code emitTanF64}) reproduce the SAME constants and operation
 * order on raw f64 locals, so they stay bit-identical to this defun path by construction.
 */
final class WasmSinCosCompiler {

	// All constants package-private for the --simd kernel mirror (see the class javadoc).

	/** Above this bound the crude 2*pi pre-fold + clamp runs first. */
	static final double BIG = 0x1p30;

	static final double TWO_OVER_PI = 2.0 / Math.PI;

	static final double INV_TWO_PI = 1.0 / (2.0 * Math.PI);

	static final double TWO_PI = 2.0 * Math.PI;

	/**
	 * The first 33 bits of pi/2 (fdlibm pio2_1): {@code k * PIO2_1} is exact for small k.
	 */
	static final double PIO2_1 = Double.longBitsToDouble(0x3FF921FB54400000L);

	/** pi/2 - PIO2_1 to full f64 precision (fdlibm pio2_1t). */
	static final double PIO2_1T = Double.longBitsToDouble(0x3DD0B4611A626331L);

	// Taylor coefficients of sin(r)/r over z = r^2, from the highest degree down
	// (Horner order): -1/11!, 1/9!, -1/7!, 1/5!, -1/3!, 1.
	static final double[] SIN_COEFFS = { -1.0 / 39916800.0, 1.0 / 362880.0, -1.0 / 5040.0, 1.0 / 120.0, -1.0 / 6.0,
			1.0 };

	// Taylor coefficients of cos(r) over z = r^2, from the highest degree down
	// (Horner order): 1/12!, -1/10!, 1/8!, -1/6!, 1/4!, -1/2!, 1.
	static final double[] COS_COEFFS = { 1.0 / 479001600.0, -1.0 / 3628800.0, 1.0 / 40320.0, -1.0 / 720.0, 1.0 / 24.0,
			-0.5, 1.0 };

	private WasmSinCosCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx, String name) {
		List<LispVal> args = cons.toList();
		if (args.size() != 2) {
			throw new UnsupportedOperationException(name + " expects 1 argument, got " + (args.size() - 1));
		}
		int xSlot = ctx.allocTemp(); // x, later reused for r
		int kSlot = ctx.allocTemp(); // k, later reused for the quadrant q
		int zSlot = ctx.allocTemp(); // z = r^2
		int sSlot = ctx.allocTemp(); // the sin polynomial
		int cSlot = ctx.allocTemp(); // the cos polynomial

		WasmExprCompiler.compileExpr(args.get(1), ctx);
		WasmEmitHelper.castFloatGetF64(ctx);
		WasmExpCompiler.boxF64(ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(xSlot);

		// The IEEE edges, then the finite main path.
		// if (x != x) -> NaN
		unbox(ctx, xSlot);
		unbox(ctx, xSlot);
		ctx.writer.write(Instruction.F64_NE);
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(Type.F64);
		unbox(ctx, xSlot);
		ctx.writer.write(Instruction.ELSE);
		// if (|x| == +inf) -> NaN
		unbox(ctx, xSlot);
		ctx.writer.write(Instruction.F64_ABS);
		f64Const(ctx, Double.POSITIVE_INFINITY);
		ctx.writer.write(Instruction.F64_EQ);
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(Type.F64);
		f64Const(ctx, Double.NaN);
		ctx.writer.write(Instruction.ELSE);
		// sin and tan are ODD, so sin(+/-0.0) and tan(+/-0.0) are the argument itself,
		// sign of the zero included. The Cody-Waite reduction below cannot produce that:
		// x = -0.0 gives k = -0.0, so k*PIO2_1 is -0.0 and the reduction's
		// -0.0 - (-0.0) cancels to +0.0, flattening the sign before the polynomial ever
		// runs. Answering the argument directly is also exact, and it is what Math.sin /
		// Math.tan give the interpreter and the JVM backend. cos is EVEN -- cos(+/-0.0)
		// is 1.0 -- and the main path already answers that, so it takes no zero rung.
		boolean odd = LispNames.SIN.equals(name) || LispNames.TAN.equals(name);
		if (odd) {
			unbox(ctx, xSlot);
			f64Const(ctx, 0.0);
			ctx.writer.write(Instruction.F64_EQ);
			ctx.writer.write(Instruction.IF);
			ctx.writer.write(Type.F64);
			unbox(ctx, xSlot);
			ctx.writer.write(Instruction.ELSE);
		}
		emitMain(ctx, name, xSlot, kSlot, zSlot, sSlot, cSlot);
		if (odd) {
			ctx.writer.write(Instruction.END);
		}
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.END);
		WasmExpCompiler.boxF64(ctx);
	}

	// The finite path; leaves the f64 result on the stack.
	private static void emitMain(WasmLispCompiler.Ctx ctx, String name, int xSlot, int kSlot, int zSlot, int sSlot,
			int cSlot) {
		// if (|x| > BIG) { x -= nearest(x / 2pi) * 2pi; x = clamp(x, -BIG, BIG) }
		unbox(ctx, xSlot);
		ctx.writer.write(Instruction.F64_ABS);
		f64Const(ctx, BIG);
		ctx.writer.write(Instruction.F64_GT);
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(0x40);
		unbox(ctx, xSlot);
		unbox(ctx, xSlot);
		f64Const(ctx, INV_TWO_PI);
		ctx.writer.write(Instruction.F64_MUL);
		ctx.writer.write(Instruction.F64_NEAREST);
		f64Const(ctx, TWO_PI);
		ctx.writer.write(Instruction.F64_MUL);
		ctx.writer.write(Instruction.F64_SUB);
		f64Const(ctx, -BIG);
		ctx.writer.write(Instruction.F64_MAX);
		f64Const(ctx, BIG);
		ctx.writer.write(Instruction.F64_MIN);
		boxInto(ctx, xSlot);
		ctx.writer.write(Instruction.END);

		// k = nearest(x * 2/pi)
		unbox(ctx, xSlot);
		f64Const(ctx, TWO_OVER_PI);
		ctx.writer.write(Instruction.F64_MUL);
		ctx.writer.write(Instruction.F64_NEAREST);
		boxInto(ctx, kSlot);

		// r = (x - k*PIO2_1) - k*PIO2_1T, reusing xSlot.
		unbox(ctx, xSlot);
		unbox(ctx, kSlot);
		f64Const(ctx, PIO2_1);
		ctx.writer.write(Instruction.F64_MUL);
		ctx.writer.write(Instruction.F64_SUB);
		unbox(ctx, kSlot);
		f64Const(ctx, PIO2_1T);
		ctx.writer.write(Instruction.F64_MUL);
		ctx.writer.write(Instruction.F64_SUB);
		boxInto(ctx, xSlot);

		// z = r * r
		unbox(ctx, xSlot);
		unbox(ctx, xSlot);
		ctx.writer.write(Instruction.F64_MUL);
		boxInto(ctx, zSlot);

		// s = Horner(SIN_COEFFS over z) * r
		f64Const(ctx, SIN_COEFFS[0]);
		for (int i = 1; i < SIN_COEFFS.length; i++) {
			unbox(ctx, zSlot);
			ctx.writer.write(Instruction.F64_MUL);
			f64Const(ctx, SIN_COEFFS[i]);
			ctx.writer.write(Instruction.F64_ADD);
		}
		unbox(ctx, xSlot);
		ctx.writer.write(Instruction.F64_MUL);
		boxInto(ctx, sSlot);

		// c = Horner(COS_COEFFS over z)
		f64Const(ctx, COS_COEFFS[0]);
		for (int i = 1; i < COS_COEFFS.length; i++) {
			unbox(ctx, zSlot);
			ctx.writer.write(Instruction.F64_MUL);
			f64Const(ctx, COS_COEFFS[i]);
			ctx.writer.write(Instruction.F64_ADD);
		}
		boxInto(ctx, cSlot);

		// q = (f64)(trunc(k) & 3), reusing kSlot (two's-complement & keeps negative k
		// in 0..3). |k| <= BIG * 2/pi < 2^31, so the i32 truncation cannot trap.
		unbox(ctx, kSlot);
		ctx.writer.write(Instruction.I32_TRUNC_S_F64);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(3);
		ctx.writer.write(Instruction.I32_AND);
		ctx.writer.write(Instruction.F64_CONVERT_S_I32);
		boxInto(ctx, kSlot);

		// The quadrant selection.
		switch (name) {
			case LispNames.SIN -> emitQuadrantSelect(ctx, kSlot, sSlot, cSlot, false);
			case LispNames.COS -> emitQuadrantSelect(ctx, kSlot, cSlot, sSlot, true);
			case LispNames.TAN -> emitTanSelect(ctx, kSlot, sSlot, cSlot);
			default -> throw new IllegalArgumentException("not a sin/cos/tan operator: " + name);
		}
	}

	// sin: q=0 -> s, q=1 -> c, q=2 -> -s, q=3 -> -c (primary = s, secondary = c).
	// cos: q=0 -> c, q=1 -> -s, q=2 -> -c, q=3 -> s (primary = c, secondary = s,
	// negateOdd flips which of q=1/q=3 negates).
	private static void emitQuadrantSelect(WasmLispCompiler.Ctx ctx, int qSlot, int primarySlot, int secondarySlot,
			boolean negateOdd) {
		unbox(ctx, qSlot);
		f64Const(ctx, 0.0);
		ctx.writer.write(Instruction.F64_EQ);
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(Type.F64);
		unbox(ctx, primarySlot);
		ctx.writer.write(Instruction.ELSE);
		unbox(ctx, qSlot);
		f64Const(ctx, 1.0);
		ctx.writer.write(Instruction.F64_EQ);
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(Type.F64);
		unbox(ctx, secondarySlot);
		if (negateOdd) {
			ctx.writer.write(Instruction.F64_NEG);
		}
		ctx.writer.write(Instruction.ELSE);
		unbox(ctx, qSlot);
		f64Const(ctx, 2.0);
		ctx.writer.write(Instruction.F64_EQ);
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(Type.F64);
		unbox(ctx, primarySlot);
		ctx.writer.write(Instruction.F64_NEG);
		ctx.writer.write(Instruction.ELSE);
		unbox(ctx, secondarySlot);
		if (!negateOdd) {
			ctx.writer.write(Instruction.F64_NEG);
		}
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.END);
	}

	// tan: even quadrants -> s/c, odd quadrants -> -(c/s) (the sign flips cancel).
	private static void emitTanSelect(WasmLispCompiler.Ctx ctx, int qSlot, int sSlot, int cSlot) {
		unbox(ctx, qSlot);
		f64Const(ctx, 1.0);
		ctx.writer.write(Instruction.F64_EQ);
		unbox(ctx, qSlot);
		f64Const(ctx, 3.0);
		ctx.writer.write(Instruction.F64_EQ);
		ctx.writer.write(Instruction.I32_OR);
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(Type.F64);
		unbox(ctx, cSlot);
		unbox(ctx, sSlot);
		ctx.writer.write(Instruction.F64_DIV);
		ctx.writer.write(Instruction.F64_NEG);
		ctx.writer.write(Instruction.ELSE);
		unbox(ctx, sSlot);
		unbox(ctx, cSlot);
		ctx.writer.write(Instruction.F64_DIV);
		ctx.writer.write(Instruction.END);
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
		ctx.writer.writeUnsignedLeb128(slot);
	}

}
