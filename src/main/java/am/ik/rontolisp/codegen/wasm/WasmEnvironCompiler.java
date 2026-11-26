package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;

/**
 * Compiles the {@code %host-environ} internal primitive for WASM Preview 1: a call of the
 * {@code _getenv} runtime helper with a nil name, which answers the whole WASI environ
 * buffer as a list of {@code "NAME=value"} strings ({@link WasmGetenvRuntimeBuilder}).
 * Under {@code --component} the primitive itself is the spliced {@code environment.lisp}
 * defun instead; under {@code --no-wasi} the environment is the empty one the stub
 * imports answer, so the list is empty.
 */
final class WasmEnvironCompiler {

	private WasmEnvironCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		if (args.size() != 1) {
			throw new UnsupportedOperationException(
					LispNames.HOST_ENVIRON + " expects no arguments, got " + (args.size() - 1));
		}
		ctx.writer.write(Instruction.REF_NULL);
		ctx.writer.writeHeapType(Type.EQ.code());
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_GETENV);
	}

}
