package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;

/**
 * Compiles the internal {@code rontolisp::%async-run} primitive (the lowered
 * {@code rontolisp:async-defun}/{@code async-lambda} body) outside asyncMode. Preview 1
 * has no asynchronous execution of its own -- nothing can genuinely suspend, so the body
 * thunk runs to completion right here through the arity-0 dispatch function, and its
 * value is wrapped in a settled (kind 2) {@code TYPE_P1_FUTURE} struct -- the degenerate
 * future that keeps the cross-backend surface identical. (A {@code --component} program
 * with an async surface is asyncMode and compiles through the {@code WasmAsyncEmit} state
 * machines instead.)
 */
final class WasmAsyncRunCompiler {

	private WasmAsyncRunCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		if (args.size() != 2) {
			throw new UnsupportedOperationException("%async-run expects 1 argument, got " + (args.size() - 1));
		}
		ctx.indirectCallArities.add(0);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(2); // kind 2: settled
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_DISPATCH_BASE);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_P1_FUTURE);
	}

}
