package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.macro.LispMacroExpander;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;

/**
 * Compiles the time built-ins for WASM via WASI {@code clock_time_get} (bound to the real
 * host clock in Preview 1 mode, and to {@code wasi:clocks} through the adapter in
 * {@code --component} mode), or -- under {@code --no-wasi}, which imports no clock at all
 * -- from the cell the host writes through the exported {@code __ronto_set_time} hook.
 * The result is an exact integer, like the interpreter and JVM backends: the magnitudes
 * (seconds since 1900, milliseconds since the Unix epoch) exceed the {@code i31} fixnum
 * range, so the value normalizes through {@code _int_new} into the boxed exact-integer
 * representation ({@code TYPE_BIGNUM}).
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
		if (ctx.noWasi) {
			compileFromHostCell(ctx, name);
			return;
		}
		emitReading(ctx, name, false);
	}

	/**
	 * The {@code --no-wasi} shape: the module imports no clock, so the time is whatever
	 * the host handed to {@code __ronto_set_time} before calling in
	 * ({@code WasmIoRuntimeBuilder.buildSetTimeBody}). An untouched cell reads zero,
	 * which is not "no time" but 1970 -- the one answer a program could not tell from a
	 * real reading -- so that case SIGNALS a catchable Lisp condition naming the operator
	 * and the hook, instead of reporting the epoch or trapping namelessly. A library that
	 * timestamps while it loads therefore loads on a host that sets the clock, and still
	 * degrades through {@code ignore-errors} on one that does not.
	 *
	 * <p>
	 * The message differs on the reactor COMPONENT, where the cell can only ever be zero:
	 * that shape exposes no hook (its top level runs at instantiation, so there is no
	 * window before the load-time reads), and pointing at one that does not exist would
	 * be worse than saying so.
	 * @param ctx the compilation context
	 * @param name the built-in being compiled
	 */
	private static void compileFromHostCell(WasmLispCompiler.Ctx ctx, String name) {
		if (ctx.reactorComponent) {
			// The cell can only ever be zero here, so there is nothing to branch on:
			// this shape carries no hook to fill it.
			WasmExprCompiler.compileExpr(LispMacroExpander.callTimeUnsupportedStub(name
					+ " has no clock to read: a --no-wasi reactor component imports nothing, and it exposes no way"
					+ " to hand a time in -- its top level runs at instantiation, so there is no window before the"
					+ " first read. Compile without --component for the __ronto_set_time hook"), ctx);
			return;
		}
		String message = name
				+ " has no clock to read: a --no-wasi module imports none, so its time is whatever the host"
				+ " hands to the exported __ronto_set_time hook (nanoseconds since the Unix epoch), and"
				+ " nothing has called it";
		loadHostNanos(ctx);
		ctx.writer.write(Instruction.I64_EQZ);
		ctx.writer.write(Instruction.IF);
		ctx.writer.writeRefType(true, Type.EQ.code());
		// The branches are compiled inside the if structure; track the depth like
		// WasmIfCompiler so a br out of the signalling branch counts the same frames.
		ctx.wasmCtrlDepth++;
		WasmExprCompiler.compileExpr(LispMacroExpander.callTimeUnsupportedStub(message), ctx);
		ctx.writer.write(Instruction.ELSE);
		emitReading(ctx, name, true);
		ctx.wasmCtrlDepth--;
		ctx.writer.write(Instruction.END);
	}

	// The arithmetic over a nanosecond reading, shared by both sources: seconds since
	// 1900 for get-universal-time, milliseconds for the two internal clocks. Under
	// --no-wasi all three read the ONE cell the host set -- there is no second clock to
	// distinguish a monotonic reading from a wall-clock one, and a module that cannot
	// advance its own clock reports the same instant for both, which is the truth about
	// it rather than an invented number.
	private static void emitReading(WasmLispCompiler.Ctx ctx, String name, boolean fromHostCell) {
		switch (name) {
			case LispNames.GET_UNIVERSAL_TIME -> {
				// nanos / 10^9 + 2208988800, exactly in i64
				emitNanos(ctx, CLOCK_REALTIME, fromHostCell);
				ctx.writer.write(Instruction.I64_CONST);
				ctx.writer.writeSignedLeb128(NANOS_PER_SECOND);
				ctx.writer.write(Instruction.I64_DIV_U);
				ctx.writer.write(Instruction.I64_CONST);
				ctx.writer.writeSignedLeb128(UNIVERSAL_TIME_OFFSET);
				ctx.writer.write(Instruction.I64_ADD);
			}
			case LispNames.GET_INTERNAL_REAL_TIME -> {
				emitNanos(ctx, CLOCK_REALTIME, fromHostCell);
				ctx.writer.write(Instruction.I64_CONST);
				ctx.writer.writeSignedLeb128(NANOS_PER_MILLI);
				ctx.writer.write(Instruction.I64_DIV_U);
			}
			case LispNames.GET_INTERNAL_RUN_TIME -> {
				emitNanos(ctx, CLOCK_MONOTONIC, fromHostCell);
				ctx.writer.write(Instruction.I64_CONST);
				ctx.writer.writeSignedLeb128(NANOS_PER_MILLI);
				ctx.writer.write(Instruction.I64_DIV_U);
			}
			default -> throw new UnsupportedOperationException("Not a time function: " + name);
		}
		// Normalize the i64 into the exact-integer representation (an i31 would only
		// occur for a pre-1970 clock; realistically a TYPE_BIGNUM box).
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_INT_NEW);
	}

	private static void emitNanos(WasmLispCompiler.Ctx ctx, int clockId, boolean fromHostCell) {
		if (fromHostCell) {
			loadHostNanos(ctx);
		}
		else {
			emitNanosAsI64(ctx, clockId);
		}
	}

	// Leaves the host-set nanosecond cell on the stack as an i64.
	private static void loadHostNanos(WasmLispCompiler.Ctx ctx) {
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.HOST_TIME_ADDR);
		ctx.writer.write(Instruction.I64_LOAD, 0x03, 0x00); // 8-byte aligned load
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
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_CLOCK_TIME_GET);
		ctx.writer.write(Instruction.DROP); // errno
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TIME_SCRATCH_ADDR);
		ctx.writer.write(Instruction.I64_LOAD, 0x03, 0x00); // 8-byte aligned load
	}

}
