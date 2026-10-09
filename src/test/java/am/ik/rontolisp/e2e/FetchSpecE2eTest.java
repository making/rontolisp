package am.ik.rontolisp.e2e;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.Deflater;

import am.ik.rontolisp.cli.RontoLispCli;
import am.ik.rontolisp.testsupport.HostWasmtime;
import am.ik.rontolisp.testsupport.InflateCases;
import am.ik.rontolisp.testsupport.YamlResources;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.dataformat.yaml.YAMLMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.abort;
import static org.junit.jupiter.api.DynamicContainer.dynamicContainer;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/**
 * The cross-backend corpus of {@code rontolisp:fetch} ({@code fetch-spec.yaml}): every
 * transport a program fetches through, against one local origin, printing the same text.
 * The interpreter and the JVM fetch through the JDK's {@code HttpClient}, a
 * {@code --native} output through its runner's {@code rlhttp} host
 * ({@code rontolisp-native/runner/src/http}), a {@code --component} through
 * {@code wasi:http} under {@code wasmtime run -S http=y} (.kb/fetch-http.md). The
 * {@code ci-spec.yaml} idea: the cases are concatenated into one program per leg and the
 * output is sliced back per case, so a failure names its case and its leg. The second
 * corpus, {@code clojure-http-spec.yaml}, is the Clojure client
 * ({@code rontolisp.http-client}) over the same origin and legs, one {@code .clj} program
 * per leg.
 *
 * <p>
 * Every leg goes through the command line -- this JVM's {@link RontoLispCli}, or the
 * binary {@code -Drontolisp.binary=<path>} names (the native-image legs of CI, where the
 * binary's own {@code --native} resources are what is under test) -- and every compiled
 * output runs as its own process. The native leg is skipped where the compiler carries no
 * precompile shim for the host (failed under {@code -Drontolisp.native.required=true}),
 * the component leg where no {@code wasmtime} is on {@code PATH}. The
 * {@code --host-fetch} reactor has no leg: its transport is whatever JavaScript host
 * imports it ({@code WasmHostFetchBodyE2eTest}).
 */
class FetchSpecE2eTest {

	private static final String SPEC_RESOURCE = "/fetch-spec.yaml";

	private static final String CLOJURE_SPEC_RESOURCE = "/clojure-http-spec.yaml";

	/**
	 * The request fields {@code /headers} leaves out: what the transports add on their
	 * own (the default User-Agent included, which differs by target), not what the
	 * program asked for.
	 */
	private static final List<String> UNSHOWN_FIELDS = List.of("host", "connection", "content-length", "http2-settings",
			"upgrade", "user-agent", "transfer-encoding");

	private static final String ORIGIN_MARK = "@ORIGIN@";

	/**
	 * Stands for the leg's own directory, where a case may write a file: the component
	 * leg preopens it, the native runner preopens its working directory, which it is.
	 */
	private static final String SCRATCH_MARK = "@SCRATCH@";

	/** The native binary that compiles, or {@code null} for this JVM. */
	private static final @Nullable String BINARY = System.getProperty("rontolisp.binary");

	/**
	 * A build that must carry the host's pair fails the native leg instead of skipping.
	 */
	private static final boolean REQUIRED = Boolean.getBoolean("rontolisp.native.required");

	private static final String NOT_AVAILABLE = "--native is not available for ";

	/** The octets {@code /octets} answers: not UTF-8, so nothing may decode them. */
	private static final byte[] OCTETS = { (byte) 0xff, (byte) 0xfe, 0x41, 0x00, (byte) 0x80, 0x7f };

	/** How long {@code /big} is: several reads on every transport. */
	private static final int BIG = 300_000;

	/** The length {@code /cut-short} announces and never sends. */
	private static final int CUT_SHORT = 1000;

	enum Leg {

		INTERPRETER, JVM, NATIVE, COMPONENT;

		/** The leg's key under a case's {@code skip:}. */
		String key() {
			return name().toLowerCase(java.util.Locale.ROOT);
		}

	}

	/**
	 * One case: its program, what it prints, and the legs it must not run on with why
	 * (each a divergence the .kb names).
	 */
	record Case(String name, String source, String expected, @Nullable Map<String, String> skip) {

		boolean runsOn(Leg leg) {
			return this.skip == null || !this.skip.containsKey(leg.key());
		}

