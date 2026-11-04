package am.ik.rontolisp.eval;

import org.jspecify.annotations.Nullable;

/**
 * The rontolisp arm of atomics (tier 4, {@code .kb/asdf.md}), which picks its operators
 * by implementation with {@code #+} and signals {@code no-support} for any other. A
 * {@code car} or {@code cdr} is a real compare-and-swap on the interpreter and the JVM.
 * Any other place, and every place on the single-threaded WASM backends, compares and
 * stores under one lock, as {@code clojure.lisp}'s atoms do: the JVM's general vector
 * widens in place and has no one word to swap
 * ({@code .kb/concurrent-served-requests.md}).
 */
final class AtomicsForms {

	/** The system the rewrite applies to. */
	static final String SYSTEM = "atomics";

	private static final String COMPONENT = "atomics.lisp";

	private static final String CAS_MACRO = "(defmacro cas (place old new)";

	private static final String CAS_ARM = "(no-support 'CAS)";

	private static final String INCF_ARM = "(no-support 'atomic-incf)";

	private static final String DECF_ARM = "(no-support 'atomic-decf)";

	private static final String CONDITION = "(define-condition implementation-not-supported (error)";

	private static final String FEATURES = """
			(pushnew :atomics-cas-car *features*)
			(pushnew :atomics-cas-svref *features*)
			(pushnew :atomics-cas-symbol-value *features*)
			(pushnew :atomics-cas-slot-value *features*)
			(pushnew :atomics-cas-struct-slot *features*)
			(pushnew :atomics-cas-special-var *features*)

			""";

	private static final String HELPERS = """
			(defvar *cas-lock* (rontolisp:make-mutex))

			#-rontolisp-wasm
			(defun cas-car (cell old new)
			  (check-type cell cons)
			  (loop
			    (let ((current (car cell)))
			      (unless (eq current old) (return nil))
			      (when (rontolisp::%cons-cas-car cell current new) (return t)))))

			#-rontolisp-wasm
			(defun cas-cdr (cell old new)
			  (check-type cell cons)
			  (loop
			    (let ((current (cdr cell)))
			      (unless (eq current old) (return nil))
			      (when (rontolisp::%cons-cas-cdr cell current new) (return t)))))

			(eval-when (:compile-toplevel :load-toplevel :execute)
			  (defun cas-expansion (place old new env)
			    (let ((place (macroexpand place env)))
			      #-rontolisp-wasm
			      (when (consp place)
			        (case (car place)
			          ((car first) (return-from cas-expansion `(cas-car ,(second place) ,old ,new)))
			          ((cdr rest) (return-from cas-expansion `(cas-cdr ,(second place) ,old ,new)))))
			      (multiple-value-bind (vars vals stores writer reader) (get-setf-expansion place env)
			        (let ((expected (gensym "OLD")) (store (first stores)))
			          `(let* (,@(mapcar #'list vars vals) (,expected ,old) (,store ,new))
			             (rontolisp:with-mutex (*cas-lock*)
			               (when (eq ,reader ,expected) ,writer t)))))))
			  (defun add-expansion (place delta)
			    (let ((by (gensym "DELTA")) (old (gensym "OLD")) (new (gensym "NEW")))
			      `(let ((,by ,delta))
			         (loop
			           (let* ((,old ,place) (,new (+ ,old ,by)))
			             (when (cas ,place ,old ,new) (return ,new))))))))

			""";

	private AtomicsForms() {
	}

	/**
	 * Rewrites {@code atomics.lisp}; any other component is left alone.
	 * @param componentFile the component's path relative to the system
	 * @param source its text
	 * @return the rewritten text, or {@code null} when the component is not this one
	 */
	static @Nullable String rewrite(String componentFile, String source) {
		if (!COMPONENT.equals(componentFile)) {
			return null;
		}
		String rewritten = replace(source, CONDITION, FEATURES + CONDITION);
		rewritten = replace(rewritten, CAS_MACRO, HELPERS + "(defmacro cas (place old new &environment env)");
		rewritten = replace(rewritten, CAS_ARM, "(cas-expansion place old new env)");
		rewritten = replace(rewritten, INCF_ARM, "(add-expansion place delta)");
		return replace(rewritten, DECF_ARM, "(add-expansion place `(- ,delta))");
	}

	private static String replace(String source, String marker, String replacement) {
		int first = source.indexOf(marker);
		if (first < 0 || source.indexOf(marker, first + 1) >= 0) {
			throw new IllegalStateException(
					"atomics " + COMPONENT + ": expected exactly one occurrence of the marker <<<" + marker
							+ ">>> (found " + (first < 0 ? 0 : 2) + "). The upstream release moved it; update "
							+ AtomicsForms.class.getSimpleName() + " -- see .kb/asdf.md.");
		}
		return source.substring(0, first) + replacement + source.substring(first + marker.length());
	}

}
