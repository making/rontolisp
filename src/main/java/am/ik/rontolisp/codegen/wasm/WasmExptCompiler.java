package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.codegen.wasm.WasmFdlibmRuntimeBuilder.Fn;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;

/**
 * Compiles the {@code expt} built-in, dispatching on the RUNTIME types of both operands
 * exactly as the interpreter does.
 *
 * <p>
 * An exact base (an integer or a ratio) to an integer exponent: repeated rational
 * multiplication, so a ratio base stays exact, an integer base promotes to big integers
 * at any magnitude (the loop runs through {@code _rat_mul}'s tier-aware fast path), and a
 * negative exponent yields the reciprocal ({@code (expt 2 -1)} is {@code 1/2}).
 *
 * <p>
 * Anything else -- a float base, a float exponent, a ratio exponent -- is the float
 * {@code pow(x, y)} of the fdlibm runtime, the same bits {@code StrictMath.pow} answers
 * on the other backends: {@code (expt 2.0 3)} is {@code 8.0}, {@code (expt 2 0.5)} is
 * {@code 1.4142135623730951}, {@code (expt 4 1/2)} is {@code 2.0}, and the IEEE edges are
 * pow's own ({@code x^0.0 = 1.0}, {@code 0^y = 0.0} for {@code y > 0} and {@code +inf}
 * for {@code y < 0}, a NaN base to a fractional power is NaN, {@code +inf^y} is
 * {@code +inf} / {@code 0.0} by the sign of {@code y}).
 *
 * <p>
 * A NEGATIVE base to a non-integer power leaves the real line and answers the plane --
 * {@code |x|^y} turned through {@code y*pi} radians, the interpreter's
 * {@code negativeBasePow} -- unless the call site's literals already prove the escape
 * unreachable, in which case the arm is not emitted at all and pow's NaN stands
 * ({@code .kb/wasm-complex.md}).
 */
final class WasmExptCompiler {

	private WasmExptCompiler() {
	}

	/**
	 * @param cons the {@code (expt base power)} form
	 * @param ctx the compile context
	 * @param complexEscape whether a negative base to a fractional power must answer the
	 * PLANE rather than NaN -- false only when the form's literals already prove the
	 * escape unreachable ({@code (expt x 2)}, {@code (expt 10.0 n)}), which is the same
	 * predicate the JVM's complex gate reads ({@code LispMacroExpander.escapesToComplex})
	 */
	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx, boolean complexEscape) {
		List<LispVal> args = cons.toList();
		int baseSlot = ctx.allocTemp();
		int pSlot = ctx.allocTemp();
		int rSlot = ctx.allocTemp();

		WasmExprCompiler.compileExpr(args.get(1), ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(baseSlot);
		WasmExprCompiler.compileExpr(args.get(2), ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(pSlot);

		// if (base is a float || p is a float || p is a ratio) { the float path }
		// else { the exact loop over an integer exponent }
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(baseSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_FLOAT);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(pSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_FLOAT);
		ctx.writer.write(Instruction.I32_OR);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(pSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_RATIO);
		ctx.writer.write(Instruction.I32_OR);
		ctx.writer.write(Instruction.IF, 0x40);
		emitFloatPath(ctx, baseSlot, pSlot, rSlot, complexEscape);
		ctx.writer.write(Instruction.ELSE);
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

	// The float path: x = as_f64(base), y = as_f64(p); the plane when the base is
	// negative and the power a finite non-integer (the interpreter's escapesToPlane),
	// pow(x, y) otherwise. Leaves the result in rSlot.
	private static void emitFloatPath(WasmLispCompiler.Ctx ctx, int baseSlot, int pSlot, int rSlot,
			boolean complexEscape) {
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(baseSlot);
		WasmEmitHelper.castFloatGetF64(ctx);
		int xSlot = boxTemp(ctx);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(pSlot);
		WasmEmitHelper.castFloatGetF64(ctx);
		int ySlot = boxTemp(ctx);
		if (complexEscape) {
			// x < 0.0 && |y| < inf && y != nearest(y): the modulus |x|^y turned
			// through y*pi radians (WasmComplexCompiler.emitNegativeBasePowInto).
			unbox(ctx, xSlot);
			f64Const(ctx, 0.0);
			ctx.writer.write(Instruction.F64_LT);
			unbox(ctx, ySlot);
			ctx.writer.write(Instruction.F64_ABS);
			f64Const(ctx, Double.POSITIVE_INFINITY);
			ctx.writer.write(Instruction.F64_LT);
			ctx.writer.write(Instruction.I32_AND);
			unbox(ctx, ySlot);
			unbox(ctx, ySlot);
			ctx.writer.write(Instruction.F64_NEAREST);
			ctx.writer.write(Instruction.F64_NE);
			ctx.writer.write(Instruction.I32_AND);
			ctx.writer.write(Instruction.IF, 0x40);
			unbox(ctx, xSlot);
			ctx.writer.write(Instruction.F64_NEG);
			unbox(ctx, ySlot);
			WasmTranscendentalCompiler.call(ctx, Fn.POW);
			int modulusSlot = boxTemp(ctx);
			WasmComplexCompiler.emitNegativeBasePowInto(ctx, modulusSlot, ySlot);
			ctx.writer.write(Instruction.SET_LOCAL);
			ctx.writer.writeUnsignedLeb128(rSlot);
			ctx.writer.write(Instruction.ELSE);
			emitPowInto(ctx, xSlot, ySlot, rSlot);
			ctx.writer.write(Instruction.END);
			return;
		}
		emitPowInto(ctx, xSlot, ySlot, rSlot);
	}

	private static void emitPowInto(WasmLispCompiler.Ctx ctx, int xSlot, int ySlot, int rSlot) {
		unbox(ctx, xSlot);
		unbox(ctx, ySlot);
		WasmTranscendentalCompiler.call(ctx, Fn.POW);
		WasmEmitHelper.boxF64(ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(rSlot);
	}

	private static void f64Const(WasmLispCompiler.Ctx ctx, double value) {
		ctx.writer.write(Instruction.F64_CONST);
		ctx.writer.writeF64(value);
	}

	private static void unbox(WasmLispCompiler.Ctx ctx, int slot) {
		WasmEmitHelper.unboxF64Local(ctx, slot);
	}

	// Boxes the f64 on the stack into a fresh temp and answers its slot.
	private static int boxTemp(WasmLispCompiler.Ctx ctx) {
		WasmEmitHelper.boxF64(ctx);
		int slot = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
		return slot;
	}

}
