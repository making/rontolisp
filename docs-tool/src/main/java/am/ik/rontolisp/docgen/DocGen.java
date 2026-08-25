package am.ik.rontolisp.docgen;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.data.MutableDataSet;

/**
 * Documentation site generator. Converts the Markdown under a source tree into a static
 * HTML site with a dark theme, sidebar navigation, previous/next links, and interactive
 * runnable Lisp examples.
 *
 * <p>
 * Layout of the source tree:
 *
 * <pre>
 * doc/
 *   assets/           shared CSS/JS (copied verbatim to &lt;out&gt;/assets)
 *   en/ nav.yaml + **&#47;*.md     one directory per language (auto-detected)
 *   ja/ ...
 * </pre>
 *
 * Each language directory that contains a {@code nav.yaml} becomes a localized site under
 * {@code <out>/<lang>/}. The default language also gets a redirect at
 * {@code <out>/index.html}. Markdown links to {@code *.md} are rewritten to
 * {@code *.html}.
 *
 * <p>
 * Usage:
 *
 * <pre>
 * java -jar rontolisp-docgen.jar --source doc --out web/dist/docs [--default-lang en]
 * </pre>
 */
public final class DocGen {

	private static final Pattern MD_LINK = Pattern.compile("href=\"([^\"]*?)\\.md(#[^\"]*)?\"");

	private static final Pattern HEADING_ID = Pattern.compile("(<h[1-6]\\b[^>]*\\bid=\")([^\"]*)(\")");

	private final Path source;

	private final Path out;

	private final String defaultLang;

	private final Parser parser;

	private final HtmlRenderer renderer;

	/**
	 * The heading anchors of the reference language, keyed by the doc-relative Markdown
	 * path. A translated page keeps its own heading TEXT but takes these anchors: a
	 * cross-page link is written once as {@code page.md#anchor} and mirrored verbatim
	 * into every language tree, so an anchor slugged from the translated heading would
	 * resolve in one tree only.
	 */
	private final Map<String, List<String>> referenceHeadingIds = new HashMap<>();

	/** The language whose anchors every other language adopts. */
	private String referenceLang;

	public DocGen(Path source, Path out, String defaultLang) {
		this.source = source;
		this.out = out;
		this.defaultLang = defaultLang;
		MutableDataSet options = Markdown.options();
		this.parser = Parser.builder(options).build();
		this.renderer = HtmlRenderer.builder(options).build();
	}

	public static void main(String[] args) throws IOException {
		// `skill` selects the agent-skill bundle instead of the HTML site; both
		// modes read the same doc/ tree, so they share this entry point.
		if (args.length > 0 && "skill".equals(args[0])) {
			SkillGen.main(java.util.Arrays.copyOfRange(args, 1, args.length));
			return;
		}
		Path source = Path.of("doc");
		Path out = Path.of("web/dist/docs");
		String defaultLang = "en";
		for (int i = 0; i < args.length - 1; i++) {
			switch (args[i]) {
				case "--source" -> source = Path.of(args[++i]);
				case "--out" -> out = Path.of(args[++i]);
				case "--default-lang" -> defaultLang = args[++i];
				default -> {
				}
			}
		}
		new DocGen(source, out, defaultLang).generate();
	}

	/** Generates the whole site for every detected language. */
	public void generate() throws IOException {
		List<String> languages = detectLanguages();
		if (languages.isEmpty()) {
			throw new IOException("No language directories with nav.yaml found under " + this.source);
		}
		List<HtmlTemplate.Language> languageList = languages.stream()
			.map(code -> new HtmlTemplate.Language(code, displayName(code)))
			.toList();

		Files.createDirectories(this.out);
		copyAssets();

		// The default language sorts first, so it is rendered before any tree that
		// has to adopt its anchors.
		this.referenceLang = languages.get(0);
		for (String lang : languages) {
			generateLanguage(lang, languageList);
		}

		String home = languages.contains(this.defaultLang) ? this.defaultLang : languages.get(0);
		writeIndexRedirect(home);
		System.out.println("Generated docs for " + languages + " into " + this.out);
	}

