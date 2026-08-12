package am.ik.rontolisp.docgen;

import java.nio.file.Path;
import java.util.List;

/**
 * Renders the full HTML page shell around a rendered Markdown body: the top bar (site
 * title, playground link, language switcher, runtime status, reset), the left sidebar
 * navigation, the content, and previous/next links. The dark theme lives in
 * {@code assets/docs.css}; the runnable-cell wiring in {@code assets/docs.js}.
 */
public final class HtmlTemplate {

	private HtmlTemplate() {
	}

	/**
	 * A language available on the site, used to render the language switcher.
	 *
	 * @param code the directory/URL code (e.g. {@code en})
	 * @param name the display name (e.g. {@code English})
	 */
	public record Language(String code, String name) {
	}

	/**
	 * A navigation target: the docs-root-relative path of an HTML page plus its display
	 * title. Used for previous/next links and the back link.
	 */
	public record Crumb(String docPath, String title) {
	}

	/**
	 * Everything needed to render one page.
	 *
	 * @param nav the sidebar navigation model for this language
	 * @param lang the language code
	 * @param title the page title (used in {@code <title>} and breadcrumbs)
	 * @param currentDocPath docs-root-relative path of this page's HTML output
	 * @param currentMdFile language-relative Markdown path (drives the language
	 * switcher's same-page link), or {@code null} to omit it
	 * @param activeNavDocPath docs-root-relative path of the sidebar entry to highlight
	 * (may differ from {@code currentDocPath} for pages, such as the per-function pages,
	 * that are not themselves sidebar entries)
	 * @param bodyHtml the rendered Markdown body
	 * @param tocHtml the right-hand "On this page" table of contents (an
	 * {@code <aside class="toc">...} block), or an empty string to omit it
	 * @param backlink an optional link shown above the content (e.g. "back to the
	 * function index"), or {@code null}
	 * @param prev the previous-page link, or {@code null}
	 * @param next the next-page link, or {@code null}
	 * @param languages all available languages for the switcher
	 */
	public record PageContext(Nav nav, String lang, String title, String currentDocPath, String currentMdFile,
			String activeNavDocPath, String bodyHtml, String tocHtml, Crumb backlink, Crumb prev, Crumb next,
			List<Language> languages) {
	}

	/** Renders the complete HTML document for one page. */
	public static String render(PageContext ctx) {
		String assetsCss = rel(ctx.currentDocPath(), "assets/docs.css");
		String assetsJs = rel(ctx.currentDocPath(), "assets/docs.js");
		String runtimeSrc = runtimeSrc(ctx.currentDocPath());
		String homeHref = rel(ctx.currentDocPath(), ctx.lang() + "/index.html");

		StringBuilder html = new StringBuilder();
		html.append("<!DOCTYPE html>\n<html lang=\"").append(esc(ctx.lang())).append("\">\n<head>\n");
		html.append("<meta charset=\"UTF-8\">\n");
		html.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n");
		html.append("<title>")
			.append(esc(ctx.title()))
			.append(" &middot; ")
			.append(esc(ctx.nav().title()))
			.append("</title>\n");
		html.append("<link rel=\"stylesheet\" href=\"").append(assetsCss).append("\">\n");
		html.append("</head>\n");
		html.append("<body data-runtime-src=\"").append(runtimeSrc).append("\">\n");

		appendTopbar(html, ctx, homeHref);

		html.append("<div class=\"layout\">\n");
		appendSidebar(html, ctx);
		html.append("<main class=\"content\">\n");
		if (ctx.backlink() != null) {
			html.append("<a class=\"backlink\" href=\"")
				.append(rel(ctx.currentDocPath(), ctx.backlink().docPath()))
				.append("\">&larr; ")
				.append(esc(ctx.backlink().title()))
				.append("</a>\n");
		}
		html.append("<article class=\"markdown\">\n").append(ctx.bodyHtml()).append("\n</article>\n");
		appendPrevNext(html, ctx);
		html.append("</main>\n");
		if (ctx.tocHtml() != null && !ctx.tocHtml().isEmpty()) {
			html.append(ctx.tocHtml());
		}
		html.append("</div>\n");

		html.append("<script src=\"").append(assetsJs).append("\"></script>\n");
		html.append("</body>\n</html>\n");
		return html.toString();
	}

