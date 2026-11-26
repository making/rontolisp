package am.ik.rontolisp.eval;

import org.jspecify.annotations.Nullable;

/**
 * The derived forms of trivial-arguments (tier 4, {@code .kb/asdf.md}): rontolisp's arms.
 * Its {@code defpackage} imports the three method readers from one MOP package per
 * {@code #+impl} arm and has no generic arm, so here the first imported name was read as
 * the package; rontolisp gets {@code closer-mop}, whose readers are built in. And
 * {@code arglist} gets an arm over {@code rontolisp::%function-lambda-list}, so the
 * {@code function-lambda-expression} fallback, and the load-time warning announcing it,
 * leave rontolisp out like every implementation with an arm.
 */
final class TrivialArgumentsForms {

	/** The system the rewrite applies to. */
	static final String SYSTEM = "trivial-arguments";

	private static final String LAST_MOP_ARM = "   #+sicl      #:sicl-clos\n";

	private static final String FALLBACK_FEATURES = "#-(or :abcl :allegro :ccl :clasp :clisp :cmucl :corman :ecl :lispworks :mezzano :sbcl :scl)";

	private static final String SBCL_ARGLIST_ARM = "        #+:sbcl\n        (multiple-value-bind (list unknown) (sb-introspect:function-lambda-list function)";

	private TrivialArgumentsForms() {
	}

	/**
	 * Rewrites {@code arguments.lisp}; any other component is left alone.
	 * @param componentFile the component's path relative to the system
	 * @param source its text
	 * @return the rewritten text, or {@code null} when the component is not this one
	 */
	static @Nullable String rewrite(String componentFile, String source) {
		if (!"arguments.lisp".equals(componentFile)) {
			return null;
		}
		String out = replace(source, LAST_MOP_ARM, LAST_MOP_ARM + "   #+rontolisp #:closer-mop\n", 1);
		out = replace(out, SBCL_ARGLIST_ARM,
				"        #+rontolisp\n        (rontolisp::%function-lambda-list function)\n\n" + SBCL_ARGLIST_ARM, 1);
		return replace(out, FALLBACK_FEATURES, FALLBACK_FEATURES.replace(" :scl)", " :scl :rontolisp)"), 2);
	}

	private static String replace(String source, String marker, String replacement, int expected) {
		int found = 0;
		for (int at = source.indexOf(marker); at >= 0; at = source.indexOf(marker, at + 1)) {
			found++;
		}
		if (found != expected) {
			throw new IllegalStateException(
					"trivial-arguments arguments.lisp: expected " + expected + " occurrence(s) of the marker <<<"
							+ marker + ">>> (found " + found + "). The upstream release moved it; update "
							+ TrivialArgumentsForms.class.getSimpleName() + " -- see .kb/asdf.md.");
		}
		return source.replace(marker, replacement);
	}

}
