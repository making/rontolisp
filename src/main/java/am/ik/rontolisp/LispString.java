package am.ik.rontolisp;

import org.jspecify.annotations.Nullable;

/**
 * A string value. Backed by a mutable code-point buffer so that destructive operations
 * (notably {@code replace} into a {@code make-string} result) can update the string in
 * place -- Common Lisp strings are mutable sequences. Equality and hashing stay
 * content-based (as the former {@code record} definition provided), so runtime-built
 * strings still work as {@code equal} hash-table keys; a caller that mutates a string
 * used as a key gets the usual undefined behaviour it would in Common Lisp.
 *
 * <p>
 * A string may carry a fill pointer (a {@code make-array :element-type 'character
 * :fill-pointer ...} result): the fill pointer is the string's effective length --
 * {@code value()}/{@code length()} see only the active prefix -- while the buffer's
 * capacity is the array dimension. {@code vector-push-extend} appends past the fill
 * pointer, growing the buffer when the string is adjustable, and {@code adjust-array}
 * resizes the capacity explicitly.
 *
 * <p>
 * The backing buffer stores ONE Unicode code point per slot ({@code int[]}), matching the
 * JVM ({@code int[]{cp}} per char-vec slot) and WASM ({@code TYPE_CHAR} per slot) compile
 * paths. A supplementary code point (above {@code U+FFFF}) occupies exactly one indexed
 * slot on every backend, so {@code (setf (schar s i) (code-char 128512))} stores in one
 * indexed step and prints as its glyph on every backend.
 *
 * <p>
 * A string may also be a VIEW over another string ({@code make-array :element-type
 * 'character :displaced-to s :displaced-index-offset k}): it owns no buffer, and every
 * read and write resolves to the target's current buffer at {@code offset + index}, so a
 * write through the view is visible through the target and vice versa. Views chain (a
 * view of a view), and each hop is resolved at access time, so a view keeps aliasing a
 * target whose buffer was reallocated by {@code vector-push-extend}/{@code adjust-array}.
 */
public final class LispString implements LispVal {

	private static final int[] NO_CHARS = new int[0];

	private int[] chars;

	/** The active length, or -1 when the string has no fill pointer. */
	private int fillPointer = -1;

	private boolean adjustable;

	// Whether this object is the SOURCE CONSTANT the reader built for a "..." in the
	// program text. It is shared by every evaluation of that form for the life of the
	// program, so a destructive write into it would rewrite the source; the indexed-write
	// lowering rebinds the place instead (see LispEvaluator's %schar-set arm).
	private boolean sourceLiteral;

	// The displacement target (make-array :displaced-to), or null when the string owns
	// its buffer. A view owns no buffer (chars is the shared empty array): every access
	// walks the chain through storage()/base(). A view MAY carry a fill pointer and the
	// :adjustable flag (CLHS forbids only :initial-element beside :displaced-to), so its
	// CAPACITY is its dimension (viewLength) while its LENGTH is the fill pointer when
	// one is present. The two fields below are cleared in place when a full
	// fill-pointered view grows: vector-push-extend un-displaces rather than running off
	// the end of someone else's buffer, exactly as SBCL does.
	@Nullable private LispString displacedTo;

	private int displacedOffset;

	private int viewLength;

	/**
	 * Creates a string with the given content. Each Unicode code point of {@code value}
	 * fills exactly one slot; a supplementary code point does not split into surrogate
	 * halves.
	 * @param value the string content
	 */
	public LispString(String value) {
		this.chars = value.codePoints().toArray();
		this.displacedTo = null;
		this.displacedOffset = 0;
		this.viewLength = 0;
	}

	/**
	 * Creates a string with the given backing content, fill pointer and adjustability
	 * (the {@code make-array :element-type 'character} shapes). Slots are in code-point
	 * units on every axis: capacity, fill pointer, and index.
	 * @param value the backing buffer content (its code-point length is the capacity)
	 * @param fillPointer the active length (in code points), or -1 for no fill pointer
	 * @param adjustable whether the string may grow
	 */
	public LispString(String value, int fillPointer, boolean adjustable) {
		this.chars = value.codePoints().toArray();
		this.fillPointer = fillPointer;
		this.adjustable = adjustable;
		this.displacedTo = null;
		this.displacedOffset = 0;
		this.viewLength = 0;
	}

