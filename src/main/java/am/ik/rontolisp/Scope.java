package am.ik.rontolisp;

/**
 * Lexical scope interface for variable lookup. Used by {@link LispLambda} to hold closure
 * environments without creating circular package dependencies.
 */
public interface Scope {

	LispVal lookup(String name);

}
