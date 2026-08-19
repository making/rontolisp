package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;

/**
 * Compiles the {@code expt} built-in, dispatching on the RUNTIME type of the exponent.
 *
 * <p>
 * An integer exponent (the exact path): repeated rational multiplication of the base by
 * itself {@code power} times, so a ratio base stays exact, an integer base promotes to
 * big integers at any magnitude (the loop runs through {@code _rat_mul}'s tier-aware fast
 * path), a float base multiplies as a float, and a negative integer exponent yields the
 * reciprocal (e.g. {@code (expt 2 -1)} is {@code 1/2}).
 *
 * <p>
 * A float or ratio exponent (the float path -- {@code (expt 10000.0 0.75)},
 * {@code (expt 4 1/2)}, an exponent computed at run time): the result is a float. An
 * integer-VALUED float exponent ({@code (expt 2 3.0)}) coerces the base to a float and
 * takes the exact loop, so it is {@code 8.0} exactly; a fractional one is
 * {@code exp(y * ln(x))} over the backend's software {@code exp} / {@code log} (so it
 * carries their approximation error, like every WASM transcendental), with the
 * {@code Math.pow} edges the interpreter and JVM answer: {@code x^0 = 1.0},
 * {@code 0^y = 0.0} for {@code y > 0} and {@code +inf} for {@code y < 0}, a negative or
 * NaN base to a fractional power is NaN, {@code +inf^y} is {@code +inf} / {@code 0.0} by
 * the sign of {@code y}.
 */
final class WasmExptCompiler {

	private WasmExptCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		int baseSlot = ctx.allocTemp();
		int pSlot = ctx.allocTemp();
		int rSlot = ctx.allocTemp();
		// Float-path temps: y (boxed f64), the exp/log scratch, and the "result already
		// in
		// rSlot" flag (an i31 0/1).
		int ySlot = ctx.allocTemp();
		int t1Slot = ctx.allocTemp();
		int t2Slot = ctx.allocTemp();
		int doneSlot = ctx.allocTemp();

