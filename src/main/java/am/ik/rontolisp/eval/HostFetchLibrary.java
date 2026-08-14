package am.ik.rontolisp.eval;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;
import am.ik.rontolisp.compiler.FetchResponseShape;
import am.ik.rontolisp.compiler.HostBoundary;
import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.reader.LispReader;

/**
 * The {@code --host-fetch} lowering of {@code rontolisp:fetch} on a {@code --no-wasi}
 * reactor: the transport is an injected host import {@code env.fetch(request-json) ->
 * response-json} for the request and the reply HEAD — joined, under
 * {@link am.ik.rontolisp.compiler.HostBoundary#STREAMING}, by
 * {@code env.readResponseBody(ptr, cap) -> i32} for the reply BODY — and this class
 * generates the Lisp that carries {@code fetch}'s public contract across them: the
 * request/response JSON envelope derived from {@link FetchResponseShape}'s records (field
 * name = JSON key, in record order, never hand-written per host), the method validation
 * at fetch time, and the {@code (:status :headers :body)} result plist every other
 * backend answers.
 *
 * <p>
 * <strong>The plist's {@code :body} is a STREAM on both boundaries; what the boundary
 * decides is what feeds it.</strong> Under {@code --host-boundary=streaming} (the
 * default) the body is OUT OF BAND, through the import above: it used to ride the
 * response envelope as one JSON string, which cost the reply a full copy in linear memory
 * before any Lisp ran, made a BINARY reply impossible (the {@code :string} decoder is
 * non-validating, so the three octets {@code ff}/{@code fe}/{@code 41} came back as code
 * point 0x1FE062 and two NULs) and left a Worker unable to forward a streamed upstream
 * response. The body import is the mirror of the reactor's {@code env.readRequestBody}
 * ({@link HttpReactorInliner}) — the {@code read(2)} shape, where the CALLER passes the
 * buffer and the host answers a count — so both directions of that reactor boundary say
 * the same thing, and the pull thunk over it rides the same reused buffer and
 * chunk-boundary UTF-8 decode ({@code %http-reactor-buffer} / {@code -chunk} /
 * {@code -body-stream}, {@code .kb/clack.md}).
 *
 * <p>
 * Under {@code --host-boundary=envelope} the reply arrives whole, in the head's own
 * {@code "body"} key, and the import, the cursor and the reply-generation counter are all
 * gone with it — the key was always the documented fallback, so the collapse is the
 * already-live path rather than a second one. A program pays the copy and cannot carry
 * binary; in exchange it imports one host function and its host keeps no state at all,
 * which is what lets the build WRITE that host half ({@code compiler/HostGlueEmitter} --
 * what {@code env.fetch} DOES is then the same twenty lines in every program).
 * Re-evaluate the DEFAULT if the copy ever stops being the cheaper thing to pay for a
 * document-shaped reply — it is the split, not the envelope, that is the special case.
 *
 * <p>
 * The call returns a FUTURE like everywhere else — the defun body runs inside
 * {@code rontolisp:async-defun}, which on the reactor is the degenerate settled
 * {@code TYPE_P1_FUTURE}: the host import blocks the wasm stack (synchronously, or
 * suspended through JSPI), so the value is ready when the call returns and
 * {@code (rontolisp:await (rontolisp:fetch ...))} reads identically on every target.
 * Consequences of that degeneracy, documented as the flag's contract: started == settled,
 * so two fetch HEADS never overlap, and a transport failure BEFORE the head signals at
 * the CALL rather than at await (the Preview 1 async divergence,
 * {@code .kb/async-await.md}). What the SPLIT changes is what "settled" covers: the
 * future settles when the HEADERS arrive, so a failure mid-BODY signals at the DRAIN —
 * which is what the other three backends have always done. On the envelope boundary the
 * head IS the whole reply, so there is no mid-body to fail in and every failure is the
 * call's.
 *
 * <p>
 * Consumers: {@code RontoLispCli} calls {@link #process(List, HostBoundary)} under
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
	public static final String IMPORT_MODULE = FetchResponseShape.HOST_IMPORT_MODULE;

	/** The import object entry the host must provide: {@code MODULE.FIELD}. */
	public static final String IMPORT_FIELD = FetchResponseShape.HOST_IMPORT_FIELD;

	/**
	 * The reply BODY's own import, beside {@link #IMPORT_FIELD}:
	 * {@code (ptr, cap) -> i32}, "write up to cap octets of the body that the last
	 * {@code env.fetch} opened at ptr and answer how many; 0 is end of stream, a NEGATIVE
	 * count is a transport failure mid-body". The mirror of the reactor's
	 * {@code readRequestBody} — same {@code read(2)} shape, same {@code env} module.
	 */
	public static final String BODY_IMPORT_FIELD = FetchResponseShape.HOST_BODY_IMPORT_FIELD;

	// The supported methods, matching the interpreter/JVM runtimes and
	// WasmFetchCompiler's compile-time literal check (the fourth spelling of this
	// list; each backend validates where its fetch is built).
	private static final List<String> SUPPORTED_METHODS = List.of("GET", "HEAD", "POST", "PUT", "DELETE", "OPTIONS",
			"PATCH");

	// One parse per BOUNDARY, not one per JVM: the two shapes generate different source,
	// and both appear in one JVM (the test suite compiles them side by side).
	private static final Map<HostBoundary, List<LispVal>> FORMS = new EnumMap<>(HostBoundary.class);

	private HostFetchLibrary() {
	}

	/**
	 * The compile-path pre-pass: when the program references {@code rontolisp:fetch},
	 * append the host-fetch lowering (the {@code env.fetch} wasm-import plus the envelope
	 * defuns). The program is returned unchanged otherwise — the zero-import contract of
	 * {@code --no-wasi} must not be spent on a program that never fetches.
	 * @param program the top-level forms
	 * @param boundary which shape of the reply body was asked for
	 * ({@code --host-boundary}): {@link HostBoundary#ENVELOPE} drops the
	 * {@code env.readResponseBody} import and the cursor behind it
	 * @return the program, with the lowering appended when it fetches
	 */
	public static List<LispVal> process(List<LispVal> program, HostBoundary boundary) {
		if (!referencesFetch(program)) {
			return program;
		}
		List<LispVal> out = new java.util.ArrayList<>(program);
		out.addAll(forms(boundary));
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
	 * (qualified {@code rontolisp} names, bare {@code cl} names), parsed once per
	 * boundary and cached.
	 * @param boundary which shape of the reply body was asked for
	 * @return the lowering forms
	 */
	public static List<LispVal> forms(HostBoundary boundary) {
		synchronized (FORMS) {
			return FORMS.computeIfAbsent(boundary,
					shape -> LispReader.readAllFromString(source(shape), Features.WASM_REACTOR));
		}
	}

	/**
	 * The generated Lisp source. Public so the envelope-pinning test can assert the JSON
	 * keys against {@link FetchResponseShape}'s records instead of by eye.
	 * @param boundary which shape of the reply body was asked for
	 * @return the Lisp source of the lowering
	 */
	public static String source(HostBoundary boundary) {
		StringBuilder src = new StringBuilder();
		src.append(";; Generated by HostFetchLibrary from FetchResponseShape -- the --host-fetch\n");
		src.append(";; lowering of rontolisp:fetch over the env.fetch host import.\n");
		src.append("(rontolisp:wasm-import 'rontolisp::%host-fetch-send :from \"")
			.append(IMPORT_MODULE)
			.append("\" :as \"")
			.append(IMPORT_FIELD)
			.append("\"\n                       :params '(:string) :returns :string)\n");
		appendBody(src, boundary);
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
				""");
		src.append(boundary.bodiesOutOfBand() ? """
				  ;; What it settles to is the HEAD -- the body is still on the wire, and
				  ;; pulling it is what the stream below does.
				  ;;
				  ;; A round trip OPENS a body: the host's read cursor moves to this
				  ;; reply, so any body left undrained from an earlier fetch is gone. The
				  ;; counter is how a stream over the old one finds out (see
				  ;; %host-fetch-pull) instead of silently reading this reply's octets.
				  (setq rontolisp::%host-fetch-open (+ rontolisp::%host-fetch-open 1))
				""" : """
				  ;; What it settles to is the WHOLE reply: the body rode the head's own
				  ;; "body" key, so nothing is left on the wire, no cursor exists to be
				  ;; superseded by the next fetch, and a failure can only be reported
				  ;; before the head -- which is where this backend reports one anyway.
				""");
		src.append("""
				  (rontolisp::%host-fetch-parse
				   (rontolisp::%host-fetch-send request-json)))
				(defun rontolisp:fetch (url &rest options)
				  ;; The options are validated here, at fetch time, like every backend.
				  (rontolisp::%host-fetch-run
				   (rontolisp::%host-fetch-request url (if options (car options) nil))))
				""");
		return src.toString();
	}

	// The :body of the result plist, in whichever shape the boundary asked for. It is ONE
	// function either way, and its argument is the head's own "body" key both times:
	// %http-reactor-body-stream takes an abstract source, and an already-buffered string
	// is the degenerate case of a pull thunk rather than a second policy. So the plist's
	// :body is a first-class stream on both boundaries, and
	// (rontolisp:await (rontolisp:read-all (getf res :body))) reads identically.
	private static void appendBody(StringBuilder src, HostBoundary boundary) {
		if (!boundary.bodiesOutOfBand()) {
			// The envelope boundary: no import, no cursor, no counter, and nothing to
			// choose between -- the head's own key IS the reply, and the transport
			// normalizes what a host may have put there (absent, "", JSON null) into the
			// empty stream a HEAD or a 204 answers.
			src.append("""
					(defun rontolisp::%host-fetch-body (in-band)
					  ;; IN-BAND is the head's own "body" key, which on this boundary is
					  ;; where the whole reply arrived.
					  (rontolisp::%http-reactor-body-stream in-band))
					""");
			return;
		}
		appendBodyImport(src);
	}

	// The reply BODY's import and the pull stream over it. Declared :async t like the
	// reactor's two body imports: a host that STREAMS (a WebAssembly.Suspending wrapper
	// over a ReadableStream reader -- which is the only way a JS host can read one at
	// all) is a declared, supported host, and a host that answers synchronously is
	// equally valid and pays nothing.
	//
	// The thunk CALLS the import rather than taking #'name, or the build's
	// suspending-import report widens to "any export may suspend"; the transport calls
	// around it are the reactor's own (%http-reactor-buffer / -chunk / -body-stream), so
	// the ONE reused receive buffer and the chunk-boundary UTF-8 decode stay in one
	// place -- a body cut into chunks by a host that knows nothing about code points is
	// the normal case in this direction too.
	//
	// The pull parameter is TOKEN and not `open': a variable of that name is rewritten
	// into a call to cl:open by the --no-wasi filesystem stub pass, which cannot tell a
	// binding from a call (compiler/NoWasiFilesystemStubs; http-server.lisp carries the
	// same note).
	private static void appendBodyImport(StringBuilder src) {
		src.append("(rontolisp:wasm-import 'rontolisp::%host-fetch-read-body :from \"")
			.append(IMPORT_MODULE)
			.append("\" :as \"")
			.append(BODY_IMPORT_FIELD)
			.append("\"\n                       :params '() :returns :bytes :async t)\n");
		src.append("""
				;; Which reply the live body belongs to -- see %host-fetch-run. The
				;; boundary deliberately carries no handle: the host has ONE cursor, and
				;; a counter turns "drained too late" into an error instead of into the
				;; next reply's octets. Drain a body before starting the next fetch.
				(defvar rontolisp::%host-fetch-open 0)
				(defun rontolisp::%host-fetch-pull (token)
				  ;; One chunk of the reply body, through the caller-passes-the-buffer
				  ;; import: the module owns the buffer and the host writes into it.
				  (unless (eql token rontolisp::%host-fetch-open)
				    (error "fetch: the response body was superseded by a later fetch"))
				  (let* ((buf (rontolisp::%http-reactor-buffer\s""").append(HttpReactorInliner.CHUNK_BYTES).append("""
				))
				         (n
				          (rontolisp::%http-reactor-force
				           (rontolisp::%host-fetch-read-body buf))))
				    ;; A NEGATIVE count is the host reporting that the transfer failed
				    ;; after the head crossed -- the mid-body failure every other backend
				    ;; signals at the drain, and the reason the split needs an error
				    ;; channel at all. 0 is end of stream.
				    (when (and n (< n 0))
				      (error "fetch: the response body failed mid-transfer"))
				    (rontolisp::%http-reactor-chunk buf n)))
				(defun rontolisp::%host-fetch-body (in-band)
				  ;; The :body of the result plist: an asynchronous stream on this
				  ;; backend too, which is the whole point of taking the body out of the
				  ;; envelope. IN-BAND is the head's own "body" key -- a host that would
				  ;; rather answer the whole reply at once still may, and that string is
				  ;; the already-buffered case of the same abstract source. Normalized
				  ;; before the OR, so a host answering `"body": null` -- JSON's spelling
				  ;; of "it is not here", not of "it is empty" -- still gets the pull.
				  (rontolisp::%http-reactor-body-stream
				   (or (rontolisp::%http-reactor-source in-band)
				       (let ((token rontolisp::%host-fetch-open))
				         (lambda () (rontolisp::%host-fetch-pull token))))))
				""");
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
				// The key is read the same way on both boundaries; what it CARRIES is
				// appendBody's decision -- on the envelope boundary the whole reply, on
				// the streaming one the fallback a host may still fill, whose absence
				// (the normal case there) is what puts the stream over the body import.
				// Either way an absent-body reply (a HEAD, a 204) is that stream finding
				// end of stream at its first read, which read-all drains to the declared
				// default, the empty string.
				case "body" -> "(rontolisp::%host-fetch-body " + read + ")";
				default -> throw new IllegalStateException(
						"The http-plist response record grew a field this lowering does not carry: " + field.name());
			};
			src.append(' ').append(field.keyword()).append(' ').append(value);
		}
		src.append(")))\n");
	}

}
