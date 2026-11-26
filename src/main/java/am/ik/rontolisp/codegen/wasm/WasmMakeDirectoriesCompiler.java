package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;

/**
 * Compiles the {@code %make-directories} internal primitive: the T symbol when the
 * directory exists afterwards, nil when the host refused. The path argument is compiled
 * to a runtime string and passed to the {@code _make_directories} runtime, which creates
 * every missing level through the {@code path_create_directory} import and verifies the
 * final result by opening it as a directory. The "a refused directory is a file-error"
 * decision lives once in the Lisp {@code ensure-directories-exist} above this, as on the
 * interpreter and the JVM -- the {@code %delete-file} / {@code %rename-file} shape.
 */
final class WasmMakeDirectoriesCompiler {

	private WasmMakeDirectoriesCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> parts = cons.toList();
		if (parts.size() != 2) {
			throw new UnsupportedOperationException(
					LispNames.MAKE_DIRECTORIES + " expects 1 argument, got " + (parts.size() - 1));
		}
		WasmExprCompiler.compileExpr(parts.get(1), ctx);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_MAKE_DIRECTORIES);
	}

}
