package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;

/**
 * Compiles {@code rontolisp:streamp}/{@code stream-read}/{@code stream-close} on the
 * {@code --component} backend (asyncMode only): the operand is a first-class
 * {@code TYPE_WASI_STREAM} value produced by http.lisp for a fetch/serve body
 * ({@code rontolisp::%wasi-stream-new} over the wasi byte-stream built-ins).
 * {@code stream-read} returns a future settling to the next chunk (nil = EOF, matching
 * the interpreter/JVM contract); a chunk the host has in flight comes back as a PENDING
 * future settled by the scheduler's {@code EVENT_STREAM_READ} dispatch, so the task keeps
 * running while it waits (true intra-instance concurrency). Guest-created streams
 * ({@code rontolisp:make-stream}/{@code stream-write}) remain unsupported here.
 */
final class WasmWasiStreamCompiler {

	private WasmWasiStreamCompiler() {
	}

	static void compile(String member, LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> args = cons.toList();
		if (args.size() != 2) {
			throw new UnsupportedOperationException(
					"rontolisp:" + member + " expects 1 argument, got " + (args.size() - 1));
		}
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		switch (member) {
			case LispNames.ASYNC_STREAMP -> {
				ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
				ctx.writer.writeHeapType(ctx.wasiStreamTypeIndex);
				WasmEmitHelper.emitBoolFromI32(ctx);
			}
			case LispNames.STREAM_READ -> {
				ctx.writer.write(Instruction.CALL);
				ctx.writer.writeSignedLeb128(ctx.asyncFuncBase + WasmFutureRuntimeBuilder.OFF_WSTREAM_READ);
			}
			case LispNames.STREAM_CLOSE -> {
				ctx.writer.write(Instruction.CALL);
				ctx.writer.writeSignedLeb128(ctx.asyncFuncBase + WasmFutureRuntimeBuilder.OFF_WSTREAM_CLOSE);
			}
			default -> throw new IllegalArgumentException("unknown stream member: " + member);
		}
	}

}
