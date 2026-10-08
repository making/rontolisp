package am.ik.rontolisp.clojure;

import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * The files a Clojure program names besides itself: the project namespaces a
 * {@code require} loads, the {@code deps.edn} files naming their source roots and
 * dependencies, a dependency's directory or jar, and the user-level {@code deps.edn}.
 * This package has no filesystem of its own -- the browser playground has none at all --
 * so the source-language seam hands one in, over the loader the rest of the program reads
 * through ({@code eval/SourceLanguage}). Where the roots are and which file a namespace
 * maps to is decided here ({@link ClojureSourcePath}, {@link ClojureDepsGraph}); the seam
 * only reads.
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
	 * The entry names of a jar, read in place.
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

}
