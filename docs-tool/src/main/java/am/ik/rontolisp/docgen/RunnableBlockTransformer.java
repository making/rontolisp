package am.ik.rontolisp.docgen;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rewrites rendered {@code lisp} code blocks into interactive, runnable cells.
 *
 * <p>
 * flexmark renders a fenced {@code ```lisp} block as
 * {@code <pre><code class="language-lisp">...</code></pre>}. This transformer replaces
 * each such block with an editable {@code <textarea>} plus a Run button and an output
 * area, wired up by {@code docs.js} to the playground runtime
 * ({@code globalThis.rontoEval}).
 *
 * <p>
 * A {@code lisp} block that looks like a REPL transcript (it contains a line starting
 * with the {@code >} prompt, e.g. the ratio examples) is left as a static, syntax-styled
 * block, because its text is interleaved input/output rather than an evaluable program.
 * Non-{@code lisp} blocks (bash, console, plain text) are never touched.
 */
public final class RunnableBlockTransformer {

	private static final Pattern LISP_BLOCK = Pattern.compile("<pre><code class=\"language-lisp\">(.*?)</code></pre>",
			Pattern.DOTALL);

	private RunnableBlockTransformer() {
	}

	/** Replaces runnable {@code lisp} blocks in {@code html} with interactive cells. */
	public static String transform(String html) {
		Matcher matcher = LISP_BLOCK.matcher(html);
		StringBuilder out = new StringBuilder();
		while (matcher.find()) {
			String escapedCode = matcher.group(1);
			String replacement = isTranscript(escapedCode) ? matcher.group() : runnableCell(escapedCode);
			matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
		}
		matcher.appendTail(out);
		return out.toString();
	}

	/** A block is a transcript when any line begins with the {@code >} REPL prompt. */
	private static boolean isTranscript(String escapedCode) {
		for (String line : escapedCode.split("\n", -1)) {
			String trimmed = line.stripLeading();
			// '>' is HTML-escaped to "&gt;" in the rendered output.
			if (trimmed.startsWith("&gt;")) {
				return true;
			}
		}
		return false;
	}

	private static String runnableCell(String escapedCode) {
		String code = stripTrailingNewline(escapedCode);
		int rows = Math.max(1, countLines(code));
		return """
				<div class="code-cell">\
				<div class="cell-toolbar"><button class="run" type="button">Run</button>\
				<span class="cell-status"></span></div>\
				<textarea class="cell-src" spellcheck="false" wrap="off" rows="%d">%s</textarea>\
				<pre class="cell-out" hidden></pre>\
				</div>""".formatted(rows, code);
	}

	private static String stripTrailingNewline(String s) {
		int end = s.length();
		while (end > 0 && (s.charAt(end - 1) == '\n' || s.charAt(end - 1) == '\r')) {
			end--;
		}
		return s.substring(0, end);
	}

	private static int countLines(String s) {
		if (s.isEmpty()) {
			return 1;
		}
		int lines = 1;
		for (int i = 0; i < s.length(); i++) {
			if (s.charAt(i) == '\n') {
				lines++;
			}
		}
		return lines;
	}

}
