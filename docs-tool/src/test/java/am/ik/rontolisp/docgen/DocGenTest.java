package am.ik.rontolisp.docgen;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DocGenTest {

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

	@Test
	void relativeLinksAreComputedFromDocsRoot() {
		assertThat(HtmlTemplate.rel("en/reference/data-types.html", "assets/docs.css")).isEqualTo("../../assets/docs.css");
		assertThat(HtmlTemplate.rel("en/index.html", "en/reference/data-types.html"))
			.isEqualTo("reference/data-types.html");
	}

	@Test
	void runtimeSrcPointsAboveTheDocsRoot() {
		assertThat(HtmlTemplate.runtimeSrc("en/index.html")).isEqualTo("../../rontoplayground.js");
		assertThat(HtmlTemplate.runtimeSrc("en/reference/data-types.html")).isEqualTo("../../../rontoplayground.js");
	}

}
