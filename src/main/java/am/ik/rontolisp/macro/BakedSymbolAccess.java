package am.ik.rontolisp.macro;

import java.util.Map;
import java.util.Set;

import am.ik.rontolisp.PackageResolver;
import org.jspecify.annotations.Nullable;

/**
 * The read/compile-time registry's answer to "which symbol does this package make
 * accessible under this name", as the compiled backends' {@code find-symbol} /
 * {@code intern} lowerings consult it: folded at compile time for a literal name and
 * package designator, read from the {@code %baked-access%} rows at run time otherwise
 * ({@link LispMacroExpander#injectBakedAccess}). Built once per compile from the
 * resolver's final registry.
 */
public final class BakedSymbolAccess {

	/** No registry: every lowering keeps the spelling build. */
	public static final BakedSymbolAccess NONE = new BakedSymbolAccess(null);

	private final @Nullable PackageResolver resolver;

	private @Nullable Map<String, PackageResolver.AccessRow> rows;

	/** The row names {@link LispMacroExpander#injectBakedAccess} put in the program. */
	private Set<String> served = Set.of();

	private BakedSymbolAccess(@Nullable PackageResolver resolver) {
		this.resolver = resolver;
	}

	/**
	 * The access view of a resolver that has resolved the whole program.
	 * @param resolver the resolver holding the final registry
	 * @return the access view
	 */
	public static BakedSymbolAccess of(PackageResolver resolver) {
		return new BakedSymbolAccess(resolver);
	}

	/**
	 * What {@code (find-symbol name pkg)} answers on the registry, or null.
	 * @param pkg the literal package designator
	 * @param name the literal name
	 * @return the accessible symbol and its status, or null
	 */
	PackageResolver.@Nullable Accessible answer(String pkg, String name) {
		return this.resolver == null ? null : this.resolver.bakedAccessible(pkg, name);
	}

	/**
	 * The row name of a literal package designator ({@code (string (find-package pkg))}),
	 * or null when no package answers to it.
	 * @param pkg the literal package designator
	 * @return the upcased canonical name, or null
	 */
	@Nullable String rowName(String pkg) {
		String canonical = this.resolver == null ? null : this.resolver.findPackageName(pkg);
		return canonical == null ? null : canonical.toUpperCase(java.util.Locale.ROOT);
	}

	/**
	 * Every package designator mapped to the upcased canonical name of the package it
	 * names.
	 * @return the designator table
	 */
	Map<String, String> designators() {
		return this.resolver == null ? Map.of() : this.resolver.runtimePackageTable();
	}

	/**
	 * Every package's row, computed on first use.
	 * @return the rows by upcased package name
	 */
	synchronized Map<String, PackageResolver.AccessRow> rows() {
		if (this.rows == null) {
			this.rows = this.resolver == null ? Map.of() : this.resolver.bakedAccessRows();
		}
		return this.rows;
	}

	/**
	 * Whether the program carries the row of a package, so a lookup in it with a computed
	 * name goes through {@code %baked-access}.
	 * @param rowName the upcased package name
	 * @return whether the row was injected
	 */
	boolean serves(String rowName) {
		return this.served.contains(rowName);
	}

	void served(Set<String> rowNames) {
		this.served = Set.copyOf(rowNames);
	}

}
