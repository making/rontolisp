package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;
import am.ik.wasm.WasmWriter;

/**
 * Compiles {@code rontolisp:await} for the WASM component backend. The promise is the
 * in-flight request handle returned by {@code rontolisp:fetch}
 * ({@link WasmFetchCompiler}) boxed as an i31 integer; this compiler unboxes it, calls
 * the adapter's {@code http.fetch-await} import (which blocks on the wasi:http response
 * pollable and reads the full response), then builds the
 * {@code (:status N :body "..." :headers ((name . value)...))} property list from the
 * status / body / response-header buffers the adapter wrote back.
 *
 * <p>
 * wasi:http hands out a response only once ({@code future-incoming-response.get} consumes
 * it), but a settled promise must be awaitable repeatedly to match the interpreter/JVM
 * (where {@code join()} is idempotent). So every successful await pushes
 * {@code cons(promise, plist)} onto the {@code GLOBAL_PROMISE_CACHE} alist and the
 * emitted code checks that cache (by {@code ref.eq} on the i31 handle) before calling the
 * adapter. For the handle to be a valid cache key it must never be reused, which is why
 * the adapter keeps settled futures alive instead of dropping them (wasmtime recycles
 * handle indices after a drop).
 *
 * <p>
 * await is component-only, like fetch. A {@code nil} promise (a fetch that could not be
 * started) and a failed request (e.g. a refused connection) both yield {@code nil},
 * matching the nil-on-failure convention of this backend.
 */
final class WasmAwaitCompiler {

	private WasmAwaitCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		if (!ctx.component) {
			throw new UnsupportedOperationException(
					"rontolisp:await is only available in WASI 0.2 component mode (--component), not Preview 1 WASM");
		}
		List<LispVal> args = cons.toList();
		if (args.size() != 2) {
			throw new UnsupportedOperationException("await expects 1 argument, got " + (args.size() - 1));
		}
		final WasmWriter w = ctx.writer;
		int promiseTmp = ctx.allocTemp();
		int statusTmp = ctx.allocTemp();
		int bodyTmp = ctx.allocTemp();
		int hdrTmp = ctx.allocTemp();
		int accTmp = ctx.allocTemp();
		int curTmp = ctx.allocTemp();
		int entryTmp = ctx.allocTemp();
		int resultTmp = ctx.allocTemp();
		WasmLispCompiler.StringTable.StringEntry statusSym = ctx.stringTable.addString(":status");
		WasmLispCompiler.StringTable.StringEntry bodySym = ctx.stringTable.addString(":body");
		WasmLispCompiler.StringTable.StringEntry headersSym = ctx.stringTable.addString(":headers");

