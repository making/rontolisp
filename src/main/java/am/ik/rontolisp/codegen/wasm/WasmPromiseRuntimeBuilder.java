package am.ik.rontolisp.codegen.wasm;

import java.io.ByteArrayOutputStream;

import am.ik.wasm.Instruction;
import am.ik.wasm.Type;
import am.ik.wasm.WasmWriter;

/**
 * Builds {@code _promise_await((ref null eq)) -> (ref null eq)}
 * ({@code FUNC_PROMISE_AWAIT}), the generic {@code rontolisp:await} resolver. A
 * non-promise value passes through unchanged (like JavaScript await). A
 * {@code TYPE_PROMISE} struct is resolved by kind:
 * <ul>
 * <li>kind 1 ({@code rontolisp:then} chain): recursively awaits the base, applies the
 * callback through the arity-1 dispatch function, and recursively awaits the result so a
 * promise-returning callback flattens, like JavaScript {@code then}.</li>
 * <li>kind 2 (settled): returns the memoized value.</li>
 * </ul>
 * After resolving, the struct is rewritten in place to kind 2 with the result, so a chain
 * callback is consumed exactly once however often the promise is awaited -- matching the
 * interpreter/JVM, where {@code join()} is idempotent and chains memoize. Recursion is
 * why this is a real function rather than inline code at each await site (see
 * {@code WasmAwaitCompiler}). (An async wit-imported call, e.g. http.lisp's
 * {@code client.send}, is a kind-1 chain whose base is the start wrapper's subtask token
 * and whose callback is the generated await wrapper -- there is no dedicated promise kind
 * for it.)
 */
final class WasmPromiseRuntimeBuilder {

	private WasmPromiseRuntimeBuilder() {
	}

	static byte[] buildAwait() {
		final ByteArrayOutputStream body = new ByteArrayOutputStream();
		final WasmWriter w = new WasmWriter(body);
		final int V = 0, KIND = 1, RESULT = 2;
		// locals: 1x i32 (KIND), 1x (ref null eq) (RESULT)
		w.write(2);
		w.writeUnsignedLeb128(1);
		w.write(Type.I32);
		w.writeUnsignedLeb128(1);
		w.write(Type.REFNULL.code());
		w.writeHeapType(Type.EQ.code());

		// A non-promise value passes through unchanged.
		getLocal(w, V);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_PROMISE);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF, 0x40);
		getLocal(w, V);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);

		// kind = v.kind
		promiseField(w, V, 0);
		setLocal(w, KIND);

		// kind 2: settled -> the memoized value
		getLocal(w, KIND);
		i32(w, 2);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.IF, 0x40);
		promiseField(w, V, 1);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);

		// kind 1: then chain -> RESULT =
		// _promise_await(dispatch_1(fn, _promise_await(base)))
		promiseField(w, V, 2); // fn
		promiseField(w, V, 1); // base
		call(w, WasmLispCompiler.FUNC_PROMISE_AWAIT);
		call(w, WasmLispCompiler.FUNC_DISPATCH_BASE + 1);
		call(w, WasmLispCompiler.FUNC_PROMISE_AWAIT);
		setLocal(w, RESULT);
		// Clear the callback reference too, so a settled chain does not pin the closure.
		castPromise(w, V);
		w.write(Instruction.REF_NULL);
		w.writeHeapType(Type.EQ.code());
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_SET);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_PROMISE);
		w.writeSignedLeb128(2);
		settleAndReturn(w, V, RESULT);
		w.write(Instruction.END); // function
		return body.toByteArray();
	}

	// Marks the promise in local vSlot settled (kind=2, base=RESULT) and leaves the
	// result on the stack as the function result via RETURN.
	private static void settleAndReturn(WasmWriter w, int vSlot, int resultSlot) {
		castPromise(w, vSlot);
		i32(w, 2);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_SET);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_PROMISE);
		w.writeSignedLeb128(0);
		castPromise(w, vSlot);
		getLocal(w, resultSlot);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_SET);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_PROMISE);
		w.writeSignedLeb128(1);
		getLocal(w, resultSlot);
		w.write(Instruction.RETURN);
	}

	// Pushes field fieldIdx of the promise in local vSlot.
	private static void promiseField(WasmWriter w, int vSlot, int fieldIdx) {
		castPromise(w, vSlot);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_PROMISE);
		w.writeSignedLeb128(fieldIdx);
	}

	private static void castPromise(WasmWriter w, int vSlot) {
		getLocal(w, vSlot);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_PROMISE);
	}

	private static void getLocal(WasmWriter w, int slot) {
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(slot);
	}

	private static void setLocal(WasmWriter w, int slot) {
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(slot);
	}

	private static void i32(WasmWriter w, int value) {
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(value);
	}

	private static void call(WasmWriter w, int func) {
		w.write(Instruction.CALL);
		w.writeSignedLeb128(func);
	}

}
