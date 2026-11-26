package am.ik.rontolisp.eval;

import org.jspecify.annotations.Nullable;

/**
 * The rontolisp arm of trivial-macroexpand-all (tier 4, {@code .kb/asdf.md}). The library
 * is one {@code macroexpand-all} per {@code #+impl} arm over the implementation's own
 * code walker, and its last arm answers the form unexpanded with a nil second value,
 * which here made petalisp's {@code transform} find no variable in any expression.
 * rontolisp gets an arm over {@code rontolisp::%macroexpand-all}
 * ({@link MacroexpandAll}), and leaves the fallback arm.
 */
final class TrivialMacroexpandAllForms {

	/** The system the rewrite applies to. */
	static final String SYSTEM = "trivial-macroexpand-all";

	private static final String FALLBACK_ARM = "#-(or abcl allegro ccl clisp cmucl corman ecl lispworks mkcl sbcl scl)\n";

	private TrivialMacroexpandAllForms() {
	}

	/**
	 * Rewrites {@code trivial-macroexpand-all.lisp}; any other component is left alone.
	 * @param componentFile the component's path relative to the system
	 * @param source its text
	 * @return the rewritten text, or {@code null} when the component is not this one
	 */
	static @Nullable String rewrite(String componentFile, String source) {
		if (!"trivial-macroexpand-all.lisp".equals(componentFile)) {
			return null;
		}
		int at = source.indexOf(FALLBACK_ARM);
		if (at < 0 || source.indexOf(FALLBACK_ARM, at + 1) >= 0) {
			throw new IllegalStateException(
					"trivial-macroexpand-all trivial-macroexpand-all.lisp: expected exactly one "
							+ "occurrence of the marker <<<" + FALLBACK_ARM + ">>> (found " + (at < 0 ? 0 : 2)
							+ "). The upstream release moved it; update "
							+ TrivialMacroexpandAllForms.class.getSimpleName() + " -- see .kb/asdf.md.");
		}
		return source.substring(0, at) + """
				#+rontolisp
				(defun macroexpand-all (form &optional env)
				  (rontolisp::%macroexpand-all form env))

				""" + FALLBACK_ARM.replace(" scl)", " scl rontolisp)") + source.substring(at + FALLBACK_ARM.length());
	}

}
