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
	 * The octets of a resource of the program's source tree, named relative to the file
	 * it starts at, as {@link #find} reads a file of it.
	 * @param from the file whose directory {@code path} is relative to
	 * @param path the path, climbing out of that directory to the top of the tree with
	 * {@code ..} only
	 * @return the octets, or {@code null} when there is no file to read (a directory is
	 * none)
	 */
	default byte @Nullable [] findResource(String from, String path) {
		return null;
	}

	/**
	 * The octets of a resource of the source tree of a program that starts at no file, as
	 * {@link #tree} reads a file of it.
	 * @param path the path below the top of the tree, with no {@code .} or {@code ..}
	 * @return the octets, or {@code null} when the tree has none or there is no tree
	 */
	default byte @Nullable [] treeResource(String path) {
		return null;
	}

	/**
	 * The octets of a resource of a library the program uses: the first root of its
	 * source path that has it, as a class loader finds it there -- below a directory the
	 * name's {@code .} and {@code ..} resolved ({@link #below}), in a jar the entry of
	 * the name as it is.
	 * @param name the resource's name below a root
	 * @return the octets, or {@code null} when no root has it or there is no source path
	 */
	default byte @Nullable [] libraryResource(String name) {
		return null;
	}

	/**
	 * The entries of a directory of the program's source tree, named relative to the file
	 * it starts at, as {@link #findResource} reads a file of it.
	 * @param from the file whose directory {@code path} is relative to
	 * @param path the directory, climbing out of that directory to the top of the tree
	 * with {@code ..} only
	 * @return the names of its entries, a directory's ending in {@code /}, or
	 * {@code null} when it is no directory that can be read
	 */
	default @Nullable List<String> findListing(String from, String path) {
		return null;
	}

	/**
	 * The entries of a directory of the source tree of a program that starts at no file,
	 * as {@link #treeResource} reads a file of it.
	 * @param path the directory below the top of the tree, the top itself the empty
	 * string
	 * @return the names of its entries, a directory's ending in {@code /}, or
	 * {@code null} when there is no such directory or no tree
	 */
	default @Nullable List<String> treeListing(String path) {
		return null;
	}

	/**
	 * How many roots the program's source path has.
	 * @return the number of roots, none when there is no source path
	 */
	default int libraryRoots() {
		return 0;
	}

	/**
	 * Whether a root of the source path is a jar, whose entries a name must match as it
	 * is, or a directory, which resolves a name's {@code .} and {@code ..}.
	 * @param root the root's index, in the source path's order
	 * @return {@code true} for a jar
	 */
	default boolean libraryArchive(int root) {
		return false;
	}

	/**
	 * The entries of a directory under one root of the source path.
	 * @param root the root's index, in the source path's order
	 * @param path the directory below the root, the root itself the empty string, else
	 * ending in {@code /}
	 * @return the names of its entries, a directory's ending in {@code /}, or
	 * {@code null} when the root has no such directory
	 */
	default @Nullable List<String> libraryListing(int root, String path) {
		return null;
	}

	/**
	 * The octets of a file under one root of the source path, named as a listing of it
	 * names it.
	 * @param root the root's index, in the source path's order
	 * @param path the file's path below the root
	 * @return the octets, or {@code null} when the root has no such file
	 */
	default byte @Nullable [] libraryResource(int root, String path) {
		return null;
	}

	/**
	 * A resource's name below the top of a directory, as a class loader reads it there:
	 * its {@code .} and empty segments dropped and a {@code ..} taking the segment before
	 * it away.
	 * @param name the name
	 * @return the path, or {@code null} where a {@code ..} climbs above the top
	 */
	static @Nullable String below(String name) {
		java.util.ArrayDeque<String> segments = new java.util.ArrayDeque<>();
		for (String segment : name.split("/", -1)) {
			if (segment.equals("..")) {
				if (segments.pollLast() == null) {
					return null;
				}
			}
			else if (!segment.isEmpty() && !segment.equals(".")) {
				segments.addLast(segment);
			}
		}
		return String.join("/", segments);
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