	/**
	 * Creates a VIEW over {@code target}: a string of {@code length} characters sharing
	 * the target's buffer from {@code offset} on. The view owns no storage, has no fill
	 * pointer and is not adjustable; reads and writes go straight to the target's current
	 * buffer, so mutation is visible in both directions.
	 * @param target the string supplying the storage
	 * @param offset the character index in {@code target} where the view starts
	 * @param length the view's length in characters
	 */
	public LispString(LispString target, int offset, int length) {
		this(target, offset, length, -1, false);
	}

	/**
	 * Creates a VIEW carrying a fill pointer and/or the {@code :adjustable} flag. The
	 * view still owns no storage; {@code length} is its CAPACITY (its one dimension) and
	 * {@code fillPointer} its active length.
	 * @param target the string supplying the storage
	 * @param offset the character index in {@code target} where the view starts
	 * @param length the view's capacity in characters
	 * @param fillPointer the active length (in code points), or -1 for no fill pointer
	 * @param adjustable whether the view was created {@code :adjustable}
	 */
	public LispString(LispString target, int offset, int length, int fillPointer, boolean adjustable) {
		this.chars = NO_CHARS;
		this.displacedTo = target;
		this.displacedOffset = offset;
		this.viewLength = length;
		this.fillPointer = fillPointer;
		this.adjustable = adjustable;
	}

	/**
	 * Creates the SOURCE CONSTANT for a {@code "..."} in the program text. Only the
	 * reader builds one: every evaluation of that form answers this same object, which is
	 * why an indexed write through it is a rebind of the place rather than a mutation
	 * (see {@code .kb/string-write-runtime.md}).
	 * @param value the string content
	 * @return the literal string
	 */
	public static LispString literal(String value) {
		LispString string = new LispString(value);
		string.sourceLiteral = true;
		return string;
	}

	/**
	 * Whether this object is the source constant the reader built for a {@code "..."} in
	 * the program text (as opposed to a string a running program allocated).
	 * @return true for a source literal
	 */
	public boolean sourceLiteral() {
		return this.sourceLiteral;
	}

	// Whether this string is the value of a FRESH-STRING producer the pure-builtin fold
	// evaluated at compile time (string-upcase/concatenate 'string/subseq of literals).
	// Such a value must materialize as a fresh MUTABLE string per evaluation -- the
	// folder spells it as (%str-fresh "...") so the compile backends emit the literal
	// plus one mutable-copy wrap, instead of sharing one literal across evaluations
	// (.kb/pure-builtin-fold.md).
	private boolean foldFresh;

	/**
	 * Creates a fold-produced FRESH-STRING value: the compile-time result of a
	 * fresh-string producer over literal arguments, which each evaluation must answer as
	 * a fresh mutable string. Only {@code PureBuiltinFolder} builds one.
	 * @param value the string content
	 * @return the marked string
	 */
	public static LispString foldFresh(String value) {
		LispString string = new LispString(value);
		string.foldFresh = true;
		return string;
	}

	/**
	 * Whether this is a fold-produced fresh-string value (see
	 * {@link #foldFresh(String)}).
	 * @return true for a fold-fresh value
	 */
	public boolean foldFresh() {
		return this.foldFresh;
	}

	/**
	 * Answers a FRESH string equal to this one with the code point at {@code index}
	 * replaced -- the rebuild the compiled backends' {@code %schar-set-runtime} performs
	 * for an immutable string. The result is an ordinary allocated string, not a source
	 * literal.
	 * @param index the code-point index to replace
	 * @param codePoint the replacement code point
	 * @return the rebuilt string
	 */
	public LispString withCharAt(int index, int codePoint) {
		int length = this.length();
		StringBuilder rebuilt = new StringBuilder(length + 1);
		for (int i = 0; i < length; i++) {
			rebuilt.appendCodePoint(i == index ? codePoint : this.charAt(i));
		}
		return new LispString(rebuilt.toString());
	}