	private static void appendTopbar(StringBuilder html, PageContext ctx, String homeHref) {
		html.append("<header class=\"topbar\">\n");
		html.append("<button type=\"button\" class=\"nav-toggle\" aria-label=\"Toggle navigation\"")
			.append(" aria-expanded=\"false\" aria-controls=\"sidebar\">\n")
			.append("<span class=\"nav-toggle-bar\"></span>")
			.append("<span class=\"nav-toggle-bar\"></span>")
			.append("<span class=\"nav-toggle-bar\"></span>")
			.append("</button>\n");
		html.append("<a class=\"brand\" href=\"")
			.append(homeHref)
			.append("\"><span class=\"paren\">(</span>")
			.append(esc(ctx.nav().title()))
			.append("<span class=\"paren\">)</span> docs</a>\n");
		html.append("<nav class=\"topnav\">\n");
		// The playground lives one level above the docs root.
		String playgroundHref = runtimeSrc(ctx.currentDocPath()).replace("rontoplayground.js", "playground.html");
		html.append("<a href=\"").append(playgroundHref).append("\">Playground</a>\n");
		html.append("<a href=\"https://github.com/making/rontolisp\" target=\"_blank\" rel=\"noopener\">GitHub</a>\n");
		appendLanguageSwitcher(html, ctx);
		html.append(
				"<button type=\"button\" class=\"reset-runtime\" title=\"Reset the in-page runtime\">Reset runtime</button>\n");
		html.append("<span class=\"runtime-status\" data-state=\"idle\"></span>\n");
		html.append("</nav>\n");
		html.append("</header>\n");
	}

	private static void appendLanguageSwitcher(StringBuilder html, PageContext ctx) {
		if (ctx.languages().size() < 2 || ctx.currentMdFile() == null) {
			return;
		}
		html.append("<span class=\"langswitch\">");
		boolean first = true;
		for (Language language : ctx.languages()) {
			if (!first) {
				html.append(" ");
			}
			first = false;
			String target = language.code() + "/" + replaceExtension(ctx.currentMdFile());
			if (language.code().equals(ctx.lang())) {
				html.append("<span class=\"lang active\">").append(esc(language.name())).append("</span>");
			}
			else {
				html.append("<a class=\"lang\" href=\"")
					.append(rel(ctx.currentDocPath(), target))
					.append("\">")
					.append(esc(language.name()))
					.append("</a>");
			}
		}
		html.append("</span>\n");
	}

	private static void appendSidebar(StringBuilder html, PageContext ctx) {
		html.append("<div class=\"sidebar-backdrop\" hidden></div>\n");
		html.append("<aside class=\"sidebar\" id=\"sidebar\">\n<nav>\n");
		for (Nav.Section section : ctx.nav().sections()) {
			html.append("<div class=\"nav-section\">\n");
			html.append("<div class=\"nav-section-title\">").append(esc(section.title())).append("</div>\n");
			html.append("<ul>\n");
			for (Nav.Page page : section.pages()) {
				String docPath = ctx.lang() + "/" + replaceExtension(page.file());
				boolean active = docPath.equals(ctx.activeNavDocPath());
				String href = rel(ctx.currentDocPath(), docPath);
				html.append("<li><a class=\"nav-link")
					.append(active ? " active" : "")
					.append("\" href=\"")
					.append(href)
					.append("\">")
					.append(esc(page.title()))
					.append("</a></li>\n");
			}
			html.append("</ul>\n</div>\n");
		}
		html.append("</nav>\n</aside>\n");
	}

	private static void appendPrevNext(StringBuilder html, PageContext ctx) {
		if (ctx.prev() == null && ctx.next() == null) {
			return;
		}
		html.append("<nav class=\"prevnext\">\n");
		if (ctx.prev() != null) {
			html.append("<a class=\"prev\" href=\"")
				.append(rel(ctx.currentDocPath(), ctx.prev().docPath()))
				.append("\"><span class=\"dir\">&larr; Previous</span><span class=\"label\">")
				.append(esc(ctx.prev().title()))
				.append("</span></a>\n");
		}
		else {
			html.append("<span></span>\n");
		}
		if (ctx.next() != null) {
			html.append("<a class=\"next\" href=\"")
				.append(rel(ctx.currentDocPath(), ctx.next().docPath()))
				.append("\"><span class=\"dir\">Next &rarr;</span><span class=\"label\">")
				.append(esc(ctx.next().title()))
				.append("</span></a>\n");
		}
		html.append("</nav>\n");
	}

	/** Computes a relative URL from one docs-root-relative path to another. */
	static String rel(String fromDocPath, String toDocPath) {
		Path from = Path.of(fromDocPath).getParent();
		Path to = Path.of(toDocPath);
		Path relative = (from == null) ? to : from.relativize(to);
		String result = relative.toString().replace('\\', '/');
		return result.isEmpty() ? "." : result;
	}

	/**
	 * Computes the URL of the playground runtime ({@code rontoplayground.js}), which sits
	 * one directory above the docs root.
	 */
	static String runtimeSrc(String fromDocPath) {
		int depth = 0;
		for (int i = 0; i < fromDocPath.length(); i++) {
			if (fromDocPath.charAt(i) == '/') {
				depth++;
			}
		}
		return "../".repeat(depth + 1) + "rontoplayground.js";
	}

	static String replaceExtension(String mdFile) {
		return mdFile.endsWith(".md") ? mdFile.substring(0, mdFile.length() - 3) + ".html" : mdFile;
	}

	static String esc(String s) {
		StringBuilder sb = new StringBuilder(s.length());
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			switch (c) {
				case '&' -> sb.append("&amp;");
				case '<' -> sb.append("&lt;");
				case '>' -> sb.append("&gt;");
				case '"' -> sb.append("&quot;");
				default -> sb.append(c);
			}
		}
		return sb.toString();
	}

}