		WasmExprCompiler.compileExpr(args.get(1), ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(baseSlot);
		WasmExprCompiler.compileExpr(args.get(2), ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(pSlot);

		WasmMathHelper.constI32(ctx, 0);
		WasmMathHelper.setI32(ctx, doneSlot);

		// if (p is a float or a ratio) { the float path }
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(pSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_FLOAT);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(pSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_RATIO);
		ctx.writer.write(Instruction.I32_OR);
		ctx.writer.write(Instruction.IF, 0x40);
		emitFloatExponent(ctx, baseSlot, pSlot, rSlot, ySlot, t1Slot, t2Slot, doneSlot);
		ctx.writer.write(Instruction.END);

		// if (!done) { the exact loop over an integer exponent, into rSlot }
		WasmMathHelper.getI32(ctx, doneSlot);
		ctx.writer.write(Instruction.I32_EQZ);
		ctx.writer.write(Instruction.IF, 0x40);
		emitIntegerExponent(ctx, baseSlot, pSlot, rSlot);
		ctx.writer.write(Instruction.END);

		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(rSlot);
	}

	// The exact path: r = base^p by repeated _rat_mul, p an i31 integer.
	private static void emitIntegerExponent(WasmLispCompiler.Ctx ctx, int baseSlot, int pSlot, int rSlot) {
		// Negative exponent: base = (/ 1 base), power = -power.
		WasmMathHelper.getI32(ctx, pSlot);
		WasmMathHelper.constI32(ctx, 0);
		ctx.writer.write(Instruction.I32_LT_S);
		ctx.writer.write(Instruction.IF, 0x40);
		WasmMathHelper.constI32(ctx, 1);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(baseSlot);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_RAT_DIV);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(baseSlot);
		WasmMathHelper.constI32(ctx, 0);
		WasmMathHelper.getI32(ctx, pSlot);
		ctx.writer.write(Instruction.I32_SUB);
		WasmMathHelper.setI32(ctx, pSlot);
		ctx.writer.write(Instruction.END);

		// r = 1
		WasmMathHelper.constI32(ctx, 1);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(rSlot);

		ctx.writer.write(Instruction.BLOCK, 0x40);
		ctx.writer.write(Instruction.LOOP, 0x40);
		// if power <= 0, exit
		WasmMathHelper.getI32(ctx, pSlot);
		WasmMathHelper.constI32(ctx, 0);
		ctx.writer.write(Instruction.I32_LE_S);
		ctx.writer.write(Instruction.BR_IF, 1);
		// r = r * base (rational multiplication keeps ratio bases exact)
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(rSlot);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(baseSlot);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_RAT_MUL);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(rSlot);
		// power = power - 1
		WasmMathHelper.getI32(ctx, pSlot);
		WasmMathHelper.constI32(ctx, 1);
		ctx.writer.write(Instruction.I32_SUB);
		WasmMathHelper.setI32(ctx, pSlot);
		ctx.writer.write(Instruction.BR, 0);
		ctx.writer.write(Instruction.END); // loop
		ctx.writer.write(Instruction.END); // block
	}

	// The float path. p is a TYPE_FLOAT or TYPE_RATIO. An integer-valued float exponent
	// rewrites (base, p) into (float base, i31 p) for the exact loop and leaves done = 0;
	// anything else computes the float result into rSlot and sets done = 1.
	private static void emitFloatExponent(WasmLispCompiler.Ctx ctx, int baseSlot, int pSlot, int rSlot, int ySlot,
			int t1Slot, int t2Slot, int doneSlot) {
		// y = as_f64(p), boxed
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(pSlot);
		WasmEmitHelper.castFloatGetF64(ctx);
		boxInto(ctx, ySlot);

		// if (trunc(y) == y && |y| < 2^30) -- integer-valued, i31-sized (a ratio never
		// is)
		unbox(ctx, ySlot);
		ctx.writer.write(Instruction.F64_TRUNC);
		unbox(ctx, ySlot);
		ctx.writer.write(Instruction.F64_EQ);
		unbox(ctx, ySlot);
		ctx.writer.write(Instruction.F64_ABS);
		f64Const(ctx, 1073741824.0);
		ctx.writer.write(Instruction.F64_LT);
		ctx.writer.write(Instruction.I32_AND);
		ctx.writer.write(Instruction.IF, 0x40);
		// y == 0.0: x^0.0 = 1.0 (a FLOAT one, whatever x is -- the exact loop would
		// answer the integer 1)
		unbox(ctx, ySlot);
		f64Const(ctx, 0.0);
		ctx.writer.write(Instruction.F64_EQ);
		ctx.writer.write(Instruction.IF, 0x40);
		f64Const(ctx, 1.0);
		boxInto(ctx, rSlot);
		WasmMathHelper.constI32(ctx, 1);
		WasmMathHelper.setI32(ctx, doneSlot);
		ctx.writer.write(Instruction.ELSE);
		// p = i31(trunc(y)); base = float(base) -- then the exact loop multiplies floats
		unbox(ctx, ySlot);
		ctx.writer.write(Instruction.I32_TRUNC_S_F64);
		WasmMathHelper.setI32(ctx, pSlot);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(baseSlot);
		WasmEmitHelper.castFloatGetF64(ctx);
		boxInto(ctx, baseSlot);
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.ELSE);
		// x = as_f64(base), boxed into t1
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(baseSlot);
		WasmEmitHelper.castFloatGetF64(ctx);
		boxInto(ctx, t1Slot);
		emitPowFractional(ctx, t1Slot, ySlot, rSlot, t2Slot, doneSlot);
		WasmMathHelper.constI32(ctx, 1);
		WasmMathHelper.setI32(ctx, doneSlot);
		ctx.writer.write(Instruction.END);
	}

	// x^y for a fractional (or NaN / infinite) y: leaves the boxed float in rSlot.
	// x is boxed in xSlot, y in ySlot; scratchSlot and doneSlot are free ref temps
	// (doneSlot is only assigned by the caller afterwards).
	private static void emitPowFractional(WasmLispCompiler.Ctx ctx, int xSlot, int ySlot, int rSlot, int scratchSlot,
			int doneSlot) {
		// if (x > 0.0)
		unbox(ctx, xSlot);
		f64Const(ctx, 0.0);
		ctx.writer.write(Instruction.F64_GT);
		ctx.writer.write(Instruction.IF, 0x40);
		// if (x == +inf) r = y > 0 ? +inf : 0.0 (y is not 0 here: 0.0 is integer-valued)
		unbox(ctx, xSlot);
		f64Const(ctx, Double.POSITIVE_INFINITY);
		ctx.writer.write(Instruction.F64_EQ);
		ctx.writer.write(Instruction.IF, 0x40);
		unbox(ctx, ySlot);
		f64Const(ctx, 0.0);
		ctx.writer.write(Instruction.F64_GT);
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(Type.F64);
		f64Const(ctx, Double.POSITIVE_INFINITY);
		ctx.writer.write(Instruction.ELSE);
		f64Const(ctx, 0.0);
		ctx.writer.write(Instruction.END);
		boxInto(ctx, rSlot);
		ctx.writer.write(Instruction.ELSE);
		// r = exp(y * ln(x)) -- ln reuses xSlot/scratchSlot/doneSlot as its temps
		// (x is consumed first), exp reuses scratchSlot/doneSlot.
		unbox(ctx, xSlot);
		WasmLogCompiler.emitLogCore(ctx, xSlot, scratchSlot, doneSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_FLOAT);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_FLOAT);
		ctx.writer.writeUnsignedLeb128(0);
		unbox(ctx, ySlot);
		ctx.writer.write(Instruction.F64_MUL);
		WasmExpCompiler.emitExpCore(ctx, scratchSlot, doneSlot);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(rSlot);
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.ELSE);
		// x == 0.0 (either sign): y > 0 -> 0.0, y < 0 -> +inf; x < 0 or NaN -> NaN
		unbox(ctx, xSlot);
		f64Const(ctx, 0.0);
		ctx.writer.write(Instruction.F64_EQ);
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(Type.F64);
		unbox(ctx, ySlot);
		f64Const(ctx, 0.0);
		ctx.writer.write(Instruction.F64_GT);
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(Type.F64);
		f64Const(ctx, 0.0);
		ctx.writer.write(Instruction.ELSE);
		f64Const(ctx, Double.POSITIVE_INFINITY);
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.ELSE);
		f64Const(ctx, Double.NaN);
		ctx.writer.write(Instruction.END);
		boxInto(ctx, rSlot);
		ctx.writer.write(Instruction.END);
	}

	private static void f64Const(WasmLispCompiler.Ctx ctx, double value) {
		ctx.writer.write(Instruction.F64_CONST);
		ctx.writer.writeF64(value);
	}

	private static void unbox(WasmLispCompiler.Ctx ctx, int slot) {
		WasmExpCompiler.unboxF64Local(ctx, slot);
	}

	private static void boxInto(WasmLispCompiler.Ctx ctx, int slot) {
		WasmExpCompiler.boxF64(ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
	}

}
