package am.ik.rontolisp.scheme;

import org.jspecify.annotations.Nullable;

/**
 * The files a Scheme program names besides itself: what {@code include} splices and where
 * an imported {@code define-library} is found. This package has no filesystem of its own
 * -- the browser playground has none at all -- so the source-language seam hands one in,
 * over the loader the rest of the program reads through ({@code eval/SourceLanguage}).
 */
@FunctionalInterface
public interface SchemeFiles {

	/** No file is readable: a program that names one is told so, by name. */
	SchemeFiles NONE = (from, path) -> null;

	/**
	 * Reads a file named relative to another.
	 * @param from the file whose directory {@code path} is relative to, or {@code null}
	 * for the working directory (a REPL, {@code -e})
	 * @param path the path as the program spells it
	 * @return the file, or {@code null} when there is none to read
	 */
	@Nullable Source find(@Nullable String from, String path);

	/**
	 * A file that was found.
	 *
	 * @param path the resolved path, for positions and for what the file names in turn
	 * @param text its contents
	 */
	record Source(String path, String text) {
	}

}
