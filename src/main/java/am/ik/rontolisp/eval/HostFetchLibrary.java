package am.ik.rontolisp.eval;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;
import am.ik.rontolisp.compiler.FetchResponseShape;
import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.reader.LispReader;
import org.jspecify.annotations.Nullable;

/**
 * The {@code --host-fetch} lowering of {@code rontolisp:fetch} on a {@code --no-wasi}
 * reactor: the transport is ONE injected host import,
 * {@code env.fetch(request-json) -> response-json}, and this class generates the Lisp
 * that carries {@code fetch}'s public contract across it — the request/response JSON
 * envelope derived from {@link FetchResponseShape}'s records (field name = JSON key, in
 * record order, never hand-written per host), the method validation at fetch time, and
 * the {@code (:status :headers :body)} result plist every other backend answers.
 *
 * <p>
 * The call returns a FUTURE like everywhere else — the defun body runs inside
 * {@code rontolisp:async-defun}, which on the reactor is the degenerate settled
 * {@code TYPE_P1_FUTURE}: the host import blocks the wasm stack (synchronously, or
 * suspended through JSPI), so the value is ready when the call returns and
 * {@code (rontolisp:await (rontolisp:fetch ...))} reads identically on every target.
 * Consequences of that degeneracy, documented as the flag's contract: started == settled
 * (two fetches never overlap), and a transport failure signals at the CALL rather than at
 * await (the Preview 1 async divergence, {@code .kb/async-await.md}). The {@code :body}
 * is an eager string — the whole reply arrived with the import call — which
 * {@code rontolisp:read-all} passes through, so the drain loop needs no edit either.
 *
 * <p>
 * Consumers: {@code RontoLispCli} calls {@link #process(List)} under
 * {@code --no-wasi --host-fetch} (before {@code UserMacroExpander}, so
 * {@code JsonLibrary} and the prelude pick up the splice's {@code json-parse} /
 * {@code json-stringify} / {@code plist-hash-table} call sites), and compiler tests
 * mirror it. The import rides the ordinary {@code rontolisp:wasm-import} synthetic-defun
 * machinery, appended after the program so a program's own import ordinals are unchanged
 * (the {@code --host-random} appended-LAST precedent). No splice happens when the program
 * never references {@code rontolisp:fetch}: a build that does not fetch still imports
 * nothing.
 */
public final class HostFetchLibrary {

	/** The import object entry the host must provide: {@code MODULE.FIELD}. */
	public static final String IMPORT_MODULE = "env";

	/** The import object entry the host must provide: {@code MODULE.FIELD}. */
	public static final String IMPORT_FIELD = "fetch";

	// The supported methods, matching the interpreter/JVM runtimes and
	// WasmFetchCompiler's compile-time literal check (the fourth spelling of this
	// list; each backend validates where its fetch is built).
	private static final List<String> SUPPORTED_METHODS = List.of("GET", "HEAD", "POST", "PUT", "DELETE", "OPTIONS",
			"PATCH");

	@Nullable private static volatile List<LispVal> forms;

	private HostFetchLibrary() {
	}

	/**
	 * The compile-path pre-pass: when the program references {@code rontolisp:fetch},
	 * append the host-fetch lowering (the {@code env.fetch} wasm-import plus the envelope
	 * defuns). The program is returned unchanged otherwise — the zero-import contract of
	 * {@code --no-wasi} must not be spent on a program that never fetches.
	 * @param program the top-level forms
	 * @return the program, with the lowering appended when it fetches
	 */
	public static List<LispVal> process(List<LispVal> program) {
		if (!referencesFetch(program)) {
			return program;
		}
		List<LispVal> out = new java.util.ArrayList<>(program);
		out.addAll(forms());
		return out;
	}

	/**
	 * Whether any form references {@code rontolisp:fetch} (quoted data included: a
	 * designator that never becomes a call still costs nothing but the splice, and the
	 * unused defuns are pruned/shaken like any library's).
	 * @param program the top-level forms
	 * @return whether the program fetches
	 */
	public static boolean referencesFetch(List<LispVal> program) {
		for (LispVal form : program) {
			if (references(form)) {
				return true;
			}
		}
		return false;
	}

	private static boolean references(LispVal form) {
		if (form instanceof LispSymbol sym) {
			PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(sym.name());
			return qn != null && LispNames.RONTOLISP_PKG.equals(qn.pkg()) && LispNames.FETCH.equals(qn.member());
		}
		return form instanceof LispCons cons && (references(cons.car()) || references(cons.cdr()));
	}

	/**
	 * Returns the parsed lowering forms. The source is written in canonical shape
	 * (qualified {@code rontolisp} names, bare {@code cl} names), parsed once and cached.
	 * @return the lowering forms
	 */
	public static List<LispVal> forms() {
		List<LispVal> cached = forms;
		if (cached == null) {
			synchronized (HostFetchLibrary.class) {
				cached = forms;
				if (cached == null) {
					cached = LispReader.readAllFromString(source(), Features.WASM_REACTOR);
					forms = cached;
				}
			}
		}
		return cached;
	}

