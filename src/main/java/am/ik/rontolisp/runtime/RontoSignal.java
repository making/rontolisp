package am.ik.rontolisp.runtime;

import java.lang.invoke.MethodHandle;

/**
 * The throwable of a compiled program's signal whose text is its condition's report,
 * which runs program code: rendered when {@link #getMessage()} first asks, by the program
 * method the handle names, and not at all for a signal nothing prints
 * ({@code .kb/error-handling.md}, "A signal renders nothing"). Otherwise the
 * {@code RuntimeException} every other signal throws, down to the host's echo of it.
 *
 * <p>
 * The class imports nothing of rontolisp, so it travels beside the program
 * ({@code .kb/jvm-export.md}, "What travels").
 */
public final class RontoSignal extends RuntimeException {

	private final transient MethodHandle render;

	private final transient Object condition;

	private final transient Object fallback;

	/** What {@link #render} answered; null until {@link #getMessage()} first asks. */
	// This package cannot import the annotation that would say so (it would travel with
	// the class).
	@SuppressWarnings("NullAway.Init")
	private String text;

	/**
	 * @param render the program's {@code (Object, Object) -> String}
	 * @param condition the condition instance, its first argument
	 * @param fallback what the signal's site says of it (nil being null when compiled),
	 * its second
	 */
	@SuppressWarnings("NullAway")
	public RontoSignal(MethodHandle render, Object condition, Object fallback) {
		this.render = render;
		this.condition = condition;
		this.fallback = fallback;
	}

	@Override
	public synchronized String getMessage() {
		if (this.text == null) {
			try {
				this.text = (String) this.render.invokeExact(this.condition, this.fallback);
			}
			catch (Throwable ex) {
				// Past what the program's own guard catches: a report too deep to run.
				// The report is what is unavailable, not the failure this stands for.
				this.text = "#<error printing a condition: " + ex + ">";
			}
		}
		return this.text;
	}

	@Override
	public String toString() {
		return RuntimeException.class.getName() + ": " + getMessage();
	}

}
