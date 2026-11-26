package am.ik.rontolisp.codegen.wasm;

import am.ik.rontolisp.codegen.wasm.WasmFdlibmRuntimeBuilder.Fn;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;

/**
 * The real arms of the inverse hyperbolics for WASM: the interpreter's own groupings
 * ({@code Environment.asinhReal} / {@code acoshReal} / {@code atanhReal}, which the JVM's
 * {@code _cu1} helper also bytecodes term for term), over the fdlibm {@code log1p},
 * {@code log} and {@code hypot} the other two backends reach as {@code StrictMath} -- so
 * the three answer the same bits everywhere. Each takes an f64 on the stack and leaves an
 * f64, over the domain the call site in {@link WasmComplexCompiler} has already narrowed
 * (the escape and the plane arms live there, like the sqrt site's).
 */
final class WasmInverseHypCompiler {

	private WasmInverseHypCompiler() {
	}

	/**
	 * asinh of the f64 on the stack, leaving an f64: with {@code a = |x|},
	 * {@code log1p(a + a*a/(1 + hypot(a, 1)))} up to 1, {@code log(a + hypot(a, 1))} up
	 * to 8.5e307 (where the sum would overflow) and {@code log(a) + log(2)} beyond, the
	 * sign of {@code x} restored by copysign.
	 * @param ctx the compile context
	 */
	static void emitAsinhRealF64(WasmLispCompiler.Ctx ctx) {
		int xSlot = boxTemp(ctx);
		WasmEmitHelper.unboxF64Local(ctx, xSlot);
		ctx.writer.write(Instruction.F64_ABS);
		int aSlot = boxTemp(ctx);
		unbox(ctx, aSlot);
		f64Const(ctx, 1.0);
		ctx.writer.write(Instruction.F64_LE);
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(Type.F64);
		// log1p(a + (a * a) / (1.0 + hypot(a, 1.0)))
		unbox(ctx, aSlot);
		unbox(ctx, aSlot);
		unbox(ctx, aSlot);
		ctx.writer.write(Instruction.F64_MUL);
		f64Const(ctx, 1.0);
		unbox(ctx, aSlot);
		f64Const(ctx, 1.0);
		WasmTranscendentalCompiler.call(ctx, Fn.HYPOT);
		ctx.writer.write(Instruction.F64_ADD);
		ctx.writer.write(Instruction.F64_DIV);
		ctx.writer.write(Instruction.F64_ADD);
		WasmTranscendentalCompiler.call(ctx, Fn.LOG1P);
		ctx.writer.write(Instruction.ELSE);
		unbox(ctx, aSlot);
		f64Const(ctx, 8.5e307);
		ctx.writer.write(Instruction.F64_LT);
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(Type.F64);
		// log(a + hypot(a, 1.0))
		unbox(ctx, aSlot);
		unbox(ctx, aSlot);
		f64Const(ctx, 1.0);
		WasmTranscendentalCompiler.call(ctx, Fn.HYPOT);
		ctx.writer.write(Instruction.F64_ADD);
		WasmTranscendentalCompiler.call(ctx, Fn.LOG);
		ctx.writer.write(Instruction.ELSE);
		// log(a) + log(2.0)
		unbox(ctx, aSlot);
		WasmTranscendentalCompiler.call(ctx, Fn.LOG);
		f64Const(ctx, 2.0);
		WasmTranscendentalCompiler.call(ctx, Fn.LOG);
		ctx.writer.write(Instruction.F64_ADD);
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.END);
		unbox(ctx, xSlot);
		ctx.writer.write(Instruction.F64_COPYSIGN);
	}

	/**
	 * acosh of the f64 on the stack (the site has proven x &gt;= 1 or NaN), leaving an
	 * f64: below 2, {@code log1p(xm + sqrt(xm * (x + 1)))} with {@code xm = x - 1}; below
	 * 8.5e307, {@code log(2x) + log1p((r - 1) / 2)} with {@code r = sqrt(1 - (1/x)^2)};
	 * beyond, {@code log(x) + log(2)}.
	 * @param ctx the compile context
	 */
	static void emitAcoshRealF64(WasmLispCompiler.Ctx ctx) {
		int xSlot = boxTemp(ctx);
		unbox(ctx, xSlot);
		f64Const(ctx, 2.0);
		ctx.writer.write(Instruction.F64_LT);
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(Type.F64);
		// xm = x - 1.0; log1p(xm + sqrt(xm * (x + 1.0)))
		unbox(ctx, xSlot);
		f64Const(ctx, 1.0);
		ctx.writer.write(Instruction.F64_SUB);
		int xmSlot = boxTemp(ctx);
		unbox(ctx, xmSlot);
		unbox(ctx, xmSlot);
		unbox(ctx, xSlot);
		f64Const(ctx, 1.0);
		ctx.writer.write(Instruction.F64_ADD);
		ctx.writer.write(Instruction.F64_MUL);
		ctx.writer.write(Instruction.F64_SQRT);
		ctx.writer.write(Instruction.F64_ADD);
		WasmTranscendentalCompiler.call(ctx, Fn.LOG1P);
		ctx.writer.write(Instruction.ELSE);
		unbox(ctx, xSlot);
		f64Const(ctx, 8.5e307);
		ctx.writer.write(Instruction.F64_LT);
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(Type.F64);
		// inv = 1.0 / x; r = sqrt(1.0 - inv * inv); log(2.0 * x) + log1p((r - 1.0) / 2.0)
		f64Const(ctx, 1.0);
		unbox(ctx, xSlot);
		ctx.writer.write(Instruction.F64_DIV);
		int invSlot = boxTemp(ctx);
		f64Const(ctx, 1.0);
		unbox(ctx, invSlot);
		unbox(ctx, invSlot);
		ctx.writer.write(Instruction.F64_MUL);
		ctx.writer.write(Instruction.F64_SUB);
		ctx.writer.write(Instruction.F64_SQRT);
		int rSlot = boxTemp(ctx);
		f64Const(ctx, 2.0);
		unbox(ctx, xSlot);
		ctx.writer.write(Instruction.F64_MUL);
		WasmTranscendentalCompiler.call(ctx, Fn.LOG);
		unbox(ctx, rSlot);
		f64Const(ctx, 1.0);
		ctx.writer.write(Instruction.F64_SUB);
		f64Const(ctx, 2.0);
		ctx.writer.write(Instruction.F64_DIV);
		WasmTranscendentalCompiler.call(ctx, Fn.LOG1P);
		ctx.writer.write(Instruction.F64_ADD);
		ctx.writer.write(Instruction.ELSE);
		// log(x) + log(2.0)
		unbox(ctx, xSlot);
		WasmTranscendentalCompiler.call(ctx, Fn.LOG);
		f64Const(ctx, 2.0);
		WasmTranscendentalCompiler.call(ctx, Fn.LOG);
		ctx.writer.write(Instruction.F64_ADD);
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.END);
	}

	/**
	 * atanh of the f64 on the stack (the site has proven |x| &lt;= 1 or NaN), leaving an
	 * f64: {@code (log1p(x) - log1p(-x)) * 0.5} -- exactly 0 at 0, the signed zero kept,
	 * +-Infinity at +-1.
	 * @param ctx the compile context
	 */
	static void emitAtanhRealF64(WasmLispCompiler.Ctx ctx) {
		int xSlot = boxTemp(ctx);
		unbox(ctx, xSlot);
		WasmTranscendentalCompiler.call(ctx, Fn.LOG1P);
		unbox(ctx, xSlot);
		ctx.writer.write(Instruction.F64_NEG);
		WasmTranscendentalCompiler.call(ctx, Fn.LOG1P);
		ctx.writer.write(Instruction.F64_SUB);
		f64Const(ctx, 0.5);
		ctx.writer.write(Instruction.F64_MUL);
	}

	// Boxes the f64 on the stack into a fresh temp and answers its slot.
	private static int boxTemp(WasmLispCompiler.Ctx ctx) {
		WasmEmitHelper.boxF64(ctx);
		int slot = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
		return slot;
	}

	private static void unbox(WasmLispCompiler.Ctx ctx, int slot) {
		WasmEmitHelper.unboxF64Local(ctx, slot);
	}

	private static void f64Const(WasmLispCompiler.Ctx ctx, double value) {
		ctx.writer.write(Instruction.F64_CONST);
		ctx.writer.writeF64(value);
	}

}
