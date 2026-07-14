package am.ik.wit;

import java.util.IdentityHashMap;
import java.util.Map;

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
 * @see WitParser#parseLocated(String)
 */
public final class WitLocations {

	private static final WitLocations NONE = new WitLocations("", new IdentityHashMap<>());

	private final String source;

	private final Map<WitItem, Integer> offsets;

	WitLocations(String source, Map<WitItem, Integer> offsets) {
		this.source = source;
		this.offsets = offsets;
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
	 * @return the 0-based character offset, or {@code -1} if the item did not come from
	 * this parse
	 */
	public int offsetOf(WitItem item) {
		Integer offset = this.offsets.get(item);
		return offset == null ? -1 : offset;
	}

	/**
	 * Returns the 1-based line on which the given item starts.
	 * @param item the item, by identity
	 * @return the line, or {@code 0} if the item did not come from this parse
	 */
	public int lineOf(WitItem item) {
		int offset = offsetOf(item);
		return offset < 0 ? 0 : lineOf(this.source, offset);
	}

	/**
	 * Returns the 1-based column at which the given item starts.
	 * @param item the item, by identity
	 * @return the column, or {@code 0} if the item did not come from this parse
	 */
	public int columnOf(WitItem item) {
		int offset = offsetOf(item);
		return offset < 0 ? 0 : columnOf(this.source, offset);
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
