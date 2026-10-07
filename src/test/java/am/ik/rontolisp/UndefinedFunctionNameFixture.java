package am.ik.rontolisp;

/**
 * Programs that catch the {@code undefined-function} a call of an unbound name signals
 * and read its {@code cell-error-name}: through a computed {@code funcall},
 * {@code apply}, {@code mapcar}, {@code symbol-function} and {@code fdefinition}, a name
 * {@code fmakunbound} retired, a direct call of a name no definition has (the compiled
 * backends' call-time stub), and the standard symbols NIL and T and a keyword. Every
 * backend used to fill no {@code name} slot, so the reader answered NIL where SBCL
 * answers the name. {@link #RESTART} adds a {@code handler-bind}, which puts the compiled
 * backends in restart mode: there the direct call's stub used to build a
 * {@code simple-error} at its signal point, so no {@code undefined-function} clause
 * matched it. The expected text is SBCL's. Shared by the backend suites, so every backend
 * is held to one expected text; {@code ci-spec.yaml}'s
 * {@code undefined-function-carries-its-name} runs both programs on the native binary,
 * without the direct calls, whose compile-time warning the corpus compiles forbid.
 * {@link #SPELLING} pins the name's spelling in the report: a package-qualified, an
 * uninterned and a lower-case symbol appear as their symbol spells, no bars.
 */
public final class UndefinedFunctionNameFixture {

	private UndefinedFunctionNameFixture() {
	}

	/** The program without a {@code handler-bind}. */
	public static final String PLAIN = """
			(defun ufn-name (thunk)
			  (handler-case (funcall thunk)
			    (undefined-function (c) (list (cell-error-name c) (type-of c)))))
			(defun ufn-gone () 1)
			(fmakunbound 'ufn-gone)
			(print (list (ufn-name (lambda () (funcall (intern "UFN-NOPE"))))
			             (ufn-name (lambda () (funcall (intern "UFN-NOPE") 1 2)))
			             (ufn-name (lambda () (apply (intern "UFN-NOPE") '(1 2))))
			             (ufn-name (lambda () (mapcar (intern "UFN-NOPE") '(1 2))))
			             (ufn-name (lambda () (symbol-function (intern "UFN-NOPE"))))
			             (ufn-name (lambda () (fdefinition (intern "UFN-NOPE"))))))
			(print (list (ufn-name (lambda () (funcall (intern "UFN-GONE"))))
			             (ufn-name (lambda () (symbol-function (intern "UFN-GONE"))))
			             (ufn-name (lambda () (ufn-direct 1 2)))
			             (ufn-name (lambda () (funcall (car (list nil)))))
			             (ufn-name (lambda () (funcall (car (list t)))))
			             (ufn-name (lambda () (funcall (car (list :ufn-key)))))))
			(print (eq (cell-error-name (handler-case (funcall (intern "UFN-NOPE")) (undefined-function (c) c)))
			           'ufn-nope))
			""";

	/** What {@link #PLAIN} prints, one value per line. */
	public static final String PLAIN_EXPECTED = String.join("\n",
			"((UFN-NOPE UNDEFINED-FUNCTION) (UFN-NOPE UNDEFINED-FUNCTION) (UFN-NOPE UNDEFINED-FUNCTION)"
					+ " (UFN-NOPE UNDEFINED-FUNCTION) (UFN-NOPE UNDEFINED-FUNCTION) (UFN-NOPE UNDEFINED-FUNCTION))",
			"((UFN-GONE UNDEFINED-FUNCTION) (UFN-GONE UNDEFINED-FUNCTION) (UFN-DIRECT UNDEFINED-FUNCTION)"
					+ " (NIL UNDEFINED-FUNCTION) (T UNDEFINED-FUNCTION) (:UFN-KEY UNDEFINED-FUNCTION))",
			"T");

