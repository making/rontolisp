package am.ik.rontolisp.clojure;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import am.ik.rontolisp.cli.RontoLispCli;
import am.ik.rontolisp.testsupport.InflateCases;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The fifth transport of {@code rontolisp.http-client}: a {@code --no-wasi --host-fetch}
 * module, whose {@code rontolisp:fetch} is the JavaScript host's {@code fetch} imported
 * as {@code env.fetch}. The host is the glue {@code --emit-js-glue} writes beside the
 * module, with its {@code defaultHost()} -- the platform {@code fetch}, entered through
 * JSPI -- on node, against a local origin: the shape a Cloudflare Worker runs. A reactor
 * fetches only inside an export (a suspending host cannot serve {@code _initialize}), so
 * the client runs in an exported function, and a Ring handler relaying an upstream reply
 * through the synthesized {@code handle-request}. Skipped where node has no JSPI.
 */
@EnabledIf("am.ik.rontolisp.clojure.ClojureHttpClientHostFetchE2eTest#nodeHasJspi")
class ClojureHttpClientHostFetchE2eTest {

	static boolean nodeHasJspi() {
		try {
			Process node = new ProcessBuilder("node", "--experimental-wasm-jspi", "-e",
					"process.exit(typeof WebAssembly.promising === 'function' ? 0 : 1)")
				.start();
			return node.waitFor(30, TimeUnit.SECONDS) && node.exitValue() == 0;
		}
		catch (IOException | InterruptedException ex) {
			return false;
		}
	}

	@TempDir
	Path workDir;

	private HttpServer origin;

	private String originUrl;

