package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;

/**
 * Compiles the {@code %host-argv} internal primitive for WASM Preview 1: a call of the
 * {@code _argv} runtime helper, which scans the buffer WASI's {@code args_sizes_get} /
 * {@code args_get} fill and answers the argument vector (argv0 first) as a list of
 * strings. The five public {@code uiop/image} names are Lisp over it
 * ({@code uiop-image.lisp}); under {@code --component} the primitive itself is the
 * spliced {@code environment.lisp} defun instead.
 */
final class WasmArgvCompiler {

	private WasmArgvCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		if (args.size() != 1) {
			throw new UnsupportedOperationException(
					LispNames.HOST_ARGV + " expects no arguments, got " + (args.size() - 1));
		}
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_ARGV);
	}

}
