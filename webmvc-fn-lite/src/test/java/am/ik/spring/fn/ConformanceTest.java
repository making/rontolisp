package am.ik.spring.fn;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One application, the sources under {@code src/test/resources/conformance}, compiled
 * twice -- against Spring Framework with Jackson, and against this module -- and run on
 * embedded Tomcat in a JVM each, since the two have their classes by the same names. The
 * same requests are asked of both: this module answers what Spring Framework answers.
 * Where it does not, by decision, the row is in {@link #DIFFERENT} with both answers.
 */
class ConformanceTest {

	/** The fields of a response that are compared; the others are the container's. */
	private static final List<String> SHOWN = List.of("content-type", "content-length", "location", "allow", "vary",
			"etag", "accept", "authorization", "x-one", "x-two", "x-from", "x-written", "x-wrapped", "x-head");

	/**
	 * Where this module answers differently, by decision: the request, then what Spring
	 * Framework answers and what this module answers. Spring Framework 7.1 refuses a path
	 * nested under a servlet mapped to a path prefix ("Could not change context path to
	 * '/sub/ctx/api': a context path is already specified").
	 */
	private static final Map<String, List<String>> DIFFERENT = Map.of("GET /ctx/sub/api/items/3",
			List.of("500", "200\ncontent-length: 11\ncontent-type: text/plain;charset=ISO-8859-1\nitem {id=3}"));

	record Asked(String method, String path, Map<String, String> fields, String body) {

		static Asked get(String path, String... fields) {
			return new Asked("GET", path, fieldsOf(fields), null);
		}

		static Asked of(String method, String path, String body, String... fields) {
			return new Asked(method, path, fieldsOf(fields), body);
		}

		private static Map<String, String> fieldsOf(String... fields) {
			Map<String, String> all = new LinkedHashMap<>();
			for (int i = 0; i < fields.length; i += 2) {
				all.put(fields[i], fields[i + 1]);
			}
			return all;
		}

		String name() {
			return this.method + " " + this.path + (this.fields.isEmpty() ? "" : " " + this.fields)
					+ ((this.body != null) ? " <" + this.body + ">" : "");
		}

	}

	private static final String JSON = "application/json";

	private static List<Asked> requests() {
		List<Asked> asked = new ArrayList<>();
		for (String path : List.of("/", "/text/latin", "/text/utf8", "/text/html", "/text/json", "/bytes", "/bytes/png",
				"/json/map", "/json/list", "/json/problem", "/json/as-text", "/status/200", "/status/418",
				"/status/299", "/status/503", "/created", "/accepted", "/no-content", "/see-other", "/temporary",
				"/permanent", "/bad-request", "/not-found", "/unprocessable", "/fields", "/etag", "/from", "/written",
				"/response", "/request?q=a%20b&q=c&z=%E3%81%82", "/request/7/caf%C3%A9?x=1", "/request/7/a%2Fb",
				"/request/7/a;v=1", "/request/7", "/request/7/", "/files", "/files/", "/files/a/b%20c.txt", "/filesx",
				"/tree", "/tree/", "/tree/a/b", "/any/x/end", "/any//end", "/any/x", "/any/", "/any", "/any/x/y",
				"/one/abc", "/one/ac", "/one/abbc", "/mixed/file-report.final.txt", "/mixed/file-.txt", "/mixed/file-x",
				"/glob/az", "/glob/a-to-z", "/glob/a-to-y", "/slash/", "/slash", "/Case", "/case", "/no-slash",
				"/var/1/2", "/var/1", "/var/1/2/3", "/var//2", "/accepts", "/param?mode=a", "/param?mode=bee",
				"/param?mode=c", "/param", "/either", "/or/left", "/or/other", "/negated/x", "/headers",
				"/variable/yes", "/variable/no", "/head", "/put/1", "/options", "/api", "/api/", "/api/items/3",
				"/api/items", "/api/v/2", "/api/v/2/9", "/api/v", "/api/nested", "/apix", "/guarded/in", "/guarded/out",
				"/added", "/added/too", "/added/nest/in", "/added/nest", "/slashed", "/slashed/", "/slashed/in",
				"/slashed//in", "/starred/x", "/starred/x/", "/starred/x/in", "/starred/", "/starred//in", "/starred",
				"/filtered", "/filtered?after=wrap", "/throws/state", "/throws/argument", "/throws/status",
				"/throws/status/bare", "/throws/status/wrapped", "/throws/checked", "/unwritable",
				"/unwritable/handled", "/null", "/nowhere", "/ctx/sub/", "/ctx/sub/request?q=1", "/ctx/sub/api/items/3",
				"/ctx/sub/json/list", "/ctx/sub", "/ctx/other", "/own")) {
			asked.add(Asked.get(path));
		}
		asked
			.add(Asked.get("/request", "X-Many", "one, two", "Accept", "text/html, application/json;q=0.9, */*;q=0.1"));
		asked.add(Asked.get("/accepts", "Accept", JSON));
		asked.add(Asked.get("/accepts", "Accept", "text/*"));
		asked.add(Asked.get("/accepts", "Accept", "image/png"));
		asked.add(Asked.get("/accepts", "Accept", "application/*+json"));
		asked.add(Asked.get("/headers", "X-Key", "k"));
		asked.add(Asked.get("/api/nested", "Accept", JSON));
		asked.add(Asked.get("/api/nested", "Accept", "text/plain"));
		asked.add(Asked.get("/guarded/in", "Authorization", "Bearer x"));
		asked.add(Asked.get("/etag", "If-None-Match", "\"v1\""));
		asked.add(Asked.get("/etag", "If-None-Match", "W/\"v1\""));
		asked.add(Asked.get("/etag", "If-None-Match", "\"v0\", \"v1\""));
		asked.add(Asked.get("/etag", "If-None-Match", "\"v2\""));
		asked.add(Asked.get("/etag", "If-None-Match", "*"));
		asked.add(Asked.of("HEAD", "/", null));
		asked.add(Asked.of("OPTIONS", "/", null));
		asked.add(Asked.of("OPTIONS", "/options", null));
		asked.add(Asked.of("HEAD", "/head", null));
		asked.add(Asked.of("HEAD", "/etag", null, "If-None-Match", "\"v1\""));
		asked.add(Asked.of("POST", "/", "x"));
		asked.add(Asked.of("POST", "/echo/text", "caf\u00e9 \u20ac", "Content-Type", "text/plain;charset=UTF-8"));
		asked.add(Asked.of("POST", "/echo/text", "caf\u00e9", "Content-Type", "text/plain"));
		asked.add(Asked.of("POST", "/echo/text", "{\"caf\u00e9\":1}", "Content-Type", JSON));
		asked.add(Asked.of("POST", "/echo/text", "untyped"));
		asked.add(Asked.of("POST", "/echo/text", ""));
		asked.add(Asked.of("POST", "/echo/bytes", "raw", "Content-Type", "application/octet-stream"));
		asked.add(Asked.of("POST", "/echo/bytes", "raw", "Content-Type", "image/png"));
		for (String document : List.of(
				"{\"s\":\"caf\u00e9 \\u00e9\\n\\\"\\/\\ud83d\\ude00\",\"i\":1,\"l\":12345678901,\"n\":-0,\"d\":1.50,"
						+ "\"e\":1e3,\"t\":true,\"f\":false,\"z\":null,\"o\":{\"b\":[]},\"a\":[1,[2],{}],\"i\":2,"
						+ "\"max\":2147483647,\"past\":2147483648,\"min\":-2147483648,\"big\":9223372036854775807}",
				" { } ", "{", "{\"a\":1,}", "{\"a\":01}", "{'a':1}", "{\"a\":1} x", "[1]", "\"text\"", "nul", "",
				"{\"a\":\"\t\"}", "{\"a\":1.}", "{\"a\":.5}", "{\"a\":+1}", "{\"a\":\"\\x\"}")) {
			asked.add(Asked.of("POST", "/echo/json", document, "Content-Type", JSON));
		}
		asked.add(Asked.of("POST", "/echo/json", "{\"a\":1}", "Content-Type", "application/json;charset=UTF-8"));
		asked.add(Asked.of("POST", "/echo/json", "{\"a\":1}", "Content-Type", "application/vnd.x+json"));
		asked.add(Asked.of("POST", "/echo/json", "{\"a\":1}", "Content-Type", "text/plain"));
		asked.add(Asked.of("POST", "/echo/json", "{\"a\":1}"));
		asked.add(Asked.of("POST", "/echo/list", "[1,\"a\",null,2.5,{\"k\":[true]}]", "Content-Type", JSON));
		asked.add(Asked.of("POST", "/echo/list", "{}", "Content-Type", JSON));
		asked.add(Asked.of("POST", "/echo/list", "[1]", "Content-Type", "text/csv"));
		asked.add(Asked.of("POST", "/echo/list", "[1]"));
		asked.add(Asked.of("POST", "/echo/any", "[1,{\"a\":null}]", "Content-Type", JSON));
		// a converter of the application's own
		asked.add(Asked.of("POST", "/own", "3, 4", "Content-Type", "text/x-point"));
		asked.add(Asked.of("POST", "/own", "3", "Content-Type", "text/x-point"));
		asked.add(Asked.of("POST", "/own", "3,4", "Content-Type", "text/plain"));
		asked.add(Asked.of("POST", "/own", "3,4"));
		asked.add(Asked.of("POST", "/echo/any", "12", "Content-Type", JSON));
		asked.add(Asked.of("POST", "/echo/any", "x", "Content-Type", "application/x-unknown"));
		asked.add(Asked.of("POST", "/form?b=query", "a=1&b=two+words&a=%C3%A9", "Content-Type",
				"application/x-www-form-urlencoded"));
		asked.add(Asked.of("POST", "/typed", "x", "Content-Type", JSON));
		asked.add(Asked.of("POST", "/typed", "x", "Content-Type", "text/plain;charset=UTF-8"));
		asked.add(Asked.of("POST", "/typed", "x", "Content-Type", "text/html"));
		asked.add(Asked.of("POST", "/typed", "x"));
		asked.add(Asked.of("PUT", "/either", "x"));
		asked.add(Asked.of("PATCH", "/either", "x"));
		asked.add(Asked.of("POST", "/either", "x"));
		asked.add(Asked.of("POST", "/negated/x/y", "x"));
		asked.add(Asked.of("PUT", "/put/9", "x"));
		asked.add(Asked.of("PATCH", "/patch", "x"));
		asked.add(Asked.of("DELETE", "/delete/9", null));
		asked.add(Asked.of("DELETE", "/put/9", null));
		asked.add(Asked.of("POST", "/api/v/2/9", "x"));
		asked.add(Asked.of("POST", "/api/v/2", "x"));
		return asked;
	}

	@Test
	void theApplicationAnswersWhatItAnswersOnSpringFramework(@TempDir Path dir) throws Exception {
		List<String> classPath = List
			.of(System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"))
				.split(File.pathSeparator));
		List<String> spring = classPath.stream().filter(entry -> !entry.endsWith("classes")).toList();
		List<String> module = classPath.stream()
			.filter(entry -> !Path.of(entry)
				.getFileName()
				.toString()
				.matches("(spring|jackson|micrometer|commons-logging)-.*"))
			.toList();
		assertThat(spring).as("the class path of Spring Framework").hasSizeLessThan(classPath.size());
		assertThat(module).as("the class path of this module").hasSizeLessThan(classPath.size());
		List<Asked> requests = requests();
		Map<String, String> expected = run(dir.resolve("spring"), spring, requests);
		Map<String, String> answered = run(dir.resolve("module"), module, requests);
		// the oracle is what it is thought to be
		assertThat(expected.get("GET /"))
			.isEqualTo("200\ncontent-length: 12\ncontent-type: text/plain;charset=ISO-8859-1\nHello World!");
		Map<String, List<String>> different = new LinkedHashMap<>();
		expected.forEach((request, answer) -> {
			if (!answer.equals(answered.get(request))) {
				different.put(request, List.of(answer, String.valueOf(answered.get(request))));
			}
		});
		assertThat(different).as("what this module answers differently from Spring Framework").isEqualTo(DIFFERENT);
	}

	/**
	 * Compiles the application against a class path, runs it and asks it the requests.
	 */
	private static Map<String, String> run(Path dir, List<String> classPath, List<Asked> requests) throws Exception {
		Path sources = Path.of(ConformanceTest.class.getClassLoader().getResource("conformance").toURI());
		Path classes = Files.createDirectories(dir.resolve("classes"));
		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		try (StandardJavaFileManager files = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8);
				Stream<Path> java = Files.list(sources)) {
			assertThat(compiler.getTask(null, files, null,
					List.of("-d", classes.toString(), "-cp", String.join(File.pathSeparator, classPath)), null,
					files.getJavaFileObjectsFromPaths(java.filter(file -> file.toString().endsWith(".java")).toList()))
				.call()).as("javac against %s", dir.getFileName()).isTrue();
		}
		Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp",
				classes + File.pathSeparator + String.join(File.pathSeparator, classPath), "conformance.Main",
				Files.createDirectories(dir.resolve("tomcat")).toString())
			.redirectError(dir.resolve("err.log").toFile())
			.start();
		try (BufferedReader out = process.inputReader()) {
			HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
			String line = out.readLine();
			while (line != null && !line.startsWith("listening ")) {
				line = out.readLine();
			}
			assertThat(line).as("the application on %s started: %s", dir.getFileName(), dir.resolve("err.log"))
				.isNotNull();
			int port = Integer.parseInt(line.substring("listening ".length()));
			Map<String, String> answers = new LinkedHashMap<>();
			for (Asked asked : requests) {
				answers.put(asked.name(), ask(client, port, asked));
			}
			return answers;
		}
		finally {
			process.destroy();
			if (!process.waitFor(10, TimeUnit.SECONDS)) {
				process.destroyForcibly();
			}
		}
	}

	/**
	 * A response as it is compared: the status, the fields that are the application's,
	 * the body. The page of a 500 is the container's, with the exception's trace, and is
	 * left out.
	 */
	private static String ask(HttpClient client, int port, Asked asked) throws IOException, InterruptedException {
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + asked.path()))
			.method(asked.method(), (asked.body() != null) ? HttpRequest.BodyPublishers.ofString(asked.body())
					: HttpRequest.BodyPublishers.noBody());
		asked.fields().forEach(request::header);
		HttpResponse<byte[]> response = client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
		StringBuilder text = new StringBuilder().append(response.statusCode());
		if (response.statusCode() == 500) {
			return text.toString();
		}
		response.headers().map().forEach((name, values) -> {
			if (SHOWN.contains(name.toLowerCase(java.util.Locale.ROOT))) {
				text.append('\n')
					.append(name.toLowerCase(java.util.Locale.ROOT))
					.append(": ")
					.append(String.join(" | ", values));
			}
		});
		text.append('\n').append(new String(response.body(), StandardCharsets.UTF_8));
		return text.toString();
	}

}
