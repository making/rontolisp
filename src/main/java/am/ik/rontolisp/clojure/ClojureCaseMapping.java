package am.ik.rontolisp.clojure;

import java.util.List;
import java.util.Locale;
import java.util.function.IntFunction;

import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReader;
import org.jspecify.annotations.Nullable;

/**
 * The data behind the Clojure runtime's {@code String.toUpperCase} and
 * {@code String.toLowerCase} ({@code clojure.lisp}, "Case mapping"), taken from the JDK
 * and handed to the library as generated definitions ({@link #runtimeForms}): the
 * characters whose whole-string mapping is not the one-character mapping
 * {@code char-upcase} / {@code char-downcase} apply ({@code SpecialCasing.txt}'s
 * unconditional rows: {@code ß} to {@code SS}, U+0130 to {@code i} and a combining dot),
 * and the table the capital sigma's Final_Sigma test reads.
 *
 * <p>
 * The JDK's Final_Sigma test is not Unicode's: {@code ConditionalSpecialCasing} looks for
 * a cased character before the sigma and none after it within the sigma's WORD, as its
 * legacy word {@code BreakIterator} draws one -- letters and digits run together, a
 * mid-word punctuation mark joins two letters and a mid-number one two digits, marks
 * belong to the character before them and format characters are passed over -- and
 * "cased" is a general category test of its own. {@link #wordClass} is each code point's
 * part in that, and the table holds it run by run. Measured 2026-10-11 on JDK 25
 * ({@code .kb/clojure-frontend.md}, "Case mapping").
 */
public final class ClojureCaseMapping {

	/** A character no word rule joins: a word ends before and after it. */
	static final int OTHER = 0;

	/** A format character, which the word iterator passes over. */
	static final int IGNORED = 1;

	/** A mark: part of the letter or digit before it. */
	static final int MARK = 2;

	/**
	 * A letter (a spacing mark too), Japanese kana and the BMP's first ideographs aside.
	 */
	static final int LETTER = 3;

	/** A digit: any number. */
	static final int DIGIT = 4;

	/** Punctuation one letter takes after it when another follows. */
	static final int MID_WORD = 5;

	/** Punctuation one digit takes after it when another follows. */
	static final int MID_NUMBER = 6;

	/** Punctuation either takes: the quotes and the period. */
	static final int MID_EITHER = 7;

	/** A danda, which ends a word that a number then continues. */
	static final int DANDA = 8;

	private static final List<LispVal> RUNTIME_FORMS = buildRuntimeForms();

	private ClojureCaseMapping() {
	}

	/**
	 * The definitions {@code clojure.lisp}'s case mapping reads.
	 * @return the forms, in the library's canonical shape
	 */
	public static List<LispVal> runtimeForms() {
		return RUNTIME_FORMS;
	}

	/**
	 * What {@code String.toUpperCase} maps a code point to when it is not
	 * {@code Character.toUpperCase}'s one code point.
	 * @param codePoint the code point
	 * @return the mapping, or {@code null} when the one-character mapping is the whole
	 * one
	 */
	static @Nullable String specialUpcase(int codePoint) {
		String single = Character.toString(codePoint);
		String full = single.toUpperCase(Locale.ROOT);
		return full.equals(Character.toString(Character.toUpperCase(codePoint))) ? null : full;
	}

	/**
	 * What {@code String.toLowerCase} maps a code point to, standing alone, when it is
	 * not {@code Character.toLowerCase}'s one code point. The capital sigma is no such
	 * character alone; in a string its final form is the library's own test.
	 * @param codePoint the code point
	 * @return the mapping, or {@code null} when the one-character mapping is the whole
	 * one
	 */
	static @Nullable String specialDowncase(int codePoint) {
		String single = Character.toString(codePoint);
		String full = single.toLowerCase(Locale.ROOT);
		return full.equals(Character.toString(Character.toLowerCase(codePoint))) ? null : full;
	}

	/**
	 * A code point's word class ({@link #OTHER} ... {@link #DANDA}) times two, plus one
	 * when the JDK's Final_Sigma test counts it cased.
	 * @param codePoint the code point
	 * @return the class
	 */
	static int wordClass(int codePoint) {
		return 2 * wordKind(codePoint) + (isCased(codePoint) ? 1 : 0);
	}

