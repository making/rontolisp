package am.ik.rontolisp;

import java.util.Arrays;

/**
 * A string value. Backed by a mutable character buffer so that destructive operations
 * (notably {@code replace} into a {@code make-string} result) can update the string in
 * place -- Common Lisp strings are mutable sequences. Equality and hashing stay
 * content-based (as the former {@code record} definition provided), so runtime-built
 * strings still work as {@code equal} hash-table keys; a caller that mutates a string
 * used as a key gets the usual undefined behaviour it would in Common Lisp.
 */
public final class LispString implements LispVal {

	private char[] chars;

	/**
	 * Creates a string with the given content.
	 * @param value the string content
	 */
	public LispString(String value) {
		this.chars = value.toCharArray();
	}

	/**
	 * Returns the current content of the string.
	 * @return the string content
	 */
	public String value() {
		return new String(this.chars);
	}

	/**
	 * Destructively copies {@code count} characters from {@code source} (starting at
	 * {@code sourceStart}) into this string starting at {@code targetStart}, like the
	 * effect of {@code replace} on a string. Out-of-range copies are clamped to this
	 * string's bounds.
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
		return o instanceof LispString other && Arrays.equals(this.chars, other.chars);
	}

	@Override
	public int hashCode() {
		return Arrays.hashCode(this.chars);
	}

	@Override
	public String toString() {
		return "LispString[value=" + this.value() + "]";
	}

}