		List<String> expectedLines() {
			return lines(this.expected);
		}

	}

	record Spec(List<Case> cases) {

		List<Case> on(Leg leg) {
			return this.cases.stream().filter(c -> c.runsOn(leg)).toList();
		}

	}

	@TempDir
	static Path workDir;

	private static @Nullable HttpServer origin;

	@BeforeAll
	static void startOrigin() throws IOException {
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/hello", exchange -> {
			boolean custom = "abc".equals(exchange.getRequestHeaders().getFirst("X-Custom"));
			exchange.getResponseHeaders().add("X-Test", "ok");
			answer(exchange, 200, custom ? "got-header" : "hello-from-fetch");
		});
		server.createContext("/echo", exchange -> answer(exchange, 200, exchange.getRequestMethod() + ":"
				+ new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
		server.createContext("/agent", exchange -> {
			List<String> agents = exchange.getRequestHeaders().get("User-Agent");
			answer(exchange, 200, String.join("|", agents == null ? List.of() : agents));
		});
		server.createContext("/status/404", exchange -> answer(exchange, 404, "missing"));
		server.createContext("/status/204", exchange -> {
			exchange.sendResponseHeaders(204, -1);
			exchange.close();
		});
		server.createContext("/redirect", exchange -> {
			exchange.getResponseHeaders().add("Location", "/hello");
			answer(exchange, 302, "moved");
		});
		// A reply that pauses mid-body: the second chunk is not there when the first
		// read of it is made.
		server.createContext("/chunked", exchange -> {
			exchange.sendResponseHeaders(200, 0);
			try (OutputStream body = exchange.getResponseBody()) {
				body.write("first-".getBytes(StandardCharsets.UTF_8));
				body.flush();
				Thread.sleep(200);
				body.write("second".getBytes(StandardCharsets.UTF_8));
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
		});
		server.createContext("/octets", exchange -> {
			exchange.getResponseHeaders().add("Content-Type", "application/octet-stream");
			answer(exchange, 200, OCTETS);
		});
		server.createContext("/utf8", exchange -> {
			exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
			answer(exchange, 200, "héllo, 世界");
		});
		server.createContext("/big", exchange -> {
			byte[] body = new byte[BIG];
			for (int i = 0; i < BIG; i++) {
				body[i] = (byte) (i % 251);
			}
			answer(exchange, 200, body);
		});
		// A transfer that fails mid-body: the head promises more than ever comes, and
		// closing the exchange short of its Content-Length closes the connection.
		server.createContext("/cut-short", exchange -> {
			exchange.sendResponseHeaders(200, CUT_SHORT);
			OutputStream body = exchange.getResponseBody();
			body.write(new byte[10]);
			body.flush();
			exchange.close();
		});
		server.createContext("/cookies", exchange -> {
			exchange.getResponseHeaders().add("Set-Cookie", "a=1");
			exchange.getResponseHeaders().add("Set-Cookie", "b=2");
			answer(exchange, 200, "two");
		});
		// The Clojure client's (clojure-http-spec.yaml) beyond those.
		server.createContext("/headers", exchange -> {
			Map<String, List<String>> fields = new TreeMap<>();
			exchange.getRequestHeaders().forEach((name, values) -> {
				String field = name.toLowerCase(Locale.ROOT);
				if (!UNSHOWN_FIELDS.contains(field)) {
					fields.computeIfAbsent(field, k -> new ArrayList<>()).addAll(values);
				}
			});
			answer(exchange, 200, fields.toString());
		});
		server.createContext("/query",
				exchange -> answer(exchange, 200, String.valueOf(exchange.getRequestURI().getRawQuery())));
		server.createContext("/form",
				exchange -> answer(exchange, 200, exchange.getRequestHeaders().getFirst("Content-Type") + "|"
						+ new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
		server.createContext("/status/500", exchange -> answer(exchange, 500, "broken"));
		server.createContext("/status/308", exchange -> {
			exchange.getResponseHeaders().add("Location", "/hello");
			answer(exchange, 308, "moved");
		});
		server.createContext("/redirect-post", exchange -> {
			exchange.getResponseHeaders().add("Location", "/echo");
			answer(exchange, 302, "moved");
		});
		server.createContext("/redirect-307", exchange -> {
			exchange.getResponseHeaders().add("Location", "/echo");
			answer(exchange, 307, "moved");
		});
		server.createContext("/redirect-303", exchange -> {
			exchange.getResponseHeaders().add("Location", "echo");
			answer(exchange, 303, "moved");
		});
		server.createContext("/loop", exchange -> {
			String query = exchange.getRequestURI().getRawQuery();
			long hop = query == null ? 0 : Long.parseLong(query);
			exchange.getResponseHeaders().add("Location", "/loop?" + (hop + 1));
			answer(exchange, 302, "hop " + hop);
		});
		server.createContext("/no-location", exchange -> answer(exchange, 302, "nowhere"));
		server.createContext("/slow", exchange -> {
			try {
				Thread.sleep(1000);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
			answer(exchange, 200, "slow");
		});
		server.createContext("/lines", exchange -> {
			exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
			answer(exchange, 200, "héllo, 世界\nsecond line\n");
		});
		// Compressed replies: /lines' text under each coding the client reads, the
		// coding's name in another case, two gzip members, and replies their coding
		// does not describe -- not gzip, empty, cut short, a wrong CRC -- plus a coding
		// the client leaves alone.
		byte[] lines = "héllo, 世界\nsecond line\n".getBytes(StandardCharsets.UTF_8);
		byte[] gzipLines = InflateCases.gzip(lines);
		coded(server, "/gzip", "gzip", 200, gzipLines);
		coded(server, "/gzip-upper", "GZIP", 200, gzipLines);
		coded(server, "/deflate", "deflate", 200,
				InflateCases.deflate(lines, Deflater.DEFAULT_COMPRESSION, false, Deflater.DEFAULT_STRATEGY));
		coded(server, "/deflate-raw", "deflate", 200,
				InflateCases.deflate(lines, Deflater.DEFAULT_COMPRESSION, true, Deflater.DEFAULT_STRATEGY));
		ByteArrayOutputStream twice = new ByteArrayOutputStream();
		twice.writeBytes(InflateCases.gzip("one ".getBytes(StandardCharsets.UTF_8)));
		twice.writeBytes(InflateCases.gzip("two".getBytes(StandardCharsets.UTF_8)));
		coded(server, "/gzip-twice", "gzip", 200, twice.toByteArray());
		coded(server, "/gzip-404", "gzip", 404, InflateCases.gzip("missing".getBytes(StandardCharsets.UTF_8)));
		coded(server, "/gzip-bad", "gzip", 200, "not gzip at all".getBytes(StandardCharsets.UTF_8));
		coded(server, "/gzip-empty", "gzip", 200, new byte[0]);
		coded(server, "/gzip-truncated", "gzip", 200, Arrays.copyOf(gzipLines, gzipLines.length - 12));
		byte[] badCrc = gzipLines.clone();
		badCrc[badCrc.length - 6]++;
		coded(server, "/gzip-badcrc", "gzip", 200, badCrc);
		coded(server, "/br", "br", 200, "brotli?".getBytes(StandardCharsets.UTF_8));
		server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
		server.start();
		origin = server;
	}

	@AfterAll
	static void stopOrigin() {
		HttpServer server = origin;
		if (server != null) {
			server.stop(0);
			origin = null;
		}
	}

	private static void answer(HttpExchange exchange, int status, String body) throws IOException {
		answer(exchange, status, body.getBytes(StandardCharsets.UTF_8));
	}

	// A reply whose octets are BODY under the content coding CODING.
	private static void coded(HttpServer server, String path, String coding, int status, byte[] body) {
		server.createContext(path, exchange -> {
			exchange.getResponseHeaders().add("Content-Encoding", coding);
			answer(exchange, status, body);
		});
	}

	// A HEAD is answered with the headers a GET would carry and no body.
	private static void answer(HttpExchange exchange, int status, byte[] body) throws IOException {
		boolean head = "HEAD".equals(exchange.getRequestMethod());
		exchange.sendResponseHeaders(status, head ? -1 : body.length);
		try (OutputStream out = exchange.getResponseBody()) {
			if (!head) {
				out.write(body);
			}
		}
	}

	@TestFactory
	Stream<DynamicNode> e2e() throws Exception {
		return corpus(SPEC_RESOURCE, "fetch-spec.lisp");
	}

	/**
	 * The Clojure client, {@code rontolisp.http-client}, over the same origin and legs
	 * ({@code clojure-http-spec.yaml}): every request it makes goes through
	 * {@code rontolisp:fetch}, so each transport is one leg here too.
	 */
	@TestFactory
	Stream<DynamicNode> clojureHttpClient() throws Exception {
		return corpus(CLOJURE_SPEC_RESOURCE, "http-spec.clj");
	}

	private static Stream<DynamicNode> corpus(String resource, String fileName) throws Exception {
		Spec spec = loadSpec(resource);
		String originUrl = "http://127.0.0.1:" + Objects.requireNonNull(origin).getAddress().getPort();
		// The four runs share nothing but the origin, which serves each request on a
		// thread of its own, so they run at once; the slices are built once all four
		// have answered.
		List<Callable<String>> runs = new ArrayList<>();
		for (Leg leg : Leg.values()) {
			String program = program(spec.on(leg), originUrl);
			runs.add(started(leg, () -> run(leg, program, fileName)));
		}
		List<DynamicNode> legs = new ArrayList<>();
		for (Leg leg : Leg.values()) {
			legs.add(leg(leg, spec.on(leg), runs.get(leg.ordinal())));
		}
		return legs.stream();
	}

	private static String program(List<Case> cases, String originUrl) {
		StringBuilder program = new StringBuilder();
		for (Case c : cases) {
			program.append(c.source().replace(ORIGIN_MARK, originUrl));
		}
		return program.toString();
	}

	private static DynamicContainer leg(Leg leg, List<Case> cases, Callable<String> run) {
		List<String> actual;
		try {
			actual = lines(run.call());
		}
		catch (Skipped skipped) {
			return dynamicContainer(leg.name(),
					Stream.of(dynamicTest("(skipped)", () -> abort(Objects.requireNonNull(skipped.getMessage())))));
		}
		catch (Exception | StackOverflowError ex) {
			return dynamicContainer(leg.name(),
					Stream.of(dynamicTest("(execution failed)", () -> fail(leg + ": " + ex, ex))));
		}
		List<DynamicTest> tests = new ArrayList<>();
		int expectedTotal = cases.stream().mapToInt(c -> c.expectedLines().size()).sum();
		tests.add(dynamicTest("total-line-count",
				() -> assertThat(actual).as("%s printed a different number of lines than the spec", leg)
					.hasSize(expectedTotal)));
		int offset = 0;
		for (Case c : cases) {
			List<String> expected = c.expectedLines();
			int start = Math.min(offset, actual.size());
			List<String> slice = actual.subList(start, Math.min(offset + expected.size(), actual.size()));
			offset += expected.size();
			tests.add(dynamicTest(c.name(),
					() -> assertThat(slice)
						.as("case '%s' on %s%n--- source ---%n%s--- end source ---", c.name(), leg, c.source())
						.containsExactlyElementsOf(expected)));
		}
		return dynamicContainer(leg.name(), tests.stream());
	}

	/** A leg this machine cannot run; its container reports the reason as a skip. */
	private static final class Skipped extends Exception {

		Skipped(String message) {
			super(message);
		}

	}

	private static String run(Leg leg, String program, String fileName) throws Exception {
		String stem = fileName.substring(0, fileName.lastIndexOf('.'));
		Path dir = Files.createDirectories(workDir.resolve(stem + "-" + leg.key()));
		Path source = dir.resolve(fileName);
		Files.writeString(source, program.replace(SCRATCH_MARK, dir.toAbsolutePath().toString()),
				StandardCharsets.UTF_8);
		return switch (leg) {
			case INTERPRETER -> interpret(source);
			case JVM -> {
				compile(source, dir.resolve("FetchSpec.class"), "--class-name", "FetchSpec");
				yield exec(dir, List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp",
						dir.toString(), "FetchSpec"));
			}
			case NATIVE -> {
				Path executable = dir.resolve(stem);
				try {
					compile(source, executable, "--native");
				}
				catch (UnsupportedOperationException ex) {
					String message = String.valueOf(ex.getMessage());
					if (message.contains(NOT_AVAILABLE) && !REQUIRED) {
						throw new Skipped(message);
					}
					throw ex;
				}
				yield exec(dir, List.of(executable.toString()));
			}
			case COMPONENT -> {
				if (!HostWasmtime.isAvailable()) {
					throw new Skipped("no usable wasmtime on PATH");
				}
				Path component = dir.resolve(stem + ".wasm");
				compile(source, component, "--component");
				yield exec(dir, List.of("wasmtime", "run", "-W", "gc=y", "-W", "exceptions=y", "-S", "http=y", "--dir",
						dir.toAbsolutePath().toString(), component.toString()));
			}
		};
	}

	private static String interpret(Path source) throws Exception {
		if (BINARY != null) {
			return exec(Objects.requireNonNull(source.getParent()),
					List.of(Path.of(BINARY).toAbsolutePath().toString(), source.toString()));
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		new RontoLispCli(new ByteArrayInputStream(new byte[0]), new PrintStream(out, true, StandardCharsets.UTF_8))
			.run(new String[] { source.toString() });
		return out.toString(StandardCharsets.UTF_8);
	}

	private static void compile(Path source, Path output, String... flags) throws Exception {
		List<String> args = new ArrayList<>(List.of(source.toString(), "-o", output.toString()));
		args.addAll(List.of(flags));
		if (BINARY == null) {
			new RontoLispCli(new ByteArrayInputStream(new byte[0]),
					new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8))
				.run(args.toArray(String[]::new));
			return;
		}
		args.addFirst(Path.of(BINARY).toAbsolutePath().toString());
		Path dir = Objects.requireNonNull(source.getParent());
		Process process = new ProcessBuilder(args).directory(dir.toFile())
			.redirectInput(ProcessBuilder.Redirect.from(Path.of("/dev/null").toFile()))
			.redirectOutput(dir.resolve("compile.out").toFile())
			.redirectError(dir.resolve("compile.err").toFile())
			.start();
		if (!process.waitFor(300, TimeUnit.SECONDS)) {
			process.destroyForcibly().waitFor();
			throw new IllegalStateException("compile timed out: " + String.join(" ", args));
		}
		if (process.exitValue() != 0) {
			// The binary reports the in-process exception on stderr; raise it as one so
			// both drivers skip (or fail) alike.
			String stderr = Files.readString(dir.resolve("compile.err"));
			if (stderr.contains(NOT_AVAILABLE)) {
				throw new UnsupportedOperationException(stderr.strip());
			}
			throw new IllegalStateException("compile failed (" + process.exitValue() + "): " + stderr);
		}
	}

	private static String exec(Path dir, List<String> command) throws Exception {
		Path stdout = dir.resolve("run.out");
		Path stderr = dir.resolve("run.err");
		Process process = new ProcessBuilder(command).directory(dir.toFile())
			.redirectInput(ProcessBuilder.Redirect.from(Path.of("/dev/null").toFile()))
			.redirectOutput(stdout.toFile())
			.redirectError(stderr.toFile())
			.start();
		if (!process.waitFor(300, TimeUnit.SECONDS)) {
			process.destroyForcibly().waitFor();
			throw new IllegalStateException("timed out: " + String.join(" ", command));
		}
		String out = Files.readString(stdout, StandardCharsets.UTF_8);
		if (process.exitValue() != 0) {
			throw new IllegalStateException(command.getFirst() + " exited " + process.exitValue() + "\n--- stdout ---\n"
					+ out + "--- stderr ---\n" + Files.readString(stderr, StandardCharsets.UTF_8));
		}
		return out;
	}

	// Starts run on a thread of its own and answers its result, rethrowing what it threw.
	private static Callable<String> started(Leg leg, Callable<String> run) {
		FutureTask<String> task = new FutureTask<>(run);
		Thread.ofPlatform().name("fetch-spec-" + leg.key()).start(task);
		return () -> {
			try {
				return task.get();
			}
			catch (ExecutionException ex) {
				if (ex.getCause() instanceof Exception cause) {
					throw cause;
				}
				if (ex.getCause() instanceof Error cause) {
					throw cause;
				}
				throw ex;
			}
		};
	}

	private static Spec loadSpec(String resource) throws IOException {
		try (InputStream in = FetchSpecE2eTest.class.getResourceAsStream(resource)) {
			assertThat(in).as("test resource %s", resource).isNotNull();
			return YAMLMapper.builder()
				.build()
				.readValue(YamlResources.safeReader(new String(in.readAllBytes(), StandardCharsets.UTF_8)), Spec.class);
		}
	}

	private static List<String> lines(String text) {
		if (text.isEmpty()) {
			return List.of();
		}
		String trimmed = text.endsWith("\n") ? text.substring(0, text.length() - 1) : text;
		return List.of(trimmed.split("\n", -1));
	}

}
