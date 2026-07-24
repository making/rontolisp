package am.ik.rontolisp;

import java.util.List;

/**
 * The immutable shape descriptor of a {@code defstruct} or {@code defclass} type: the
 * instance tag, the name as printed, the kind, the ordered slot base names and the slot
 * initforms.
 *
 * <p>
 * A layout is what makes an instance <em>self-describing</em>. Every instance value
 * carries a reference to its layout, so rendering an instance never needs a registry
 * lookup: the interpreter reads {@link #slotNames()} straight off the value, the JVM
 * backend bakes the layout as a {@code String[]} constant that sits in slot 0 of the
 * instance array, and the WASM backend bakes it as a linear-memory record whose address
 * sits in field 0 of the instance struct. That is why {@code #S(POINT :X 1 :Y 2)} and
 * {@code #<PT :X 5>} can be produced by one fixed-size loop per backend instead of three
 * hand-written tag-to-slot-name lookup tables that would have to agree byte for byte.
 *
 * <p>
 * Layouts are registered in {@link ClosRegistry} (one per evaluator, one per compilation)
 * and are interned there, so instance-of tests compare layout identity rather than tag
 * text.
 *
 * @param tag the instance tag symbol name, {@code %struct-<name>} or
 * {@code %class-<name>}
 * @param printName the type name as printed, i.e. the tag without its prefix (a
 * package-qualified type keeps its qualifier, e.g. {@code GEO::PT})
 * @param kind whether instances print in {@code #S(...)} or {@code #<...>} syntax
 * @param slotNames the package-stripped slot names, in layout order (for a class:
 * inherited slots first)
 * @param initforms the per-slot default expressions, in the same order as
 * {@code slotNames}
 */
public record LispLayout(String tag, String printName, Kind kind, List<String> slotNames, List<LispVal> initforms) {

	/** Whether a layout describes a {@code defstruct} type or a CLOS class. */
	public enum Kind {

		/**
		 * A {@code defstruct} type; instances print as {@code #S(NAME :SLOT value ...)}.
		 */
		STRUCT,
		/**
		 * A {@code defclass} / {@code define-condition} type; instances print as
		 * {@code #<NAME :SLOT value ...>}.
		 */
		CLASS

	}

	/** The instance-tag prefix of a {@code defstruct} type. */
	public static final String STRUCT_TAG_PREFIX = "%struct-";

	/** The instance-tag prefix of a CLOS class. */
	public static final String CLASS_TAG_PREFIX = "%class-";

	/**
	 * Canonicalizes the collections so a layout is deeply immutable.
	 * @param tag the instance tag symbol name
	 * @param printName the type name as printed
	 * @param kind the printing kind
	 * @param slotNames the slot base names in layout order
	 * @param initforms the slot initforms in layout order
	 */
	public LispLayout {
		slotNames = List.copyOf(slotNames);
		initforms = List.copyOf(initforms);
	}

	/**
	 * Builds the layout of a {@code defstruct} type.
	 * @param structName the canonical struct name (as spelled in the defstruct)
	 * @param slotNames the package-stripped slot names in declaration order
	 * @param initforms the slot initforms in declaration order
	 * @return the struct layout
	 */
	public static LispLayout ofStruct(String structName, List<String> slotNames, List<LispVal> initforms) {
		return new LispLayout(STRUCT_TAG_PREFIX + structName, structName, Kind.STRUCT, slotNames, initforms);
	}

	/**
	 * Builds the layout of a CLOS class.
	 * @param className the canonical class name
	 * @param slotNames the package-stripped slot names, inherited slots first
	 * @param initforms the slot initforms in the same order
	 * @return the class layout
	 */
	public static LispLayout ofClass(String className, List<String> slotNames, List<LispVal> initforms) {
		return new LispLayout(CLASS_TAG_PREFIX + className, className, Kind.CLASS, slotNames, initforms);
	}

	/**
	 * The number of slots an instance of this layout holds.
	 * @return the slot count
	 */
	public int slotCount() {
		return this.slotNames.size();
	}

	/**
	 * The 0-based index of a slot by its package-stripped base name.
	 * @param baseName the slot base name
	 * @return the index, or {@code -1} when this layout has no such slot
	 */
	public int slotIndex(String baseName) {
		return this.slotNames.indexOf(baseName);
	}

	/**
	 * The text opening an instance of this layout: {@code "#S("} or {@code "#<"}.
	 * @return the opening delimiter
	 */
	public String openDelimiter() {
		return this.kind == Kind.STRUCT ? "#S(" : "#<";
	}

	/**
	 * The text closing an instance of this layout: {@code ")"} or {@code ">"}.
	 * @return the closing delimiter
	 */
	public String closeDelimiter() {
		return this.kind == Kind.STRUCT ? ")" : ">";
	}

	/**
	 * The type name carried by an instance tag, i.e. the tag without its
	 * {@code %struct-}/{@code %class-} prefix.
	 * @param tag the instance tag symbol name
	 * @return the type name, or null when the name is not an instance tag
	 */
	@org.jspecify.annotations.Nullable
	public static String printNameOfTag(String tag) {
		if (tag.startsWith(STRUCT_TAG_PREFIX)) {
			return tag.substring(STRUCT_TAG_PREFIX.length());
		}
		if (tag.startsWith(CLASS_TAG_PREFIX)) {
			return tag.substring(CLASS_TAG_PREFIX.length());
		}
		return null;
	}

}
