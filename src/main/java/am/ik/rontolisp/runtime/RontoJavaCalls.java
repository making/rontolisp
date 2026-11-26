package am.ik.rontolisp.runtime;

/**
 * How a {@link RontoJavaValue} calls back into the Lisp program that made it: the
 * interpreter through its evaluator, a compiled program through the class it generates
 * for it ({@code <Program>$JavaCalls}) over its own {@code _apply}. What the Lisp
 * function raises leaves through the record a {@code java:reify} callback's does, so the
 * {@code java:} site whose Java call it reaches throws it on unchanged.
 */
public interface RontoJavaCalls {

	/**
	 * {@code (printer value)}: a view's {@code toString}.
	 * @param printer the Lisp function
	 * @param value the Lisp value the view stands for
	 * @return its answer's text: a string's own, any other value's printed spelling
	 */
	String text(Object printer, Object value);

	/**
	 * {@code (order value other)}, {@code other} the Lisp value the Java object stands
	 * for: a {@code compareTo}.
	 * @param order the Lisp function
	 * @param value the Lisp value the handle or view stands for
	 * @param other the Java object compared with
	 * @return the sign of its answer, or null when it answers no real number: the order
	 * refuses the object
	 */
	Integer order(Object order, Object value, Object other);

}
