package am.ik.rontolisp;

/**
 * A program whose globals are assigned only inside function bodies: a {@code defun}, a
 * {@code lambda} and a {@code labels} function in one, a nested {@code defun}, a
 * {@code defmethod}, through {@code setq}, {@code setf}, {@code psetq} and
 * {@code multiple-value-setq}, none with a {@code defvar}. Assigning an undeclared
 * variable is undefined in CL; SBCL warns and treats the name as a global, and so does
 * the interpreter, so the compile paths give such a name a global backing store too
 * ({@code GlobalVarCollector.collectFreeAssignedInFunctionBodies}). A {@code let} or a
 * parameter of the same name stays lexical, as in SBCL, where the name is not proclaimed
 * special. Until 2026-10-04 the JVM and both WASM refused the first read
 * ({@code Cannot compile symbol reference: *FA-Z*}), and with no read compiled the
 * assignment into a local of the function, so {@code boundp} answered NIL. The expected
 * text is SBCL's. Shared by the backend suites; {@code ci-spec.yaml}'s
 * {@code a-global-assigned-only-inside-a-function} runs the same program on the native
 * binary.
 */
public final class FunctionAssignedGlobalFixture {

	private FunctionAssignedGlobalFixture() {
	}

	/** The program. */
	public static final String SOURCE = """
			(defun fa-set () (setq *fa-z* 1))
			(defun fa-get () *fa-z*)
			(print (boundp '*fa-z*))
			(fa-set)
			(print (list (fa-get) *fa-z* (boundp '*fa-z*) (symbol-value '*fa-z*)))
			(defun fa-setf (v) (setf *fa-w* v) (fa-get-w))
			(defun fa-get-w () *fa-w*)
			(defun fa-closure () (mapc (lambda (x) (setq *fa-last* x)) '(1 2 3)) *fa-last*)
			(defun fa-labels () (labels ((walk (n) (when (> n 0) (setq *fa-depth* n) (walk (- n 1))))) (walk 4)) *fa-depth*)
			(defun fa-reset () (setq *fa-sum* 0))
			(defun fa-add (l) (when l (setq *fa-sum* (+ *fa-sum* (car l))) (fa-add (cdr l))))
			(fa-reset)
			(fa-add '(1 2 3))
			(print (list (fa-setf 2) *fa-w* (fa-closure) (fa-labels) *fa-sum*))
			(defun fa-lex () (let ((*fa-z* 10)) (setq *fa-z* 11) *fa-z*))
			(defun fa-param (*fa-z*) (setq *fa-z* 20) *fa-z*)
			(print (list (fa-lex) (fa-param 0) *fa-z* (fa-get)))
			(defun fa-mv () (multiple-value-setq (*fa-q* *fa-r*) (floor 7 2)))
			(defun fa-pset () (psetq *fa-a* 1 *fa-b* 2))
			(defun fa-outer () (defun fa-inner () (setq *fa-in* :inner)) (fa-inner))
			(fa-mv)
			(fa-pset)
			(fa-outer)
			(print (list *fa-q* *fa-r* *fa-a* *fa-b* *fa-in*))
			(defmethod fa-m ((x integer)) (setq *fa-m* (* x 2)))
			(defmethod fa-m ((x string)) (setq *fa-m* x))
			(fa-m 5)
			(print *fa-m*)
			""";

	/** What {@link #SOURCE} prints, one value per line. */
	public static final String EXPECTED = String.join("\n", "NIL", "(1 1 T 1)", "(2 2 3 1 6)", "(11 20 1 1)",
			"(3 1 1 2 :INNER)", "10");

}
