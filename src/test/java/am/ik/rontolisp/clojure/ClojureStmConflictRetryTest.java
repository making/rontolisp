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
import java.util.concurrent.TimeUnit;

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
 * Conflicting transactions retry (g37): concurrent requests bumping the same refs
 * converge to the oracle's totals on the interpreter and the JVM (no lost updates), a
 * writer of an ensured ref waits for its holder, and a writer of another ref does not
 * wait. A lost update leaves a count short; a missed ensure pin lets the other writer
 * through while the holder is still inside.
 */
class ClojureStmConflictRetryTest {

	private static final int REQUESTS = 16;

	private static final int UPDATES = 20;

	@TempDir
	Path dir;

	private static String program(int port) {
		return """
				(ns stmretry (:require [ring.adapter.rontolisp :as ring]))
				(def bumps (ref 0))
				(def cbump (ref 0))
				(def er (ref 0))
				(def other (ref 0))
				(def tlog (atom []))
				(defn slow-inc [n] (inc (+ n (- (reduce + (range 64)) (reduce + (range 64))))))
				(defn handler [req]
				  (let [uri (:uri req)]
				    (cond (= "/count" uri) {:body (pr-str [@bumps @cbump @er @other])}
				          (= "/flags" uri) {:body (pr-str @tlog)}
				          (= "/bump" uri) (do (dotimes [_ %UPDATES%] (dosync (alter bumps slow-inc)))
				                                (dotimes [_ %UPDATES%] (dosync (commute cbump slow-inc)))
				                                {:body "ok"})
				          (= "/hold" uri) (do (dosync (swap! tlog conj :hold-attempt) (ensure er)
				                                (swap! tlog conj :hold-in)
				                                (Thread/sleep 1500) (alter er inc))
				                            (swap! tlog conj :hold-out)
				                            {:body "held"})
				          (= "/ewrite" uri) (do (swap! tlog conj :writer-started)
				                                (dosync (swap! tlog conj :writer-attempt) (alter er inc))
				                                (swap! tlog conj :writer-committed)
				                                {:body "wrote"})
				          (= "/other" uri) (do (dosync (alter other inc))
				                               (swap! tlog conj :other-done)
				                               {:body "other"})
				          :else {:body "nope"})))
				(println (ring/run-server handler {:port %PORT% :host "127.0.0.1" :join? false}))
				""".replace("%PORT%", Integer.toString(port)).replace("%UPDATES%", Integer.toString(UPDATES));
	}

	@Test
	void theInterpreterConvergesAndEnsureBlocksWriters() throws Exception {
		int port = freePort();
		Path source = Files.createDirectories(this.dir.resolve("stm-i")).resolve("hits.clj");
		Files.writeString(source, program(port));
		List<LispVal> forms = SourceLanguage.CLOJURE.read(Files.readString(source), Features.INTERPRETER,
				source.toString(), SourceStandards.DEFAULT, SourceLoader.fileSystem());
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		CliStack.call("clojure-stm-retry", () -> {
			LispEvaluator evaluator = new LispEvaluator(new PrintStream(out, true, StandardCharsets.UTF_8));
			for (LispVal form : evaluator.clojureProgram(forms)) {
				evaluator.eval(form);
			}
			return null;
		});
		long handle = Long.parseLong(out.toString(StandardCharsets.UTF_8).strip());
		try {
			assertRetry(port);
		}
		finally {
			RontoHttpServer.stopServer(handle);
		}
	}

