package am.ik.rontolisp;

/**
 * A string value. Backed by a mutable character buffer so that destructive operations
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
 */
public final class LispString implements LispVal {

	private char[] chars;

	/** The active length, or -1 when the string has no fill pointer. */
	private int fillPointer = -1;

	private boolean adjustable;

	/**
	 * Creates a string with the given content.
	 * @param value the string content
	 */
	public LispString(String value) {
		this.chars = value.toCharArray();
	}

	/**
	 * Creates a string with the given backing content, fill pointer and adjustability
	 * (the {@code make-array :element-type 'character} shapes).
	 * @param value the backing buffer content (its length is the capacity)
	 * @param fillPointer the active length, or -1 for no fill pointer
	 * @param adjustable whether the string may grow
	 */
	public LispString(String value, int fillPointer, boolean adjustable) {
		this.chars = value.toCharArray();
		this.fillPointer = fillPointer;
		this.adjustable = adjustable;
	}

	/**
	 * Returns the current content of the string: the active prefix when a fill pointer is
	 * present, the whole buffer otherwise.
	 * @return the string content
	 */
	public String value() {
		return this.fillPointer >= 0 ? new String(this.chars, 0, this.fillPointer) : new String(this.chars);
	}

	/**
	 * Destructively copies {@code count} characters from {@code source} (starting at
	 * {@code sourceStart}) into this string starting at {@code targetStart}, like the
	 * effect of {@code replace} on a string. Out-of-range copies are clamped to this
	 * string's capacity.
	 * @param targetStart the index in this string to start writing at
	 * @param source the source string to copy from
	 * @param sourceStart the index in {@code source} to start reading from
	 * @param count the number of characters to copy
	 */
	public void replaceInPlace(int targetStart, String source, int sourceStart, int count) {
		for (int i = 0; i < count; i++) {
			int t = targetStart + i;
			int s = sourceStart + i;
			if (t < 0 || t >= this.chars.length || s < 0 || s >= source.length()) {
				break;
			}
			this.chars[t] = source.charAt(s);
		}
	}

	/**
	 * Returns the number of UTF-16 code units in the string (the fill pointer when
	 * present). This is the size of the backing buffer and NOT the character-visible
	 * length -- callers implementing Common Lisp's
	 * {@code length}/{@code char}/{@code aref} should use {@link #codePointCount()} /
	 * {@link #codePointAt(int)} / {@link #codePointByteIndex(int)} so a supplementary
	 * code point (a surrogate pair occupies two code units) still counts as one
	 * character.
	 * @return the code-unit length
	 */
	public int length() {
		return this.fillPointer >= 0 ? this.fillPointer : this.chars.length;
	}

	/**
	 * Returns the number of Unicode code points (character-visible length) in the string,
	 * matching Java's {@code String.codePointCount(0, length())}. A supplementary code
	 * point counts as one character; every ASCII / BMP-only string agrees with
	 * {@link #length()}.
	 * @return the code-point count
	 */
	public int codePointCount() {
		int n = length();
		return Character.codePointCount(this.chars, 0, n);
	}

	/**
	 * Returns the Unicode code point at character index {@code cpIndex} (0-based). Walks
	 * the buffer with {@link Character#offsetByCodePoints(char[], int, int, int, int)} so
	 * a supplementary code point comes back as a single 21-bit value.
	 * @param cpIndex the 0-based character index
	 * @return the code point at that position
	 */
	public int codePointAt(int cpIndex) {
		int codeUnit = codePointByteIndex(cpIndex);
		return Character.codePointAt(this.chars, codeUnit);
	}

	/**
	 * Returns the UTF-16 code-unit offset at which the {@code cpIndex}-th character
	 * begins. Useful for taking substrings on a character range: convert a start/end
	 * character range to code-unit offsets and slice through {@code value()} /
	 * {@code new String(...)}. Passing the code-point count returns the code-unit length
	 * (a valid one-past-the-end for slicing).
	 * @param cpIndex the 0-based character index (in {@code [0, codePointCount()]})
	 * @return the code-unit offset
	 */
	public int codePointByteIndex(int cpIndex) {
		return Character.offsetByCodePoints(this.chars, 0, length(), 0, cpIndex);
	}

	/**
	 * Returns the backing buffer's capacity (the array dimension of a fill-pointered
	 * string; equal to {@link #length()} otherwise).
	 * @return the capacity
	 */
	public int capacity() {
		return this.chars.length;
	}

	/**
	 * Returns the fill pointer, or -1 when the string has none.
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
	 * @param fillPointer the new active length (0..capacity)
	 */
	public void setFillPointer(int fillPointer) {
		if (fillPointer < 0 || fillPointer > this.chars.length) {
			throw new IllegalArgumentException("fill pointer " + fillPointer + " out of range 0.." + this.chars.length);
		}
		this.fillPointer = fillPointer;
	}

	/**
	 * Appends a character at the fill pointer, growing the buffer when needed (the
	 * {@code vector-push-extend} operation).
	 * @param c the character to append
	 * @return the index the character was stored at
	 */
	public int vectorPushExtend(char c) {
		if (this.fillPointer < 0) {
			throw new IllegalStateException("string has no fill pointer");
		}
		if (this.fillPointer >= this.chars.length) {
			char[] grown = new char[this.chars.length == 0 ? 8 : this.chars.length * 2];
			System.arraycopy(this.chars, 0, grown, 0, this.chars.length);
			this.chars = grown;
		}
		int index = this.fillPointer;
		this.chars[index] = c;
		this.fillPointer = index + 1;
		return index;
	}

	/**
	 * Resizes the backing buffer (the {@code adjust-array} operation), preserving the
	 * existing content and the fill pointer (clamped to the new capacity).
	 * @param newCapacity the new capacity
	 */
	public void adjustCapacity(int newCapacity) {
		char[] resized = new char[newCapacity];
		System.arraycopy(this.chars, 0, resized, 0, Math.min(this.chars.length, newCapacity));
		this.chars = resized;
		if (this.fillPointer > newCapacity) {
			this.fillPointer = newCapacity;
		}
	}

	/**
	 * Destructively replaces the character at {@code index}, like the effect of
	 * {@code (setf (schar s index) c)}. The caller checks bounds (the capacity, so a
	 * write between the fill pointer and the capacity is allowed, as in CL).
	 * @param index the index to write at
	 * @param c the replacement character
	 */
	public void setCharAt(int index, char c) {
		this.chars[index] = c;
	}

	/**
	 * Reads the character at {@code index} from the backing buffer (capacity bounds, like
	 * {@link #setCharAt}).
	 * @param index the index to read
	 * @return the character
	 */
	public char charAt(int index) {
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
