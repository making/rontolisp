package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;

/**
 * Compiles the internal {@code %fixed-decimal} built-in function -- what {@code format}'s
 * {@code ~F} and {@code ~$} lower to -- as one call to the {@code _fixed_dec} runtime
 * helper ({@link WasmFixedDecimalRuntimeBuilder}).
 *
 * <p>
 * The helper takes the four arguments boxed and does its own coercions, so the whole
 * directive costs a call and four argument pushes at the site. That is the point of it:
 * the numeric type ladders live in the shared function instead of being inlined once per
 * generic operation per directive.
 */
final class WasmFixedDecimalCompiler {

	private WasmFixedDecimalCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		if (args.size() != 5) {
			throw new UnsupportedOperationException("%fixed-decimal expects 4 arguments, got " + (args.size() - 1));
		}
		for (int i = 1; i <= 4; i++) {
			WasmExprCompiler.compileExpr(args.get(i), ctx);
		}
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_FIXED_DEC);
	}

}
