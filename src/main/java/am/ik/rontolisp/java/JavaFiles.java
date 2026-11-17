package am.ik.rontolisp.java;

import org.jspecify.annotations.Nullable;

/**
 * The files of a Java program besides the one it starts at: the classes it names that
 * stand in other files of its source tree. This package has no filesystem of its own --
 * the browser playground has none at all -- so the source-language seam hands one in,
 * over the loader the rest of the program reads through ({@code eval/SourceLanguage}).
 */
public interface JavaFiles {

	/** No file is readable: a program is the one file it starts at. */
	JavaFiles NONE = (from, path) -> null;

	/**
	 * Reads a file named relative to another.
	 * @param from the file whose directory {@code path} is relative to
	 * @param path the path, which may climb with {@code ..}
	 * @return the file, or {@code null} when there is none to read
	 */
	@Nullable Source find(String from, String path);

	/**
	 * A path made absolute, so that the directories above a file can be compared with its
	 * package; a host with no working directory keeps the spelling.
	 * @param path a path
	 * @return the absolute path
	 */
	default String absolute(String path) {
		return path;
	}

	/**
	 * A file that was found.
	 *
	 * @param path the resolved path: what the file is known by, for positions
	 * @param text its contents
	 */
	record Source(String path, String text) {
	}

}
