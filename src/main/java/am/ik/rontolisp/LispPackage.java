package am.ik.rontolisp;

import java.util.List;
import java.util.Set;

/**
 * A namespace (package) in the Lisp dialect. A package owns a set of symbol names and may
 * {@code use} other packages, inheriting their symbols for unqualified access. This is a
 * generic type with no hard-coded knowledge of the built-in packages, so additional
 * packages (or a future {@code defpackage}) can be registered without changing the
 * resolution logic.
 *
 * @param name the package name (e.g. {@code cl}, {@code cl-user}, {@code rontolisp})
 * @param useList the names of packages this package uses (whose symbols are visible
 * unqualified)
 * @param symbols the names of symbols owned by this package
 */
public record LispPackage(String name, List<String> useList, Set<String> symbols) {

	/**
	 * Returns whether this package uses the package with the given name.
	 * @param packageName the package name to check
	 * @return {@code true} if this package uses the given package
	 */
	public boolean uses(String packageName) {
		return this.useList.contains(packageName);
	}

	/**
	 * Returns whether this package owns a symbol with the given name.
	 * @param symbolName the symbol name to check
	 * @return {@code true} if this package owns the symbol
	 */
	public boolean owns(String symbolName) {
		return this.symbols.contains(symbolName);
	}

}
