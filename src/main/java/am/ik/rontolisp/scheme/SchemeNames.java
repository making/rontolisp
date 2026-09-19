package am.ik.rontolisp.scheme;

import java.util.List;
import java.util.Locale;

/**
 * How a Scheme identifier is spelled as a Common Lisp symbol name.
 *
 * <p>
 * An identifier keeps its spelling VERBATIM whenever it contains an ASCII lowercase
 * letter: every canonical name the pipeline dispatches on is upcased
 * ({@code .kb/reader-case-upcase.md}), so such a name can never reach a {@code LispNames}
 * case label, a lambda-list keyword or {@code T}/{@code NIL}. The identifiers that COULD
 * collide are escaped behind {@link #PREFIX}:
 * <ul>
 * <li>no ASCII lowercase letter at all ({@code CAR}, {@code X}, {@code T}, {@code +}) --
 * a user binding of {@code CAR} would otherwise REPLACE the built-in;</li>
 * <li>a {@code :} anywhere -- the package resolver splits on it ({@code a:b} is "symbol b
 * of package a");</li>
 * <li>a leading {@code &} -- a lambda-list keyword position;</li>
 * <li>the spellings {@code #f} and {@code #!unspecific}, the false value's and the
 * unspecified object's symbols, which must not be forgeable through
 * {@code string->symbol};</li>
 * <li>a leading {@link #PREFIX} itself, so the mapping stays injective.</li>
 * </ul>
 * The same rule is applied to quoted symbols (data) and spelled again in
 * {@code scheme.lisp} for {@code string->symbol} / {@code symbol->string} and the
 * printer, so {@code (eq? 'ABC (string->symbol "ABC"))} holds and {@code (display 'ABC)}
 * shows {@code ABC}. Change the two together.
 */
final class SchemeNames {

	/** What an escaped identifier starts with. Contains a lowercase letter on purpose. */
	static final String PREFIX = "s%";

	/**
	 * The unspecified object's symbol name, MIT Scheme's spelling of it. Escaped by
	 * {@link #mangle}, so no identifier and no {@code string->symbol} can forge it.
	 */
	static final String UNSPECIFIED_NAME = "#!unspecific";

	private SchemeNames() {
	}

	/**
	 * The Common Lisp symbol name for a Scheme identifier.
	 * @param identifier the identifier as written
	 * @return the symbol name the lowering emits
	 */
	static String mangle(String identifier) {
		if (!needsEscape(identifier)) {
			return identifier;
		}
		StringBuilder mangled = new StringBuilder(PREFIX);
		for (int i = 0; i < identifier.length(); i++) {
			char c = identifier.charAt(i);
			if (c == '%') {
				mangled.append("%%");
			}
			else if (c == ':') {
				mangled.append("%c");
			}
			else {
				mangled.append(c);
			}
		}
		return mangled.toString();
	}

	/**
	 * What every top-level name a user library defines starts with: {@code s%%(} plus the
	 * library's name, {@code s%%(mylib util)}. No identifier mangles to it -- an escaped
	 * one continues its {@link #PREFIX} with {@code %%}, {@code %c} or a character that
	 * is not {@code %} -- so a library's names never collide with a program's, and the
	 * space and the closing parenthesis, escaped inside a part ({@link #component}), keep
	 * {@code (a b)}'s names apart from {@code (a)}'s and from {@code (|a b|)}'s.
	 * @param library the library name's parts, as written
	 * @return the prefix
	 */
	static String libraryPrefix(List<String> library) {
		return PREFIX + "%(" + String.join(" ", library.stream().map(SchemeNames::component).toList()) + ")";
	}

	/**
	 * A name as a part of a composed internal name -- a library prefix, an internal
	 * record type's {@code s%%[<definition> <type>]}: the separators ({@code ' '},
	 * {@code ')'}, {@code ']'}) and the escape character {@code |} itself spelled
	 * {@code |s}, {@code |p}, {@code |b} and {@code ||}. A name without them -- every
	 * identifier written without vertical lines but one holding a {@code )} or a
	 * {@code ]} -- is unchanged.
	 * @param part the name
	 * @return the part as it stands in a composed name
	 */
	static String component(String part) {
		if (part.indexOf(' ') < 0 && part.indexOf(')') < 0 && part.indexOf(']') < 0 && part.indexOf('|') < 0) {
			return part;
		}
		StringBuilder escaped = new StringBuilder();
		for (int i = 0; i < part.length(); i++) {
			char c = part.charAt(i);
			switch (c) {
				case '|' -> escaped.append("||");
				case ' ' -> escaped.append("|s");
				case ')' -> escaped.append("|p");
				case ']' -> escaped.append("|b");
				default -> escaped.append(c);
			}
		}
		return escaped.toString();
	}

