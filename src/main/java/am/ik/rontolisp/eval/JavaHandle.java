package am.ik.rontolisp.eval;

import org.jspecify.annotations.Nullable;

import am.ik.rontolisp.LispVal;

/**
 * What {@code (java:handle value "text" hash "order")} makes on the interpreter: a Java
 * object that stands for a Lisp value Java has no value of. Java sees the text as its
 * {@code toString}; two handles of one text are equal, its hash is its {@code hashCode},
 * and handles order by their order texts -- so a handle keys a {@code HashMap} and sorts
 * in a {@code TreeSet} as the value would in its own language. Wherever Java hands one
 * back -- a call's answer, an array's element, a callback's argument -- {@code java:}
 * answers the value ({@link JavaInterop#unmarshal}). A compiled program generates its own
 * class of the same shape ({@code codegen.jvm.JvmJavaImplementations}).
 */
public final class JavaHandle implements Comparable<Object> {

	private final LispVal value;

	private final String text;

	private final int hash;

	private final String order;

	/**
	 * @param value the Lisp value the handle stands for
	 * @param text what Java sees of it, and what it equals by
	 * @param hash its {@code hashCode}
	 * @param order what it orders by
	 */
	JavaHandle(LispVal value, String text, int hash, String order) {
		this.value = value;
		this.text = text;
		this.hash = hash;
		this.order = order;
	}

	/**
	 * @return the Lisp value the handle stands for
	 */
	LispVal value() {
		return this.value;
	}

	@Override
	public String toString() {
		return this.text;
	}

	@Override
	public boolean equals(@Nullable Object other) {
		return other instanceof JavaHandle handle && handle.text.equals(this.text);
	}

	@Override
	public int hashCode() {
		return this.hash;
	}

	/**
	 * Orders handles by their order texts; anything else is the
	 * {@code ClassCastException} a {@code Comparable} of another class throws.
	 */
	@Override
	public int compareTo(Object other) {
		return this.order.compareTo(((JavaHandle) other).order);
	}

}
