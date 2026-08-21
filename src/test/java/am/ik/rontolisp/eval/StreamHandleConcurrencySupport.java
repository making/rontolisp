package am.ik.rontolisp.eval;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Shared fixture for the "concurrent requests must not share a stream handle" tests on
 * the interpreter ({@code HttpHandlerTest}) and the JVM backend
 * ({@code HttpHandlerJvmTest}). Both backends allocate socket handles out of one
 * process-wide table ({@code Environment}'s stream map / the generated class'
 * {@code _streams} array), and {@code http-handler} runs one virtual thread per request,
 * so the allocation is genuinely concurrent -- this is the shape that lost connections
 * inside the PostgreSQL auth handshake.
 */
final class StreamHandleConcurrencySupport {

	private StreamHandleConcurrencySupport() {
	}

	/** How many requests are fired simultaneously per round. */
	static final int CONCURRENCY = 24;

	/** How many rounds the probe runs (each round re-fires all requests at once). */
	static final int ROUNDS = 3;

	/**
	 * Starts a line-echo server on an ephemeral port, one virtual thread per connection.
	 * Closing the returned listener stops the accept loop.
	 * @return the listening server socket (close it to shut the echo server down)
	 * @throws IOException if the listener cannot be bound
	 */
	static ServerSocket startEchoServer() throws IOException {
		ServerSocket listener = new ServerSocket(0);
		Thread.ofVirtual().start(() -> {
			while (!listener.isClosed()) {
				final Socket connection;
				try {
					connection = listener.accept();
				}
				catch (IOException ex) {
					return; // listener closed: the test is over
				}
				Thread.ofVirtual().start(() -> echoLines(connection));
			}
		});
		return listener;
	}

	private static void echoLines(Socket connection) {
		try (Socket socket = connection) {
			BufferedReader reader = new BufferedReader(
					new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
			OutputStream out = socket.getOutputStream();
			for (String line = reader.readLine(); line != null; line = reader.readLine()) {
				out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
				out.flush();
			}
		}
		catch (IOException ignored) {
			// the peer went away; nothing to echo
		}
	}

	/**
	 * The handler program both backends serve: every request opens its own TCP connection
	 * to the echo server, sends its own path as the payload, reads the echo back and
	 * answers {@code "<socket-handle> <echoed-path>"}. A handle shared between two
	 * in-flight requests therefore shows up twice -- and the crossed reads show up as a
	 * mismatched echo.
	 * @param echoPort the port of {@link #startEchoServer}
	 * @param servePort the port the handler serves on
	 * @return the rontolisp program text
	 */
	static String echoingHandlerProgram(int echoPort, int servePort) {
		return """
				(defun handle (env)
				  (let* ((token (getf env :path-info))
				         (sock (rontolisp:tcp-connect "127.0.0.1" %d)))
				    (write-line token sock)
				    (let ((reply (read-line sock)))
				      (close sock)
				      (list 200 nil (list (format nil "~A ~A" sock reply))))))
				(rontolisp:http-handler 'handle %d)
				""".formatted(echoPort, servePort);
	}

	/**
	 * Fires {@link #CONCURRENCY} requests at once, {@link #ROUNDS} times, against the
	 * served handler and returns every response body (or an {@code "ERROR: ..."} marker
	 * for a request that never completed).
	 * @param port the served port
	 * @return one entry per request, in completion-independent submission order
	 * @throws Exception if the probe itself fails
	 */
	static List<String> probeConcurrently(int port) throws Exception {
		List<String> bodies = new ArrayList<>();
		try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
			for (int round = 0; round < ROUNDS; round++) {
				CyclicBarrier startTogether = new CyclicBarrier(CONCURRENCY);
				List<Callable<String>> probes = new ArrayList<>();
				for (int i = 0; i < CONCURRENCY; i++) {
					String path = "/token-" + round + "-" + i;
					probes.add(() -> {
						startTogether.await();
						return get(port, path);
					});
				}
				for (Future<String> result : pool.invokeAll(probes)) {
					try {
						bodies.add(result.get());
					}
					catch (Exception ex) {
						bodies.add("ERROR: " + ex.getMessage());
					}
				}
			}
		}
		return bodies;
	}

	private static String get(int port, String path) throws Exception {
		HttpClient client = HttpClient.newHttpClient();
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
			.timeout(Duration.ofSeconds(20))
			.GET()
			.build();
		HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
		return response.statusCode() + " " + response.body();
	}

	/**
	 * Asserts the invariant on the bodies {@link #probeConcurrently} collected: every
	 * request got a 200 whose echoed token is its own path, and no two requests were
	 * handed the same socket handle.
	 * @param bodies the collected response bodies
	 */
	static void assertHandlesAreUnshared(List<String> bodies) {
		List<String> handles = new ArrayList<>();
		List<String> broken = new ArrayList<>();
		int round = 0;
		int index = 0;
		for (String body : bodies) {
			String expectedToken = "/token-" + round + "-" + index;
			// "200 <handle> <echoed token>"
			String[] parts = body.split(" ");
			if (parts.length != 3 || !"200".equals(parts[0]) || !expectedToken.equals(parts[2])) {
				broken.add(expectedToken + " -> " + body);
			}
			else {
				handles.add(parts[1]);
			}
			if (++index == CONCURRENCY) {
				index = 0;
				round++;
			}
		}
		org.assertj.core.api.Assertions.assertThat(broken)
			.as("requests whose echo came back wrong or failed")
			.isEmpty();
		org.assertj.core.api.Assertions.assertThat(handles)
			.as("socket handles handed out to concurrent requests")
			.doesNotHaveDuplicates();
	}

}
