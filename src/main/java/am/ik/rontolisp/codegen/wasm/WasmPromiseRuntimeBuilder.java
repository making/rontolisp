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
 * <li>kind 0 (fetch root): calls the adapter's {@code http.fetch-await} import with the
 * i31-boxed wasi:http future handle and builds the
 * {@code (:status N :body "..." :headers ((name . value)...))} property list from the
 * status / body / response-header buffers the adapter wrote back (a non-zero errno, e.g.
 * a refused connection, yields {@code nil} -- this backend's nil-on-failure
 * convention).</li>
 * <li>kind 1 ({@code rontolisp:then} chain): recursively awaits the base, applies the
 * callback through the arity-1 dispatch function, and recursively awaits the result so a
 * promise-returning callback flattens, like JavaScript {@code then}.</li>
 * <li>kind 2 (settled): returns the memoized value.</li>
 * </ul>
 * After resolving, the struct is rewritten in place to kind 2 with the result, so the
 * wasi:http response (which {@code future-incoming-response.get} hands out only once) and
 * a chain callback are each consumed exactly once however often the promise is awaited --
 * matching the interpreter/JVM, where {@code join()} is idempotent and chains memoize.
 * Recursion is why this is a real function rather than inline code at each await site
 * (see {@code WasmAwaitCompiler}).
 */
final class WasmPromiseRuntimeBuilder {

	private WasmPromiseRuntimeBuilder() {
	}

	static byte[] buildAwait(WasmLispCompiler.StringTable stringTable) {
		WasmLispCompiler.StringTable.StringEntry statusSym = stringTable.addString(":status");
		WasmLispCompiler.StringTable.StringEntry bodySym = stringTable.addString(":body");
		WasmLispCompiler.StringTable.StringEntry headersSym = stringTable.addString(":headers");

		final ByteArrayOutputStream body = new ByteArrayOutputStream();
		final WasmWriter w = new WasmWriter(body);
		final int V = 0, KIND = 1, RESULT = 2, STATUS = 3, BODY = 4, HDR = 5, ACC = 6;
		// locals: 1x i32 (KIND), 5x (ref null eq) (RESULT..ACC)
		w.write(2);
		w.writeUnsignedLeb128(1);
		w.write(Type.I32);
		w.writeUnsignedLeb128(5);
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

		// kind 0: fetch root -> http.fetch-await(handle, status, rhdrPtr, rhdrLen,
		// bodyPtr, bodyLen)
		getLocal(w, KIND);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF, 0x40);
		promiseField(w, V, 1);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(Type.I31.code());
		w.write(Instruction.GC_PREFIX, Instruction.I31_GET_S);
		i32(w, WasmLispCompiler.FETCH_STATUS_ADDR);
		i32(w, WasmLispCompiler.FETCH_RHDR_PTR_ADDR);
		i32(w, WasmLispCompiler.FETCH_RHDR_LEN_ADDR);
		i32(w, WasmLispCompiler.FETCH_BODY_PTR_ADDR);
		i32(w, WasmLispCompiler.FETCH_BODY_LEN_ADDR);
		call(w, WasmLispCompiler.FUNC_FETCH_AWAIT);
		// On a non-zero errno the adapter has not written the out-params, so the result
		// stays nil (RESULT defaults to null) instead of reading uninitialized buffers.
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF, 0x40);
		// status = i31(memory[FETCH_STATUS_ADDR])
		i32(w, WasmLispCompiler.FETCH_STATUS_ADDR);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		w.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		setLocal(w, STATUS);
		// body = _fetch_str(memory[FETCH_BODY_PTR_ADDR], memory[FETCH_BODY_LEN_ADDR])
		i32(w, WasmLispCompiler.FETCH_BODY_PTR_ADDR);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		i32(w, WasmLispCompiler.FETCH_BODY_LEN_ADDR);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		call(w, WasmLispCompiler.FUNC_FETCH_STR);
		setLocal(w, BODY);
		// headers = _fetch_deser_headers(memory[FETCH_RHDR_PTR_ADDR])
		i32(w, WasmLispCompiler.FETCH_RHDR_PTR_ADDR);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		call(w, WasmLispCompiler.FUNC_FETCH_DESER_HDRS);
		setLocal(w, HDR);
		// Build (:status status :body body :headers headers) tail-first.
		w.write(Instruction.REF_NULL);
		w.writeHeapType(Type.EQ.code());
		setLocal(w, ACC);
		consLocal(w, HDR, ACC);
		setLocal(w, ACC);
		consSymCdr(w, headersSym, ACC);
		setLocal(w, ACC);
		consLocal(w, BODY, ACC);
		setLocal(w, ACC);
		consSymCdr(w, bodySym, ACC);
		setLocal(w, ACC);
		consLocal(w, STATUS, ACC);
		setLocal(w, ACC);
		consSymCdr(w, statusSym, ACC);
		setLocal(w, RESULT);
		w.write(Instruction.END);
		settleAndReturn(w, V, RESULT);
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

	// Pushes cons(local carSlot, local cdrSlot).
	private static void consLocal(WasmWriter w, int carSlot, int cdrSlot) {
		getLocal(w, carSlot);
		getLocal(w, cdrSlot);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
	}

	// Pushes cons(<keyword symbol>, local cdrSlot).
	private static void consSymCdr(WasmWriter w, WasmLispCompiler.StringTable.StringEntry sym, int cdrSlot) {
		i32(w, sym.offset());
		i32(w, sym.length());
		WasmEmitHelper.emitStrBuildCall(w);
		getLocal(w, cdrSlot);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
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