	private List<String> detectLanguages() throws IOException {
		try (Stream<Path> entries = Files.list(this.source)) {
			return entries.filter(Files::isDirectory)
				.filter(dir -> !dir.getFileName().toString().equals("assets"))
				.filter(dir -> Files.exists(dir.resolve("nav.yaml")))
				.map(dir -> dir.getFileName().toString())
				.sorted(byDefaultFirst())
				.toList();
		}
	}

	private Comparator<String> byDefaultFirst() {
		return Comparator.comparing((String code) -> code.equals(this.defaultLang) ? 0 : 1).thenComparing(c -> c);
	}

	private void generateLanguage(String lang, List<HtmlTemplate.Language> languageList) throws IOException {
		Path langDir = this.source.resolve(lang);
		Nav nav = Nav.load(langDir.resolve("nav.yaml"));
		List<Catalog> catalogs = Catalog.discover(langDir);
		// Each catalog's index page (the table) is linked to its detail pages.
		Map<String, Catalog> indexToCatalog = new HashMap<>();
		for (Catalog catalog : catalogs) {
			indexToCatalog.put(catalog.indexPage(), catalog);
		}

		List<Nav.Page> pages = nav.flatPages();
		for (int i = 0; i < pages.size(); i++) {
			Nav.Page page = pages.get(i);
			HtmlTemplate.Crumb prev = (i > 0) ? crumb(lang, pages.get(i - 1)) : null;
			HtmlTemplate.Crumb next = (i < pages.size() - 1) ? crumb(lang, pages.get(i + 1)) : null;
			renderNavPage(lang, nav, languageList, langDir, page, prev, next, indexToCatalog.get(page.file()));
			renderSubpages(lang, nav, languageList, langDir, page);
		}

		for (Catalog catalog : catalogs) {
			renderDetailPages(lang, nav, languageList, langDir, catalog, indexTitle(nav, catalog));
		}
	}

	private void renderNavPage(String lang, Nav nav, List<HtmlTemplate.Language> languageList, Path langDir,
			Nav.Page page, HtmlTemplate.Crumb prev, HtmlTemplate.Crumb next, Catalog catalog) throws IOException {
		Path mdPath = langDir.resolve(page.file());
		if (!Files.exists(mdPath)) {
			throw new IOException("Missing Markdown source: " + mdPath);
		}
		String body = renderBody(Files.readString(mdPath, StandardCharsets.UTF_8), page.file(), lang);
		// On a reference table page (functions/macros/special forms), link each
		// operator name in the table to its detail page, so that one page is both
		// the quick reference and the index of the detail pages.
		if (catalog != null) {
			body = TableLinkTransformer.transform(body, buildNameToSlug(catalog), catalog.linkPrefix());
		}
		String docPath = lang + "/" + replaceExtension(page.file());
		HtmlTemplate.PageContext ctx = new HtmlTemplate.PageContext(nav, lang, page.title(), docPath, page.file(),
				docPath, body, TocBuilder.build(body), null, prev, next, languageList);
		writePage(docPath, HtmlTemplate.render(ctx));
	}

	/**
	 * Renders the sub-pages of one nav page. They are reachable only through their
	 * parent, so they get its sidebar row highlighted, a back link to it, and a
	 * previous/next chain of their own that starts at the parent -- the same shape the
	 * per-operator detail pages have, with the parent page standing in for the catalog
	 * index.
	 */
	private void renderSubpages(String lang, Nav nav, List<HtmlTemplate.Language> languageList, Path langDir,
			Nav.Page parent) throws IOException {
		List<Nav.Page> subpages = parent.subpages();
		if (subpages.isEmpty()) {
			return;
		}
		String parentDocPath = lang + "/" + replaceExtension(parent.file());
		HtmlTemplate.Crumb backlink = new HtmlTemplate.Crumb(parentDocPath, parent.title());
		for (int i = 0; i < subpages.size(); i++) {
			Nav.Page page = subpages.get(i);
			Path mdPath = langDir.resolve(page.file());
			if (!Files.exists(mdPath)) {
				throw new IOException("Missing Markdown source: " + mdPath);
			}
			String docPath = lang + "/" + replaceExtension(page.file());
			HtmlTemplate.Crumb prev = (i > 0) ? crumb(lang, subpages.get(i - 1)) : backlink;
			HtmlTemplate.Crumb next = (i < subpages.size() - 1) ? crumb(lang, subpages.get(i + 1)) : null;
			String body = renderBody(Files.readString(mdPath, StandardCharsets.UTF_8), page.file(), lang);
			HtmlTemplate.PageContext ctx = new HtmlTemplate.PageContext(nav, lang, page.title(), docPath, page.file(),
					parentDocPath, body, TocBuilder.build(body), backlink, prev, next, languageList);
			writePage(docPath, HtmlTemplate.render(ctx));
			renderSubpages(lang, nav, languageList, langDir, page);
		}
	}

