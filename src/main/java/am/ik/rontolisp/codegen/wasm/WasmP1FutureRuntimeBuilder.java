package am.ik.rontolisp.codegen.wasm;

import java.io.ByteArrayOutputStream;

import am.ik.wasm.Instruction;
import am.ik.wasm.WasmWriter;

/**
 * Builds {@code _p1_future_await((ref null eq)) -> (ref null eq)}
 * ({@code FUNC_P1_FUTURE_AWAIT}), the generic {@code rontolisp:await} resolver of the
 * degenerate (non-asyncMode) tier. A non-future value passes through unchanged (like
 * JavaScript await). A {@code TYPE_P1_FUTURE} struct is always settled (its only
 * producer, {@code %async-run}, runs the body to completion and wraps the value), so
 * await returns the memoized value, recursively awaited so nested futures flatten.
 * Recursion is why this is a real function rather than inline code at each await site
 * (see {@code WasmAwaitCompiler}).
 */
final class WasmP1FutureRuntimeBuilder {

	private WasmP1FutureRuntimeBuilder() {
	}

	static byte[] buildAwait() {
		final ByteArrayOutputStream body = new ByteArrayOutputStream();
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
