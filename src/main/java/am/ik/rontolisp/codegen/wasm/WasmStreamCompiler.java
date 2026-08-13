package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;

/**
 * Compiles {@code rontolisp:streamp}/{@code stream-read}/{@code stream-close} over a
 * first-class stream value, in either of the two tiers that has one -- both built by
 * {@code rontolisp::%stream-new} from a read thunk and a close thunk:
 * <ul>
 * <li>asyncMode {@code --component}: a {@code TYPE_WASI_STREAM}, whose chunks http.lisp
 * pulls from the wasi byte-stream built-ins. A chunk the host has in flight comes back as
 * a PENDING future settled by the scheduler's {@code EVENT_STREAM_READ} dispatch, so the
 * task keeps running while it waits (true intra-instance concurrency).</li>
 * <li>Preview 1 / {@code --no-wasi}: a {@code TYPE_P1_STREAM}, whose chunks come from a
 * host import or any Lisp closure. Nothing on that tier can suspend, so the read is a
 * synchronous pull answering a SETTLED future.</li>
 * </ul>
 * Either way {@code stream-read} answers a future settling to the next chunk (nil = EOF,
 * matching the interpreter/JVM contract). Guest-created streams
 * ({@code rontolisp:make-stream}/{@code stream-write}) remain unsupported on both.
 */
final class WasmStreamCompiler {

	private WasmStreamCompiler() {
	}

	static void compile(String member, LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		if (args.size() != 2) {
			throw new UnsupportedOperationException(
					"rontolisp:" + member + " expects 1 argument, got " + (args.size() - 1));
		}
		boolean wasi = ctx.wasiStreamTypeIndex >= 0;
		int streamType = wasi ? ctx.wasiStreamTypeIndex : ctx.p1StreamTypeIndex;
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		switch (member) {
			case LispNames.ASYNC_STREAMP -> {
				ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
				ctx.writer.writeHeapType(streamType);
				WasmEmitHelper.emitBoolFromI32(ctx);
			}
			case LispNames.STREAM_READ -> {
				ctx.writer.write(Instruction.CALL);
				ctx.writer.writeUnsignedLeb128(wasi ? ctx.asyncFuncBase + WasmFutureRuntimeBuilder.OFF_WSTREAM_READ
						: ctx.p1StreamFuncBase + WasmP1StreamRuntimeBuilder.OFF_READ);
			}
			case LispNames.STREAM_CLOSE -> {
				ctx.writer.write(Instruction.CALL);
				ctx.writer.writeUnsignedLeb128(wasi ? ctx.asyncFuncBase + WasmFutureRuntimeBuilder.OFF_WSTREAM_CLOSE
						: ctx.p1StreamFuncBase + WasmP1StreamRuntimeBuilder.OFF_CLOSE);
			}
			default -> throw new IllegalArgumentException("unknown stream member: " + member);
		}
	}

	/**
	 * Compiles {@code rontolisp:streamp} in a module where no stream value can EXIST (no
	 * {@code %stream-new} anywhere, and no async block to produce one): the answer is nil
	 * for every argument, so the operand is compiled for its effects and dropped. The
	 * predicate stays total -- it is {@code stream-read}/{@code stream-close} that get a
	 * call-time error there, because reaching one really is a bug.
	 */
	static void compileStreampConstantNil(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		if (args.size() != 2) {
			throw new UnsupportedOperationException(
					"rontolisp:" + LispNames.ASYNC_STREAMP + " expects 1 argument, got " + (args.size() - 1));
		}
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		ctx.writer.write(Instruction.DROP);
		ctx.writer.write(Instruction.REF_NULL);
		ctx.writer.writeHeapType(am.ik.wasm.Type.EQ.code());
	}

}