	@Test
	void theJvmConvergesAndEnsureBlocksWriters() throws Exception {
		int port = freePort();
		Path source = Files.createDirectories(this.dir.resolve("stm-j")).resolve("hits.clj");
		Files.writeString(source, program(port));
		String name = "StmRetry";
		JvmSourceCompiler.Result result = new JvmSourceCompiler(name).sourceLanguage("clojure")
			.compile(Files.readString(source), source.toString());
		Path classes = Files.createDirectories(this.dir.resolve("classes"));
		Files.write(classes.resolve(name + ".class"), result.classBytes());
		for (Map.Entry<String, byte[]> file : result.runtimeClasses().entrySet()) {
			Path target = classes.resolve(file.getKey());
			Files.createDirectories(target.getParent());
			Files.write(target, file.getValue());
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		var loader = new URLClassLoader(new URL[] { classes.toUri().toURL() }, ClassLoader.getSystemClassLoader());
		try (var _ = ThreadStdio.out(out)) {
			CliStack.call("clojure-stm-retry", () -> {
				Method main = loader.loadClass(name).getMethod("main", String[].class);
				main.invoke(null, (Object) new String[0]);
				return null;
			});
		}
		long handle = Long.parseLong(out.toString(StandardCharsets.UTF_8).strip());
		try {
			assertRetry(port);
		}
		finally {
			RontoHttpServer.stopServer(handle);
		}
	}

	/** The ensure protocol, then the concurrent bump convergence. */
	private static void assertRetry(int port) throws Exception {
		HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
		String base = "http://127.0.0.1:" + port;
		try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
			// the holder ensures er and sleeps inside its transaction
			Future<HttpResponse<String>> held = pool
				.submit(() -> client.send(HttpRequest.newBuilder(URI.create(base + "/hold")).build(),
						HttpResponse.BodyHandlers.ofString()));
			pollFor(client, base, ":hold-in");
			// a writer of another ref does not wait for the holder
			HttpResponse<String> other = client.send(HttpRequest.newBuilder(URI.create(base + "/other")).build(),
					HttpResponse.BodyHandlers.ofString());
			assertThat(other.body()).isEqualTo("other");
			assertThat(flags(client, base)).contains(":other-done").doesNotContain(":hold-out");
			// a writer of the ensured ref waits for the holder: its body runs (it
			// shares the machine) but its commit waits
			Future<HttpResponse<String>> writer = pool
				.submit(() -> client.send(HttpRequest.newBuilder(URI.create(base + "/ewrite")).build(),
						HttpResponse.BodyHandlers.ofString()));
			pollFor(client, base, ":writer-started");
			assertThat(flags(client, base)).doesNotContain(":writer-committed");
			assertThat(held.get(30, TimeUnit.SECONDS).body()).isEqualTo("held");
			assertThat(writer.get(30, TimeUnit.SECONDS).body()).isEqualTo("wrote");
			assertThat(counts(client, base)).isEqualTo("[0 0 2 1]");
			// concurrent bumps converge: no lost updates, commute included
			CountDownLatch start = new CountDownLatch(1);
			List<Future<HttpResponse<String>>> replies = new ArrayList<>();
			for (int i = 0; i < REQUESTS; i++) {
				replies.add(pool.submit(() -> {
					start.await();
					return client.send(HttpRequest.newBuilder(URI.create(base + "/bump")).build(),
							HttpResponse.BodyHandlers.ofString());
				}));
			}
			start.countDown();
			for (Future<HttpResponse<String>> reply : replies) {
				assertThat(reply.get(120, TimeUnit.SECONDS).body()).isEqualTo("ok");
			}
		}
		int bumped = REQUESTS * UPDATES;
		assertThat(counts(client, base)).isEqualTo("[" + bumped + " " + bumped + " 2 1]");
	}

	private static String counts(HttpClient client, String base) throws Exception {
		return client
			.send(HttpRequest.newBuilder(URI.create(base + "/count")).build(), HttpResponse.BodyHandlers.ofString())
			.body();
	}

	private static String flags(HttpClient client, String base) throws Exception {
		return client
			.send(HttpRequest.newBuilder(URI.create(base + "/flags")).build(), HttpResponse.BodyHandlers.ofString())
			.body();
	}

	private static void pollFor(HttpClient client, String base, String marker) throws Exception {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
		while (!flags(client, base).contains(marker)) {
			if (System.nanoTime() > deadline) {
				throw new AssertionError("timed out waiting for " + marker + ": " + flags(client, base));
			}
			Thread.sleep(50);
		}
	}

	private static int freePort() throws IOException {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
	}

}
