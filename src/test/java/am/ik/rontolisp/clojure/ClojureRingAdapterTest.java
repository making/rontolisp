package am.ik.rontolisp.clojure;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.cli.JvmSourceCompiler;
import am.ik.rontolisp.cli.RontoLispCli;
import am.ik.rontolisp.eval.LispEvaluator;
import am.ik.rontolisp.eval.SourceLanguage;
import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.reader.LispReadException;
import am.ik.rontolisp.runtime.RontoHttpServer;
import am.ik.rontolisp.testsupport.CliStack;
import am.ik.rontolisp.testsupport.ThreadStdio;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@code ring.adapter.rontolisp/run-server}: one Ring handler served over real requests
 * on the interpreter and the JVM (a socket, {@code :join? false} so the test keeps the
 * thread) and called through the {@code handle-request} export of a {@code --no-wasi}
 * module by a JS host (the Preview 1 serving transport). The {@code --component} leg
 * under {@code wasmtime serve} is {@code ServeRingComponentE2eTest}; the war leg is
 * {@code WarE2eTest}. What every leg pins: the request map (a lower-cased method keyword
 * usable as a map key, the raw {@code :uri}, the query string, the headers map, scheme,
 * protocol, content type and length), the request {@code :body} read through
 * {@code slurp}, {@code clojure.java.io/reader} + {@code line-seq} and
 * {@code java.io.InputStreamReader}, and the response map (a missing status, a header
 * vector as repeated lines, a keyword header name, a seq body).
 */
class ClojureRingAdapterTest {

	@TempDir
	Path workDir;

	/** The handler every leg serves; {@code %HANDLER%} and {@code %PORT%} vary. */
	private static final String PROGRAM = """
			(ns ring-probe
			  (:require [ring.adapter.rontolisp :as ring]
			            [clojure.java.io :as io]))

			(defn handler [req]
			  (let [{:keys [request-method uri query-string headers body]} req]
			    (cond
			      (= uri "/info")
			      {:status 200
			       :headers {"Content-Type" "text/plain" :x-kw "k" "X-Multi" ["a" "b"]}
			       :body (str (name request-method) " " (get {:get "G" :post "P"} request-method)
			                  " " uri " " query-string " " (get headers "user-agent")
			                  " " (:scheme req) " " (:protocol req) " " (integer? (:server-port req)))}
			      (= uri "/echo")
			      {:status 201
			       :body ["echo:" (slurp body) "|" (:content-length req) "|" (:content-type req)]}
			      (= uri "/lines")
			      {:body (str (vec (line-seq (io/reader (java.io.InputStreamReader. body "UTF-8")))))}
			      (= uri "/empty") {:status 204}
			      :else {:status 404 :body "not found"})))

			(println (ring/run-server %HANDLER% {:port %PORT% :host "127.0.0.1" :join? false}))
			""";

	private static String program(String handler, int port) {
		return PROGRAM.replace("%HANDLER%", handler).replace("%PORT%", Integer.toString(port));
	}

	@Test
	void theInterpreterServesARingHandler() throws Exception {
		int port = freePort();
		long handle = Long.parseLong(interpret(program("handler", port)).strip());
		try {
			assertServes(port);
		}
		finally {
			RontoHttpServer.stopServer(handle);
		}
	}

	@Test
	void theJvmServesARingHandlerThroughItsVar() throws Exception {
		int port = freePort();
		long handle = Long.parseLong(runOnJvm(program("#'handler", port)).strip());
		try {
			assertServes(port);
		}
		finally {
			RontoHttpServer.stopServer(handle);
		}
	}

	private static void assertServes(int port) throws Exception {
		HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
		HttpResponse<String> info = client.send(
				HttpRequest.newBuilder(uri(port, "/info?a=1&b=%20x")).header("User-Agent", "ring-test").build(),
				HttpResponse.BodyHandlers.ofString());
		assertThat(info.statusCode()).isEqualTo(200);
		assertThat(info.body()).isEqualTo("get G /info a=1&b=%20x ring-test :http HTTP/1.1 true");
		assertThat(info.headers().allValues("x-multi")).containsExactly("a", "b");
		assertThat(info.headers().firstValue("x-kw")).hasValue("k");
		assertThat(info.headers().firstValue("content-type")).hasValue("text/plain");

		HttpResponse<String> echo = client.send(HttpRequest.newBuilder(uri(port, "/echo"))
			.header("Content-Type", "text/plain")
			.POST(HttpRequest.BodyPublishers.ofString("hé"))
			.build(), HttpResponse.BodyHandlers.ofString());
		assertThat(echo.statusCode()).isEqualTo(201);
		assertThat(echo.body()).isEqualTo("echo:hé|3|text/plain");

		HttpResponse<String> lines = client.send(HttpRequest.newBuilder(uri(port, "/lines"))
			.POST(HttpRequest.BodyPublishers.ofString("l1\nl2\n"))
			.build(), HttpResponse.BodyHandlers.ofString());
		assertThat(lines.statusCode()).as("a response map without :status").isEqualTo(200);
		assertThat(lines.body()).isEqualTo("[\"l1\" \"l2\"]");

		HttpResponse<String> empty = client.send(HttpRequest.newBuilder(uri(port, "/empty")).build(),
				HttpResponse.BodyHandlers.ofString());
		assertThat(empty.statusCode()).isEqualTo(204);

		HttpResponse<String> missing = client.send(HttpRequest.newBuilder(uri(port, "/nope")).build(),
				HttpResponse.BodyHandlers.ofString());
		assertThat(missing.statusCode()).isEqualTo(404);
		assertThat(missing.body()).isEqualTo("not found");
	}

