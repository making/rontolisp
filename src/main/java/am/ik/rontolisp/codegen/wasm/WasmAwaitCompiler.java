package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;

/**
 * Compiles {@code rontolisp:await}, the generic future resolver of the degenerate
 * (non-asyncMode) tier: the argument is evaluated and handed to the
 * {@code _p1_future_await} runtime helper ({@link WasmP1FutureRuntimeBuilder}), which
 * passes a non-future value through unchanged and resolves a settled
 * {@code TYPE_P1_FUTURE} to its memoized value. Unlike fetch, await compiles in every
 * mode (Preview 1 included).
 */
final class WasmAwaitCompiler {

	private WasmAwaitCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		if (ctx.asyncResume != null) {
			// asyncMode state machines: the await is a suspend point of the enclosing
			// resume function (WasmAsyncEmit).
			WasmAsyncEmit.compileAwait(cons, ctx);
			return;
		}
		List<LispVal> args = cons.toList();
		if (args.size() != 2) {
			throw new UnsupportedOperationException("await expects 1 argument, got " + (args.size() - 1));
		}
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.FUNC_P1_FUTURE_AWAIT);
	}

}
