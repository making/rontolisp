package am.ik.rontolisp;

/**
 * Programs that catch the {@code unbound-variable} a read of an unbound name signals and
 * read its {@code cell-error-name}: {@code symbol-value} of a literal and of a computed
 * name, {@code #'symbol-value} called and mapped, inside a binding of an unrelated
 * special, and caught as {@code cell-error} and as {@code error}. Every backend used to
 * fill no {@code name} slot, so the reader answered NIL where SBCL answers the name, and
 * the wasm-GC backends trapped instead of signalling. A special declared without a value
 * -- a {@code (defvar x)}, a {@code (declaim (special x))}, a local
 * {@code (declare (special x))} -- read directly, from a function, through
 * {@code symbol-value}, an operand of arithmetic, after a binding of it ended and after a
 * callee's {@code setq} inside one: the compiled backends used to read NIL there, without
 * signalling. {@link #RESTART} adds a {@code handler-bind}, which puts the compiled
 * backends in restart mode. The expected text is SBCL's. {@link #MESSAGE} pins the
 * condition's report, rontolisp's text (SBCL ends it with a period). {@link #SPELLING}
 * pins the name's spelling in that report: a package-qualified, an uninterned and a
 * lower-case symbol appear as their symbol spells, no bars. Shared by the backend suites,
 * so every backend is held to one expected text; {@code ci-spec.yaml}'s
 * {@code unbound-variable-carries-its-name} runs the programs on the native binary.
 */
public final class UnboundVariableNameFixture {

	private UnboundVariableNameFixture() {
	}

	/** The program without a {@code handler-bind}. */
	public static final String PLAIN = """
			(defun uvn-name (thunk)
			  (handler-case (funcall thunk)
			    (unbound-variable (c) (list (cell-error-name c) (type-of c)))))
			(defvar *uvn-special* 1)
			(print (list (uvn-name (lambda () (symbol-value 'uvn-nope)))
			             (uvn-name (lambda () (symbol-value (intern "UVN-COMPUTED"))))
			             (uvn-name (lambda () (funcall #'symbol-value 'uvn-nope)))
			             (uvn-name (lambda () (mapcar #'symbol-value '(uvn-mapped))))
			             (uvn-name (lambda () (let ((*uvn-special* 2)) (symbol-value 'uvn-inside))))))
			(print (eq (cell-error-name (handler-case (symbol-value 'uvn-nope) (unbound-variable (c) c)))
			           'uvn-nope))
			(print (handler-case (symbol-value 'uvn-nope)
			         (cell-error (c) (list :cell (cell-error-name c) (typep c 'unbound-variable)))))
			(print (handler-case (symbol-value 'uvn-nope)
			         (error (c) (list :error (type-of c)))))
			(defvar *uvn-valueless*)
			(declaim (special *uvn-declaimed*))
			(defun uvn-read () *uvn-valueless*)
			(defun uvn-local-read () (declare (special uvn-local)) uvn-local)
			(print (list (uvn-name (lambda () *uvn-valueless*))
			             (uvn-name #'uvn-read)
			             (uvn-name (lambda () (symbol-value '*uvn-valueless*)))
			             (uvn-name (lambda () (let ((*uvn-valueless* 1)) (uvn-read))))
			             (uvn-name (lambda () (let ((*uvn-valueless* 2)) (setq *uvn-valueless* 3)) (uvn-read)))
			             (uvn-name (lambda () (1+ *uvn-valueless*)))
			             (uvn-name (lambda () *uvn-declaimed*))
			             (uvn-name #'uvn-local-read)
			             (uvn-name (lambda () (let ((uvn-local 4)) (declare (special uvn-local)) (uvn-local-read))))))
			(setq *uvn-valueless* 5)
			(print (list (uvn-read) (symbol-value '*uvn-valueless*)))
			""";

