package am.ik.rontolisp.runtime;

import java.util.List;

/**
 * The KEY SET of the Clack environment plist a {@code rontolisp:http-handler} handler
 * receives, and its cons order -- declared once, here, for every backend that builds one.
 *
 * <p>
 * It lives in {@code runtime} (not beside the AST-scanning half in
 * {@code compiler.ClackEnv}, which re-exports every name below) for the reason every
 * class in this package does: {@link RontoHttpClack} reads it at RUN time, and the class
 * files of this package travel with a compiled program so it needs no rontolisp jar on
 * its classpath. A rontolisp import here would drag the interpreter along behind it, so
 * the declaration is plain strings and nothing else.
 *
 * <p>
 * A consumer that switches over {@link #FIELDS} must throw on an unknown field, so adding
 * a key here fails each backend loudly until its value extraction is written:
 * {@code LispEvaluator.buildClackEnv} (interpreter), {@link RontoHttpClack#buildEnv}
 * (JVM), {@code %http-make-env} in {@code http-server.lisp} (WASM component).
 */
public final class RontoClackEnv {

	/** {@code :request-method} -- the upcased method keyword ({@code :GET}). */
	public static final String REQUEST_METHOD = ":REQUEST-METHOD";

	/** {@code :script-name} -- always the empty string, never nil. */
	public static final String SCRIPT_NAME = ":SCRIPT-NAME";

	/** {@code :path-info} -- the percent-decoded path, no query. */
	public static final String PATH_INFO = ":PATH-INFO";

	/** {@code :query-string} -- the raw text after the first {@code ?}, or nil. */
	public static final String QUERY_STRING = ":QUERY-STRING";

	/** {@code :server-name} -- the {@code Host} host part, else the bind address. */
	public static final String SERVER_NAME = ":SERVER-NAME";

	/** {@code :server-port} -- an integer. */
	public static final String SERVER_PORT = ":SERVER-PORT";

	/** {@code :server-protocol} -- {@code :HTTP/1.1} or {@code :HTTP/1.0}. */
	public static final String SERVER_PROTOCOL = ":SERVER-PROTOCOL";

	/** {@code :request-uri} -- the request target verbatim, still encoded. */
	public static final String REQUEST_URI = ":REQUEST-URI";

	/** {@code :url-scheme} -- {@code "http"} or {@code "https"}. */
	public static final String URL_SCHEME = ":URL-SCHEME";

	/** {@code :remote-addr} -- the peer address, or nil. */
	public static final String REMOTE_ADDR = ":REMOTE-ADDR";

	/** {@code :remote-port} -- the peer port, or nil. */
	public static final String REMOTE_PORT = ":REMOTE-PORT";

	/** {@code :headers} -- an {@code equal} table, lowercased names, never nil. */
	public static final String HEADERS = ":HEADERS";

	/** {@code :content-type} -- the full header value, or nil. */
	public static final String CONTENT_TYPE = ":CONTENT-TYPE";

	/** {@code :content-length} -- an integer, or nil. */
	public static final String CONTENT_LENGTH = ":CONTENT-LENGTH";

	/** {@code :raw-body} -- the request body stream, or nil when there is none. */
	public static final String RAW_BODY = ":RAW-BODY";

	/** The environment keys, in cons order. */
	public static final List<String> FIELDS = List.of(REQUEST_METHOD, SCRIPT_NAME, PATH_INFO, QUERY_STRING, SERVER_NAME,
			SERVER_PORT, SERVER_PROTOCOL, REQUEST_URI, URL_SCHEME, REMOTE_ADDR, REMOTE_PORT, HEADERS, CONTENT_TYPE,
			CONTENT_LENGTH, RAW_BODY);

	private RontoClackEnv() {
	}

}
