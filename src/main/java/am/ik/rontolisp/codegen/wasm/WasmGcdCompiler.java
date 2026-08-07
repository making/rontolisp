package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;

/**
 * Compiles the {@code gcd} built-in: the greatest common divisor of two integers via the
 * {@code _big_gcd} runtime helper (Euclid over tier-aware remainders), so the result is
 * exact at any magnitude and always non-negative.
 */
final class WasmGcdCompiler {

	private WasmGcdCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		WasmExprCompiler.compileExpr(args.get(2), ctx);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_BIG_GCD);
	}

}
