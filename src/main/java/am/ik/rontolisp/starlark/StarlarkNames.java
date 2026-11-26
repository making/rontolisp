package am.ik.rontolisp.starlark;

import java.util.ArrayList;
import java.util.List;

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
 * A file read by a {@code load} statement has globals of its own: each is spelled behind
 * {@link #filePrefix} of the file's path, {@code sl%(lib/util.star)helper}. No
 * identifier's spelling starts so (its third character is a {@code %}), and the
 * parentheses keep one path apart from another, so two files may both define
 * {@code helper} and the same file lowered twice spells the same names.
 *
 * <p>
 * What the lowering generates itself starts with {@code %SL-} -- behind the file's prefix
 * when it is a global, which two files of one program must not share -- so it is no
 * identifier's spelling either; the run-time helpers are {@code rontolisp::%starlark-}
 * names.
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
	 * What the names of one file start with: its path, normalized, between parentheses. A
	 * {@code %}, a colon (a package marker to the pipeline) and a closing parenthesis of
	 * the path are escaped.
	 * @param path the file's path as it was resolved
	 * @return the prefix
	 */
	static String filePrefix(String path) {
		StringBuilder prefix = new StringBuilder("sl%(");
		String key = key(path);
		for (int i = 0; i < key.length(); i++) {
			char c = key.charAt(i);
			switch (c) {
				case '%' -> prefix.append("%%");
				case ':' -> prefix.append("%c");
				case ')' -> prefix.append("%r");
				default -> prefix.append(c);
			}
		}
		return prefix.append(')').toString();
	}

	/**
	 * What a file is known by: its path with {@code /} between the segments, no {@code .}
	 * segment and no {@code name/..} pair, so that one file reached by two spellings from
	 * one directory is one module.
	 * @param path the file's path as it was resolved
	 * @return the key
	 */
	static String key(String path) {
		String slashed = path.replace('\\', '/');
		List<String> segments = new ArrayList<>();
		for (String segment : slashed.split("/")) {
			if (segment.isEmpty() || segment.equals(".")) {
				continue;
			}
			if (segment.equals("..") && !segments.isEmpty() && !segments.get(segments.size() - 1).equals("..")) {
				segments.remove(segments.size() - 1);
			}
			else {
				segments.add(segment);
			}
		}
		return (slashed.startsWith("/") ? "/" : "") + String.join("/", segments);
	}

	/**
	 * Whether the text is an identifier: what a {@code load} statement may bind.
	 * @param text the text
	 * @return {@code true} for a letter or underscore followed by letters, digits and
	 * underscores
	 */
	static boolean isIdentifier(String text) {
		return !text.isEmpty() && !Character.isDigit(text.codePointAt(0))
				&& text.codePoints().allMatch(c -> c == '_' || Character.isLetterOrDigit(c));
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
