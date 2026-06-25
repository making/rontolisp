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
 * WASI 0.2 request/response state machine) lives in the adapter's {@code http.fetch}
 * import; this compiler evaluates the URL, serializes the request headers via
 * {@code _fetch_ser_headers}, calls {@code http.fetch}, then builds the
 * {@code (:status N :body "..." :headers ((name . value)...))} property list from the
 * status / body / response-header buffers the adapter wrote back.
 *
 * <p>
 * fetch is component-only: in Preview 1 mode it raises a compile error (there is no host
 * {@code wasi:http}). Only GET is supported; a statically-known non-GET {@code :method}
 * is rejected at compile time (a method computed at runtime cannot be checked and is
 * treated as GET). A failed request (e.g. a refused connection) returns {@code nil}.
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
		// The WASM backend always issues a GET, so reject a statically-known non-GET
		// :method at compile time (matching the interpreter/JVM, which reject it at
		// runtime). A method computed at runtime cannot be checked and is treated as GET.
		String literalMethod = staticMethod(args.size() == 3 ? args.get(2) : null);
		if (literalMethod != null && !literalMethod.equalsIgnoreCase("GET")) {
			throw new UnsupportedOperationException(
					"fetch: only the GET method is currently supported, got: " + literalMethod);
		}
		final WasmWriter w = ctx.writer;
		int urlTmp = ctx.allocTemp();
		int statusTmp = ctx.allocTemp();
		int bodyTmp = ctx.allocTemp();
		int hdrTmp = ctx.allocTemp();
		int accTmp = ctx.allocTemp();
		int headersOff = ctx.stringTable.addString(":headers").offset();
		WasmLispCompiler.StringTable.StringEntry statusSym = ctx.stringTable.addString(":status");
		WasmLispCompiler.StringTable.StringEntry bodySym = ctx.stringTable.addString(":body");
		WasmLispCompiler.StringTable.StringEntry headersSym = ctx.stringTable.addString(":headers");

		// Evaluate the URL string and stash the struct so we can read offset and length.
		WasmExprCompiler.compileExpr(args.get(1), ctx);
		setLocal(w, urlTmp);

		// http.fetch(method=0, urlPtr, urlLen, REQ_HDR_BUF, reqHdrLen, status, rhdrPtr,
		// rhdrLen, bodyPtr, bodyLen)
		i32(w, 0); // method GET
		getLocal(w, urlTmp);
		refCast(w, WasmLispCompiler.TYPE_STRING);
		structGet(w, WasmLispCompiler.TYPE_STRING, 0);
		i32(w, 1);
		w.write(Instruction.I32_ADD); // urlPtr = offset + 1 (skip opening quote)
		getLocal(w, urlTmp);
		refCast(w, WasmLispCompiler.TYPE_STRING);
		structGet(w, WasmLispCompiler.TYPE_STRING, 1);
		i32(w, 2);
		w.write(Instruction.I32_SUB); // urlLen = length - 2 (strip quotes)
		i32(w, WasmLispCompiler.REQ_HDR_BUF);
		// reqHdrLen = _fetch_ser_headers(_fetch_plist_get(options, :headers))
		if (args.size() == 3) {
			WasmExprCompiler.compileExpr(args.get(2), ctx);
		}
		else {
			w.write(Instruction.REF_NULL);
			w.writeHeapType(Type.EQ.code());
		}
		i32(w, headersOff);
		call(w, WasmLispCompiler.FUNC_FETCH_PLIST_GET);
		call(w, WasmLispCompiler.FUNC_FETCH_SER_HDRS);
		i32(w, WasmLispCompiler.FETCH_STATUS_ADDR);
		i32(w, WasmLispCompiler.FETCH_RHDR_PTR_ADDR);
		i32(w, WasmLispCompiler.FETCH_RHDR_LEN_ADDR);
		i32(w, WasmLispCompiler.FETCH_BODY_PTR_ADDR);
		i32(w, WasmLispCompiler.FETCH_BODY_LEN_ADDR);
		call(w, WasmLispCompiler.FUNC_FETCH);
		// On a non-zero errno (e.g. a malformed URL or a failed connection) the adapter
		// has not written the out-params, so return nil instead of reading uninitialized
		// buffers. Otherwise build the result plist.
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
		// final cons (:status . rest) left on the stack as the result
		consSymCdr(w, statusSym, accTmp);

		// else branch: errno != 0 -> nil
		w.write(Instruction.ELSE);
		w.write(Instruction.REF_NULL);
		w.writeHeapType(Type.EQ.code());
		w.write(Instruction.END);
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

	private static void refCast(WasmWriter w, int heapType) {
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(heapType);
	}

	private static void structGet(WasmWriter w, int type, int field) {
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeSignedLeb128(type);
		w.writeSignedLeb128(field);
	}

	private static void structNew(WasmWriter w, int type) {
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeSignedLeb128(type);
	}

}
