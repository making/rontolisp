package am.ik.rontolisp;

/**
 * Base type for all Lisp values.
 */
public sealed interface LispVal
		permits LispInteger, LispDouble, LispSymbol, LispString, LispCons, LispNil, LispTrue, LispFunction, LispLambda {

	String print();

}
