package am.ik.rontolisp.starlark;

import am.ik.rontolisp.LispSymbol;

/**
 * How a Starlark identifier is spelled as a Common Lisp symbol name.
 *
 * <p>
 * An identifier keeps its spelling VERBATIM whenever it contains an ASCII lowercase
 * letter: every canonical name the pipeline dispatches on is upcased
 * ({@code .kb/reader-case-upcase.md}), so such a name can never reach a built-in, a
 * lambda-list keyword or {@code T}/{@code NIL}. Any other identifier ({@code X},
 * {@code T}, {@code NIL}, {@code _}, {@code MAX_SIZE}) is escaped behind {@link #PREFIX}:
 * a binding of {@code T} would otherwise be a binding of the constant. An identifier
 * holds letters, digits and underscores only, so the prefix, with its {@code %}, is no
 * identifier's start and the mapping is injective.
 *
 * <p>
 * What the lowering generates itself starts with {@code %SL-} and has no lowercase
 * letter, so it is no identifier's spelling either; the run-time helpers are
 * {@code rontolisp::%starlark-} names.
 */
final class StarlarkNames {

	/** What an escaped identifier starts with. Contains a lowercase letter on purpose. */
	static final String PREFIX = "s%";

	private StarlarkNames() {
	}

	/**
	 * The variable (and, for a top-level {@code def}, the function) an identifier names.
	 * @param identifier the identifier as written
	 * @return its symbol
	 */
	static LispSymbol symbol(String identifier) {
		return new LispSymbol(mangle(identifier));
	}

	/**
	 * The Common Lisp symbol name for a Starlark identifier.
	 * @param identifier the identifier as written
	 * @return the symbol name the lowering emits
	 */
	static String mangle(String identifier) {
		for (int i = 0; i < identifier.length(); i++) {
			char c = identifier.charAt(i);
			if (c >= 'a' && c <= 'z') {
				return identifier;
			}
		}
		return PREFIX + identifier;
	}

}
