package am.ik.rontolisp.eval;

import am.ik.rontolisp.ClosRegistry;
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

	/** The seeded condition class this error carries, or {@code null} for a plain one. */
	private final @Nullable String conditionClassName;

	/**
	 * Create a new evaluation exception with the given message.
	 * @param message the error message
	 */
	public LispEvalException(String message) {
		this(message, null);
	}

	/**
	 * Create a new evaluation exception a built-in raised, naming the CONDITION CLASS the
	 * catching form should synthesize for it -- {@code type-error} for a bad {@code car},
	 * {@code division-by-zero} for a zero divisor, and so on. The alternative (typing the
	 * message at the catching end) would have every backend pattern-matching prose;
	 * naming the class where the failure is DETECTED is the only place that actually
	 * knows.
	 * @param className the condition class name, e.g.
	 * {@link ClosRegistry#TYPE_ERROR_CLASS_NAME}
	 * @param message the error message
	 * @return the exception to throw
	 */
	public static LispEvalException ofClass(String className, String message) {
		return new LispEvalException(message, null, className);
	}

	/**
	 * Create a new evaluation exception carrying a condition object.
	 * @param message the error message
	 * @param condition the condition instance (a tagged list), or null
	 */
	public LispEvalException(String message, @Nullable LispVal condition) {
		this(message, condition, null);
	}

	private LispEvalException(String message, @Nullable LispVal condition, @Nullable String conditionClassName) {
		super(message);
		this.condition = condition;
		this.conditionClassName = conditionClassName;
	}

	/**
	 * The condition object signaled with this error, or null when the error was signaled
	 * without one.
	 * @return the condition instance or null
	 */
	public @Nullable LispVal condition() {
		return this.condition;
	}

	/**
	 * The condition class a catching form synthesizes an instance of when this error
	 * carries no condition object, or null for the {@code simple-error} default.
	 * @return the condition class name or null
	 */
	public @Nullable String conditionClassName() {
		return this.conditionClassName;
	}

}
