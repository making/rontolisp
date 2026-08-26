package am.ik.rontolisp.runtime;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsExchange;

/**
 * Runs a blocking embedded HTTP server for the {@code rontolisp:http-handler} directive
 * on the interpreter and JVM backends, using the JDK {@link HttpServer}. Each request is
 * adapted to a backend-neutral {@link Request}, passed to a {@link Handler} (the compiled
 * or interpreted Lisp handler function, wrapped by the caller), and the returned
 * {@link Response} is written back. The same request/response shape mirrors
 * {@code rontolisp:fetch} so one HTTP value model spans incoming and outgoing requests.
 *
 * <p>
 * When the program is compiled to a WASI component ({@code --component}) this class is
 * not used: the module instead exports {@code wasi:http/incoming-handler} so a host (e.g.
 * {@code wasmtime serve}) drives the handler.
 *
 * <p>
 * It lives in {@code runtime} rather than in {@code eval} because a compiled
 * {@code http-handler} class CALLS it: the class files of this package travel with the
 * compiled output ({@code .kb/jvm-export.md}), which is what makes such a program run on
 * a bare {@code java -cp .} instead of needing the rontolisp jar on the classpath. That
 * is also why it imports nothing of the project's: a rontolisp name would drag the
 * interpreter along behind it. The one name it would want,
 * {@code LispNames.HTTP_HANDLER}, is spelled out in {@link #WHERE}.
 */
public final class RontoHttpServer {

	/**
	 * The operator this server serves, for an error message --
	 * {@code LispNames.HTTP_HANDLER} spelled out, because this package imports nothing.
	 */
	private static final String WHERE = "HTTP-HANDLER";

	private RontoHttpServer() {
	}

	/**
	 * A server-side failure this class raises -- today a bind that could not be made. It
	 * is NOT {@code eval}'s {@code LispEvalException}: this package carries no rontolisp
	 * import, so the interpreter catches this at its call sites and re-raises the Lisp
	 * error, while a compiled program (which has no condition system around the bind)
	 * lets it propagate as it did before.
	 */
	public static final class ServerException extends RuntimeException {

		private final String reason;

		/**
		 * Creates the failure.
		 * @param reason what went wrong
		 */
		public ServerException(String reason) {
			super(reason);
			this.reason = reason;
		}

		/**
		 * What went wrong -- the same text {@code getMessage()} answers, but never
		 * absent, so a caller can hand it straight to its own error constructor.
		 * @return the reason
		 */
		public String reason() {
			return this.reason;
		}

	}

	/**
	 * A single HTTP header (name and value).
	 *
	 * @param name the header name
	 * @param value the header value
	 */
	public record Header(String name, String value) {
	}

	/**
	 * The transport facts of one incoming HTTP request -- everything only this server can
	 * know. It is NOT the value the handler sees: the shared {@code http-server.lisp}
	 * library turns it into the Clack environment (splitting the target, percent-decoding
	 * the path, building the header table, ...), so that shape is written once for every
	 * backend instead of once here.
	 *
	 * @param method the HTTP method (e.g. {@code GET})
	 * @param target the request target exactly as sent, query included and still
	 * percent-encoded (e.g. {@code /get?a=1})
	 * @param headers the request headers in wire order, duplicates kept
	 * @param body the request body octets, exactly as received (empty if none) -- bytes,
	 * not a string, so a binary body (a multipart file part) survives the trip into the
	 * buffered {@code :raw-body}
	 * @param protocol the HTTP version token (e.g. {@code HTTP/1.1})
	 * @param scheme {@code http} or {@code https}
	 * @param localName the address this server is bound to, or {@code ""} when unknown
	 * @param localPort the port this server is bound to
	 * @param remoteAddr the peer address, or {@code ""} when unknown
	 * @param remotePort the peer port, or {@code 0} when unknown
	 * @param scriptName the application's mount point as a RAW prefix of {@code target}
	 * (still percent-encoded, the Servlet transport's context path + servlet path), or
	 * {@code ""} on a root-mounted transport. Only the transport knows where it is
	 * mounted, so the value enters here, with the other transport facts: the environment
	 * build splits it into {@code :script-name} / {@code :path-info}
	 */
	public record Request(String method, String target, List<Header> headers, byte[] body, String protocol,
			String scheme, String localName, int localPort, String remoteAddr, int remotePort, String scriptName) {

		/**
		 * Creates a request carrying only what a test needs, defaulting the transport
		 * facts (HTTP/1.1 over http, no known peer).
		 * @param method the HTTP method
		 * @param target the request target as sent
		 * @param headers the request headers
		 * @param body the request body text (encoded as UTF-8)
		 * @return the request
		 */
		public static Request of(String method, String target, List<Header> headers, String body) {
			return new Request(method, target, headers, body.getBytes(StandardCharsets.UTF_8), "HTTP/1.1", "http", "",
					0, "", 0, "");
		}

		/**
		 * Returns the body octets decoded as UTF-8 -- what the default asynchronous
		 * {@code :raw-body} stream carries.
		 * @return the body as text
		 */
		public String bodyString() {
			return new String(this.body, StandardCharsets.UTF_8);
		}

	}

