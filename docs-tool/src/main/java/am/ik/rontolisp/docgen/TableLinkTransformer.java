package am.ik.rontolisp.docgen;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the operator names in the first column of a reference table (Built-in Functions,
 * Macros, or Special Forms) into links to their per-operator detail pages, so each table
 * doubles as the index of its detail pages.
 *
 * <p>
 * Only the first cell of each data row is rewritten (the operator name), never the
 * Example/Syntax/Result columns -- those mention names too, but as code rather than as
 * the row's subject. Names are matched in their HTML-escaped form (e.g. {@code &lt;} for
 * {@code <}).
 */
public final class TableLinkTransformer {

	// First data cell of a table row: <tr><td> ... </td>
	private static final Pattern FIRST_CELL = Pattern.compile("(<tr><td>)(.*?)(</td>)", Pattern.DOTALL);

	private static final Pattern CODE = Pattern.compile("<code>([^<]*)</code>");

	private TableLinkTransformer() {
	}

	/**
	 * Links operator names in {@code html}.
	 * @param html the rendered table page body
	 * @param escapedNameToSlug map from each HTML-escaped operator name to its page slug
	 * @param hrefPrefix the relative path prefix to the detail pages (e.g.
	 * {@code functions/})
	 */
	public static String transform(String html, Map<String, String> escapedNameToSlug, String hrefPrefix) {
		Matcher row = FIRST_CELL.matcher(html);
		StringBuilder out = new StringBuilder();
		while (row.find()) {
			String linkedCell = linkCodes(row.group(2), escapedNameToSlug, hrefPrefix);
			row.appendReplacement(out, Matcher.quoteReplacement(row.group(1) + linkedCell + row.group(3)));
		}
		row.appendTail(out);
		return out.toString();
	}

	private static String linkCodes(String cell, Map<String, String> escapedNameToSlug, String hrefPrefix) {
		Matcher code = CODE.matcher(cell);
		StringBuilder out = new StringBuilder();
		while (code.find()) {
			String token = code.group(1);
			String slug = escapedNameToSlug.get(token);
			String replacement = (slug == null) ? code.group()
					: "<a class=\"fn-link\" href=\"" + hrefPrefix + slug + ".html\"><code>" + token + "</code></a>";
			code.appendReplacement(out, Matcher.quoteReplacement(replacement));
		}
		code.appendTail(out);
		return out.toString();
	}

}
