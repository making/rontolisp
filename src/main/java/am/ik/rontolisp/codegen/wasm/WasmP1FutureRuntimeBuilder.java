package am.ik.rontolisp.codegen.wasm;

import java.io.ByteArrayOutputStream;

import am.ik.wasm.Instruction;
import am.ik.wasm.WasmWriter;

/**
 * Builds {@code _p1_future_await((ref null eq)) -> (ref null eq)}
 * ({@code FUNC_P1_FUTURE_AWAIT}), the generic {@code rontolisp:await} resolver of the
 * degenerate (non-asyncMode) tier. A non-future value passes through unchanged (like
 * JavaScript await). A settled {@code TYPE_P1_FUTURE} ({@link #KIND_SETTLED}: what
 * {@code %async-run}, an {@code :async t} import wrapper and a stream read produce)
 * answers its memoized value, recursively awaited so nested futures flatten. Recursion is
 * why this is a real function rather than inline code at each await site (see
 * {@code WasmAwaitCompiler}).
 *
 * <p>
 * A module that names {@code rontolisp::%future-deferred} also meets a DEFERRED one
 * ({@link #KIND_DEFERRED}), whose value field is a thunk: every await calls it through
 * the arity-0 dispatch and awaits what it answers, so the future settles where it is
 * first awaited -- and a thunk that signals makes the await signal, which is how a
 * {@code --native} fetch reports a transport failure at the await rather than at the
 * call. Any other module's body is byte-identical to what it was before the kind existed.
 */
final class WasmP1FutureRuntimeBuilder {

	/** The {@code kind} of a future settled at creation. */
	static final int KIND_SETTLED = 2;

	/** The {@code kind} of a future settled by its thunk at each await. */
	static final int KIND_DEFERRED = 3;

	private WasmP1FutureRuntimeBuilder() {
	}

	static byte[] buildAwait(boolean deferred) {
		final ByteArrayOutputStream body = new am.ik.wasm.UnsynchronizedByteArrayOutputStream();
		final WasmWriter w = new WasmWriter(body);
		final int V = 0;
		w.write(0); // no locals

		// A non-future value passes through unchanged.
		getLocal(w, V);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_P1_FUTURE);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF, 0x40);
		getLocal(w, V);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);

		if (deferred) {
			// Deferred: run the thunk (it keeps its own answer) and await that.
			futureField(w, V, 0);
			w.write(Instruction.I32_CONST);
			w.writeSignedLeb128(KIND_DEFERRED);
			w.write(Instruction.I32_EQ);
			w.write(Instruction.IF, 0x40);
			futureField(w, V, 1);
			call(w, WasmLispCompiler.FUNC_DISPATCH_BASE);
			call(w, WasmLispCompiler.FUNC_P1_FUTURE_AWAIT);
			w.write(Instruction.RETURN);
			w.write(Instruction.END);
		}

		// Settled: the memoized value, recursively awaited so a nested settled future
		// (an async body returning another async call's future) flattens like
		// JavaScript await; a non-future value returns immediately.
		futureField(w, V, 1);
		call(w, WasmLispCompiler.FUNC_P1_FUTURE_AWAIT);
		w.write(Instruction.END); // function
		return body.toByteArray();
	}

	// Pushes field fieldIdx of the future in local vSlot.
	private static void futureField(WasmWriter w, int vSlot, int fieldIdx) {
		getLocal(w, vSlot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_P1_FUTURE);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_P1_FUTURE);
		w.writeUnsignedLeb128(fieldIdx);
	}

	private static void getLocal(WasmWriter w, int slot) {
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(slot);
	}

	private static void call(WasmWriter w, int func) {
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(func);
	}

}