	/**
	 * Answers a FRESH ordinary string holding this one's content -- the copy a
	 * DESTRUCTIVE BULK operation ({@code replace}, {@code fill}, and the
	 * {@code (setf (subseq ...))} that lowers to {@code replace}) works on when its
	 * target is a source literal, so the source constant is never written (see
	 * {@code .kb/string-write-runtime.md}).
	 * <p>
	 * Unlike {@link #withCharAt} there is no place to rebind -- {@code replace} is a
	 * FUNCTION call, not a place form -- so the copy reaches the program only as the
	 * operation's return value, which is exactly what the three compile paths already
	 * answer for the same program.
	 * @return the mutable copy
	 */
	public LispString copyForBulkWrite() {
		return new LispString(this.value());
	}

	/**
	 * Returns the displacement target ({@code make-array :displaced-to}), or {@code null}
	 * when the string owns its buffer.
	 * @return the target string, or {@code null}
	 */
	@Nullable public LispString displacedTo() {
		return this.displacedTo;
	}

	/**
	 * Returns the displacement offset ({@code :displaced-index-offset}; 0 when the string
	 * is not a view).
	 * @return the character offset into the displacement target
	 */
	public int displacedOffset() {
		return this.displacedOffset;
	}

	// The string that actually owns the buffer: this one, or the end of the view chain.
	private LispString storage() {
		LispString s = this;
		while (s.displacedTo != null) {
			s = s.displacedTo;
		}
		return s;
	}

	// The index in storage()'s buffer that this string's index 0 maps to. Each hop is
	// read now rather than at creation, so a reallocated target buffer is followed.
	private int base() {
		int offset = 0;
		LispString s = this;
		while (s.displacedTo != null) {
			offset += s.displacedOffset;
			s = s.displacedTo;
		}
		return offset;
	}

	/**
	 * Returns the current content of the string as a Java {@code String}: the active
	 * prefix when a fill pointer is present, the whole buffer otherwise. Reassembled from
	 * code points via {@link String#String(int[], int, int)} so a supplementary code
	 * point expands to its UTF-16 surrogate pair at the Java boundary.
	 * @return the string content
	 */
	public String value() {
		if (this.displacedTo != null) {
			return new String(this.storage().chars, this.base(), this.length());
		}
		int end = this.fillPointer >= 0 ? this.fillPointer : this.chars.length;
		return new String(this.chars, 0, end);
	}

	/**
	 * Destructively copies {@code count} code points from {@code source} (starting at
	 * character index {@code sourceStart}) into this string starting at
	 * {@code targetStart}, like the effect of {@code replace} on a string. The walk is BY
	 * CODE POINT on both sides so a supplementary code point in the source round-trips
	 * into one target slot. Out-of-range copies are clamped to this string's capacity.
	 * @param targetStart the code-point index in this string to start writing at
	 * @param source the source string to copy from
	 * @param sourceStart the character index (in code points) in {@code source} to start
	 * reading from
	 * @param count the number of code points to copy
	 */
	public void replaceInPlace(int targetStart, String source, int sourceStart, int count) {
		int srcCpLen = source.codePointCount(0, source.length());
		int srcCu = source.offsetByCodePoints(0, Math.min(sourceStart, srcCpLen));
		int srcSlot = sourceStart;
		int capacity = this.capacity();
		for (int i = 0; i < count; i++) {
			int t = targetStart + i;
			if (t < 0 || t >= capacity || srcSlot >= srcCpLen) {
				break;
			}
			int cp = source.codePointAt(srcCu);
			this.setCharAt(t, cp);
			srcCu += Character.charCount(cp);
			srcSlot++;
		}
	}

	/**
	 * Returns the number of Unicode code points (character-visible length) in the string
	 * -- {@link #length()} and {@link #codePointCount()} are the same value now that the
	 * backing store is code-point per slot. A supplementary code point counts as one
	 * character on every backend.
	 * @return the code-point length
	 */
	public int length() {
		if (this.fillPointer >= 0) {
			return this.fillPointer;
		}
		return this.displacedTo != null ? this.viewLength : this.chars.length;
	}

