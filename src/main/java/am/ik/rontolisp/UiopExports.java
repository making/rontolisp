package am.ik.rontolisp;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The uiop 3.3.7 export inventory: which sub-package exports which symbol, and what
 * DEFINITION FORM upstream gives it. Read once from the checked-in
 * {@code uiop-exports.txt} resource next to this class, which is extracted from the
 * pinned sources the built-in Quicklisp client fetches -- the coverage TARGET is data,
 * not a Java literal, so a number moves only when the inventory or an implementation
 * does.
 *
 * <p>
 * Two consumers, deliberately on opposite sides of the package-dependency rule:
 * {@link PackageRegistry} builds the 15 sub-packages and the {@code uiop} re-export table
 * from {@link #entries()}, and {@code eval.UiopLibrary} decides which names still need a
 * {@code not-implemented-error} stub. Both need the same table, so it lives in the root
 * package where neither has to depend on the other.
 *
 * <p>
 * The file lists one row per EXPORT, not per symbol: a name two sub-packages both export
 * gets a row in each (upstream's {@code uiop/backward-driver} re-exports five
 * {@code uiop/configuration} names, and {@code *output-translation-function*} is exported
 * by both {@code uiop/pathname} and {@code uiop/lisp-build}). Rows are in upstream load
 * order, so the FIRST row for a symbol names its {@link #homePackage home} and every
 * later row is an import redirect.
 *
 * @see <a href="file:../../../../../.kb/uiop.md">.kb/uiop.md</a>
 */
public final class UiopExports {

	/**
	 * One export: the sub-package that exports {@code symbol}, and the definition form
	 * upstream gives it.
	 *
	 * @param subPackage the exporting sub-package's canonical name ({@code UIOP/UTILITY})
	 * @param symbol the exported member name, upper-case
	 * @param kind upstream's definition form(s), joined with {@code +} when a name is
	 * defined twice ({@code condition+function} for {@code not-implemented-error}, which
	 * is both the condition and the function that signals it)
	 */
	public record Entry(String subPackage, String symbol, String kind) {

		/**
		 * Returns whether this entry carries the given kind component.
		 * @param k one of {@code function}, {@code macro}, {@code variable},
		 * {@code constant}, {@code condition}, {@code class}, {@code type}
		 * @return {@code true} when the symbol is defined that way upstream
		 */
		public boolean is(String k) {
			return this.kind.equals(k) || this.kind.startsWith(k + "+") || this.kind.endsWith("+" + k)
					|| this.kind.contains("+" + k + "+");
		}
	}

	private static final String RESOURCE = "uiop-exports.txt";

	private static final List<Entry> ENTRIES;

	private static final List<String> SUB_PACKAGES;

	private static final Map<String, Set<String>> EXTERNALS;

	private static final Map<String, String> HOME_PACKAGES;

	private static final Map<String, Entry> BY_SYMBOL;

	static {
		List<Entry> entries = new ArrayList<>();
		Set<String> subPackages = new LinkedHashSet<>();
		Map<String, Set<String>> externals = new LinkedHashMap<>();
		Map<String, String> homes = new LinkedHashMap<>();
		Map<String, Entry> bySymbol = new LinkedHashMap<>();
		for (String line : read().split("\n")) {
			String row = line.strip();
			if (row.isEmpty() || row.startsWith("#")) {
				continue;
			}
			String[] parts = row.split("\t");
			if (parts.length != 3) {
				throw new IllegalStateException(RESOURCE + ": expected <sub-package> TAB <symbol> TAB <kind>: " + row);
			}
			Entry entry = new Entry(parts[0], parts[1], parts[2]);
			entries.add(entry);
			subPackages.add(entry.subPackage());
			externals.computeIfAbsent(entry.subPackage(), ignored -> new LinkedHashSet<>()).add(entry.symbol());
			// First row wins: rows are in upstream load order, so the first sub-package
			// to export a name is the one that DEFINES it.
			homes.putIfAbsent(entry.symbol(), entry.subPackage());
			bySymbol.putIfAbsent(entry.symbol(), entry);
		}
		ENTRIES = List.copyOf(entries);
		SUB_PACKAGES = List.copyOf(subPackages);
		Map<String, Set<String>> copy = new LinkedHashMap<>();
		externals.forEach((pkg, names) -> copy.put(pkg, Set.copyOf(names)));
		EXTERNALS = Map.copyOf(copy);
		HOME_PACKAGES = Map.copyOf(homes);
		BY_SYMBOL = Map.copyOf(bySymbol);
	}

	private UiopExports() {
	}

	/**
	 * Returns every export row, in upstream load order.
	 * @return the export rows
	 */
	public static List<Entry> entries() {
		return ENTRIES;
	}

	/**
	 * Returns the 15 sub-package names {@code uiop} re-exports, in upstream load order.
	 * @return the sub-package names
	 */
	public static List<String> subPackages() {
		return SUB_PACKAGES;
	}

	/**
	 * Returns the names a sub-package exports.
	 * @param subPackage the sub-package's canonical name
	 * @return the exported names, empty for an unknown package
	 */
	public static Set<String> externals(String subPackage) {
		return EXTERNALS.getOrDefault(subPackage, Set.of());
	}

	/**
	 * Returns the sub-package that DEFINES the given symbol -- the first one to export it
	 * in upstream load order.
	 * @param symbol the member name, upper-case
	 * @return the home sub-package, or {@code null} when uiop does not export the name
	 */
	public static @org.jspecify.annotations.Nullable String homePackage(String symbol) {
		return HOME_PACKAGES.get(symbol);
	}

	/**
	 * Composes the canonical qualified spelling of a uiop member: the HOME sub-package's,
	 * which is what {@code PackageResolver} rewrites a {@code uiop:name} occurrence into,
	 * and therefore the name a definition of it must carry.
	 * @param symbol the member name, upper-case
	 * @return {@code <home>:<symbol>}
	 * @throws IllegalArgumentException when uiop does not export the name
	 */
	public static String qualified(String symbol) {
		String home = HOME_PACKAGES.get(symbol);
		if (home == null) {
			throw new IllegalArgumentException("Not a uiop external: " + symbol);
		}
		return home + ":" + symbol;
	}

	/**
	 * Returns whether a qualified name denotes the given uiop export -- in the
	 * {@code uiop:} spelling a program writes or in the home sub-package's spelling
	 * {@code PackageResolver} rewrites it into. A pass that runs on either side of
	 * resolution has to accept both.
	 * @param packageName the package part, canonical
	 * @param member the member part, canonical
	 * @param uiopMember the uiop export to test against
	 * @return {@code true} when the name denotes that export
	 */
	public static boolean denotes(String packageName, String member, String uiopMember) {
		if (!member.equals(uiopMember)) {
			return false;
		}
		return LispNames.UIOP_PKG.equals(packageName) || packageName.equals(HOME_PACKAGES.get(uiopMember));
	}

	/**
	 * Returns the inventory row of the given symbol, taken from its home sub-package.
	 * @param symbol the member name, upper-case
	 * @return the row, or {@code null} when uiop does not export the name
	 */
	public static @org.jspecify.annotations.Nullable Entry entry(String symbol) {
		return BY_SYMBOL.get(symbol);
	}

	/**
	 * Returns every distinct symbol {@code uiop} exports, mapped to its home sub-package.
	 * @return the symbol-to-home table
	 */
	public static Map<String, String> homePackages() {
		return HOME_PACKAGES;
	}

	/**
	 * Returns whether the given package name is {@code uiop} itself or one of its
	 * sub-packages -- the family a pass has to recognize when it runs on either side of
	 * package resolution.
	 * @param packageName the canonical package name
	 * @return {@code true} when the name is in the uiop family
	 */
	public static boolean isUiopFamily(String packageName) {
		return LispNames.UIOP_PKG.equals(packageName) || EXTERNALS.containsKey(packageName);
	}

	/**
	 * Returns whether the given package name is one of the uiop sub-packages.
	 * @param packageName the canonical package name
	 * @return {@code true} when it is a sub-package of uiop
	 */
	public static boolean isSubPackage(String packageName) {
		return EXTERNALS.containsKey(packageName);
	}

	private static String read() {
		try (InputStream in = UiopExports.class.getResourceAsStream(RESOURCE)) {
			if (in == null) {
				throw new IllegalStateException(RESOURCE + " is missing from the classpath");
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