	/**
	 * A code point's part in the legacy word {@code BreakIterator}
	 * ({@code WordBreakRules} of {@code sun.text.resources.BreakIteratorRules}). Its
	 * compiled table agrees with this over every assigned code point but five
	 * supplementary format characters (U+110BD, U+110CD, U+1343F, U+1BCA3, U+1D17A) and
	 * U+E0001 / U+E007F, which it treats as controls, and the unassigned code points
	 * between the CJK extensions, which it treats as letters (measured 2026-10-11, JDK
	 * 25).
	 * @param codePoint the code point
	 * @return the class
	 */
	static int wordKind(int codePoint) {
		switch (codePoint) {
			case 0xAD, 0x2027 -> {
				return MID_WORD;
			}
			case '"', '\'', '.' -> {
				return MID_EITHER;
			}
			case ',', 0x66B -> {
				return MID_NUMBER;
			}
			case 0x964, 0x965 -> {
				return DANDA;
			}
			case 0x3099, 0x309A -> {
				return MARK;
			}
			default -> {
			}
		}
		int type = Character.getType(codePoint);
		if (type == Character.FORMAT) {
			return IGNORED;
		}
		if (isKanaOrKanji(codePoint)) {
			return OTHER;
		}
		return switch (type) {
			case Character.NON_SPACING_MARK, Character.ENCLOSING_MARK -> MARK;
			case Character.UPPERCASE_LETTER, Character.LOWERCASE_LETTER, Character.TITLECASE_LETTER,
					Character.MODIFIER_LETTER, Character.OTHER_LETTER, Character.COMBINING_SPACING_MARK ->
				LETTER;
			case Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER, Character.OTHER_NUMBER -> DIGIT;
			case Character.DASH_PUNCTUATION, Character.CONNECTOR_PUNCTUATION -> MID_WORD;
			default -> OTHER;
		};
	}

	// The word rules' kanji, katakana, hiragana and CJK diacritics, which run among
	// themselves and never with a letter.
	private static boolean isKanaOrKanji(int c) {
		return c == 0x3005 || c >= 0x4E00 && c <= 0x9FA5 || c >= 0xF900 && c <= 0xFA2D || c >= 0x30A1 && c <= 0x30FE
				|| c >= 0x3041 && c <= 0x3094 || c >= 0x309B && c <= 0x309E;
	}

	/**
	 * Whether {@code ConditionalSpecialCasing.isCased} holds: an uppercase, lowercase or
	 * titlecase letter, or one of the {@code Other_Lowercase} / {@code Other_Uppercase}
	 * ranges it spells.
	 * @param c the code point
	 * @return {@code true} for a cased character
	 */
	static boolean isCased(int c) {
		int type = Character.getType(c);
		return type == Character.UPPERCASE_LETTER || type == Character.LOWERCASE_LETTER
				|| type == Character.TITLECASE_LETTER || c >= 0x2B0 && c <= 0x2B8 || c >= 0x2C0 && c <= 0x2C1
				|| c >= 0x2E0 && c <= 0x2E4 || c == 0x345 || c == 0x37A || c >= 0x1D2C && c <= 0x1D61
				|| c >= 0x2160 && c <= 0x217F || c >= 0x24B6 && c <= 0x24E9;
	}

	private static List<LispVal> buildRuntimeForms() {
		StringBuilder source = new StringBuilder();
		special(source, "upcase", ClojureCaseMapping::specialUpcase);
		special(source, "downcase", ClojureCaseMapping::specialDowncase);
		StringBuilder runs = new StringBuilder();
		int count = 0;
		int start = 0;
		int previous = -1;
		for (int codePoint = 0; codePoint <= Character.MAX_CODE_POINT; codePoint++) {
			int wordClass = wordClass(codePoint);
			if (wordClass != previous) {
				encodeDistance(runs, codePoint - start);
				runs.append(digit(wordClass));
				count++;
				start = codePoint;
				previous = wordClass;
			}
		}
		source.append("(defvar rontolisp::%clojure-word-runs-cache nil)\n")
			.append("(defun rontolisp::%clojure-word-runs ()\n")
			.append("  (or rontolisp::%clojure-word-runs-cache\n")
			.append("      (setq rontolisp::%clojure-word-runs-cache (rontolisp::%clojure-decode-runs \"")
			.append(runs)
			.append("\" ")
			.append(count)
			.append("))))\n");
		return List.copyOf(LispReader.readAllFromString(source.toString()));
	}

	// (defun rontolisp::%clojure-special-<name> (code)
	// (rontolisp::%clojure-special-case code "<keys>" '#("<mapping>" ...)))
	// with the keys, every code point the mapping departs at, as one string in order.
	private static void special(StringBuilder source, String name, IntFunction<@Nullable String> mapping) {
		StringBuilder keys = new StringBuilder();
		StringBuilder values = new StringBuilder();
		for (int codePoint = 0; codePoint <= Character.MAX_CODE_POINT; codePoint++) {
			String mapped = mapping.apply(codePoint);
			if (mapped != null) {
				keys.appendCodePoint(codePoint);
				values.append(' ').append(new LispString(mapped).print());
			}
		}
		source.append("(defun rontolisp::%clojure-special-")
			.append(name)
			.append(" (code)\n  (rontolisp::%clojure-special-case code ")
			.append(new LispString(keys.toString()).print())
			.append(" '#(")
			.append(values.substring(1))
			.append(")))\n");
	}

	// A run's distance from the start of the one before it in base-32 digits, the most
	// significant first, each but the last offset by 32.
	private static void encodeDistance(StringBuilder table, int distance) {
		int shift = 0;
		while (distance >>> (shift + 5) != 0) {
			shift += 5;
		}
		for (; shift > 0; shift -= 5) {
			table.append(digit(32 + ((distance >>> shift) & 31)));
		}
		table.append(digit(distance & 31));
	}

	// The digit d, 0 to 63, as the character 48 + d, skipping the backslash (92), so the
	// table needs no escape.
	private static char digit(int d) {
		return (char) (d < 44 ? 48 + d : 49 + d);
	}

}
