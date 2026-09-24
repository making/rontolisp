package am.ik.rontolisp;

import java.util.AbstractSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.jspecify.annotations.Nullable;

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
 * <p>
 * The owned set doubles as the package's MEMBER table: a name the runtime {@code intern}
 * mints in the package is {@linkplain #addSymbol recorded} there, so {@code find-symbol}
 * can answer nil before the intern and the symbol after, and {@code unintern} can take it
 * {@linkplain #removeSymbol out} again. The set is therefore a mutable, thread-safe one
 * whatever the caller passed -- the canonical constructor copies it -- while the other
 * components stay as given (the rarer package operations rebuild the record). The copy is
 * a {@link MemberTable}: an immutable base plus what was interned and uninterned since,
 * so copying a package with a thousand symbols -- every registry does it for every
 * built-in package -- costs nothing until the program interns.
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
	 * The canonical constructor: the owned set becomes this record's own mutable member
	 * table (see the class comment).
	 */
	public LispPackage {
		symbols = MemberTable.copyOf(symbols);
	}

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
	 * Records a name the runtime {@code intern} minted in this package as one of its own
	 * symbols (the member table's write).
	 * @param symbolName the symbol name
	 */
	public void addSymbol(String symbolName) {
		this.symbols.add(symbolName);
	}

	/**
	 * Drops a name from the owned set (the {@code unintern} half of the member table).
	 * @param symbolName the symbol name
	 */
	public void removeSymbol(String symbolName) {
		this.symbols.remove(symbolName);
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

	/**
	 * A package's member table: an immutable base set plus the names interned since
	 * ({@code added}, never base members) and the base names uninterned since
	 * ({@code removed}, only base members). A copy shares the base, so the built-in
	 * packages every registry starts from are never copied name by name. Each operation
	 * is thread-safe, like the concurrent set it replaces.
	 */
	static final class MemberTable extends AbstractSet<String> {

		private final Set<String> base;

		private final Set<String> added = ConcurrentHashMap.newKeySet();

		private final Set<String> removed = ConcurrentHashMap.newKeySet();

		private MemberTable(Set<String> base) {
			this.base = base;
		}

		/**
		 * An independent member table holding exactly the given names.
		 * @param names the names, a member table or any set
		 * @return the new table
		 */
		static MemberTable copyOf(Set<String> names) {
			if (names instanceof MemberTable table) {
				MemberTable copy = new MemberTable(table.base);
				copy.added.addAll(table.added);
				copy.removed.addAll(table.removed);
				return copy;
			}
			// Set.copyOf answers an already-immutable set as itself.
			return new MemberTable(Set.copyOf(names));
		}

		@Override
		public boolean contains(Object name) {
			return this.added.contains(name) || (this.base.contains(name) && !this.removed.contains(name));
		}

		@Override
		public boolean add(String name) {
			if (this.base.contains(name)) {
				return this.removed.remove(name);
			}
			return this.added.add(name);
		}

		@Override
		public boolean remove(Object name) {
			if (this.base.contains(name)) {
				return this.removed.add((String) name);
			}
			return this.added.remove(name);
		}

		@Override
		public int size() {
			return this.base.size() - this.removed.size() + this.added.size();
		}

		@Override
		public Iterator<String> iterator() {
			Iterator<String> names = java.util.stream.Stream
				.concat(this.base.stream().filter(name -> !this.removed.contains(name)), this.added.stream())
				.iterator();
			return new Iterator<>() {

				private @Nullable String last;

				@Override
				public boolean hasNext() {
					return names.hasNext();
				}

				@Override
				public String next() {
					String name = names.next();
					this.last = name;
					return name;
				}

				@Override
				public void remove() {
					String name = this.last;
					if (name == null) {
						throw new IllegalStateException();
					}
					MemberTable.this.remove(name);
					this.last = null;
				}

			};
		}

	}

}
