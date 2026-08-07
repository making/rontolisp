package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;

/**
 * Compiles the {@code %list-directory} internal primitive: {@code (t . names)} for a
 * readable directory, nil otherwise. The path argument is compiled to a runtime string
 * and passed to the {@code _list_directory} runtime, which (like {@code _probe_file})
 * answers nil on a failed {@code path_open} instead of trapping. The path resolves
 * relative to the first preopened directory (fd 3), so the module must run with
 * {@code --dir}; without one the answer is simply nil.
 */
final class WasmListDirectoryCompiler {

	private WasmListDirectoryCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> parts = cons.toList();
		if (parts.size() != 2) {
			throw new UnsupportedOperationException(
					LispNames.LIST_DIRECTORY + " expects 1 argument, got " + (parts.size() - 1));
		}
		WasmExprCompiler.compileExpr(parts.get(1), ctx);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_LIST_DIRECTORY);
	}

}
