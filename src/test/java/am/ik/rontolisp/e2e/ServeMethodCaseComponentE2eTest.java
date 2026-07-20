package am.ik.rontolisp.e2e;

import java.io.File;
import java.io.IOException;
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
import java.util.concurrent.TimeUnit;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The one place a {@code --component} program reads a rich WIT type BACK from a real host
 * and branches on its raw tag: {@code wasi:http}'s {@code method} variant, which
 * {@code request.get-method} lifts and {@code http.lisp}'s {@code %serve-method-string}
 * inspects. Under the reader's upcase premise that lifted case name reads upcased
 * ({@code :POST}), so a lowercase-only comparison ({@code (eq m :post)}) silently misses
 * and every non-GET method collapses to {@code GET} (todo-155 item 2). This pins the fix
 * end-to-end: compile {@code examples/net/http-handler.lisp} to a component, serve it
 * with {@code wasmtime serve}, and confirm each HTTP method round-trips as itself.
 * <p>
 * The interpreter and JVM serve through a Java-backed server whose method never passes
 * through {@code %serve-method-string} (they are covered by {@code HttpHandlerJvmTest} /
 * {@code HttpHandlerTest}); only the component backend lifts the variant, so only it can
 * regress here.
 * <p>
 * Opt-in and gated, exactly like {@link ExamplesE2eTest}: it needs a driver
 * ({@code -Drontolisp.binary=<native binary>}, or the built {@code target/*-exec.jar}
 * when {@code -Drontolisp.examples=true}) and a {@code wasmtime} 46+ on {@code PATH}. A
 * plain {@code mvn test} skips it.
 */
class ServeMethodCaseComponentE2eTest {

	private static final Path PROJECT_DIR = Path.of("").toAbsolutePath();

	private static final Path HANDLER_SOURCE = PROJECT_DIR.resolve("examples/net/http-handler.lisp");

	/** wasmtime 46 needs a moment to compile the GC module and bind the socket. */
	private static final Duration BIND_TIMEOUT = Duration.ofSeconds(30);

	private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

	@Test
	void everyHttpMethodRoundTripsAsItselfOnAServedComponent() throws Exception {
		List<String> driver = resolveDriver();
		assumeTrue(driver != null, "serve component E2E is opt-in: pass -Drontolisp.binary=<native binary> or "
				+ "-Drontolisp.examples=true (after ./mvnw clean package -DskipTests)");
		assumeTrue(onPath("wasmtime"), "wasmtime is not on PATH");
		assumeTrue(Files.isRegularFile(HANDLER_SOURCE), () -> "handler example is missing: " + HANDLER_SOURCE);

		Path work = Files.createTempDirectory("rontolisp-serve-");
		Process server = null;
		try {
			Path component = work.resolve("handler-component.wasm");
			compileComponent(driver, HANDLER_SOURCE, component, work);

			int port = freePort();
			server = startServe(component, port, work);
			waitForPort(port, server, work.resolve("serve.log"));

			// GET is the default arm, so it passes even with the bug -- the non-GET
			// methods
			// are what a lowercase-only compare drops to "GET". http-handler.lisp echoes
			// "<method> <path>" on its second line.
			for (String method : List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS")) {
				HttpResponse<String> response = request(port, method, "/probe");
				assertThat(response.statusCode()).as("%s status", method).isEqualTo(200);
				assertThat(response.body()).as("%s must round-trip as itself, not collapse to GET", method)
					.contains(method + " /probe");
			}
		}
		finally {
			if (server != null) {
				server.destroyForcibly();
				server.waitFor(10, TimeUnit.SECONDS);
			}
			deleteRecursively(work);
		}
	}

	private static void compileComponent(List<String> driver, Path source, Path out, Path work) throws Exception {
		List<String> command = new ArrayList<>(driver);
		command.add(source.toString());
		command.add("-o");
		command.add(out.toString());
		command.add("--component");
		command.add("--optimize");
		Process process = new ProcessBuilder(command).directory(work.toFile()).redirectErrorStream(true).start();
		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		boolean finished = process.waitFor(120, TimeUnit.SECONDS);
		if (!finished) {
			process.destroyForcibly();
			throw new IllegalStateException("component compile timed out:\n" + output);
		}
		assertThat(process.exitValue()).as("compile --component failed:%n%s", output).isZero();
		assertThat(Files.isRegularFile(out)).as("component was not produced: %s", out).isTrue();
	}

	private static Process startServe(Path component, int port, Path work) throws IOException {
		// wasmtime 46+: -W gc/exceptions are the flags every served rontolisp component
		// needs (async EH mode); --addr pins the ephemeral port this test bound.
		List<String> command = List.of("wasmtime", "serve", "-W", "gc=y", "-W", "exceptions=y", "--addr",
				"127.0.0.1:" + port, component.toString());
		return new ProcessBuilder(command).directory(work.toFile())
			.redirectOutput(work.resolve("serve.log").toFile())
			.redirectErrorStream(true)
			.start();
	}

	private static void waitForPort(int port, Process server, Path serveLog) throws Exception {
		long deadline = System.nanoTime() + BIND_TIMEOUT.toNanos();
		while (System.nanoTime() < deadline) {
			if (!server.isAlive()) {
				throw new IllegalStateException(
						"wasmtime serve exited early (" + server.exitValue() + "):\n" + readOrEmpty(serveLog));
			}
			try (var socket = new java.net.Socket("127.0.0.1", port)) {
				return;
			}
			catch (IOException retry) {
				Thread.sleep(100);
			}
		}
		throw new IllegalStateException("wasmtime serve never bound 127.0.0.1:" + port + "\n" + readOrEmpty(serveLog));
	}

	private static HttpResponse<String> request(int port, String method, String path) throws Exception {
		HttpClient client = HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build();
		HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
			.timeout(REQUEST_TIMEOUT);
		HttpRequest req = "GET".equals(method) ? builder.GET().build()
				: builder.method(method, HttpRequest.BodyPublishers.noBody()).build();
		return client.send(req, HttpResponse.BodyHandlers.ofString());
	}

	private static int freePort() throws IOException {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
	}

	private static @Nullable List<String> resolveDriver() {
		String binary = System.getProperty("rontolisp.binary");
		if (binary != null) {
			Path bin = Path.of(binary).toAbsolutePath();
			return Files.isExecutable(bin) ? List.of(bin.toString()) : null;
		}
		if (!Boolean.getBoolean("rontolisp.examples")) {
			return null;
		}
		Path jar = newestExecJar();
		return (jar != null && Files.isRegularFile(jar)) ? List.of("java", "-jar", jar.toAbsolutePath().toString())
				: null;
	}

	private static @Nullable Path newestExecJar() {
		Path target = PROJECT_DIR.resolve("target");
		if (!Files.isDirectory(target)) {
			return null;
		}
		try (var jars = Files.list(target)) {
			return jars.filter(p -> p.getFileName().toString().endsWith("-exec.jar"))
				.max((a, b) -> Long.compare(a.toFile().lastModified(), b.toFile().lastModified()))
				.orElse(null);
		}
		catch (IOException ex) {
			return null;
		}
	}

	private static boolean onPath(String tool) {
		String path = System.getenv("PATH");
		if (path == null) {
			return false;
		}
		for (String dir : path.split(File.pathSeparator)) {
			if (Files.isExecutable(Path.of(dir).resolve(tool))) {
				return true;
			}
		}
		return false;
	}

	private static String readOrEmpty(Path file) {
		try {
			return Files.isRegularFile(file) ? Files.readString(file) : "";
		}
		catch (IOException ex) {
			return "";
		}
	}

	private static void deleteRecursively(Path root) {
		if (!Files.exists(root)) {
			return;
		}
		try (var walk = Files.walk(root)) {
			walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(p -> {
				try {
					Files.deleteIfExists(p);
				}
				catch (IOException ignored) {
					// best effort cleanup
				}
			});
		}
		catch (IOException ignored) {
			// best effort cleanup
		}
	}

}
