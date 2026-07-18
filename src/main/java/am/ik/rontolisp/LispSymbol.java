package am.ik.rontolisp;

/**
 * A symbol value.
 *
 * @param name the symbol name
 */
public record LispSymbol(String name) implements LispVal {

	/**
	 * Returns whether this symbol is a keyword (starts with {@code :}).
	 * @return {@code true} if the symbol name starts with ':'
	 */
	public boolean isKeyword() {
		return !this.name.isEmpty() && this.name.charAt(0) == ':';
	}

	/**
	 * Returns the symbol name without the leading package marker: a keyword's {@code :}
	 * and an uninterned symbol's {@code #:} are markers, not part of the name. This is
	 * what {@code symbol-name}, {@code princ} and {@code ~A} yield (matching CL apart
	 * from case, which stays as read); {@code prin1} keeps the marker via
	 * {@link #print()}.
	 * @param name the stored symbol name
	 * @return the name without a leading ':' or '#:'
	 */
	public static String displayName(String name) {
		if (name.startsWith("#:")) {
			return name.substring(2);
		}
		if (name.startsWith(":")) {
			return name.substring(1);
		}
		return name;
	}

	@Override
	public String print() {
		return this.name;
	}

	@Override
	public String display() {
		return displayName(this.name);
	}

}
