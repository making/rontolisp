package am.ik.rontolisp.eval;

import java.util.List;

import org.jspecify.annotations.Nullable;

import am.ik.rontolisp.clojure.ClojureRepositories;
import am.ik.rontolisp.scheme.SchemeStandard;
import am.ik.rontolisp.starlark.StarlarkStandard;

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
 * @param clojureAliases the {@code deps.edn} aliases every Clojure read applies, the
 * oracle's {@code -A}/{@code -M}/{@code -X} selection ({@code :test}), in order; none
 * when the command line selects none
 * @param clojureRepositories where a {@code deps.edn}'s Maven and git coordinates are
 * fetched from, or {@code null} to fetch none: the command line fetches through the
 * network and the caches the environment names, while an embedder, a test and the browser
 * fetch nothing, for the same reason they read no user-level map
 * @param starlark what a Starlark file is read against ({@code --starlark-standard})
 * @param javaSourcePath the roots a Java program's libraries are read from as source, a
 * directory or a jar each, in order ({@code --java-source-path}, then the sources jars of
 * the {@code --java-source-dep} coordinates); none when the command line names none
 * @param javaSourceTree the top of the source tree of a Java program read from no file,
 * which a class it names is read from as a source launcher reads a file beside the one it
 * starts at: the browser playground's uploads, by the names they were uploaded by
 * ({@code ""}); {@code null} for none -- a program given with {@code -e} reads no other
 * file
 * @param javaTests what a Java file's tests are run for ({@code rontolisp test}): the
 * program is then the run of the tests the file's classes declare, and this is what it is
 * said to be of where it finds none; {@code null} for a file that is a program
 * @param javaTestFiles the further files of a run of tests, of the source tree of the
 * file read, whose classes' tests run after its own, in order; none for a run of one file
 * @param javaMetadataRepository the shared repository of reachability metadata a Java
 * program's libraries are looked up in, a directory or a zip of its layout
 * ({@code --java-metadata-repository}), or {@code null} for none
 * @param javaDependencies the Maven coordinates ({@code group:artifact:version}) of the
 * libraries on the source path the repository is asked for; none when it is not
 */
