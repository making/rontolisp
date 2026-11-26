package am.ik.rontolisp.compiler;

/**
 * The three imports of a {@code --no-wasi} wasm-GC core module built
 * {@code --host-timer}, whose host waits for it: the names the splice
 * ({@code eval/HostTimerLibrary}), the backend and the glue ({@link HostGlueEmitter})
 * read from one place, since those packages may not import each other
 * ({@code .kb/wasm-export-no-wasi.md}, "Randomness, the clock, and the host opt-ins").
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

	/** The function {@code sleep} is lowered to under the flag. */
	public static final String SLEEP_FUNCTION = "RONTOLISP::%HOST-SLEEP";

	/** The function a call idles through. */
	public static final String IDLE_FUNCTION = "RONTOLISP::%HOST-IDLE";

	/** The function a call is rung through. */
	public static final String RING_FUNCTION = "RONTOLISP::%HOST-RING";

	private HostTimer() {
	}

	/**
	 * Whether an import is one of the three: a function of the flag's, which the glue
	 * implements rather than asks its host for.
	 * @param name the Lisp name the import is declared under
	 * @param module the import module
	 * @param field the import field
	 * @return {@code true} for {@code env.sleep}, {@code env.idle} and {@code env.ring}
	 * under the flag's own names
	 */
	public static boolean imports(String name, String module, String field) {
		return MODULE.equals(module) && (SLEEP_FUNCTION.equals(name) && SLEEP_FIELD.equals(field)
				|| IDLE_FUNCTION.equals(name) && IDLE_FIELD.equals(field)
				|| RING_FUNCTION.equals(name) && RING_FIELD.equals(field));
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
