package am.ik.rontolisp;

/**
 * Programs that reach a function a {@code defun} below the top level defines through a
 * name resolved at run time -- {@code intern}, a quoted symbol in a variable, a
 * {@code (setf name)} list built at run time, {@code eval}, {@code read-from-string} --
 * and retire it with {@code fmakunbound}, then define it again. The compiled backends
 * held such a function in a global variable only the compile-time references read: a
 * computed {@code funcall} / {@code apply} / {@code symbol-function} /
 * {@code fdefinition} signalled {@code undefined-function}, a computed {@code fboundp}
 * answered NIL, and after {@code fmakunbound} a call still ran (and {@code fboundp}
 * stayed NIL after the next definition). A top-level {@code defun} a nested one redefines
 * was not reachable by a computed name at all, and a
 * {@code (setf (symbol-function 'name) ...)} of a nested name was invisible to a direct
 * call. The expected text is SBCL 2.2.9's (a true {@code fboundp} answer is printed
 * through {@code and}). {@link #RETIRED} adds a {@code handler-bind}, which puts the
 * compiled backends in restart mode. Shared by the backend suites, so every backend is
 * held to one expected text.
 */
public final class NestedDefunNamespaceFixture {

	private NestedDefunNamespaceFixture() {
	}

	/** Names built by {@code intern}, before and after the definition and fmakunbound. */
	public static final String COMPUTED = """
			(defun ns-def () (defun ns-q (x) (* x 2)) (defun (setf ns-q) (v x) (list v x)))
			(let ((n 0))
			  (defun ns-c () (incf n)))
			(defun ns-name (thunk) (handler-case (funcall thunk) (undefined-function (c) (cell-error-name c))))
			(defun ns-sym (s) (intern s))
			(print (list (fboundp (ns-sym "NS-Q")) (fboundp (list 'setf (ns-sym "NS-Q")))
			             (ns-name (lambda () (funcall (ns-sym "NS-Q") 3)))
			             (ns-name (lambda () (apply (ns-sym "NS-Q") '(3))))
			             (ns-name (lambda () (symbol-function (ns-sym "NS-Q"))))
			             (ns-name (lambda () (fdefinition (list 'setf (ns-sym "NS-Q")))))))
			(ns-def)
			(print (list (funcall (ns-sym "NS-Q") 3) (apply (ns-sym "NS-Q") '(4))
			             (funcall (fdefinition (list 'setf (ns-sym "NS-Q"))) 1 2)
			             (and (fboundp (ns-sym "NS-Q")) t) (and (fboundp (list 'setf (ns-sym "NS-Q"))) t)
			             (functionp (symbol-function (ns-sym "NS-Q")))
			             (eq (symbol-function (ns-sym "NS-Q")) #'ns-q)
			             (let ((f 'ns-c)) (funcall f)) (mapcar 'ns-q '(1 2))))
			(print (list (fmakunbound 'ns-q) (fmakunbound '(setf ns-q))))
			(print (list (ns-name (lambda () (ns-q 1))) (ns-name (lambda () #'ns-q))
			             (ns-name (lambda () (setf (ns-q 1) 2)))
			             (fboundp 'ns-q) (fboundp '(setf ns-q)) (fboundp (ns-sym "NS-Q"))
			             (ns-name (lambda () (funcall (ns-sym "NS-Q") 1)))))
			(ns-def)
			(print (list (ns-q 5) (setf (ns-q 1) 2) (funcall (ns-sym "NS-Q") 6) (and (fboundp 'ns-q) t)))
			(print (list (fmakunbound (ns-sym "NS-C")) (ns-name (lambda () (ns-c))) (fboundp 'ns-c)))
			""";

	/** What {@link #COMPUTED} prints, one value per line. */
	public static final String COMPUTED_EXPECTED = String.join("\n", "(NIL NIL NS-Q NS-Q NS-Q (SETF NS-Q))",
			"(6 8 (1 2) T T T T 1 (2 4))", "(NS-Q (SETF NS-Q))", "(NS-Q NS-Q (SETF NS-Q) NIL NIL NIL NS-Q)",
			"(10 (2 1) 12 T)", "(NS-C NS-C NIL)");

	/**
	 * A literal {@code fmakunbound} and a redefinition, under a {@code handler-bind}; a
	 * top-level {@code defun} a nested one redefines; a symbol-function write of a nested
	 * name.
	 */
	public static final String RETIRED = """
			(defvar *nf-seen* nil)
			(let ((n 0)) (defun nf-c () (incf n)))
			(defun nf-bound (thunk)
			  (handler-case
			      (handler-bind ((undefined-function (lambda (c) (push (cell-error-name c) *nf-seen*))))
			        (funcall thunk))
			    (undefined-function (c) (list (cell-error-name c) (type-of c)))))
			(print (list (nf-c) (fmakunbound 'nf-c) (nf-bound (lambda () (nf-c))) (nf-bound (lambda () #'nf-c))
			             (fboundp 'nf-c) *nf-seen*))
			(let ((n 10)) (defun nf-c () (incf n)))
			(print (list (nf-c) (and (fboundp 'nf-c) t)))
			(defun nr-over () 'top)
			(defun nr-redef () (defun nr-over () 'nested))
			(print (list (nr-over) (funcall (intern "NR-OVER"))))
			(nr-redef)
			(print (list (nr-over) (funcall (intern "NR-OVER")) (fmakunbound 'nr-over)
			             (nf-bound (lambda () (nr-over)))))
			(let () (defun ns-a () 'a))
			(print (ns-a))
			(setf (symbol-function 'ns-a) (lambda () 'b))
			(print (list (ns-a) (funcall 'ns-a)))
			""";

	/** What {@link #RETIRED} prints, one value per line. */
	public static final String RETIRED_EXPECTED = String.join("\n",
			"(1 NF-C (NF-C UNDEFINED-FUNCTION) (NF-C UNDEFINED-FUNCTION) NIL (NF-C NF-C))", "(11 T)", "(TOP TOP)",
			"(NESTED NESTED NR-OVER (NR-OVER UNDEFINED-FUNCTION))", "A", "(B B)");

	/** Names that exist only in data the program evaluates or reads. */
	public static final String EVALUATED = """
			(let ((n 0)) (defun ne-c () (incf n)))
			(print (list (eval '(ne-c)) (eval '(funcall 'ne-c)) (funcall (read-from-string "ne-c")) (ne-c)))
			""";

	/** What {@link #EVALUATED} prints. */
	public static final String EVALUATED_EXPECTED = "(1 2 3 4)";

}