public record SourceStandards(SchemeStandard scheme, @Nullable String clojureConfigDir, List<String> clojureAliases,
		@Nullable ClojureRepositories clojureRepositories, StarlarkStandard starlark, List<String> javaSourcePath,
		@Nullable String javaSourceTree, @Nullable String javaTests, List<String> javaTestFiles,
		@Nullable String javaMetadataRepository, List<String> javaDependencies) {

	/**
	 * Every language's default: {@code --scheme-standard rontolisp},
	 * {@code --starlark-standard rontolisp}, no user config.
	 */
	public static final SourceStandards DEFAULT = new SourceStandards(SchemeStandard.RONTOLISP, null, List.of(), null,
			StarlarkStandard.RONTOLISP, List.of(), null, null, List.of(), null, List.of());

	/**
	 * The standards.
	 * @param scheme what a Scheme file is read against
	 * @param clojureConfigDir the user-level {@code deps.edn} directory, or {@code null}
	 * @param clojureAliases the selected {@code deps.edn} aliases
	 * @param clojureRepositories where coordinates are fetched from, or {@code null}
	 * @param starlark what a Starlark file is read against
	 * @param javaSourcePath the roots of a Java program's source path
	 * @param javaSourceTree the source tree of a Java program read from no file, or
	 * {@code null}
	 * @param javaTests what a Java file's tests are run for, or {@code null}
	 * @param javaTestFiles the further files of a run of tests
	 * @param javaMetadataRepository the shared repository of reachability metadata, or
	 * {@code null}
	 * @param javaDependencies the coordinates of the libraries the repository is asked
	 * for
	 */
	public SourceStandards {
		clojureAliases = List.copyOf(clojureAliases);
		javaSourcePath = List.copyOf(javaSourcePath);
		javaTestFiles = List.copyOf(javaTestFiles);
		javaDependencies = List.copyOf(javaDependencies);
	}

	/**
	 * Parses the command-line options that pick a standard.
	 * @param scheme the {@code --scheme-standard} value, or {@code null} for the default
	 * @return the standards
	 * @throws IllegalArgumentException when a value names no standard, naming the value
	 */
	public static SourceStandards parse(@Nullable String scheme) {
		return parse(scheme, null);
	}

	/**
	 * Parses the command-line options that pick a standard.
	 * @param scheme the {@code --scheme-standard} value, or {@code null} for the default
	 * @param starlark the {@code --starlark-standard} value, or {@code null} for the
	 * default
	 * @return the standards
	 * @throws IllegalArgumentException when a value names no standard, naming the value
	 */
	public static SourceStandards parse(@Nullable String scheme, @Nullable String starlark) {
		return new SourceStandards(scheme == null ? SchemeStandard.RONTOLISP : SchemeStandard.parse(scheme), null,
				List.of(), null, starlark == null ? StarlarkStandard.RONTOLISP : StarlarkStandard.parse(starlark),
				List.of(), null, null, List.of(), null, List.of());
	}

	/**
	 * These standards with the user-level Clojure configuration directory.
	 * @param dir the directory, or {@code null} for none
	 * @return the standards
	 */
	public SourceStandards withClojureConfigDir(@Nullable String dir) {
		return new SourceStandards(this.scheme, dir, this.clojureAliases, this.clojureRepositories, this.starlark,
				this.javaSourcePath, this.javaSourceTree, this.javaTests, this.javaTestFiles,
				this.javaMetadataRepository, this.javaDependencies);
	}

	/**
	 * These standards with the {@code deps.edn} aliases every Clojure read applies.
	 * @param aliases the aliases' keyword spellings ({@code :test}), in order
	 * @return the standards
	 */
	public SourceStandards withClojureAliases(List<String> aliases) {
		return new SourceStandards(this.scheme, this.clojureConfigDir, aliases, this.clojureRepositories, this.starlark,
				this.javaSourcePath, this.javaSourceTree, this.javaTests, this.javaTestFiles,
				this.javaMetadataRepository, this.javaDependencies);
	}

	/**
	 * These standards with where a {@code deps.edn}'s Maven and git coordinates are
	 * fetched from.
	 * @param repositories the repositories, or {@code null} to fetch none
	 * @return the standards
	 */
	public SourceStandards withClojureRepositories(@Nullable ClojureRepositories repositories) {
		return new SourceStandards(this.scheme, this.clojureConfigDir, this.clojureAliases, repositories, this.starlark,
				this.javaSourcePath, this.javaSourceTree, this.javaTests, this.javaTestFiles,
				this.javaMetadataRepository, this.javaDependencies);
	}

	/**
	 * These standards with a Java program's source path: where the libraries it uses are
	 * read from as source.
	 * @param roots the roots, a directory or a jar each, in order
	 * @return the standards
	 */
	public SourceStandards withJavaSourcePath(List<String> roots) {
		return new SourceStandards(this.scheme, this.clojureConfigDir, this.clojureAliases, this.clojureRepositories,
				this.starlark, roots, this.javaSourceTree, this.javaTests, this.javaTestFiles,
				this.javaMetadataRepository, this.javaDependencies);
	}

	/**
	 * These standards with the source tree of a Java program read from no file.
	 * @param tree the top of the tree, which file names are relative to
	 * @return the standards
	 */
	public SourceStandards withJavaSourceTree(String tree) {
		return new SourceStandards(this.scheme, this.clojureConfigDir, this.clojureAliases, this.clojureRepositories,
				this.starlark, this.javaSourcePath, tree, this.javaTests, this.javaTestFiles,
				this.javaMetadataRepository, this.javaDependencies);
	}

	/**
	 * These standards for a run of a Java file's tests.
	 * @param target what the run is said to be of where it finds no test
	 * @return the standards
	 */
	public SourceStandards withJavaTests(String target) {
		return withJavaTests(target, List.of());
	}

	/**
	 * These standards for a run of the tests of several Java files of one source tree, in
	 * one program: the file read, then the further ones.
	 * @param target what the run is said to be of where it finds no test
	 * @param files the further files, of the source tree of the file read, in order
	 * @return the standards
	 */
	public SourceStandards withJavaTests(String target, List<String> files) {
		return new SourceStandards(this.scheme, this.clojureConfigDir, this.clojureAliases, this.clojureRepositories,
				this.starlark, this.javaSourcePath, this.javaSourceTree, target, files, this.javaMetadataRepository,
				this.javaDependencies);
	}

	/**
	 * These standards with the shared repository of reachability metadata a Java
	 * program's libraries are looked up in.
	 * @param repository the repository, a directory or a zip of its layout, or
	 * {@code null} for none
	 * @param dependencies the coordinates ({@code group:artifact:version}) of the
	 * libraries it is asked for
	 * @return the standards
	 */
	public SourceStandards withJavaMetadataRepository(@Nullable String repository, List<String> dependencies) {
		return new SourceStandards(this.scheme, this.clojureConfigDir, this.clojureAliases, this.clojureRepositories,
				this.starlark, this.javaSourcePath, this.javaSourceTree, this.javaTests, this.javaTestFiles, repository,
				repository == null ? List.of() : dependencies);
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
