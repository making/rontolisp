package am.ik.rontolisp;

/**
 * A program whose every {@code boundp} is a literal probe of a global no definer gives a
 * value and no binding covers: a name only a function body assigns (to a value, then to
 * nil), a name a later top-level {@code setq} assigns probed from a function, a
 * {@code (defvar x)} never bound, a counter a loop in a function bumps through its own
 * probe, a {@code setf} in a function, and a name a top-level lambda assigns probed from
 * another, plus the parallel and multiple-value assignments ({@code psetq},
 * {@code psetf}, {@code multiple-value-setq}) in a function body. The fold cannot answer
 * any of the function-body cases ({@code CompileTimeBoundp}: a deferred body or a
 * valueless {@code defvar}), and with no eval runtime in the program the compile paths
 * answer from the variable, which holds an UNBOUND marker until the first store
 * ({@code GlobalVarCollector.collectProbedUnbound}); until 2026-10-04 each probe asked
 * the eval mirror, which put the whole eval runtime in the program. The expected text is
 * SBCL's. Shared by the backend suites.
 */
public final class ProbedUnboundGlobalFixture {

	private ProbedUnboundGlobalFixture() {
	}

	/** The program. */
	public static final String SOURCE = """
			(defun pg-set (v) (setq *pg-z* v))
			(defun pg-get () *pg-z*)
			(defun pg-probe () (boundp '*pg-z*))
			(print (list (boundp '*pg-z*) (pg-probe)))
			(pg-set nil)
			(print (list (boundp '*pg-z*) (pg-probe) (pg-get) *pg-z*))
			(pg-set 3)
			(print (list (pg-probe) (pg-get) (+ *pg-z* 1)))
			(defun pg-later () (boundp '*pg-w*))
			(print (pg-later))
			(setq *pg-w* 5)
			(print (list (pg-later) *pg-w*))
			(defvar *pg-u*)
			(defun pg-su () (setq *pg-u* :u))
			(print (boundp '*pg-u*))
			(pg-su)
			(print (list (boundp '*pg-u*) *pg-u*))
			(defun pg-bump () (setq *pg-c* (+ (if (boundp '*pg-c*) *pg-c* 0) 1)))
			(dotimes (i 5) (pg-bump))
			(print *pg-c*)
			(defun pg-setf (v) (setf *pg-f* v))
			(print (boundp '*pg-f*))
			(pg-setf '(1))
			(print (list (boundp '*pg-f*) *pg-f*))
			(let ((f (lambda () (list (boundp '*pg-l*) (if (boundp '*pg-l*) *pg-l* :none)))))
			  (print (funcall f))
			  (mapc (lambda (x) (setq *pg-l* x)) '(1 2))
			  (print (funcall f)))
			(defun pg-pset () (psetq *pg-pa* 1 *pg-pb* 2))
			(print (list (boundp '*pg-pa*) (boundp '*pg-pb*)))
			(pg-pset)
			(print (list (boundp '*pg-pa*) *pg-pa* (boundp '*pg-pb*) *pg-pb*))
			(defun pg-mv () (multiple-value-setq (*pg-q* *pg-r*) (floor 7 2)))
			(print (boundp '*pg-q*))
			(pg-mv)
			(print (list (boundp '*pg-q*) *pg-q* *pg-r*))
			(defun pg-psetf () (psetf *pg-g* 4 *pg-h* 5))
			(print (boundp '*pg-g*))
			(pg-psetf)
			(print (list (boundp '*pg-g*) *pg-g* *pg-h*))
			""";

	/** What {@link #SOURCE} prints, one value per line. */
	public static final String EXPECTED = String.join("\n", "(NIL NIL)", "(T T NIL NIL)", "(T 3 4)", "NIL", "(T 5)",
			"NIL", "(T :U)", "5", "NIL", "(T (1))", "(NIL :NONE)", "(T 2)", "(NIL NIL)", "(T 1 T 2)", "NIL", "(T 3 1)",
			"NIL", "(T 4 5)");

}
