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
import java.util.stream.Collectors;
import java.util.stream.IntStream;

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
 * clojure.java.io's process-wide tables under a burst of concurrent first requests: a
 * Ring handler runs one thread per request on the interpreter and the JVM. Every request
 * reads resources literal names found while the program lowered -- deleted from the disk
 * before it runs, so a read missing the contents the program keeps fails -- and encodes
 * text through writers into byte streams, each writer registered until it is closed, so a
 * lost registration loses its text.
 */
class ClojureIoConcurrentRequestsTest {

	private static final int RESOURCES = 24;

	private static final int WRITERS = 24;

	private static final int REQUESTS = 48;

	@TempDir
	Path dir;

	private String program(int port) {
		String reads = IntStream.range(0, RESOURCES)
			.mapToObj(i -> "(io/resource \"r/" + i + ".txt\")")
			.collect(Collectors.joining(" "));
		return """
				(ns burst (:require [ring.adapter.rontolisp :as ring] [clojure.java.io :as io]))
				(defn written []
				  (let [outs (vec (repeatedly %WRITERS% #(java.io.ByteArrayOutputStream.)))
				        ws (mapv io/writer outs)]
				    (doseq [w ws] (.write w "w"))
				    (doseq [w ws] (.close w))
				    (reduce + (map #(count (.toByteArray %)) outs))))
				(defn handler [req]
				  {:body (pr-str [(written) (mapv slurp [%READS%])])})
				(println (ring/run-server handler {:port %PORT% :host "127.0.0.1" :join? false}))
				""".replace("%WRITERS%", Integer.toString(WRITERS))
			.replace("%READS%", reads)
			.replace("%PORT%", Integer.toString(port));
	}

	private static String expected() {
		return "[" + WRITERS + " ["
				+ IntStream.range(0, RESOURCES).mapToObj(i -> "\"r " + i + "\"").collect(Collectors.joining(" "))
				+ "]]";
	}

	/** The program's source beside the resources its literal names find. */
	private Path source(String leg, int port) throws IOException {
		Path root = Files.createDirectories(this.dir.resolve(leg)).toRealPath();
		for (int i = 0; i < RESOURCES; i++) {
			Path resource = root.resolve("r/" + i + ".txt");
			Files.createDirectories(resource.getParent());
			Files.writeString(resource, "r " + i);
		}
		Path source = root.resolve("burst.clj");
		Files.writeString(source, program(port));
		return source;
	}

	/** Deletes the resources the lowering read, so only the kept contents answer. */
	private static void deleteResources(Path source) throws IOException {
		for (int i = 0; i < RESOURCES; i++) {
			Files.delete(source.resolveSibling("r/" + i + ".txt"));
		}
	}

	@Test
	void theInterpreterAnswersABurstOfFirstRequests() throws Exception {
		for (int round = 0; round < 3; round++) {
			int port = freePort();
			Path source = source("i" + round, port);
			List<LispVal> forms = SourceLanguage.CLOJURE.read(Files.readString(source), Features.INTERPRETER,
					source.toString(), SourceStandards.DEFAULT, SourceLoader.fileSystem());
			deleteResources(source);
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
		for (int round = 0; round < 2; round++) {
			int port = freePort();
			Path source = source("j" + round, port);
			String name = "IoBurst" + round;
			JvmSourceCompiler.Result result = new JvmSourceCompiler(name).sourceLanguage("clojure")
				.compile(Files.readString(source), source.toString());
			deleteResources(source);
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
			for (Future<HttpResponse<String>> reply : replies) {
				HttpResponse<String> response = reply.get();
				assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
				assertThat(response.body()).isEqualTo(expected());
			}
		}
	}

	private static int freePort() throws IOException {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
	}

}
