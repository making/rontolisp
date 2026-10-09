package am.ik.rontolisp.runtime;

import java.util.Objects;

/**
 * What {@code (java:handle value text hash order class)} makes: a Java object standing
 * for a Lisp value Java has none of. Java sees the text as its {@code toString} --
 * without one, {@code Object}'s spelling, the class and the hex hash -- and equals
 * handles of one class by their texts, or, made with no hash, only a handle of the very
 * same value (whose hash is then the value's identity hash). It orders a handle of its
 * own class by its order text, or asks a Lisp function of its value and the object
 * compared with; made with neither, and against a handle of another class,
 * {@code compareTo} is the {@code ClassCastException} a cast to the class would throw. A
 * handle standing for a real number is a {@link RontoJavaNumberHandle}.
 */
public final class RontoJavaHandle implements RontoJavaValue, Comparable<Object> {

	/**
	 * No order: {@code compareTo} is the cast failure of a class that is no Comparable.
	 */
	public static final int ORDER_NONE = 0;

	/** By the order text, against a handle of the same class ordered by one too. */
	public static final int ORDER_TEXT = 1;

	/** By a Lisp function of the value and the object compared with. */
	public static final int ORDER_FUNCTION = 2;

	private final Object value;

	// What toString answers; null for Object's spelling.
	private final String text;

	// Whether the handle is equal only to a handle of the very same value.
	private final boolean identity;

	private final int hash;

	private final int orderMode;

	// The order text, or the Lisp function; null without an order.
	private final Object order;

	// The class Java's messages name it by; null for the handle's own.
	private final String className;

	// How the order function is called; null unless the order is one.
	private final RontoJavaCalls calls;

	/**
	 * @param value the Lisp value the handle stands for
	 * @param text its {@code toString}, or null for {@code Object}'s spelling
	 * @param identity whether it is equal only to a handle of the very same value
	 * @param hash its {@code hashCode} unless {@code identity}
	 * @param orderMode {@link #ORDER_NONE}, {@link #ORDER_TEXT} or
	 * {@link #ORDER_FUNCTION}
	 * @param order the order text or the Lisp function, as the mode says
	 * @param className the class Java's messages name it by, or null
	 * @param calls how the order function is called, or null without one
	 */
	public RontoJavaHandle(Object value, String text, boolean identity, int hash, int orderMode, Object order,
			String className, RontoJavaCalls calls) {
		this.value = value;
		this.text = text;
		this.identity = identity;
		this.hash = hash;
		this.orderMode = orderMode;
		this.order = order;
		this.className = className;
		this.calls = calls;
	}

	@Override
	public Object value() {
		return this.value;
	}

	@Override
	public String className() {
		return this.className != null ? this.className : getClass().getName();
	}

	@Override
	public String toString() {
		return this.text != null ? this.text : className() + "@" + Integer.toHexString(hashCode());
	}

	@Override
	public boolean equals(Object other) {
		if (this == other) {
			return true;
		}
		if (!(other instanceof RontoJavaHandle handle) || handle.identity != this.identity
				|| !Objects.equals(handle.className, this.className)) {
			return false;
		}
		return this.identity ? handle.value == this.value : Objects.equals(handle.text, this.text);
	}

	@Override
	public int hashCode() {
		return this.identity ? System.identityHashCode(this.value) : this.hash;
	}

	@Override
	public int compareTo(Object other) {
		String mine = className();
		if (this.orderMode == ORDER_NONE) {
			throw castFailure(mine, "java.lang.Comparable");
		}
		String theirs = classNameOf(Objects.requireNonNull(other));
		if (other instanceof RontoJavaValue && !theirs.equals(mine)) {
			throw castFailure(theirs, mine);
		}
		if (this.orderMode == ORDER_TEXT) {
			if (other instanceof RontoJavaHandle handle && handle.orderMode == ORDER_TEXT) {
				return ((String) this.order).compareTo((String) handle.order);
			}
			throw castFailure(theirs, mine);
		}
		Integer answer = this.calls.order(this.order, this.value, other);
		if (answer == null) {
			throw castFailure(theirs, mine);
		}
		return answer;
	}

	/**
	 * @param object a Java object
	 * @return the class Java's messages name it by
	 */
	static String classNameOf(Object object) {
		return object instanceof RontoJavaValue value ? value.className() : object.getClass().getName();
	}

	/**
	 * @param from the class of the object cast
	 * @param to the class it is cast to
	 * @return the {@code ClassCastException} the JDK's cast throws, without its module
	 * tail
	 */
	static ClassCastException castFailure(String from, String to) {
		return new ClassCastException("class " + from + " cannot be cast to class " + to);
	}

}
