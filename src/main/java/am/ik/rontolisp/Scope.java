package am.ik.rontolisp;

/**
 * Lexical scope interface for variable lookup. Used by {@link LispLambda} to hold closure
 * environments without creating circular package dependencies.
 */
public interface Scope {

	/**
	 * Look up a variable binding by name.
	 * @param name the variable name
	 * @return the bound value
	 */
	LispVal lookup(String name);

}
