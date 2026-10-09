package am.ik.rontolisp.clojure;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.cli.JvmSourceCompiler;
import am.ik.rontolisp.cli.RontoLispCli;
import am.ik.rontolisp.eval.ClojureLibrary;
import am.ik.rontolisp.eval.LispEvaluator;
import am.ik.rontolisp.eval.SourceLanguage;
import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.reader.LispReadException;
import am.ik.rontolisp.runtime.RontoHttpServer;
import am.ik.rontolisp.testsupport.CliStack;
import am.ik.rontolisp.testsupport.ThreadStdio;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code rontolisp.http-client}: a built-in namespace with babashka.http-client's API,
 * whose every request goes through {@code rontolisp:fetch}
 * ({@code .kb/clojure-frontend.md}, "HTTP client"). What it sends and answers on each
 * transport is the {@code clojure-http-spec.yaml} corpus of {@code FetchSpecE2eTest};
 * this class pins how it lowers and what it refuses.
 */
class ClojureHttpClientTest {

	@TempDir
	Path workDir;

	private static final String REQUIRE = "(ns a (:require [rontolisp.http-client :as http]))";

	private static String lowered(String source) {
		List<LispVal> forms = Clojure.read(source, null);
		return forms.stream().map(LispVal::print).collect(Collectors.joining("\n"));
	}

	@Test
	void theClientIsABuiltInNamespaceThatSendsThroughFetchItself() {
		// the program names rontolisp:fetch, which every transport splice and the
		// Preview 1 refusal read; the request is the kernel, which builds exceptions
		String out = lowered(REQUIRE + " (http/get \"http://example.com/\")");
		assertThat(out).contains("(RONTOLISP::%CLOJURE-HTTP-REQUEST ")
			.contains("(RONTOLISP:FETCH ")
			.contains("C%E-NEW");
	}