	/** The program whose {@code handler-bind} sees the condition first. */
	public static final String RESTART = """
			(defvar *ufn-seen* nil)
			(defun ufn-bound (thunk)
			  (handler-case
			      (handler-bind ((undefined-function (lambda (c) (push (cell-error-name c) *ufn-seen*))))
			        (funcall thunk))
			    (undefined-function (c) (list (cell-error-name c) (type-of c)))))
			(print (list (ufn-bound (lambda () (funcall (intern "UFN-NOPE"))))
			             (ufn-bound (lambda () (ufn-direct 1)))
			             *ufn-seen*))
			""";

	/** What {@link #RESTART} prints. */
	public static final String RESTART_EXPECTED = "((UFN-NOPE UNDEFINED-FUNCTION) (UFN-DIRECT UNDEFINED-FUNCTION)"
			+ " (UFN-DIRECT UFN-NOPE))";

	/**
	 * A LITERAL reference to a name no definition has: {@code #'name},
	 * {@code (symbol-function 'name)} and a quoted designator
	 * ({@code (funcall 'name ...)}, {@code apply}, {@code mapcar}), at top level and in a
	 * {@code defun} body. The compiled backends refused the whole program
	 * ({@code Cannot compile: NAME}) where the interpreter and SBCL signal when the
	 * reference runs. {@code #'name} signals where it is evaluated; a quoted designator
	 * is looked up when the call runs, after the arguments ({@code (:SPREAD :ARG)}); a
	 * reference in a branch never taken signals nothing.
	 */
	public static final String REFERENCE = """
			(defun ufr-name (thunk)
			  (handler-case (funcall thunk)
			    (undefined-function (c) (list (cell-error-name c) (type-of c)))))
			(defun ufr-ref () #'ufr-nope)
			(defun ufr-call (x) (funcall 'ufr-nope x))
			(defvar *ufr-trace* nil)
			(print (list (ufr-name (lambda () (funcall 'ufr-nope 1)))
			             (ufr-name (lambda () #'ufr-nope))
			             (ufr-name (lambda () (symbol-function 'ufr-nope)))
			             (ufr-name (lambda () (apply 'ufr-nope '(1 2))))
			             (ufr-name (lambda () (mapcar 'ufr-nope '(1 2))))
			             (ufr-name (lambda () (mapcar #'ufr-nope '(1 2))))
			             (ufr-name #'ufr-ref)
			             (ufr-name (lambda () (ufr-call 1)))))
			(print (list (ufr-name (lambda () (funcall 'ufr-nope (push :arg *ufr-trace*))))
			             (ufr-name (lambda () (apply 'ufr-nope (push :spread *ufr-trace*) nil)))
			             *ufr-trace*
			             (if (car (list nil)) #'ufr-nope :dead)))
			""";

	/** What {@link #REFERENCE} prints, one value per line. */
	public static final String REFERENCE_EXPECTED = String.join("\n",
			"((UFR-NOPE UNDEFINED-FUNCTION) (UFR-NOPE UNDEFINED-FUNCTION) (UFR-NOPE UNDEFINED-FUNCTION)"
					+ " (UFR-NOPE UNDEFINED-FUNCTION) (UFR-NOPE UNDEFINED-FUNCTION) (UFR-NOPE UNDEFINED-FUNCTION)"
					+ " (UFR-NOPE UNDEFINED-FUNCTION) (UFR-NOPE UNDEFINED-FUNCTION))",
			"((UFR-NOPE UNDEFINED-FUNCTION) (UFR-NOPE UNDEFINED-FUNCTION) (:SPREAD :ARG) :DEAD)");

	/** {@link #REFERENCE}'s two forms of reference under a {@code handler-bind}. */
	public static final String REFERENCE_RESTART = """
			(defvar *ufr-seen* nil)
			(defun ufr-bound (thunk)
			  (handler-case
			      (handler-bind ((undefined-function (lambda (c) (push (cell-error-name c) *ufr-seen*))))
			        (funcall thunk))
			    (undefined-function (c) (list (cell-error-name c) (type-of c)))))
			(print (list (ufr-bound (lambda () #'ufr-ref-nope))
			             (ufr-bound (lambda () (funcall 'ufr-call-nope 1)))
			             *ufr-seen*))
			""";

