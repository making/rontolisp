package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;

/**
 * Compiles the {@code progn} special form.
 */
final class WasmPrognCompiler {

	private WasmPrognCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> parts = cons.toList();
		for (int i = 1; i < parts.size(); i++) {
			if (i > 1) {
				ctx.writer.write(Instruction.DROP);
			}
			WasmExprCompiler.compileExpr(parts.get(i), ctx);
		}
	}

}
