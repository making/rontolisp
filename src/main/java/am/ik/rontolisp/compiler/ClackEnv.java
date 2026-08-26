package am.ik.rontolisp.compiler;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;
import am.ik.rontolisp.runtime.RontoClackEnv;

/**
 * The single source of truth for the SERVER-side HTTP value model: the environment plist
 * a {@code rontolisp:http-handler} handler receives. Since the Clack cutover that shape
 * IS Clack's application environment, so a Clack application is a rontolisp handler and
 * {@code clack.handler.rontolisp} converts nothing per request.
 *
 * <p>
 * The KEY SET and its order are declared once, in {@link RontoClackEnv} -- which this
 * class re-exports name for name, and which lives in {@code runtime} only because the JVM
 * backend's builder reads it at RUN time and travels with the compiled program
 * ({@code .kb/jvm-export.md}). Every backend loops {@link #FIELDS} and supplies only the
 * per-field value, which is the one part that cannot be shared (the interpreter and the
 * JVM read a JDK {@code HttpExchange}, the WASI component reads a {@code wasi:http}
 * request resource). A consumer that switches over the fields must throw on an unknown
 * one, so adding a key here fails each backend loudly until its value extraction is
 * supplied:
 *
 * <ul>
 * <li><strong>Interpreter</strong> -- {@code LispEvaluator.buildClackEnv}.</li>
 * <li><strong>JVM</strong> -- {@code JvmHttpHandlerRuntimeBuilder}, at codegen time, so
 * the emitted bytecode carries the keys in this order.</li>
 * <li><strong>WASM component</strong> -- {@code %http-make-env} in
 * {@code http-server.lisp}, whose key order this list mirrors.</li>
 * </ul>
 *
 * <p>
 * <strong>Why the construction is NOT shared Lisp.</strong> An earlier cut of this design
 * had every backend hand a raw tuple to one shared {@code %http-make-env} written in
 * rontolisp. It was measurably wrong: on the interpreter, building the environment in
 * interpreted Lisp cost 3.1x the throughput of building it in Java (18880 -> 6125 rps),
 * and it made the CLACK path -- the one the cutover exists to speed up -- 1.6x slower
 * too. The shape is shared; the construction is native to each backend.
 *
 * <p>
 * The one exception is {@code :raw-body} in its buffered form: it is a CLOS Gray stream
 * instance, which hand-written bytecode cannot build, so that single value comes from
 * {@code http-server.lisp}'s {@code %http-body-stream} -- and only when the request
 * actually has a body.
 *
 * <p>
 * Value contracts, per key: {@code :request-method} an upcased keyword;
 * {@code :script-name} the application's mount point, percent-decoded ({@code ""} on
 * every root-mounted transport -- only the Servlet war under a context path mounts one);
 * {@code :path-info} percent-decoded, with the raw mount prefix stripped BEFORE decoding;
 * {@code :query-string} the raw text after the first {@code ?} or nil; {@code :server-*}
 * from the {@code Host} header, falling back to the listening address;
 * {@code :server-protocol} a keyword; {@code :request-uri} the raw target verbatim;
 * {@code :remote-addr} / {@code :remote-port} the peer or nil (the WASI component has no
 * peer accessor at all); {@code :headers} an {@code equal} hash table with lowercased
 * names and repeated headers joined by {@code ", "}, never nil; {@code :content-length}
 * an integer or nil; {@code :raw-body} nil when the request has no body. The plist must
 * be freshly consed and proper on every request: lack-request appends to its last cons
 * and the mount / session middleware {@code setf getf} into it.
 */
public final class ClackEnv {

	/** {@code :request-method} -- the upcased method keyword ({@code :GET}). */
	public static final String REQUEST_METHOD = RontoClackEnv.REQUEST_METHOD;

	/**
	 * {@code :script-name} -- the application's mount point, percent-decoded; {@code ""}
	 * on a root-mounted transport (every transport but the Servlet war under a context
	 * path). Never nil.
	 */
	public static final String SCRIPT_NAME = RontoClackEnv.SCRIPT_NAME;

	/** {@code :path-info} -- the percent-decoded path, no query. */
	public static final String PATH_INFO = RontoClackEnv.PATH_INFO;

	/** {@code :query-string} -- the raw text after the first {@code ?}, or nil. */
	public static final String QUERY_STRING = RontoClackEnv.QUERY_STRING;

	/** {@code :server-name} -- the {@code Host} host part, else the bind address. */
	public static final String SERVER_NAME = RontoClackEnv.SERVER_NAME;

