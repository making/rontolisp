package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;

/**
 * Compiles {@code rontolisp:await}, the generic promise resolver: the argument is
 * evaluated and handed to the {@code _promise_await} runtime helper
 * ({@link WasmPromiseRuntimeBuilder}), which passes a non-promise value through
 * unchanged, resolves a {@code rontolisp:fetch} root (component mode only at runtime -- a
 * root promise cannot exist without fetch), applies {@code rontolisp:then} callbacks, and
 * memoizes the settled value into the promise struct. Unlike fetch, await compiles in
 * every mode (Preview 1 included).
 */
final class WasmAwaitCompiler {

	private WasmAwaitCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		if (args.size() != 2) {
			throw new UnsupportedOperationException("await expects 1 argument, got " + (args.size() - 1));
		}
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.FUNC_PROMISE_AWAIT);
	}

}