	private void renderDetailPages(String lang, Nav nav, List<HtmlTemplate.Language> languageList, Path langDir,
			Catalog catalog, String backlinkTitle) throws IOException {
		String indexDocPath = lang + "/" + replaceExtension(catalog.indexPage());
		HtmlTemplate.Crumb backlink = new HtmlTemplate.Crumb(indexDocPath, backlinkTitle);
		List<Catalog.Entry> entries = catalog.flatEntries();
		for (int i = 0; i < entries.size(); i++) {
			Catalog.Entry entry = entries.get(i);
			Path mdPath = langDir.resolve(catalog.mdFile(entry));
			if (!Files.exists(mdPath)) {
				throw new IOException("Missing detail page: " + mdPath);
			}
			String docPath = lang + "/" + replaceExtension(catalog.mdFile(entry));
			HtmlTemplate.Crumb prev = (i > 0) ? detailCrumb(lang, catalog, entries.get(i - 1)) : backlink;
			HtmlTemplate.Crumb next = (i < entries.size() - 1) ? detailCrumb(lang, catalog, entries.get(i + 1)) : null;
			String detailBody = renderBody(Files.readString(mdPath, StandardCharsets.UTF_8), catalog.mdFile(entry),
					lang);
			HtmlTemplate.PageContext ctx = new HtmlTemplate.PageContext(nav, lang, entry.name(), docPath,
					catalog.mdFile(entry), indexDocPath, detailBody, TocBuilder.build(detailBody), backlink, prev, next,
					languageList);
			writePage(docPath, HtmlTemplate.render(ctx));
		}
	}

	/** The sidebar title of a catalog's index page, used for the back link. */
	private static String indexTitle(Nav nav, Catalog catalog) {
		for (Nav.Section section : nav.sections()) {
			for (Nav.Page page : section.pages()) {
				if (page.file().equals(catalog.indexPage())) {
					return page.title();
				}
			}
		}
		return "Back";
	}

	/**
	 * Maps each individual operator name (HTML-escaped, as it appears in the rendered
	 * table) to its page slug. A grouped entry like {@code char schar} maps every token
	 * to the same page.
	 */
	static Map<String, String> buildNameToSlug(Catalog catalog) {
		Map<String, String> map = new HashMap<>();
		for (Catalog.Entry entry : catalog.flatEntries()) {
			for (String token : entry.name().trim().split("\\s+")) {
				if (!token.isEmpty()) {
					map.put(HtmlTemplate.esc(token), entry.slug());
				}
			}
		}
		return map;
	}

	private String renderBody(String markdown, String mdFile, String lang) throws IOException {
		String body = this.renderer.render(this.parser.parse(markdown));
		body = alignHeadingIds(body, mdFile, lang);
		body = rewriteMarkdownLinks(body);
		return RunnableBlockTransformer.transform(body);
	}