	/** What {@link #PLAIN} prints, one value per line. */
	public static final String PLAIN_EXPECTED = String.join("\n",
			"((UVN-NOPE UNBOUND-VARIABLE) (UVN-COMPUTED UNBOUND-VARIABLE) (UVN-NOPE UNBOUND-VARIABLE)"
					+ " (UVN-MAPPED UNBOUND-VARIABLE) (UVN-INSIDE UNBOUND-VARIABLE))",
			"T", "(:CELL UVN-NOPE T)", "(:ERROR UNBOUND-VARIABLE)",
			"((*UVN-VALUELESS* UNBOUND-VARIABLE) (*UVN-VALUELESS* UNBOUND-VARIABLE)"
					+ " (*UVN-VALUELESS* UNBOUND-VARIABLE) 1 (*UVN-VALUELESS* UNBOUND-VARIABLE)"
					+ " (*UVN-VALUELESS* UNBOUND-VARIABLE) (*UVN-DECLAIMED* UNBOUND-VARIABLE)"
					+ " (UVN-LOCAL UNBOUND-VARIABLE) 4)",
			"(5 5)");

	/** The program whose {@code handler-bind} sees the condition first. */
	public static final String RESTART = """
			(defvar *uvn-seen* nil)
			(defvar *uvn-restart*)
			(defun uvn-bound (thunk)
			  (handler-case
			      (handler-bind ((unbound-variable (lambda (c) (push (cell-error-name c) *uvn-seen*))))
			        (funcall thunk))
			    (unbound-variable (c) (list (cell-error-name c) (type-of c)))))
			(print (list (uvn-bound (lambda () (symbol-value 'uvn-nope)))
			             (uvn-bound (lambda () (symbol-value (intern "UVN-OTHER"))))
			             (uvn-bound (lambda () *uvn-restart*))
			             *uvn-seen*))
			""";

	/** What {@link #RESTART} prints. */
	public static final String RESTART_EXPECTED = "((UVN-NOPE UNBOUND-VARIABLE) (UVN-OTHER UNBOUND-VARIABLE)"
			+ " (*UVN-RESTART* UNBOUND-VARIABLE) (*UVN-RESTART* UVN-OTHER UVN-NOPE))";

	/** The program printing the report of a caught {@code unbound-variable}. */
	public static final String MESSAGE = """
			(print (handler-case (symbol-value 'uvn-nope) (error (c) (princ-to-string c))))
			(defvar *uvn-message*)
			(print (handler-case *uvn-message* (error (c) (princ-to-string c))))
			""";

	/** What {@link #MESSAGE} prints: the interpreter's text, on every backend. */
	public static final String MESSAGE_EXPECTED = String.join("\n", "\"The variable UVN-NOPE is unbound\"",
			"\"The variable *UVN-MESSAGE* is unbound\"");

	/**
	 * The program printing the report for a package-qualified, uninterned and lower-case
	 * name.
	 */
	public static final String SPELLING = """
			(defpackage :uvn-pkg (:use :cl))
			(defun uvn-text (name)
			  (handler-case (symbol-value name) (unbound-variable (c) (princ-to-string c))))
			(print (list (uvn-text (car (list 'uvn-pkg::nope)))
			             (uvn-text (car (list (make-symbol "UVN-UNINTERNED"))))
			             (uvn-text (car (list (intern "uvn-lower"))))))
			(defvar uvn-pkg::*valueless*)
			(defvar |uvn-lower-var|)
			(defun uvn-read-text (thunk)
			  (handler-case (funcall thunk) (unbound-variable (c) (list (princ-to-string c) (cell-error-name c)))))
			(print (list (uvn-read-text (lambda () uvn-pkg::*valueless*))
			             (uvn-read-text (lambda () |uvn-lower-var|))))
			""";

	/** What {@link #SPELLING} prints. */
	public static final String SPELLING_EXPECTED = String.join("\n",
			"(\"The variable UVN-PKG::NOPE is unbound\" \"The variable #:UVN-UNINTERNED is unbound\""
					+ " \"The variable uvn-lower is unbound\")",
			"((\"The variable UVN-PKG::*VALUELESS* is unbound\" UVN-PKG::*VALUELESS*)"
					+ " (\"The variable uvn-lower-var is unbound\" |uvn-lower-var|))");

}
