package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;

/**
 * Compiles the {@code numerator} and {@code denominator} accessors via the
 * {@code _rat_num}/{@code _rat_den} runtime helpers: a ratio yields the requested
 * component, an integer is its own numerator and has denominator one.
 */
final class WasmRatioAccessorCompiler {

	private WasmRatioAccessorCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx, int ratioFunc) {
		List<LispVal> args = cons.toList();
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeSignedLeb128(ratioFunc);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
	}

}