	/**
	 * The Scheme spelling of a symbol name, the inverse of {@link #mangle}.
	 * @param symbolName the Common Lisp symbol name
	 * @return the identifier it spells
	 */
	static String unmangle(String symbolName) {
		if (!symbolName.startsWith(PREFIX)) {
			return symbolName;
		}
		StringBuilder identifier = new StringBuilder();
		for (int i = PREFIX.length(); i < symbolName.length(); i++) {
			char c = symbolName.charAt(i);
			if (c == '%' && i + 1 < symbolName.length()) {
				i++;
				identifier.append(symbolName.charAt(i) == 'c' ? ':' : symbolName.charAt(i));
			}
			else {
				identifier.append(c);
			}
		}
		return identifier.toString();
	}

	/**
	 * Whether {@code write} puts the symbol between vertical lines: its spelling would
	 * not read back as that symbol. The same grammar as
	 * {@code %scheme-plain-identifier-p} in {@code scheme.lisp} -- change the two
	 * together. The false value's, the unspecified object's and the environment's symbols
	 * are printed as themselves.
	 * @param symbolName the Common Lisp symbol name
	 * @return {@code true} for {@code |foo bar|}, {@code ||}, {@code |1|},
	 * {@code |+inf.0|}, ...
	 */
	static boolean writtenWithVerticalLines(String symbolName) {
		if (symbolName.equals("#f") || symbolName.equals(UNSPECIFIED_NAME)
				|| symbolName.equals(SchemeBuiltins.ENVIRONMENT_NAME)) {
			return false;
		}
		return !isPlainIdentifier(unmangle(symbolName));
	}

	// R7RS 7.1.1 <identifier> without vertical lines, less the <infnan> spellings.
	private static boolean isPlainIdentifier(String name) {
		int n = name.length();
		if (n == 0 || List.of("+inf.0", "-inf.0", "+nan.0", "-nan.0").contains(name.toLowerCase(Locale.ROOT))) {
			return false;
		}
		char c = name.charAt(0);
		int start;
		if (isInitial(c)) {
			start = 1;
		}
		else if ((c == '+' || c == '-') && n == 1) {
			start = 1;
		}
		else if (c == '+' || c == '-') {
			if (isSignSubsequent(name.charAt(1))) {
				start = 2;
			}
			else if (name.charAt(1) == '.' && n > 2 && (name.charAt(2) == '.' || isSignSubsequent(name.charAt(2)))) {
				start = 3;
			}
			else {
				return false;
			}
		}
		else if (c == '.' && n > 1 && (name.charAt(1) == '.' || isSignSubsequent(name.charAt(1)))) {
			start = 2;
		}
		else {
			return false;
		}
		for (int i = start; i < n; i++) {
			char s = name.charAt(i);
			if (!isSignSubsequent(s) && s != '.' && !(s >= '0' && s <= '9')) {
				return false;
			}
		}
		return true;
	}

	// Every non-ASCII character counts as a letter, as Gauche writes one bare.
	private static boolean isInitial(char c) {
		return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c >= 128 || "!$%&*/:<=>?^_~".indexOf(c) >= 0;
	}

	private static boolean isSignSubsequent(char c) {
		return isInitial(c) || c == '+' || c == '-' || c == '@';
	}

	private static boolean needsEscape(String identifier) {
		if (identifier.startsWith(PREFIX) || identifier.startsWith("&") || identifier.equals("#f")
				|| identifier.equals(UNSPECIFIED_NAME) || identifier.indexOf(':') >= 0) {
			return true;
		}
		for (int i = 0; i < identifier.length(); i++) {
			char c = identifier.charAt(i);
			if (c >= 'a' && c <= 'z') {
				return false;
			}
		}
		return true;
	}

}
