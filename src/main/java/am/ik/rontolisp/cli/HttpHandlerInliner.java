package am.ik.rontolisp.cli;

import java.util.ArrayList;
import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;
import am.ik.rontolisp.reader.LispReader;

/**
 * Compile-path rewrite for {@code rontolisp:http-handler} when targeting a WASI component
 * ({@code --component}). The interpreter runs a blocking embedded HTTP server; a
 * component instead exports {@code wasi:http/incoming-handler}, driven by the serve
 * adapter ({@code adapter-serve.wat}) which calls a core {@code %http-dispatch} entry per
 * request.
 *
 * <p>
 * This pass turns {@code (rontolisp:http-handler 'handle [port])} into that
 * {@code %http-dispatch} entry: it splices in two synthesized defuns and a
 * {@code rontolisp:wasm-export} directive so the existing wasm-export memory-ABI
 * machinery exposes {@code %http-dispatch(method, path, body) -> "<status>\n<body>"} (the
 * adapter parses the status line and writes the body). The original {@code http-handler}
 * directive is removed (it would otherwise be a compile error on the WASM backend). The
 * handler must be named by a quoted top-level symbol, exactly like
 * {@code rontolisp:wasm-export}.
 */
public final class HttpHandlerInliner {

	private HttpHandlerInliner() {
	}

	/**
	 * Returns whether any top-level form is a {@code rontolisp:http-handler} directive.
	 * @param program the top-level forms
	 * @return {@code true} if the program serves HTTP via {@code http-handler}
	 */
	public static boolean usesHttpHandler(List<LispVal> program) {
		for (LispVal form : program) {
			if (isHttpHandlerForm(form)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Returns a copy of {@code program} with the {@code rontolisp:http-handler} directive
	 * replaced by the synthesized {@code %http-dispatch} wrapper (two defuns plus a
	 * {@code rontolisp:wasm-export} directive). Only the first {@code http-handler} is
	 * honored; any others are dropped (a component exports a single incoming-handler).
	 * @param program the top-level forms (after macro expansion)
	 * @return the rewritten forms
	 */
	public static List<LispVal> inline(List<LispVal> program) {
		String handler = null;
		List<LispVal> rewritten = new ArrayList<>();
		for (LispVal form : program) {
			if (isHttpHandlerForm(form)) {
				if (handler == null) {
					handler = handlerName((LispCons) form);
				}
				// Drop the directive; the incoming-handler export replaces it.
				continue;
			}
			rewritten.add(form);
		}
		if (handler == null) {
			return program;
		}
		rewritten.addAll(wrapperForms(handler));
		return rewritten;
	}

	// The synthesized %http-dispatch / %http-request / %http-encode defuns + the
	// wasm-export directive. %http-dispatch is a wasm-export :string*3 -> :string
	// function; it runs the handler on a request plist and encodes the response as
	// "<status>\n<body>". The serve adapter passes the request target with the query
	// attached (wasi:http path-with-query), so %http-request splits it at the first ?
	// into :path / :query (nil when there is none), matching the interpreter and JVM
	// backends. A newline byte is produced with (code-char 10) so no raw newline needs
	// to survive the reader.
	private static List<LispVal> wrapperForms(String handler) {
		String template = """
				(defun %%http-dispatch (%%http-m %%http-p %%http-b)
				  (%%http-encode (%s (%%http-request %%http-m %%http-p %%http-b))))
				(defun %%http-request (%%http-m %%http-p %%http-b)
				  (let ((%%http-q (position #\\? %%http-p)))
				    (list :method %%http-m
				          :path (if %%http-q (subseq %%http-p 0 %%http-q) %%http-p)
				          :query (if %%http-q (subseq %%http-p (+ %%http-q 1)) nil)
				          :headers nil :body %%http-b)))
				(defun %%http-encode (%%http-r)
				  (concatenate 'string
				    (princ-to-string (or (getf %%http-r :status) 200))
				    (princ-to-string (code-char 10))
				    (or (getf %%http-r :body) "")))
				(rontolisp:wasm-export '%%http-dispatch :params '(:string :string :string) :returns :string)
				""".formatted(handler);
		return LispReader.readAllFromString(template);
	}

	private static boolean isHttpHandlerForm(LispVal form) {
		if (!(form instanceof LispCons cons) || !(cons.car() instanceof LispSymbol sym)) {
			return false;
		}
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(sym.name());
		return qn != null && LispNames.RONTOLISP_PKG.equals(qn.pkg()) && LispNames.HTTP_HANDLER.equals(qn.member());
	}

	// Extracts the handler function name from (rontolisp:http-handler 'name [port]).
	private static String handlerName(LispCons form) {
		List<LispVal> args = form.toList();
		if (args.size() < 2) {
			throw new UnsupportedOperationException(
					LispNames.HTTP_HANDLER + " expects a quoted handler name, got: " + form.print());
		}
		LispVal ref = args.get(1);
		// 'name reads as (quote name).
		if (ref instanceof LispCons quote && quote.car() instanceof LispSymbol q && LispNames.QUOTE.equals(q.name())
				&& quote.cdr() instanceof LispCons rest && rest.car() instanceof LispSymbol name) {
			return name.name();
		}
		throw new UnsupportedOperationException(LispNames.HTTP_HANDLER
				+ " expects a quoted handler name (e.g. 'handle) when compiling to a component, got: " + ref.print());
	}

}