	/**
	 * Returns the number of Unicode code points in the string. Kept as a distinct name
	 * for callers that document indexing by code point at their call site; equivalent to
	 * {@link #length()}.
	 * @return the code-point count
	 */
	public int codePointCount() {
		return length();
	}

	/**
	 * Returns the Unicode code point at character index {@code cpIndex} (0-based). Since
	 * the backing store is one code point per slot, this is a trivial array lookup.
	 * @param cpIndex the 0-based character index
	 * @return the code point at that position
	 */
	public int codePointAt(int cpIndex) {
		return this.charAt(cpIndex);
	}

	/**
	 * Returns the backing buffer's capacity (the array dimension of a fill-pointered
	 * string; equal to {@link #length()} otherwise). Capacity is in CODE POINTS.
	 * @return the capacity
	 */
	public int capacity() {
		return this.displacedTo != null ? this.viewLength : this.chars.length;
	}

	/**
	 * Returns the fill pointer (in code points), or -1 when the string has none.
	 * @return the fill pointer or -1
	 */
	public int fillPointer() {
		return this.fillPointer;
	}

	/**
	 * Whether the string was created adjustable.
	 * @return true when adjustable
	 */
	public boolean adjustable() {
		return this.adjustable;
	}

	/**
	 * Moves the fill pointer.
	 * @param fillPointer the new active length (0..capacity, in code points)
	 */
	public void setFillPointer(int fillPointer) {
		int capacity = this.capacity();
		if (fillPointer < 0 || fillPointer > capacity) {
			throw new IllegalArgumentException("fill pointer " + fillPointer + " out of range 0.." + capacity);
		}
		this.fillPointer = fillPointer;
	}

	/**
	 * Appends a code point at the fill pointer, growing the buffer when needed (the
	 * {@code vector-push-extend} operation). A supplementary code point still occupies
	 * exactly one slot.
	 * @param codePoint the code point to append
	 * @param extension the number of code points to grow by when full, or
	 * {@link ArrayGrowth#NO_EXTENSION} for the default policy
	 * @return the index the code point was stored at
	 */
	public int vectorPushExtend(int codePoint, int extension) {
		if (this.fillPointer < 0) {
			throw new IllegalStateException("string has no fill pointer");
		}
		if (this.fillPointer >= this.capacity()) {
			// A full DISPLACED view stops being a view: its characters are copied into a
			// buffer of its own and the displacement is dropped, so the growth never runs
			// past the end of someone else's buffer (SBCL 2.2.9 does the same).
			this.undisplace();
		}
		if (this.fillPointer >= this.chars.length) {
			int[] grown = new int[ArrayGrowth.grownCapacity(this.chars.length, extension)];
			// The slots the growth OPENS read back through aref/char, so they take the
			// character type's own zero rather than the int[]'s -- the same fill
			// make-array gives an unsupplied element (ArrayElementTypes).
			java.util.Arrays.fill(grown, ArrayElementTypes.DEFAULT_CHARACTER);
			System.arraycopy(this.chars, 0, grown, 0, this.chars.length);
			this.chars = grown;
		}
		int index = this.fillPointer;
		// setCharAt, not this.chars: a view that still fits writes THROUGH to the
		// target's buffer (its own chars is the shared empty array).
		this.setCharAt(index, codePoint);
		this.fillPointer = index + 1;
		return index;
	}

	/**
	 * Copies a view's current characters into a buffer of its own and drops the
	 * displacement, keeping its capacity, fill pointer and adjustable flag. A no-op for a
	 * string that owns its buffer already.
	 */
	public void undisplace() {
		if (this.displacedTo == null) {
			return;
		}
		int[] own = new int[this.viewLength];
		System.arraycopy(this.storage().chars, this.base(), own, 0, this.viewLength);
		this.displacedTo = null;
		this.displacedOffset = 0;
		this.viewLength = 0;
		this.chars = own;
	}

