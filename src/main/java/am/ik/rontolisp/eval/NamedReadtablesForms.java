package am.ik.rontolisp.eval;

import org.jspecify.annotations.Nullable;

/**
 * The derived form of named-readtables' {@code src/cruft.lisp} (tier 4,
 * {@code .kb/asdf.md}): the readtable iterator for rontolisp.
 *
 * <p>
 * named-readtables iterates a readtable's macro characters through
 * {@code %make-readtable-iterator}, written per implementation over its readtable
 * internals, with a PORTABLE fallback that calls {@code get-macro-character} on every
 * code point below {@code char-code-limit} and, for each dispatch character,
 * {@code get-dispatch-macro-character} on every code point again -- 2.2 million calls per
 * walk here, and the library walks a readtable at load ({@code *empty-readtable*}) and on
 * every {@code defreadtable} that merges one. rontolisp's readtable keeps only the
 * entries that differ from the standard syntax ({@code .kb/readtables.md}), so the port
 * walks those plus the standard macro characters ({@code %readtable-macro-entries}) and
 * yields what the portable iterator yields, in the same five values. The fallback and its
 * "has not been ported" compile-time warning are read out with {@code #+(or)}.
 */
final class NamedReadtablesForms {

	/** The system the rewrite applies to. */
	static final String SYSTEM = "named-readtables";

	/** Its one component the rewrite applies to. */
	static final String COMPONENT = "src/cruft.lisp";

	private static final String WARNING_MARKER = "#-(or sbcl clozure allegro cmucl)\n(eval-when (:compile-toplevel)";

	private static final String ITERATOR_MARKER = "#-(or sbcl clozure allegro cmucl)\n(defun %make-readtable-iterator (readtable)";

	private static final String PORT = """
			;; rontolisp (derived form): the readtable's own entries, yielded as the portable
			;; iterator below yields them, without grovelling every code point.
			(defun %make-readtable-iterator (readtable)
			  (check-type readtable readtable)
			  (let ((entries (%readtable-macro-entries readtable)))
			    #'(lambda ()
			        (if entries
			            (destructuring-bind (char fn non-terminating-p disp? alist) (pop entries)
			              (declare (ignore non-terminating-p))
			              (values t char fn disp? alist))
			            (values nil nil nil nil nil)))))

			""";

	private NamedReadtablesForms() {
	}

	/**
	 * Rewrites {@code src/cruft.lisp}; any other component is left alone.
	 * @param componentFile the component's path relative to the system
	 * @param source its text
	 * @return the rewritten text, or {@code null} when the component is not this one
	 */
	static @Nullable String rewrite(String componentFile, String source) {
		if (!COMPONENT.equals(componentFile)) {
			return null;
		}
		String out = replaceOnce(source, WARNING_MARKER, "#+(or)\n(eval-when (:compile-toplevel)");
		return replaceOnce(out, ITERATOR_MARKER, PORT + "#+(or)\n(defun %make-readtable-iterator-portable (readtable)");
	}

	private static String replaceOnce(String source, String marker, String replacement) {
		int first = source.indexOf(marker);
		if (first < 0 || source.indexOf(marker, first + 1) >= 0) {
			throw new IllegalStateException(
					"named-readtables " + COMPONENT + ": expected exactly one occurrence of the marker <<<" + marker
							+ ">>> (found " + (first < 0 ? 0 : 2) + "). The upstream release moved it; update "
							+ NamedReadtablesForms.class.getSimpleName() + " -- see .kb/readtables.md.");
		}
		return source.substring(0, first) + replacement + source.substring(first + marker.length());
	}

}
