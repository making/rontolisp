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
 * {@code async func} member's binding), plus the one member that is neither:
 * {@code rontolisp::%stream-new}, which builds a first-class stream value in whichever
 * tier this module has one (see {@link WasmStreamCompiler}).
 */
final class WasmFutureInternalCompiler {

	private WasmFutureInternalCompiler() {
	}

	static void compile(String member, LispCons cons, WasmLispCompiler.Ctx ctx) {
		if (LispNames.STREAM_NEW_INTERNAL.equals(member)) {
			// The one member that is not async-runtime plumbing: a stream is a read
			// thunk, a close thunk and a drained flag, and both tiers that have a
			// first-class stream value build it from exactly that -- the asyncMode
			// TYPE_WASI_STREAM and the degenerate TYPE_P1_STREAM have the same shape.
			List<LispVal> streamArgs = cons.toList();
			expectArgs(member, streamArgs, 2);
			int streamType = ctx.wasiStreamTypeIndex >= 0 ? ctx.wasiStreamTypeIndex : ctx.p1StreamTypeIndex;
			if (streamType < 0) {
				// Only reachable when the gate that decides the type disagrees with this
				// call site (both read the same %stream-new occurrence), i.e. a compiler
				// bug rather than a program error.
				throw new IllegalStateException("rontolisp::" + member + " has no stream type in this module");
			}
			ctx.writer.write(Instruction.I32_CONST);
			ctx.writer.writeSignedLeb128(0); // eof = 0
			WasmExprCompiler.compileExpr(streamArgs.get(1), ctx);
			WasmExprCompiler.compileExpr(streamArgs.get(2), ctx);
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
			ctx.writer.writeUnsignedLeb128(streamType);
			return;
		}
		if (ctx.asyncFuncBase < 0) {
			// %future-force is the one member with a meaning OUTSIDE asyncMode: every
			// non-asyncMode future is the degenerate settled TYPE_P1_FUTURE, so the
			// synchronous resolve IS the P1 await pass-through (non-futures included) --
			// which is what lets the host-driven reactor transport resolve a
			// future-valued application answer at its boundary.
			if (LispNames.FUTURE_FORCE_INTERNAL.equals(member)) {
				List<LispVal> forceArgs = cons.toList();
				expectArgs(member, forceArgs, 1);
				WasmExprCompiler.compileExpr(forceArgs.get(1), ctx);
				ctx.writer.write(Instruction.CALL);
				ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_P1_FUTURE_AWAIT);
				return;
			}
			throw new UnsupportedOperationException("rontolisp::" + member
					+ " requires --component and an async program (it is an internal async-runtime binding)");
		}
		List<LispVal> args = cons.toList();
		switch (member) {
			case LispNames.FUTURE_NEW_INTERNAL -> {
				expectArgs(member, args, 0);
				ctx.writer.write(Instruction.CALL);
				ctx.writer.writeUnsignedLeb128(ctx.asyncFuncBase + WasmFutureRuntimeBuilder.OFF_NEW);
			}
			case LispNames.FUTURE_SETTLE_INTERNAL -> {
				expectArgs(member, args, 2);
				WasmExprCompiler.compileExpr(args.get(1), ctx);
				WasmExprCompiler.compileExpr(args.get(2), ctx);
				ctx.writer.write(Instruction.CALL);
				ctx.writer.writeUnsignedLeb128(ctx.asyncFuncBase + WasmFutureRuntimeBuilder.OFF_SETTLE);
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
				ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
				ctx.writer.write(Instruction.CALL);
				ctx.writer.writeUnsignedLeb128(ctx.asyncFuncBase + WasmFutureRuntimeBuilder.OFF_REJECT);
			}
			case LispNames.SUBTASK_FUTURE_INTERNAL -> {
				expectArgs(member, args, 2);
				WasmExprCompiler.compileExpr(args.get(1), ctx);
				WasmExprCompiler.compileExpr(args.get(2), ctx);
				ctx.writer.write(Instruction.CALL);
				ctx.writer.writeUnsignedLeb128(ctx.asyncFuncBase + WasmFutureRuntimeBuilder.OFF_SUBTASK_FUTURE);
			}
			case LispNames.FUTURE_FORCE_INTERNAL -> {
				// The synchronous force: block on the module scheduler until the future
				// settles, yield its value (a rejection re-signals through _poll, like
				// await). The blocking driver of sockets.lisp's synchronous tcp surface.
				expectArgs(member, args, 1);
				WasmExprCompiler.compileExpr(args.get(1), ctx);
				ctx.writer.write(Instruction.CALL);
				ctx.writer.writeUnsignedLeb128(ctx.asyncFuncBase + WasmFutureRuntimeBuilder.OFF_SCHED_LOOP);
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
