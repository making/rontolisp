package am.ik.rontolisp.eval;

import am.ik.rontolisp.runtime.RontoHttpServer;
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
 * server seam ({@link RontoHttpServer#start}) and the end-to-end directive round trip.
 */
class HttpHandlerTest {

	@AfterEach
	void shutDownServers() {
		RontoHttpServer.stopAllForTesting();
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

	private static HttpResponse<byte[]> getBytes(int port, String path) throws Exception {
		HttpClient client = HttpClient.newHttpClient();
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build();
		return client.send(request, HttpResponse.BodyHandlers.ofByteArray());
	}

	// The (unsigned-byte 8) response body every backend's round-trip test serves: three
	// octets, two of them >= #x80, so a transport that flattens them into characters and
	// UTF-8 encodes the result answers five bytes instead of three.
	static final String OCTET_BODY_PROGRAM = """
			(defun handle (env)
			  (let ((v (make-array 3 :element-type '(unsigned-byte 8))))
			    (setf (aref v 0) 255)
			    (setf (aref v 1) 254)
			    (setf (aref v 2) 65)
			    (list 200 (list :content-type "application/octet-stream") v)))
			""";

	// The relay every backend's round-trip test serves: /relay answers a fetched reply's
	// :body STREAM as its own response body -- the proxy shape, nothing reads the
	// stream -- and /text drains the same upstream's reply through read-all. The
	// upstream port rides the query string. What has to hold: the relay answers the
	// upstream's exact octets (a body stream is a BYTE stream, so nothing decodes on
	// the way through), and read-all still answers the decoded text.
	static final String RELAY_PROGRAM = """
			(rontolisp:async-defun handle (env)
			  (let* ((upstream (concatenate 'string "http://127.0.0.1:" (getf env :query-string)))
			         (path (getf env :path-info)))
			    (if (string= path "/text")
			        (let ((res (rontolisp:await (rontolisp:fetch (concatenate 'string upstream "/text")))))
			          (list 200 (list :content-type "text/plain")
			                (list (rontolisp:await (rontolisp:read-all (getf res :body))))))
			        (let ((res (rontolisp:await (rontolisp:fetch (concatenate 'string upstream "/jpeg")))))
			          (list (getf res :status)
			                (list :content-type
			                      (cdr (assoc "content-type" (getf res :headers) :test #'string-equal)))
			                (getf res :body))))))
			""";

	// The octets a relay has to answer unchanged: a JPEG's lead bytes, a stray
	// continuation byte and a valid two-byte sequence, so a transport that decodes and
	// re-encodes answers something else for every one of them.
	static final int[] RELAY_OCTETS = { 0xff, 0xd8, 0xff, 0x00, 0x41, 0xfe, 0x80, 0xc3, 0xbf };

	// The upstream the relay tests fetch from: /jpeg answers RELAY_OCTETS, /text a
	// non-ASCII string. Started on an ephemeral port; the caller stops it with the rest.
	static int startRelayUpstream() {
		byte[] jpeg = new byte[RELAY_OCTETS.length];
		for (int i = 0; i < jpeg.length; i++) {
			jpeg[i] = (byte) RELAY_OCTETS[i];
		}
		HttpServer upstream = RontoHttpServer.start(0,
				request -> request.target().startsWith("/jpeg")
						? new RontoHttpServer.Response(200,
								List.of(new RontoHttpServer.Header("content-type", "image/jpeg")), jpeg)
						: RontoHttpServer.Response.of(200,
								List.of(new RontoHttpServer.Header("content-type", "text/plain")), "こんにちは"));
		return upstream.getAddress().getPort();
	}

	@Test
	void directiveRelaysAFetchedBodyByteExactlyAndReadAllStillDecodesIt() throws Exception {
		// The byte-exactness gate: a fetched reply's :body relayed as the response body
		// crosses byte-exact (it used to be decoded chunk by chunk and re-encoded, so a
		// JPEG's ff d8 ff came out c3 bf d8), while read-all on the same kind of reply
		// still answers the decoded text.
		int upstream = startRelayUpstream();
		int port = freePort();
		serveInBackground(RELAY_PROGRAM + "(rontolisp:http-handler 'handle %d)\n".formatted(port), port);
		HttpResponse<byte[]> relayed = getBytes(port, "/relay?" + upstream);
		assertThat(relayed.statusCode()).isEqualTo(200);
		assertThat(relayed.headers().firstValue("content-type")).hasValue("image/jpeg");
		assertThat(relayed.body()).containsExactly(RELAY_OCTETS);
		assertThat(get(port, "/text?" + upstream).body()).isEqualTo("こんにちは");
	}

	@Test
	void startServesHandlerResponse() throws Exception {
		HttpServer server = RontoHttpServer.start(0,
				request -> RontoHttpServer.Response.of(201,
						List.of(new RontoHttpServer.Header("content-type", "text/plain")),
						"hello " + request.method() + " " + request.target()));
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
		long handle = RontoHttpServer.startServer(0, "127.0.0.1",
				request -> RontoHttpServer.Response.of(200, List.of(), "stoppable " + request.target()));
		int port = (int) RontoHttpServer.serverPort(handle);
		assertThat(port).isPositive();
		assertThat(get(port, "/x").body()).isEqualTo("stoppable /x");
		Thread joiner = Thread.ofVirtual().start(() -> RontoHttpServer.joinServer(handle));
		RontoHttpServer.stopServer(handle);
		joiner.join(Duration.ofSeconds(5));
		assertThat(joiner.isAlive()).isFalse();
		assertThat(RontoHttpServer.serverPort(handle)).isEqualTo(-1);
		// Idempotent: a second stop (the unwind cleanup after an explicit clack:stop)
		// is a no-op.
		RontoHttpServer.stopServer(handle);
		assertThatThrownBy(() -> get(port, "/x")).isInstanceOf(IOException.class);
	}

	@Test
	void joinServerReturnsWhenTheJoinerIsInterrupted() throws Exception {
		// clack's :use-thread t stop path destroy-threads the acceptor: the interrupt
		// must land in the join and return normally so the Lisp unwind-protect stops
		// the server in an orderly unwind.
		long handle = RontoHttpServer.startServer(0, "127.0.0.1",
				request -> RontoHttpServer.Response.of(200, List.of(), "ok"));
		Thread joiner = Thread.ofVirtual().start(() -> RontoHttpServer.joinServer(handle));
		Thread.sleep(50);
		joiner.interrupt();
		joiner.join(Duration.ofSeconds(5));
		assertThat(joiner.isAlive()).isFalse();
		RontoHttpServer.stopServer(handle);
	}

	@Test
	void startServerUnwrapsAQuoteWrappedAddress() throws Exception {
		// The JVM backend passes its runtime string rep (quote-wrapped) as-is.
		long handle = RontoHttpServer.startServer(0, "\"127.0.0.1\"",
				request -> RontoHttpServer.Response.of(200, List.of(), "wrapped"));
		int port = (int) RontoHttpServer.serverPort(handle);
		assertThat(get(port, "/").body()).isEqualTo("wrapped");
		RontoHttpServer.stopServer(handle);
	}

	@Test
	void interpreterSeamFunctionsRoundTrip() throws Exception {
		// The rontolisp::%http-server-* functions on the interpreter: start with a
		// function value, read the ephemeral port back, serve, stop.
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(out, true));
		for (var expr : LispReader.readAllFromString("""
				(defun seam-handler (env)
				  (list 200 nil (list (getf env :path-info))))
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
	void requestKeepsTheTargetVerbatimAndTheBodyAsBytes() {
		// The transport hands the shared library the raw facts only: the target stays
		// unsplit and still percent-encoded (the Clack environment build owns the ?
		// split and the decoding), and the body crosses as bytes.
		RontoHttpServer.Request request = RontoHttpServer.Request.of("GET", "/a%20b?a=1&b=?x", List.of(), "ボディ");
		assertThat(request.target()).isEqualTo("/a%20b?a=1&b=?x");
		assertThat(request.bodyString()).isEqualTo("ボディ");
		assertThat(request.body()).isEqualTo("ボディ".getBytes(java.nio.charset.StandardCharsets.UTF_8));
	}

	@Test
	void directiveServesClackEnvironment() throws Exception {
		int port = freePort();
		serveInBackground("""
				(defun handle (env)
				  (list 200
				        '(:content-type "text/plain")
				        (list "path=" (getf env :path-info))))
				(rontolisp:http-handler 'handle %d)
				""".formatted(port), port);
		HttpResponse<String> response = get(port, "/hello");
		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body()).isEqualTo("path=/hello");
		assertThat(response.headers().firstValue("content-type")).hasValue("text/plain");
	}

	@Test
	void directiveReportsTheRealPeerAndTheHostAsServerName() throws Exception {
		// The transport's twin of the JVM half's unknown-peer case
		// (HttpHandlerJvmTest): a REAL request always has a peer, so what must never
		// happen here is the "" the record carries for "unknown" leaking through as a
		// string.
		int port = freePort();
		serveInBackground("""
				(defun handle (env)
				  (list 200 '(:content-type "text/plain")
				        (list (getf env :remote-addr) " " (getf env :server-name))))
				(rontolisp:http-handler 'handle %d)
				""".formatted(port), port);
		assertThat(get(port, "/").body()).isEqualTo("127.0.0.1 127.0.0.1");
	}

	@Test
	void directiveDecodesPathInfoAndKeepsRequestUriRaw() throws Exception {
		int port = freePort();
		serveInBackground("""
				(defun handle (env)
				  (list 200 '(:content-type "text/plain")
				        (list (getf env :path-info) " uri=" (getf env :request-uri))))
				(rontolisp:http-handler 'handle %d)
				""".formatted(port), port);
		assertThat(get(port, "/a%20b").body()).isEqualTo("/a b uri=/a%20b");
	}

	@Test
	void directiveServesBufferedRawBody() throws Exception {
		// :raw-body :buffered -- the Java-backed bivalent octet stream: read-line and
		// read-byte share one cursor, and file-position is a real byte index.
		int port = freePort();
		serveInBackground("""
				(defun handle (env)
				  (let* ((s (getf env :raw-body))
				         (line (read-line s nil nil))
				         (b (read-byte s nil nil))
				         (pos (file-position s)))
				    (file-position s 0)
				    (list 200 nil
				          (list (format nil "~a/~a/~a/~a" line b pos (read-char s nil nil))))))
				(rontolisp:http-handler 'handle %d :raw-body :buffered)
				""".formatted(port), port);
		assertThat(post(port, "/", "first\nsecond").body()).isEqualTo("first/115/7/f");
	}

	@Test
	void directiveGivesNilRawBodyForABodylessBufferedRequest() throws Exception {
		int port = freePort();
		serveInBackground("""
				(defun handle (env)
				  (list 200 nil (list (if (getf env :raw-body) "body" "none"))))
				(rontolisp:http-handler 'handle %d :raw-body :buffered)
				""".formatted(port), port);
		assertThat(get(port, "/").body()).isEqualTo("none");
	}

	@Test
	void directiveSupportsTheDelayedResponse() throws Exception {
		// Clack's delayed form: the handler answers a function that later calls the
		// responder with the real response.
		int port = freePort();
		serveInBackground("""
				(defun handle (env)
				  (lambda (responder)
				    (funcall responder (list 200 nil (list "delayed")))))
				(rontolisp:http-handler 'handle %d)
				""".formatted(port), port);
		assertThat(get(port, "/").body()).isEqualTo("delayed");
	}

	@Test
	void directiveRefusesABareStringBody() throws Exception {
		// Upstream-faithful, and load-bearing here: a rontolisp pathname IS its
		// namestring, so accepting a string would make lack's :static middleware serve
		// a file's PATH as its contents with a 200.
		int port = freePort();
		serveInBackground("""
				(defun handle (env)
				  (list 200 nil "bare"))
				(rontolisp:http-handler 'handle %d)
				""".formatted(port), port);
		assertThat(get(port, "/").statusCode()).isEqualTo(500);
	}

	@Test
	void directiveSplitsPathInfoAndQueryString() throws Exception {
		int port = freePort();
		serveInBackground("""
				(defun handle (env)
				  (list 200 nil
				        (list "path=" (getf env :path-info)
				              " query=" (if (getf env :query-string) (getf env :query-string) "none"))))
				(rontolisp:http-handler 'handle %d)
				""".formatted(port), port);
		assertThat(get(port, "/hello?a=1&b=two").body()).isEqualTo("path=/hello query=a=1&b=two");
		assertThat(get(port, "/hello").body()).isEqualTo("path=/hello query=none");
	}

	@Test
	void directiveEchoesMethodAndBody() throws Exception {
		// the default :raw-body is an asynchronous stream, so a handler that reads it
		// is an async-defun draining it with read-all; the server awaits the handler's
		// future; :request-method is an interned keyword, (eq m :POST)-able
		int port = freePort();
		serveInBackground("""
				(rontolisp:async-defun handle (env)
				  (let ((body (rontolisp:await (rontolisp:read-all (getf env :raw-body)))))
				    (list 200 nil
				          (list (if (eq (getf env :request-method) :POST) "POST" "?") ":" body))))
				(rontolisp:http-handler 'handle %d)
				""".formatted(port), port);
		HttpResponse<String> response = post(port, "/", "payload");
		assertThat(response.body()).isEqualTo("POST:payload");
	}

	@Test
	void directiveServesAnOctetBodyByteExactly() throws Exception {
		// An (unsigned-byte 8) body is a documented Clack body shape, and the RAW
		// response bytes are what has to match: the shared normalizer hands the octets
		// through unflattened and this transport writes them as they are. Asserting the
		// TEXT would pass on the flattening that used to double every octet >= #x80.
		int port = freePort();
		serveInBackground(OCTET_BODY_PROGRAM + "(rontolisp:http-handler 'handle %d)\n".formatted(port), port);
		HttpResponse<byte[]> response = getBytes(port, "/");
		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body()).containsExactly(0xff, 0xfe, 0x41);
	}

	@Test
	void directiveServesTheTwoElementBodylessResponse() throws Exception {
		// lack's finalize-response answers (status headers) for a bodyless response --
		// the shape it takes when make-response was given no body argument at all.
		int port = freePort();
		serveInBackground("""
				(defun handle (env) (list 204 nil))
				(rontolisp:http-handler 'handle %d)
				""".formatted(port), port);
		HttpResponse<String> response = get(port, "/");
		assertThat(response.statusCode()).isEqualTo(204);
		assertThat(response.body()).isEmpty();
	}

	@Test
	void directiveServesABodyListHoldingNil() throws Exception {
		// A controller that returns nil: lack's finalize-response answers a body LIST
		// whose one element is NIL, which is every ningle 404. Upstream renders a NIL
		// chunk as nothing, so it contributes the empty string here too.
		int port = freePort();
		serveInBackground("""
				(defun handle (env)
				  (if (string= (getf env :path-info) "/mixed")
				      (list 200 nil (list "a" nil "b"))
				      (list 404 nil (list nil))))
				(rontolisp:http-handler 'handle %d)
				""".formatted(port), port);
		HttpResponse<String> missing = get(port, "/");
		assertThat(missing.statusCode()).isEqualTo(404);
		assertThat(missing.body()).isEmpty();
		assertThat(get(port, "/mixed").body()).isEqualTo("ab");
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

				(defun handle (env)
				  (let* ((page (getf env :path-info))
				         (bucket (kv:open ""))
				         (seen (kv:bucket-get bucket page)))
				    (kv:bucket-set bucket page
				                   (princ-to-string (+ 1 (if seen (parse-integer seen) 0))))
				    (list 200 nil
				          (list page " " (kv:bucket-get bucket page)))))
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
		// cross -- the shape that lost PostgreSQL connections inside auth.
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
			for (var expr : LispReader.readAllFromString("(defun h (r) nil) (rontolisp:http-handler 'h 1 2 3 4)")) {
				evaluator.eval(expr);
			}
		}).isInstanceOf(LispEvalException.class).hasMessageContaining("expects 1 to 4 arguments");
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