	/**
	 * The HTTP response the Lisp handler returns.
	 *
	 * @param status the HTTP status code
	 * @param headers the response headers
	 * @param body the response body octets, exactly as they go on the wire (empty if
	 * none) -- bytes, not a string, so an {@code (unsigned-byte 8)} body survives the
	 * trip out instead of being flattened to characters and re-encoded. The same shape
	 * {@link Request} carries for the same reason.
	 */
	public record Response(int status, List<Header> headers, byte[] body) {

		/**
		 * Creates a response whose body is TEXT, encoded as UTF-8 -- the ordinary
		 * string-body case.
		 * @param status the HTTP status code
		 * @param headers the response headers
		 * @param body the response body text
		 * @return the response
		 */
		public static Response of(int status, List<Header> headers, String body) {
			return new Response(status, headers, body.getBytes(StandardCharsets.UTF_8));
		}

	}

	/** Adapts an incoming {@link Request} to a {@link Response}. */
	@FunctionalInterface
	public interface Handler {

		/**
		 * Handles one incoming HTTP request.
		 * @param request the incoming request
		 * @return the response to write back
		 */
		Response handle(Request request);

	}

	// Servers started in this process, tracked so tests can shut them all down.
	private static final List<HttpServer> SERVERS = new CopyOnWriteArrayList<>();

	// A stoppable server: the HttpServer plus the latch a joiner blocks on until the
	// server is stopped. The Lisp-visible handle is an opaque integer index into
	// HANDLES (the socket/mutex handle convention -- nothing portable may print or
	// compare one), so the interpreter and the JVM backend share one representation
	// (a boxed integer).
	private record StoppableServer(HttpServer server, CountDownLatch stopped) {
	}

	private static final Map<Long, StoppableServer> HANDLES = new ConcurrentHashMap<>();

	private static final AtomicLong NEXT_HANDLE = new AtomicLong(1);

	/**
	 * Starts an embedded HTTP server bound to the given address and port and returns an
	 * opaque handle for {@link #joinServer} / {@link #stopServer} -- the stoppable seam
	 * behind the internal {@code rontolisp::%http-server-start} function (the
	 * {@code clack-handler-rontolisp} backend's acceptor). Unlike the
	 * {@code http-handler} directive's {@link #serve}, the server is per-handle stoppable
	 * and binds the given address instead of the wildcard.
	 * @param port the TCP port to bind (0 = ephemeral)
	 * @param address the bind address (a hostname or IP literal); anything that is not a
	 * non-empty string -- {@code ""} from the interpreter, {@code null} from compiled
	 * bytecode, where nil IS null -- binds the wildcard address. A JVM-runtime
	 * quote-wrapped string ({@code "\"host\""}) is accepted and unwrapped, so the
	 * compiled backend can pass its string value rep as-is.
	 * @param handler the request handler
	 * @return the opaque server handle
	 */
	public static long startServer(int port, Object address, Handler handler) {
		String bindAddress = null;
		if (address instanceof String str && !str.isEmpty()) {
			bindAddress = str.length() >= 2 && str.startsWith("\"") && str.endsWith("\"")
					? str.substring(1, str.length() - 1) : str;
		}
		final HttpServer server;
		try {
			server = HttpServer.create(
					bindAddress == null ? new InetSocketAddress(port) : new InetSocketAddress(bindAddress, port), 0);
		}
		catch (IOException ex) {
			throw new ServerException(WHERE + ": failed to bind "
					+ (bindAddress == null ? "port " + port : bindAddress + ":" + port) + ": " + ex.getMessage());
		}
		server.createContext("/", exchange -> dispatch(exchange, handler));
		server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
		server.start();
		SERVERS.add(server);
		long handle = NEXT_HANDLE.getAndIncrement();
		HANDLES.put(handle, new StoppableServer(server, new CountDownLatch(1)));
		return handle;
	}

	/**
	 * Returns the local port the given server is bound to (the ephemeral-port readback
	 * for port 0), or {@code -1} for an unknown or already-stopped handle.
	 * @param handle a handle returned by {@link #startServer}
	 * @return the bound port, or {@code -1}
	 */
	public static long serverPort(long handle) {
		StoppableServer stoppable = HANDLES.get(handle);
		return stoppable == null ? -1 : stoppable.server().getAddress().getPort();
	}

