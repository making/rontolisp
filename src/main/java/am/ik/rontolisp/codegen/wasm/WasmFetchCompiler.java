package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import org.jspecify.annotations.Nullable;

/**
 * Compile-time validation for {@code rontolisp:fetch} on the WASM backend. fetch itself
 * is the spliced {@code http.lisp} defun over the canon-lowered {@code wasi:http} user
 * import, so this class emits no code: under {@code --component},
 * {@link WasmExprCompiler} runs {@link #validate(LispCons)} and lets the call fall
 * through to the ordinary defun call path; in Preview 1 mode {@link #reject()} raises the
 * component-only compile error.
 *
 * <p>
 * The supported methods are GET, HEAD, POST, PUT, DELETE, OPTIONS and PATCH; the method
 * is resolved <strong>statically</strong> from a literal {@code :method} (a
 * statically-unknown method, e.g. one computed at runtime, is treated as GET, and a
 * statically-known unsupported method is rejected at compile time) -- parity with the
 * interpreter/JVM, which validate at runtime.
 */
final class WasmFetchCompiler {

	private WasmFetchCompiler() {
	}

	/**
	 * Compile-time checks a {@code (defun rontolisp:fetch ...)} could not do: the arity
	 * and a statically-known unsupported {@code :method}.
	 * @param cons the {@code (rontolisp:fetch ...)} call form
	 * @throws UnsupportedOperationException on a bad arity or a literal unsupported
	 * method
	 */
	static void validate(LispCons cons) {
		List<LispVal> args = cons.toList();
		if (args.size() < 2 || args.size() > 3) {
			throw new UnsupportedOperationException("fetch expects 1 or 2 arguments, got " + (args.size() - 1));
		}
		checkMethod(staticMethod(args.size() == 3 ? args.get(2) : null));
	}

	/** Raises the Preview-1 compile error: fetch is component-only on WASM. */
	static void reject() {
		throw new UnsupportedOperationException(
				"rontolisp:fetch is only available in WASM component mode (--component), not Preview 1 WASM");
	}

	// Rejects a statically-known unsupported literal method; null (unknown/runtime)
	// means GET and passes.
	private static void checkMethod(@Nullable String method) {
		if (method == null) {
			return;
		}
		switch (method.toUpperCase(java.util.Locale.ROOT)) {
			case "GET", "HEAD", "POST", "PUT", "DELETE", "OPTIONS", "PATCH" -> {
			}
			default -> throw new UnsupportedOperationException("fetch: unsupported method: " + method
					+ " (supported: GET, HEAD, POST, PUT, DELETE, OPTIONS, PATCH)");
		}
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
			if (key.car() instanceof LispSymbol sym && LispNames.keywordMatches(sym.name(), ":method")
					&& value.car() instanceof LispString method) {
				return method.value();
			}
			current = value.cdr();
		}
		return null;
	}

}
