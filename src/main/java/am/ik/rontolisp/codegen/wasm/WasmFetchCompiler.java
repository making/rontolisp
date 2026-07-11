package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;
import am.ik.wasm.WasmWriter;
import org.jspecify.annotations.Nullable;

/**
 * Compiles {@code rontolisp:fetch} for the WASM component backend. The heavy lifting (the
 * WASI 0.2 request state machine) lives in the adapter's {@code http.fetch-start} import;
 * this compiler evaluates the URL, serializes the request headers via
 * {@code _fetch_ser_headers} and calls {@code http.fetch-start}, which sends the request
 * and immediately returns while the response is still in flight. The result is a
 * <em>promise</em>: the adapter's in-flight request handle (the wasi:http
 * {@code future-incoming-response} handle) boxed as an i31 integer, to be resolved by
 * {@code rontolisp:await} ({@link WasmAwaitCompiler}). Multiple promises can be in flight
 * at once.
 *
 * <p>
 * fetch is component-only: in Preview 1 mode it raises a compile error (there is no host
 * {@code wasi:http}). The supported methods are GET, HEAD, POST, PUT, DELETE, OPTIONS and
 * PATCH; the method is resolved <strong>statically</strong> from a literal
 * {@code :method} (a statically-unknown method, e.g. one computed at runtime, is treated
 * as GET, and a statically-known unsupported method is rejected at compile time). The
 * {@code :body} value (a request body string) <em>is</em> resolved at runtime: it is read
 * out of the options property list and its bytes are staged into the linear heap (via
 * {@code _str_to_mem}, like the URL and each header string -- a string's bytes live on
 * the GC heap and its field 0 is an identity id, not a linear offset). A request that
 * cannot be started (e.g. a malformed URL) returns {@code nil} instead of a promise,
 * matching the nil-on-failure convention of this backend.
 */
final class WasmFetchCompiler {

	private WasmFetchCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		if (!ctx.component) {
			throw new UnsupportedOperationException(
					"rontolisp:fetch is only available in WASI 0.2 component mode (--component), not Preview 1 WASM");
		}
		List<LispVal> args = cons.toList();
		if (args.size() < 2 || args.size() > 3) {
			throw new UnsupportedOperationException("fetch expects 1 or 2 arguments, got " + (args.size() - 1));
		}
		// Resolve the method discriminant statically (matching the interpreter/JVM, which
		// validate at runtime). A method computed at runtime cannot be checked and is
		// treated as GET; a statically-known unsupported method is rejected here.
		int methodDisc = methodDiscriminant(staticMethod(args.size() == 3 ? args.get(2) : null));
		final WasmWriter w = ctx.writer;
		int urlTmp = ctx.allocTemp();
		int optTmp = ctx.allocTemp();
		int reqBodyTmp = ctx.allocTemp();
		int bodyOff = ctx.stringTable.addString(":body").offset();
		int headersOff = ctx.stringTable.addString(":headers").offset();

		// Evaluate the URL string and stash the struct so we can read offset and length.
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		setLocal(w, urlTmp);
		// Evaluate the options plist once (or nil) and stash it for the :body / :headers
		// lookups.
		if (args.size() == 3) {
			WasmExprCompiler.compileExpr(args.get(2), ctx);
		}
		else {
			w.write(Instruction.REF_NULL);
			w.writeHeapType(Type.EQ.code());
		}
		setLocal(w, optTmp);
		// reqBody = _fetch_plist_get(options, :body) -- a string struct or nil.
		getLocal(w, optTmp);
		i32(w, bodyOff);
		call(w, WasmLispCompiler.FUNC_FETCH_PLIST_GET);
		setLocal(w, reqBodyTmp);

