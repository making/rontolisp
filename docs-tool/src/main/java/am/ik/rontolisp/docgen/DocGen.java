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

import com.vladsch.flexmark.ext.autolink.AutolinkExtension;
import com.vladsch.flexmark.ext.gfm.strikethrough.StrikethroughExtension;
import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.data.MutableDataSet;

/**
 * Documentation site generator. Converts the Markdown under a source tree into a
 * static HTML site with a dark theme, sidebar navigation, previous/next links,
 * and interactive runnable Lisp examples.
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
 * Each language directory that contains a {@code nav.yaml} becomes a localized
 * site under {@code <out>/<lang>/}. The default language also gets a redirect at
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

	private final Path source;

	private final Path out;

	private final String defaultLang;

	private final Parser parser;

	private final HtmlRenderer renderer;

	public DocGen(Path source, Path out, String defaultLang) {
		this.source = source;
		this.out = out;
		this.defaultLang = defaultLang;
		MutableDataSet options = new MutableDataSet();
		options.set(Parser.EXTENSIONS,
				List.of(TablesExtension.create(), AutolinkExtension.create(), StrikethroughExtension.create()));
		// Heading ids (for intra-page #anchor links) without wrapping the heading
		// text in an anchor element.
		options.set(HtmlRenderer.GENERATE_HEADER_ID, true);
		options.set(HtmlRenderer.RENDER_HEADER_ID, true);
		options.set(TablesExtension.COLUMN_SPANS, false);
		options.set(TablesExtension.APPEND_MISSING_COLUMNS, true);
		options.set(TablesExtension.DISCARD_EXTRA_COLUMNS, true);
		this.parser = Parser.builder(options).build();
		this.renderer = HtmlRenderer.builder(options).build();
	}

	public static void main(String[] args) throws IOException {
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
		List<Catalog> catalogs = discoverCatalogs(langDir);
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
		}

		for (Catalog catalog : catalogs) {
			renderDetailPages(lang, nav, languageList, langDir, catalog, indexTitle(nav, catalog));
		}
	}

	/** Finds every {@code _catalog.yaml} under the language directory. */
	private List<Catalog> discoverCatalogs(Path langDir) throws IOException {
		try (Stream<Path> paths = Files.walk(langDir)) {
			List<Path> catalogFiles = paths.filter(p -> p.getFileName().toString().equals("_catalog.yaml"))
				.sorted()
				.toList();
			List<Catalog> catalogs = new ArrayList<>();
			for (Path file : catalogFiles) {
				catalogs.add(Catalog.load(langDir, file));
			}
			return catalogs;
		}
	}

	private void renderNavPage(String lang, Nav nav, List<HtmlTemplate.Language> languageList, Path langDir,
			Nav.Page page, HtmlTemplate.Crumb prev, HtmlTemplate.Crumb next, Catalog catalog) throws IOException {
		Path mdPath = langDir.resolve(page.file());
		if (!Files.exists(mdPath)) {
			throw new IOException("Missing Markdown source: " + mdPath);
		}
		String body = renderBody(Files.readString(mdPath, StandardCharsets.UTF_8));
		// On a reference table page (functions/macros/special forms), link each
		// operator name in the table to its detail page, so that one page is both
		// the quick reference and the index of the detail pages.
		if (catalog != null) {
			body = TableLinkTransformer.transform(body, buildNameToSlug(catalog), catalog.linkPrefix());
		}
		String docPath = lang + "/" + replaceExtension(page.file());
		HtmlTemplate.PageContext ctx = new HtmlTemplate.PageContext(nav, lang, page.title(), docPath, page.file(),
				docPath, body, null, prev, next, languageList);
		writePage(docPath, HtmlTemplate.render(ctx));
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
			HtmlTemplate.PageContext ctx = new HtmlTemplate.PageContext(nav, lang, entry.name(), docPath,
					catalog.mdFile(entry), indexDocPath, renderBody(Files.readString(mdPath, StandardCharsets.UTF_8)),
					backlink, prev, next, languageList);
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
	 * Maps each individual operator name (HTML-escaped, as it appears in the
	 * rendered table) to its page slug. A grouped entry like {@code char schar}
	 * maps every token to the same page.
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

	private String renderBody(String markdown) {
		String body = this.renderer.render(this.parser.parse(markdown));
		body = rewriteMarkdownLinks(body);
		return RunnableBlockTransformer.transform(body);
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
			String anchor = matcher.group(2) == null ? "" : matcher.group(2);
			matcher.appendReplacement(out, Matcher.quoteReplacement("href=\"" + matcher.group(1) + ".html" + anchor + "\""));
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
