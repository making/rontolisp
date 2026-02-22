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

	@Override
	public String print() {
		return this.name;
	}

}
