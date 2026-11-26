package am.ik.wit;

import java.util.Set;

/**
 * WIT identifier escaping. An identifier that collides with a WIT keyword is written
 * {@code %}-prefixed in the source text ({@code %type}, {@code %flags}, {@code %stream} —
 * all three occur in the WASI WIT itself), but the {@code %} is <strong>source escaping
 * only</strong>: the identifier, and the component-model {@code label} it becomes, is the
 * bare word.
 *
 * <p>
 * The model therefore holds the bare identifier: {@link WitParser} strips the {@code %}
 * and {@link WitPrinter} puts it back on exactly the words that need it, so a document
 * still round-trips byte-for-byte while every consumer (a binder, a name check, a
 * component export label) sees the real name.
 */
final class WitIdentifiers {

	// The WIT keywords. An identifier equal to one of these must be %-escaped in the
	// source text; anything else is written bare.
	private static final Set<String> KEYWORDS = Set.of("use", "type", "func", "u8", "u16", "u32", "u64", "s8", "s16",
			"s32", "s64", "f32", "f64", "char", "resource", "own", "borrow", "record", "flags", "variant", "enum",
			"bool", "string", "option", "result", "future", "stream", "list", "tuple", "as", "from", "static",
			"interface", "world", "import", "export", "package", "constructor", "include", "with", "async");

	private WitIdentifiers() {
	}

	/** Returns the bare identifier a (possibly {@code %}-escaped) source word denotes. */
	static String unescape(String word) {
		return word.startsWith("%") ? word.substring(1) : word;
	}

	/**
	 * Returns the source spelling of an identifier: {@code %}-escaped iff it is a
	 * keyword.
	 */
	static String escape(String identifier) {
		return KEYWORDS.contains(identifier) ? "%" + identifier : identifier;
	}

}
