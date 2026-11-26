package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;

/**
 * Compiles the {@code prin1-to-string} built-in function: the string that {@code prin1}
 * would print (readable form), built in the heap by the {@code _prin1_to_str} runtime
 * function.
 */
final class WasmPrin1ToStringCompiler {

	private WasmPrin1ToStringCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_PRIN1_TO_STR);
	}

}
