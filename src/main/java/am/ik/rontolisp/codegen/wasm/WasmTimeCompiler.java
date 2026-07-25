package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;

/**
 * Compiles the time built-ins for WASM via WASI {@code clock_time_get} (bound to the real
 * host clock in Preview 1 mode, and to {@code wasi:clocks} through the adapter in
 * {@code --component} mode). The result is an exact integer, like the interpreter and JVM
 * backends: the magnitudes (seconds since 1900, milliseconds since the Unix epoch) exceed
 * the {@code i31} fixnum range, so the value normalizes through {@code _int_new} into the
 * boxed exact-integer representation ({@code TYPE_BIGNUM}).
 */
final class WasmTimeCompiler {

	private static final int CLOCK_REALTIME = 0;

	private static final int CLOCK_MONOTONIC = 1;

	// Seconds between the Common Lisp epoch (1900-01-01) and the Unix epoch (1970-01-01).
	private static final long UNIVERSAL_TIME_OFFSET = 2208988800L;

	private static final long NANOS_PER_SECOND = 1_000_000_000L;

	private static final long NANOS_PER_MILLI = 1_000_000L;

	private WasmTimeCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx, String name) {
		List<LispVal> args = cons.toList();
		if (args.size() != 1) {
			throw new UnsupportedOperationException(name + " expects 0 arguments, got " + (args.size() - 1));
		}
		switch (name) {
			case LispNames.GET_UNIVERSAL_TIME -> {
				// nanos / 10^9 + 2208988800, exactly in i64
				emitNanosAsI64(ctx, CLOCK_REALTIME);
				ctx.writer.write(Instruction.I64_CONST);
				ctx.writer.writeSignedLeb128(NANOS_PER_SECOND);
				ctx.writer.write(Instruction.I64_DIV_U);
				ctx.writer.write(Instruction.I64_CONST);
				ctx.writer.writeSignedLeb128(UNIVERSAL_TIME_OFFSET);
				ctx.writer.write(Instruction.I64_ADD);
			}
			case LispNames.GET_INTERNAL_REAL_TIME -> {
				emitNanosAsI64(ctx, CLOCK_REALTIME);
				ctx.writer.write(Instruction.I64_CONST);
				ctx.writer.writeSignedLeb128(NANOS_PER_MILLI);
				ctx.writer.write(Instruction.I64_DIV_U);
			}
			case LispNames.GET_INTERNAL_RUN_TIME -> {
				emitNanosAsI64(ctx, CLOCK_MONOTONIC);
				ctx.writer.write(Instruction.I64_CONST);
				ctx.writer.writeSignedLeb128(NANOS_PER_MILLI);
				ctx.writer.write(Instruction.I64_DIV_U);
			}
			default -> throw new UnsupportedOperationException("Not a time function: " + name);
		}
		// Normalize the i64 into the exact-integer representation (an i31 would only
		// occur for a pre-1970 clock; realistically a TYPE_BIGNUM box).
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.FUNC_INT_NEW);
	}

	// Calls clock_time_get(clockId, precision=1, TIME_SCRATCH_ADDR), then loads the i64
	// nanoseconds, leaving it on the stack.
	private static void emitNanosAsI64(WasmLispCompiler.Ctx ctx, int clockId) {
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(clockId);
		ctx.writer.write(Instruction.I64_CONST);
		ctx.writer.writeSignedLeb128(1); // precision (ignored by the host)
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TIME_SCRATCH_ADDR);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.FUNC_CLOCK_TIME_GET);
		ctx.writer.write(Instruction.DROP); // errno
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TIME_SCRATCH_ADDR);
		ctx.writer.write(Instruction.I64_LOAD, 0x03, 0x00); // 8-byte aligned load
	}

}
