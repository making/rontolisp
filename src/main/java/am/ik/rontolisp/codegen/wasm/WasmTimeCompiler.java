package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;

/**
 * Compiles the time built-ins for WASM via WASI {@code clock_time_get} (bound to the real
 * host clock in Preview 1 mode, and to {@code wasi:clocks} through the adapter in
 * {@code --component} mode). Because WASM integers are 31-bit ({@code i31ref}) and cannot
 * hold the magnitudes involved (seconds since 1900, or milliseconds since the Unix
 * epoch), the result is returned as a floating-point number (a {@code float_struct}); an
 * {@code f64} represents these values exactly. This differs from the interpreter and JVM
 * backends, which return an integer.
 */
final class WasmTimeCompiler {

	private static final int CLOCK_REALTIME = 0;

	private static final int CLOCK_MONOTONIC = 1;

	// Seconds between the Common Lisp epoch (1900-01-01) and the Unix epoch (1970-01-01).
	private static final double UNIVERSAL_TIME_OFFSET = 2208988800.0;

	private static final double NANOS_PER_SECOND = 1.0e9;

	private static final double NANOS_PER_MILLI = 1.0e6;

	private WasmTimeCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx, String name) {
		List<LispVal> args = cons.toList();
		if (args.size() != 1) {
			throw new UnsupportedOperationException(name + " expects 0 arguments, got " + (args.size() - 1));
		}
		switch (name) {
			case LispNames.GET_UNIVERSAL_TIME -> {
				// nanos / 1e9 + 2208988800, as a float
				emitNanosAsF64(ctx, CLOCK_REALTIME);
				ctx.writer.write(Instruction.F64_CONST);
				ctx.writer.writeF64(NANOS_PER_SECOND);
				ctx.writer.write(Instruction.F64_DIV);
				ctx.writer.write(Instruction.F64_CONST);
				ctx.writer.writeF64(UNIVERSAL_TIME_OFFSET);
				ctx.writer.write(Instruction.F64_ADD);
			}
			case LispNames.GET_INTERNAL_REAL_TIME -> {
				emitNanosAsF64(ctx, CLOCK_REALTIME);
				ctx.writer.write(Instruction.F64_CONST);
				ctx.writer.writeF64(NANOS_PER_MILLI);
				ctx.writer.write(Instruction.F64_DIV);
			}
			case LispNames.GET_INTERNAL_RUN_TIME -> {
				emitNanosAsF64(ctx, CLOCK_MONOTONIC);
				ctx.writer.write(Instruction.F64_CONST);
				ctx.writer.writeF64(NANOS_PER_MILLI);
				ctx.writer.write(Instruction.F64_DIV);
			}
			default -> throw new UnsupportedOperationException("Not a time function: " + name);
		}
		// Box the f64 as a float_struct.
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_FLOAT);
	}

	// Calls clock_time_get(clockId, precision=1, TIME_SCRATCH_ADDR), then loads the i64
	// nanoseconds and converts it to f64, leaving the f64 on the stack.
	private static void emitNanosAsF64(WasmLispCompiler.Ctx ctx, int clockId) {
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
		ctx.writer.write(Instruction.F64_CONVERT_U_I64);
	}

}