	/**
	 * The generated Lisp source. Public so the envelope-pinning test can assert the JSON
	 * keys against {@link FetchResponseShape}'s records instead of by eye.
	 * @return the Lisp source of the lowering
	 */
	public static String source() {
		StringBuilder src = new StringBuilder();
		src.append(";; Generated by HostFetchLibrary from FetchResponseShape -- the --host-fetch\n");
		src.append(";; lowering of rontolisp:fetch over the env.fetch host import.\n");
		src.append("(rontolisp:wasm-import 'rontolisp::%host-fetch-send :from \"")
			.append(IMPORT_MODULE)
			.append("\" :as \"")
			.append(IMPORT_FIELD)
			.append("\"\n                       :params '(:string) :returns :string)\n");
		src.append("""
				(defun rontolisp::%host-fetch-pairs (alist)
				  ;; header alist -> a JSON array of [name, value] (a VECTOR: an empty
				  ;; LIST would stringify as false, and a name may repeat, so no object).
				  (let ((out nil))
				    (dolist (pair alist) (setq out (cons (list (car pair) (cdr pair)) out)))
				    (coerce (nreverse out) 'vector)))
				(defun rontolisp::%host-fetch-alist (pairs)
				  ;; the JSON array of [name, value] -> the response header alist.
				  (let ((out nil)
				        (n (if pairs (length pairs) 0))
				        (i 0))
				    (while (< i n)
				      (let ((pair (elt pairs i)))
				        (setq out (cons (cons (elt pair 0) (elt pair 1)) out)))
				      (setq i (+ i 1)))
				    (nreverse out)))
				(defun rontolisp::%host-fetch-method (options)
				  (let ((method (string-upcase (or (getf options :method) "GET"))))
				""");
		src.append("    (unless (or");
		for (String method : SUPPORTED_METHODS) {
			src.append(" (string= method \"").append(method).append("\")");
		}
		src.append(")\n");
		src.append("      (error \"fetch: unsupported method: ~a\" method))\n    method))\n");
		appendRequestBuilder(src);
		appendResponseParser(src);
		src.append("""
				(rontolisp:async-defun rontolisp::%host-fetch-run (request-json)
				  ;; The async frame is what makes fetch answer a future; the host call
				  ;; blocks the wasm stack (JSPI or a synchronous host), so the future is
				  ;; settled at creation and await never suspends.
				  (rontolisp::%host-fetch-parse
				   (rontolisp::%host-fetch-send request-json)))
				(defun rontolisp:fetch (url &rest options)
				  ;; The options are validated here, at fetch time, like every backend.
				  (rontolisp::%host-fetch-run
				   (rontolisp::%host-fetch-request url (if options (car options) nil))))
				""");
		return src.toString();
	}

	// (defun rontolisp::%host-fetch-request (url options) ...) -> the request JSON, its
	// keys the request record's fields in record order. Each field's value expression is
	// supplied here (the one part a record cannot derive); an unknown field fails the
	// build loudly, so growing the record forces this switch to say what crosses.
	private static void appendRequestBuilder(StringBuilder src) {
		src.append("(defun rontolisp::%host-fetch-request (url options)\n");
		src.append("  (let ((method (rontolisp::%host-fetch-method options)))\n");
		src.append("    (rontolisp:json-stringify\n     (rontolisp:plist-hash-table\n      (append (list");
		StringBuilder optional = new StringBuilder();
		for (FetchResponseShape.Field field : FetchResponseShape.requestFields()) {
			switch (field.name()) {
				case "url" -> src.append(' ').append(field.keyword()).append(" url");
				case "method" -> src.append(' ').append(field.keyword()).append(" method");
				case "headers" -> src.append(' ')
					.append(field.keyword())
					.append(" (rontolisp::%host-fetch-pairs (getf options :headers))");
				// option<string>: an absent :body crosses as an ABSENT key -- a GET
				// with any body key (even "") is a TypeError in the host's fetch.
				case "body" -> optional.append("\n              (let ((body (getf options :body)))\n")
					.append("                (if body (list ")
					.append(field.keyword())
					.append(" body) nil))");
				default -> throw new IllegalStateException(
						"The http-plist request record grew a field this lowering does not carry: " + field.name());
			}
		}
		src.append(')').append(optional).append(")))))\n");
	}

	// (defun rontolisp::%host-fetch-parse (response-json) ...) -> the result plist, its
	// keys the response record's fields in record order; the reserved error key signals.
	private static void appendResponseParser(StringBuilder src) {
		src.append("(defun rontolisp::%host-fetch-parse (response-json)\n");
		src.append("  (let ((envelope (rontolisp:json-parse response-json)))\n");
		src.append("    (let ((err (gethash \"")
			.append(FetchResponseShape.HOST_ENVELOPE_ERROR_KEY)
			.append("\" envelope)))\n");
		src.append("      (when err (error \"fetch: ~a\" err)))\n");
		src.append("    (list");
		for (FetchResponseShape.Field field : FetchResponseShape.responseFields()) {
			String read = "(gethash \"" + field.name() + "\" envelope)";
			String value = switch (field.name()) {
				case "status" -> read;
				case "headers" -> "(rontolisp::%host-fetch-alist " + read + ")";
				// The declared default: on a reactor the body has fully arrived, so
				// the absent-body reply of a HEAD is the eager empty string.
				case "body" -> "(or " + read + " \"" + FetchResponseShape.RESPONSE_BODY_DEFAULT + "\")";
				default -> throw new IllegalStateException(
						"The http-plist response record grew a field this lowering does not carry: " + field.name());
			};
			src.append(' ').append(field.keyword()).append(' ').append(value);
		}
		src.append(")))\n");
	}

}
