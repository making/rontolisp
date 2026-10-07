package am.ik.rontolisp;

/**
 * Programs that use a {@code (setf name)} function name where CL takes a function name.
 * {@link #CLOSURE} references {@code #'(setf name)} from inside a closure: a
 * {@code lambda}, a nested {@code lambda}, a closure a {@code defun} returns, a
 * {@code labels} / {@code flet} function, beside a variable of the same name and for an
 * undefined writer. The compiled backends read the {@code (setf name)} operand as a call
 * whose argument is a free variable and refused the program ({@code Cannot capture
 * variable}). {@link #DESIGNATOR} passes the quoted {@code (setf name)} list to
 * {@code fdefinition}, {@code fboundp}, {@code fmakunbound} and
 * {@code (setf fdefinition)}, which took symbols only: the interpreter signalled
 * {@code FDEFINITION expects a symbol}, the JVM a raw {@code ClassCastException}, both
 * wasm targets a trap. The expected text is SBCL's (a true {@code fboundp} answer is
 * printed through {@code not}, SBCL answering the function). {@link #UNKNOWN_PLACE}
 * writes a place whose operator no definition makes a place, which every backend refused
 * at expansion ({@code setf does not support place}) where CL calls the
 * {@code (setf name)} function when the form runs. {@link #STRUCT_WRITER} takes the
 * {@code (setf name)} function of {@code defstruct} slot accessors, which no backend
 * defined. Shared by the backend suites, so every backend is held to one expected text.
 */
public final class SetfFunctionNameFixture {

	private SetfFunctionNameFixture() {
	}

	/** {@code #'(setf name)} inside closures. */
	public static final String CLOSURE = """
			(defun (setf sfc-d) (v x) (list v x))
			(defun sfc-maker (k) (lambda (a) (funcall #'(setf sfc-d) a k)))
			(print (funcall (lambda (a) (funcall #'(setf sfc-d) a 2)) 7))
			(print (funcall (funcall (lambda (a) (lambda (b) (funcall #'(setf sfc-d) a b))) 3) 4))
			(print (funcall (sfc-maker 5) 6))
			(print (mapcar (lambda (x) (funcall #'(setf sfc-d) x x)) '(1 2)))
			(print (labels ((down (n) (if (= n 0) (funcall #'(setf sfc-d) n :done) (down (- n 1))))) (down 3)))
			(print (let ((k 8)) (flet ((wr (v) (funcall #'(setf sfc-d) v k))) (mapcar #'wr '(1 2)))))
			(print (let ((sfc-d 10)) (funcall (lambda () (list sfc-d (funcall #'(setf sfc-d) sfc-d 1))))))
			(print (handler-case (funcall (lambda (a) (funcall #'(setf sfc-nope) a 1)) 0)
			         (undefined-function (c) (cell-error-name c))))
			""";

	/** What {@link #CLOSURE} prints, one value per line. */
	public static final String CLOSURE_EXPECTED = String.join("\n", "(7 2)", "(3 4)", "(6 5)", "((1 1) (2 2))",
			"(0 :DONE)", "((1 8) (2 8))", "(10 (10 1))", "(SETF SFC-NOPE)");

	/**
	 * A quoted {@code (setf name)} designator to the operators that take a function name.
	 */
	public static final String DESIGNATOR = """
			(defun (setf sfn-d) (v x) (list v x))
			(print (funcall (fdefinition '(setf sfn-d)) 1 2))
			(print (list (not (fboundp '(setf sfn-d))) (fboundp '(setf sfn-nope))))
			(print (handler-case (fdefinition '(setf sfn-nope)) (undefined-function (c) (cell-error-name c))))
			(print (funcall (lambda () (funcall (fdefinition '(setf sfn-d)) 3 4))))
			(setf (fdefinition '(setf sfn-e)) (lambda (v x) (list :e v x)))
			(print (list (not (fboundp '(setf sfn-e))) (funcall #'(setf sfn-e) 1 2)
			             (funcall (fdefinition '(setf sfn-e)) 3 4)))
			(print (fmakunbound '(setf sfn-e)))
			(print (fboundp '(setf sfn-e)))
			(print (handler-case (funcall (fdefinition '(setf sfn-e)) 1 2)
			         (undefined-function (c) (cell-error-name c))))
			(print (fmakunbound '(setf sfn-d)))
			(print (fboundp '(setf sfn-d)))
			""";

	/** What {@link #DESIGNATOR} prints, one value per line. */
	public static final String DESIGNATOR_EXPECTED = String.join("\n", "(1 2)", "(NIL NIL)", "(SETF SFN-NOPE)", "(3 4)",
			"(NIL (:E 1 2) (:E 3 4))", "(SETF SFN-E)", "NIL", "(SETF SFN-E)", "(SETF SFN-D)", "NIL");

