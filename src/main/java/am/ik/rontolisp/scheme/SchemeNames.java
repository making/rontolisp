package am.ik.rontolisp.scheme;

import java.util.List;

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
	 * is not {@code %}, and no identifier holds a parenthesis -- so a library's names
	 * never collide with a program's or with another library's, and the space and the
	 * closing parenthesis keep {@code (a b)}'s names apart from {@code (a)}'s.
	 * @param library the library name's parts, as written
	 * @return the prefix
	 */
	static String libraryPrefix(List<String> library) {
		return PREFIX + "%(" + String.join(" ", library) + ")";
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
