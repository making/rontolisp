package am.ik.rontolisp.clojure;

import org.jspecify.annotations.Nullable;

/**
 * The files a Clojure program names besides itself: the project namespaces a
 * {@code require} loads, and the {@code deps.edn} naming their source roots. This package
 * has no filesystem of its own -- the browser playground has none at all -- so the
 * source-language seam hands one in, over the loader the rest of the program reads
 * through ({@code eval/SourceLanguage}). Where the roots are and which file a namespace
 * maps to is decided here ({@link ClojureSourcePath}); the seam only reads.
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

}
