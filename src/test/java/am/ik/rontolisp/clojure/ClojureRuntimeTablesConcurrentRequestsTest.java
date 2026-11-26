package am.ik.rontolisp.clojure;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.cli.JvmSourceCompiler;
import am.ik.rontolisp.eval.LispEvaluator;
import am.ik.rontolisp.eval.SourceLanguage;
import am.ik.rontolisp.eval.SourceLoader;
import am.ik.rontolisp.eval.SourceStandards;
import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.runtime.RontoHttpServer;
import am.ik.rontolisp.testsupport.CliStack;
import am.ik.rontolisp.testsupport.ThreadStdio;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Clojure runtime's process-wide tables under a burst of concurrent first requests: a
 * Ring handler runs one thread per request on the interpreter and the JVM. Every request
 * attaches metadata, builds a reify of a protocol and an interface, keys maps by fresh
 * vectors and by a deftype with a hash and an equality of its own, and takes a var and a
 * namespace twice; a lost entry answers one of the checks false.
 */
class ClojureRuntimeTablesConcurrentRequestsTest {

	private static final int REQUESTS = 48;

	private static final String EXPECTED = "[true true true true true true true]";

	@TempDir
	Path dir;

	private static String program(int port) {
		return """
				(ns burst (:require [ring.adapter.rontolisp :as ring]))
				(defprotocol Sized (size [this]))
				(deftype K [a]
				  Object
				  (hashCode [_] (hash a))
				  (equals [_ o] (and (instance? K o) (= a (.-a o)))))
				(def ids (atom 0))
				(defn one [n]
				  (let [v (with-meta [n] {:n n})
				        r (reify Sized (size [_] n) clojure.lang.Counted (count [_] n))
				        m (into {} (map (fn [i] [[n i] i]) (range 16)))
				        k (into {} (map (fn [i] [(K. [n i]) i]) (range 4)))]
				    [(= n (:n (meta v)))
				     (= n (size r))
				     (= n (count r))
				     (= 7 (get m [n 7]))
				     (= 3 (get k (K. [n 3])))
				     (identical? #'one #'one)
				     (identical? (the-ns 'burst) (the-ns 'burst))]))
				(defn handler [req]
				  (let [base (* 1000 (swap! ids inc))]
				    {:body (pr-str (reduce (fn [acc v] (mapv #(and %1 %2) acc v))
				                           (map one (range base (+ base 40)))))}))
				(println (ring/run-server handler {:port %PORT% :host "127.0.0.1" :join? false}))
				""".replace("%PORT%", Integer.toString(port));
	}

	@Test
	void theInterpreterAnswersABurstOfFirstRequests() throws Exception {
		for (int round = 0; round < 3; round++) {
			int port = freePort();
			Path source = Files.createDirectories(this.dir.resolve("i" + round)).resolve("burst.clj");
			Files.writeString(source, program(port));
			List<LispVal> forms = SourceLanguage.CLOJURE.read(Files.readString(source), Features.INTERPRETER,
					source.toString(), SourceStandards.DEFAULT, SourceLoader.fileSystem());
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			CliStack.call("clojure-burst", () -> {
				LispEvaluator evaluator = new LispEvaluator(new PrintStream(out, true, StandardCharsets.UTF_8));
				for (LispVal form : evaluator.clojureProgram(forms)) {
					evaluator.eval(form);
				}
				return null;
			});
			long handle = Long.parseLong(out.toString(StandardCharsets.UTF_8).strip());
			try {
				assertBurst(port);
			}
			finally {
				RontoHttpServer.stopServer(handle);
			}
		}
	}

	@Test
	void theJvmAnswersABurstOfFirstRequests() throws Exception {
		for (int round = 0; round < 3; round++) {
			int port = freePort();
			Path source = Files.createDirectories(this.dir.resolve("j" + round)).resolve("burst.clj");
			Files.writeString(source, program(port));
			String name = "TablesBurst" + round;
			JvmSourceCompiler.Result result = new JvmSourceCompiler(name).sourceLanguage("clojure")
				.compile(Files.readString(source), source.toString());
			Path classes = Files.createDirectories(this.dir.resolve("classes" + round));
			Files.write(classes.resolve(name + ".class"), result.classBytes());
			for (Map.Entry<String, byte[]> file : result.runtimeClasses().entrySet()) {
				Path target = classes.resolve(file.getKey());
				Files.createDirectories(target.getParent());
				Files.write(target, file.getValue());
			}
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			// the loader stays open: the served handler keeps loading the program's
			// classes after main returns
			var loader = new java.net.URLClassLoader(new java.net.URL[] { classes.toUri().toURL() },
					ClassLoader.getSystemClassLoader());
			try (var _ = ThreadStdio.out(out)) {
				CliStack.call("clojure-burst", () -> {
					Method main = loader.loadClass(name).getMethod("main", String[].class);
					main.invoke(null, (Object) new String[0]);
					return null;
				});
			}
			long handle = Long.parseLong(out.toString(StandardCharsets.UTF_8).strip());
			try {
				assertBurst(port);
			}
			finally {
				RontoHttpServer.stopServer(handle);
			}
		}
	}

	/** {@link #REQUESTS} first requests at once, each answered in full. */
	private static void assertBurst(int port) throws Exception {
		HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
		CountDownLatch start = new CountDownLatch(1);
		List<Future<HttpResponse<String>>> replies = new ArrayList<>();
		try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
			for (int i = 0; i < REQUESTS; i++) {
				replies.add(pool.submit(() -> {
					start.await();
					return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/")).build(),
							HttpResponse.BodyHandlers.ofString());
				}));
			}
			start.countDown();
			List<String> bodies = new ArrayList<>();
			for (Future<HttpResponse<String>> reply : replies) {
				HttpResponse<String> response = reply.get();
				bodies.add(response.statusCode() + " " + response.body());
			}
			assertThat(bodies).containsOnly("200 " + EXPECTED);
		}
	}

	private static int freePort() throws IOException {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
	}

}
