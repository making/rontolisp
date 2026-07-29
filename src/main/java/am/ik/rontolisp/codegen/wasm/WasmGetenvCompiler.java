package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;

/**
 * Compiles {@code uiop:getenv} for WASM: compiles the name argument and delegates to the
 * {@code _getenv} runtime helper, which scans the WASI environ buffer and returns the
 * value as a string (or {@code nil}).
 */
final class WasmGetenvCompiler {

	private WasmGetenvCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		if (args.size() != 2) {
			throw new UnsupportedOperationException(
					LispNames.UIOP_GETENV + " expects 1 argument, got " + (args.size() - 1));
		}
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.FUNC_GETENV);
	}

}
