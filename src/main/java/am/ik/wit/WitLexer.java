package am.ik.wit;

import java.util.ArrayList;
import java.util.List;

/**
 * Lexer for the WIT (WebAssembly Interface Types) textual format.
 *
 * <p>
 * The lexer is <em>lossless</em>: every character of the input ends up either in a
 * token's {@link WitToken#text()} or in the {@link WitToken#trivia()} of the following
 * token (the final {@link WitToken.Kind#EOF} token carries the trailing trivia), so
 * concatenating {@code trivia + text} over the token list reproduces the input
 * byte-for-byte. Comments ({@code //}, {@code ///}, {@code /*}) are trivia; doc comments
 * are recovered from the trivia by {@link WitParser}.
 */
public final class WitLexer {

	private WitLexer() {
	}

	/**
	 * Splits a WIT source text into tokens.
	 * @param source the WIT text
	 * @return the tokens in source order, ending with a {@link WitToken.Kind#EOF} token
	 * @throws WitParseException on an unexpected character or an unterminated block
	 * comment
	 */
	public static List<WitToken> lex(String source) {
		List<WitToken> tokens = new ArrayList<>();
		int i = 0;
		int n = source.length();
		while (true) {
			int triviaStart = i;
			while (i < n) {
				char c = source.charAt(i);
				if (Character.isWhitespace(c)) {
					i++;
				}
				else if (c == '/' && i + 1 < n && source.charAt(i + 1) == '/') {
					while (i < n && source.charAt(i) != '\n') {
						i++;
					}
				}
				else if (c == '/' && i + 1 < n && source.charAt(i + 1) == '*') {
					int end = source.indexOf("*/", i + 2);
					if (end < 0) {
						throw new WitParseException("Unterminated block comment", source, i);
					}
					i = end + 2;
				}
				else {
					break;
				}
			}
			String trivia = source.substring(triviaStart, i);
			if (i >= n) {
				tokens.add(new WitToken(WitToken.Kind.EOF, "", trivia, i));
				return tokens;
			}
			int start = i;
			char c = source.charAt(i);
			if (c == '%' || c == '_' || Character.isLetter(c)) {
				i = scanWord(source, i);
				tokens.add(new WitToken(WitToken.Kind.WORD, source.substring(start, i), trivia, start));
			}
			else if (Character.isDigit(c)) {
				i = scanVersion(source, i);
				tokens.add(new WitToken(WitToken.Kind.VERSION, source.substring(start, i), trivia, start));
			}
			else if (c == '-' && i + 1 < n && source.charAt(i + 1) == '>') {
				i += 2;
				tokens.add(new WitToken(WitToken.Kind.PUNCT, "->", trivia, start));
			}
			else if ("{}()<>,;:=.@/*".indexOf(c) >= 0) {
				i++;
				tokens.add(new WitToken(WitToken.Kind.PUNCT, String.valueOf(c), trivia, start));
			}
			else {
				throw new WitParseException("Unexpected character '" + c + "'", source, i);
			}
		}
	}

	// A word is kebab-case: alphanumeric runs joined by single '-', optionally
	// %-escaped ("%flags") or the bare "_" placeholder. A '-' is consumed only when
	// followed by an alphanumeric so "a -> b" never glues.
	private static int scanWord(String source, int i) {
		int n = source.length();
		if (source.charAt(i) == '%') {
			i++;
		}
		if (source.charAt(i) == '_') {
			return i + 1;
		}
		while (i < n) {
			char c = source.charAt(i);
			if (Character.isLetterOrDigit(c)) {
				i++;
			}
			else if (c == '-' && i + 1 < n && Character.isLetterOrDigit(source.charAt(i + 1))) {
				i++;
			}
			else {
				break;
			}
		}
		return i;
	}

	// A version is a digit-led semver-ish run: alphanumerics plus '+', with '.' and
	// '-' consumed only when followed by an alphanumeric -- so "@0.2.0.{duration}"
	// stops the version before ".{" and "0.2.0-rc-2023-11-10" stays one token.
	private static int scanVersion(String source, int i) {
		int n = source.length();
		while (i < n) {
			char c = source.charAt(i);
			if (Character.isLetterOrDigit(c) || c == '+') {
				i++;
			}
			else if ((c == '.' || c == '-') && i + 1 < n && Character.isLetterOrDigit(source.charAt(i + 1))) {
				i++;
			}
			else {
				break;
			}
		}
		return i;
	}

}
