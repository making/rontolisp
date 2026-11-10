package am.ik.wit;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * The source positions of the items in a parsed {@link WitDocument}, so a consumer can
 * report an error against the WIT line that caused it (e.g. "the world declares an export
 * this program does not implement" pointing at the {@code export} line).
 *
 * <p>
 * The positions are kept <strong>beside</strong> the model rather than inside it: the
 * {@link WitItem} records stay pure values, so two structurally equal documents remain
 * {@code equals} (the round-trip and idempotence tests depend on it). Items are therefore
 * keyed by identity -- an item looked up here must be the very instance
 * {@link WitParser#parseLocated} produced, not a copy.
 *
 * <p>
 * A package read from several files ({@link WitPackageAssembler}) has one table per file,
 * each naming its file: a lookup answers from the table that holds the item, so a line is
 * always a line of the file the item was written in ({@link #fileOf}).
 *
 * @see WitParser#parseLocated(String)
 */
public final class WitLocations {

	private static final WitLocations NONE = new WitLocations("", new IdentityHashMap<>(), null, List.of());

	private final String source;

	private final Map<WitItem, Integer> offsets;

	private final @Nullable String file;

	private final List<WitLocations> parts;

	WitLocations(String source, Map<WitItem, Integer> offsets) {
		this(source, offsets, null, List.of());
	}

	private WitLocations(String source, Map<WitItem, Integer> offsets, @Nullable String file,
			List<WitLocations> parts) {
		this.source = source;
		this.offsets = offsets;
		this.file = file;
		this.parts = parts;
	}

	// This table, naming the file its source was read from.
	WitLocations inFile(String name) {
		return new WitLocations(this.source, this.offsets, name, List.of());
	}

	// One table over several files' tables: a lookup answers from the one holding the
	// item.
	static WitLocations combine(List<WitLocations> parts) {
		return new WitLocations("", new IdentityHashMap<>(), null, List.copyOf(parts));
	}

	// The table that holds the item: this one, or the part that does.
	private @Nullable WitLocations owner(WitItem item) {
		if (this.offsets.containsKey(item)) {
			return this;
		}
		for (WitLocations part : this.parts) {
			WitLocations owner = part.owner(item);
			if (owner != null) {
				return owner;
			}
		}
		return null;
	}

	/**
	 * Returns the file the given item was written in, for a package read from several
	 * files.
	 * @param item the item, by identity
	 * @return the file's name as {@link WitPackageAssembler} was handed it, or
	 * {@code null} for an item parsed from a single text (or not parsed at all)
	 */
	public @Nullable String fileOf(WitItem item) {
		WitLocations owner = owner(item);
		return owner == null ? null : owner.file;
	}

	/**
	 * Returns an empty location table -- every lookup is unknown. For a document that was
	 * built in memory rather than parsed.
	 * @return the empty table
	 */
	public static WitLocations none() {
		return NONE;
	}

	/**
	 * Returns the character offset at which the given item starts.
	 * @param item the item, by identity
	 * @return the 0-based character offset (in the item's own file), or {@code -1} if the
	 * item did not come from this parse
	 */
	public int offsetOf(WitItem item) {
		WitLocations owner = owner(item);
		Integer offset = owner == null ? null : owner.offsets.get(item);
		return offset == null ? -1 : offset;
	}

	/**
	 * Returns the 1-based line on which the given item starts.
	 * @param item the item, by identity
	 * @return the line, or {@code 0} if the item did not come from this parse
	 */
	public int lineOf(WitItem item) {
		WitLocations owner = owner(item);
		return owner == null ? 0 : lineOf(owner.source, owner.offsetOf(item));
	}

	/**
	 * Returns the 1-based column at which the given item starts.
	 * @param item the item, by identity
	 * @return the column, or {@code 0} if the item did not come from this parse
	 */
	public int columnOf(WitItem item) {
		WitLocations owner = owner(item);
		return owner == null ? 0 : columnOf(owner.source, owner.offsetOf(item));
	}

	/**
	 * Returns the 1-based line containing a character offset.
	 * @param source the source text
	 * @param offset the character offset
	 * @return the line number
	 */
	public static int lineOf(String source, int offset) {
		int line = 1;
		for (int i = 0; i < offset && i < source.length(); i++) {
			if (source.charAt(i) == '\n') {
				line++;
			}
		}
		return line;
	}

	/**
	 * Returns the 1-based column of a character offset.
	 * @param source the source text
	 * @param offset the character offset
	 * @return the column number
	 */
	public static int columnOf(String source, int offset) {
		int column = 1;
		for (int i = 0; i < offset && i < source.length(); i++) {
			column = (source.charAt(i) == '\n') ? 1 : column + 1;
		}
		return column;
	}

}
