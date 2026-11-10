package am.ik.rontolisp.eval;

import org.jspecify.annotations.Nullable;

/**
 * The derived form of local-time (tier 4, {@code .kb/asdf.md}): its wall clock for
 * rontolisp.
 *
 * <p>
 * {@code %get-current-time} reads the clock per implementation in {@code #+impl} arms
 * answering {@code (values unix-seconds nanoseconds)}, closed by a {@code #-(or ...)} arm
 * that falls back to {@code get-universal-time} with zero nanoseconds -- the arm
 * rontolisp reads, so {@code local-time:now} stopped at the second. That arm's body
 * becomes {@code (rontolisp:unix-time)}, which answers the same two values with the
 * clock's fraction on every backend.
 */
final class LocalTimeForms {

	/** The system the rewrite applies to. */
	static final String SYSTEM = "local-time";

	private static final String COMPONENT = "src/local-time.lisp";

	private static final String CLOCK_MARKER = """
			#-(or allegro cmu sbcl abcl ccl (and lispworks (or linux darwin)))
			  (values (- (get-universal-time)
			             ;; CL's get-universal-time uses an epoch of 1/1/1900, so adjust the result to the Unix epoch
			             #.(encode-universal-time 0 0 0 1 1 1970 0))
			          0))""";

	private static final String CLOCK_REPLACEMENT = """
			#-(or allegro cmu sbcl abcl ccl (and lispworks (or linux darwin)))
			  (rontolisp:unix-time))""";

	private LocalTimeForms() {
	}

	/**
	 * Rewrites {@code src/local-time.lisp}; any other component is left alone.
	 * @param componentFile the component's path relative to the system
	 * @param source its text
	 * @return the rewritten text, or {@code null} when the component is not this one
	 */
	static @Nullable String rewrite(String componentFile, String source) {
		if (!COMPONENT.equals(componentFile)) {
			return null;
		}
		int first = source.indexOf(CLOCK_MARKER);
		if (first < 0 || source.indexOf(CLOCK_MARKER, first + 1) >= 0) {
			throw new IllegalStateException(
					"local-time " + componentFile + ": expected exactly one occurrence of the marker <<<" + CLOCK_MARKER
							+ ">>> (found " + (first < 0 ? 0 : 2) + "). The upstream release moved it; update "
							+ LocalTimeForms.class.getSimpleName() + " -- see .kb/asdf.md.");
		}
		return source.substring(0, first) + CLOCK_REPLACEMENT + source.substring(first + CLOCK_MARKER.length());
	}

}
