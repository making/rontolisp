package am.ik.rontolisp.docgen;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class DocGenTest {

	private static final Path DOC = Path.of("..", "doc");

	private static final Pattern ID = Pattern.compile("\\bid=\"([^\"]*)\"");

	private static final Pattern HREF = Pattern.compile("href=\"([^\"]*#[^\"]*)\"");

	@TempDir
	static Path site;

	@BeforeAll
	static void generateSite() throws IOException {
		assumeTrue(Files.isDirectory(DOC), "run from the docs-tool module, next to doc/");
		new DocGen(DOC, site, "en").generate();
	}

	private static Catalog functionCatalog() {
		return new Catalog("reference/functions", "reference/functions.md",
				List.of(new Catalog.Category("c", List.of(new Catalog.Entry("plus", "+"), new Catalog.Entry("lt", "<"),
						new Catalog.Entry("char-compare", "char= char< char<=")))));
	}

	@Test
	void catalogResolvesDetailPathAndLinkPrefix() {
		Catalog catalog = functionCatalog();
		assertThat(catalog.mdFile(new Catalog.Entry("plus", "+"))).isEqualTo("reference/functions/plus.md");
		assertThat(catalog.linkPrefix()).isEqualTo("functions/");
		assertThat(new Catalog("reference/macros", "reference/macros.md", List.of()).linkPrefix()).isEqualTo("macros/");
	}

	@Test
	void nameToSlugMapsEachTokenIncludingGroupedAndSymbolicNames() {
		Map<String, String> map = DocGen.buildNameToSlug(functionCatalog());
		assertThat(map).containsEntry("+", "plus") // symbolic name
			.containsEntry("&lt;", "lt") // HTML-escaped name
			.containsEntry("char=", "char-compare") // grouped names share a page
			.containsEntry("char&lt;", "char-compare");
	}

	@Test
	void tableNamesAreLinkedToDetailPages() {
		String row = "<tr><td><code>+</code></td><td><code>(+ 1 2)</code></td><td><code>3</code></td></tr>";
		String out = TableLinkTransformer.transform(row, Map.of("+", "plus"), "functions/");
		// the name cell is linked...
		assertThat(out).contains("<td><a class=\"fn-link\" href=\"functions/plus.html\"><code>+</code></a></td>");
		// ...but the example/result columns are not
		assertThat(out).contains("<td><code>(+ 1 2)</code></td>").contains("<td><code>3</code></td>");
	}

	@Test
	void runnableLispBlockBecomesAnInteractiveCell() {
		String html = "<pre><code class=\"language-lisp\">(print (+ 1 2))</code></pre>";
		String out = RunnableBlockTransformer.transform(html);
		assertThat(out).contains("class=\"code-cell\"")
			.contains("<button class=\"run\"")
			.contains("<textarea class=\"cell-src\"")
			.contains("(print (+ 1 2))");
	}

	@Test
	void replTranscriptStaysStatic() {
		String html = "<pre><code class=\"language-lisp\">&gt; (+ 1 2)\n3</code></pre>";
		String out = RunnableBlockTransformer.transform(html);
		assertThat(out).isEqualTo(html);
	}

	@Test
	void nonLispBlocksAreUntouched() {
		String html = "<pre><code class=\"language-bash\">java -jar app.jar</code></pre>";
		assertThat(RunnableBlockTransformer.transform(html)).isEqualTo(html);
	}

	@Test
	void markdownLinksAreRewrittenToHtml() {
		assertThat(DocGen.rewriteMarkdownLinks("<a href=\"format.md\">format</a>"))
			.isEqualTo("<a href=\"format.html\">format</a>");
		assertThat(DocGen.rewriteMarkdownLinks("<a href=\"../guides/eval-limitations.md#x\">eval</a>"))
			.isEqualTo("<a href=\"../guides/eval-limitations.html#x\">eval</a>");
		// An absolute URL ending in .md is a file elsewhere, with no .html beside it
		String published = "<a href=\"https://making.github.io/rontolisp/skill/rontolisp-full.md\">full</a>";
		assertThat(DocGen.rewriteMarkdownLinks(published)).isEqualTo(published);
		assertThat(DocGen.rewriteMarkdownLinks("<a href=\"//cdn.example/x.md\">x</a>"))
			.isEqualTo("<a href=\"//cdn.example/x.md\">x</a>");
	}

	@Test
	void tocCollectsH2AndH3Headings() {
		String body = "<h1 id=\"title\">Title</h1>\n<h2 id=\"opts\">Options</h2>\n<p>x</p>\n"
				+ "<h3 id=\"flags\">Flags</h3>\n<h2 id=\"result\">Result</h2>";
		String toc = TocBuilder.build(body);
		assertThat(toc).contains("class=\"toc\"")
			.contains("On this page")
			.contains("<li class=\"toc-h2\"><a href=\"#opts\">Options</a></li>")
			.contains("<li class=\"toc-h3\"><a href=\"#flags\">Flags</a></li>")
			.contains("<li class=\"toc-h2\"><a href=\"#result\">Result</a></li>")
			// the h1 page title is excluded
			.doesNotContain("#title");
	}

	@Test
	void tocIsOmittedWhenFewerThanTwoHeadings() {
		assertThat(TocBuilder.build("<h1 id=\"t\">T</h1><h2 id=\"only\">Only</h2>")).isEmpty();
		assertThat(TocBuilder.build("<p>no headings</p>")).isEmpty();
	}

	@Test
	void tocKeepsInlineCodeInHeadingLabels() {
		String body = "<h2 id=\"a\"><code>fetch</code> options</h2><h2 id=\"b\">Result</h2>";
		assertThat(TocBuilder.build(body)).contains("<a href=\"#a\"><code>fetch</code> options</a>");
	}

	/**
	 * The anchor half of the link check {@link SkillGenTest} does for pages: a cross-page
	 * link is written once and mirrored into every language tree, so a heading renamed in
	 * one tree must break this build rather than ship a link that lands at the top of the
	 * right page.
	 */
	@Test
	void everyAnchorLinkResolvesInEveryLanguage() throws IOException {
		Map<Path, Set<String>> ids = new HashMap<>();
		try (Stream<Path> pages = Files.walk(site)) {
			for (Path page : pages.filter(p -> p.toString().endsWith(".html")).toList()) {
				Matcher matcher = ID.matcher(Files.readString(page, StandardCharsets.UTF_8));
				Set<String> anchors = new HashSet<>();
				while (matcher.find()) {
					anchors.add(matcher.group(1));
				}
				ids.put(page.normalize(), anchors);
			}
		}
		List<String> dead = new ArrayList<>();
		for (Path page : ids.keySet()) {
			Matcher matcher = HREF.matcher(Files.readString(page, StandardCharsets.UTF_8));
			while (matcher.find()) {
				String href = matcher.group(1);
				if (href.contains("://") || href.startsWith("//")) {
					continue;
				}
				String target = href.substring(0, href.indexOf('#'));
				String fragment = href.substring(href.indexOf('#') + 1);
				Path targetPage = target.isEmpty() ? page : page.getParent().resolve(target).normalize();
				if (!ids.containsKey(targetPage) || !ids.get(targetPage).contains(fragment)) {
					dead.add(site.relativize(page) + " -> " + href);
				}
			}
		}
		assertThat(dead).isEmpty();
	}

	/**
	 * A sub-page is rendered and reachable from its parent, but the sidebar keeps one row
	 * for the whole topic -- the uiop sub-package pages are a breakdown of "The uiop
	 * Package", not four more entries of the Language Reference section.
	 */
	@Test
	void subpagesAreRenderedButAbsentFromTheSidebar() throws IOException {
		String parent = Files.readString(site.resolve("en/reference/uiop.html"), StandardCharsets.UTF_8);
		String sidebar = parent.substring(parent.indexOf("<aside class=\"sidebar\""), parent.indexOf("</aside>"));
		assertThat(sidebar).contains("<a class=\"nav-link active\" href=\"uiop.html\">The uiop Package</a>")
			.doesNotContain("uiop/os.html");
		// ...and the page it links to exists, with the parent's row highlighted and a
		// back link to it.
		String sub = Files.readString(site.resolve("en/reference/uiop/os.html"), StandardCharsets.UTF_8);
		assertThat(sub).contains("<a class=\"nav-link active\" href=\"../uiop.html\">The uiop Package</a>")
			.contains("<a class=\"backlink\" href=\"../uiop.html\">&larr; The uiop Package</a>");
	}

	@Test
	void translatedPagesKeepTheReferenceLanguagesAnchors() throws IOException {
		String ja = Files.readString(site.resolve("ja/guides/wasm-gc-module.html"), StandardCharsets.UTF_8);
		// The heading TEXT is translated, its anchor is not -- that is what lets
		// ja/compiling/wasm.md link to the same #no-wasi-reactor-mode as en.
		assertThat(ja).contains("id=\"no-wasi-reactor-mode\"").doesNotContain("id=\"no-wasiリアクターモード\"");
		assertThat(ja).contains("リアクター");
	}

	@Test
	void aTranslationWithADifferentHeadingLayoutFailsTheBuild(@TempDir Path tmp) throws IOException {
		Path source = tmp.resolve("doc");
		writePage(source.resolve("en"), "English", "# Title\n\n## Options\n\ntext\n");
		writePage(source.resolve("ja"), "日本語", "# タイトル\n\n## オプション\n\n## 余分\n\ntext\n");
		assertThatThrownBy(() -> new DocGen(source, tmp.resolve("out"), "en").generate())
			.isInstanceOf(IOException.class)
			.hasMessageContaining("ja/index.md")
			.hasMessageContaining("3 headings vs 2");
	}

	private static void writePage(Path langDir, String langName, String markdown) throws IOException {
		Files.createDirectories(langDir);
		Files.writeString(langDir.resolve("nav.yaml"), """
				title: docs
				lang_name: %s
				sections:
				  - title: Guide
				    pages:
				      - file: index.md
				        title: Index
				""".formatted(langName), StandardCharsets.UTF_8);
		Files.writeString(langDir.resolve("index.md"), markdown, StandardCharsets.UTF_8);
	}

	@Test
	void relativeLinksAreComputedFromDocsRoot() {
		assertThat(HtmlTemplate.rel("en/reference/data-types.html", "assets/docs.css"))
			.isEqualTo("../../assets/docs.css");
		assertThat(HtmlTemplate.rel("en/index.html", "en/reference/data-types.html"))
			.isEqualTo("reference/data-types.html");
	}

	@Test
	void runtimeSrcPointsAboveTheDocsRoot() {
		assertThat(HtmlTemplate.runtimeSrc("en/index.html")).isEqualTo("../../rontoplayground.js");
		assertThat(HtmlTemplate.runtimeSrc("en/reference/data-types.html")).isEqualTo("../../../rontoplayground.js");
	}

}
