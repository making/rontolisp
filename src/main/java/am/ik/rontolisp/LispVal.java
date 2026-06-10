package am.ik.rontolisp;

/**
 * Base type for all Lisp values.
 */
public sealed interface LispVal permits LispInteger, LispBigInteger, LispFraction, LispDouble, LispSymbol, LispString,
		LispCons, LispNil, LispTrue, LispFunction, LispLambda {

	/**
	 * Return the printed representation of this value.
	 * @return the string representation
	 */
	String print();

	/**
	 * Return the display representation of this value (without string quotes).
	 * @return the display string
	 */
	default String display() {
		return print();
	}

}
