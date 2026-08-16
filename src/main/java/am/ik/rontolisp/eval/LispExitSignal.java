package am.ik.rontolisp.eval;

/**
 * The interpreter's {@code %host-exit}: {@code uiop:quit} asking for the process to end
 * with a status code.
 *
 * <p>
 * It is a control-flow signal, not a condition. {@code handler-case} /
 * {@code ignore-errors} catch {@link LispEvalException} and therefore cannot see it, and
 * {@code unwind-protect} deliberately runs NO cleanup for it: the compiled backends spell
 * the same primitive {@code System.exit} / {@code proc_exit} /
 * {@code wasi:cli/exit.exit-with-code}, which end the process where they stand, so an
 * interpreter that unwound would be the one backend where a {@code quit} inside an
 * {@code unwind-protect} still ran something. Quitting means the same thing on all four.
 *
 * <p>
 * The exception ESCAPES {@code RontoLispCli.run} rather than being turned into a
 * {@code System.exit} at the point of the call: {@code run} is embedded (the tests, the
 * playground, anything holding a {@code RontoLispCli}), and killing the calling JVM is
 * not a call's decision to make -- the same rule the JVM backend's uncaught-condition
 * handler follows. {@code main} is the process entry point and is where the code becomes
 * the process's.
 */
public final class LispExitSignal extends RuntimeException {

	/** The process status code {@code uiop:quit} was given. */
	private final int code;

	/**
	 * Creates the signal for a status code.
	 * @param code the process status code {@code uiop:quit} was given
	 */
	public LispExitSignal(int code) {
		super(null, null, false, false);
		this.code = code;
	}

	/**
	 * The status code the program asked to exit with.
	 * @return the status code
	 */
	public int code() {
		return this.code;
	}

}
