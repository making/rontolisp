package am.ik.rontolisp.eval;

import org.jspecify.annotations.Nullable;

/**
 * The rontolisp arms of coalton's standard library (tier 4, {@code .kb/asdf.md}). Its
 * {@code Hash} type and hash combination pick an arm per implementation and signal on any
 * other, at read time. The ccl arms are what holds here: {@code sxhash} answers a
 * non-negative fixnum, and the combination masks a 64-bit mix back to one.
 */
final class CoaltonForms {

	/** The system the rewrite applies to. */
	static final String SYSTEM = "coalton/library";

	private static final String CLASSES = "library/classes.lisp";

	private static final String HASH = "library/hash.lisp";

	private CoaltonForms() {
	}

	/**
	 * Rewrites the two components; any other is left alone.
	 * @param componentFile the component's path relative to the system
	 * @param source its text
	 * @return the rewritten text, or {@code null} when the component is not one of these
	 */
	static @Nullable String rewrite(String componentFile, String source) {
		return switch (componentFile) {
			case CLASSES -> {
				String arm = replace(componentFile, source,
						"#+ccl\n  (repr :native (cl:and cl:fixnum cl:unsigned-byte))",
						"#+(or ccl rontolisp)\n  (repr :native (cl:and cl:fixnum cl:unsigned-byte))");
				yield replace(componentFile, arm, "#-(or sbcl allegro ccl)\n  #.(cl:error",
						"#-(or sbcl allegro ccl rontolisp)\n  #.(cl:error");
			}
			case HASH -> replace(componentFile, source, "#+ccl (cl:logand (cl:logxor lhs",
					"#+(or ccl rontolisp) (cl:logand (cl:logxor lhs");
			default -> null;
		};
	}

	private static String replace(String component, String source, String marker, String replacement) {
		int first = source.indexOf(marker);
		if (first < 0 || source.indexOf(marker, first + 1) >= 0) {
			throw new IllegalStateException(
					"coalton " + component + ": expected exactly one occurrence of the marker <<<" + marker
							+ ">>> (found " + (first < 0 ? 0 : 2) + "). The upstream release moved it; update "
							+ CoaltonForms.class.getSimpleName() + " -- see .kb/asdf.md.");
		}
		return source.substring(0, first) + replacement + source.substring(first + marker.length());
	}

}
