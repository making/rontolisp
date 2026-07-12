package am.ik.rontolisp.eval;

import am.ik.rontolisp.LispVal;

import org.jspecify.annotations.Nullable;

/**
 * Exception thrown during Lisp expression evaluation. When the error was signaled with a
 * condition object (the typed {@code (error 'type ...)} / {@code (error obj)} designator
 * forms), the CLOS-subset tagged-list instance rides along in {@link #condition()} so
 * {@code handler-case} can dispatch on its type; a plain error carries {@code null} and
 * the handler synthesizes a {@code simple-error} from the message.
 */
public class LispEvalException extends RuntimeException {

	private final transient @Nullable LispVal condition;

	/**
	 * Create a new evaluation exception with the given message.
	 * @param message the error message
	 */
	public LispEvalException(String message) {
		this(message, null);
	}

	/**
	 * Create a new evaluation exception carrying a condition object.
	 * @param message the error message
	 * @param condition the condition instance (a tagged list), or null
	 */
	public LispEvalException(String message, @Nullable LispVal condition) {
		super(message);
		this.condition = condition;
	}

	/**
	 * The condition object signaled with this error, or null when the error was signaled
	 * without one.
	 * @return the condition instance or null
	 */
	public @Nullable LispVal condition() {
		return this.condition;
	}

}
