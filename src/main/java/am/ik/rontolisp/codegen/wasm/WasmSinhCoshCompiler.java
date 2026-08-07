package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;

/**
 * Compiles the {@code sinh} / {@code cosh} built-ins for WASM. WASM has no native
 * hyperbolic instruction, so this derives both from the {@link WasmExpCompiler} software
 * {@code exp} approximation -- {@code e = exp(|x|)}, then {@code sinh = sign(x) * (e -
 * 1/e) / 2} and {@code cosh = (e + 1/e) / 2} -- always returning a float. Working on
 * {@code |x|} keeps the {@code exp} polynomial on its accurate non-negative side and one
 * evaluation covers both exponentials.
 *
 * <p>
 * The {@code e - 1/e} subtraction cancels catastrophically for tiny {@code x} (the same
 * class as {@code tanh}'s small-x edge, but unbounded: ~5e-14 ABSOLUTE error from the
 * squaring chain swamps a 1e-12-sized result). So for {@code |x| <= SMALL} {@code sinh}
 * switches to its odd Taylor series {@code x * (1 + z/6 + z^2/120 + z^3/5040 +
 * z^4/362880)} over {@code z = x^2}, ~2e-14 relative (and, applied to {@code x} directly,
 * it PRESERVES {@code -0.0}, matching {@code Math.sinh}). {@code cosh} adds instead of
 * subtracting, so it needs no small branch.
 *
 * <p>
 * Accuracy elsewhere follows the software {@code exp} itself: ~1e-7 relative for
 * {@code |x|} up to ~20, degrading as {@code |x|} grows (the {@code exp} Taylor argument
 * {@code x/256} leaves its accurate range; ~1e-3 at {@code |x| = 100}, documented like
 * the {@code exp}/{@code log} low-digit divergence), and overflowing to {@code inf} near
 * {@code |x| ~ 755} rather than the true 710.5. Edges: NaN -> NaN; {@code (sinh
 * +-inf) = +-inf}, {@code (cosh +-inf) = +inf} (checked BEFORE the exponential --
 * {@code exp(-inf)}'s Horner polynomial would yield {@code +inf}, not 0). Exact anchors:
 * {@code (sinh 0) = 0.0}, {@code (cosh 0) = 1.0} (the exp core is exactly 1.0 at 0).
 *
 * <p>
 * Intermediate f64 values are boxed as {@code TYPE_FLOAT} structs in ref-typed
 * temporaries because the WASM compiler only allocates {@code (ref null eq)} locals; the
 * {@code --simd} unary kernels ({@code WasmVecSimdRuntimeBuilder.emitSinhCoshF64})
 * reproduce the SAME constants and operation order on raw f64 locals, so they stay
 * bit-identical to this defun path by construction.
 */
final class WasmSinhCoshCompiler {

	// All constants package-private for the --simd kernel mirror (see the class javadoc).

	/** Below this bound (inclusive) sinh uses its odd Taylor series instead of exp. */
	static final double SMALL = 0.25;

	// Taylor coefficients of sinh(x)/x over z = x^2, from the highest degree down
	// (Horner order): 1/9!, 1/7!, 1/5!, 1/3!, 1.
	static final double[] SINH_COEFFS = { 1.0 / 362880.0, 1.0 / 5040.0, 1.0 / 120.0, 1.0 / 6.0, 1.0 };

