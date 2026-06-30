package am.ik.rontolisp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.stream.Stream;

import com.sun.net.httpserver.HttpServer;
import am.ik.rontolisp.eval.LispEvaluator;
import am.ik.rontolisp.reader.LispReader;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Verifies the runnable Lisp examples in the documentation site sources under
 * {@code doc/en}. This replaces the former {@code ReadmeExamplesTest}: instead of
 * hard-coded snippets mirroring the README, it parses every Markdown page and exercises
 * the examples that actually ship on the site.
 *
 * <p>
 * Convention (shared with {@code RunnableBlockTransformer} in the docgen tool):
 * <ul>
 * <li>A <code>```lisp</code> fenced block is an evaluable program. Every such block must
 * run on the interpreter without throwing.</li>
 * <li>When a <code>```lisp</code> block is immediately followed by a plain
 * (no-info-string, {@code text} or {@code output}) fenced block, that block is the
 * program's expected standard output, which is asserted exactly.</li>
 * <li>On the per-function reference pages ({@code reference/functions/}) a final form
 * annotated with <code>; =&gt; value</code> has its printed value asserted.</li>
 * <li>REPL transcripts (<code>```console</code>) and shell blocks (<code>```bash</code>)
 * are static and not executed.</li>
 * </ul>
 *
 * <p>
 * Examples on the same page share one evaluator (a definition in an earlier block is
 * visible to later ones), matching the in-browser behavior of the documentation runnable
 * cells. Cross-backend (JVM/WASM/component) parity is covered separately by
 * {@code CiSpecE2eTest} and {@code ci-spec.yaml}.
 *
 * <p>
 * Run with {@code -Drontolisp.doc.fix=true} to instead rewrite the function pages' shown
 * results ({@code ; => ...} annotations and stdout output blocks) to the actual evaluated
 * values -- a maintenance helper that keeps the shown results exact. The normal
 * verification factory is disabled in that mode.
 */
class DocExamplesTest {

	private static final Path DOC_ROOT = Path.of("doc", "en");

	private static final String ARROW = "; =>";

	// The rontolisp:fetch examples document a public URL (e.g. https://httpbin.org/get),
	// but the test must not reach the network: it serves the requests from a local JDK
	// HttpServer and rewrites the URL in the example to point at it before evaluating.
	// The
	// documented URL is intentionally left as-is on the page -- this is the one place the
	// executed example diverges from what the page shows.
	private static @Nullable HttpServer fetchServer;

	private static synchronized String localFetchUrl() {
		HttpServer server = fetchServer;
		if (server == null) {
			try {
				server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
				server.createContext("/", exchange -> {
					byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
					exchange.getResponseHeaders().add("Content-Type", "application/json");
					exchange.sendResponseHeaders(200, body.length);
					try (OutputStream os = exchange.getResponseBody()) {
						os.write(body);
					}
				});
				server.start();
				fetchServer = server;
			}
			catch (IOException ex) {
				throw new UncheckedIOException(ex);
			}
		}
		return "http://127.0.0.1:" + server.getAddress().getPort() + "/";
	}

	@AfterAll
	static void stopFetchServer() {
		if (fetchServer != null) {
			fetchServer.stop(0);
			fetchServer = null;
		}
	}

	/**
	 * If an example uses {@code rontolisp:fetch}, rewrites the http(s) URL string
	 * literals to the local test server so evaluation stays offline; otherwise returns
	 * the source unchanged.
	 */
	private static String rewriteFetchUrls(String source) {
		if (!source.contains("rontolisp:fetch")) {
			return source;
		}
		return source.replaceAll("\"https?://[^\"]*\"", Matcher.quoteReplacement("\"" + localFetchUrl() + "\""));
	}

	@TestFactory
	@DisabledIfSystemProperty(named = "rontolisp.doc.fix", matches = "true")
	Stream<DynamicTest> documentationExamples() throws IOException {
		assertThat(Files.isDirectory(DOC_ROOT)).as("documentation source directory %s exists", DOC_ROOT).isTrue();
		try (Stream<Path> paths = Files.walk(DOC_ROOT)) {
			List<Path> markdownFiles = paths.filter(p -> p.toString().endsWith(".md")).sorted().toList();
			assertThat(markdownFiles).as("at least one Markdown page is present").isNotEmpty();
			return markdownFiles.stream()
				.map(md -> DynamicTest.dynamicTest(DOC_ROOT.relativize(md).toString(), () -> checkPage(md)))
				.toList()
				.stream();
		}
	}