	@Test
	void babashkasNameIsRefusedPointingAtTheClient() {
		// a claimed name promises its options: the API lives under rontolisp's own name
		assertThatThrownBy(() -> Clojure.read("(ns a (:require [babashka.http-client :as http]))", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining(
					"babashka.http-client is not built in: rontolisp.http-client has its API over rontolisp:fetch");
	}

	@Test
	void theVarsOfBabashkasClientThatBuildAJavaClientAreRefusedByName() {
		assertThatThrownBy(() -> Clojure.read(REQUIRE + " (http/client {})", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("rontolisp.http-client/client is not built in: it builds a java.net.http client");
		assertThatThrownBy(() -> Clojure.read(REQUIRE + " (http/nope 1)", null)).isInstanceOf(LispReadException.class)
			.hasMessageContaining("No such var: http/nope");
	}

	@Test
	void theHttpKernelsAreNoUserSurface() {
		assertThatThrownBy(() -> Clojure.read("(ns a (:require [rontolisp.internal.http :as k]))", null))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("rontolisp.internal.http is internal to rontolisp.http-client");
	}

	@Test
	void aProgramThatFetchesNothingSplicesTheLibraryWithoutItsFetchArms() {
		// only the client's kernel makes a rontolisp future or a byte stream over a
		// reply: a program that never fetches compiles deref, future? and the byte
		// stream verbs as before the client existed
		assertThat(defun(ClojureLibrary.forms(), "RONTOLISP::%CLOJURE-DEREF-OTHER"))
			.contains("(RONTOLISP::%CLOJURE-FUTURE-P X)");
		assertThat(defun(ClojureLibrary.forms(), "RONTOLISP::%CLOJURE-IO-READ-BYTE"))
			.contains("(RONTOLISP::%CLOJURE-ASYNC-STREAM-P S)");
		String source = "(println @(atom 1) (future? 1) (deref (atom 2) 10 :t) (future-done? 3) (slurp \"x\")"
				+ " (.read (clojure.java.io/input-stream \"x\")) (.close (clojure.java.io/reader \"x\")))";
		List<LispVal> plain = ClojureLibrary.process(Clojure.read(source, null));
		for (String verb : List.of("RONTOLISP::%CLOJURE-DEREF-OTHER", "RONTOLISP::%CLOJURE-SLURP",
				"RONTOLISP::%CLOJURE-IO-READ-BYTE", "RONTOLISP::%CLOJURE-IO-CLOSE-INPUT",
				"RONTOLISP::%CLOJURE-IO-RING-BODY")) {
			assertThat(defun(plain, verb)).as(verb)
				.doesNotContain("%CLOJURE-FUTURE-P")
				.doesNotContain("%CLOJURE-ASYNC-STREAM-P");
		}
		// future?'s fetch alias stands for the host one, which .close's java:call keeps
		String program = plain.get(plain.size() - 1).print();
		assertThat(program).doesNotContain("%CLOJURE-FUTURE")
			.doesNotContain("%CLOJURE-ASYNC-STREAM-P")
			.doesNotContain("%FUTURE-SETTLED-P")
			.doesNotContain("STREAM-CLOSE")
			.contains("(RONTOLISP::%CLOJURE-HOST-FUTURE-P 1 RONTOLISP::%CLOJURE-FALSE)");
		List<LispVal> fetching = ClojureLibrary
			.process(Clojure.read(REQUIRE + " " + source + " (println @(http/get \"http://x/\" {:async true}))", null));
		assertThat(defun(fetching, "RONTOLISP::%CLOJURE-DEREF-OTHER")).contains("(RONTOLISP::%CLOJURE-FUTURE-P X)");
		assertThat(defun(fetching, "RONTOLISP::%CLOJURE-IO-READ-BYTE"))
			.contains("(RONTOLISP::%CLOJURE-ASYNC-STREAM-P S)");
		assertThat(fetching.stream().map(LispVal::print).collect(Collectors.joining("\n")))
			.contains("(RONTOLISP::%CLOJURE-FUTURE-OR-HOST-P 1 RONTOLISP::%CLOJURE-FALSE)")
			.contains("(RONTOLISP::%CLOJURE-FUTURE-GET-WITHIN ")
			.contains("(RONTOLISP::%FUTURE-SETTLED-P ");
	}

	@Test
	void aProgramThatFetchesKeepsTheByteStreamArmsItsStreamBodyIsReadThrough() {
		// the :as :stream body is a clojure.java.io byte stream, so a program that
		// fetches and names no clojure.java.io var still reads it through the io arms
		List<LispVal> fetching = ClojureLibrary
			.process(Clojure.read(REQUIRE + " (println (.read (:body (http/get \"http://x/\" {:as :stream}))))", null));
		assertThat(defun(fetching, "RONTOLISP::%CLOJURE-IO-READ-BYTE"))
			.contains("(RONTOLISP::%CLOJURE-ASYNC-STREAM-P S)");
		assertThat(fetching.stream().map(LispVal::print).collect(Collectors.joining("\n")))
			.contains("RONTOLISP::%CLOJURE-IO-M-READ");
	}

	private static String defun(List<LispVal> forms, String name) {
		return forms.stream()
			.map(LispVal::print)
			.filter(text -> text.startsWith("(DEFUN " + name + " "))
			.findFirst()
			.orElseThrow();
	}

	@Test
	void aTargetWithNoFetchTransportRefusesTheClientWhenItCompiles() throws Exception {
		// plain Preview 1 has no host answering HTTP, a --no-wasi build no import unless
		// --host-fetch asks for env.fetch: both refusals read from a .clj file
		Path source = this.workDir.resolve("client.clj");
		Files.writeString(source, REQUIRE + " (println (:status (http/get \"http://127.0.0.1:1/\")))");
		assertThatThrownBy(() -> compile(source, "-o", this.workDir.resolve("p1.wasm").toString()))
			.hasMessageContaining("rontolisp.http-client")
			.hasMessageContaining("--component");
		assertThatThrownBy(() -> compile(source, "-o", this.workDir.resolve("reactor.wasm").toString(), "--no-wasi"))
			.hasMessageContaining("rontolisp.http-client")
			.hasMessageContaining("--host-fetch");
	}

	private static void compile(Path source, String... args) {
		String[] all = new String[args.length + 1];
		all[0] = source.toString();
		System.arraycopy(args, 0, all, 1, args.length);
		new RontoLispCli(new ByteArrayInputStream(new byte[0]),
				new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8))
			.run(all);
	}

	/**
	 * A Ring handler relaying an upstream reply: under {@code :as :stream} the reply body
	 * is the handler's response body as it is, so the upstream's octets arrive unchanged
	 * -- a binary body included. The component leg is
	 * {@code ServeRingComponentE2eTest}'s, the Worker's
	 * {@code ClojureHttpClientHostFetchE2eTest}'s.
	 */
	private static final String PROXY = """
			(ns proxy
			  (:require [ring.adapter.rontolisp :refer [run-server]]
			            [rontolisp.http-client :as http]))

			(def upstream "%UPSTREAM%")

			(defn handler [req]
			  (let [r (http/get (str upstream (:uri req)) {:as :stream :throw false})]
			    {:status (:status r)
			     :headers {"content-type" "application/octet-stream"}
			     :body (:body r)}))

			(println (run-server %HANDLER% {:port %PORT% :host "127.0.0.1" :join? false}))
			""";

	@Test
	void aRingHandlerOnTheInterpreterRelaysAnUpstreamReplyByteForByte() throws Exception {
		HttpServer upstream = upstream();
		try {
			int port = freePort();
			long handle = Long.parseLong(interpret(proxy(upstream, "handler", port)).strip());
			try {
				assertRelays(port);
			}
			finally {
				RontoHttpServer.stopServer(handle);
			}
		}
		finally {
			upstream.stop(0);
		}
	}

	@Test
	void aRingHandlerOnTheJvmRelaysAnUpstreamReplyByteForByte() throws Exception {
		HttpServer upstream = upstream();
		try {
			int port = freePort();
			long handle = Long.parseLong(runOnJvm(proxy(upstream, "#'handler", port)).strip());
			try {
				assertRelays(port);
			}
			finally {
				RontoHttpServer.stopServer(handle);
			}
		}
		finally {
			upstream.stop(0);
		}
	}

	/**
	 * A redirect to another origin -- scheme or raw authority -- goes without the
	 * caller's {@code Authorization}, {@code Cookie}, {@code Origin}, {@code Referer} and
	 * {@code Host}, as the JDK's {@code newInstanceForRedirection} sends it; one to the
	 * same origin keeps them. The rule is Lisp over the reply, the same on every
	 * transport; the corpus has one origin, so the second one is here.
	 */
	@Test
	void aRedirectToAnotherOriginGoesWithoutTheCredentialHeaders() throws Exception {
		HttpServer other = headerEcho();
		HttpServer origin = headerEcho();
		try {
			redirect(origin, "/same", "/seen");
			redirect(origin, "/other", "http://127.0.0.1:" + other.getAddress().getPort() + "/seen");
			String base = "http://127.0.0.1:" + origin.getAddress().getPort();
			String program = REQUIRE + """
					(def headers {"authorization" "Bearer t" "cookie" "c=1" "origin" "http://o"
					              "referer" "http://r" "x-keep" "k"})
					(println (:body (http/get "%BASE%/same" {:headers headers})))
					(println (:body (http/get "%BASE%/other" {:headers headers})))
					""".replace("%BASE%", base);
			assertThat(interpret(program)).isEqualTo("authorization cookie origin referer x-keep\nx-keep\n");
		}
		finally {
			origin.stop(0);
			other.stop(0);
		}
	}

	/**
	 * A server whose {@code /seen} answers the names of the caller's headers among the
	 * credential ones and {@code x-keep}, sorted.
	 */
	private static HttpServer headerEcho() throws IOException {
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/seen", exchange -> {
			String names = exchange.getRequestHeaders()
				.keySet()
				.stream()
				.map(name -> name.toLowerCase(Locale.ROOT))
				.filter(List.of("authorization", "cookie", "origin", "referer", "x-keep")::contains)
				.sorted()
				.collect(Collectors.joining(" "));
			byte[] body = names.getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(200, body.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(body);
			}
		});
		server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
		server.start();
		return server;
	}

	private static void redirect(HttpServer server, String path, String location) {
		server.createContext(path, exchange -> {
			exchange.getResponseHeaders().add("Location", location);
			exchange.sendResponseHeaders(302, -1);
			exchange.close();
		});
	}

	private static String proxy(HttpServer upstream, String handler, int port) {
		return PROXY.replace("%UPSTREAM%", "http://127.0.0.1:" + upstream.getAddress().getPort())
			.replace("%HANDLER%", handler)
			.replace("%PORT%", Integer.toString(port));
	}

	private static HttpServer upstream() throws IOException {
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/octets", exchange -> {
			byte[] body = { (byte) 0xff, (byte) 0xfe, 0x41 };
			exchange.sendResponseHeaders(200, body.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(body);
			}
		});
		server.createContext("/status/404", exchange -> {
			byte[] body = "missing".getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(404, body.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(body);
			}
		});
		server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
		server.start();
		return server;
	}

	private static void assertRelays(int port) throws Exception {
		HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
		HttpResponse<byte[]> octets = client.send(
				HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/octets")).build(),
				HttpResponse.BodyHandlers.ofByteArray());
		assertThat(octets.statusCode()).isEqualTo(200);
		assertThat(octets.body()).containsExactly((byte) 0xff, (byte) 0xfe, 0x41);
		HttpResponse<String> missing = client.send(
				HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/status/404")).build(),
				HttpResponse.BodyHandlers.ofString());
		assertThat(missing.statusCode()).isEqualTo(404);
		assertThat(missing.body()).isEqualTo("missing");
	}

	private static int freePort() throws IOException {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
	}

	private static String interpret(String program) throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		CliStack.call("clojure-http-proxy", () -> {
			LispEvaluator evaluator = new LispEvaluator(new PrintStream(out, true, StandardCharsets.UTF_8));
			for (LispVal form : evaluator
				.clojureProgram(SourceLanguage.CLOJURE.read(program, Features.INTERPRETER, "proxy.clj"))) {
				evaluator.eval(form);
			}
			return null;
		});
		return out.toString(StandardCharsets.UTF_8);
	}

	private String runOnJvm(String program) throws Exception {
		String name = "HttpProxy";
		JvmSourceCompiler.Result result = new JvmSourceCompiler(name).sourceLanguage("clojure").compile(program, null);
		Path classes = Files.createDirectories(this.workDir.resolve("classes"));
		Files.write(classes.resolve(name + ".class"), result.classBytes());
		for (Map.Entry<String, byte[]> file : result.runtimeClasses().entrySet()) {
			Path target = classes.resolve(file.getKey());
			Files.createDirectories(target.getParent());
			Files.write(target, file.getValue());
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		// the loader stays open: the served handler keeps loading the program's classes
		// after main returns
		var loader = new java.net.URLClassLoader(new java.net.URL[] { classes.toUri().toURL() },
				ClassLoader.getSystemClassLoader());
		try (var _ = ThreadStdio.out(out)) {
			CliStack.call("clojure-http-proxy", () -> {
				try {
					Method main = loader.loadClass(name).getMethod("main", String[].class);
					main.invoke(null, (Object) new String[0]);
				}
				catch (ReflectiveOperationException ex) {
					throw new IllegalStateException(ex);
				}
				return null;
			});
		}
		return out.toString(StandardCharsets.UTF_8);
	}

}
