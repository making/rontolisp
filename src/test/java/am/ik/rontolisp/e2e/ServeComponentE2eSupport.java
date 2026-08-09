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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one way the suite drives a {@code --component} build over REAL HTTP: compile a
 * program to a component with the resolved driver, start {@code wasmtime serve} on a free
 * port, wait for the bind, send requests, tear the server down. Shared by every
 * {@code Serve*ComponentE2eTest}; the tests keep only their program and their assertions.
 *
 * <p>
 * All of it is opt-in the same way {@link ExamplesE2eTest} is: a driver comes from
 * {@code -Drontolisp.binary=<native binary>} or, under {@code -Drontolisp.examples=true},
 * the newest {@code target/*-exec.jar}; {@code wasmtime} 46+ must be on {@code PATH}. A
 * plain {@code mvn test} skips the callers.
 */
final class ServeComponentE2eSupport {

	static final Path PROJECT_DIR = Path.of("").toAbsolutePath();

	/** wasmtime 46 needs a moment to compile the GC module and bind the socket. */
	private static final Duration BIND_TIMEOUT = Duration.ofSeconds(30);

	private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

	private ServeComponentE2eSupport() {
	}

	static void compileComponent(List<String> driver, Path source, Path out, Path work) throws Exception {
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

	/**
	 * Start {@code wasmtime serve} on the component.
	 * @param component the compiled component
	 * @param port the port to serve on
	 * @param work the working directory; {@code serve.log} is written there
	 * @param extraFlags flags beyond the {@code -W gc/exceptions} every served rontolisp
	 * component needs (async EH mode) -- e.g. the {@code -S} grants a component whose
	 * imports reach {@code wasi:cli} / {@code wasi:sockets} must be linked with
	 * @return the server process
	 */
	static Process startServe(Path component, int port, Path work, List<String> extraFlags) throws IOException {
		List<String> command = new ArrayList<>(
				List.of("wasmtime", "serve", "-W", "gc=y", "-W", "exceptions=y", "--addr", "127.0.0.1:" + port));
		command.addAll(extraFlags);
		command.add(component.toString());
		return new ProcessBuilder(command).directory(work.toFile())
			.redirectOutput(work.resolve("serve.log").toFile())
			.redirectErrorStream(true)
			.start();
	}

	static void waitForPort(int port, Process server, Path serveLog) throws Exception {
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

	static HttpResponse<String> request(int port, String method, String path) throws Exception {
		HttpClient client = HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build();
		HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
			.timeout(REQUEST_TIMEOUT);
		HttpRequest req = "GET".equals(method) ? builder.GET().build()
				: builder.method(method, HttpRequest.BodyPublishers.noBody()).build();
		return client.send(req, HttpResponse.BodyHandlers.ofString());
	}

	static int freePort() throws IOException {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
	}

	static @Nullable List<String> resolveDriver() {
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

	static boolean onPath(String tool) {
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

	static String readOrEmpty(Path file) {
		try {
			return Files.isRegularFile(file) ? Files.readString(file) : "";
		}
		catch (IOException ex) {
			return "";
		}
	}

	static void deleteRecursively(Path root) {
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
