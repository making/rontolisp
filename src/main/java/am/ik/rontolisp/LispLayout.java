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
 * @param capacity how many cells an instance RESERVES room for -- normally
 * {@code slotNames.size()}, but wider when {@code change-class} can turn an instance of
 * this type into one of a descendant (see {@link #withCapacity}), or when the type keeps
 * MACHINERY beside its declared slots ({@link #SYNONYM_STREAM}'s reader closure). The
 * cells past {@code slotNames.size()} are addressable by
 * {@code %obj-new}/{@code %obj-ref} / {@code %obj-set} and invisible to printing,
 * {@code equal} and slot introspection
 */
public record LispLayout(String tag, String printName, Kind kind, List<String> slotNames, List<LispVal> initforms,
		int capacity) {

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
		CLASS,
		/**
		 * The built-in pathname type ({@link #PATHNAME}, the one layout of this kind): an
		 * instance carries its namestring in slot 0 and prints as {@code #P"namestring"}
		 * under {@code prin1} and as the bare namestring under {@code princ} (CLHS
		 * 22.1.3.11), never in the slot-name syntax of the other two kinds.
		 */
		PATHNAME

	}

	/** The instance-tag prefix of a {@code defstruct} type. */
	public static final String STRUCT_TAG_PREFIX = "%struct-";

	/** The instance-tag prefix of a CLOS class. */
	public static final String CLASS_TAG_PREFIX = "%class-";

	/**
	 * The instance tag of the built-in pathname type. Spelled in upper case so prelude
	 * Lisp can quote it literally ({@code (%obj-is x '%PATHNAME)}) -- the reader upcases
	 * source symbols, so a mixed-case tag would be unspellable there.
	 */
	public static final String PATHNAME_TAG = "%PATHNAME";

	/**
	 * The layout of every pathname value: one slot holding the namestring. A FIXED layout
	 * rather than a registered type -- like the slot-unbound marker it is seeded into
	 * {@code ClosRegistry.layoutsByTag} as a LAYOUT ONLY (never a class, never a struct),
	 * so it joins no {@code typep} tag table, no {@code structure-object} /
	 * {@code standard-object} enumeration and no {@code %class-slot-defs} answer, while
	 * {@code %obj-new}/{@code %obj-is} resolve the tag on every backend. Being a constant
	 * is what lets {@code LispReader} build a {@code #P"..."} literal with no registry in
	 * scope.
	 */
	public static final LispLayout PATHNAME = new LispLayout(PATHNAME_TAG, "PATHNAME", Kind.PATHNAME,
			List.of("NAMESTRING"), List.of(LispNil.INSTANCE), 1);

	/**
	 * The instance tag of the built-in synonym-stream type, spelled in upper case for the
	 * same reason as {@link #PATHNAME_TAG}: prelude Lisp quotes it literally
	 * ({@code (%obj-is s '%SYNONYM-STREAM)}) and the reader upcases source symbols.
	 */
	public static final String SYNONYM_STREAM_TAG = "%SYNONYM-STREAM";

	/**
	 * The layout of every synonym-stream value: ONE declared slot holding the symbol
	 * {@code make-synonym-stream} was given, plus ONE reserved cell (hence capacity 2)
	 * holding the per-operation READER -- a zero-argument closure over a read of that
	 * variable, which is how "the symbol's current value, dynamic binding included"
	 * becomes a first-class value on all four backends. The reader is machinery, not a
	 * slot: it is outside {@link #slotNames()}, so it never reaches the printers (a
	 * synonym stream prints as {@code #<SYNONYM-STREAM :SYMBOL *STANDARD-OUTPUT*>} on
	 * every backend, where the closure itself prints differently) nor {@code equal}.
	 *
	 * <p>
	 * A FIXED layout, seeded into {@code ClosRegistry.layoutsByTag} as a LAYOUT ONLY like
	 * {@link #PATHNAME}, so {@code %obj-new}/{@code %obj-is} resolve the tag on every
	 * backend while the type joins no {@code typep} tag table, no
	 * {@code structure-object} / {@code standard-object} enumeration and no
	 * {@code %class-slot-defs} answer.
	 */
	public static final LispLayout SYNONYM_STREAM = new LispLayout(SYNONYM_STREAM_TAG, "SYNONYM-STREAM", Kind.CLASS,
			List.of("SYMBOL"), List.of(LispNil.INSTANCE), 2);

	/**
	 * The instance tag of the built-in OPEN-STREAM type, spelled in upper case for the
	 * same reason as {@link #PATHNAME_TAG}: prelude Lisp quotes it literally
	 * ({@code (%obj-is s '%STREAM)}) and the reader upcases source symbols.
	 */
	public static final String STREAM_TAG = "%STREAM";

	/**
	 * The layout of every OPEN stream value -- what {@code open},
	 * {@code make-string-input-stream}, {@code make-string-output-stream} and the socket
	 * constructors answer. Slot 0 is the BACKEND HANDLE the I/O primitives act on (a
	 * stream table index on the interpreter and the JVM, a WASI file descriptor or a
	 * negative string-stream record on the wasm backends); slot 1 is the {@link Kinds}
	 * keyword saying which kind of stream it is, which is what lets
	 * {@code (typep s 'file-stream)} and {@code (typep s 'string-stream)} be told apart
	 * identically on all four backends.
	 *
	 * <p>
	 * The handle is a DECLARED slot rather than machinery so {@code equal} keeps CL's
	 * "two streams are the same only when they are the same stream" -- a kind-only layout
	 * would make any two file streams {@code equal}. It is therefore also visible in the
	 * printed form, where the number is backend-local (a WASI fd is not a table index),
	 * exactly as the bare handle was before this type existed.
	 *
	 * <p>
	 * A FIXED layout, seeded into {@code ClosRegistry.layoutsByTag} as a LAYOUT ONLY like
	 * {@link #PATHNAME} and {@link #SYNONYM_STREAM}, so {@code %obj-new}/{@code %obj-is}
	 * resolve the tag on every backend while the type joins no {@code typep} tag table,
	 * no {@code structure-object} / {@code standard-object} enumeration and no
	 * {@code %class-slot-defs} answer.
	 */
	public static final LispLayout STREAM = new LispLayout(STREAM_TAG, "STREAM", Kind.CLASS, List.of("HANDLE", "KIND"),
			List.of(LispNil.INSTANCE, LispNil.INSTANCE), 2);

	/**
	 * The {@code KIND} slot values of {@link #STREAM}, one keyword per stream kind. They
	 * are compared with {@code equal} (the keyword is a plain interned name on every
	 * backend), and the two type tests that read them are
	 * {@code LispMacroExpander.makeTypeTest}'s {@code FILE-STREAM} and
	 * {@code STRING-STREAM} arms.
	 */
	public static final class Kinds {

		private Kinds() {
		}

		/** A stream {@code open} answers: {@code file-stream}. */
		public static final String FILE = ":FILE";

		/** A {@code make-string-input-stream} stream: {@code string-stream}. */
		public static final String STRING_INPUT = ":STRING-INPUT";

		/** A {@code make-string-output-stream} stream: {@code string-stream}. */
		public static final String STRING_OUTPUT = ":STRING-OUTPUT";

		/** A connected TCP/TLS socket stream. */
		public static final String SOCKET = ":SOCKET";

		/** A listening TCP/TLS server socket. */
		public static final String SOCKET_SERVER = ":SOCKET-SERVER";

		/** A buffered HTTP request body stream. */
		public static final String BODY = ":BODY";

		/**
		 * A process standard stream named by a reserved handle -- the value
		 * {@code *error-output*} holds. The {@code t} designator is NOT one of these: it
		 * stays a designator rather than a value.
		 */
		public static final String STANDARD = ":STANDARD";

	}

	/**
	 * Canonicalizes the collections so a layout is deeply immutable.
	 * @param tag the instance tag symbol name
	 * @param printName the type name as printed
	 * @param kind the printing kind
	 * @param slotNames the slot base names in layout order
	 * @param initforms the slot initforms in layout order
	 * @param capacity the reserved slot count (at least {@code slotNames.size()})
	 */
	public LispLayout {
		slotNames = List.copyOf(slotNames);
		initforms = List.copyOf(initforms);
		capacity = Math.max(capacity, slotNames.size());
	}

	/**
	 * Builds the layout of a {@code defstruct} type.
	 * @param structName the canonical struct name (as spelled in the defstruct)
	 * @param slotNames the package-stripped slot names in declaration order
	 * @param initforms the slot initforms in declaration order
	 * @return the struct layout
	 */
	public static LispLayout ofStruct(String structName, List<String> slotNames, List<LispVal> initforms) {
		return new LispLayout(STRUCT_TAG_PREFIX + structName, structName, Kind.STRUCT, slotNames, initforms,
				slotNames.size());
	}

	/**
	 * Builds the layout of a CLOS class.
	 * @param className the canonical class name
	 * @param slotNames the package-stripped slot names, inherited slots first
	 * @param initforms the slot initforms in the same order
	 * @return the class layout
	 */
	public static LispLayout ofClass(String className, List<String> slotNames, List<LispVal> initforms) {
		return new LispLayout(CLASS_TAG_PREFIX + className, className, Kind.CLASS, slotNames, initforms,
				slotNames.size());
	}

	/**
	 * The same layout with a wider reserved slot count. {@code change-class} turns an
	 * instance into one of a DESCENDANT class in place, and on the JVM the instance IS
	 * its {@code Object[]}, which cannot grow without losing object identity -- so every
	 * class a {@code change-class} target descends from reserves the target's slot count
	 * up front. The reservation is per program: only classes actually named by a
	 * {@code change-class} in the source widen anything.
	 * @param reserved the new reserved slot count (ignored when narrower than the
	 * current)
	 * @return the widened layout
	 */
	public LispLayout withCapacity(int reserved) {
		return reserved <= this.capacity ? this
				: new LispLayout(this.tag, this.printName, this.kind, this.slotNames, this.initforms, reserved);
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
	 * The text opening an instance of this layout: {@code "#S("}, {@code "#<"} or
	 * {@code "#P"} (the pathname renderer never appends slot names after it).
	 * @return the opening delimiter
	 */
	public String openDelimiter() {
		return switch (this.kind) {
			case STRUCT -> "#S(";
			case CLASS -> "#<";
			case PATHNAME -> "#P";
		};
	}

	/**
	 * The text closing an instance of this layout: {@code ")"}, {@code ">"} or nothing (a
	 * pathname's namestring closes itself).
	 * @return the closing delimiter
	 */
	public String closeDelimiter() {
		return switch (this.kind) {
			case STRUCT -> ")";
			case CLASS -> ">";
			case PATHNAME -> "";
		};
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
