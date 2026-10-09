package am.ik.rontolisp.runtime;

import java.util.AbstractList;
import java.util.Objects;

/**
 * What {@code (java:view value items :list ...)} makes: a read-only
 * {@code java.util.List} standing for a Lisp sequence, its elements the items converted
 * as {@code Object} arguments when it was made. {@code get} and {@code size} read them,
 * every write is the {@code UnsupportedOperationException} of {@code AbstractList},
 * {@code equals} and {@code hashCode} are the {@code List} contract's, and
 * {@code toString} is what its printer answers for the value (the {@code List} spelling
 * without one). As an argument it is itself where its class fits, else -- where an array
 * is expected, after every way to pass it whole -- an array of its items
 * ({@link #items()}).
 */
public class RontoJavaListView extends AbstractList<Object> implements RontoJavaValue {

	private final Object value;

	private final Object items;

	private final Object[] elements;

	private final Object printer;

	private final String className;

	// How the printer (and a vector's order) is called; null without either.
	final RontoJavaCalls calls;

	/**
	 * @param value the Lisp value the view stands for
	 * @param items the Lisp sequence its elements were made from
	 * @param elements the items as {@code Object} arguments
	 * @param printer the Lisp function its {@code toString} calls with the value, or null
	 * @param className the class Java's messages name it by, or null for its own
	 * @param calls how the printer is called, or null without one
	 */
	public RontoJavaListView(Object value, Object items, Object[] elements, Object printer, String className,
			RontoJavaCalls calls) {
		this.value = value;
		this.items = items;
		this.elements = elements;
		this.printer = printer;
		this.className = className;
		this.calls = calls;
	}

	@Override
	public Object get(int index) {
		return this.elements[Objects.checkIndex(index, this.elements.length)];
	}

	@Override
	public int size() {
		return this.elements.length;
	}

	@Override
	public Object[] toArray() {
		return this.elements.clone();
	}

	@Override
	public String toString() {
		return this.printer == null ? super.toString() : this.calls.text(this.printer, this.value);
	}

	@Override
	public Object value() {
		return this.value;
	}

	@Override
	public String className() {
		return this.className != null ? this.className : getClass().getName();
	}

	/**
	 * @return the Lisp sequence its elements were made from: what it converts as where an
	 * array is expected
	 */
	public Object items() {
		return this.items;
	}

}
