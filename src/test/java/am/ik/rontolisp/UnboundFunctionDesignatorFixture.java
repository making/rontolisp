package am.ik.rontolisp;

/**
 * The function namespace asked about {@code t}, {@code nil}, a keyword and a name nothing
 * defines, through computed designators: {@code fboundp} answers nil, and
 * {@code symbol-function}, {@code fdefinition}, {@code funcall} and {@code apply} signal
 * an {@code undefined-function} catchable by its class on every backend. The answers are
 * sbcl's. Shared by the backend suites.
 */
public final class UnboundFunctionDesignatorFixture {

	private UnboundFunctionDesignatorFixture() {
	}

	/** The program. */
	public static final String SOURCE = """
			(defun fb-fn () 1)
			(defun fb-uf (thunk)
			  (handler-case (funcall thunk)
			    (undefined-function () :undefined-function)
			    (error () :other-error)))
			(defvar *fb-t* (read-from-string "t"))
			(defvar *fb-nil* (read-from-string "nil"))
			(defvar *fb-kw* (read-from-string ":fb-kw"))
			(defvar *fb-none* (intern "FB-NONE"))
			(defvar *fb-fn* (intern "FB-FN"))
			(print (mapcar #'fboundp (list *fb-t* *fb-nil* *fb-kw* *fb-none*)))
			(print (and (fboundp *fb-fn*) t))
			(print (mapcar (lambda (s) (fb-uf (lambda () (symbol-function s)))) (list *fb-t* *fb-nil* *fb-kw* *fb-none*)))
			(print (mapcar (lambda (s) (fb-uf (lambda () (fdefinition s)))) (list *fb-t* *fb-nil* *fb-kw* *fb-none*)))
			(print (functionp (symbol-function *fb-fn*)))
			(print (mapcar (lambda (s) (fb-uf (lambda () (funcall s)))) (list *fb-t* *fb-nil* *fb-kw* *fb-none*)))
			(print (mapcar (lambda (s) (fb-uf (lambda () (apply s nil)))) (list *fb-t* *fb-nil* *fb-kw* *fb-none*)))
			(print (list (macro-function *fb-t*) (macro-function *fb-nil*) (special-operator-p *fb-t*) (boundp *fb-t*)))
			(print (list (fboundp t) (fboundp nil) (fb-uf (lambda () (symbol-function t)))
			             (fb-uf (lambda () (fdefinition nil)))))
			""";

	/** What {@link #SOURCE} prints, one value per line (sbcl's answers). */
	public static final String EXPECTED = String.join("\n", "(NIL NIL NIL NIL)", "T",
			"(:UNDEFINED-FUNCTION :UNDEFINED-FUNCTION :UNDEFINED-FUNCTION :UNDEFINED-FUNCTION)",
			"(:UNDEFINED-FUNCTION :UNDEFINED-FUNCTION :UNDEFINED-FUNCTION :UNDEFINED-FUNCTION)", "T",
			"(:UNDEFINED-FUNCTION :UNDEFINED-FUNCTION :UNDEFINED-FUNCTION :UNDEFINED-FUNCTION)",
			"(:UNDEFINED-FUNCTION :UNDEFINED-FUNCTION :UNDEFINED-FUNCTION :UNDEFINED-FUNCTION)", "(NIL NIL NIL T)",
			"(NIL NIL :UNDEFINED-FUNCTION :UNDEFINED-FUNCTION)");

}
