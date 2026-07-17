package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;

/**
 * Compiles the internal {@code rontolisp::%future-new}/{@code %future-settle}/
 * {@code %future-reject} test primitives of the {@code --component} async state machines
 * -- thin bindings of the {@link WasmFutureRuntimeBuilder} runtime, available only in
 * asyncMode, deliberately undocumented and absent from every other backend -- plus the
 * import layer's {@code rontolisp::%subtask-future} (the bridge from an async-lowered
 * call's token to a first-class future, synthesized by {@code WitImportDirective} for an
 * {@code async func} member's binding).
 */
final class WasmFutureInternalCompiler {

	private WasmFutureInternalCompiler() {
	}

	static void compile(String member, LispCons cons, WasmLispCompiler.Ctx ctx) {
		if (ctx.asyncFuncBase < 0) {
			throw new UnsupportedOperationException("rontolisp::" + member
					+ " requires --component and an async program (it is an internal async-runtime binding)");
		}
		List<LispVal> args = cons.toList();
		switch (member) {
			case LispNames.FUTURE_NEW_INTERNAL -> {
				expectArgs(member, args, 0);
				ctx.writer.write(Instruction.CALL);
				ctx.writer.writeSignedLeb128(ctx.asyncFuncBase + WasmFutureRuntimeBuilder.OFF_NEW);
			}
			case LispNames.FUTURE_SETTLE_INTERNAL -> {
				expectArgs(member, args, 2);
				WasmExprCompiler.compileExpr(args.get(1), ctx);
				WasmExprCompiler.compileExpr(args.get(2), ctx);
				ctx.writer.write(Instruction.CALL);
				ctx.writer.writeSignedLeb128(ctx.asyncFuncBase + WasmFutureRuntimeBuilder.OFF_SETTLE);
			}
			case LispNames.FUTURE_REJECT_INTERNAL -> {
				expectArgs(member, args, 2);
				WasmExprCompiler.compileExpr(args.get(1), ctx);
				// The $lisp-cond payload shape is (condition-instance . message); a nil
				// instance makes the catch side synthesize a simple-error from the
				// message, exactly like a plain %error throw.
				ctx.writer.write(Instruction.REF_NULL);
				ctx.writer.writeHeapType(Type.EQ.code());
				WasmExprCompiler.compileExpr(args.get(2), ctx);
				ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
				ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
				ctx.writer.write(Instruction.CALL);
				ctx.writer.writeSignedLeb128(ctx.asyncFuncBase + WasmFutureRuntimeBuilder.OFF_REJECT);
			}
			case LispNames.SUBTASK_FUTURE_INTERNAL -> {
				expectArgs(member, args, 2);
				WasmExprCompiler.compileExpr(args.get(1), ctx);
				WasmExprCompiler.compileExpr(args.get(2), ctx);
				ctx.writer.write(Instruction.CALL);
				ctx.writer.writeSignedLeb128(ctx.asyncFuncBase + WasmFutureRuntimeBuilder.OFF_SUBTASK_FUTURE);
			}
			case LispNames.FUTURE_FORCE_INTERNAL -> {
				// The synchronous force: block on the module scheduler until the future
				// settles, yield its value (a rejection re-signals through _poll, like
				// await). The blocking driver of sockets.lisp's synchronous tcp surface.
				expectArgs(member, args, 1);
				WasmExprCompiler.compileExpr(args.get(1), ctx);
				ctx.writer.write(Instruction.CALL);
				ctx.writer.writeSignedLeb128(ctx.asyncFuncBase + WasmFutureRuntimeBuilder.OFF_SCHED_LOOP);
			}
			case LispNames.WASI_STREAM_NEW_INTERNAL -> {
				expectArgs(member, args, 2);
				// TYPE_WASI_STREAM {eof = 0, readFn, closeFn}
				ctx.writer.write(Instruction.I32_CONST);
				ctx.writer.writeSignedLeb128(0);
				WasmExprCompiler.compileExpr(args.get(1), ctx);
				WasmExprCompiler.compileExpr(args.get(2), ctx);
				ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
				ctx.writer.writeSignedLeb128(ctx.wasiStreamTypeIndex);
			}
			default -> throw new IllegalArgumentException("unknown future internal: " + member);
		}
	}

	private static void expectArgs(String member, List<LispVal> args, int expected) {
		if (args.size() - 1 != expected) {
			throw new UnsupportedOperationException(
					"rontolisp::" + member + " expects " + expected + " arguments, got " + (args.size() - 1));
		}
	}

}
