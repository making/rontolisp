package am.ik.rontolisp.clojure;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * The files a Clojure program names besides itself: the project namespaces a
 * {@code require} loads, the {@code deps.edn} files naming their source roots and
 * dependencies, a dependency's directory or jar, the user-level {@code deps.edn}, the
 * repositories its Maven and git coordinates come from, and the classes of its Java class
 * path. This package has no filesystem of its own -- the browser playground has none at
 * all -- so the source-language seam hands one in, over the loader the rest of the
 * program reads through ({@code eval/SourceLanguage}). Where the roots are and which file
 * a namespace maps to is decided here ({@link ClojureSourcePath},
 * {@link ClojureDepsGraph}); the seam only reads.
 */
public interface ClojureFiles {

	/** No file is readable: a program that requires a project namespace is told so. */
	ClojureFiles NONE = new ClojureFiles() {
		@Override
		public @Nullable String read(String path) {
			return null;
		}

		@Override
		public @Nullable String parent(String path) {
			return null;
		}

		@Override
		public String resolve(@Nullable String dir, String relative) {
			return relative;
		}
	};

	/**
	 * Reads a file.
	 * @param path the path, as {@link #resolve} built it
	 * @return its text, or {@code null} when there is none to read
	 */
	@Nullable String read(String path);

	/**
	 * Reads a file's octets, for a resource whose contents travel with the program as
	 * they are. The default encodes what {@link #read} answers in UTF-8.
	 * @param path the path, as {@link #resolve} built it
	 * @return its octets, or {@code null} when there is none to read
	 */
	default byte @Nullable [] readBytes(String path) {
		String text = read(path);
		return text == null ? null : text.getBytes(StandardCharsets.UTF_8);
	}

	/**
	 * The directory holding a path, absolute where the host has a working directory.
	 * @param path a file or directory path
	 * @return the directory, or {@code null} past the top
	 */
	@Nullable String parent(String path);

	/**
	 * A relative path resolved against a directory.
	 * @param dir the directory, or {@code null} (or empty) for the working directory
	 * @param relative the path below it
	 * @return the resolved path
	 */
	String resolve(@Nullable String dir, String relative);

	/**
	 * The class loader the program's host classes come from -- the program's Java class
	 * path over the classes rontolisp runs with -- which the lowering asks what a class
	 * name is (its members' arities, a throwable's chain). The default is rontolisp's own
	 * loader.
	 * @return the loader
	 */
	default ClassLoader javaClassLoader() {
		return ClojureFiles.class.getClassLoader();
	}

	/**
	 * Whether a file or directory exists at a path. The default reads it.
	 * @param path the path
	 * @return whether it exists
	 */
	default boolean exists(String path) {
		return read(path) != null || isDirectory(path);
	}

	/**
	 * Whether a path names a directory -- a {@code :local/root} dependency's project.
	 * @param path the path
	 * @return whether it is a directory; {@code false} by default, where nothing is one
	 */
	default boolean isDirectory(String path) {
		return false;
	}

	/**
	 * The path a dependency is known by: absolute, with every symbolic link resolved
	 * where the host has links, so one directory reached by two spellings is one
	 * dependency (the oracle's {@code getCanonicalPath}).
	 * @param path an absolute or working-directory-relative path
	 * @return the canonical path; the path itself by default
	 */
	default String canonical(String path) {
		return path;
	}

	/**
	 * The absolute spelling of a path, its {@code .} and {@code ..} taken out and no link
	 * resolved: what a class loader's {@code file:} URL names (the oracle's
	 * {@code clojure.java.io/resource}).
	 * @param path an absolute or working-directory-relative path
	 * @return the absolute path; the path itself by default, where no working directory
	 * is
	 */
	default String absolute(String path) {
		return path;
	}

	/**
	 * The entry names of a jar, read in place; a directory entry's ends with {@code /}.
	 * @param path the jar's path
	 * @return the names, or {@code null} when the path names no readable jar (the
	 * default)
	 */
	default @Nullable List<String> archiveEntries(String path) {
		return null;
	}

	/**
	 * Reads one entry of a jar as text.
	 * @param archive the jar's path
	 * @param entry the entry's name, one {@link #archiveEntries} gave
	 * @return its text, or {@code null} when it cannot be read (the default)
	 */
	default @Nullable String readArchiveEntry(String archive, String entry) {
		return null;
	}

	/**
	 * Reads one entry of a jar's octets, for a resource whose contents travel with the
	 * program as they are. The default encodes what {@link #readArchiveEntry} answers in
	 * UTF-8.
	 * @param archive the jar's path
	 * @param entry the entry's name, one {@link #archiveEntries} gave
	 * @return its octets, or {@code null} when it cannot be read
	 */
	default byte @Nullable [] readArchiveEntryBytes(String archive, String entry) {
		String text = readArchiveEntry(archive, entry);
		return text == null ? null : text.getBytes(StandardCharsets.UTF_8);
	}

	/**
	 * The directory of the user-level {@code deps.edn} every project merges, the oracle's
	 * {@code CLJ_CONFIG} -- chosen once, where the environment is read, never here.
	 * @return the directory, or {@code null} when no user-level file is merged (the
	 * default)
	 */
	default @Nullable String userConfigDir() {
		return null;
	}

	/**
	 * The {@code deps.edn} aliases the program is read under, the oracle's {@code -A} /
	 * {@code -M} / {@code -X} selection -- chosen once, on the command line, never here.
	 * @return the aliases' keyword spellings ({@code :test}), in order; none by default
	 */
	default List<String> aliases() {
		return List.of();
	}

	/**
	 * The entries of a directory, for finding a project's test namespaces.
	 * @param dir the directory
	 * @return the names, a directory's with a trailing {@code /}, or {@code null} when
	 * the path names no readable directory (the default)
	 */
	default @Nullable List<String> list(String dir) {
		return null;
	}

	/**
	 * Where a {@code deps.edn}'s Maven and git coordinates are fetched from -- chosen
	 * once, where the environment is read, never here.
	 * @return the repositories, or {@code null} when nothing is fetched (the default):
	 * every Maven and git coordinate stays unfetched, named when a lookup misses
	 */
	default @Nullable ClojureRepositories repositories() {
		return null;
	}

	/**
	 * Adds a jar the program's dependencies bring to its Java class path -- what its
	 * {@link #javaClassLoader()} loads from from now on, and what a compiled program
	 * carries. The default adds nothing: a host without a Java class path of its own.
	 * @param jar the jar's path
	 * @param mavenCoordinate its Maven coordinates
	 * ({@code groupId:artifactId[:extension[:classifier]]:version}) when a Maven
	 * coordinate brought it, else {@code null}
	 */
	default void addJavaClassPath(String jar, @Nullable String mavenCoordinate) {
	}

}
