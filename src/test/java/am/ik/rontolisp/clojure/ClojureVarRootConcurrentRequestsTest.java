package am.ik.rontolisp.clojure;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
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
 * A var's root and metadata altered by concurrently served requests: a Ring handler runs
 * one thread per request on the interpreter and the JVM, and every request alters a var's
 * root and a var's metadata {@link #UPDATES} times each -- {@code alter-var-root} and
 * {@code alter-meta!} through a literal {@code #'} and as values -- through a function
 * slow enough to widen the window between the read and the write. Each alteration holds
 * the reference's monitor, like the oracle's synchronized methods, so none is lost.
 */
class ClojureVarRootConcurrentRequestsTest {

	private static final int REQUESTS = 48;

	private static final int UPDATES = 20;

	private static final String EXPECTED = "[960 960 960 960]";

	@TempDir
	Path dir;

	private static String program(int port) {
		return """
				(ns roots (:require [ring.adapter.rontolisp :as ring]))
				(def hits 0)
				(def value-hits 0)
				(def tagged 0)
				(def value-tagged 0)
				(defn slow-inc [n] (inc (+ (or n 0) (- (reduce + (range 64)) (reduce + (range 64))))))
				(defn handler [req]
				  (if (= "/count" (:uri req))
				    {:body (pr-str [hits value-hits (:n (meta #'tagged)) (:n (meta #'value-tagged))])}
				    (do (dotimes [_ %UPDATES%]
				          (alter-var-root #'hits slow-inc)
				          (apply alter-var-root [#'value-hits slow-inc])
				          (alter-meta! #'tagged update :n slow-inc)
				          (apply alter-meta! [#'value-tagged update :n slow-inc]))
				        {:body "ok"})))
				(println (ring/run-server handler {:port %PORT% :host "127.0.0.1" :join? false}))
				""".replace("%PORT%", Integer.toString(port)).replace("%UPDATES%", Integer.toString(UPDATES));
	}

	@Test
	void theInterpreterLosesNoAlterationUnderABurst() throws Exception {
		for (int round = 0; round < 2; round++) {
			int port = freePort();
			Path source = Files.createDirectories(this.dir.resolve("i" + round)).resolve("hits.clj");
			Files.writeString(source, program(port));
			List<LispVal> forms = SourceLanguage.CLOJURE.read(Files.readString(source), Features.INTERPRETER,
					source.toString(), SourceStandards.DEFAULT, SourceLoader.fileSystem());
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			CliStack.call("clojure-var-root-burst", () -> {
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
	void theJvmLosesNoAlterationUnderABurst() throws Exception {
		for (int round = 0; round < 2; round++) {
			int port = freePort();
			Path source = Files.createDirectories(this.dir.resolve("j" + round)).resolve("hits.clj");
			Files.writeString(source, program(port));
			String name = "VarRootBurst" + round;
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
			var loader = new URLClassLoader(new URL[] { classes.toUri().toURL() }, ClassLoader.getSystemClassLoader());
			try (var _ = ThreadStdio.out(out)) {
				CliStack.call("clojure-var-root-burst", () -> {
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

	/** {@link #REQUESTS} requests at once, then the four counts. */
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
			for (Future<HttpResponse<String>> reply : replies) {
				HttpResponse<String> response = reply.get();
				assertThat(response.statusCode() + " " + response.body()).isEqualTo("200 ok");
			}
		}
		HttpResponse<String> counts = client.send(
				HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/count")).build(),
				HttpResponse.BodyHandlers.ofString());
		assertThat(counts.body()).isEqualTo(EXPECTED);
	}

	private static int freePort() throws IOException {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
	}

}
