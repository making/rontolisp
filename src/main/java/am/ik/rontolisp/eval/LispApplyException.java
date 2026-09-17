package am.ik.rontolisp.eval;

import java.util.List;

import org.jspecify.annotations.Nullable;

import am.ik.rontolisp.LispVal;

/**
 * An application of a value that is not a function: {@code (funcall 3 1)}, or a symbol
 * designator naming no function. The message and the condition class are exactly what a
 * plain {@link LispEvalException} carried for it; what this adds is the value and its
 * arguments, so a front end whose language words the failure differently -- a Scheme
 * session saying {@code #f is not a procedure} -- can do so without parsing prose.
 */
public final class LispApplyException extends LispEvalException {

	private final transient LispVal function;

	private final transient List<LispVal> arguments;

	LispApplyException(LispEvalException failure, LispVal function, List<LispVal> arguments) {
		super(failure);
		this.function = function;
		this.arguments = List.copyOf(arguments);
	}

	/**
	 * The value that was applied.
	 * @return the value
	 */
	public LispVal function() {
		return this.function;
	}

	/**
	 * The arguments it was applied to, evaluated.
	 * @return the arguments
	 */
	public List<LispVal> arguments() {
		return this.arguments;
	}

	/**
	 * The application failure somewhere in a failure's cause chain: the handler-bind seam
	 * wraps the original.
	 * @param failure what a form raised
	 * @return the application failure, or {@code null}
	 */
	public static @Nullable LispApplyException in(Throwable failure) {
		for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
			if (cause instanceof LispApplyException apply) {
				return apply;
			}
		}
		return null;
	}

}