		// Stage the URL (and request body) bytes into the linear heap via _str_to_mem: a
		// string's bytes live on the GC heap and its field 0 is an identity id, NOT a
		// linear offset (a runtime-built string has no linear bytes at its id at all).
		// Each staging records the unquoted (ptr,len) in a fixed cell pair and ADVANCES
		// HEAP_PTR past the copy, so the header serialization below (which stages each
		// header string at the then-current HEAP_PTR scratch) cannot clobber it; the
		// pointer is popped back once fetch-start has consumed the buffers.
		// FETCH_URL_PTR = HEAP_PTR + 1 (content pointer, past the staged opening quote)
		i32(w, WasmLispCompiler.FETCH_URL_PTR_ADDR);
		loadCell(w, WasmLispCompiler.HEAP_PTR_ADDR);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		// FETCH_URL_LEN = _str_to_mem(url, HEAP_PTR) - 2 (strip both quotes)
		i32(w, WasmLispCompiler.FETCH_URL_LEN_ADDR);
		getLocal(w, urlTmp);
		loadCell(w, WasmLispCompiler.HEAP_PTR_ADDR);
		WasmEmitHelper.emitStrToMemCall(w);
		i32(w, 2);
		w.write(Instruction.I32_SUB);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		// HEAP_PTR = FETCH_URL_PTR + FETCH_URL_LEN + 1 (past the closing quote)
		advanceHeapPast(w, WasmLispCompiler.FETCH_URL_PTR_ADDR, WasmLispCompiler.FETCH_URL_LEN_ADDR);
		// Request body: nil -> (0, 0); otherwise stage it the same way above the URL.
		getLocal(w, reqBodyTmp);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.IF, 0x40);
		storeCellI32(w, WasmLispCompiler.FETCH_REQ_BODY_PTR_ADDR, 0);
		storeCellI32(w, WasmLispCompiler.FETCH_REQ_BODY_LEN_ADDR, 0);
		w.write(Instruction.ELSE);
		i32(w, WasmLispCompiler.FETCH_REQ_BODY_PTR_ADDR);
		loadCell(w, WasmLispCompiler.HEAP_PTR_ADDR);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		i32(w, WasmLispCompiler.FETCH_REQ_BODY_LEN_ADDR);
		getLocal(w, reqBodyTmp);
		loadCell(w, WasmLispCompiler.HEAP_PTR_ADDR);
		WasmEmitHelper.emitStrToMemCall(w);
		i32(w, 2);
		w.write(Instruction.I32_SUB);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		advanceHeapPast(w, WasmLispCompiler.FETCH_REQ_BODY_PTR_ADDR, WasmLispCompiler.FETCH_REQ_BODY_LEN_ADDR);
		w.write(Instruction.END);

		// http.fetch-start(method, urlPtr, urlLen, reqBodyPtr, reqBodyLen, REQ_HDR_BUF,
		// reqHdrLen, handleOut)
		i32(w, methodDisc);
		loadCell(w, WasmLispCompiler.FETCH_URL_PTR_ADDR);
		loadCell(w, WasmLispCompiler.FETCH_URL_LEN_ADDR);
		loadCell(w, WasmLispCompiler.FETCH_REQ_BODY_PTR_ADDR);
		loadCell(w, WasmLispCompiler.FETCH_REQ_BODY_LEN_ADDR);
		i32(w, WasmLispCompiler.REQ_HDR_BUF);
		// reqHdrLen = _fetch_ser_headers(_fetch_plist_get(options, :headers))
		getLocal(w, optTmp);
		i32(w, headersOff);
		call(w, WasmLispCompiler.FUNC_FETCH_PLIST_GET);
		call(w, WasmLispCompiler.FUNC_FETCH_SER_HDRS);
		i32(w, WasmLispCompiler.FETCH_HANDLE_ADDR);
		call(w, WasmLispCompiler.FUNC_FETCH_START);
		// Pop the URL/body staging (the adapter has copied the buffers into wasi:http
		// resources); the errno stays on the stack across the stores.
		i32(w, WasmLispCompiler.HEAP_PTR_ADDR);
		loadCell(w, WasmLispCompiler.FETCH_URL_PTR_ADDR);
		i32(w, 1);
		w.write(Instruction.I32_SUB);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		// On a non-zero errno (e.g. a malformed URL) the adapter has not written the
		// handle, so yield nil instead of a promise. Otherwise the promise is a
		// TYPE_PROMISE root struct (kind 0) holding the in-flight handle boxed as an i31
		// integer; _promise_await resolves and settles it.
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF);
		w.write(Type.REFNULL.code());
		w.writeHeapType(Type.EQ.code());
		i32(w, 0); // kind 0: fetch root
		i32(w, WasmLispCompiler.FETCH_HANDLE_ADDR);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		w.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		w.write(Instruction.REF_NULL);
		w.writeHeapType(Type.EQ.code());
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_PROMISE);
		w.write(Instruction.ELSE);
		w.write(Instruction.REF_NULL);
		w.writeHeapType(Type.EQ.code());
		w.write(Instruction.END);
	}

	// The WASI http method variant discriminants for the methods rontolisp supports
	// (connect=5 and trace=7 are intentionally omitted). A null/unknown literal method
	// means GET; an unsupported literal method is a compile error.
	private static int methodDiscriminant(@Nullable String method) {
		if (method == null) {
			return 0; // GET (also the default for a runtime-computed method)
		}
		return switch (method.toUpperCase(java.util.Locale.ROOT)) {
			case "GET" -> 0;
			case "HEAD" -> 1;
			case "POST" -> 2;
			case "PUT" -> 3;
			case "DELETE" -> 4;
			case "OPTIONS" -> 6;
			case "PATCH" -> 8;
			default -> throw new UnsupportedOperationException("fetch: unsupported method: " + method
					+ " (supported: GET, HEAD, POST, PUT, DELETE, OPTIONS, PATCH)");
		};
	}

	// Pushes mem[addr] (a fixed i32 cell).
	private static void loadCell(WasmWriter w, int addr) {
		i32(w, addr);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
	}

	// mem[addr] = value (an i32 constant).
	private static void storeCellI32(WasmWriter w, int addr, int value) {
		i32(w, addr);
		i32(w, value);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
	}

	// HEAP_PTR = mem[ptrCell] + mem[lenCell] + 1: advances the bump pointer past a
	// quote-framed staging whose content (ptr,len) cells were just written (base =
	// ptr - 1, total = len + 2).
	private static void advanceHeapPast(WasmWriter w, int ptrCell, int lenCell) {
		i32(w, WasmLispCompiler.HEAP_PTR_ADDR);
		loadCell(w, ptrCell);
		loadCell(w, lenCell);
		w.write(Instruction.I32_ADD);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
	}

	// Returns the :method value if it can be determined statically from the options form
	// -- either (list :method "X" ...) or (quote (:method "X" ...)) with a string literal
	// -- or null when it is computed at runtime (and therefore cannot be checked here).
	private static @Nullable String staticMethod(@Nullable LispVal options) {
		if (!(options instanceof LispCons cons) || !(cons.car() instanceof LispSymbol head)) {
			return null;
		}
		LispVal plist;
		if (head.name().equals(LispNames.QUOTE) && cons.cdr() instanceof LispCons quoted) {
			plist = quoted.car();
		}
		else if (head.name().equals(LispNames.LIST)) {
			plist = cons.cdr();
		}
		else {
			return null;
		}
		LispVal current = plist;
		while (current instanceof LispCons key && key.cdr() instanceof LispCons value) {
			if (key.car() instanceof LispSymbol sym && ":method".equals(sym.name())
					&& value.car() instanceof LispString method) {
				return method.value();
			}
			current = value.cdr();
		}
		return null;
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
