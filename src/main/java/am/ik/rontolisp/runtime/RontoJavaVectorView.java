package am.ik.rontolisp.runtime;

import java.util.Objects;
import java.util.RandomAccess;

/**
 * What {@code (java:view value items :vector ...)} makes: a {@link RontoJavaListView}
 * that is {@code RandomAccess} and {@code Comparable}, as Clojure's vector is.
 * {@code compareTo} asks a Lisp function of the value and the object compared with (its
 * answer's sign), and is the {@code ClassCastException} of a cast to the view's class
 * when the function refuses the object -- or of a cast to {@code Comparable} when the
 * view has no order.
 */
public final class RontoJavaVectorView extends RontoJavaListView implements RandomAccess, Comparable<Object> {

	// The Lisp function compareTo asks; null without an order.
	private final Object order;

	/**
	 * @param value the Lisp value the view stands for
	 * @param items the Lisp sequence its elements were made from
	 * @param elements the items as {@code Object} arguments
	 * @param printer the Lisp function its {@code toString} calls with the value, or null
	 * @param order the Lisp function its {@code compareTo} calls, or null
	 * @param className the class Java's messages name it by, or null for its own
	 * @param calls how the printer and the order are called, or null without either
	 */
	public RontoJavaVectorView(Object value, Object items, Object[] elements, Object printer, Object order,
			String className, RontoJavaCalls calls) {
		super(value, items, elements, printer, className, calls);
		this.order = order;
	}

	@Override
	public int compareTo(Object other) {
		String mine = className();
		if (this.order == null) {
			throw RontoJavaHandle.castFailure(mine, "java.lang.Comparable");
		}
		Integer answer = this.calls.order(this.order, value(), Objects.requireNonNull(other));
		if (answer == null) {
			throw RontoJavaHandle.castFailure(RontoJavaHandle.classNameOf(other), mine);
		}
		return answer;
	}

}