	@BeforeEach
	void startOrigin() throws IOException {
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/hello", exchange -> {
			boolean custom = "abc".equals(exchange.getRequestHeaders().getFirst("X-Custom"));
			exchange.getResponseHeaders().add("X-Test", "ok");
			answer(exchange, 200, custom ? "got-header" : "hello-from-fetch");
		});
		server.createContext("/echo", exchange -> answer(exchange, 200, exchange.getRequestMethod() + ":"
				+ new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
		server.createContext("/query",
				exchange -> answer(exchange, 200, String.valueOf(exchange.getRequestURI().getRawQuery())));
		server.createContext("/status/404", exchange -> answer(exchange, 404, "missing"));
		// The request's content type and its body in hex: an octet body crosses as
		// octets.
		server.createContext("/octets-echo", exchange -> {
			StringBuilder hex = new StringBuilder();
			for (byte b : exchange.getRequestBody().readAllBytes()) {
				hex.append(String.format("%02x", b & 0xff));
			}
			String type = String.valueOf(exchange.getRequestHeaders().getFirst("Content-Type"));
			answer(exchange, 200, type.replaceAll("babashka_http_client_Boundary[0-9a-f-]{36}", "B") + "|" + hex);
		});
		// The host's fetch decodes this itself: the head the glue answers says so.
		server.createContext("/gzip", exchange -> {
			byte[] body = InflateCases.gzip("héllo, gzip".getBytes(StandardCharsets.UTF_8));
			exchange.getResponseHeaders().add("Content-Encoding", "gzip");
			exchange.sendResponseHeaders(200, body.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(body);
			}
		});
		server.createContext("/octets", exchange -> {
			byte[] body = { (byte) 0xff, (byte) 0xfe, 0x41 };
			exchange.sendResponseHeaders(200, body.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(body);
			}
		});
		server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
		server.start();
		this.origin = server;
		this.originUrl = "http://127.0.0.1:" + server.getAddress().getPort();
	}

	@AfterEach
	void stopOrigin() {
		this.origin.stop(0);
	}

	private static void answer(HttpExchange exchange, int status, String body) throws IOException {
		byte[] octets = body.getBytes(StandardCharsets.UTF_8);
		exchange.sendResponseHeaders(status, octets.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(octets);
		}
	}

	private static final String CLIENT = """
			(ns client (:require [rontolisp.http-client :as http]))

			(defn probe {:wasm/export {:as "probe" :params [:string] :returns :string}} [origin]
			  (let [url (fn [p] (str origin p))]
			    (pr-str [(:status (http/get (url "/hello")))
			             (get-in (http/get (url "/hello")) [:headers "x-test"])
			             (:body (http/get (url "/hello") {:headers {"X-Custom" "abc"}}))
			             (:body (http/post (url "/echo") {:body "héllo"}))
			             (:body (http/get (url "/query") {:query-params {"a" "1 2"}}))
			             (try (http/get (url "/status/404"))
			                  (catch clojure.lang.ExceptionInfo e (:status (ex-data e))))
			             (:status @(http/get (url "/hello") {:async true}))
			             (slurp (:body (http/get (url "/hello") {:as :stream})))
			             (:body (http/get (url "/gzip")))
			             (get-in (http/get (url "/gzip")) [:headers "content-encoding"])
			             (subs (:body (http/post (url "/octets-echo") {:multipart [{:name "a" :content "x"}]})) 0 36)])))
			""";

	@Test
	void theClientSendsThroughTheHostsFetch() throws Exception {
		Path dir = compile("client", CLIENT);
		Files.writeString(dir.resolve("driver.mjs"), """
				import fs from "node:fs";
				import { instantiate, defaultHost } from "./client.mjs";
				const module = new WebAssembly.Module(fs.readFileSync(new URL("./client.wasm", import.meta.url)));
				const lisp = instantiate(module, defaultHost());
				console.log(await lisp.probe(process.argv[2]));
				""");
		assertThat(node(dir, "driver.mjs", this.originUrl))
			.isEqualTo("[200 \"ok\" \"got-header\" \"POST:héllo\" \"a=1+2\" 404 200 \"hello-from-fetch\""
					+ " \"héllo, gzip\" nil \"multipart/form-data; boundary=B|2d2d\"]");
	}

	/**
	 * An octet body through the host's fetch: on the streaming boundary a binary reply
	 * arrives as its octets, and sent on as a request body it leaves as them too (the
	 * envelope boundary carries a reply's body as text).
	 */
	private static final String OCTETS = """
			(ns octets (:require [rontolisp.http-client :as http]))

			(defn probe {:wasm/export {:as "probe" :params [:string] :returns :string}} [origin]
			  (let [url (fn [p] (str origin p))]
			    (:body (http/post (url "/octets-echo") {:body (:body (http/get (url "/octets") {:as :stream}))}))))
			""";

	@Test
	void anOctetBodyLeavesThroughTheHostsFetchAsItsOctets() throws Exception {
		Path dir = compile("octets", OCTETS, "--host-boundary=streaming");
		Files.writeString(dir.resolve("driver.mjs"), """
				import fs from "node:fs";
				import { instantiate, defaultHost } from "./octets.mjs";
				const module = new WebAssembly.Module(fs.readFileSync(new URL("./octets.wasm", import.meta.url)));
				let lisp = null;
				lisp = instantiate(module, defaultHost(() => lisp));
				console.log(await lisp.probe(process.argv[2]));
				""");
		assertThat(node(dir, "driver.mjs", this.originUrl)).isEqualTo("null|fffe41");
	}

	/**
	 * A Ring handler relaying an upstream reply: the reply body under {@code :as :stream}
	 * is the handler's response body as it is, so the octets arrive unchanged.
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

			(run-server handler {:port 8080})
			""";

	@Test
	void aRingHandlerRelaysAnUpstreamReplyByteForByte() throws Exception {
		Path dir = compile("proxy", PROXY.replace("%UPSTREAM%", this.originUrl), "--host-boundary=streaming");
		Files.writeString(dir.resolve("driver.mjs"), """
				import fs from "node:fs";
				import { worker } from "./proxy.mjs";
				const module = new WebAssembly.Module(fs.readFileSync(new URL("./proxy.wasm", import.meta.url)));
				const app = worker(module);
				for (const path of ["/octets", "/status/404"]) {
				  const response = await app.fetch(new Request("http://worker.local" + path));
				  const octets = [...new Uint8Array(await response.arrayBuffer())];
				  console.log(response.status, octets.join(","));
				}
				""");
		assertThat(node(dir, "driver.mjs", this.originUrl))
			.isEqualTo("200 255,254,65\n404 " + String.join(",", "109", "105", "115", "115", "105", "110", "103"));
	}

	private Path compile(String name, String source, String... extra) throws IOException {
		Path dir = Files.createDirectories(this.workDir.resolve(name));
		Path file = dir.resolve(name + ".clj");
		Files.writeString(file, source);
		String[] args = new String[6 + extra.length];
		args[0] = file.toString();
		args[1] = "-o";
		args[2] = dir.resolve(name + ".wasm").toString();
		args[3] = "--no-wasi";
		args[4] = "--host-fetch";
		args[5] = "--emit-js-glue";
		System.arraycopy(extra, 0, args, 6, extra.length);
		new RontoLispCli(new ByteArrayInputStream(new byte[0]),
				new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8))
			.run(args);
		// the glue is an ES module; node reads .js as CommonJS outside a "type": "module"
		// package
		Files.copy(dir.resolve(name + ".js"), dir.resolve(name + ".mjs"));
		return dir;
	}

	private static String node(Path dir, String script, String argument) throws Exception {
		Process node = new ProcessBuilder("node", "--experimental-wasm-jspi", script, argument).directory(dir.toFile())
			.redirectErrorStream(true)
			.start();
		String out = new String(node.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		assertThat(node.waitFor(120, TimeUnit.SECONDS)).as("node finished: %s", out).isTrue();
		assertThat(node.exitValue()).as(out).isZero();
		return out.strip();
	}

}
