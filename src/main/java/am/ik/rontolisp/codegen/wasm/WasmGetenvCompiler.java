package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;

/**
 * Compiles the {@code %host-getenv} internal primitive for WASM Preview 1: compiles the
 * name argument and delegates to the {@code _getenv} runtime helper, which scans the WASI
 * environ buffer and returns the value as a string (or {@code nil}). The public
 * {@code uiop:getenv} is Lisp over this ({@code uiop-os.lisp}), consulting the override
 * map a {@code (setf (uiop:getenv ...))} wrote first; under {@code --component} the
 * primitive itself is the spliced {@code environment.lisp} defun instead.
 */
final class WasmGetenvCompiler {

	private WasmGetenvCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		if (args.size() != 2) {
			throw new UnsupportedOperationException(
					LispNames.HOST_GETENV + " expects 1 argument, got " + (args.size() - 1));
		}
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_GETENV);
	}

}
