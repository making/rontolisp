package am.ik.rontolisp.runtime;

/**
 * A {@link RontoJavaHandle} standing for a real number: a {@code java.lang.Number} of the
 * number's value -- its {@code doubleValue}, {@code longValue} and {@code intValue} the
 * ones its maker computed (a ratio's double its {@code DECIMAL64} quotient and its
 * {@code intValue} that double's, as Clojure's {@code Ratio}) -- and otherwise the handle
 * it holds: text, equality, hash and order.
 */
public final class RontoJavaNumberHandle extends Number implements RontoJavaValue, Comparable<Object> {

	private static final long serialVersionUID = 1L;

	private final RontoJavaHandle handle;

	private final double doubleValue;

	private final long longValue;

	private final int intValue;

	/**
	 * @param handle the handle it is
	 * @param doubleValue its {@code doubleValue}
	 * @param longValue its {@code longValue}
	 * @param intValue its {@code intValue}
	 */
	public RontoJavaNumberHandle(RontoJavaHandle handle, double doubleValue, long longValue, int intValue) {
		this.handle = handle;
		this.doubleValue = doubleValue;
		this.longValue = longValue;
		this.intValue = intValue;
	}

	@Override
	public Object value() {
		return this.handle.value();
	}

	@Override
	public String className() {
		return this.handle.className();
	}

	@Override
	public int intValue() {
		return this.intValue;
	}

	@Override
	public long longValue() {
		return this.longValue;
	}

	@Override
	public float floatValue() {
		return (float) this.doubleValue;
	}

	@Override
	public double doubleValue() {
		return this.doubleValue;
	}

	@Override
	public String toString() {
		return this.handle.toString();
	}

	@Override
	public boolean equals(Object other) {
		return other instanceof RontoJavaNumberHandle number && number.handle.equals(this.handle);
	}

	@Override
	public int hashCode() {
		return this.handle.hashCode();
	}

	@Override
	public int compareTo(Object other) {
		return this.handle.compareTo(other);
	}

}