	/**
	 * The JS host of {@code WasmReactorBodyE2eTest}, envelope boundary: the head and the
	 * body cross as one JSON object, the reply as another.
	 */
	private static final String REACTOR_HOST = """
			const fs = require('fs');
			const enc = new TextEncoder(), dec = new TextDecoder();
			const inst = new WebAssembly.Instance(new WebAssembly.Module(fs.readFileSync(process.argv[2])), {});
			inst.exports._initialize();
			function call(head) {
			  const x = inst.exports;
			  const mark = x.__ronto_alloc_mark();
			  const hb = enc.encode(JSON.stringify(head));
			  const p = x.__ronto_alloc(hb.length);
			  new Uint8Array(x.memory.buffer, p, hb.length).set(hb);
			  const [rp, rl] = x['handle-request'](p, hb.length);
			  const reply = JSON.parse(dec.decode(new Uint8Array(x.memory.buffer.slice(rp, rp + rl))));
			  x.__ronto_alloc_reset(mark);
			  return reply;
			}
			const show = (r) => console.log(JSON.stringify([r.status, r.body,
			  r.headers.filter(([n]) => n.startsWith('x-')).map(([n, v]) => n + '=' + v).sort()]));
			show(call({ method: 'GET', target: '/info?a=1&b=%20x',
			            headers: { host: '127.0.0.1', 'user-agent': 'ring-test' } }));
			show(call({ method: 'POST', target: '/echo',
			            headers: { host: 'h', 'content-type': 'text/plain', 'content-length': '3' }, body: 'hé' }));
			show(call({ method: 'POST', target: '/lines', headers: { host: 'h', 'content-length': '6' },
			            body: 'l1\\nl2\\n' }));
			show(call({ method: 'GET', target: '/empty', headers: { host: 'h' } }));
			show(call({ method: 'GET', target: '/nope', headers: { host: 'h' } }));
			""";

