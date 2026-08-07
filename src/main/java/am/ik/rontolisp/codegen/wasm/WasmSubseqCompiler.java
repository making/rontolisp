package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.macro.LispMacroExpander;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;

/**
 * Compiles {@code subseq} for strings and cons chains: {@code (subseq seq start [end])}.
 *
 * <p>
 * A general array runs through the {@link LispMacroExpander#expandSubseqCompat} rewrite
 * that dispatches on {@link am.ik.rontolisp.LispNames#ARRAYP_INTERNAL} and copies the
 * requested range into a fresh {@code make-array}; the string/list arm remains this
 * class's {@link am.ik.rontolisp.LispNames#SUBSEQ_CORE} lane, which calls the
 * {@code _subseq} runtime helper.
 */
final class WasmSubseqCompiler {

	private WasmSubseqCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		LispVal rewritten = LispMacroExpander.expandSubseqCompat(cons, true,
				ctx.functions.containsKey(LispNames.SUBSEQ_RUNTIME));
		if (rewritten != null) {
			WasmExprCompiler.compileExpr(rewritten, ctx);
			return;
		}
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
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_SUBSEQ);
	}

}
