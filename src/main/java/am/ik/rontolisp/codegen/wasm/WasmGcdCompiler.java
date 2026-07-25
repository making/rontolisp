package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;

/**
 * Compiles the {@code gcd} built-in: the greatest common divisor of two integers via the
 * Euclidean algorithm (always non-negative). Computes in i64, so the full exact-integer
 * range (i31 or boxed) crosses exactly.
 */
final class WasmGcdCompiler {

	private WasmGcdCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		int aSlot = ctx.allocTemp();
		int bSlot = ctx.allocTemp();
		int scratch = ctx.allocTemp();

		WasmExprCompiler.compileExpr(args.get(1), ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeSignedLeb128(aSlot);
		WasmExprCompiler.compileExpr(args.get(2), ctx);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeSignedLeb128(bSlot);

		WasmMathHelper.emitEuclid(ctx, aSlot, bSlot, scratch);
		// Result is abs(aSlot), normalized through _int_new.
		WasmMathHelper.emitAbs64FromLocal(ctx, aSlot);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.FUNC_INT_NEW);
	}

}
