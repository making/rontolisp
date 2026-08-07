package am.ik.rontolisp.format;

/**
 * The whitespace facts about a node's position in the ORIGINAL source that the formatter
 * carries forward. Everything else about the original whitespace is discarded and
 * re-derived from the indent rules, so these two bits are the whole of what the author
 * still controls.
 *
 * @param blankLineBefore whether at least one blank line separated this node from the
 * previous sibling. Preserved (collapsed to exactly one blank line) because a blank line
 * is the only paragraph break Lisp source has; a form holding one can never be printed on
 * a single line.
 * @param startsLine whether this node was the first thing on its source line. Only
 * comments read it: a comment that started its line is an OWN-LINE comment and keeps a
 * line to itself, while one that followed code is a TRAILING comment and stays glued to
 * the end of that code's line.
 */
public record Trivia(boolean blankLineBefore, boolean startsLine) {

	/** A node that follows other content on the same source line. */
	public static final Trivia SAME_LINE = new Trivia(false, false);

	/** A node that starts its own source line, with no blank line before it. */
	public static final Trivia OWN_LINE = new Trivia(false, true);

}
