package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;

/**
 * Compiles the {@code tanh} built-in for WASM. WASM has no native transcendental
 * instruction, so this derives {@code tanh(x) = (e^(2x) - 1) / (e^(2x) + 1)} from the
 * {@link WasmExpCompiler} software {@code exp} approximation, always returning a float.
 *
 * <p>
 * The doubled argument is clamped to {@code [-CLAMP, CLAMP]} BEFORE the exponential:
 * {@code tanh} saturates long before {@code 2x = ±40} ({@code 1 - tanh(20)} is below half
 * an ulp of {@code 1.0}), so the clamp makes the large-|x| result exactly {@code ±1.0}
 * instead of the {@code inf/inf = NaN} the raw formula would produce, while keeping the
 * reduced argument small enough for the {@code exp} polynomial. The clamp uses
 * {@code f64.max}/{@code f64.min}, which propagate NaN, so {@code (tanh NaN)} stays NaN.
 *
 * <p>
 * Like {@code exp} itself, the result matches {@code Math.tanh} to roughly 1e-6 relative
 * error but is not bit-identical to the interpreter/JVM value (for a tiny {@code x} the
 * {@code e^(2x) - 1} subtraction additionally loses a few low-order digits). The IEEE
 * edges DO match: NaN survives the clamp, and a zero short-circuits to the argument so
 * {@code (tanh -0.0)} is {@code -0.0} as on every other backend.
 */
final class WasmTanhCompiler {

	// The saturation clamp on the DOUBLED argument (2x). Package-private so the --simd
	// unary kernels (WasmVecSimdRuntimeBuilder.emitTanhF64) reproduce the SAME sequence
	// on raw f64 locals -- bit-identity to this defun path by shared constants and
	// operation order.
	static final double CLAMP = 40.0;

	private WasmTanhCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		if (args.size() != 2) {
			throw new UnsupportedOperationException("tanh expects 1 argument, got " + (args.size() - 1));
		}
		int tSlot = ctx.allocTemp();
		int accSlot = ctx.allocTemp();
		int xSlot = ctx.allocTemp();

		WasmExprCompiler.compileExpr(args.get(1), ctx);
		WasmEmitHelper.castFloatGetF64(ctx);
		WasmExpCompiler.boxF64(ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(xSlot);

		// tanh is ODD, so tanh(+/-0.0) is the argument itself, sign of the zero included
		// -- which the derivation below cannot produce: exp(-0.0) is 1.0, and the
		// (e-1)/(e+1) that follows is (0.0)/(2.0) = +0.0, flattening the sign. Answering
		// the argument is exact, and it is what Math.tanh gives the interpreter and the
		// JVM backend.
		WasmExpCompiler.unboxF64Local(ctx, xSlot);
		ctx.writer.write(Instruction.F64_CONST);
		ctx.writer.writeF64(0.0);
		ctx.writer.write(Instruction.F64_EQ);
		ctx.writer.write(Instruction.IF);
		ctx.writer.writeRefType(true, am.ik.wasm.Type.EQ.code());
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(xSlot);
		ctx.writer.write(Instruction.ELSE);

		// e = exp(clamp(2x)), boxed into accSlot by the shared exp core.
		WasmExpCompiler.unboxF64Local(ctx, xSlot);
		ctx.writer.write(Instruction.F64_CONST);
		ctx.writer.writeF64(2.0);
		ctx.writer.write(Instruction.F64_MUL);
		ctx.writer.write(Instruction.F64_CONST);
		ctx.writer.writeF64(-CLAMP);
		ctx.writer.write(Instruction.F64_MAX);
		ctx.writer.write(Instruction.F64_CONST);
		ctx.writer.writeF64(CLAMP);
		ctx.writer.write(Instruction.F64_MIN);
		WasmExpCompiler.emitExpCore(ctx, tSlot, accSlot);
		ctx.writer.write(Instruction.DROP);

		// (e - 1) / (e + 1), boxed.
		WasmExpCompiler.unboxF64Local(ctx, accSlot);
		ctx.writer.write(Instruction.F64_CONST);
		ctx.writer.writeF64(1.0);
		ctx.writer.write(Instruction.F64_SUB);
		WasmExpCompiler.unboxF64Local(ctx, accSlot);
		ctx.writer.write(Instruction.F64_CONST);
		ctx.writer.writeF64(1.0);
		ctx.writer.write(Instruction.F64_ADD);
		ctx.writer.write(Instruction.F64_DIV);
		WasmExpCompiler.boxF64(ctx);
		ctx.writer.write(Instruction.END);
	}

}
