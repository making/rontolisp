package am.ik.rontolisp.eval;

import org.jspecify.annotations.Nullable;

/**
 * The rontolisp arm of FSet's port layer (tier 4, {@code .kb/asdf.md}). {@code port.lisp}
 * defines {@code make-lock}, {@code with-lock} and the memory barriers per {@code #+impl}
 * arm with no generic one, so here nothing defines them. The arm is the upstream shape
 * for an implementation with real threads and no barrier operator: a mutex per lock and a
 * barrier as a grab of one lock.
 */
final class FsetForms {

	/** The system the rewrite applies to. */
	static final String SYSTEM = "fset";

	private static final String COMPONENT = "Code/port.lisp";

	private static final String MARKER = "#+sbcl\n(defmacro defglobal";

	private static final String ARM = """
			(defun make-lock (&optional name)
			  (declare (ignore name))
			  (rontolisp:make-mutex))
			(defmacro with-lock ((lock &key (wait? t)) &body body)
			  (declare (ignore wait?))
			  `(rontolisp:with-mutex (,lock) . ,body))
			(deflex *Memory-Barrier-Lock* (rontolisp:make-mutex))
			(defmacro read-memory-barrier ()
			  '(rontolisp:with-mutex (*Memory-Barrier-Lock*) nil))
			(defmacro write-memory-barrier ()
			  '(rontolisp:with-mutex (*Memory-Barrier-Lock*) nil))

			""";

	private FsetForms() {
	}

	/**
	 * Rewrites {@code Code/port.lisp}; any other component is left alone.
	 * @param componentFile the component's path relative to the system
	 * @param source its text
	 * @return the rewritten text, or {@code null} when the component is not this one
	 */
	static @Nullable String rewrite(String componentFile, String source) {
		if (!COMPONENT.equals(componentFile)) {
			return null;
		}
		int first = source.indexOf(MARKER);
		if (first < 0 || source.indexOf(MARKER, first + 1) >= 0) {
			throw new IllegalStateException("fset " + COMPONENT + ": expected exactly one occurrence of the marker <<<"
					+ MARKER + ">>> (found " + (first < 0 ? 0 : 2) + "). The upstream release moved it; update "
					+ FsetForms.class.getSimpleName() + " -- see .kb/asdf.md.");
		}
		return source.substring(0, first) + ARM + source.substring(first);
	}

}
