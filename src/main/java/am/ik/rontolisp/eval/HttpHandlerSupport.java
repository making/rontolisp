package am.ik.rontolisp.eval;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import am.ik.rontolisp.LispNames;

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
 * not used: the module instead exports {@code wasi:http/incoming-handler} so a host
 * ({@code wasmtime serve} / Spin) drives the handler.
 */
public final class HttpHandlerSupport {

	private HttpHandlerSupport() {
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
	 * An incoming HTTP request handed to the Lisp handler.
	 *
	 * @param method the HTTP method (e.g. {@code GET})
	 * @param path the request path including any query string
	 * @param headers the request headers
	 * @param body the request body (empty string if none)
	 */
	public record Request(String method, String path, List<Header> headers, String body) {
	}

	/**
	 * The HTTP response the Lisp handler returns.
	 *
	 * @param status the HTTP status code
	 * @param headers the response headers
	 * @param body the response body (empty string for none)
	 */
	public record Response(int status, List<Header> headers, String body) {
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
			throw new LispEvalException(
					LispNames.HTTP_HANDLER + ": failed to bind port " + port + ": " + ex.getMessage());
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
	static void stopAllForTesting() {
		for (HttpServer server : SERVERS) {
			server.stop(0);
		}
		SERVERS.clear();
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
				writeResponse(exchange, new Response(500, List.of(), "Internal Server Error"));
				return;
			}
			writeResponse(exchange, response);
		}
	}

	private static Request readRequest(HttpExchange exchange) throws IOException {
		final String method = exchange.getRequestMethod();
		final String path = exchange.getRequestURI().toString();
		final List<Header> headers = new ArrayList<>();
		for (Map.Entry<String, List<String>> entry : exchange.getRequestHeaders().entrySet()) {
			for (String value : entry.getValue()) {
				headers.add(new Header(entry.getKey(), value));
			}
		}
		final byte[] body = exchange.getRequestBody().readAllBytes();
		return new Request(method, path, headers, new String(body, StandardCharsets.UTF_8));
	}

	private static void writeResponse(HttpExchange exchange, Response response) throws IOException {
		for (Header header : response.headers()) {
			exchange.getResponseHeaders().add(header.name(), header.value());
		}
		final byte[] body = response.body().getBytes(StandardCharsets.UTF_8);
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
