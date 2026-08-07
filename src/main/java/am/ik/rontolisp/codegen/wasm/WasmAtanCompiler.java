package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;

/**
 * Compiles the {@code atan} / {@code asin} / {@code acos} built-ins for WASM. WASM has no
 * native inverse-trigonometric instruction, so this emits a software approximation
 * entirely in f64 arithmetic, always returning a float.
 *
 * <p>
 * The core is {@code atan}: the odd symmetry folds the argument to {@code t = |x|}, the
 * reciprocal identity {@code atan(t) = pi/2 - atan(1/t)} folds {@code t > 1} into
 * {@code [0, 1]} (and maps {@code +-inf} to {@code +-pi/2} with no special casing, since
 * {@code 1/inf = 0}), and TWO half-angle folds {@code atan(u) = 2*atan(u / (1 + sqrt(1 +
 * u^2)))} (cheap: {@code f64.sqrt} is native) shrink the series argument to {@code |u| <=
 * tan(pi/16) ~= 0.199}, where a 10-term Taylor series {@code u - u^3/3 + u^5/5 - ...} in
 * Horner form over {@code z = u^2} converges to ~1e-15 relative error (measured max
 * 7.1e-16 over the full range against {@code Math.atan} -- close to but not bit-identical
 * to the interpreter/JVM value, so cross-backend output for a non-trivial argument
 * differs in the last digit or two).
 *
 * <p>
 * {@code asin(x) = atan(x / sqrt((1-x)(1+x)))} (the factored radicand is more accurate
 * near {@code |x| = 1} than {@code 1 - x^2}; at {@code x = +-1} the division yields
 * {@code +-inf} and the reciprocal fold turns it into {@code +-pi/2} exactly) and
 * {@code acos(x) = 2*atan(sqrt((1-x)/(1+x)))} (NOT {@code pi/2 - asin(x)}, which loses
 * relative accuracy near {@code x = 1}; this form makes {@code (acos 1) = 0.0} exact and
 * {@code (acos -1) = pi} via {@code (1-x)/(1+x) = 2/0 = inf}). Both reject {@code |x| >
 * 1} with NaN.
 *
 * <p>
 * There is no {@code i32.trunc} anywhere, so no branch can trap: NaN flows through every
 * comparison (all false) and the arithmetic to a NaN result, and {@code +-inf} flows
 * through the reciprocal fold. Unlike the {@code signum}-class edges, {@code (atan -0.0)}
 * and {@code (asin -0.0)} PRESERVE {@code -0.0} (the sign fold's {@code x < 0} is false
 * for {@code -0.0}, so it rides the series to {@code -0.0}), matching {@code Math.atan} /
 * {@code Math.asin}. Exact anchors: {@code (atan 0) = 0.0}, {@code (asin 0) = 0.0},
 * {@code (acos 1) = 0.0}, {@code (asin 1) = pi/2}, {@code (acos -1) = pi}.
 *
 * <p>
 * Intermediate f64 values are boxed as {@code TYPE_FLOAT} structs in ref-typed
 * temporaries because the WASM compiler only allocates {@code (ref null eq)} locals; the
 * {@code --simd} unary kernels ({@code WasmVecSimdRuntimeBuilder.emitAtanFamilyF64})
 * reproduce the SAME constants and operation order on raw f64 locals, so they stay
 * bit-identical to this defun path by construction.
 */
final class WasmAtanCompiler {

	// All constants package-private for the --simd kernel mirror (see the class javadoc).

	static final double PI_OVER_2 = Math.PI / 2.0;

	/** How many half-angle folds shrink the series argument (to tan(pi/2^(2+n))). */
	static final int HALF_ANGLE_FOLDS = 2;

