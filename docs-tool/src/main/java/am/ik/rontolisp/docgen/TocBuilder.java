package am.ik.rontolisp.docgen;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

// @formatter:off
/**
 * Builds the right-hand "On this page" table of contents from a rendered
 * Markdown body. It collects the {@code <h2>}/{@code <h3>} headings (each already
 * carries an {@code id} from flexmark's header-id generation) and renders them as
 * a nested list of intra-page anchor links. The page's {@code <h1>} (its title)
 * is intentionally excluded.
 *
 * <p>
 * Returns an empty string when a page has fewer than two such headings, so short
 * pages get no TOC column.
 */
public final class TocBuilder {
	// @formatter:on

	private TocBuilder() {
	}

	// Matches <h2 ... id="anchor" ...>label</h2> and the h3 equivalent. The id may
	// sit among other attributes, and the label may contain inline markup (<code>).
	private static final Pattern HEADING = Pattern.compile("<h([23])\\b[^>]*\\bid=\"([^\"]*)\"[^>]*>(.*?)</h\\1>",
			Pattern.DOTALL);

	/** Renders the TOC {@code <aside>} for a body, or {@code ""} if it is too short. */
	public static String build(String bodyHtml) {
		Matcher matcher = HEADING.matcher(bodyHtml);
		StringBuilder items = new StringBuilder();
		int count = 0;
		while (matcher.find()) {
			String level = matcher.group(1);
			String id = matcher.group(2);
			String label = matcher.group(3).trim();
			items.append("<li class=\"toc-h")
				.append(level)
				.append("\"><a href=\"#")
				.append(id)
				.append("\">")
				.append(label)
				.append("</a></li>\n");
			count++;
		}
		if (count < 2) {
			return "";
		}
		return "<aside class=\"toc\">\n<div class=\"toc-title\">On this page</div>\n<nav>\n<ul>\n" + items
				+ "</ul>\n</nav>\n</aside>\n";
	}

}
