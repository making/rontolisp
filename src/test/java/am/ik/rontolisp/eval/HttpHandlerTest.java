package am.ik.rontolisp.eval;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import am.ik.rontolisp.reader.LispReader;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

	@Test
	void stoppableSeamServesJoinsAndStops() throws Exception {
		// The %http-server-* seam behind the clack-handler-rontolisp shim: start on an
		// ephemeral port with a bind address, read the port back, serve, stop -- and
		// the stop releases a blocked joiner.
		long handle = HttpHandlerSupport.startServer(0, "127.0.0.1",
				request -> new HttpHandlerSupport.Response(200, List.of(), "stoppable " + request.path()));
		int port = (int) HttpHandlerSupport.serverPort(handle);
		assertThat(port).isPositive();
		assertThat(get(port, "/x").body()).isEqualTo("stoppable /x");
		Thread joiner = Thread.ofVirtual().start(() -> HttpHandlerSupport.joinServer(handle));
		HttpHandlerSupport.stopServer(handle);
		joiner.join(Duration.ofSeconds(5));
		assertThat(joiner.isAlive()).isFalse();
		assertThat(HttpHandlerSupport.serverPort(handle)).isEqualTo(-1);
		// Idempotent: a second stop (the unwind cleanup after an explicit clack:stop)
		// is a no-op.
		HttpHandlerSupport.stopServer(handle);
		assertThatThrownBy(() -> get(port, "/x")).isInstanceOf(IOException.class);
	}

	@Test
	void joinServerReturnsWhenTheJoinerIsInterrupted() throws Exception {
		// clack's :use-thread t stop path destroy-threads the acceptor: the interrupt
		// must land in the join and return normally so the Lisp unwind-protect stops
		// the server in an orderly unwind.
		long handle = HttpHandlerSupport.startServer(0, "127.0.0.1",
				request -> new HttpHandlerSupport.Response(200, List.of(), "ok"));
		Thread joiner = Thread.ofVirtual().start(() -> HttpHandlerSupport.joinServer(handle));
		Thread.sleep(50);
		joiner.interrupt();
		joiner.join(Duration.ofSeconds(5));
		assertThat(joiner.isAlive()).isFalse();
		HttpHandlerSupport.stopServer(handle);
	}

	@Test
	void startServerUnwrapsAQuoteWrappedAddress() throws Exception {
		// The JVM backend passes its runtime string rep (quote-wrapped) as-is.
		long handle = HttpHandlerSupport.startServer(0, "\"127.0.0.1\"",
				request -> new HttpHandlerSupport.Response(200, List.of(), "wrapped"));
		int port = (int) HttpHandlerSupport.serverPort(handle);
		assertThat(get(port, "/").body()).isEqualTo("wrapped");
		HttpHandlerSupport.stopServer(handle);
	}

	@Test
	void interpreterSeamFunctionsRoundTrip() throws Exception {
		// The rontolisp::%http-server-* functions on the interpreter: start with a
		// function value, read the ephemeral port back, serve, stop.
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(out, true));
		for (var expr : LispReader.readAllFromString("""
				(defun seam-handler (req)
				  (list :status 200 :headers nil :body (getf req :path)))
				(defvar *server* (rontolisp::%http-server-start #'seam-handler 0 "127.0.0.1"))
				(print (rontolisp::%http-server-port *server*))
				""")) {
			evaluator.eval(expr);
		}
		int port = Integer.parseInt(out.toString().trim());
		assertThat(get(port, "/from-lisp").body()).isEqualTo("/from-lisp");
		for (var expr : LispReader.readAllFromString("""
				(rontolisp::%http-server-stop *server*)
				(rontolisp::%http-server-join *server*)
				""")) {
			evaluator.eval(expr);
		}
		assertThatThrownBy(() -> get(port, "/from-lisp")).isInstanceOf(IOException.class);
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
	void requestOfSplitsPathAndQueryAtTheFirstQuestionMark() {
		HttpHandlerSupport.Request withQuery = HttpHandlerSupport.Request.of("GET", "/get?a=1&b=?x", List.of(), "");
		assertThat(withQuery.path()).isEqualTo("/get");
		assertThat(withQuery.query()).isEqualTo("a=1&b=?x");
		HttpHandlerSupport.Request without = HttpHandlerSupport.Request.of("GET", "/get", List.of(), "");
		assertThat(without.path()).isEqualTo("/get");
		assertThat(without.query()).isNull();
		HttpHandlerSupport.Request emptyQuery = HttpHandlerSupport.Request.of("GET", "/get?", List.of(), "");
		assertThat(emptyQuery.path()).isEqualTo("/get");
		assertThat(emptyQuery.query()).isEmpty();
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
	void directiveSplitsPathAndQuery() throws Exception {
		int port = freePort();
		serveInBackground("""
				(defun handle (request)
				  (list :status 200
				        :body (concatenate 'string "path=" (getf request :path)
				                           " query=" (if (getf request :query) (getf request :query) "none"))))
				(rontolisp:http-handler 'handle %d)
				""".formatted(port), port);
		assertThat(get(port, "/hello?a=1&b=two").body()).isEqualTo("path=/hello query=a=1&b=two");
		assertThat(get(port, "/hello").body()).isEqualTo("path=/hello query=none");
	}

	@Test
	void directiveEchoesMethodAndBody() throws Exception {
		// the request body is an asynchronous stream, so a handler that reads it is an
		// async-defun draining it with read-all; the server awaits the handler's future
		int port = freePort();
		serveInBackground("""
				(rontolisp:async-defun handle (request)
				  (list :status 200
				        :body (concatenate 'string (getf request :method) ":"
				                           (rontolisp:await (rontolisp:read-all (getf request :body))))))
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

	// The wasi:keyvalue store, cut to what a page-hit counter binds. The component built
	// from the same source imports this interface from its host; here the provider below
	// answers it.
	private static final String KEYVALUE_WIT = """
			package wasi:keyvalue@0.2.0-draft;

			interface store {
			  variant error {
			    no-such-store,
			    other(string)
			  }

			  open: func(identifier: string) -> result<bucket, error>;

			  resource bucket {
			    get: func(key: string) -> result<option<list<u8>>, error>;
			    set: func(key: string, value: list<u8>) -> result<_, error>;
			  }
			}
			""";

	@Test
	void aServedHandlerCountsHitsInAWitImportedStore(@TempDir Path tempDir) throws Exception {
		// The pairing rontolisp:http-handler and rontolisp:wit-import make possible: a
		// handler whose state lives OUTSIDE it, in whatever implements the interface. The
		// store outlives the request here (one process, one provider), so the counter
		// accumulates -- the property a process-local hash table cannot give a served
		// component, whose instance a wasi:http host recreates per request.
		Path wit = tempDir.resolve("kv.wit");
		Files.writeString(wit, KEYVALUE_WIT);
		int port = freePort();
		serveInBackground("""
				(rontolisp:wit-import "%s" :interface "wasi:keyvalue/store@0.2.0-draft" :package kv)

				(defvar *pages* (make-hash-table :test #'equal))
				(defun page-store (member &rest args)
				  (cond ((string= member "open") 1)
				        ((string= member "bucket-get") (gethash (nth 1 args) *pages*))
				        ((string= member "bucket-set")
				         (setf (gethash (nth 1 args) *pages*) (nth 2 args))
				         nil)
				        (t (error 'rontolisp:wit-error :payload :other :message member))))
				(rontolisp:wit-provide "wasi:keyvalue/store@0.2.0-draft" #'page-store)

				(defun handle (request)
				  (let* ((page (getf request :path))
				         (bucket (kv:open ""))
				         (seen (kv:bucket-get bucket page)))
				    (kv:bucket-set bucket page
				                   (princ-to-string (+ 1 (if seen (parse-integer seen) 0))))
				    (list :status 200
				          :body (concatenate 'string page " " (kv:bucket-get bucket page)))))
				(rontolisp:http-handler 'handle %d)
				""".formatted(wit.toString().replace("\\", "\\\\"), port), port);
		assertThat(get(port, "/index").body()).isEqualTo("/index 1");
		assertThat(get(port, "/index").body()).isEqualTo("/index 2");
		assertThat(get(port, "/pricing").body()).isEqualTo("/pricing 1");
		assertThat(get(port, "/index").body()).isEqualTo("/index 3");
	}

	private static LispEvaluator evaluator() {
		return new LispEvaluator(new PrintStream(new ByteArrayOutputStream()));
	}

	@Test
	void concurrentRequestsGetTheirOwnSocketHandle() throws Exception {
		// One virtual thread per request means the global stream table is allocated from
		// concurrently: a non-atomic handle counter (and an unsynchronized map) hands two
		// requests the same handle, so one socket is dropped and the two conversations
		// cross -- the shape that lost PostgreSQL connections inside auth (.todo/193).
		try (ServerSocket echo = StreamHandleConcurrencySupport.startEchoServer()) {
			int port = freePort();
			serveInBackground(StreamHandleConcurrencySupport.echoingHandlerProgram(echo.getLocalPort(), port), port);
			StreamHandleConcurrencySupport
				.assertHandlesAreUnshared(StreamHandleConcurrencySupport.probeConcurrently(port));
		}
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
