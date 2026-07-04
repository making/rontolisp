package am.ik.rontolisp.eval;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import am.ik.rontolisp.reader.LispReader;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@code rontolisp:http-handler} on the interpreter backend: the embedded HTTP
 * server seam ({@link HttpHandlerSupport#start}) and the end-to-end directive round trip.
 */
class HttpHandlerTest {

	@AfterEach
	void shutDownServers() {
		HttpHandlerSupport.stopAllForTesting();
	}

	// A free TCP port (bound then released; a tiny race, acceptable for tests).
	private static int freePort() throws IOException {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
	}

	private static HttpResponse<String> get(int port, String path) throws Exception {
		HttpClient client = HttpClient.newHttpClient();
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build();
		return client.send(request, HttpResponse.BodyHandlers.ofString());
	}

	private static HttpResponse<String> post(int port, String path, String body) throws Exception {
		HttpClient client = HttpClient.newHttpClient();
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
			.POST(HttpRequest.BodyPublishers.ofString(body))
			.build();
		return client.send(request, HttpResponse.BodyHandlers.ofString());
	}

	@Test
	void startServesHandlerResponse() throws Exception {
		HttpServer server = HttpHandlerSupport.start(0,
				request -> new HttpHandlerSupport.Response(201,
						List.of(new HttpHandlerSupport.Header("content-type", "text/plain")),
						"hello " + request.method() + " " + request.path()));
		int port = server.getAddress().getPort();
		HttpResponse<String> response = get(port, "/greet");
		assertThat(response.statusCode()).isEqualTo(201);
		assertThat(response.body()).isEqualTo("hello GET /greet");
		assertThat(response.headers().firstValue("content-type")).hasValue("text/plain");
	}

	// Runs a rontolisp program on a background thread (http-handler blocks) and waits
	// until
	// the port is accepting connections.
	private static void serveInBackground(String program, int port) throws Exception {
		Thread thread = new Thread(() -> {
			LispEvaluator evaluator = new LispEvaluator(new PrintStream(new ByteArrayOutputStream()));
			for (var expr : LispReader.readAllFromString(program)) {
				evaluator.eval(expr);
			}
		});
		thread.setDaemon(true);
		thread.start();
		waitForPort(port);
	}

	private static void waitForPort(int port) throws InterruptedException {
		long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
		while (System.nanoTime() < deadline) {
			try (var socket = new java.net.Socket("127.0.0.1", port)) {
				return;
			}
			catch (IOException ex) {
				Thread.sleep(20);
			}
		}
		throw new IllegalStateException("port " + port + " never opened");
	}

	@Test
	void directiveServesRequestPlist() throws Exception {
		int port = freePort();
		serveInBackground("""
				(defun handle (request)
				  (list :status 200
				        :headers (list (cons "content-type" "text/plain"))
				        :body (concatenate 'string "path=" (getf request :path))))
				(rontolisp:http-handler 'handle %d)
				""".formatted(port), port);
		HttpResponse<String> response = get(port, "/hello");
		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body()).isEqualTo("path=/hello");
		assertThat(response.headers().firstValue("content-type")).hasValue("text/plain");
	}

	@Test
	void directiveEchoesMethodAndBody() throws Exception {
		int port = freePort();
		serveInBackground("""
				(defun handle (request)
				  (list :status 200
				        :body (concatenate 'string (getf request :method) ":" (getf request :body))))
				(rontolisp:http-handler 'handle %d)
				""".formatted(port), port);
		HttpResponse<String> response = post(port, "/", "payload");
		assertThat(response.body()).isEqualTo("POST:payload");
	}

	@Test
	void directiveDefaultsStatusTo200() throws Exception {
		int port = freePort();
		serveInBackground("""
				(defun handle (request) (list :body "ok"))
				(rontolisp:http-handler 'handle %d)
				""".formatted(port), port);
		HttpResponse<String> response = get(port, "/");
		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body()).isEqualTo("ok");
	}

	private static LispEvaluator evaluator() {
		return new LispEvaluator(new PrintStream(new ByteArrayOutputStream()));
	}

	@Test
	void directiveRejectsWrongArgCount() {
		assertThatThrownBy(() -> {
			LispEvaluator evaluator = evaluator();
			for (var expr : LispReader.readAllFromString("(defun h (r) nil) (rontolisp:http-handler 'h 1 2)")) {
				evaluator.eval(expr);
			}
		}).isInstanceOf(LispEvalException.class).hasMessageContaining("expects 1 or 2 arguments");
	}

	@Test
	void directiveRejectsNonIntegerPort() {
		assertThatThrownBy(() -> {
			LispEvaluator evaluator = evaluator();
			for (var expr : LispReader.readAllFromString("(defun h (r) nil) (rontolisp:http-handler 'h \"x\")")) {
				evaluator.eval(expr);
			}
		}).isInstanceOf(LispEvalException.class).hasMessageContaining("expects an integer port");
	}

}
