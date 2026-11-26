package am.ik.rontolisp.compiler;

/**
 * The imports of a {@code --no-wasi} wasm-GC core module built {@code --host-timer},
 * whose host waits for it, and the export its host calls for the program's own turn: the
 * names the splice ({@code eval/HostTimerLibrary}), the backend and the glue
 * ({@link HostGlueEmitter}) read from one place, since those packages may not import each
 * other ({@code .kb/wasm-export-no-wasi.md}, "Randomness, the clock, and the host
 * opt-ins").
 *
 * <ul>
 * <li>{@code env.sleep(ms)} -- a wait for a time: the host suspends the call (JSPI) and
 * moves the module's clock before it returns. The module stays the call's.</li>
 * <li>{@code env.idle(token, at)} -- a call with nothing to do: the host suspends it and
 * may call the module for others meanwhile, until {@code env.ring(token)} or, where
 * {@code at} (nanoseconds since the Unix epoch) is not 0, that time; it moves the clock
 * before it returns. The module's re-entry guard is lifted for the wait.</li>
 * <li>{@code env.ring(token)} -- the call idling under {@code token} has something to
 * do.</li>
 * <li>{@code env.wake(at)} -- the program has something to do with no call idling: the
 * host calls {@code __ronto_turn} at {@code at} (nanoseconds since the Unix epoch; 0, at
 * once), through the queue its calls take.</li>
 * <li>{@code __ronto_turn()} -- the export of that turn: the threads take their turns
 * until none can go on, and what they left is handed on again.</li>
 * </ul>
 */
public final class HostTimer {

	/** The import module, the host's own, as every {@code --no-wasi} opt-in's. */
	public static final String MODULE = "env";

	/** {@code env.sleep(ms)}: {@code (:float) -> :void}, suspending. */
	public static final String SLEEP_FIELD = "sleep";

	/** {@code env.idle(token, at)}: {@code (:s32 :s64) -> :void}, suspending. */
	public static final String IDLE_FIELD = "idle";

	/** {@code env.ring(token)}: {@code (:s32) -> :void}. */
	public static final String RING_FIELD = "ring";

	/** {@code env.wake(at)}: {@code (:s64) -> :void}. */
	public static final String WAKE_FIELD = "wake";

	/** The export the host calls for the program's own turn. */
	public static final String TURN_EXPORT = "__ronto_turn";

	/** The function {@code sleep} is lowered to under the flag. */
	public static final String SLEEP_FUNCTION = "RONTOLISP::%HOST-SLEEP";

	/** The function a call idles through. */
	public static final String IDLE_FUNCTION = "RONTOLISP::%HOST-IDLE";

	/** The function a call is rung through. */
	public static final String RING_FUNCTION = "RONTOLISP::%HOST-RING";

	/** The function the program asks its host for a turn through. */
	public static final String WAKE_FUNCTION = "RONTOLISP::%HOST-WAKE";

	/** The function {@link #TURN_EXPORT} exports. */
	public static final String TURN_FUNCTION = "RONTOLISP::%HOST-TURN";

	private HostTimer() {
	}

	/**
	 * Whether an import is one of the flag's, which the glue implements rather than asks
	 * its host for.
	 * @param name the Lisp name the import is declared under
	 * @param module the import module
	 * @param field the import field
	 * @return {@code true} for {@code env.sleep}, {@code env.idle}, {@code env.ring} and
	 * {@code env.wake} under the flag's own names
	 */
	public static boolean imports(String name, String module, String field) {
		return MODULE.equals(module) && (SLEEP_FUNCTION.equals(name) && SLEEP_FIELD.equals(field)
				|| IDLE_FUNCTION.equals(name) && IDLE_FIELD.equals(field)
				|| RING_FUNCTION.equals(name) && RING_FIELD.equals(field)
				|| WAKE_FUNCTION.equals(name) && WAKE_FIELD.equals(field));
	}

	/**
	 * Whether an export is the program's own turn: the flag's function under the flag's
	 * export name, which the glue calls rather than offers as an entry point.
	 * @param name the Lisp name the export is declared for
	 * @param exportName the export name
	 * @return {@code true} for {@code __ronto_turn} over the flag's own function
	 */
	public static boolean turns(String name, String exportName) {
		return TURN_FUNCTION.equals(name) && TURN_EXPORT.equals(exportName);
	}

	/**
	 * Whether an import is the one a call idles through, whose wait lifts the module's
	 * re-entry guard.
	 * @param name the Lisp name the import is declared under
	 * @param module the import module
	 * @param field the import field
	 * @return {@code true} for {@code env.idle} under the flag's own name
	 */
	public static boolean idles(String name, String module, String field) {
		return MODULE.equals(module) && IDLE_FUNCTION.equals(name) && IDLE_FIELD.equals(field);
	}

}
