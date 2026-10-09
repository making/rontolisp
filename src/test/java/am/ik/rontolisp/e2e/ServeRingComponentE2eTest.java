package am.ik.rontolisp.e2e;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import static am.ik.rontolisp.e2e.ServeComponentE2eSupport.compileComponent;
import static am.ik.rontolisp.e2e.ServeComponentE2eSupport.deleteRecursively;
import static am.ik.rontolisp.e2e.ServeComponentE2eSupport.freePort;
import static am.ik.rontolisp.e2e.ServeComponentE2eSupport.onPath;
import static am.ik.rontolisp.e2e.ServeComponentE2eSupport.resolveDriver;
import static am.ik.rontolisp.e2e.ServeComponentE2eSupport.startServe;
import static am.ik.rontolisp.e2e.ServeComponentE2eSupport.waitForPort;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The {@code --component} leg of {@code ring.adapter.rontolisp/run-server}: the same Ring
 * handler {@code ClojureRingAdapterTest} serves on the interpreter, the JVM and a
 * {@code --no-wasi} module, here under {@code wasmtime serve} (the WASI leg of
 * {@code rontolisp::%http-serve}: the {@code http-handler} directive over the one stored
 * application). Opt-in and gated as {@link ServeComponentE2eSupport} describes.
 */
