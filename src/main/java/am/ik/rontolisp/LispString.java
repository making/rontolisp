package am.ik.rontolisp;

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
 */
public final class LispString implements LispVal {

	private int[] chars;

	/** The active length, or -1 when the string has no fill pointer. */
	private int fillPointer = -1;

	private boolean adjustable;

	/**
	 * Creates a string with the given content. Each Unicode code point of {@code value}
	 * fills exactly one slot; a supplementary code point does not split into surrogate
	 * halves.
	 * @param value the string content
	 */
	public LispString(String value) {
		this.chars = value.codePoints().toArray();
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
	}

	/**
	 * Returns the current content of the string as a Java {@code String}: the active
	 * prefix when a fill pointer is present, the whole buffer otherwise. Reassembled from
	 * code points via {@link String#String(int[], int, int)} so a supplementary code
	 * point expands to its UTF-16 surrogate pair at the Java boundary.
	 * @return the string content
	 */
	public String value() {
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
		for (int i = 0; i < count; i++) {
			int t = targetStart + i;
			if (t < 0 || t >= this.chars.length || srcSlot >= srcCpLen) {
				break;
			}
			int cp = source.codePointAt(srcCu);
			this.chars[t] = cp;
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
		return this.fillPointer >= 0 ? this.fillPointer : this.chars.length;
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
		return this.chars[cpIndex];
	}

	/**
	 * Returns the backing buffer's capacity (the array dimension of a fill-pointered
	 * string; equal to {@link #length()} otherwise). Capacity is in CODE POINTS.
	 * @return the capacity
	 */
	public int capacity() {
		return this.chars.length;
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
		if (fillPointer < 0 || fillPointer > this.chars.length) {
			throw new IllegalArgumentException("fill pointer " + fillPointer + " out of range 0.." + this.chars.length);
		}
		this.fillPointer = fillPointer;
	}

	/**
	 * Appends a code point at the fill pointer, growing the buffer when needed (the
	 * {@code vector-push-extend} operation). A supplementary code point still occupies
	 * exactly one slot.
	 * @param codePoint the code point to append
	 * @return the index the code point was stored at
	 */
	public int vectorPushExtend(int codePoint) {
		if (this.fillPointer < 0) {
			throw new IllegalStateException("string has no fill pointer");
		}
		if (this.fillPointer >= this.chars.length) {
			int[] grown = new int[this.chars.length == 0 ? 8 : this.chars.length * 2];
			System.arraycopy(this.chars, 0, grown, 0, this.chars.length);
			this.chars = grown;
		}
		int index = this.fillPointer;
		this.chars[index] = codePoint;
		this.fillPointer = index + 1;
		return index;
	}

	/**
	 * Resizes the backing buffer (the {@code adjust-array} operation), preserving the
	 * existing content and the fill pointer (clamped to the new capacity).
	 * @param newCapacity the new capacity (in code points)
	 */
	public void adjustCapacity(int newCapacity) {
		int[] resized = new int[newCapacity];
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
		this.chars[index] = codePoint;
	}

	/**
	 * Reads the code point at {@code index} from the backing buffer (capacity bounds,
	 * like {@link #setCharAt}). Supplementary code points come back unsplit.
	 * @param index the index to read
	 * @return the code point
	 */
	public int charAt(int index) {
		return this.chars[index];
	}

	@Override
	public String print() {
		return "\"" + this.value() + "\"";
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
