package am.ik.rontolisp.eval;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import am.ik.rontolisp.codegen.jvm.JvmLispCompiler;
import am.ik.rontolisp.compiler.OptimizeLevel;
import am.ik.rontolisp.reader.LispReader;
import am.ik.rontolisp.runtime.RontoHttpClack;
import am.ik.rontolisp.runtime.RontoHttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@code rontolisp:http-handler} on the JVM backend: the compiled class
 * implements {@link RontoHttpServer.Handler} and the directive serves requests through
 * {@link RontoHttpServer#serve} using the same request/response property lists as the
 * interpreter. Lives in this package so {@code stopAllForTesting} can shut the servers
 * down.
 */
class HttpHandlerJvmTest {

	@TempDir
	Path tempDir;

	@AfterEach
	void shutDownServers() {
		RontoHttpServer.stopAllForTesting();
	}

	@Test
	@SuppressWarnings("NullAway") // buildEnv is called from bytecode, where nil IS null
	void anUnknownPeerAndBindAddressStillReachTheEnvironmentAsNilAndLocalhost() {
		// RontoHttpServer.Request spells "the transport does not know" as "", because the
		// travelling package has no @Nullable to spell a null with -- so every consumer
		// has to translate it back. Getting that wrong would put a bare "" in the
		// environment where Clack requires nil (:remote-addr) or a host (:server-name).
		Object env = RontoHttpClack.buildEnv(RontoHttpServer.Request.of("GET", "/", java.util.List.of(), ""), null);

		assertThat(plistGet(env, ":REMOTE-ADDR")).isNull();
		assertThat(plistGet(env, ":REMOTE-PORT")).isNull();
		assertThat(plistGet(env, ":SERVER-NAME")).isEqualTo("\"localhost\"");
	}

	// Walks the JVM runtime representation of a plist: an Object[2] cons chain.
	private static Object plistGet(Object plist, String key) {
		Object cursor = plist;
		while (cursor instanceof Object[] cell) {
			Object[] rest = (Object[]) cell[1];
			if (key.equals(cell[0])) {
				return rest[0];
			}
			cursor = rest[1];
		}
		throw new IllegalStateException("no " + key + " in the environment plist");
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

	// Compiles the program to a standalone class, then runs its main() on a daemon
	// background thread (http-handler blocks in serve) and waits until the port is
	// accepting connections.
	private void compileAndServeInBackground(String program, int port) throws Exception {
		compileAndServeInBackground(program, port, OptimizeLevel.NONE);
	}

	private void compileAndServeInBackground(String program, int port, OptimizeLevel optimize) throws Exception {
		JvmLispCompiler compiler = new JvmLispCompiler("TestHttpServe", false, optimize);
		// mirror the CLI pipeline's splices: http-server.lisp (the shared server value
		// model the injected handle() calls into), then the Gray call-site rewrite its
		// buffered :raw-body stream needs, then the prelude for rontolisp:read-all
		java.util.List<am.ik.rontolisp.LispVal> forms = LispReader.readAllFromString(program);
		forms = HttpServerLibrary.process(forms, am.ik.rontolisp.compiler.ClackEnv.usesBufferedBody(forms));
		forms = GrayStreamsLibrary.process(forms);
		byte[] classBytes = compiler.compile(LispPreludeLibrary.process(forms));
		Path classFile = this.tempDir.resolve("TestHttpServe.class");
		Files.write(classFile, classBytes);
		URLClassLoader loader = new URLClassLoader(new URL[] { this.tempDir.toUri().toURL() },
				ClassLoader.getSystemClassLoader());
		Class<?> clazz = loader.loadClass("TestHttpServe");
		Method main = clazz.getMethod("main", String[].class);
		Thread thread = new Thread(() -> {
			try {
				main.invoke(null, (Object) new String[0]);
			}
			catch (ReflectiveOperationException ex) {
				throw new IllegalStateException(ex);
			}
		});
		thread.setDaemon(true);
		thread.start();
		waitForPort(port);
	}

	@Test
	void compiledDirectiveServesClackEnvironment() throws Exception {
		int port = freePort();
		compileAndServeInBackground("""
				(defun handle (env)
				  (list 200 nil (list "path=" (getf env :path-info))))
				(rontolisp:http-handler 'handle %d)
				""".formatted(port), port);
		HttpResponse<String> response = get(port, "/hello");
		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body()).isEqualTo("path=/hello");
	}

	@Test
	void compiledDirectiveDecodesPathInfoAndServesBufferedRawBody() throws Exception {
		// :raw-body :buffered on the JVM backend: the compiled %http-body-stream Gray
		// instance, read through the compiled Gray dispatch -- read-line and read-byte
		// share one cursor and file-position is a real byte index.
		int port = freePort();
		compileAndServeInBackground("""
				(defun handle (env)
				  (let* ((s (getf env :raw-body))
				         (line (if s (read-line s nil nil) "none"))
				         (b (if s (read-byte s nil nil) 0))
				         (pos (if s (file-position s) 0)))
				    (list 200 nil
				          (list (getf env :path-info) "/" (format nil "~a/~a/~a" line b pos)))))
				(rontolisp:http-handler 'handle %d :raw-body :buffered)
				""".formatted(port), port);
		assertThat(post(port, "/a%20b", "first\nsecond").body()).isEqualTo("/a b/first/115/7");
		assertThat(get(port, "/plain").body()).isEqualTo("/plain/none/0/0");
	}

	@Test
	void compiledDirectiveSplitsPathInfoAndQueryString() throws Exception {
		int port = freePort();
		compileAndServeInBackground("""
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
	void compiledDirectiveEchoesMethodAndBody() throws Exception {
		// the request body is an asynchronous stream: an async-defun handler drains it
		// with read-all and the injected handle() awaits the handler's future
		int port = freePort();
		compileAndServeInBackground("""
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
	void compiledDirectiveServesAnOctetBodyByteExactly() throws Exception {
		// The JVM twin of HttpHandlerTest#directiveServesAnOctetBodyByteExactly: the
		// octets reach toResponse as the long[]{width, e0, ...} the normalizer did not
		// flatten, and go out unencoded. The RAW bytes are the assertion; the text
		// spelling would pass on the double-encode.
		int port = freePort();
		compileAndServeInBackground(
				HttpHandlerTest.OCTET_BODY_PROGRAM + "(rontolisp:http-handler 'handle %d)\n".formatted(port), port);
		HttpResponse<byte[]> response = getBytes(port, "/");
		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body()).containsExactly(0xff, 0xfe, 0x41);
	}

	@Test
	void compiledDirectiveRelaysAFetchedBodyByteExactlyAndReadAllStillDecodesIt() throws Exception {
		// The JVM twin of HttpHandlerTest#directiveRelaysAFetchedBodyByteExactly...: the
		// fetch takes the reply as byte[] and answers it as one long[] octet chunk, and
		// _drain_body hands the octets to toResponse unencoded.
		int upstream = HttpHandlerTest.startRelayUpstream();
		int port = freePort();
		compileAndServeInBackground(
				HttpHandlerTest.RELAY_PROGRAM + "(rontolisp:http-handler 'handle %d)\n".formatted(port), port);
		HttpResponse<byte[]> relayed = getBytes(port, "/relay?" + upstream);
		assertThat(relayed.statusCode()).isEqualTo(200);
		assertThat(relayed.headers().firstValue("content-type")).hasValue("image/jpeg");
		assertThat(relayed.body()).containsExactly(HttpHandlerTest.RELAY_OCTETS);
		assertThat(get(port, "/text?" + upstream).body()).isEqualTo("こんにちは");
	}

	@Test
	void compiledDirectiveServesTheTwoElementBodylessResponse() throws Exception {
		// lack's finalize-response answers (status headers) for a bodyless response.
		int port = freePort();
		compileAndServeInBackground("""
				(defun handle (env) (list 204 nil))
				(rontolisp:http-handler 'handle %d)
				""".formatted(port), port);
		HttpResponse<String> response = get(port, "/");
		assertThat(response.statusCode()).isEqualTo(204);
		assertThat(response.body()).isEmpty();
	}

	@Test
	void compiledDirectiveServesABodyListHoldingNil() throws Exception {
		// The compiled half of the same contract: lack's finalize-response answers a
		// body LIST holding NIL for a controller that returned nil (every ningle 404),
		// and a NIL chunk contributes the empty string.
		int port = freePort();
		compileAndServeInBackground("""
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

	@Test
	void compiledDirectiveSurvivesOptimize() throws Exception {
		// --optimize (JvmClassShaker) must keep handle(): RontoHttpServer invokes it
		// through the Handler interface, an edge the call-graph shaker cannot see.
		int port = freePort();
		compileAndServeInBackground("""
				(defun handle (env)
				  (list 200 nil (list "opt=" (getf env :path-info))))
				(rontolisp:http-handler 'handle %d)
				""".formatted(port), port, OptimizeLevel.DEFAULT);
		HttpResponse<String> response = get(port, "/x");
		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body()).isEqualTo("opt=/x");
	}

	@Test
	void compiledDirectiveMarshalsRequestHeaders() throws Exception {
		// The Clack :headers value is an equal hash table with LOWERCASED names,
		// whatever casing the wire (or the JDK server's normalization) used.
		int port = freePort();
		compileAndServeInBackground("""
				(defun handle (env)
				  (list 200 nil
				        (list (gethash "x-token" (getf env :headers)))))
				(rontolisp:http-handler 'handle %d)
				""".formatted(port), port);
		HttpClient client = HttpClient.newHttpClient();
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/"))
			.header("X-Token", "secret42")
			.GET()
			.build();
		HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
		assertThat(response.body()).isEqualTo("secret42");
	}

	@Test
	void compiledDirectiveMarshalsResponseHeaders() throws Exception {
		// Response headers are a keyword plist (Clack), with the dotted alist (a fetch
		// result's :headers) accepted too -- exercise the plist form here.
		int port = freePort();
		compileAndServeInBackground("""
				(defun handle (env)
				  (list 200
				        '(:content-type "text/plain" :x-custom "v1")
				        (list "ok")))
				(rontolisp:http-handler 'handle %d)
				""".formatted(port), port);
		HttpResponse<String> response = get(port, "/");
		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.headers().firstValue("content-type")).hasValue("text/plain");
		assertThat(response.headers().firstValue("x-custom")).hasValue("v1");
		assertThat(response.body()).isEqualTo("ok");
	}

	@Test
	void compiledDirectiveReturnsCustomStatusPerRequest() throws Exception {
		int port = freePort();
		compileAndServeInBackground("""
				(defun handle (env)
				  (if (equal (getf env :path-info) "/found")
				      (list 200 nil (list "yes"))
				      (list 404 nil (list "no"))))
				(rontolisp:http-handler 'handle %d)
				""".formatted(port), port);
		assertThat(get(port, "/found").statusCode()).isEqualTo(200);
		HttpResponse<String> notFound = get(port, "/missing");
		assertThat(notFound.statusCode()).isEqualTo(404);
		assertThat(notFound.body()).isEqualTo("no");
	}

	@Test
	void concurrentRequestsGetTheirOwnSocketHandle() throws Exception {
		// One virtual thread per request means the generated class' _streams table is
		// allocated from concurrently: a non-atomic slot reservation hands two requests
		// the same handle, so one socket is dropped and the two conversations cross
		// .
		try (ServerSocket echo = StreamHandleConcurrencySupport.startEchoServer()) {
			int port = freePort();
			compileAndServeInBackground(StreamHandleConcurrencySupport.echoingHandlerProgram(echo.getLocalPort(), port),
					port);
			StreamHandleConcurrencySupport
				.assertHandlesAreUnshared(StreamHandleConcurrencySupport.probeConcurrently(port));
		}
	}

}
