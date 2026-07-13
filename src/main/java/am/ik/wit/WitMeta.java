package am.ik.wit;

import java.util.List;

/**
 * The documentation comment and gate attributes attached to a WIT item.
 *
 * @param docs the {@code ///} doc-comment lines immediately preceding the item, each
 * line's text after the {@code ///} marker verbatim (typically starting with a space; an
 * empty string for a bare {@code ///} line); empty when the item has no doc comment
 * @param gates the {@code @since} / {@code @unstable} / {@code @deprecated} attributes in
 * source order; empty when ungated
 */
public record WitMeta(List<String> docs, List<Gate> gates) {

	private static final WitMeta NONE = new WitMeta(List.of(), List.of());

	/**
	 * The empty meta (no docs, no gates).
	 * @return the shared empty instance
	 */
	public static WitMeta none() {
		return NONE;
	}

	/**
	 * Whether this meta carries neither docs nor gates.
	 * @return {@code true} when there is nothing to print
	 */
	public boolean isEmpty() {
		return this.docs.isEmpty() && this.gates.isEmpty();
	}

	/**
	 * One gate attribute, e.g. {@code @since(version = 0.3.0)} or
	 * {@code @unstable(feature = clocks-timezone)}.
	 *
	 * @param name the attribute name after {@code @}, e.g. {@code since}
	 * @param key the argument key, e.g. {@code version} or {@code feature}
	 * @param value the argument value verbatim, e.g. {@code 0.3.0}
	 */
	public record Gate(String name, String key, String value) {
	}

}
