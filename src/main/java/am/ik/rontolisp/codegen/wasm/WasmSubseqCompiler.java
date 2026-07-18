package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;

/**
 * Compiles {@code subseq} for strings: {@code (subseq string start [end])}. The string,
 * start index and end index (or nil when omitted) are passed to the {@code _subseq}
 * runtime helper, which copies the requested content range into a fresh heap string.
 */
final class WasmSubseqCompiler {

	private WasmSubseqCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		// A mutable character vector normalizes to a string first, so subseq of one
		// yields a fresh immutable string of its active content.
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		WasmEmitHelper.emitCharvecToStrCall(ctx);
		WasmExprCompiler.compileExpr(args.get(2), ctx);
		if (args.size() >= 4) {
			WasmExprCompiler.compileExpr(args.get(3), ctx);
		}
		else {
			// No end: pass nil so the helper defaults to the content length.
			ctx.writer.write(Instruction.REF_NULL);
			ctx.writer.writeHeapType(Type.EQ.code());
		}
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.FUNC_SUBSEQ);
	}

}
