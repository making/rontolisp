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
		return new Catalog(
				"reference/functions", "reference/functions.md", List
					.of(new Catalog.Category("c",
							List.of(new Catalog.Entry("plus", "+"), new Catalog.Entry("lt", "<"),
									new Catalog.Entry("char-compare", "char= char< char<=")),
							"reference/functions.md")));
	}

	@Test
	void catalogResolvesDetailPathAndLinkPrefix() {
		Catalog catalog = functionCatalog();
		assertThat(catalog.mdFile(new Catalog.Entry("plus", "+"))).isEqualTo("reference/functions/plus.md");
		assertThat(catalog.linkPrefix("reference/functions.md")).isEqualTo("functions/");
		assertThat(new Catalog("reference/macros", "reference/macros.md", List.of()).linkPrefix("reference/macros.md"))
			.isEqualTo("macros/");
	}

	@Test
	void linkPrefixIsEmptyWhenTheIndexPageAlreadyLivesInTheBaseDir() {
		Catalog catalog = functionCatalog();
		assertThat(catalog.linkPrefix("reference/functions/cl.md")).isEqualTo("");
	}

	@Test
	void categoryIndexPageOverridesTheCatalogDefault() {
		Catalog catalog = new Catalog("reference/functions", "reference/functions.md",
				List.of(new Catalog.Category("cl", List.of(new Catalog.Entry("plus", "+")),
						"reference/functions/cl.md"),
						new Catalog.Category("torch", List.of(new Catalog.Entry("tensor", "torch:tensor")),
								"reference/functions/torch.md")));
		assertThat(catalog.indexPages()).containsExactly("reference/functions/cl.md", "reference/functions/torch.md");
		assertThat(catalog.flatEntryRefs()).extracting(Catalog.EntryRef::indexPage)
			.containsExactly("reference/functions/cl.md", "reference/functions/torch.md");
	}

	@Test
	void categoryWithoutItsOwnIndexPageFallsBackToTheCatalogDefault() {
		Catalog catalog = new Catalog("reference/macros", "reference/macros.md", List
			.of(new Catalog.Category("c", List.of(new Catalog.Entry("when-let", "when-let")), "reference/macros.md")));
		assertThat(catalog.indexPages()).containsExactly("reference/macros.md");
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
	 * for the whole topic -- the per-package function pages are a breakdown of
	 * "Functions", not fifteen more entries of the Language Reference section.
	 */
	@Test
	void subpagesAreRenderedButAbsentFromTheSidebar() throws IOException {
		String parent = Files.readString(site.resolve("en/reference/functions.html"), StandardCharsets.UTF_8);
		String sidebar = parent.substring(parent.indexOf("<aside class=\"sidebar\""), parent.indexOf("</aside>"));
		assertThat(sidebar).contains("<a class=\"nav-link active\" href=\"functions.html\">Functions</a>")
			.doesNotContain("functions/cl.html");
		// ...and the page it links to exists, with the parent's row highlighted and a
		// back link to it.
		String sub = Files.readString(site.resolve("en/reference/functions/cl.html"), StandardCharsets.UTF_8);
		assertThat(sub).contains("<a class=\"nav-link active\" href=\"../functions.html\">Functions</a>")
			.contains("<a class=\"backlink\" href=\"../functions.html\">&larr; Functions</a>");
	}

	/**
	 * "The uiop Package" and its four sub-package pages moved under "Functions"
	 * (2026-08-30) so uiop stopped being its own sidebar row -- they are now nested TWO
	 * levels deep (Functions -> uiop.md -> uiop/os.md). Every level must still highlight
	 * the single top-level "Functions" row, while the back link at each level keeps
	 * pointing at its own immediate parent rather than jumping straight to the top.
	 */
	@Test
	void deeplyNestedSubpagesStillHighlightTheTopLevelSidebarRow() throws IOException {
		assertThat(Files.exists(site.resolve("en/reference/functions/uiop.html"))).isFalse();

		String uiop = Files.readString(site.resolve("en/reference/uiop.html"), StandardCharsets.UTF_8);
		String uiopSidebar = uiop.substring(uiop.indexOf("<aside class=\"sidebar\""), uiop.indexOf("</aside>"));
		assertThat(uiopSidebar).contains("<a class=\"nav-link active\" href=\"functions.html\">Functions</a>")
			.doesNotContain("nav-link active\" href=\"uiop.html\"");
		assertThat(uiop).contains("<a class=\"backlink\" href=\"functions.html\">&larr; Functions</a>");

		String os = Files.readString(site.resolve("en/reference/uiop/os.html"), StandardCharsets.UTF_8);
		String osSidebar = os.substring(os.indexOf("<aside class=\"sidebar\""), os.indexOf("</aside>"));
		assertThat(osSidebar).contains("<a class=\"nav-link active\" href=\"../functions.html\">Functions</a>");
		assertThat(os).contains("<a class=\"backlink\" href=\"../uiop.html\">&larr; The uiop Package</a>");
	}

	/**
	 * The per-package function pages are subpages of "Functions" that are ALSO catalog
	 * index pages: their table names must be auto-linked like a top-level index page's,
	 * and each detail page's back link must point at its own package's page rather than
	 * "Functions" itself.
	 */
	@Test
	void perPackageFunctionSubpagesActAsCatalogIndexPages() throws IOException {
		String cl = Files.readString(site.resolve("en/reference/functions/cl.html"), StandardCharsets.UTF_8);
		assertThat(cl).contains("<a class=\"fn-link\" href=\"plus.html\">");

		String plus = Files.readString(site.resolve("en/reference/functions/plus.html"), StandardCharsets.UTF_8);
		assertThat(plus).contains("<a class=\"backlink\" href=\"cl.html\">");

		String rontolispVersion = Files.readString(site.resolve("en/reference/functions/rontolisp-version.html"),
				StandardCharsets.UTF_8);
		assertThat(rontolispVersion).contains("<a class=\"backlink\" href=\"rontolisp.html\">");

		// The uiop category's index_page is reference/uiop.md itself (not a
		// reference/functions/uiop.md stub), so that page's own table gets the same
		// auto-linking treatment, and its entries' detail pages back-link there.
		String uiop = Files.readString(site.resolve("en/reference/uiop.html"), StandardCharsets.UTF_8);
		assertThat(uiop).contains("<a class=\"fn-link\" href=\"functions/uiop-file-exists-p.html\">");

		String uiopFileExistsP = Files.readString(site.resolve("en/reference/functions/uiop-file-exists-p.html"),
				StandardCharsets.UTF_8);
		assertThat(uiopFileExistsP).contains("<a class=\"backlink\" href=\"../uiop.html\">&larr; The uiop Package</a>");
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
	void searchIndexPlainTextDropsMarkupAndDecodesEntities() {
		assertThat(SearchIndex.plainText("<p>Compare with <code>eq</code>.</p>")).isEqualTo("Compare with eq.");
		// An inline tag sits inside a word, so removing it must not split one...
		assertThat(SearchIndex.plainText("<p><code>car</code>s</p>")).isEqualTo("cars");
		// ...while a block tag separates two.
		assertThat(SearchIndex.plainText("<ul><li>one</li><li>two</li></ul>")).isEqualTo("one two");
		assertThat(SearchIndex.plainText("<pre><code>(if (&lt; a b) &quot;x&quot;)</code></pre>"))
			.isEqualTo("(if (< a b) \"x\")");
		assertThat(SearchIndex.plainText("<p>&amp;lt; is an escape</p>")).isEqualTo("&lt; is an escape");
	}

	@Test
	void searchIndexSplitsAPageIntoSectionsKeyedToItsAnchors() {
		SearchIndex index = new SearchIndex("en");
		index.addPage("guides/x.html", "Title", """
				<h1 id="title">Title</h1>
				<p>lead text</p>
				<h2 id="options">Options</h2>
				<p>option text</p>
				""", false);
		// Every heading is an entry, the <h1> included, so the index shape follows
		// the document rather than the (translated) nav title.
		assertThat(index.tier1()).contains("\"p\":\"guides/x.html\"", "\"t\":\"Title\"",
				"[[\"title\",\"Title\"],[\"options\",\"Options\"]]");
		assertThat(index.tier2()).contains("[0,0,\"lead text\"]", "[0,1,\"option text\"]");
	}

	@Test
	void searchIndexRecordsAnOperatorsSignature() {
		SearchIndex index = new SearchIndex("en");
		index.addPage("reference/functions/mapcar.html", "mapcar", """
				<h1 id="mapcar">mapcar</h1>
				<p><code>(mapcar function list &amp;rest more-lists)</code></p>
				<p>Applies.</p>
				""", true);
		assertThat(index.tier1()).contains("\"s\":\"(mapcar function list &rest more-lists)\"", "\"o\":1");
		// A page that is not an operator page carries neither.
		SearchIndex plain = new SearchIndex("en");
		plain.addPage("index.html", "Introduction", "<h1 id=\"i\">Introduction</h1><p><code>x</code></p>", false);
		assertThat(plain.tier1()).doesNotContain("\"s\":").doesNotContain("\"o\":1");
	}

	@Test
	void searchIndexQuotesJsonStrings() {
		assertThat(SearchIndex.quote("a\"b\\c")).isEqualTo("\"a\\\"b\\\\c\"");
		assertThat(SearchIndex.quote("tab\there")).isEqualTo("\"tab\\there\"");
		// Non-ASCII stays verbatim: the files are served as UTF-8.
		assertThat(SearchIndex.quote("リーダー")).isEqualTo("\"リーダー\"");
	}

	/**
	 * The anti-rot gate for the search: every page the site renders is reachable through
	 * the index, and every index entry lands on a real anchor of a real page.
	 */
	@Test
	void everyRenderedPageIsInItsLanguagesSearchIndex() throws IOException {
		for (String lang : List.of("en", "ja")) {
			Set<String> indexed = new HashSet<>();
			for (Object page : tier1Pages(lang)) {
				indexed.add(String.valueOf(((Map<?, ?>) page).get("p")));
			}
			Set<String> rendered = new HashSet<>();
			try (Stream<Path> pages = Files.walk(site.resolve(lang))) {
				pages.filter(p -> p.toString().endsWith(".html"))
					.forEach(p -> rendered.add(site.resolve(lang).relativize(p).toString().replace('\\', '/')));
			}
			assertThat(indexed).as("search index of " + lang).isEqualTo(rendered);
		}
	}

	@Test
	void everySearchHitResolvesToAnAnchorOfTheGeneratedSite() throws IOException {
		for (String lang : List.of("en", "ja")) {
			List<String> dead = new ArrayList<>();
			for (Object rawPage : tier1Pages(lang)) {
				Map<?, ?> page = (Map<?, ?>) rawPage;
				Path html = site.resolve(lang).resolve(String.valueOf(page.get("p")));
				if (!Files.exists(html)) {
					dead.add(lang + "/" + page.get("p"));
					continue;
				}
				Set<String> anchors = new HashSet<>();
				Matcher matcher = ID.matcher(Files.readString(html, StandardCharsets.UTF_8));
				while (matcher.find()) {
					anchors.add(matcher.group(1));
				}
				for (Object rawHeading : (List<?>) page.get("h")) {
					String anchor = String.valueOf(((List<?>) rawHeading).get(0));
					if (!anchors.contains(anchor)) {
						dead.add(lang + "/" + page.get("p") + "#" + anchor);
					}
				}
			}
			assertThat(dead).isEmpty();
		}
	}

	/**
	 * The trees are structurally identical by rule, so a divergence between their indexes
	 * is a doc bug -- and a body tier that lost a language's sections would fail silently
	 * at query time rather than at build time.
	 */
	@Test
	void theLanguageIndexesHaveTheSameShape() throws IOException {
		List<?> en = tier1Pages("en");
		List<?> ja = tier1Pages("ja");
		assertThat(ja).hasSameSizeAs(en);
		for (int i = 0; i < en.size(); i++) {
			Map<?, ?> a = (Map<?, ?>) en.get(i);
			Map<?, ?> b = (Map<?, ?>) ja.get(i);
			// Same page, same anchors -- only the titles and heading labels differ.
			assertThat(b.get("p")).isEqualTo(a.get("p"));
			assertThat(anchorsOf(b)).as(String.valueOf(a.get("p"))).isEqualTo(anchorsOf(a));
		}
		assertThat(tier2Sections("ja")).hasSameSizeAs(tier2Sections("en"));
	}

	@Test
	void theSearchIndexReachesTheOperatorPagesWithTheirSignature() throws IOException {
		Map<?, ?> mapcar = tier1Pages("en").stream()
			.map(Map.class::cast)
			.filter(page -> "reference/functions/mapcar.html".equals(page.get("p")))
			.findFirst()
			.orElseThrow();
		assertThat(mapcar.get("t")).isEqualTo("mapcar");
		assertThat(mapcar.get("o")).isEqualTo(1);
		assertThat(mapcar.get("s")).isEqualTo("(mapcar function list &rest more-lists)");
		// ...and its prose is in the body tier, which is what a phrase query hits.
		assertThat(tier2Sections("en").stream()
			.map(List.class::cast)
			.anyMatch(section -> String.valueOf(section.get(2)).contains("Applies"))).isTrue();
	}

	@Test
	void everyPageLinksTheSearchIndexOfItsOwnLanguage() throws IOException {
		String deep = Files.readString(site.resolve("ja/reference/functions/mapcar.html"), StandardCharsets.UTF_8);
		assertThat(deep).contains("data-search-base=\"../..\"").contains("class=\"search-open\"");
		assertThat(Files.readString(site.resolve("en/index.html"), StandardCharsets.UTF_8))
			.contains("data-search-base=\".\"");
	}

	@SuppressWarnings("unchecked")
	private static List<Object> tier1Pages(String lang) throws IOException {
		return (List<Object>) loadJson(site.resolve(lang).resolve("search-index.json")).get("pages");
	}

	@SuppressWarnings("unchecked")
	private static List<Object> tier2Sections(String lang) throws IOException {
		return (List<Object>) loadJson(site.resolve(lang).resolve("search-body.json")).get("s");
	}

	/** JSON is YAML, and snakeyaml is already here for the nav and the catalogs. */
	private static Map<String, Object> loadJson(Path file) throws IOException {
		return new org.yaml.snakeyaml.Yaml().load(Files.readString(file, StandardCharsets.UTF_8));
	}

	private static List<String> anchorsOf(Map<?, ?> page) {
		return ((List<?>) page.get("h")).stream().map(h -> String.valueOf(((List<?>) h).get(0))).toList();
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
