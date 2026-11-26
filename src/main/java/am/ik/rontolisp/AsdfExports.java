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
 * The ASDF 3.3.1 export inventory, measured in SBCL 2.2.9's image: which package exports
 * which symbol, where the symbol lives and how upstream defines it. Read once from the
 * checked-in {@code asdf-exports.txt} next to this class; the coverage target is data,
 * like {@link UiopExports}.
 *
 * <p>
 * {@link PackageRegistry} builds the {@code asdf} package and its 22 sub-packages from
 * the rows, {@code eval.AsdfRuntimeLibrary} decides which exports still need a signalling
 * stub. A symbol has one spelling: a member homed in an {@code asdf/*} package is owned
 * by {@code asdf} itself, external there only when {@code asdf/interface} exports it, and
 * the sub-packages redirect to it; a member homed in {@code uiop/*} is uiop's symbol.
 *
 * @see <a href="file:../../../../../.kb/asdf.md">.kb/asdf.md</a>
 */
public final class AsdfExports {

	/**
	 * One export.
	 *
	 * @param exporter the exporting package's name
	 * @param symbol the member name, upper-case
	 * @param home the package upstream homes the symbol in
	 * @param kind upstream's definition forms, {@code +}-joined: {@code function},
	 * {@code generic}, {@code macro}, {@code variable}, {@code condition}, {@code class}
	 * or {@code symbol}
	 * @param superclasses a class's or condition's direct superclasses, nearest exported
	 * ones, by member name
	 * @param lambdaList a generic's lambda list, by parameter name
	 */
	public record Entry(String exporter, String symbol, String home, String kind, List<String> superclasses,
			List<String> lambdaList) {

		/**
		 * Returns whether upstream defines the symbol as {@code k}.
		 * @param k one of the kinds
		 * @return {@code true} when the kind lists it
		 */
		public boolean is(String k) {
			return List.of(this.kind.split("\\+")).contains(k);
		}

		/**
		 * Returns whether the symbol is uiop's.
		 * @return {@code true} for a member homed in a uiop package
		 */
		public boolean isUiopHomed() {
			return this.home.startsWith("UIOP/");
		}
	}

	/**
	 * The package real ASDF names {@code asdf}: its rows are {@code asdf}'s externals.
	 */
	private static final String INTERFACE = "ASDF/INTERFACE";

	/** A sub-package exporting nothing, so no row names it. */
	private static final String FOOTER = "ASDF/FOOTER";

	private static final String RESOURCE = "asdf-exports.txt";

	private static final List<Entry> ENTRIES;

	private static final Map<String, Entry> EXTERNALS;

	private static final Map<String, Map<String, Entry>> SUB_PACKAGES;

	static {
		List<Entry> entries = new ArrayList<>();
		Map<String, Entry> externals = new LinkedHashMap<>();
		Map<String, Map<String, Entry>> subPackages = new LinkedHashMap<>();
		for (String line : read().split("\n")) {
			if (line.isBlank() || line.startsWith("#")) {
				continue;
			}
			String[] parts = line.split("\t", -1);
			if (parts.length != 6) {
				throw new IllegalStateException(RESOURCE + ": expected six tab-separated columns: " + line);
			}
			Entry entry = new Entry(parts[0], parts[1], parts[2], parts[3], words(parts[4]), words(parts[5]));
			entries.add(entry);
			if (INTERFACE.equals(entry.exporter())) {
				externals.put(entry.symbol(), entry);
			}
			else {
				subPackages.computeIfAbsent(entry.exporter(), ignored -> new LinkedHashMap<>())
					.put(entry.symbol(), entry);
			}
		}
		subPackages.put(FOOTER, Map.of());
		ENTRIES = List.copyOf(entries);
		EXTERNALS = java.util.Collections.unmodifiableMap(externals);
		SUB_PACKAGES = java.util.Collections.unmodifiableMap(subPackages);
	}

	private AsdfExports() {
	}

	/**
	 * Returns every export row.
	 * @return the rows
	 */
	public static List<Entry> entries() {
		return ENTRIES;
	}

	/**
	 * Returns what {@code asdf} exports, by member name.
	 * @return the 230 externals
	 */
	public static Map<String, Entry> externals() {
		return EXTERNALS;
	}

	/**
	 * Returns the sub-packages, each with what it exports by member name.
	 * @return the sub-packages by name, {@code asdf/interface} (a nickname here) aside
	 */
	public static Map<String, Map<String, Entry>> subPackages() {
		return SUB_PACKAGES;
	}

	/**
	 * Returns one row of a member, whichever package exports it.
	 * @param member the member name, upper-case
	 * @return the row, or {@code null} when no package exports the name
	 */
	public static @org.jspecify.annotations.Nullable Entry entry(String member) {
		for (Entry entry : ENTRIES) {
			if (entry.symbol().equals(member)) {
				return entry;
			}
		}
		return null;
	}

	/**
	 * Returns the names {@code asdf} owns: every member homed in an {@code asdf/*}
	 * package, external or not.
	 * @return the owned member names
	 */
	public static Set<String> owned() {
		Set<String> owned = new LinkedHashSet<>();
		for (Entry entry : ENTRIES) {
			if (!entry.isUiopHomed()) {
				owned.add(entry.symbol());
			}
		}
		return owned;
	}

	/**
	 * Returns whether a package name is {@code asdf} or one of its sub-packages or
	 * nicknames, as spelled in source before resolution.
	 * @param pkg the package name, upper-case
	 * @return {@code true} for a member of the family
	 */
	public static boolean isAsdfFamily(String pkg) {
		return LispNames.ASDF_PKG.equals(pkg) || INTERFACE.equals(pkg) || "ASDF-UTILITIES".equals(pkg)
				|| "ASDF-ACTION".equals(pkg) || "ASDF/DEFSYSTEM".equals(pkg) || SUB_PACKAGES.containsKey(pkg);
	}

	private static List<String> words(String column) {
		return column.isEmpty() ? List.of() : List.of(column.split(" "));
	}

	private static String read() {
		try (InputStream in = AsdfExports.class.getResourceAsStream(RESOURCE)) {
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
