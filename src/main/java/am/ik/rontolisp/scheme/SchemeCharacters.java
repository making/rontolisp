package am.ik.rontolisp.scheme;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.IntPredicate;

import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.reader.LispReader;
import org.jspecify.annotations.Nullable;

/**
 * The Unicode data behind {@code (scheme char)}, taken from the JDK -- the same source
 * every backend's {@code char-upcase} / {@code char-downcase} table comes from, so the
 * answers agree on all four backends -- and handed to {@code scheme.lisp} as generated
 * definitions ({@link Scheme#runtimeForms}): one range table per property the Common Lisp
 * character functions do not answer uniformly, and the unconditional multi-character
 * uppercase mappings of {@code SpecialCasing.txt}. The helpers that read them are Common
 * Lisp source, pruned with everything else a program does not reach.
 *
 * <p>
 * A range table is a string of inclusive {@code [from, to]} code-point pairs in ascending
 * order, each bound four characters of base 64 ({@link #encode}) -- one literal constant
 * on every backend, where a vector literal of 1,500 integers would be code on the JVM --
 * decoded into a simple vector the first time a helper asks for it and kept in a
 * variable: the binary search then reads integers, not four characters per bound
 * ({@code .kb/scheme-frontend.md}, "{@code (scheme char)}", has the measurement).
 *
 * <p>
 * Case folding is spelled twice, here ({@link #foldcase(String)}, for the
 * {@code #!fold-case} directive of {@link SchemeReader}) and in {@code scheme.lisp}
 * ({@code %scheme-char-foldcase} / {@code %scheme-string-foldcase}); change the two
 * together. {@code SchemeCharactersTest} pins them to each other.
 */
final class SchemeCharacters {

	/** The first capital Cherokee letter: Cherokee folds to its CAPITALS (Unicode 8). */
	static final int CHEROKEE_FIRST = 0x13A0;

	/** The last capital Cherokee letter. */
	static final int CHEROKEE_LAST = 0x13F5;

	private static final List<LispVal> RUNTIME_FORMS = buildRuntimeForms();

	private SchemeCharacters() {
	}

	/**
	 * Unicode simple case folding of one code point ({@code char-foldcase}): the
	 * lowercase of the uppercase, except that the dotted and dotless i have no folding
	 * and Cherokee folds to its capitals.
	 * @param codePoint the code point
	 * @return its simple case folding
	 */
	static int foldcase(int codePoint) {
		if (codePoint < 0x80) {
			return Character.toLowerCase(codePoint);
		}
		if (codePoint == 0x130 || codePoint == 0x131) {
			return codePoint;
		}
		int upper = Character.toUpperCase(codePoint);
		return upper >= CHEROKEE_FIRST && upper <= CHEROKEE_LAST ? upper : Character.toLowerCase(upper);
	}

	/**
	 * Unicode full case folding of a string ({@code string-foldcase}, and what
	 * {@code #!fold-case} applies to an identifier): the simple folding of each character
	 * of the character's full uppercase, except for three characters that full folding
	 * treats differently -- U+0130 folds to {@code i} and a combining dot, U+0131 not at
	 * all, U+1E9E to {@code ss}.
	 * @param string the string
	 * @return its full case folding
	 */
	static String foldcase(String string) {
		StringBuilder folded = new StringBuilder(string.length());
		string.codePoints().forEach(codePoint -> {
			switch (codePoint) {
				case 0x130 -> folded.append("i\u0307");
				case 0x131 -> folded.appendCodePoint(codePoint);
				case 0x1E9E -> folded.append("ss");
				default -> {
					String special = specialUpcase(codePoint);
					if (special == null) {
						folded.appendCodePoint(foldcase(codePoint));
					}
					else {
						special.codePoints().forEach(upper -> folded.appendCodePoint(foldcase(upper)));
					}
				}
			}
		});
		return folded.toString();
	}

	/**
	 * The full uppercase mapping of a code point when it is not the simple one: the
	 * unconditional multi-character mappings of {@code SpecialCasing.txt} ({@code ß} to
	 * {@code SS}).
	 * @param codePoint the code point
	 * @return the mapping, or {@code null} when the simple mapping is the full one
	 */
	static @Nullable String specialUpcase(int codePoint) {
		if (codePoint < 0xDF) {
			return null;
		}
		String full = Character.toString(codePoint).toUpperCase(Locale.ROOT);
		return full.equals(Character.toString(Character.toUpperCase(codePoint))) ? null : full;
	}

	/**
	 * Whether a code point has the Unicode {@code White_Space} property. Stable since
	 * Unicode 6.3; spelled again, by value, in {@code %scheme-char-whitespace?}.
	 * @param codePoint the code point
	 * @return {@code true} for a whitespace character
	 */
	static boolean isWhiteSpace(int codePoint) {
		return codePoint >= 0x9 && codePoint <= 0xD || codePoint == 0x85
				|| Character.getType(codePoint) == Character.SPACE_SEPARATOR
				|| Character.getType(codePoint) == Character.LINE_SEPARATOR
				|| Character.getType(codePoint) == Character.PARAGRAPH_SEPARATOR;
	}

