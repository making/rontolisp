package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;

/**
 * Compiles the {@code %delete-file} internal primitive: the T symbol when the named file
 * or empty directory was removed, nil when there was nothing to remove or the host
 * refused. The path argument is compiled to a runtime string and passed to the
 * {@code _delete_file} runtime, which unlinks through the {@code path_unlink_file} import
 * and removes a directory through the injected {@code path_remove_directory}. The "a
 * missing file is a file-error" decision lives once in the Lisp {@code delete-file} above
 * this, as on the interpreter and the JVM.
 */
final class WasmDeleteFileCompiler {

	private WasmDeleteFileCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> parts = cons.toList();
		if (parts.size() != 2) {
			throw new UnsupportedOperationException(
					LispNames.DELETE_FILE_INTERNAL + " expects 1 argument, got " + (parts.size() - 1));
		}
		WasmExprCompiler.compileExpr(parts.get(1), ctx);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_DELETE_FILE);
	}

}
