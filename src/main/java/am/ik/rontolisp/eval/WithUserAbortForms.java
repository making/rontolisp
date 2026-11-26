package am.ik.rontolisp.eval;

import org.jspecify.annotations.Nullable;

/**
 * The derived form of with-user-abort (tier 4, {@code .kb/asdf.md}): its
 * per-implementation interrupt condition for rontolisp. The library names one condition
 * per {@code #+impl} arm of a {@code quote} and has no generic arm, so here the quote
 * would have no datum. The last arm becomes {@code rontolisp:interactive-interrupt}, what
 * SIGINT is signalled as; everything else is the release's.
 */
final class WithUserAbortForms {

	/** The system the rewrite applies to. */
	static final String SYSTEM = "with-user-abort";

	private static final String LAST_ARM = "#+lispworks conditions:keyboard-break";

	private WithUserAbortForms() {
	}

	/**
	 * Rewrites {@code main.lisp}; any other component is left alone.
	 * @param componentFile the component's path relative to the system
	 * @param source its text
	 * @return the rewritten text, or {@code null} when the component is not this one
	 */
	static @Nullable String rewrite(String componentFile, String source) {
		if (!"main.lisp".equals(componentFile)) {
			return null;
		}
		int first = source.indexOf(LAST_ARM);
		if (first < 0 || source.indexOf(LAST_ARM, first + 1) >= 0) {
			throw new IllegalStateException(
					"with-user-abort main.lisp: expected exactly one occurrence of the marker <<<" + LAST_ARM
							+ ">>> (found " + (first < 0 ? 0 : 2) + "). The upstream release moved it; update "
							+ WithUserAbortForms.class.getSimpleName() + " -- see .kb/asdf.md.");
		}
		return source.substring(0, first) + "rontolisp:interactive-interrupt"
				+ source.substring(first + LAST_ARM.length());
	}

}
