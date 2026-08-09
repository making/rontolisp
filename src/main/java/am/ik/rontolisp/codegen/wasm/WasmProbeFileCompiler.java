package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;

/**
 * Compiles the {@code %probe-file} internal primitive: the namestring when the file
 * exists, nil otherwise. The path argument is compiled to a runtime string and passed to
 * the {@code _probe_file} runtime, which (unlike {@code _open}) answers nil on a failed
 * {@code path_open} instead of trapping. Like {@code open} and {@code load} the path
 * resolves relative to the first preopened directory (fd 3), so the module must run with
 * {@code --dir}. The public {@code probe-file} is prelude Lisp over this, wrapping the
 * answer in a pathname value.
 */
final class WasmProbeFileCompiler {

	private WasmProbeFileCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> parts = cons.toList();
		if (parts.size() != 2) {
			throw new UnsupportedOperationException(
					LispNames.PROBE_FILE_INTERNAL + " expects 1 argument, got " + (parts.size() - 1));
		}
		WasmExprCompiler.compileExpr(parts.get(1), ctx);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_PROBE_FILE);
	}

}