	/** {@code :server-port} -- an integer. */
	public static final String SERVER_PORT = RontoClackEnv.SERVER_PORT;

	/** {@code :server-protocol} -- {@code :HTTP/1.1} or {@code :HTTP/1.0}. */
	public static final String SERVER_PROTOCOL = RontoClackEnv.SERVER_PROTOCOL;

	/** {@code :request-uri} -- the request target verbatim, still encoded. */
	public static final String REQUEST_URI = RontoClackEnv.REQUEST_URI;

	/** {@code :url-scheme} -- {@code "http"} or {@code "https"}. */
	public static final String URL_SCHEME = RontoClackEnv.URL_SCHEME;

	/** {@code :remote-addr} -- the peer address, or nil. */
	public static final String REMOTE_ADDR = RontoClackEnv.REMOTE_ADDR;

	/** {@code :remote-port} -- the peer port, or nil. */
	public static final String REMOTE_PORT = RontoClackEnv.REMOTE_PORT;

	/** {@code :headers} -- an {@code equal} table, lowercased names, never nil. */
	public static final String HEADERS = RontoClackEnv.HEADERS;

	/** {@code :content-type} -- the full header value, or nil. */
	public static final String CONTENT_TYPE = RontoClackEnv.CONTENT_TYPE;

	/** {@code :content-length} -- an integer, or nil. */
	public static final String CONTENT_LENGTH = RontoClackEnv.CONTENT_LENGTH;

	/** {@code :raw-body} -- the request body stream, or nil when there is none. */
	public static final String RAW_BODY = RontoClackEnv.RAW_BODY;

	/** The environment keys, in cons order. */
	public static final List<String> FIELDS = RontoClackEnv.FIELDS;

	/**
	 * The Clack response normalizer defun in {@code http-server.lisp} (member name,
	 * {@code rontolisp}-internal). Declared here so the JVM compiler can name the
	 * compiled method without depending on {@code eval}'s {@code HttpServerLibrary}.
	 */
	public static final String NORMALIZE_RESPONSE = "%HTTP-NORMALIZE-RESPONSE";

	/**
	 * The buffered {@code :raw-body} constructor defun in {@code http-server.lisp}
	 * (member name, {@code rontolisp}-internal); present only when the program asked for
	 * {@code :raw-body :buffered}.
	 */
	public static final String BODY_STREAM = "%HTTP-BODY-STREAM";

	private ClackEnv() {
	}

	/**
	 * Returns whether any {@code rontolisp:http-handler} call (or an internal
	 * {@code rontolisp::%http-server-start} seam call, the
	 * {@code clack-handler-rontolisp} shim's interpreter/JVM leg) in the program asks for
	 * the buffered request body ({@code :raw-body :buffered}) -- what a Clack application
	 * needs. A program serves through ONE handler slot, so one flag describes the
	 * program. It lives here (not in {@code eval}) because the JVM compiler reads it too,
	 * and {@code codegen.jvm} must not depend on {@code eval}.
	 * @param program the top-level forms
	 * @return {@code true} when the buffered body was asked for
	 */
	public static boolean usesBufferedBody(List<LispVal> program) {
		for (LispVal form : program) {
			if (bufferedBodyIn(form)) {
				return true;
			}
		}
		return false;
	}

	private static boolean bufferedBodyIn(LispVal form) {
		if (!(form instanceof LispCons cons)) {
			return false;
		}
		if (cons.car() instanceof LispSymbol sym && LispNames.QUOTE.equals(sym.name())) {
			return false;
		}
		if (isServeForm(form)) {
			List<LispVal> args = cons.toList();
			for (int i = 1; i + 1 < args.size(); i++) {
				if (args.get(i) instanceof LispSymbol key && key.isKeyword()
						&& LispNames.RAW_BODY_KEYWORD.equalsIgnoreCase(key.name())
						&& args.get(i + 1) instanceof LispSymbol mode
						&& LispNames.BUFFERED_KEYWORD.equalsIgnoreCase(mode.name())) {
					return true;
				}
			}
			return false;
		}
		return bufferedBodyIn(cons.car()) || bufferedBodyIn(cons.cdr());
	}

	private static boolean isServeForm(LispVal form) {
		if (!(form instanceof LispCons cons) || !(cons.car() instanceof LispSymbol sym)) {
			return false;
		}
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(sym.name());
		return qn != null && LispNames.RONTOLISP_PKG.equals(qn.pkg())
				&& (LispNames.HTTP_HANDLER.equals(qn.member()) || LispNames.HTTP_SERVER_START.equals(qn.member()));
	}

}