		// Evaluate the promise and stash it; a nil promise (a fetch that could not be
		// started) yields nil without calling the adapter.
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		setLocal(w, promiseTmp);
		getLocal(w, promiseTmp);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.IF);
		w.write(Type.REFNULL.code());
		w.writeHeapType(Type.EQ.code());
		w.write(Instruction.REF_NULL);
		w.writeHeapType(Type.EQ.code());
		w.write(Instruction.ELSE);

		// Look the promise up in the await result cache (an alist of
		// cons(promise, plist) in GLOBAL_PROMISE_CACHE): resultTmp = the cached plist,
		// or null on a miss.
		w.write(Instruction.REF_NULL);
		w.writeHeapType(Type.EQ.code());
		setLocal(w, resultTmp);
		w.write(Instruction.GET_GLOBAL);
		w.writeUnsignedLeb128(WasmLispCompiler.GLOBAL_PROMISE_CACHE);
		setLocal(w, curTmp);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		getLocal(w, curTmp);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.BR_IF);
		w.writeUnsignedLeb128(1); // end of cache: exit the block
		// entry = car(cur)
		getLocal(w, curTmp);
		refCast(w, WasmLispCompiler.TYPE_CONS);
		structGet(w, WasmLispCompiler.TYPE_CONS, 0);
		setLocal(w, entryTmp);
		// hit when car(entry) is the same i31 handle (ref.eq compares i31s by value)
		getLocal(w, entryTmp);
		refCast(w, WasmLispCompiler.TYPE_CONS);
		structGet(w, WasmLispCompiler.TYPE_CONS, 0);
		getLocal(w, promiseTmp);
		w.write(Instruction.REF_EQ);
		w.write(Instruction.IF, 0x40);
		getLocal(w, entryTmp);
		refCast(w, WasmLispCompiler.TYPE_CONS);
		structGet(w, WasmLispCompiler.TYPE_CONS, 1);
		setLocal(w, resultTmp);
		w.write(Instruction.BR);
		w.writeUnsignedLeb128(2); // found: exit the block (0 = if, 1 = loop, 2 = block)
		w.write(Instruction.END);
		// cur = cdr(cur)
		getLocal(w, curTmp);
		refCast(w, WasmLispCompiler.TYPE_CONS);
		structGet(w, WasmLispCompiler.TYPE_CONS, 1);
		setLocal(w, curTmp);
		w.write(Instruction.BR);
		w.writeUnsignedLeb128(0); // next entry
		w.write(Instruction.END); // loop
		w.write(Instruction.END); // block

		getLocal(w, resultTmp);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.IF);
		w.write(Type.REFNULL.code());
		w.writeHeapType(Type.EQ.code());

		// Cache miss: http.fetch-await(handle, status, rhdrPtr, rhdrLen, bodyPtr,
		// bodyLen)
		getLocal(w, promiseTmp);
		WasmEmitHelper.castI31GetS(ctx);
		i32(w, WasmLispCompiler.FETCH_STATUS_ADDR);
		i32(w, WasmLispCompiler.FETCH_RHDR_PTR_ADDR);
		i32(w, WasmLispCompiler.FETCH_RHDR_LEN_ADDR);
		i32(w, WasmLispCompiler.FETCH_BODY_PTR_ADDR);
		i32(w, WasmLispCompiler.FETCH_BODY_LEN_ADDR);
		call(w, WasmLispCompiler.FUNC_FETCH_AWAIT);
		// On a non-zero errno (e.g. a failed connection) the adapter has not written the
		// out-params, so return nil instead of reading uninitialized buffers. Otherwise
		// build the result plist.
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF);
		w.write(Type.REFNULL.code());
		w.writeHeapType(Type.EQ.code());

		// status = i31(memory[FETCH_STATUS_ADDR])
		i32(w, WasmLispCompiler.FETCH_STATUS_ADDR);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		w.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		setLocal(w, statusTmp);
		// body = _fetch_str(memory[FETCH_BODY_PTR_ADDR], memory[FETCH_BODY_LEN_ADDR])
		i32(w, WasmLispCompiler.FETCH_BODY_PTR_ADDR);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		i32(w, WasmLispCompiler.FETCH_BODY_LEN_ADDR);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		call(w, WasmLispCompiler.FUNC_FETCH_STR);
		setLocal(w, bodyTmp);
		// headers = _fetch_deser_headers(memory[FETCH_RHDR_PTR_ADDR])
		i32(w, WasmLispCompiler.FETCH_RHDR_PTR_ADDR);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		call(w, WasmLispCompiler.FUNC_FETCH_DESER_HDRS);
		setLocal(w, hdrTmp);

		// Build (:status status :body body :headers headers) tail-first.
		w.write(Instruction.REF_NULL);
		w.writeHeapType(Type.EQ.code());
		setLocal(w, accTmp);
		consLocal(w, hdrTmp, accTmp);
		setLocal(w, accTmp);
		consSymCdr(w, headersSym, accTmp);
		setLocal(w, accTmp);
		consLocal(w, bodyTmp, accTmp);
		setLocal(w, accTmp);
		consSymCdr(w, bodySym, accTmp);
		setLocal(w, accTmp);
		consLocal(w, statusTmp, accTmp);
		setLocal(w, accTmp);
		consSymCdr(w, statusSym, accTmp);
		setLocal(w, resultTmp);
		// Remember the settled result: cache = cons(cons(promise, result), cache). The
		// wasi future's response has been consumed, so a repeat await must come from
		// here.
		getLocal(w, promiseTmp);
		getLocal(w, resultTmp);
		structNew(w, WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.GET_GLOBAL);
		w.writeUnsignedLeb128(WasmLispCompiler.GLOBAL_PROMISE_CACHE);
		structNew(w, WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.SET_GLOBAL);
		w.writeUnsignedLeb128(WasmLispCompiler.GLOBAL_PROMISE_CACHE);
		getLocal(w, resultTmp);

		// else branch: errno != 0 -> nil
		w.write(Instruction.ELSE);
		w.write(Instruction.REF_NULL);
		w.writeHeapType(Type.EQ.code());
		w.write(Instruction.END);

		// cache hit: the plist of the earlier await
		w.write(Instruction.ELSE);
		getLocal(w, resultTmp);
		w.write(Instruction.END);

		// end of the nil-promise check
		w.write(Instruction.END);
	}

	// Pushes cons(local carSlot, local cdrSlot).
	private static void consLocal(WasmWriter w, int carSlot, int cdrSlot) {
		getLocal(w, carSlot);
		getLocal(w, cdrSlot);
		structNew(w, WasmLispCompiler.TYPE_CONS);
	}

	// Pushes cons(<keyword symbol>, local cdrSlot).
	private static void consSymCdr(WasmWriter w, WasmLispCompiler.StringTable.StringEntry sym, int cdrSlot) {
		i32(w, sym.offset());
		i32(w, sym.length());
		structNew(w, WasmLispCompiler.TYPE_STRING);
		getLocal(w, cdrSlot);
		structNew(w, WasmLispCompiler.TYPE_CONS);
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

	private static void structNew(WasmWriter w, int type) {
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeSignedLeb128(type);
	}

	private static void refCast(WasmWriter w, int heapType) {
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(heapType);
	}

	private static void structGet(WasmWriter w, int type, int field) {
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeSignedLeb128(type);
		w.writeSignedLeb128(field);
	}

}
