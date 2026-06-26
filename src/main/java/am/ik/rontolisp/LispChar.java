package am.ik.rontolisp;

/**
 * A character value (Common Lisp {@code character}).
 *
 * <p>
 * The value is stored as a Unicode code point. The printed representation is the
 * {@code #\c} reader syntax (with the standard names {@code #\Space}, {@code #\Newline},
 * {@code #\Tab}, {@code #\Return}, {@code #\Page}, {@code #\Backspace}, {@code #\Rubout},
 * {@code #\Nul} for the non-graphic characters); {@link #display()} returns the bare
 * character so {@code princ} prints the glyph.
 *
 * @param codePoint the Unicode code point of the character
 */
public record LispChar(int codePoint) implements LispVal {

	@Override
	public String print() {
		return "#\\" + name(this.codePoint);
	}

	@Override
	public String display() {
		return new String(Character.toChars(this.codePoint));
	}

	/**
	 * Returns the printed name of a character code: a standard name for the common
	 * non-graphic characters, the bare glyph for a graphic character, and the glyph as a
	 * fallback otherwise.
	 * @param codePoint the Unicode code point
	 * @return the name used in the {@code #\name} printed representation
	 */
	public static String name(int codePoint) {
		return switch (codePoint) {
			case ' ' -> "Space";
			case '\n' -> "Newline";
			case '\t' -> "Tab";
			case '\r' -> "Return";
			case '\f' -> "Page";
			case '\b' -> "Backspace";
			case 0 -> "Nul";
			case 127 -> "Rubout";
			default -> new String(Character.toChars(codePoint));
		};
	}

}
