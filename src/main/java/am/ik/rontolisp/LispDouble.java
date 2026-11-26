package am.ik.rontolisp;

/**
 * A floating-point number value. There is one float type here, so its spelling is nothing
 * a program can observe through a standard operator, and two floats are one value
 * whatever they were read from.
 *
 * @param value the double value
 * @param doubleSpelled whether it was read from a token with a {@code d} or {@code l}
 * exponent marker: a library that types a float by how it is spelled (coalton's
 * {@code F64} literals, {@code .kb/asdf.md}) asks through
 * {@code rontolisp::%double-spelled-p}, and nothing else reads it
 */
public record LispDouble(double value, boolean doubleSpelled) implements LispVal {

	/**
	 * A float no token spelled as a double.
	 * @param value the double value
	 */
	public LispDouble(double value) {
		this(value, false);
	}

	@Override
	public String print() {
		return FloatText.text(this);
	}

	/**
	 * Two floats are the same value by their bits, as {@code eql} asks.
	 * @param other the value to compare to
	 * @return whether it is a float of the same value
	 */
	@Override
	public boolean equals(Object other) {
		return other instanceof LispDouble that && Double.compare(this.value, that.value) == 0;
	}

	@Override
	public int hashCode() {
		return Double.hashCode(this.value);
	}

}