class ServeRingComponentE2eTest {

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
			       :body ["echo:" (slurp body) "|" (pr-str (slurp body)) "|" (.read body)
			              "|" (:content-length req) "|" (:content-type req)]}
			      (= uri "/lines")
			      {:body (str (vec (line-seq (io/reader (java.io.InputStreamReader. body "UTF-8")))))}
			      (= uri "/empty") {:status 204}
			      (= uri "/missing") {:body (io/file "/no/such/dir/missing.bin")}
			      (= uri "/asset") {:body (io/input-stream (io/resource "asset.txt"))}
			      :else {:status 404 :body "not found"})))

			(ring/run-server #'handler {:port 3000})
			""";

	@Test
	void wasmtimeServeServesARingHandler() throws Exception {
		List<String> driver = resolveDriver();
		assumeTrue(driver != null, "serve component E2E is opt-in: pass -Drontolisp.binary=<native binary> or "
				+ "-Drontolisp.examples=true (after ./mvnw clean package -DskipTests)");
		assumeTrue(onPath("wasmtime"), "wasmtime is not on PATH");

		Path work = Files.createTempDirectory("rontolisp-serve-ring-");
		Process server = null;
		try {
			Path source = work.resolve("ring-probe.clj");
			Files.writeString(source, PROGRAM);
			// a resource the lowering finds beside the source travels with the component
			Files.writeString(work.resolve("asset.txt"), "h\u00e9llo\n");
			Path component = work.resolve("ring-probe.wasm");
			compileComponent(driver, source, component, work);

			int port = freePort();
			server = startServe(component, port, work, List.of());
			waitForPort(port, server, work.resolve("serve.log"));

			HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
			HttpResponse<String> info = client.send(
					HttpRequest.newBuilder(uri(port, "/info?a=1&b=%20x")).header("User-Agent", "ring-test").build(),
					HttpResponse.BodyHandlers.ofString());
			assertThat(info.statusCode()).isEqualTo(200);
			assertThat(info.body()).isEqualTo("get G /info a=1&b=%20x ring-test :http HTTP/1.1 true");
			assertThat(info.headers().allValues("x-multi")).containsExactly("a", "b");
			assertThat(info.headers().firstValue("x-kw")).hasValue("k");

			HttpResponse<String> echo = client.send(HttpRequest.newBuilder(uri(port, "/echo"))
				.header("Content-Type", "text/plain")
				.POST(HttpRequest.BodyPublishers.ofString("hé"))
				.build(), HttpResponse.BodyHandlers.ofString());
			assertThat(echo.statusCode()).isEqualTo(201);
			assertThat(echo.body()).isEqualTo("echo:hé|\"\"|-1|3|text/plain");

			HttpResponse<String> lines = client.send(HttpRequest.newBuilder(uri(port, "/lines"))
				.POST(HttpRequest.BodyPublishers.ofString("l1\nl2\n"))
				.build(), HttpResponse.BodyHandlers.ofString());
			assertThat(lines.statusCode()).isEqualTo(200);
			assertThat(lines.body()).isEqualTo("[\"l1\" \"l2\"]");

			assertThat(client
				.send(HttpRequest.newBuilder(uri(port, "/empty")).build(), HttpResponse.BodyHandlers.ofString())
				.statusCode()).isEqualTo(204);
			HttpResponse<String> missing = client.send(HttpRequest.newBuilder(uri(port, "/nope")).build(),
					HttpResponse.BodyHandlers.ofString());
			assertThat(missing.statusCode()).isEqualTo(404);
			assertThat(missing.body()).isEqualTo("not found");

			assertThat(client
				.send(HttpRequest.newBuilder(uri(port, "/missing")).build(), HttpResponse.BodyHandlers.ofString())
				.statusCode()).as("a File naming no file the serve world can read").isEqualTo(500);
			HttpResponse<String> asset = client.send(HttpRequest.newBuilder(uri(port, "/asset")).build(),
					HttpResponse.BodyHandlers.ofString());
			assertThat(asset.statusCode()).isEqualTo(200);
			assertThat(asset.body()).as("a byte stream over a resource the component carries")
				.isEqualTo("h\u00e9llo\n");
		}
		finally {
			if (server != null) {
				server.destroyForcibly();
				server.waitFor(10, TimeUnit.SECONDS);
			}
			deleteRecursively(work);
		}
	}

	/**
	 * A Ring handler relaying an upstream reply through {@code rontolisp.http-client}:
	 * serve and fetch in one component, the reply body under {@code :as :stream} the
	 * response body as it is, so a binary body arrives byte for byte.
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

			(run-server handler {:port 3000})
			""";

	@Test
	void wasmtimeServeRelaysAFetchedReplyByteForByte() throws Exception {
		List<String> driver = resolveDriver();
		assumeTrue(driver != null, "serve component E2E is opt-in: pass -Drontolisp.binary=<native binary> or "
				+ "-Drontolisp.examples=true (after ./mvnw clean package -DskipTests)");
		assumeTrue(onPath("wasmtime"), "wasmtime is not on PATH");

		HttpServer upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		upstream.createContext("/octets", exchange -> {
			byte[] body = { (byte) 0xff, (byte) 0xfe, 0x41 };
			exchange.sendResponseHeaders(200, body.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(body);
			}
		});
		upstream.createContext("/status/404", exchange -> {
			byte[] body = "missing".getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(404, body.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(body);
			}
		});
		upstream.start();
		Path work = Files.createTempDirectory("rontolisp-serve-ring-proxy-");
		Process server = null;
		try {
			Path source = work.resolve("proxy.clj");
			Files.writeString(source,
					PROXY.replace("%UPSTREAM%", "http://127.0.0.1:" + upstream.getAddress().getPort()));
			Path component = work.resolve("proxy.wasm");
			compileComponent(driver, source, component, work);

			int port = freePort();
			server = startServe(component, port, work, List.of());
			waitForPort(port, server, work.resolve("serve.log"));

			HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
			HttpResponse<byte[]> octets = client.send(HttpRequest.newBuilder(uri(port, "/octets")).build(),
					HttpResponse.BodyHandlers.ofByteArray());
			assertThat(octets.statusCode()).isEqualTo(200);
			assertThat(octets.body()).containsExactly((byte) 0xff, (byte) 0xfe, 0x41);
			HttpResponse<String> missing = client.send(HttpRequest.newBuilder(uri(port, "/status/404")).build(),
					HttpResponse.BodyHandlers.ofString());
			assertThat(missing.statusCode()).isEqualTo(404);
			assertThat(missing.body()).isEqualTo("missing");
		}
		finally {
			if (server != null) {
				server.destroyForcibly();
				server.waitFor(10, TimeUnit.SECONDS);
			}
			upstream.stop(0);
			deleteRecursively(work);
		}
	}

	private static URI uri(int port, String path) {
		return URI.create("http://127.0.0.1:" + port + path);
	}

}