	/**
	 * Blocks the calling thread until the given server is stopped ({@link #stopServer})
	 * or the thread is interrupted ({@code rontolisp:destroy-thread} on the acceptor
	 * thread -- clack's {@code :use-thread t} stop path); both return normally, so a Lisp
	 * {@code unwind-protect} around the join runs its cleanup in an orderly unwind. An
	 * unknown or already-stopped handle returns immediately.
	 * @param handle a handle returned by {@link #startServer}
	 */
	public static void joinServer(long handle) {
		StoppableServer stoppable = HANDLES.get(handle);
		if (stoppable == null) {
			return;
		}
		try {
			stoppable.stopped().await();
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

	/**
	 * Stops the given server and releases its joiners. Idempotent: an unknown or
	 * already-stopped handle is a no-op, so the acceptor's {@code unwind-protect}-cleanup
	 * stop and an explicit {@code clack:stop} cannot double-fault.
	 * @param handle a handle returned by {@link #startServer}
	 */
	public static void stopServer(long handle) {
		StoppableServer stoppable = HANDLES.remove(handle);
		if (stoppable == null) {
			return;
		}
		stoppable.server().stop(0);
		SERVERS.remove(stoppable.server());
		stoppable.stopped().countDown();
	}

	/**
	 * Start an embedded HTTP server bound to the given port and return it immediately
	 * (non-blocking). All request paths route to {@code handler}. Passing port 0 binds an
	 * ephemeral port (readable via {@code server.getAddress().getPort()}); this is the
	 * seam the tests use.
	 * @param port the TCP port to bind (0 = ephemeral)
	 * @param handler the request handler
	 * @return the started server
	 */
	public static HttpServer start(int port, Handler handler) {
		final HttpServer server;
		try {
			server = HttpServer.create(new InetSocketAddress(port), 0);
		}
		catch (IOException ex) {
			throw new ServerException(WHERE + ": failed to bind port " + port + ": " + ex.getMessage());
		}
		server.createContext("/", exchange -> dispatch(exchange, handler));
		// One virtual thread per request; virtual threads are always daemon, so leftover
		// servers never keep the JVM alive.
		server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
		server.start();
		SERVERS.add(server);
		return server;
	}

	/**
	 * Start an embedded HTTP server and block the calling thread forever (serving until
	 * the process is stopped, e.g. with Ctrl-C). This is what the
	 * {@code rontolisp:http-handler} directive calls on the interpreter and JVM backends.
	 * @param port the TCP port to bind
	 * @param handler the request handler
	 */
	public static void serve(int port, Handler handler) {
		start(port, handler);
		try {
			// Serve forever; the JVM exits on SIGINT regardless of this latch.
			new CountDownLatch(1).await();
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

	/** Stops every server started in this process. Intended for tests only. */
	public static void stopAllForTesting() {
		for (HttpServer server : SERVERS) {
			server.stop(0);
		}
		SERVERS.clear();
		for (Long handle : HANDLES.keySet()) {
			stopServer(handle);
		}
	}

	// Reads the request, invokes the handler and writes the response. A handler that
	// throws
	// yields a 500 so a single bad request does not take the server down.
	private static void dispatch(HttpExchange exchange, Handler handler) throws IOException {
		try (exchange) {
			final Request request = readRequest(exchange);
			final Response response;
			try {
				response = handler.handle(request);
			}
			catch (RuntimeException ex) {
				// A handler that dies must not take the server down -- but it must not
				// vanish either: without this the only trace of a broken handler (or of a
				// response the shared normalizer rejected) was a bare 500.
				System.err.println(WHERE + ": handler failed: " + ex);
				writeResponse(exchange,
						Response.of(500, List.of(new Header("content-type", "text/plain")), "Internal Server Error"));
				return;
			}
			writeResponse(exchange, response);
		}
	}

	private static Request readRequest(HttpExchange exchange) throws IOException {
		final String method = exchange.getRequestMethod();
		final String target = exchange.getRequestURI().toString();
		final List<Header> headers = new ArrayList<>();
		for (Map.Entry<String, List<String>> entry : exchange.getRequestHeaders().entrySet()) {
			for (String value : entry.getValue()) {
				headers.add(new Header(entry.getKey(), value));
			}
		}
		final byte[] body = exchange.getRequestBody().readAllBytes();
		final InetSocketAddress local = exchange.getLocalAddress();
		final InetSocketAddress remote = exchange.getRemoteAddress();
		// scriptName "": this server owns its whole port, so the application is
		// root-mounted by construction.
		return new Request(method, target, headers, body, exchange.getProtocol(),
				exchange instanceof HttpsExchange ? "https" : "http", local == null ? "" : local.getHostString(),
				local == null ? 0 : local.getPort(),
				remote == null || remote.getAddress() == null ? "" : remote.getAddress().getHostAddress(),
				remote == null ? 0 : remote.getPort(), "");
	}

	private static void writeResponse(HttpExchange exchange, Response response) throws IOException {
		for (Header header : response.headers()) {
			exchange.getResponseHeaders().add(header.name(), header.value());
		}
		final byte[] body = response.body();
		// A zero-length response must pass -1 (no body) to HttpServer, not 0.
		final long contentLength = body.length == 0 ? -1 : body.length;
		exchange.sendResponseHeaders(response.status(), contentLength);
		if (body.length > 0) {
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(body);
			}
		}
	}

}
