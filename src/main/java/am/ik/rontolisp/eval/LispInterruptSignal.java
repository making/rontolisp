package am.ik.rontolisp.eval;

/**
 * An evaluation an interrupt stopped ({@link LispEvaluator#interrupt}): what an nREPL
 * {@code interrupt} makes of the evaluation it names, at its next function call or loop
 * step.
 *
 * <p>
 * A control-flow signal, not a condition, like the oracle's {@code ThreadDeath}:
 * {@code handler-case} and a Clojure {@code catch} take {@link LispEvalException} and
 * never see it, while every {@code unwind-protect} cleanup and {@code finally} runs on
 * the way out.
 */
public final class LispInterruptSignal extends RuntimeException {

	private static final long serialVersionUID = 1L;

	/** The signal; it carries no stack trace, which names only interpreter frames. */
	public LispInterruptSignal() {
		super("interrupted", null, false, false);
	}

}
