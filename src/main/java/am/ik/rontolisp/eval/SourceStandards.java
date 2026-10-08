package am.ik.rontolisp.eval;

import org.jspecify.annotations.Nullable;

import am.ik.rontolisp.scheme.SchemeStandard;

/**
 * The standard each language's source is read against: one member per language that
 * offers a choice. Program-wide -- every file of the program in that language, the entry
 * file, a file loaded at run time or inlined on the compile path, and the REPL -- so it
 * travels with the {@link SourceLanguage} seam that reads them, and a caller above the
 * seam ({@code cli}) holds it without reaching into a language's package.
 *
 * @param scheme what a Scheme file is read against ({@code --scheme-standard})
 * @param clojureConfigDir the directory of the user-level {@code deps.edn} every Clojure
 * project merges, like the oracle's {@code clj} ({@code CLJ_CONFIG}), or {@code null} for
 * none: the command line locates it from the environment
 * ({@link #clojureConfigDir(String, String, String)}), while an embedder and every test
 * read none, so what a program means does not depend on the machine that happens to build
 * it
 */
public record SourceStandards(SchemeStandard scheme, @Nullable String clojureConfigDir) {

	/** Every language's default: {@code --scheme-standard rontolisp}, no user config. */
	public static final SourceStandards DEFAULT = new SourceStandards(SchemeStandard.RONTOLISP, null);

	/**
	 * Parses the command-line options that pick a standard.
	 * @param scheme the {@code --scheme-standard} value, or {@code null} for the default
	 * @return the standards
	 * @throws IllegalArgumentException when a value names no standard, naming the value
	 */
	public static SourceStandards parse(@Nullable String scheme) {
		return scheme == null ? DEFAULT : new SourceStandards(SchemeStandard.parse(scheme), null);
	}

	/**
	 * These standards with the user-level Clojure configuration directory.
	 * @param dir the directory, or {@code null} for none
	 * @return the standards
	 */
	public SourceStandards withClojureConfigDir(@Nullable String dir) {
		return new SourceStandards(this.scheme, dir);
	}

	/**
	 * Where the oracle's {@code clj} keeps the user-level {@code deps.edn}:
	 * {@code $CLJ_CONFIG}, else {@code $XDG_CONFIG_HOME/clojure}, else
	 * {@code ~/.clojure}.
	 * @param cljConfig {@code CLJ_CONFIG}, or {@code null}
	 * @param xdgConfigHome {@code XDG_CONFIG_HOME}, or {@code null}
	 * @param userHome the {@code user.home} property, or {@code null}
	 * @return the directory, or {@code null} when none of the three is set
	 */
	public static @Nullable String clojureConfigDir(@Nullable String cljConfig, @Nullable String xdgConfigHome,
			@Nullable String userHome) {
		if (cljConfig != null && !cljConfig.isEmpty()) {
			return cljConfig;
		}
		if (xdgConfigHome != null && !xdgConfigHome.isEmpty()) {
			return xdgConfigHome + java.io.File.separator + "clojure";
		}
		return userHome == null || userHome.isEmpty() ? null : userHome + java.io.File.separator + ".clojure";
	}

}