	private void checkPage(Path markdown) throws IOException {
		// A per-operator detail page lives in a directory with a _catalog.yaml; on
		// those pages a final form's `; => value` annotation is asserted.
		boolean detailPage = Files.exists(markdown.resolveSibling("_catalog.yaml"));
		List<Block> blocks = parseFencedBlocks(Files.readString(markdown, StandardCharsets.UTF_8));

		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(buffer, true, StandardCharsets.UTF_8));

		for (int i = 0; i < blocks.size(); i++) {
			Block block = blocks.get(i);
			if (!block.isLisp()) {
				continue;
			}
			buffer.reset();
			LispVal last = LispNil.INSTANCE;
			try {
				for (LispVal expr : LispReader.readAllFromString(rewriteFetchUrls(block.content()))) {
					last = evaluator.eval(expr);
				}
			}
			catch (RuntimeException ex) {
				fail("Example in %s failed to evaluate:%n%s%n-> %s".formatted(markdown, block.content(), ex), ex);
			}
			String actual = buffer.toString(StandardCharsets.UTF_8).strip();

			Block expected = (i + 1 < blocks.size()) ? blocks.get(i + 1) : null;
			if (expected != null && expected.isExpectedOutput()) {
				assertThat(actual).as("output of example in %s:%n%s", markdown, block.content())
					.isEqualTo(expected.content().strip());
			}
			else if (detailPage) {
				String annotation = lastArrowAnnotation(block.content());
				if (!annotation.isEmpty()) {
					assertThat(last.print()).as("`; =>` result of example in %s:%n%s", markdown, block.content())
						.isEqualTo(annotation);
				}
			}
		}
	}

	/**
	 * Maintenance helper: rewrites the per-function pages so the shown results exactly
	 * match the interpreter. Enabled only with {@code -Drontolisp.doc.fix=true}.
	 */
	@Test
	@EnabledIfSystemProperty(named = "rontolisp.doc.fix", matches = "true")
	void fixDetailResults() throws IOException {
		List<String> failures = new ArrayList<>();
		List<Path> pages = new ArrayList<>();
		// Every directory that holds a _catalog.yaml is a detail-page directory.
		try (Stream<Path> paths = Files.walk(DOC_ROOT)) {
			List<Path> catalogDirs = paths.filter(p -> p.getFileName().toString().equals("_catalog.yaml"))
				.map(Path::getParent)
				.sorted()
				.toList();
			for (Path dir : catalogDirs) {
				try (Stream<Path> mds = Files.list(dir)) {
					mds.filter(p -> p.toString().endsWith(".md")).sorted().forEach(pages::add);
				}
			}
		}
		int fixed = 0;
		for (Path page : pages) {
			try {
				Files.writeString(page, fixPage(Files.readString(page, StandardCharsets.UTF_8), page),
						StandardCharsets.UTF_8);
				fixed++;
			}
			catch (RuntimeException ex) {
				failures.add(ex.getMessage());
			}
		}
		System.out.println("Fixed shown results in " + fixed + " detail pages");
		if (!failures.isEmpty()) {
			fail("Non-runnable examples (%d):%n%s".formatted(failures.size(), String.join("\n---\n", failures)));
		}
	}

	/** Rewrites one page's {@code ; =>} annotations and stdout output blocks. */
	private String fixPage(String markdown, Path page) {
		String[] lines = markdown.split("\n", -1);
		StringBuilder out = new StringBuilder();
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(buffer, true, StandardCharsets.UTF_8));
		String pendingStdout = null; // stdout of the previous lisp block, awaiting an
										// output fence

		int i = 0;
		while (i < lines.length) {
			String line = lines[i];
			if (line.stripLeading().startsWith("```")) {
				String info = line.strip().substring(3).trim();
				int j = i + 1;
				while (j < lines.length && !lines[j].stripLeading().startsWith("```")) {
					j++;
				}
				List<String> content = new ArrayList<>(List.of(lines).subList(i + 1, Math.min(j, lines.length)));
				String closing = (j < lines.length) ? lines[j] : "```";

				if (info.equals("lisp")) {
					buffer.reset();
					LispVal last = LispNil.INSTANCE;
					try {
						// Evaluate with fetch URLs rewritten to the local server, but
						// keep
						// `content` (the page source) unchanged so the fix helper
						// rewrites
						// only the shown result, not the documented URL.
						for (LispVal expr : LispReader
							.readAllFromString(rewriteFetchUrls(String.join("\n", content)))) {
							last = evaluator.eval(expr);
						}
					}
					catch (RuntimeException ex) {
						throw new IllegalStateException(
								"Example in " + page + " failed:\n" + String.join("\n", content), ex);
					}
					content = rewriteArrow(content, last.print());
					pendingStdout = buffer.toString(StandardCharsets.UTF_8).strip();
				}
				else if (isOutputInfo(info) && pendingStdout != null) {
					content = pendingStdout.isEmpty() ? List.of()
							: new ArrayList<>(List.of(pendingStdout.split("\n", -1)));
					pendingStdout = null;
				}
				else {
					pendingStdout = null;
				}

				out.append(line).append('\n');
				for (String c : content) {
					out.append(c).append('\n');
				}
				out.append(closing).append('\n');
				i = j + 1;
			}
			else {
				if (!line.isBlank()) {
					pendingStdout = null;
				}
				out.append(line).append('\n');
				i++;
			}
		}
		// split("\n", -1) yields a trailing empty element for the final newline; the
		// loop already re-appended a newline per emitted line, so drop the extra.
		if (out.length() > 0 && out.charAt(out.length() - 1) == '\n') {
			out.setLength(out.length() - 1);
		}
		return out.toString();
	}

	/** Replaces the text after the last {@code ; =>} on its line with {@code value}. */
	static List<String> rewriteArrow(List<String> content, String value) {
		int target = -1;
		for (int k = content.size() - 1; k >= 0; k--) {
			if (content.get(k).contains(ARROW)) {
				target = k;
				break;
			}
		}
		if (target < 0) {
			return content;
		}
		List<String> result = new ArrayList<>(content);
		String line = result.get(target);
		int idx = line.lastIndexOf(ARROW);
		result.set(target, line.substring(0, idx) + ARROW + " " + value);
		return result;
	}

	/** Returns the text after the last {@code ; =>} annotation, or {@code ""} if none. */
	static String lastArrowAnnotation(String content) {
		int idx = content.lastIndexOf(ARROW);
		if (idx < 0) {
			return "";
		}
		String rest = content.substring(idx + ARROW.length());
		int newline = rest.indexOf('\n');
		if (newline >= 0) {
			rest = rest.substring(0, newline);
		}
		return rest.strip();
	}

	private static boolean isOutputInfo(String info) {
		return info.isEmpty() || info.equals("text") || info.equals("output");
	}

	/** Splits Markdown into fenced code blocks (info string + raw content). */
	static List<Block> parseFencedBlocks(String markdown) {
		List<Block> blocks = new ArrayList<>();
		String[] lines = markdown.split("\n", -1);
		int i = 0;
		while (i < lines.length) {
			String line = lines[i];
			if (line.startsWith("```")) {
				String info = line.substring(3).trim();
				StringBuilder content = new StringBuilder();
				i++;
				while (i < lines.length && !lines[i].startsWith("```")) {
					content.append(lines[i]).append('\n');
					i++;
				}
				blocks.add(new Block(info, content.toString()));
				i++; // skip the closing fence
			}
			else {
				i++;
			}
		}
		return blocks;
	}

	/** A fenced code block: its info string (language) and raw content. */
	record Block(String info, String content) {

		boolean isLisp() {
			return this.info.equals("lisp");
		}

		boolean isExpectedOutput() {
			return this.info.isEmpty() || this.info.equals("text") || this.info.equals("output");
		}
	}

}
