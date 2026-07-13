package am.ik.wit;

/**
 * One lexical token of a WIT source text, carrying the <em>trivia</em> (whitespace and
 * comments) that precede it verbatim, so that joining every token's {@code trivia + text}
 * reproduces the input byte-for-byte (the round-trip guarantee
 * {@link WitPrinter#printVerbatim(java.util.List)} builds on).
 *
 * @param kind the token kind
 * @param text the token text exactly as it appears in the source (for {@link Kind#EOF}
 * this is empty)
 * @param trivia the whitespace and comment characters between the previous token and this
 * one, verbatim (doc comments ride here; {@link WitParser} extracts them)
 * @param offset the index of the first character of {@code text} in the source
 */
public record WitToken(Kind kind, String text, String trivia, int offset) {

	/**
	 * The lexical category of a {@link WitToken}.
	 */
	public enum Kind {

		/**
		 * An identifier or keyword: kebab-case words (possibly {@code %}-escaped, e.g.
		 * {@code %flags}) and the bare {@code _} placeholder. WIT keywords are not
		 * distinguished lexically; the parser decides by context.
		 */
		WORD,

		/**
		 * A version-like number starting with a digit, e.g. {@code 0.3.0} or
		 * {@code 0.2.0-rc-2023-11-10}.
		 */
		VERSION,

		/**
		 * Punctuation: one of <code>{ } ( ) &lt; &gt; , ; : = . @ / *</code> or the
		 * two-character arrow {@code ->}.
		 */
		PUNCT,

		/**
		 * The end of the source; its {@link WitToken#trivia()} holds any trailing
		 * whitespace and comments.
		 */
		EOF

	}

	/**
	 * Whether this token is the given punctuation.
	 * @param punct the punctuation text, e.g. an opening brace or the arrow {@code ->}
	 * @return {@code true} when this token is {@link Kind#PUNCT} with exactly that text
	 */
	public boolean isPunct(String punct) {
		return this.kind == Kind.PUNCT && this.text.equals(punct);
	}

	/**
	 * Whether this token is the given word (identifier or contextual keyword).
	 * @param word the word text, e.g. {@code "interface"}
	 * @return {@code true} when this token is {@link Kind#WORD} with exactly that text
	 */
	public boolean isWord(String word) {
		return this.kind == Kind.WORD && this.text.equals(word);
	}

}
