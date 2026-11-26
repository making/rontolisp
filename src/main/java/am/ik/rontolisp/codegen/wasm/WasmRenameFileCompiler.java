package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;

/**
 * Compiles the {@code %rename-file} internal primitive: the T symbol when the file was
 * renamed, nil when there was nothing to rename or the host refused. Both path arguments
 * are compiled to runtime strings and passed to the {@code _rename_file} runtime, which
 * moves through the {@code path_rename} import. The "a missing file is a file-error"
 * decision lives once in the Lisp {@code rename-file} above this, as on the interpreter
 * and the JVM.
 */
final class WasmRenameFileCompiler {

	private WasmRenameFileCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> parts = cons.toList();
		if (parts.size() != 3) {
			throw new UnsupportedOperationException(
					LispNames.RENAME_FILE_INTERNAL + " expects 2 arguments, got " + (parts.size() - 1));
		}
		WasmExprCompiler.compileExpr(parts.get(1), ctx);
		WasmExprCompiler.compileExpr(parts.get(2), ctx);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_RENAME_FILE);
	}

}