	/** What {@link #REFERENCE_RESTART} prints. */
	public static final String REFERENCE_RESTART_EXPECTED = "((UFR-REF-NOPE UNDEFINED-FUNCTION)"
			+ " (UFR-CALL-NOPE UNDEFINED-FUNCTION) (UFR-CALL-NOPE UFR-REF-NOPE))";

	/**
	 * A {@code (setf name)} function no definition has, called through
	 * {@code #'(setf name)} at top level, from a {@code defun} body and through
	 * {@code apply}: the condition names {@code (setf name)}, as SBCL does, not the
	 * internal name the writer is stored under, and the report spells the same. A defined
	 * one is the control. {@link #SETF_FUNCTION_RESTART} puts the compiled backends in
	 * restart mode with a {@code handler-bind}.
	 */
	public static final String SETF_FUNCTION = """
			(defun (setf ufs-defined) (v x) (list v x))
			(defun ufs-text (c) (list (cell-error-name c) (princ-to-string c)))
			(defun ufs-call (v x) (funcall #'(setf ufs-nope) v x))
			(print (handler-case (funcall #'(setf ufs-nope) 1 2) (undefined-function (c) (ufs-text c))))
			(print (handler-case (ufs-call 1 2) (undefined-function (c) (ufs-text c))))
			(print (handler-case (apply #'(setf ufs-nope) '(1 2)) (undefined-function (c) (ufs-text c))))
			(print (handler-case (ufs-call 1 2)
			         (undefined-function (c) (equal (cell-error-name c) '(setf ufs-nope)))))
			(print (funcall #'(setf ufs-defined) 1 2))
			""";

	/** What {@link #SETF_FUNCTION} prints, one value per line. */
	public static final String SETF_FUNCTION_EXPECTED = String.join("\n",
			"((SETF UFS-NOPE) \"The function (SETF UFS-NOPE) is undefined\")",
			"((SETF UFS-NOPE) \"The function (SETF UFS-NOPE) is undefined\")",
			"((SETF UFS-NOPE) \"The function (SETF UFS-NOPE) is undefined\")", "T", "(1 2)");

	/** {@link #SETF_FUNCTION}'s call under a {@code handler-bind}. */
	public static final String SETF_FUNCTION_RESTART = """
			(defvar *ufs-seen* nil)
			(defun ufs-call (v x) (funcall #'(setf ufs-nope) v x))
			(print (list (handler-case
			                 (handler-bind ((undefined-function (lambda (c) (push (cell-error-name c) *ufs-seen*))))
			                   (ufs-call 1 2))
			               (undefined-function (c) (cell-error-name c)))
			             *ufs-seen*))
			""";

	/** What {@link #SETF_FUNCTION_RESTART} prints. */
	public static final String SETF_FUNCTION_RESTART_EXPECTED = "((SETF UFS-NOPE) ((SETF UFS-NOPE)))";

	/**
	 * The program printing the report for a package-qualified, uninterned and lower-case
	 * name.
	 */
	public static final String SPELLING = """
			(defpackage :ufn-pkg (:use :cl))
			(defun ufn-text (name)
			  (handler-case (funcall name) (undefined-function (c) (princ-to-string c))))
			(print (list (ufn-text (car (list 'ufn-pkg::nope)))
			             (ufn-text (car (list (make-symbol "UFN-UNINTERNED"))))
			             (ufn-text (car (list (intern "ufn-lower"))))))
			""";

	/** What {@link #SPELLING} prints. */
	public static final String SPELLING_EXPECTED = "(\"The function UFN-PKG::NOPE is undefined\""
			+ " \"The function #:UFN-UNINTERNED is undefined\" \"The function ufn-lower is undefined\")";

}
