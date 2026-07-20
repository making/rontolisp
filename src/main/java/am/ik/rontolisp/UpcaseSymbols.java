package am.ik.rontolisp;

import java.util.Set;

/**
 * Uppercase-canonical model shim. The reader upcases every unescaped symbol character
 * like Common Lisp's {@code :upcase} readtable case, and a lexed name IS its canonical
 * spelling -- there is no fold back to a lowercase canonical form. This class therefore
 * does nothing but pass names through; it remains only so the now-inert fold entry points
 * the compiled backends still reference (an empty baked fold set) can be retired backend
 * by backend. Slated for deletion once every backend's runtime reader has dropped its
 * fold.
 */
public final class UpcaseSymbols {

	private UpcaseSymbols() {
	}

	/**
	 * Returns the name unchanged: under the uppercase-canonical model the lexed name is
	 * already canonical, so nothing folds.
	 * @param name the symbol name as lexed (unescaped characters upcased)
	 * @return the same name
	 */
	public static String canonicalize(String name) {
		return name;
	}

	/**
	 * The empty fold set: nothing folds under the uppercase-canonical model.
	 * @return an empty set
	 */
	public static Set<String> foldableBareNames() {
		return Set.of();
	}

	/**
	 * The empty package fold set: nothing folds under the uppercase-canonical model.
	 * @return an empty set
	 */
	public static Set<String> foldablePackageNames() {
		return Set.of();
	}

	/**
	 * The (empty) foldable bare names as a single {@code \n}-delimited blob still baked
	 * by the compiled backends' runtime readers until their fold is retired.
	 * @return the delimited fold-name blob
	 */
	public static String foldNamesBlob() {
		return delimitedBlob(foldableBareNames());
	}

	/**
	 * The (empty) foldable package names as a {@code \n}-delimited blob.
	 * @return the delimited package-name blob
	 */
	public static String foldPackageNamesBlob() {
		return delimitedBlob(foldablePackageNames());
	}

	private static String delimitedBlob(Set<String> names) {
		StringBuilder sb = new StringBuilder("\n");
		for (String name : new java.util.TreeSet<>(names)) {
			sb.append(name).append('\n');
		}
		return sb.toString();
	}

}
