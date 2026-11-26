package am.ik.rontolisp.starlark;

import java.util.HashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * One token of Starlark source, as {@link StarlarkLexer} produces it.
 *
 * @param kind what the token is
 * @param value the literal's value -- a {@code String} for an identifier or a string, a
 * {@code byte[]} for bytes, a {@code BigInteger} for an int, a {@code Double} for a float
 * -- or {@code null} for every other kind
 * @param line the 1-based line of the token's first character
 * @param column the 1-based column of the token's first character
 */
record StarlarkToken(Kind kind, @Nullable Object value, int line, int column) {

	/** Token kinds; a keyword or punctuation kind carries its spelling. */
	enum Kind {

		IDENTIFIER("identifier"), INT("int literal"), FLOAT("float literal"), STRING("string literal"),
		BYTES("bytes literal"), NEWLINE("newline"), INDENT("indent"), OUTDENT("outdent"), EOF("end of file"),

		AND("and"), BREAK("break"), CONTINUE("continue"), DEF("def"), ELIF("elif"), ELSE("else"), FOR("for"), IF("if"),
		IN("in"), LAMBDA("lambda"), LOAD("load"), NOT("not"), OR("or"), PASS("pass"), RETURN("return"),

		PLUS("+"), MINUS("-"), STAR("*"), SLASH("/"), SLASH_SLASH("//"), PERCENT("%"), STAR_STAR("**"), TILDE("~"),
		AMPERSAND("&"), PIPE("|"), CARET("^"), LESS_LESS("<<"), GREATER_GREATER(">>"), DOT("."), COMMA(","),
		EQUALS("="), SEMICOLON(";"), COLON(":"), LEFT_PAREN("("), RIGHT_PAREN(")"), LEFT_BRACKET("["),
		RIGHT_BRACKET("]"), LEFT_BRACE("{"), RIGHT_BRACE("}"), LESS("<"), GREATER(">"), GREATER_EQUALS(">="),
		LESS_EQUALS("<="), EQUALS_EQUALS("=="), NOT_EQUALS("!="), PLUS_EQUALS("+="), MINUS_EQUALS("-="),
		STAR_EQUALS("*="), SLASH_EQUALS("/="), SLASH_SLASH_EQUALS("//="), PERCENT_EQUALS("%="), AMPERSAND_EQUALS("&="),
		PIPE_EQUALS("|="), CARET_EQUALS("^="), LESS_LESS_EQUALS("<<="), GREATER_GREATER_EQUALS(">>=");

		private static final Map<String, Kind> KEYWORDS = new HashMap<>();

		private static final Map<String, Kind> PUNCTUATION = new HashMap<>();

		static {
			for (Kind kind : values()) {
				if (kind.ordinal() >= AND.ordinal() && kind.ordinal() <= RETURN.ordinal()) {
					KEYWORDS.put(kind.spelling, kind);
				}
				else if (kind.ordinal() >= PLUS.ordinal()) {
					PUNCTUATION.put(kind.spelling, kind);
				}
			}
		}

		private final String spelling;

		Kind(String spelling) {
			this.spelling = spelling;
		}

		/**
		 * The keyword spelled {@code word}.
		 * @param word an identifier-shaped word
		 * @return the keyword kind, or {@code null} when the word is no keyword
		 */
		static @Nullable Kind keyword(String word) {
			return KEYWORDS.get(word);
		}

		/**
		 * The punctuation spelled {@code text}.
		 * @param text one to three characters
		 * @return the punctuation kind, or {@code null} when nothing is spelled so
		 */
		static @Nullable Kind punctuation(String text) {
			return PUNCTUATION.get(text);
		}

		/**
		 * How an error message names the kind: the quoted spelling of a keyword or
		 * punctuation, a description of any other kind.
		 * @return the description
		 */
		String describe() {
			return this.ordinal() >= AND.ordinal() ? "'" + this.spelling + "'" : this.spelling;
		}

	}

}
