package am.ik.rontolisp.docgen;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rewrites rendered {@code lisp} and {@code scheme} code blocks into interactive,
 * runnable cells.
 *
 * <p>
 * flexmark renders a fenced {@code ```lisp} block as
 * {@code <pre><code class="language-lisp">...</code></pre>}. This transformer replaces
 * each such block with an editable {@code <textarea>} plus a Run button and an output
 * area, wired up by {@code docs.js} to the playground runtime. The cell's
 * {@code data-lang} says which language it reads; the semantics follow
 * {@code DocExamplesTest}, which checks every block:
 * <ul>
 * <li>A {@code lisp} cell runs in the page's one shared interpreter ({@code rontoEval}):
 * a definition in an earlier cell is visible to later ones.</li>
 * <li>A {@code scheme} cell is a whole program on a fresh interpreter, or -- carrying a
 * {@code ; =>} annotation -- a REPL session of its own. The {@code ```stdin} block right
 * before it is its standard input, carried in the cell as a hidden
 * {@code textarea.cell-stdin}. A {@code scheme} block whose first line is
 * {@code ; file: NAME} is a file the page's other Scheme blocks read, not a program: it
 * stays static, and {@code docs.js} hands it to the runtime as that file.</li>
 * </ul>
 *
 * <p>
 * A block that looks like a REPL transcript (it contains a line starting with the
 * {@code >} prompt, e.g. the ratio examples) is left as a static, syntax-styled block,
 * because its text is interleaved input/output rather than an evaluable program. Other
 * blocks (bash, console, plain text) are never touched.
 */
public final class RunnableBlockTransformer {

	// Every rendered fence, in page order: the stdin rule needs the block BEFORE a
	// scheme block, whatever language that is.
	private static final Pattern BLOCK = Pattern
		.compile("<pre><code(?: class=\"language-([^\"]*)\")?>(.*?)</code></pre>", Pattern.DOTALL);

	private static final Pattern FILE_BLOCK = Pattern.compile("\\A;+ file: \\S+");

	private RunnableBlockTransformer() {
	}

	/**
	 * Replaces runnable {@code lisp} and {@code scheme} blocks in {@code html} with
	 * cells.
	 */
	public static String transform(String html) {
		Matcher matcher = BLOCK.matcher(html);
		StringBuilder out = new StringBuilder();
		String stdin = null;
		while (matcher.find()) {
			String language = matcher.group(1) == null ? "" : matcher.group(1);
			String escapedCode = matcher.group(2);
			String replacement = matcher.group();
			if (language.equals("lisp") && !isTranscript(escapedCode)) {
				replacement = runnableCell(escapedCode, "lisp", null);
			}
			else if (language.equals("scheme") && !isTranscript(escapedCode)
					&& !FILE_BLOCK.matcher(escapedCode).lookingAt()) {
				replacement = runnableCell(escapedCode, "scheme", stdin);
			}
			// A stdin block feeds the ONE block after it; every other block reads none.
			stdin = language.equals("stdin") ? escapedCode : null;
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

	private static String runnableCell(String escapedCode, String language, String escapedStdin) {
		String code = stripTrailingNewline(escapedCode);
		int rows = Math.max(1, countLines(code));
		String stdin = escapedStdin == null ? ""
				: "<textarea class=\"cell-stdin\" hidden>%s</textarea>".formatted(escapedStdin);
		return """
				<div class="code-cell" data-lang="%s">\
				<div class="cell-toolbar"><button class="run" type="button">Run</button>\
				<span class="cell-status"></span></div>\
				<textarea class="cell-src" spellcheck="false" wrap="off" rows="%d">%s</textarea>\
				%s<pre class="cell-out" hidden></pre>\
				</div>""".formatted(language, rows, code, stdin);
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