	/**
	 * Gives a translated page the reference language's heading anchors, matched by
	 * position. The trees are required to have the same heading layout, so a page whose
	 * heading count differs from its reference counterpart is a translation that has
	 * drifted -- it fails the build here rather than shipping anchors that point at the
	 * wrong sections.
	 */
	private String alignHeadingIds(String body, String mdFile, String lang) throws IOException {
		if (lang.equals(this.referenceLang)) {
			this.referenceHeadingIds.put(mdFile, headingIds(body));
			return body;
		}
		List<String> reference = this.referenceHeadingIds.get(mdFile);
		if (reference == null) {
			return body;
		}
		List<String> own = headingIds(body);
		if (own.size() != reference.size()) {
			throw new IOException("Heading layout of " + lang + "/" + mdFile + " differs from " + this.referenceLang
					+ "/" + mdFile + ": " + own.size() + " headings vs " + reference.size()
					+ ". Every language tree must have the same headings in the same order.");
		}
		Matcher matcher = HEADING_ID.matcher(body);
		StringBuilder out = new StringBuilder();
		int i = 0;
		while (matcher.find()) {
			String replacement = matcher.group(1) + reference.get(i++) + matcher.group(3);
			matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
		}
		matcher.appendTail(out);
		return out.toString();
	}

	/** The {@code id} of every heading of a rendered body, in document order. */
	static List<String> headingIds(String bodyHtml) {
		List<String> ids = new ArrayList<>();
		Matcher matcher = HEADING_ID.matcher(bodyHtml);
		while (matcher.find()) {
			ids.add(matcher.group(2));
		}
		return ids;
	}

	private void writePage(String docPath, String html) throws IOException {
		Path outPath = this.out.resolve(docPath);
		Files.createDirectories(outPath.getParent());
		Files.writeString(outPath, html, StandardCharsets.UTF_8);
	}

	private static HtmlTemplate.Crumb crumb(String lang, Nav.Page page) {
		return new HtmlTemplate.Crumb(lang + "/" + replaceExtension(page.file()), page.title());
	}

	private static HtmlTemplate.Crumb detailCrumb(String lang, Catalog catalog, Catalog.Entry entry) {
		return new HtmlTemplate.Crumb(lang + "/" + replaceExtension(catalog.mdFile(entry)), entry.name());
	}

	/** Rewrites {@code href="foo.md"} / {@code href="foo.md#x"} to {@code .html}. */
	static String rewriteMarkdownLinks(String html) {
		Matcher matcher = MD_LINK.matcher(html);
		StringBuilder out = new StringBuilder();
		while (matcher.find()) {
			String target = matcher.group(1);
			// Only a link to a page of this site becomes .html. An absolute URL
			// ending in .md names a file somewhere else -- the published SKILL.md,
			// say -- and there is no .html beside it.
			String replacement = target.contains("://") || target.startsWith("//") ? matcher.group(0)
					: "href=\"" + target + ".html" + (matcher.group(2) == null ? "" : matcher.group(2)) + "\"";
			matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
		}
		matcher.appendTail(out);
		return out.toString();
	}

	private void copyAssets() throws IOException {
		Path assetsDir = this.source.resolve("assets");
		if (!Files.isDirectory(assetsDir)) {
			return;
		}
		Path target = this.out.resolve("assets");
		Files.createDirectories(target);
		try (Stream<Path> files = Files.walk(assetsDir)) {
			files.forEach(src -> {
				try {
					Path dest = target.resolve(assetsDir.relativize(src).toString());
					if (Files.isDirectory(src)) {
						Files.createDirectories(dest);
					}
					else {
						Files.createDirectories(dest.getParent());
						Files.copy(src, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
					}
				}
				catch (IOException e) {
					throw new UncheckedIOException(e);
				}
			});
		}
	}

	private void writeIndexRedirect(String home) throws IOException {
		String html = """
				<!DOCTYPE html>
				<html lang="en">
				<head>
				<meta charset="UTF-8">
				<meta http-equiv="refresh" content="0; url=%s/index.html">
				<link rel="canonical" href="%s/index.html">
				<title>rontolisp documentation</title>
				</head>
				<body>
				<p>Redirecting to <a href="%s/index.html">the documentation</a>.</p>
				</body>
				</html>
				""".formatted(home, home, home);
		Files.writeString(this.out.resolve("index.html"), html, StandardCharsets.UTF_8);
	}

	private static String displayName(String code) {
		return switch (code) {
			case "en" -> "English";
			case "ja" -> "日本語";
			default -> code;
		};
	}

	private static String replaceExtension(String mdFile) {
		return mdFile.endsWith(".md") ? mdFile.substring(0, mdFile.length() - 3) + ".html" : mdFile;
	}

}
