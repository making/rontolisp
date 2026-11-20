package am.ik.rontolisp.java;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Predicate;

import org.jspecify.annotations.Nullable;

/**
 * The files a resource name computed where the program runs may find, which the program
 * carries: those of its source tree, where a lookup through the program's loader computes
 * a name, and those of every root of its source path. A lookup of a name computed in full
 * selects every file of a place but a source file ({@code .java}) and a class file
 * ({@code .class}), and every directory as what it answers, its name ending in {@code /};
 * {@code ResourceBundle.getBundle} of a constant base name the {@code .properties} files
 * of its bundles in one directory. An entry whose name starts with a dot is left out, and
 * the entries of a directory are taken in the order of their names, so that what is
 * carried does not depend on the host. More than {@link #FILES} files or {@link #OCTETS}
 * octets to carry, or more than {@link #VISITS} entries to look at, are refused: a tree
 * whose top is a home directory is not carried.
 * <p>
 * The table is a text the run time reads once ({@code rontolisp.internal.ResourceTable}):
 * for each place a letter ({@code T} the tree, {@code D} a directory of the source path,
 * {@code J} a jar), its count of files, a {@code ;}, the length of its path, a {@code ;}
 * and the path (where it is, for a resource's URL), then for each file the length of its
 * name, a {@code ;}, the name, the count of its octets, a {@code ;} and the octets, one
 * character an octet. The tree comes first, then the roots in their order.
 */
final class JavaResourceTable {

	/** The most files the places hold together. */
	static final int FILES = 10_000;

	/** The most octets the places' files hold together. */
	static final long OCTETS = 16L << 20;

	/**
	 * The most entries of directories looked at, files left out and directories included.
	 */
	static final int VISITS = 100_000;

	/** The places, by index: the tree's -1, a root's its index in the source path. */
	private final Map<Integer, Place> places = new TreeMap<>();

	private int files;

	private long octets;

	private int visits;

	/**
	 * A place the files are found in, where it is (its absolute path where the program is
	 * compiled, for a resource's URL), and the files found there, by name.
	 */
	private record Place(char kind, String path, Map<String, byte[]> files) {
	}

	/**
	 * Why a place could not be carried: the bound it went past.
	 */
	static final class Bounded extends Exception {

		private static final long serialVersionUID = 1L;

		Bounded(String reason) {
			super(reason, null, false, false);
		}

	}

	/**
	 * Selects files of a place below one of its directories.
	 * @param index the place: -1 the tree, a root's index in the source path
	 * @param kind {@code T}, {@code D} or {@code J}
	 * @param path where the place is, its absolute path
	 * @param listing the entries of a directory of the place (the top the empty string,
	 * any other ending in {@code /}), a directory's ending in {@code /}; {@code null} for
	 * none
	 * @param reading the octets of a file of the place by its path, or {@code null}
	 * @param directory where to look, ending in {@code /} or the top
	 * @param deep whether the directories below it are looked in too
	 * @param wanted which file names are selected
	 * @param directories what a directory walked through answers as a resource, by its
	 * path and its entries, or {@code null} where it is none; {@code null} where no
	 * directory is selected
	 * @param named what the place is called in a refusal
	 */
	void select(int index, char kind, String path, Function<String, @Nullable List<String>> listing,
			Function<String, byte @Nullable []> reading, String directory, boolean deep, Predicate<String> wanted,
			@Nullable Directories directories, String named) throws Bounded {
		Place place = this.places.computeIfAbsent(index, key -> new Place(kind, path, new TreeMap<>()));
		walk(place, directory, listing, reading, deep, wanted, directories, named);
	}

	/** What a directory answers as a resource. */
	@FunctionalInterface
	interface Directories {

		/**
		 * The octets of a directory, or {@code null} where it is no resource.
		 * @param path the directory, the top the empty string, any other ending in
		 * {@code /}
		 * @param entries its entries
		 */
		byte @Nullable [] octets(String path, List<String> entries);

	}

	private void walk(Place place, String directory, Function<String, @Nullable List<String>> listing,
			Function<String, byte @Nullable []> reading, boolean deep, Predicate<String> wanted,
			@Nullable Directories directories, String named) throws Bounded {
		List<String> entries = listing.apply(directory);
		if (entries == null) {
			return;
		}
		if (directories != null && !place.files().containsKey(directory)) {
			carry(place, directory, directories.octets(directory, entries), named);
		}
		List<String> sorted = new ArrayList<>(entries);
		sorted.sort(null);
		for (String entry : sorted) {
			if (entry.isEmpty() || entry.startsWith(".")) {
				continue;
			}
			if (++this.visits > VISITS) {
				throw new Bounded(named + " has more than " + VISITS + " entries to look at");
			}
			String path = directory + entry;
			if (entry.endsWith("/")) {
				if (deep) {
					walk(place, path, listing, reading, true, wanted, directories, named);
				}
				continue;
			}
			if (wanted.test(entry) && !place.files().containsKey(path)) {
				carry(place, path, reading.apply(path), named);
			}
		}
	}

	private void carry(Place place, String path, byte @Nullable [] read, String named) throws Bounded {
		if (read == null) {
			return;
		}
		if (++this.files > FILES) {
			throw new Bounded(named + " holds more than " + FILES + " files to carry");
		}
		this.octets += read.length;
		if (this.octets > OCTETS) {
			throw new Bounded(named + " holds more than " + OCTETS + " octets to carry");
		}
		place.files().put(path, read);
	}

	/** Whether a file name is one a name computed in full may find. */
	static boolean anyFile(String name) {
		return !name.endsWith(".java") && !name.endsWith(".class");
	}

	/**
	 * The table as the run time reads it.
	 * @param paths whether each place's path is written, for a resource's URL: an empty
	 * one otherwise
	 */
	String text(boolean paths) {
		StringBuilder text = new StringBuilder();
		for (Place place : this.places.values()) {
			text.append(place.kind()).append(place.files().size()).append(';');
			String path = paths ? place.path() : "";
			text.append(path.length()).append(';').append(path);
			place.files().forEach((name, read) -> {
				text.append(name.length()).append(';').append(name).append(read.length).append(';');
				for (byte octet : read) {
					text.append((char) (octet & 0xff));
				}
			});
		}
		return text.toString();
	}

}