	/**
	 * Resizes the backing buffer (the {@code adjust-array} operation), preserving the
	 * existing content and the fill pointer (clamped to the new capacity).
	 * @param newCapacity the new capacity (in code points)
	 * @param fill the code point the slots the resize OPENS take -- the
	 * {@code :initial-element}, or the character type's own zero
	 * ({@link ArrayElementTypes#DEFAULT_CHARACTER}) when it was not supplied
	 */
	public void adjustCapacity(int newCapacity, int fill) {
		this.undisplace();
		int[] resized = new int[newCapacity];
		java.util.Arrays.fill(resized, fill);
		System.arraycopy(this.chars, 0, resized, 0, Math.min(this.chars.length, newCapacity));
		this.chars = resized;
		if (this.fillPointer > newCapacity) {
			this.fillPointer = newCapacity;
		}
	}

	/**
	 * Destructively replaces the code point at {@code index}, like the effect of
	 * {@code (setf (schar s index) c)}. The caller checks bounds (the capacity, so a
	 * write between the fill pointer and the capacity is allowed, as in CL).
	 * @param index the index to write at
	 * @param codePoint the replacement code point
	 */
	public void setCharAt(int index, int codePoint) {
		if (this.displacedTo != null) {
			this.storage().chars[this.base() + index] = codePoint;
			return;
		}
		this.chars[index] = codePoint;
	}

	/**
	 * Reads the code point at {@code index} from the backing buffer (capacity bounds,
	 * like {@link #setCharAt}). Supplementary code points come back unsplit.
	 * @param index the index to read
	 * @return the code point
	 */
	public int charAt(int index) {
		if (this.displacedTo != null) {
			return this.storage().chars[this.base() + index];
		}
		return this.chars[index];
	}

	/**
	 * Returns the quote-framed spelling WITHOUT the {@code *print-escape*} escapes -- the
	 * COMPILE-PATH STORAGE form, not a printed representation.
	 *
	 * <p>
	 * Both compile backends encode a string value as its content framed in {@code "}
	 * bytes, and use the leading {@code "} as the discriminator that tells a string from
	 * a symbol name (see {@code .kb/core-representation.md}, "symbolp/stringp"). That
	 * framing is part of the VALUE, so it must stay verbatim: the escaping belongs to the
	 * printer ({@link #print()}) and is applied by the emitted runtime at print time, on
	 * the content only. A storage site that used {@link #print()} would bake the escapes
	 * into the value itself and make {@code length}/{@code char} see them.
	 * @return the raw quote-framed content
	 */
	public String literal() {
		return "\"" + this.value() + "\"";
	}

	/**
	 * Escapes a string's content for the {@code *print-escape*} = {@code t} renderers
	 * ({@code print}/{@code prin1}/{@code ~S}/{@code write-to-string}/
	 * {@code prin1-to-string}), so that the reader can read the result back.
	 *
	 * <p>
	 * CLHS 22.1.3.4: only the characters whose syntax type is single-escape ({@code \})
	 * and the string terminator ({@code "}) are preceded by a {@code \}; every other
	 * character -- a newline included -- is printed literally. Our reader ALSO accepts
	 * {@code \n} / {@code \t} on input, but writing those back would be a different
	 * string than the one printed (CL prints a literal newline), so the writer
	 * deliberately covers less than the reader.
	 * @param content the raw string content
	 * @return the content with every {@code "} and {@code \} preceded by a {@code \}
	 */
	public static String escape(String content) {
		int n = content.length();
		StringBuilder sb = null;
		for (int i = 0; i < n; i++) {
			char c = content.charAt(i);
			if (c == '"' || c == '\\') {
				if (sb == null) {
					sb = new StringBuilder(n + 8).append(content, 0, i);
				}
				sb.append('\\');
			}
			if (sb != null) {
				sb.append(c);
			}
		}
		return sb == null ? content : sb.toString();
	}

	@Override
	public String print() {
		return "\"" + escape(this.value()) + "\"";
	}

	@Override
	public String display() {
		return this.value();
	}

	@Override
	public boolean equals(Object o) {
		return o instanceof LispString other && this.value().equals(other.value());
	}

	@Override
	public int hashCode() {
		return this.value().hashCode();
	}

	@Override
	public String toString() {
		return "LispString[value=" + this.value() + "]";
	}

}
