package am.ik.rontolisp;

/**
 * A name only {@code (setf (symbol-function 'name) ...)} binds is undefined until the
 * setf runs: a call (direct, {@code funcall} of the quoted or a computed name) and a
 * reference ({@code #'name}, {@code symbol-function}, {@code fdefinition}) and a run-time
 * {@code eval} of a call signal an {@code undefined-function} naming it and
 * {@code fboundp} answers false; afterwards each answers the installed function itself,
 * and {@code fmakunbound} retires it again (as it retires a defun for {@code eval}). The
 * compilers' forwarder defun for the name's direct call sites is no definition of it. The
 * answers are sbcl's. Shared by the backend suites.
 */
public final class SetfSymbolFunctionReferenceFixture {

	private SetfSymbolFunctionReferenceFixture() {
	}

	/** The program. */
	public static final String SOURCE = """
			(defun sf-uf (thunk)
			  (handler-case (funcall thunk)
			    (undefined-function (c) (list :undefined (cell-error-name c)))))
			(defun sf-bound-p (name) (not (null (fboundp name))))
			(defvar *sf-name* (intern "SF-ALIAS"))
			(defvar *sf-fn* (lambda (x) (* x 10)))
			(print (list (sf-uf (lambda () (funcall 'sf-alias 1))) (sf-uf (lambda () #'sf-alias))
			             (sf-uf (lambda () (sf-alias 1))) (sf-uf (lambda () (funcall *sf-name* 1)))))
			(print (list (sf-uf (lambda () (symbol-function 'sf-alias))) (sf-uf (lambda () (fdefinition 'sf-alias)))
			             (sf-uf (lambda () (symbol-function *sf-name*))) (not (null (fboundp 'sf-alias)))
			             (sf-bound-p *sf-name*) (sf-uf (lambda () (eval '(sf-alias 1))))))
			(setf (symbol-function 'sf-alias) *sf-fn*)
			(print (list (funcall 'sf-alias 1) (sf-alias 2) (funcall #'sf-alias 3) (funcall *sf-name* 4)
			             (mapcar #'sf-alias '(5 6)) (eval '(sf-alias 7))))
			(print (list (eq #'sf-alias *sf-fn*) (eq (symbol-function 'sf-alias) *sf-fn*)
			             (eq (symbol-function *sf-name*) *sf-fn*) (not (null (fboundp 'sf-alias)))
			             (sf-bound-p *sf-name*)))
			(setf (fdefinition 'sf-alias) (lambda (x) (+ x 1)))
			(print (list (sf-alias 1) (funcall #'sf-alias 2) (funcall *sf-name* 3)))
			(fmakunbound 'sf-alias)
			(print (list (sf-uf (lambda () #'sf-alias)) (sf-uf (lambda () (sf-alias 1)))
			             (sf-uf (lambda () (funcall *sf-name* 1))) (not (null (fboundp 'sf-alias)))
			             (sf-bound-p *sf-name*) (sf-uf (lambda () (eval '(sf-alias 1))))))
			(defun sf-real (x) x)
			(fmakunbound 'sf-real)
			(print (sf-uf (lambda () (eval '(sf-real 1)))))
			""";

	/** What {@link #SOURCE} prints, one value per line (sbcl's answers). */
	public static final String EXPECTED = String.join("\n",
			"((:UNDEFINED SF-ALIAS) (:UNDEFINED SF-ALIAS) (:UNDEFINED SF-ALIAS) (:UNDEFINED SF-ALIAS))",
			"((:UNDEFINED SF-ALIAS) (:UNDEFINED SF-ALIAS) (:UNDEFINED SF-ALIAS) NIL NIL (:UNDEFINED SF-ALIAS))",
			"(10 20 30 40 (50 60) 70)", "(T T T T T)", "(2 3 4)",
			"((:UNDEFINED SF-ALIAS) (:UNDEFINED SF-ALIAS) (:UNDEFINED SF-ALIAS) NIL NIL (:UNDEFINED SF-ALIAS))",
			"(:UNDEFINED SF-REAL)");

}
