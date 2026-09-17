package am.ik.rontolisp.scheme;

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

	private static boolean needsEscape(String identifier) {
		if (identifier.startsWith(PREFIX) || identifier.startsWith("&") || identifier.equals("#f")
				|| identifier.equals(SchemeBuiltins.UNSPECIFIED_NAME) || identifier.indexOf(':') >= 0) {
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
