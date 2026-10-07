package am.ik.rontolisp;

/**
 * Calls through a computed SYMBOL designator see the run-time function namespace: a name
 * {@code fmakunbound} retired signals an {@code undefined-function} naming it from
 * {@code funcall} and {@code apply}, and a name only {@code eval}'s {@code defun} or a
 * computed {@code (setf (symbol-function ...))} bound is called through {@code funcall},
 * {@code apply} and {@code mapcar}. The answers are sbcl's. Shared by the backend suites.
 */
public final class RuntimeFunctionNamespaceCallFixture {

	private RuntimeFunctionNamespaceCallFixture() {
	}

	/** The program. */
	public static final String SOURCE = """
			(defun rn-retired () 1)
			(defun rn-kept () 2)
			(defun rn-uf (thunk)
			  (handler-case (funcall thunk)
			    (undefined-function () :undefined-function)
			    (error () :other-error)))
			(defun rn-names (thunk)
			  (handler-case (funcall thunk)
			    (error (e) (and (search "RN-RETIRED" (princ-to-string e)) t))))
			(defvar *rn-retired* (intern "RN-RETIRED"))
			(defvar *rn-evaled* (intern "RN-EVALED"))
			(defvar *rn-set* (intern "RN-SET"))
			(fmakunbound *rn-retired*)
			(eval (read-from-string "(defun rn-evaled (x) (* x 5))"))
			(setf (symbol-function *rn-set*) (lambda (x) (+ x 7)))
			(print (list (rn-uf (lambda () (funcall *rn-retired*))) (rn-uf (lambda () (funcall *rn-retired* 1)))
			             (rn-uf (lambda () (apply *rn-retired* nil))) (funcall (intern "RN-KEPT"))))
			(print (list (rn-names (lambda () (funcall *rn-retired*))) (rn-names (lambda () (apply *rn-retired* nil)))))
			(print (list (funcall *rn-evaled* 2) (apply *rn-evaled* '(3)) (funcall *rn-set* 1) (apply *rn-set* '(2))))
			(print (mapcar *rn-set* '(1 2)))
			""";

	/** What {@link #SOURCE} prints, one value per line (sbcl's answers). */
	public static final String EXPECTED = String.join("\n",
			"(:UNDEFINED-FUNCTION :UNDEFINED-FUNCTION :UNDEFINED-FUNCTION 2)", "(T T)", "(10 15 8 9)", "(8 9)");

}
