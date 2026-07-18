package am.ik.rontolisp;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A namespace (package) in the Lisp dialect. A package owns a set of symbol names and may
 * {@code use} other packages, inheriting their symbols for unqualified access. A subset
 * of the owned symbols is <em>external</em> (exported): only external symbols are
 * accessible with the single-colon qualifier ({@code pkg:name}), while any symbol is
 * accessible with the double-colon qualifier ({@code pkg::name}), mirroring Common Lisp.
 * This is a generic type with no hard-coded knowledge of the built-in packages, so
 * additional packages (or a future {@code defpackage}) can be registered without changing
 * the resolution logic.
 *
 * @param name the package name (e.g. {@code cl}, {@code cl-user}, {@code rontolisp})
 * @param useList the names of packages this package uses (whose symbols are visible
 * unqualified)
 * @param symbols the names of symbols owned by this package
 * @param externals the names of the exported (external) symbols, a subset of
 * {@code symbols}
 * @param imports the symbols imported from other packages via the {@code defpackage}
 * {@code :import-from} clause, mapping each imported name to its source package (symbol
 * resolution is textual, so an imported name simply resolves to the source package's
 * canonical spelling)
 * @param shadows the symbol names shadowed via the {@code defpackage} {@code :shadow}
 * clause: inside this package an unqualified use of such a name always resolves to this
 * package's own symbol, never to the {@code cl} (or any used package's) symbol of the
 * same name
 */
public record LispPackage(String name, List<String> useList, Set<String> symbols, Set<String> externals,
		Map<String, String> imports, Set<String> shadows) {

	/**
	 * Creates a package with no shadowed symbols.
	 * @param name the package name
	 * @param useList the names of packages this package uses
	 * @param symbols the names of symbols owned by this package
	 * @param externals the names of the exported (external) symbols
	 * @param imports the imported symbol names mapped to their source packages
	 */
	public LispPackage(String name, List<String> useList, Set<String> symbols, Set<String> externals,
			Map<String, String> imports) {
		this(name, useList, symbols, externals, imports, Set.of());
	}

	/**
	 * Creates a package with no imported symbols.
	 * @param name the package name
	 * @param useList the names of packages this package uses
	 * @param symbols the names of symbols owned by this package
	 * @param externals the names of the exported (external) symbols
	 */
	public LispPackage(String name, List<String> useList, Set<String> symbols, Set<String> externals) {
		this(name, useList, symbols, externals, Map.of());
	}

	/**
	 * Creates a package that exports every symbol it owns.
	 * @param name the package name
	 * @param useList the names of packages this package uses
	 * @param symbols the names of symbols owned by this package, all external
	 */
	public LispPackage(String name, List<String> useList, Set<String> symbols) {
		this(name, useList, symbols, symbols);
	}

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

	/**
	 * Returns whether this package exports a symbol with the given name (accessible with
	 * the single-colon qualifier).
	 * @param symbolName the symbol name to check
	 * @return {@code true} if the symbol is external
	 */
	public boolean exports(String symbolName) {
		return this.externals.contains(symbolName);
	}

	/**
	 * Returns whether this package shadows a symbol with the given name (the
	 * {@code defpackage} {@code :shadow} clause).
	 * @param symbolName the symbol name to check
	 * @return {@code true} if the symbol is shadowed
	 */
	public boolean shadows(String symbolName) {
		return this.shadows.contains(symbolName);
	}

}