	/**
	 * A {@code (setf name)} designator built when the program runs, to the same
	 * operators, as a function value and for a {@code defstruct} slot writer; a computed
	 * symbol still designates its function. The compiled backends took the list quoted
	 * only: the JVM threw a raw {@code ClassCastException}, both wasm targets trapped.
	 */
	public static final String COMPUTED_DESIGNATOR = """
			(defun (setf sfr-d) (v x) (list v x))
			(defun sfr-name (place) (list 'setf place))
			(print (funcall (fdefinition (sfr-name 'sfr-d)) 1 2))
			(print (list (not (fboundp (sfr-name 'sfr-d))) (fboundp (sfr-name 'sfr-nope))))
			(print (handler-case (fdefinition (sfr-name 'sfr-nope))
			         (undefined-function (c) (cell-error-name c))))
			(print (funcall (lambda (p) (funcall (fdefinition (sfr-name p)) 3 4)) 'sfr-d))
			(setf (fdefinition (sfr-name 'sfr-e)) (lambda (v x) (list :e v x)))
			(print (list (not (fboundp (sfr-name 'sfr-e))) (funcall (fdefinition (sfr-name 'sfr-e)) 3 4)))
			(print (fmakunbound (sfr-name 'sfr-e)))
			(print (fboundp (sfr-name 'sfr-e)))
			(print (handler-case (funcall (fdefinition (sfr-name 'sfr-e)) 1 2)
			         (undefined-function (c) (cell-error-name c))))
			(print (list (not (funcall #'fboundp (sfr-name 'sfr-d)))
			             (funcall (funcall #'fdefinition (sfr-name 'sfr-d)) 5 6)))
			(print (fmakunbound (sfr-name 'sfr-d)))
			(print (fboundp (sfr-name 'sfr-d)))
			(defun sfr-f (x) (* x 2))
			(print (list (funcall (fdefinition (car (list 'sfr-f))) 4) (not (fboundp (car (list 'sfr-f))))))
			(defstruct sfr-s a)
			(let ((o (make-sfr-s :a 1)))
			  (print (list (funcall (fdefinition (sfr-name 'sfr-s-a)) 9 o) (sfr-s-a o))))
			""";

	/** What {@link #COMPUTED_DESIGNATOR} prints, one value per line. */
	public static final String COMPUTED_DESIGNATOR_EXPECTED = String.join("\n", "(1 2)", "(NIL NIL)", "(SETF SFR-NOPE)",
			"(3 4)", "(NIL (:E 3 4))", "(SETF SFR-E)", "NIL", "(SETF SFR-E)", "(NIL (5 6))", "(SETF SFR-D)", "NIL",
			"(8 NIL)", "(9 9)");

	/**
	 * {@code (setf (name args...) v)} of a name no definition makes a place: the
	 * {@code (setf name)} function, defined later, installed at run time, or undefined.
	 */
	public static final String UNKNOWN_PLACE = """
			(defun sfu-set (x v) (setf (sfu-late x) v))
			(defun (setf sfu-late) (v x) (list :late v x))
			(print (sfu-set 1 2))
			(print (handler-case (setf (sfu-nope 3) 5)
			         (undefined-function (c) (cell-error-name c))))
			(defun sfu-r (x) (car x))
			(print (handler-case (incf (sfu-r (list 1)))
			         (undefined-function (c) (cell-error-name c))))
			(setf (fdefinition '(setf sfu-dyn)) (lambda (v x) (list :dyn v x)))
			(print (setf (sfu-dyn 1) 2))
			(print (funcall (lambda (k) (setf (sfu-dyn k) (* k 10))) 3))
			""";

	/** What {@link #UNKNOWN_PLACE} prints, one value per line. */
	public static final String UNKNOWN_PLACE_EXPECTED = String.join("\n", "(:LATE 2 1)", "(SETF SFU-NOPE)",
			"(SETF SFU-R)", "(:DYN 2 1)", "(:DYN 30 3)");

	/**
	 * The {@code (setf name)} function of {@code defstruct} slot accessors: taken with
	 * {@code #'}, {@code fdefinition}, {@code apply}, {@code mapcar} and inside a
	 * closure, inherited through {@code :include}, checking its object, and absent for a
	 * {@code :read-only} slot, whose place is then that undefined function too.
	 */
	public static final String STRUCT_WRITER = """
			(defstruct sfs b (c 0 :read-only t))
			(defstruct (sfs2 (:include sfs)) d)
			(print (list (not (fboundp '(setf sfs-b))) (fboundp '(setf sfs-c))
			             (not (fboundp '(setf sfs2-b))) (fboundp '(setf sfs2-c)) (not (fboundp '(setf sfs2-d)))))
			(let ((o (make-sfs :b 1)))
			  (print (list (funcall #'(setf sfs-b) 6 o) (sfs-b o)))
			  (print (list (funcall (fdefinition '(setf sfs-b)) 7 o) (sfs-b o)))
			  (print (list (apply #'(setf sfs-b) (list 8 o)) (sfs-b o)))
			  (print (list (mapcar #'(setf sfs-b) '(9) (list o)) (sfs-b o)))
			  (print (let ((w #'(setf sfs-b))) (funcall (lambda (v) (funcall w v o)) 10)))
			  (print (handler-case (funcall #'(setf sfs-c) 1 o) (undefined-function (c) (cell-error-name c))))
			  (print (handler-case (setf (sfs-c o) 1) (undefined-function (c) (cell-error-name c))))
			  (print (list (sfs-b o) (sfs-c o))))
			(let ((o (make-sfs2 :b 1 :d 2)))
			  (print (list (funcall #'(setf sfs-b) 3 o) (funcall #'(setf sfs2-b) 4 o) (funcall #'(setf sfs2-d) 5 o)
			               (sfs2-b o) (sfs2-d o)))
			  (print (handler-case (setf (sfs2-c o) 1) (undefined-function (c) (cell-error-name c)))))
			(print (handler-case (funcall #'(setf sfs-b) 1 (car (list 42)))
			         (type-error (c) (list (type-error-datum c) (type-error-expected-type c)))))
			""";

	/** What {@link #STRUCT_WRITER} prints, one value per line. */
	public static final String STRUCT_WRITER_EXPECTED = String.join("\n", "(NIL NIL NIL NIL NIL)", "(6 6)", "(7 7)",
			"(8 8)", "((9) 9)", "10", "(SETF SFS-C)", "(SETF SFS-C)", "(10 0)", "(3 4 5 4 5)", "(SETF SFS2-C)",
			"(42 SFS)");

}