	@Test
	void aNoWasiModuleServesARingHandlerThroughTheHandleRequestExport() throws Exception {
		assumeTrue(nodeIsAvailable(), "node is not on PATH");
		Path source = this.workDir.resolve("probe.clj");
		Files.writeString(source, program("handler", 3000));
		Path module = this.workDir.resolve("probe.wasm");
		String output = runCli(source.toString(), "-o", module.toString(), "--no-wasi");
		assertThat(module).as("the --no-wasi compile: %s", output).exists();
		Path host = this.workDir.resolve("host.js");
		Files.writeString(host, REACTOR_HOST);
		Process node = new ProcessBuilder("node", host.toString(), module.toString()).redirectErrorStream(true).start();
		String replies = new String(node.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		assertThat(node.waitFor()).as(replies).isZero();
		assertThat(replies.lines().toList()).containsExactly(
				"[200,\"get G /info a=1&b=%20x ring-test :http HTTP/1.1 true\",[\"x-kw=k\",\"x-multi=a\",\"x-multi=b\"]]",
				"[201,\"echo:hé|3|text/plain\",[]]", "[200,\"[\\\"l1\\\" \\\"l2\\\"]\",[]]", "[204,\"\",[]]",
				"[404,\"not found\",[]]");
	}

	@Test
	void aWarRegistersTheRingHandlerWithTheContainer() throws Exception {
		// The servlet leg of rontolisp::%http-serve: the handler goes into the slot the
		// container dispatches through and the top level returns -- no socket server
		// and no join to block the war's <clinit> (WarE2eTest deploys it).
		Path source = this.workDir.resolve("probe.clj");
		Files.writeString(source, program("handler", 3000));
		Path war = this.workDir.resolve("probe.war");
		String output = runCli(source.toString(), "-o", war.toString());
		assertThat(war).as("the war compile: %s", output).exists();
		String classes;
		try (var zip = new java.util.zip.ZipFile(war.toFile())) {
			StringBuilder all = new StringBuilder();
			for (var entries = zip.entries(); entries.hasMoreElements();) {
				var entry = entries.nextElement();
				if (entry.getName().startsWith("WEB-INF/classes/") && entry.getName().endsWith(".class")
						&& !entry.getName().contains("/am/ik/")) {
					all.append(new String(zip.getInputStream(entry).readAllBytes(), StandardCharsets.ISO_8859_1));
				}
			}
			classes = all.toString();
		}
		assertThat(classes).contains("_httpHandlerFn").doesNotContain("startServer").doesNotContain("joinServer");
	}

	@Test
	void anAsynchronousHandlerIsRefusedByItsOption() {
		assertThatThrownBy(() -> interpret("""
				(require '[ring.adapter.rontolisp :refer [run-server]])
				(run-server (fn [req] {:status 200}) {:port 0 :async? true})
				""")).hasMessageContaining("asynchronous handlers (:async? true) are not supported");
	}

	@Test
	void theLoweringRefusesAWrongCountAndAnUnknownVar() {
		assertThatThrownBy(() -> SourceLanguage.CLOJURE.read("""
				(require '[ring.adapter.rontolisp :as r])
				(r/run-server (fn [req] {}))
				""", Features.INTERPRETER, "t.clj")).isInstanceOf(LispReadException.class)
			.hasMessageContaining("Wrong number of args (1) passed to: ring.adapter.rontolisp/run-server");
		assertThatThrownBy(() -> SourceLanguage.CLOJURE.read("""
				(require '[ring.adapter.rontolisp :as r])
				(r/run-jetty (fn [req] {}) {})
				""", Features.INTERPRETER, "t.clj")).isInstanceOf(LispReadException.class)
			.hasMessageContaining("unknown name: ring.adapter.rontolisp/run-jetty");
	}

	@Test
	void runServerIsAFunctionValueToo() throws Exception {
		int port = freePort();
		String source = PROGRAM
			.replace("(println (ring/run-server %HANDLER% {:port %PORT% :host \"127.0.0.1\" :join? false}))",
					"(println (apply ring/run-server [handler {:port %PORT% :host \"127.0.0.1\" :join? false}]))")
			.replace("%PORT%", Integer.toString(port));
		long handle = Long.parseLong(interpret(source).strip());
		try {
			HttpResponse<String> missing = HttpClient.newHttpClient()
				.send(HttpRequest.newBuilder(uri(port, "/nope")).build(), HttpResponse.BodyHandlers.ofString());
			assertThat(missing.statusCode()).isEqualTo(404);
		}
		finally {
			RontoHttpServer.stopServer(handle);
		}
	}

	private static URI uri(int port, String path) {
		return URI.create("http://127.0.0.1:" + port + path);
	}

	private static int freePort() throws IOException {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
	}

	private static boolean nodeIsAvailable() {
		try {
			return new ProcessBuilder("node", "--version").start().waitFor() == 0;
		}
		catch (Exception ex) {
			return false;
		}
	}

	private static String runCli(String... args) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		new RontoLispCli(new ByteArrayInputStream(new byte[0]), new PrintStream(out, true, StandardCharsets.UTF_8))
			.run(args);
		return out.toString(StandardCharsets.UTF_8);
	}

	private static String interpret(String program) throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		CliStack.call("clojure-ring", () -> {
			LispEvaluator evaluator = new LispEvaluator(new PrintStream(out, true, StandardCharsets.UTF_8));
			for (LispVal form : SourceLanguage.CLOJURE.read(program, Features.INTERPRETER, "probe.clj")) {
				evaluator.eval(form);
			}
			return null;
		});
		return out.toString(StandardCharsets.UTF_8);
	}

	private String runOnJvm(String program) throws Exception {
		String name = "RingProbe";
		JvmSourceCompiler.Result result = new JvmSourceCompiler(name).sourceLanguage("clojure").compile(program, null);
		Path classes = Files.createDirectories(this.workDir.resolve("classes"));
		Files.write(classes.resolve(name + ".class"), result.classBytes());
		for (Map.Entry<String, byte[]> file : result.runtimeClasses().entrySet()) {
			Path target = classes.resolve(file.getKey());
			Files.createDirectories(target.getParent());
			Files.write(target, file.getValue());
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		// The loader stays open: the served handler keeps loading the program's classes
		// after main returns.
		var loader = new java.net.URLClassLoader(new java.net.URL[] { classes.toUri().toURL() },
				ClassLoader.getSystemClassLoader());
		try (var _ = ThreadStdio.out(out)) {
			CliStack.call("clojure-ring", () -> {
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
