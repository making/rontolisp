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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
 * {@code ci-spec.yaml} idea: the cases are concatenated into one program per leg, each
 * followed by a line marking its end, and the output is cut back into cases at those
 * marks, so a failure names its case and its leg, and a case printing too few or too many
 * lines leaves the other cases' output where it is. The second corpus,
 * {@code clojure-http-spec.yaml}, is the Clojure client ({@code rontolisp.http-client})
 * and {@code clojure.java.io}'s reads of an {@code http:} URL over the same origin and
 * legs, one {@code .clj} program per leg. The third, {@code java-http-spec.yaml}, is the
 * Java subset's {@code java.net.http} client: each of its programs a {@code .java} file
 * per leg, with the JDK running the test as one leg more, the oracle. The fourth,
 * {@code java-http-server-spec.yaml}, is the other direction, the Java subset's
 * {@code com.sun.net.httpserver}: a program per leg is started as a server -- on the
 * transports a served application has, the component under {@code wasmtime serve} -- and
 * the test asks each the same requests; its handlers reach the origin through the client.
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

	private static final String JAVA_SPEC_RESOURCE = "/java-http-spec.yaml";

	private static final String JAVA_SERVER_SPEC_RESOURCE = "/java-http-server-spec.yaml";

	/** Stands for the port a served program binds. */
	private static final String PORT_MARK = "@PORT@";

	/** Starts the lines of a served program's log. */
	private static final String LOG_LINE = "| ";

	/** What a request no response came back for is recorded as. */
	private static final String NO_RESPONSE = "(no response)";

	/**
	 * The readers {@code /seen?<key>} waits for, and for how many seconds: the test
	 * counts a key's latch down once it has read the first part of a served program's
	 * reply.
	 */
	private static final Map<String, CountDownLatch> SEEN = new ConcurrentHashMap<>();

	private static final Map<String, Integer> SEEN_SECONDS = new ConcurrentHashMap<>();

	/** How long {@code /seen} waits on a leg said to send a reply whole. */
	private static final int UNSEEN_SECONDS = 3;

	/** Starts the line a Java program prints ahead of each of its cases. */
	private static final String JAVA_CASE = "== ";

	/** The key of the leg a Java program runs under the JDK running the test. */
	private static final String JDK = "jdk";

	/**
	 * The request fields {@code /headers} leaves out: what the transports add on their
	 * own (the default User-Agent included, which differs by target), not what the
	 * program asked for.
	 */
	private static final List<String> UNSHOWN_FIELDS = List.of("host", "connection", "content-length", "http2-settings",
			"upgrade", "user-agent", "transfer-encoding");

	private static final String ORIGIN_MARK = "@ORIGIN@";

	/**
	 * Stands for the leg's name, which keeps what a case keys on the origin (a held
	 * reply's release) apart from the same case running on the other legs at once.
	 */
	private static final String LEG_MARK = "@LEG@";

	/**
	 * Stands for the leg's own directory, where a case may write a file: the component
	 * leg preopens it, the native runner preopens its working directory, which it is.
	 */
	private static final String SCRATCH_MARK = "@SCRATCH@";

	/**
	 * Starts the line a program prints after each case, the case's name following it:
	 * where the output is cut into cases.
	 */
	private static final String CASE_END = "%fetch-spec-case-end ";

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

	/**
	 * How long {@code /held} waits for its {@code /release} before it ends the reply
	 * saying none came: what a reader reading the whole reply before its first line costs
	 * a run.
	 */
	private static final int HELD_SECONDS = 30;

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

	/**
	 * One Java program: its file, what it prints under a JDK, and the legs it must not
	 * run on with why.
	 */
	record Program(String name, String file, String source, String expected, @Nullable Map<String, String> skip) {
	}

	record Programs(List<Program> programs) {
	}

	/**
	 * One request to a served program: what is sent, what of the response is shown, what
	 * a JDK answers, what a leg answers instead and the legs it is not sent on.
	 * {@code unanswered} is for how many seconds the JDK leg waits for an answer a JDK
	 * never gives. {@code meanwhile} is a request asked while this one is in hand: once
	 * {@code first} was read of its reply where a leg sends a chunk as it is written, so
	 * that its handler is waiting by then, and beside it elsewhere.
	 */
	record Asked(String name, String request, @Nullable Map<String, String> headers, @Nullable String body,
			@Nullable String hex, @Nullable List<String> show, @Nullable Integer times, @Nullable String first,
			@Nullable String meanwhile, @Nullable Integer unanswered, String expected,
			@Nullable Map<String, String> deviates, @Nullable Map<String, String> skip) {

		boolean sentOn(String leg) {
			return this.skip == null || !this.skip.containsKey(leg);
		}

		List<String> expectedOn(String leg) {
			String deviation = (this.deviates != null) ? this.deviates.get(leg) : null;
			return lines((deviation != null) ? deviation : this.expected);
		}

	}

	/**
	 * One served Java program: its file, its source (or the example under
	 * {@code examples/} it serves), the requests in turn and what it logs.
	 * {@code endless} marks a program no request stops, which is ended once asked;
	 * {@code listening} is the line saying its port (its one group); {@code unshown} the
	 * lines of a response body left out, what the client and the transports add on their
	 * own.
	 */
	record Served(String name, String file, @Nullable String source, @Nullable String example, @Nullable String log,
			@Nullable Boolean endless, @Nullable String listening, @Nullable List<String> unshown,
			List<Asked> requests) {

		String text() throws IOException {
			return (this.example != null) ? Files.readString(Path.of("examples", this.example), StandardCharsets.UTF_8)
					: Objects.requireNonNull(this.source);
		}

		boolean ends() {
			return !Boolean.TRUE.equals(this.endless);
		}

	}

	record ServedPrograms(List<Served> programs) {
	}

	/** What one leg of a served program gave: each response, its log, how it ended. */
	record Answers(Map<String, List<String>> responses, List<String> log, @Nullable Integer exit) {
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
		server.createContext("/framing", exchange -> {
			String upgrade = exchange.getRequestHeaders().getFirst("Upgrade");
			String length = exchange.getRequestHeaders().getFirst("Content-Length");
			answer(exchange, 200, exchange.getRequestMethod() + " upgrade=" + (upgrade == null ? "-" : upgrade)
					+ " content-length=" + (length == null ? "-" : length));
		});
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
		// Text in a charset the reply names, which is not UTF-8.
		server.createContext("/latin1", exchange -> {
			exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=ISO-8859-1");
			answer(exchange, 200, "héllo".getBytes(StandardCharsets.ISO_8859_1));
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
		// A redirect to another protocol, which clojure.java.io's read of a URL does not
		// follow (java.net.HttpURLConnection's rule).
		server.createContext("/redirect-https", exchange -> {
			exchange.getResponseHeaders()
				.add("Location", "https://127.0.0.1:" + server.getAddress().getPort() + "/hello");
			answer(exchange, 302, "moved");
		});
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
		// What a request body was, octet for octet: its content type, then each octet
		// as two hex digits.
		server.createContext("/octets-echo", exchange -> {
			StringBuilder hex = new StringBuilder();
			for (byte b : exchange.getRequestBody().readAllBytes()) {
				hex.append(String.format("%02x", b & 0xff));
			}
			answer(exchange, 200, exchange.getRequestHeaders().getFirst("Content-Type") + "|" + hex);
		});
		// A multipart body, one line of it a line of text: the client's boundary (when
		// it is the oracle's babashka_http_client_Boundary and a version 4 UUID) as
		// BOUNDARY, each octet that is no printable ASCII as <xx>.
		server.createContext("/multipart", exchange -> {
			String type = String.valueOf(exchange.getRequestHeaders().getFirst("Content-Type"));
			Matcher boundary = Pattern.compile(
					"babashka_http_client_Boundary[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
				.matcher(type);
			String found = boundary.find() ? boundary.group() : "\u0000";
			String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1);
			StringBuilder out = new StringBuilder(type.replace(found, "BOUNDARY")).append('\n');
			for (String line : body.replace(found, "BOUNDARY").split("\r\n", -1)) {
				for (char c : line.toCharArray()) {
					out.append((c < 0x20 || c > 0x7e) ? String.format("<%02x>", (int) c) : String.valueOf(c));
				}
				out.append('\n');
			}
			answer(exchange, 200, out.toString());
		});
		// A reply whose rest the origin holds until the client has read its first line:
		// /held?<key> writes "first line" and the second line once /release?<key> has
		// arrived, which the client sends after reading the first. A client that reads
		// the whole reply before its first line reads "never released" HELD_SECONDS
		// later instead.
		Map<String, CountDownLatch> releases = new ConcurrentHashMap<>();
		server.createContext("/held", exchange -> {
			CountDownLatch release = releases.computeIfAbsent(String.valueOf(exchange.getRequestURI().getRawQuery()),
					key -> new CountDownLatch(1));
			exchange.sendResponseHeaders(200, 0);
			try (OutputStream body = exchange.getResponseBody()) {
				body.write("first line\n".getBytes(StandardCharsets.UTF_8));
				body.flush();
				boolean released = release.await(HELD_SECONDS, TimeUnit.SECONDS);
				body.write((released ? "released\n" : "never released\n").getBytes(StandardCharsets.UTF_8));
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
		});
		server.createContext("/release", exchange -> {
			releases
				.computeIfAbsent(String.valueOf(exchange.getRequestURI().getRawQuery()), key -> new CountDownLatch(1))
				.countDown();
			answer(exchange, 200, "released");
		});
		// UTF-8 as a reader decodes it while the reply arrives: a sequence cut across two
		// writes, then malformed octets -- a lone lead, a truncated sequence, an encoded
		// surrogate -- and the reply ending inside a sequence.
		server.createContext("/split", exchange -> {
			exchange.sendResponseHeaders(200, 0);
			try (OutputStream body = exchange.getResponseBody()) {
				body.write(new byte[] { 'a', (byte) 0xf0, (byte) 0x9f });
				body.flush();
				Thread.sleep(100);
				body.write(new byte[] { (byte) 0x98, (byte) 0x80, '\n', 'h', (byte) 0xe9, 'l', 'l', 'o', '\n',
						(byte) 0xe4, (byte) 0xb8, 'A', '\n', (byte) 0xed, (byte) 0xa0, (byte) 0x80, '\n', (byte) 0xf0,
						(byte) 0x9f });
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
		});
		// /seen?<key> answers whether the test has read the first part of the reply a
		// served program is writing: it waits for that reader, as long as the key's leg
		// is given.
		server.createContext("/seen", exchange -> {
			String key = String.valueOf(exchange.getRequestURI().getRawQuery());
			boolean seen = false;
			try {
				seen = SEEN.computeIfAbsent(key, made -> new CountDownLatch(1))
					.await(SEEN_SECONDS.getOrDefault(key, HELD_SECONDS), TimeUnit.SECONDS);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
			answer(exchange, 200, seen ? "seen" : "unseen");
		});
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
	 * The Clojure client, {@code rontolisp.http-client}, and {@code clojure.java.io}'s
	 * reads of an {@code http:} URL over the same origin and legs
	 * ({@code clojure-http-spec.yaml}): every request either makes goes through
	 * {@code rontolisp:fetch}, so each transport is one leg here too.
	 */
	@TestFactory
	Stream<DynamicNode> clojureHttpClient() throws Exception {
		return corpus(CLOJURE_SPEC_RESOURCE, "http-spec.clj");
	}

	/**
	 * The Java subset's {@code java.net.http} client over the same origin and legs
	 * ({@code java-http-spec.yaml}): every request it makes is the program's own
	 * {@code rontolisp:fetch} call, and the JDK running the test is the oracle, a leg of
	 * its own.
	 */
	@TestFactory
	Stream<DynamicNode> javaHttpClient() throws Exception {
		Programs spec = load(JAVA_SPEC_RESOURCE, Programs.class);
		String originUrl = "http://127.0.0.1:" + Objects.requireNonNull(origin).getAddress().getPort();
		List<String> legs = new ArrayList<>(List.of(JDK));
		for (Leg leg : Leg.values()) {
			legs.add(leg.key());
		}
		List<DynamicNode> programs = new ArrayList<>();
		// One program's legs run at once, the programs one after the other.
		for (Program program : spec.programs()) {
			Map<String, Callable<String>> runs = new java.util.LinkedHashMap<>();
			for (String leg : legs) {
				if (program.skip() == null || !program.skip().containsKey(leg)) {
					String source = program.source().replace(ORIGIN_MARK, originUrl).replace(LEG_MARK, leg);
					runs.put(leg, started(program.name() + "-" + leg, () -> runJava(leg, source, program.file())));
				}
			}
			List<DynamicNode> ran = new ArrayList<>();
			runs.forEach((leg, run) -> ran.add(javaLeg(leg, program, run)));
			programs.add(dynamicContainer(program.name(), ran.stream()));
		}
		return programs.stream();
	}

	private static String runJava(String leg, String source, String fileName) throws Exception {
		if (!leg.equals(JDK)) {
			return run(Leg.valueOf(leg.toUpperCase(Locale.ROOT)), source, fileName);
		}
		String stem = fileName.substring(0, fileName.lastIndexOf('.'));
		Path dir = Files.createDirectories(workDir.resolve(stem + "-" + leg));
		Path file = dir.resolve(fileName);
		Files.writeString(file, source, StandardCharsets.UTF_8);
		return exec(dir, List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), file.toString()));
	}

	// What a Java program printed, cut at the lines naming its cases: each case's lines
	// by its name, what came ahead of the first under the empty name.
	private static Map<String, List<String>> javaCases(String output) {
		Map<String, List<String>> cases = new java.util.LinkedHashMap<>();
		List<String> current = new ArrayList<>();
		String name = "";
		for (String line : lines(output)) {
			if (line.startsWith(JAVA_CASE)) {
				cases.put(name, current);
				name = line.substring(JAVA_CASE.length());
				current = new ArrayList<>();
			}
			else {
				current.add(line);
			}
		}
		cases.put(name, current);
		return cases;
	}

	private static DynamicContainer javaLeg(String leg, Program program, Callable<String> run) {
		Map<String, List<String>> printed;
		try {
			printed = javaCases(run.call());
		}
		catch (Skipped skipped) {
			return dynamicContainer(leg,
					Stream.of(dynamicTest("(skipped)", () -> abort(Objects.requireNonNull(skipped.getMessage())))));
		}
		catch (Exception | StackOverflowError ex) {
			return dynamicContainer(leg, Stream
				.of(dynamicTest("(execution failed)", () -> fail(program.name() + " on " + leg + ": " + ex, ex))));
		}
		Map<String, List<String>> expected = javaCases(program.expected());
		List<DynamicTest> tests = new ArrayList<>();
		expected.forEach((name,
				lines) -> tests.add(dynamicTest(name.isEmpty() ? "before-the-first-case" : name,
						() -> assertThat(printed.get(name)).as("case '%s' of %s on %s", name, program.name(), leg)
							.isEqualTo(lines))));
		tests.add(dynamicTest("the-cases-it-printed",
				() -> assertThat(printed.keySet()).as("the cases %s printed on %s", program.name(), leg)
					.containsExactlyElementsOf(expected.keySet())));
		return dynamicContainer(leg, tests.stream());
	}

	/**
	 * The Java subset's {@code com.sun.net.httpserver}
	 * ({@code java-http-server-spec.yaml}): a program is started as a server under the
	 * JDK running the test -- the oracle -- and on each transport, and asked the same
	 * requests in turn.
	 */
	@TestFactory
	Stream<DynamicNode> javaHttpServer() throws Exception {
		ServedPrograms spec = load(JAVA_SERVER_SPEC_RESOURCE, ServedPrograms.class);
		String originUrl = "http://127.0.0.1:" + Objects.requireNonNull(origin).getAddress().getPort();
		List<String> legs = new ArrayList<>(List.of(JDK));
		for (Leg leg : Leg.values()) {
			legs.add(leg.key());
		}
		List<DynamicNode> programs = new ArrayList<>();
		for (Served program : spec.programs()) {
			Map<String, Callable<Answers>> runs = new java.util.LinkedHashMap<>();
			for (String leg : legs) {
				runs.put(leg, started(program.name() + "-served-" + leg, () -> serve(leg, program, originUrl)));
			}
			List<DynamicNode> ran = new ArrayList<>();
			runs.forEach((leg, run) -> ran.add(servedLeg(leg, program, run)));
			programs.add(dynamicContainer(program.name(), ran.stream()));
		}
		return programs.stream();
	}

	private static DynamicContainer servedLeg(String leg, Served program, Callable<Answers> run) {
		Answers answers;
		try {
			answers = run.call();
		}
		catch (Skipped skipped) {
			return dynamicContainer(leg,
					Stream.of(dynamicTest("(skipped)", () -> abort(Objects.requireNonNull(skipped.getMessage())))));
		}
		catch (Exception | StackOverflowError ex) {
			return dynamicContainer(leg, Stream
				.of(dynamicTest("(execution failed)", () -> fail(program.name() + " on " + leg + ": " + ex, ex))));
		}
		List<DynamicTest> tests = new ArrayList<>();
		for (Asked asked : program.requests()) {
			if (asked.sentOn(leg)) {
				tests.add(dynamicTest(asked.name(),
						() -> assertThat(answers.responses().get(asked.name()))
							.as("'%s' (%s) of %s on %s", asked.name(), asked.request(), program.name(), leg)
							.isEqualTo(asked.expectedOn(leg))));
			}
		}
		// the host starts a component as often as it likes, and never ends it
		String log = program.log();
		if (!leg.equals(Leg.COMPONENT.key()) && log != null) {
			tests.add(dynamicTest("the-lines-it-logged",
					() -> assertThat(answers.log()).as("the log of %s on %s", program.name(), leg)
						.isEqualTo(lines(log))));
		}
		if (!leg.equals(Leg.COMPONENT.key()) && program.ends()) {
			tests.add(dynamicTest("it-ends-once-its-server-stopped",
					() -> assertThat(answers.exit()).as("the status %s ended with on %s", program.name(), leg)
						.isEqualTo(0)));
		}
		return dynamicContainer(leg, tests.stream());
	}

	// Starts a program as a server on one leg, asks it the requests in turn and ends
	// it: the last request stops a program that can stop; the host's is destroyed. A
	// file in a package is under its directories.
	private static Answers serve(String leg, Served program, String originUrl) throws Exception {
		String name = Path.of(program.file()).getFileName().toString();
		String stem = name.substring(0, name.lastIndexOf('.'));
		Path dir = Files.createDirectories(workDir.resolve(stem + "-served-" + leg));
		boolean component = leg.equals(Leg.COMPONENT.key());
		boolean nativeLeg = leg.equals(Leg.NATIVE.key());
		// every leg that binds a port tells the one it got
		int reserved = 0;
		Path source = dir.resolve(program.file());
		Files.createDirectories(source.getParent());
		Files.writeString(source,
				program.text()
					.replace(ORIGIN_MARK, originUrl)
					.replace(LEG_MARK, leg)
					.replace(PORT_MARK, Integer.toString(reserved)),
				StandardCharsets.UTF_8);
		String launcher = Path.of(System.getProperty("java.home"), "bin", "java").toString();
		List<String> command;
		if (leg.equals(JDK)) {
			command = List.of(launcher, source.toString());
		}
		else if (leg.equals(Leg.INTERPRETER.key())) {
			command = (BINARY != null) ? List.of(Path.of(BINARY).toAbsolutePath().toString(), source.toString())
					: List.of(launcher, "-cp", System.getProperty("java.class.path"),
							"am.ik.rontolisp.cli.RontoLispCli", source.toString());
		}
		else if (leg.equals(Leg.JVM.key())) {
			compile(source, dir.resolve("Served.class"), "--class-name", "Served");
			command = List.of(launcher, "-cp", dir.toString(), "Served");
		}
		else if (nativeLeg) {
			Path executable = executableOf(dir, stem);
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
			command = List.of(executable.toString());
		}
		else {
			if (!HostWasmtime.isAvailable()) {
				throw new Skipped("no usable wasmtime on PATH");
			}
			Path served = dir.resolve(stem + ".wasm");
			compile(source, served, "--component");
			command = List.of("wasmtime", "serve", "-W", "gc=y", "-W", "exceptions=y", "-S", "cli=y", "--addr",
					"127.0.0.1:0", served.toString());
		}
		Path stdout = dir.resolve("served.out");
		Path stderr = dir.resolve("served.err");
		ProcessBuilder builder = new ProcessBuilder(command).directory(dir.toFile())
			.redirectInput(ProcessBuilder.Redirect.from(Path.of("/dev/null").toFile()))
			.redirectOutput(stdout.toFile())
			.redirectError(stderr.toFile());
		// an example's port is its environment's
		builder.environment().put("PORT", Integer.toString(reserved));
		Process process = start(builder);
		try {
			int port = servedPort(process, stdout, stderr,
					component ? Pattern.compile("Serving HTTP on http://127\\.0\\.0\\.1:(\\d+)/") : Pattern.compile(
							Objects.requireNonNullElse(program.listening(), "^listening (\\d+)$"), Pattern.MULTILINE));
			Map<String, List<String>> responses = new java.util.LinkedHashMap<>();
			try (java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
				.version(java.net.http.HttpClient.Version.HTTP_1_1)
				.build()) {
				for (Asked asked : program.requests()) {
					if (asked.sentOn(leg)) {
						responses.put(asked.name(), shownOf(program, ask(client, port, asked, leg)));
					}
				}
			}
			Integer exit = null;
			if (!component && program.ends() && process.waitFor(60, TimeUnit.SECONDS)) {
				exit = process.exitValue();
			}
			List<String> log = new ArrayList<>();
			for (String line : lines(Files.readString(stdout, StandardCharsets.UTF_8))) {
				if (line.startsWith(LOG_LINE)) {
					log.add(line);
				}
			}
			return new Answers(responses, log, exit);
		}
		finally {
			process.destroyForcibly().waitFor();
		}
	}

	// A response without the lines the program leaves unshown.
	private static List<String> shownOf(Served program, List<String> response) {
		List<String> unshown = program.unshown();
		if (unshown == null) {
			return response;
		}
		List<Pattern> patterns = unshown.stream().map(Pattern::compile).toList();
		return response.stream().filter(line -> patterns.stream().noneMatch(p -> p.matcher(line).find())).toList();
	}

	// The port a served program says it listens on: the line is waited for while the
	// program runs.
	private static int servedPort(Process process, Path stdout, Path stderr, Pattern line) throws Exception {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
		while (System.nanoTime() < deadline) {
			// wasmtime serve says it on its standard error
			Matcher said = line.matcher(Files.readString(stdout, StandardCharsets.UTF_8)
					+ Files.readString(stderr, StandardCharsets.UTF_8));
			if (said.find()) {
				return Integer.parseInt(said.group(1));
			}
			if (!process.isAlive()) {
				break;
			}
			Thread.sleep(50);
		}
		throw new IllegalStateException("the served program never said where it listens\n--- stdout ---\n"
				+ Files.readString(stdout) + "--- stderr ---\n" + Files.readString(stderr));
	}

	// One request, or the same one several times at once, and what came back: the
	// status, the fields the request shows, the body's lines (its octets in hex where
	// the request's are).
	private static List<String> ask(java.net.http.HttpClient client, int port, Asked asked, String leg)
			throws Exception {
		int space = asked.request().indexOf(' ');
		byte[] body = (asked.hex() != null) ? java.util.HexFormat.of().parseHex(asked.hex())
				: (asked.body() != null) ? asked.body().getBytes(StandardCharsets.UTF_8) : null;
		java.net.http.HttpRequest.Builder builder = java.net.http.HttpRequest
			.newBuilder(java.net.URI.create("http://127.0.0.1:" + port + asked.request().substring(space + 1)))
			.timeout(java.time.Duration
				.ofSeconds((asked.unanswered() != null && leg.equals(JDK)) ? asked.unanswered() : 120))
			.method(asked.request().substring(0, space),
					(body == null) ? java.net.http.HttpRequest.BodyPublishers.noBody()
							: java.net.http.HttpRequest.BodyPublishers.ofByteArray(body));
		if (asked.headers() != null) {
			asked.headers().forEach(builder::header);
		}
		java.net.http.HttpRequest request = builder.build();
		String first = asked.first();
		String meanwhile = asked.meanwhile();
		if (meanwhile != null) {
			// a JDK and the component send a chunk as it is written
			boolean streamed = leg.equals(JDK) || leg.equals(Leg.COMPONENT.key());
			int told = streamed ? Objects.requireNonNull(first).getBytes(StandardCharsets.UTF_8).length : 0;
			CountDownLatch waiting = new CountDownLatch(1);
			Callable<List<String>> held = started("held-" + leg, () -> answered(() -> {
				try {
					java.net.http.HttpResponse<InputStream> response = client.send(request,
							java.net.http.HttpResponse.BodyHandlers.ofInputStream());
					try (InputStream in = response.body()) {
						ByteArrayOutputStream whole = new ByteArrayOutputStream();
						whole.write(in.readNBytes(told));
						waiting.countDown();
						in.transferTo(whole);
						return shown(asked, response.statusCode(), response.headers(), whole.toByteArray());
					}
				}
				finally {
					waiting.countDown();
				}
			}));
			if (streamed) {
				waiting.await();
			}
			int method = meanwhile.indexOf(' ');
			client.send(java.net.http.HttpRequest
				.newBuilder(java.net.URI.create("http://127.0.0.1:" + port + meanwhile.substring(method + 1)))
				.timeout(java.time.Duration.ofSeconds(120))
				.method(meanwhile.substring(0, method), java.net.http.HttpRequest.BodyPublishers.noBody())
				.build(), java.net.http.HttpResponse.BodyHandlers.discarding());
			return held.call();
		}
		if (first != null) {
			String key = "stream-" + leg;
			if (asked.deviates() != null && asked.deviates().containsKey(leg)) {
				SEEN_SECONDS.put(key, UNSEEN_SECONDS);
			}
			return answered(() -> {
				java.net.http.HttpResponse<InputStream> response = client.send(request,
						java.net.http.HttpResponse.BodyHandlers.ofInputStream());
				try (InputStream in = response.body()) {
					byte[] head = in.readNBytes(first.getBytes(StandardCharsets.UTF_8).length);
					SEEN.computeIfAbsent(key, made -> new CountDownLatch(1)).countDown();
					ByteArrayOutputStream whole = new ByteArrayOutputStream();
					whole.write(head);
					in.transferTo(whole);
					return shown(asked, response.statusCode(), response.headers(), whole.toByteArray());
				}
			});
		}
		int times = (asked.times() != null) ? asked.times() : 1;
		List<Callable<List<String>>> sent = new ArrayList<>();
		for (int i = 0; i < times; i++) {
			sent.add(started("ask-" + leg + "-" + i, () -> answered(() -> {
				java.net.http.HttpResponse<byte[]> response = client.send(request,
						java.net.http.HttpResponse.BodyHandlers.ofByteArray());
				return shown(asked, response.statusCode(), response.headers(), response.body());
			})));
		}
		// several at once answer as one when every answer is the same
		java.util.LinkedHashSet<List<String>> distinct = new java.util.LinkedHashSet<>();
		for (Callable<List<String>> one : sent) {
			distinct.add(one.call());
		}
		List<String> all = new ArrayList<>();
		distinct.forEach(all::addAll);
		return all;
	}

	private static List<String> answered(Callable<List<String>> exchange) throws Exception {
		try {
			return exchange.call();
		}
		catch (IOException ex) {
			return List.of(NO_RESPONSE);
		}
	}

	private static List<String> shown(Asked asked, int status, java.net.http.HttpHeaders headers, byte[] body) {
		List<String> shown = new ArrayList<>();
		shown.add(Integer.toString(status));
		if (asked.show() != null) {
			for (String name : asked.show()) {
				for (String value : headers.allValues(name)) {
					shown.add(name + ": " + value);
				}
			}
		}
		if (body.length > 0) {
			shown.addAll((asked.hex() != null) ? List.of(java.util.HexFormat.of().formatHex(body))
					: lines(new String(body, StandardCharsets.UTF_8)));
		}
		return shown;
	}

	private static Stream<DynamicNode> corpus(String resource, String fileName) throws Exception {
		Spec spec = loadSpec(resource);
		String originUrl = "http://127.0.0.1:" + Objects.requireNonNull(origin).getAddress().getPort();
		// The four runs share nothing but the origin, which serves each request on a
		// thread of its own, so they run at once; the slices are built once all four
		// have answered.
		List<Callable<String>> runs = new ArrayList<>();
		for (Leg leg : Leg.values()) {
			String program = program(spec.on(leg), originUrl, leg, fileName.endsWith(".clj"));
			runs.add(started(leg.key(), () -> run(leg, program, fileName)));
		}
		List<DynamicNode> legs = new ArrayList<>();
		for (Leg leg : Leg.values()) {
			legs.add(leg(leg, spec.on(leg), runs.get(leg.ordinal())));
		}
		return legs.stream();
	}

	// The cases in order, each followed by the form printing its end mark. The mark goes
	// after its case, not before: a Clojure program's ns form comes first.
	private static String program(List<Case> cases, String originUrl, Leg leg, boolean clojure) {
		StringBuilder program = new StringBuilder();
		for (Case c : cases) {
			program.append(c.source().replace(ORIGIN_MARK, originUrl).replace(LEG_MARK, leg.key()))
				.append(clojure ? "(println \"" : "(write-line \"")
				.append(CASE_END)
				.append(c.name())
				.append("\")\n");
		}
		return program.toString();
	}

	/**
	 * What a program printed for one case, and the name its end mark carried: null for
	 * what it printed after its last mark.
	 */
	record Slice(@Nullable String name, List<String> lines) {

	}

	// The output cut at each end mark. A case whose last line has no line end leaves that
	// line on the mark's, before the mark.
	private static List<Slice> slices(String output) {
		List<Slice> slices = new ArrayList<>();
		List<String> current = new ArrayList<>();
		for (String line : lines(output)) {
			int mark = line.indexOf(CASE_END);
			if (mark < 0) {
				current.add(line);
				continue;
			}
			if (mark > 0) {
				current.add(line.substring(0, mark));
			}
			slices.add(new Slice(line.substring(mark + CASE_END.length()), current));
			current = new ArrayList<>();
		}
		slices.add(new Slice(null, current));
		return slices;
	}

	private static DynamicContainer leg(Leg leg, List<Case> cases, Callable<String> run) {
		List<Slice> slices;
		try {
			slices = slices(run.call());
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
		for (int i = 0; i < cases.size(); i++) {
			Case c = cases.get(i);
			// Past the slice after the last mark, a case the program never started.
			Slice slice = (i < slices.size()) ? slices.get(i) : new Slice(null, List.of());
			tests.add(dynamicTest(c.name(), () -> {
				assertThat(slice.name())
					.as("the end mark of case '%s' on %s (the program stopped first, having printed %s)", c.name(), leg,
							slice.lines())
					.isEqualTo(c.name());
				assertThat(slice.lines())
					.as("case '%s' on %s%n--- source ---%n%s--- end source ---", c.name(), leg, c.source())
					.containsExactlyElementsOf(c.expectedLines());
			}));
		}
		List<String> after = (slices.size() > cases.size()) ? slices.get(cases.size()).lines() : List.of();
		tests.add(dynamicTest("after-the-last-case",
				() -> assertThat(after).as("what %s printed after its last case", leg).isEmpty()));
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
				Path executable = executableOf(dir, stem);
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

	// A program's executable, apart from the tree its source's package directories make:
	// a case-insensitive file system (macOS) reads the executable "Httpbin" and the
	// directory "httpbin" as one name.
	private static Path executableOf(Path dir, String stem) throws IOException {
		return Files.createDirectories(dir.resolve("out")).resolve(stem);
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

	// Starts a process, again while its executable is busy: the legs write and run
	// outputs at once, and a fork on another thread holds a just-written output open
	// until that child's exec (ETXTBSY, .kb/native-output.md).
	private static Process start(ProcessBuilder builder) throws Exception {
		for (int attempt = 1;; attempt++) {
			try {
				return builder.start();
			}
			catch (IOException ex) {
				if (attempt == 50 || !String.valueOf(ex.getMessage()).contains("error: 26")) {
					throw ex;
				}
				Thread.sleep(100);
			}
		}
	}

	private static String exec(Path dir, List<String> command) throws Exception {
		Path stdout = dir.resolve("run.out");
		Path stderr = dir.resolve("run.err");
		Process process = start(new ProcessBuilder(command).directory(dir.toFile())
			.redirectInput(ProcessBuilder.Redirect.from(Path.of("/dev/null").toFile()))
			.redirectOutput(stdout.toFile())
			.redirectError(stderr.toFile()));
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
	private static <T> Callable<T> started(String leg, Callable<T> run) {
		FutureTask<T> task = new FutureTask<>(run);
		Thread.ofPlatform().name("fetch-spec-" + leg).start(task);
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
		return load(resource, Spec.class);
	}

	private static <T> T load(String resource, Class<T> type) throws IOException {
		try (InputStream in = FetchSpecE2eTest.class.getResourceAsStream(resource)) {
			assertThat(in).as("test resource %s", resource).isNotNull();
			return YAMLMapper.builder()
				.build()
				.readValue(YamlResources.safeReader(new String(in.readAllBytes(), StandardCharsets.UTF_8)), type);
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
