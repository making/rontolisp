package am.ik.wit;

import java.util.List;

/**
 * A parsed (or programmatically built) WIT file: an ordered list of top-level items —
 * optionally a {@link WitItem.PackageHeader}, then worlds / interfaces / explicit
 * {@link WitItem.PackageBlock}s.
 *
 * @param items the top-level items in source order
 */
public record WitDocument(List<WitItem> items) {

	/**
	 * The first top-level {@code world} in the document.
	 * @return the world
	 * @throws IllegalStateException when the document has no top-level world
	 */
	public WitItem.World world() {
		for (WitItem item : this.items) {
			if (item instanceof WitItem.World world) {
				return world;
			}
		}
		throw new IllegalStateException("WIT document has no top-level world");
	}

	/**
	 * Returns a copy of this document with the given transformation applied to its first
	 * top-level world.
	 * @param replacement the world to put in the first world's place
	 * @return a new document with the world replaced
	 * @throws IllegalStateException when the document has no top-level world
	 */
	public WitDocument withWorld(WitItem.World replacement) {
		int index = -1;
		for (int i = 0; i < this.items.size(); i++) {
			if (this.items.get(i) instanceof WitItem.World) {
				index = i;
				break;
			}
		}
		if (index < 0) {
			throw new IllegalStateException("WIT document has no top-level world");
		}
		List<WitItem> replaced = new java.util.ArrayList<>(this.items);
		replaced.set(index, replacement);
		return new WitDocument(List.copyOf(replaced));
	}

}
