package am.ik.rontolisp.java;

import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * The files of a Java program besides the one it starts at: the classes it names that
 * stand in other files of its source tree, and those of the libraries it uses, under the
 * roots of its source path. This package has no filesystem of its own -- the browser
 * playground has none at all -- so the source-language seam hands one in, over the loader
 * the rest of the program reads through ({@code eval/SourceLanguage}).
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
	 * Reads a file of a library the program uses: one under a root of its source path,
	 * the first root that has it.
	 * @param path the path below a root, as a class's qualified name spells it
	 * ({@code org/example/Lists.java})
	 * @return the file, or {@code null} when no root has it or there is no source path
	 */
	default @Nullable Source library(String path) {
		return null;
	}

	/**
	 * The files of a package of the libraries the program uses: the {@code .java} files
	 * directly in one directory of every root of its source path, the first root's of a
	 * name, in the order of the roots.
	 * @param directory the package's directory below a root, ending in {@code /}
	 * ({@code org/example/}), or the empty string for the unnamed package
	 * @return the files, each as {@link #library} reads it; none when there is no source
	 * path
	 */
	default List<Source> libraryPackage(String directory) {
		return List.of();
	}

	/**
	 * Reads a file of the source tree of a program that starts at no file: one the host
	 * holds as the program's own, the browser playground's uploads. A program given with
	 * {@code -e} has none.
	 * @param path the path below the top of the tree, as a class's qualified name spells
	 * it ({@code geo/Place.java})
	 * @return the file, or {@code null} when the tree has none or there is no tree
	 */
	default @Nullable Source tree(String path) {
		return null;
	}

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
