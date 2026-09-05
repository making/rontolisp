package am.ik.rontolisp;

/**
 * Base type for all Lisp values.
 */
public sealed interface LispVal permits LispInteger, LispBigInteger, LispRatio, LispDouble, LispSymbol, LispString,
		LispChar, LispCons, LispNil, LispTrue, LispFunction, LispLambda, LispHashTable, LispArray, LispFloatArray,
		LispIntVector, LispJavaObject, LispObjcObject, LispForeignPointer, LispFuture, LispThread, LispStream,
		LispInstance, LispStructLiteral, LispQuantizedMatrix {

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