	// Taylor coefficients of atan(u)/u over z = u^2, from the highest degree down
	// (Horner order): -1/19, 1/17, ..., -1/3, 1.
	static final double[] ATAN_COEFFS = { -1.0 / 19.0, 1.0 / 17.0, -1.0 / 15.0, 1.0 / 13.0, -1.0 / 11.0, 1.0 / 9.0,
			-1.0 / 7.0, 1.0 / 5.0, -1.0 / 3.0, 1.0 };

	private WasmAtanCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx, String name) {
		List<LispVal> args = cons.toList();
		if (args.size() != 2) {
			throw new UnsupportedOperationException(name + " expects 1 argument, got " + (args.size() - 1));
		}
		int xSlot = ctx.allocTemp(); // x, later the (possibly transformed) atan argument
		int tSlot = ctx.allocTemp(); // t = the sign fold, kept for the reciprocal select
		int uSlot = ctx.allocTemp(); // u = the series argument after the folds
		int zSlot = ctx.allocTemp(); // z = u^2
		int rSlot = ctx.allocTemp(); // the series result / selected value

		WasmExprCompiler.compileExpr(args.get(1), ctx);
		WasmEmitHelper.castFloatGetF64(ctx);
		WasmExpCompiler.boxF64(ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(xSlot);

		switch (name) {
			case LispNames.ATAN -> emitAtanCore(ctx, xSlot, tSlot, uSlot, zSlot, rSlot);
			case LispNames.ASIN -> {
				// if (|x| > 1) -> NaN, else atan(x / sqrt((1-x)*(1+x))).
				emitDomainGuard(ctx, xSlot);
				unbox(ctx, xSlot);
				emitOneMinusAndOnePlus(ctx, xSlot);
				ctx.writer.write(Instruction.F64_MUL);
				ctx.writer.write(Instruction.F64_SQRT);
				ctx.writer.write(Instruction.F64_DIV);
				boxInto(ctx, xSlot);
				emitAtanCore(ctx, xSlot, tSlot, uSlot, zSlot, rSlot);
				ctx.writer.write(Instruction.END);
			}
			case LispNames.ACOS -> {
				// if (|x| > 1) -> NaN, else 2 * atan(sqrt((1-x)/(1+x))).
				emitDomainGuard(ctx, xSlot);
				emitOneMinusAndOnePlus(ctx, xSlot);
				ctx.writer.write(Instruction.F64_DIV);
				ctx.writer.write(Instruction.F64_SQRT);
				boxInto(ctx, xSlot);
				emitAtanCore(ctx, xSlot, tSlot, uSlot, zSlot, rSlot);
				f64Const(ctx, 2.0);
				ctx.writer.write(Instruction.F64_MUL);
				ctx.writer.write(Instruction.END);
			}
			default -> throw new IllegalArgumentException("not an atan/asin/acos operator: " + name);
		}
		WasmExpCompiler.boxF64(ctx);
	}

	// Opens "if (|x| > 1) -> NaN else ..." (the caller emits the else body + END). A NaN
	// x fails the comparison and flows through the else arm's arithmetic to NaN.
	private static void emitDomainGuard(WasmLispCompiler.Ctx ctx, int xSlot) {
		unbox(ctx, xSlot);
		ctx.writer.write(Instruction.F64_ABS);
		f64Const(ctx, 1.0);
		ctx.writer.write(Instruction.F64_GT);
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(Type.F64);
		f64Const(ctx, Double.NaN);
		ctx.writer.write(Instruction.ELSE);
	}

	// Leaves [1-x, 1+x] on the stack.
	private static void emitOneMinusAndOnePlus(WasmLispCompiler.Ctx ctx, int xSlot) {
		f64Const(ctx, 1.0);
		unbox(ctx, xSlot);
		ctx.writer.write(Instruction.F64_SUB);
		f64Const(ctx, 1.0);
		unbox(ctx, xSlot);
		ctx.writer.write(Instruction.F64_ADD);
	}

	// The atan core over the argument in xSlot; leaves the f64 result on the stack.
	private static void emitAtanCore(WasmLispCompiler.Ctx ctx, int xSlot, int tSlot, int uSlot, int zSlot, int rSlot) {
		// t = x < 0 ? 0 - x : x (the odd-symmetry fold; -0.0 passes through unnegated).
		unbox(ctx, xSlot);
		f64Const(ctx, 0.0);
		ctx.writer.write(Instruction.F64_LT);
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(Type.F64);
		f64Const(ctx, 0.0);
		unbox(ctx, xSlot);
		ctx.writer.write(Instruction.F64_SUB);
		ctx.writer.write(Instruction.ELSE);
		unbox(ctx, xSlot);
		ctx.writer.write(Instruction.END);
		boxInto(ctx, tSlot);

		// u = t > 1 ? 1/t : t (the reciprocal fold; +inf becomes 0).
		unbox(ctx, tSlot);
		f64Const(ctx, 1.0);
		ctx.writer.write(Instruction.F64_GT);
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(Type.F64);
		f64Const(ctx, 1.0);
		unbox(ctx, tSlot);
		ctx.writer.write(Instruction.F64_DIV);
		ctx.writer.write(Instruction.ELSE);
		unbox(ctx, tSlot);
		ctx.writer.write(Instruction.END);
		boxInto(ctx, uSlot);

		// The half-angle folds: u = u / (sqrt(1 + u*u) + 1).
		for (int i = 0; i < HALF_ANGLE_FOLDS; i++) {
			unbox(ctx, uSlot);
			f64Const(ctx, 1.0);
			unbox(ctx, uSlot);
			unbox(ctx, uSlot);
			ctx.writer.write(Instruction.F64_MUL);
			ctx.writer.write(Instruction.F64_ADD);
			ctx.writer.write(Instruction.F64_SQRT);
			f64Const(ctx, 1.0);
			ctx.writer.write(Instruction.F64_ADD);
			ctx.writer.write(Instruction.F64_DIV);
			boxInto(ctx, uSlot);
		}

		// z = u * u
		unbox(ctx, uSlot);
		unbox(ctx, uSlot);
		ctx.writer.write(Instruction.F64_MUL);
		boxInto(ctx, zSlot);

		// r = Horner(ATAN_COEFFS over z) * u * 4 (each half-angle fold doubles; the *4
		// is an exact power-of-two scale).
		f64Const(ctx, ATAN_COEFFS[0]);
		for (int i = 1; i < ATAN_COEFFS.length; i++) {
			unbox(ctx, zSlot);
			ctx.writer.write(Instruction.F64_MUL);
			f64Const(ctx, ATAN_COEFFS[i]);
			ctx.writer.write(Instruction.F64_ADD);
		}
		unbox(ctx, uSlot);
		ctx.writer.write(Instruction.F64_MUL);
		f64Const(ctx, 4.0);
		ctx.writer.write(Instruction.F64_MUL);
		boxInto(ctx, rSlot);

		// The reciprocal select: r = t > 1 ? pi/2 - r : r.
		unbox(ctx, tSlot);
		f64Const(ctx, 1.0);
		ctx.writer.write(Instruction.F64_GT);
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(Type.F64);
		f64Const(ctx, PI_OVER_2);
		unbox(ctx, rSlot);
		ctx.writer.write(Instruction.F64_SUB);
		ctx.writer.write(Instruction.ELSE);
		unbox(ctx, rSlot);
		ctx.writer.write(Instruction.END);
		boxInto(ctx, rSlot);

		// The sign select: x < 0 ? 0 - r : r, left on the stack.
		unbox(ctx, xSlot);
		f64Const(ctx, 0.0);
		ctx.writer.write(Instruction.F64_LT);
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(Type.F64);
		f64Const(ctx, 0.0);
		unbox(ctx, rSlot);
		ctx.writer.write(Instruction.F64_SUB);
		ctx.writer.write(Instruction.ELSE);
		unbox(ctx, rSlot);
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