	/**
	 * Whether a code point is {@code Case_Ignorable} (Unicode 3.13): what the final-sigma
	 * condition of {@code string-downcase} looks through.
	 * @param codePoint the code point
	 * @return {@code true} for a case-ignorable character
	 */
	static boolean isCaseIgnorable(int codePoint) {
		return switch (Character.getType(codePoint)) {
			case Character.NON_SPACING_MARK, Character.ENCLOSING_MARK, Character.FORMAT, Character.MODIFIER_LETTER,
					Character.MODIFIER_SYMBOL ->
				true;
			// Word_Break MidLetter, MidNumLet and Single_Quote.
			default -> switch (codePoint) {
				case 0x27, 0x2E, 0x3A, 0xB7, 0x387, 0x55F, 0x5F4, 0x2018, 0x2019, 0x2024, 0xFE13, 0xFE52, 0xFE55,
						0xFF07, 0xFF0E, 0xFF1A ->
					true;
				default -> false;
			};
		};
	}

	/**
	 * The definitions {@code scheme.lisp}'s {@code (scheme char)} helpers read.
	 * @return the forms, in the library's canonical shape
	 */
	static List<LispVal> runtimeForms() {
		return RUNTIME_FORMS;
	}

	private static List<LispVal> buildRuntimeForms() {
		StringBuilder source = new StringBuilder();
		table(source, "alphabetic-ranges", ranges(Character::isAlphabetic));
		table(source, "decimal-ranges",
				ranges(codePoint -> Character.getType(codePoint) == Character.DECIMAL_DIGIT_NUMBER));
		// Where the Unicode Uppercase / Lowercase property disagrees with "has a
		// lowercase / uppercase mapping", which is what the helpers test first.
		table(source, "uppercase-exceptions", ranges(
				codePoint -> Character.isUpperCase(codePoint) != (Character.toLowerCase(codePoint) != codePoint)));
		table(source, "lowercase-exceptions", ranges(
				codePoint -> Character.isLowerCase(codePoint) != (Character.toUpperCase(codePoint) != codePoint)));
		table(source, "case-ignorable-ranges", ranges(SchemeCharacters::isCaseIgnorable));
		// The special uppercase mappings: their code points as one-point ranges, the
		// mappings a vector in the same order.
		StringBuilder keys = new StringBuilder();
		StringBuilder values = new StringBuilder();
		for (int codePoint = 0; codePoint <= Character.MAX_CODE_POINT; codePoint++) {
			String special = specialUpcase(codePoint);
			if (special != null) {
				encode(keys, codePoint);
				encode(keys, codePoint);
				values.append(' ').append(new LispString(special).print());
			}
		}
		table(source, "special-upcase-keys", keys.toString());
		source.append("(defun rontolisp::%scheme-special-upcase (code)\n")
			.append("  (if (< code 223) nil\n")
			.append("      (let ((i (rontolisp::%scheme-range-index code (rontolisp::%scheme-special-upcase-keys))))\n")
			.append("        (if i (svref '#(")
			.append(values.substring(1))
			.append(") i) nil))))\n");
		return List.copyOf(LispReader.readAllFromString(source.toString(), Features.INTERPRETER));
	}

	// A table decoded once, on first use, into a simple vector of the bounds:
	// (defvar ...-cache nil) (defun ... () (or ...-cache (setq ...-cache (decode
	// "<table>"))))
	private static void table(StringBuilder source, String name, String encoded) {
		String function = "rontolisp::%scheme-" + name;
		String cache = function + "-cache";
		source.append("(defvar ").append(cache).append(" nil)\n");
		source.append("(defun ").append(function).append(" ()\n");
		source.append("  (or ").append(cache).append(" (setq ").append(cache);
		source.append(" (rontolisp::%scheme-decode-ranges \"").append(encoded).append("\"))))\n");
	}

	/**
	 * The range table of a property, encoded.
	 * @param property the property
	 * @return the table
	 */
	static String ranges(IntPredicate property) {
		StringBuilder table = new StringBuilder();
		int from = -1;
		for (int codePoint = 0; codePoint <= Character.MAX_CODE_POINT + 1; codePoint++) {
			boolean in = codePoint <= Character.MAX_CODE_POINT && property.test(codePoint);
			if (in && from < 0) {
				from = codePoint;
			}
			else if (!in && from >= 0) {
				encode(table, from);
				encode(table, codePoint - 1);
				from = -1;
			}
		}
		return table.toString();
	}

	// Four base-64 digits, most significant first; digit d is the character 48 + d,
	// skipping the backslash (92), so the table never needs an escape.
	private static void encode(StringBuilder table, int codePoint) {
		for (int shift = 18; shift >= 0; shift -= 6) {
			int digit = (codePoint >> shift) & 63;
			table.append((char) (digit < 44 ? 48 + digit : 49 + digit));
		}
	}

}
