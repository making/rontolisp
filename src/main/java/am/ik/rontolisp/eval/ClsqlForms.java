package am.ik.rontolisp.eval;

import org.jspecify.annotations.Nullable;

/**
 * The derived forms of clsql (tier 4, {@code .kb/asdf.md}). {@code sql/package.lisp} uses
 * one MOP package per {@code #+impl} arm, so here {@code clsql-sys} gets
 * {@code closer-mop}, which {@code clsql-closer-mop.asd} loads first.
 * {@code sql/metaclasses.lisp} asks three generics for their lambda lists to choose the
 * AMOP method shapes, and those are the shapes here; and it computes a class's key slots
 * in {@code register-metaclass} unless {@code #+(or sbcl allegro)} moves that to a
 * {@code finalize-inheritance :after} method. The method is what holds here too: the
 * effective slots do not exist yet when the class is initialized.
 */
final class ClsqlForms {

	/** The system the rewrite applies to. */
	static final String SYSTEM = "clsql";

	private static final String USE_MARKER = "#+openmcl #:openmcl-mop)";

	private static final String KEY_SLOTS_ON_INITIALIZE_MARKER = "#-(or sbcl allegro)\n    (setf (key-slots class)";

	private static final String KEY_SLOTS_ON_FINALIZE_MARKER = "#+(or sbcl allegro)\n(defmethod finalize-inheritance :after";

	private ClsqlForms() {
	}

	/**
	 * Rewrites {@code sql/package.lisp} and {@code sql/metaclasses.lisp}; any other
	 * component is left alone.
	 * @param componentFile the component's path relative to the system
	 * @param source its text
	 * @return the rewritten text, or {@code null} when the component is not one of these
	 */
	static @Nullable String rewrite(String componentFile, String source) {
		return switch (componentFile) {
			case "sql/package.lisp" -> replaceOnce(componentFile, source, USE_MARKER, "#:closer-mop)");
			case "sql/metaclasses.lisp" -> {
				String out = replaceOnce(componentFile, source, lambdaListProbe("compute-effective-slot-definition"),
						"(pushnew :kmr-normal-cesd cl:*features*)");
				out = replaceOnce(componentFile, out, lambdaListProbe("direct-slot-definition-class"),
						"(pushnew :kmr-normal-dsdc cl:*features*)");
				out = replaceOnce(componentFile, out, lambdaListProbe("effective-slot-definition-class"),
						"(pushnew :kmr-normal-esdc cl:*features*)");
				out = replaceOnce(componentFile, out, KEY_SLOTS_ON_INITIALIZE_MARKER,
						"#+(or)\n    (setf (key-slots class)");
				yield replaceOnce(componentFile, out, KEY_SLOTS_ON_FINALIZE_MARKER,
						"(defmethod finalize-inheritance :after");
			}
			default -> null;
		};
	}

	private static String lambdaListProbe(String generic) {
		String feature = switch (generic) {
			case "compute-effective-slot-definition" -> "cesd";
			case "direct-slot-definition-class" -> "dsdc";
			default -> "esdc";
		};
		return "(when (>= (length (generic-function-lambda-list\n" + "                     (ensure-generic-function\n"
				+ "                      '" + generic + ")))\n" + "            3)\n" + "    (pushnew :kmr-normal-"
				+ feature + " cl:*features*))";
	}

	private static String replaceOnce(String componentFile, String source, String marker, String replacement) {
		int first = source.indexOf(marker);
		if (first < 0 || source.indexOf(marker, first + 1) >= 0) {
			throw new IllegalStateException(
					"clsql " + componentFile + ": expected exactly one occurrence of the marker <<<" + marker
							+ ">>> (found " + (first < 0 ? 0 : 2) + "). The upstream release moved it; update "
							+ ClsqlForms.class.getSimpleName() + " -- see .kb/asdf.md.");
		}
		return source.substring(0, first) + replacement + source.substring(first + marker.length());
	}

}