	private WasmSinhCoshCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx, String name) {
		List<LispVal> args = cons.toList();
		if (args.size() != 2) {
			throw new UnsupportedOperationException(name + " expects 1 argument, got " + (args.size() - 1));
		}
		int xSlot = ctx.allocTemp(); // x
		int tSlot = ctx.allocTemp(); // the exp core's reduced argument / z / s scratch
		int accSlot = ctx.allocTemp(); // the exp core's accumulator (holds e after it)

		WasmExprCompiler.compileExpr(args.get(1), ctx);
		WasmEmitHelper.castFloatGetF64(ctx);
		WasmExpCompiler.boxF64(ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(xSlot);

		// if (x != x) -> NaN
		unbox(ctx, xSlot);
		unbox(ctx, xSlot);
		ctx.writer.write(Instruction.F64_NE);
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(Type.F64);
		unbox(ctx, xSlot);
		ctx.writer.write(Instruction.ELSE);
		// if (|x| == +inf) -> x for sinh (odd), |x| for cosh (even). Must precede the
		// exponential: the exp core's Horner polynomial maps -inf to +inf, not 0.
		unbox(ctx, xSlot);
		ctx.writer.write(Instruction.F64_ABS);
		f64Const(ctx, Double.POSITIVE_INFINITY);
		ctx.writer.write(Instruction.F64_EQ);
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(Type.F64);
		unbox(ctx, xSlot);
		if (LispNames.COSH.equals(name)) {
			ctx.writer.write(Instruction.F64_ABS);
		}
		ctx.writer.write(Instruction.ELSE);
		switch (name) {
			case LispNames.SINH -> emitSinhMain(ctx, xSlot, tSlot, accSlot);
			case LispNames.COSH -> emitCoshMain(ctx, xSlot, tSlot, accSlot);
			default -> throw new IllegalArgumentException("not a sinh/cosh operator: " + name);
		}
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.END);
		WasmExpCompiler.boxF64(ctx);
	}

	// The finite sinh path; leaves the f64 result on the stack.
	private static void emitSinhMain(WasmLispCompiler.Ctx ctx, int xSlot, int tSlot, int accSlot) {
		// if (|x| > SMALL) exp derivation else the odd Taylor series.
		unbox(ctx, xSlot);
		ctx.writer.write(Instruction.F64_ABS);
		f64Const(ctx, SMALL);
		ctx.writer.write(Instruction.F64_GT);
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(Type.F64);
		// e = exp(|x|); s = (e - 1/e) * 0.5, boxed into tSlot.
		emitExpOfAbs(ctx, xSlot, tSlot, accSlot);
		unbox(ctx, accSlot);
		f64Const(ctx, 1.0);
		unbox(ctx, accSlot);
		ctx.writer.write(Instruction.F64_DIV);
		ctx.writer.write(Instruction.F64_SUB);
		f64Const(ctx, 0.5);
		ctx.writer.write(Instruction.F64_MUL);
		boxInto(ctx, tSlot);
		// The sign restore: x < 0 ? 0 - s : s.
		unbox(ctx, xSlot);
		f64Const(ctx, 0.0);
		ctx.writer.write(Instruction.F64_LT);
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(Type.F64);
		f64Const(ctx, 0.0);
		unbox(ctx, tSlot);
		ctx.writer.write(Instruction.F64_SUB);
		ctx.writer.write(Instruction.ELSE);
		unbox(ctx, tSlot);
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.ELSE);
		// z = x * x; Horner(SINH_COEFFS over z) * x (applied to x directly, so -0.0 is
		// preserved).
		unbox(ctx, xSlot);
		unbox(ctx, xSlot);
		ctx.writer.write(Instruction.F64_MUL);
		boxInto(ctx, tSlot);
		f64Const(ctx, SINH_COEFFS[0]);
		for (int i = 1; i < SINH_COEFFS.length; i++) {
			unbox(ctx, tSlot);
			ctx.writer.write(Instruction.F64_MUL);
			f64Const(ctx, SINH_COEFFS[i]);
			ctx.writer.write(Instruction.F64_ADD);
		}
		unbox(ctx, xSlot);
		ctx.writer.write(Instruction.F64_MUL);
		ctx.writer.write(Instruction.END);
	}

	// The finite cosh path; leaves the f64 result on the stack.
	private static void emitCoshMain(WasmLispCompiler.Ctx ctx, int xSlot, int tSlot, int accSlot) {
		// e = exp(|x|); (e + 1/e) * 0.5.
		emitExpOfAbs(ctx, xSlot, tSlot, accSlot);
		unbox(ctx, accSlot);
		f64Const(ctx, 1.0);
		unbox(ctx, accSlot);
		ctx.writer.write(Instruction.F64_DIV);
		ctx.writer.write(Instruction.F64_ADD);
		f64Const(ctx, 0.5);
		ctx.writer.write(Instruction.F64_MUL);
	}

	// Runs the shared exp core on |x|; leaves e = exp(|x|) boxed in accSlot.
	private static void emitExpOfAbs(WasmLispCompiler.Ctx ctx, int xSlot, int tSlot, int accSlot) {
		unbox(ctx, xSlot);
		ctx.writer.write(Instruction.F64_ABS);
		WasmExpCompiler.emitExpCore(ctx, tSlot, accSlot);
		ctx.writer.write(Instruction.DROP);
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
