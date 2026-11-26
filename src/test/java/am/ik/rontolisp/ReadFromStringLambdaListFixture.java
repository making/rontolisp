package am.ik.rontolisp;

/**
 * {@code read-from-string}'s whole lambda list -- {@code (string &optional eof-error-p
 * eof-value &key start end preserve-whitespace)} -- shared by the backend suites.
 * <ul>
 * <li>{@link #PROGRAM} (mirrored by the `read-from-string-takes-its-whole-lambda-list`
 * ci-spec case): the datum and the stop index under {@code :start}/{@code :end}; the
 * eof-value of a window holding no datum (comments and whitespace only, a fill pointer's
 * end) against the {@code end-of-file} of an incomplete datum whatever
 * {@code eof-error-p} says; {@code :preserve-whitespace}; a repeated keyword and
 * {@code :allow-other-keys}; a bad bound's {@code type-error} (the datum printed only
 * where it is the bound itself); the function value; an unknown keyword's and an odd
 * tail's {@code program-error}; a function tail and a discarded call; and the order the
 * arguments are evaluated in, every one before the window is checked.</li>
 * <li>{@link #REPORT_PROGRAM}: what a refused bound's {@code type-error} carries -- the
 * bound, its range and {@code subseq}'s report text.</li>
 * </ul>
 * The bounds are read at run time so no backend can fold them.
 */
public final class ReadFromStringLambdaListFixture {

	private ReadFromStringLambdaListFixture() {
	}

	/** The program; each row prints four probes, each order line one. */
	public static final String PROGRAM = """
			(defvar *rfl-9* (read-from-string "9"))
			(defvar *rfl-m1* (read-from-string "-1"))
			(defvar *rfl-f* (read-from-string "1.5"))
			(defvar *rfl-nil* (read-from-string "nil"))
			(defvar *rfl-s* (copy-seq "7123  "))
			(defvar *rfl-fp* (make-array 6 :element-type 'character :initial-contents "ab cd " :fill-pointer 3))
			(defun rfl-probe (thunk)
			  (handler-case (multiple-value-list (funcall thunk))
			    (end-of-file () :end-of-file)
			    (reader-error () :reader-error)
			    (type-error (c)
			      (let ((d (type-error-datum c)))
			        (if (or (consp d) (and (integerp d) (<= 0 d 1000))) :type-error (list :type-error d))))
			    (program-error () :program-error)
			    (error () :other-error)))
			(defmacro rfl-row (&rest forms)
			  `(print (list ,@(mapcar (lambda (f) `(rfl-probe (lambda () ,f))) forms))))
			(defun rfl-tail (s) (read-from-string s nil nil :start 1))
			(rfl-row (read-from-string *rfl-s* t nil :start 1)
			         (read-from-string *rfl-s* t nil :start 1 :end 3)
			         (read-from-string *rfl-s* t nil :end 2)
			         (read-from-string *rfl-s* :start 1))
			(rfl-row (read-from-string "" nil :good)
			         (read-from-string (format nil "  ; c~% #| b |# ") nil :good)
			         (read-from-string *rfl-s* nil 'foo :start 2 :end 2)
			         (read-from-string *rfl-fp* nil :eof :start 3))
			(rfl-row (read-from-string *rfl-s* t nil :start 6)
			         (read-from-string "(a b" nil nil)
			         (read-from-string ")" nil nil)
			         (read-from-string (format nil " #| b |# x~%y") nil nil))
			(rfl-row (read-from-string "123  " t nil :preserve-whitespace t)
			         (read-from-string (format nil "( )~%") t nil :preserve-whitespace t)
			         (read-from-string "(1 2) x" t nil :preserve-whitespace t)
			         (read-from-string "(1 2) x" t nil))
			(rfl-row (read-from-string *rfl-s* t nil :start 1 :start 2)
			         (read-from-string *rfl-s* t nil :end 4 :end 2)
			         (read-from-string "abc   " t nil :allow-other-keys nil)
			         (read-from-string "123   " t nil :foo 'bar :allow-other-keys t))
			(rfl-row (read-from-string *rfl-s* t nil :start *rfl-9*)
			         (read-from-string *rfl-s* t nil :end *rfl-9*)
			         (read-from-string *rfl-s* t nil :start *rfl-m1*)
			         (read-from-string *rfl-s* t nil :start *rfl-nil*))
			(rfl-row (read-from-string *rfl-s* t nil :start 3 :end 1)
			         (read-from-string *rfl-fp* t nil :end 4)
			         (read-from-string *rfl-s* t nil :start *rfl-f*)
			         (read-from-string *rfl-s* nil nil :end *rfl-nil*))
			(rfl-row (funcall #'read-from-string *rfl-s* t nil :start 1)
			         (apply #'read-from-string *rfl-s* nil nil (list :start 1 :end 3))
			         (funcall #'read-from-string "  " nil :e)
			         (funcall #'read-from-string *rfl-s*))
			(rfl-row (read-from-string "A" nil t :bad-keyword t)
			         (read-from-string "1 2" t nil :start)
			         (rfl-tail "a bc")
			         (let ((x (read-from-string "a bc" nil nil))) x))
			(defvar *rfo-log* nil)
			(defun rfo-n (tag v) (push tag *rfo-log*) v)
			(defun rfo-probe (thunk)
			  (setq *rfo-log* nil)
			  (let ((r (handler-case (multiple-value-list (funcall thunk))
			             (error () :error))))
			    (list r (reverse *rfo-log*))))
			(print (rfo-probe (lambda () (read-from-string (rfo-n :s "12345") (rfo-n :e t) (rfo-n :v nil)
			                                               :end (rfo-n :end 4) :start (rfo-n :st 1)))))
			(print (rfo-probe (lambda () (read-from-string (rfo-n :s "12345") (rfo-n :e t) (rfo-n :v nil)
			                                               :start (rfo-n :a 1) :start (rfo-n :b 2)))))
			(print (rfo-probe (lambda () (read-from-string (rfo-n :s "12345") (rfo-n :e t) (rfo-n :v nil)
			                                               :end (rfo-n :end 9) :start (rfo-n :st 1)))))
			(print (rfo-probe (lambda () (funcall #'read-from-string (rfo-n :s "12 45") (rfo-n :e nil) (rfo-n :v :x)
			                                      :preserve-whitespace (rfo-n :p t)))))
			""";

	/** What {@link #PROGRAM} prints (sbcl's answers). */
	public static final String EXPECTED = String.join("\n", "((123 5) (12 3) (71 2) (7123 5))",
			"((:GOOD 0) (:GOOD 15) (FOO 2) (:EOF 3))", "(:END-OF-FILE :END-OF-FILE :READER-ERROR (X 11))",
			"((123 3) (NIL 3) ((1 2) 5) ((1 2) 6))", "((123 5) (7123 4) (ABC 4) (123 4))",
			"(:TYPE-ERROR :TYPE-ERROR (:TYPE-ERROR -1) (:TYPE-ERROR NIL))",
			"(:TYPE-ERROR :TYPE-ERROR (:TYPE-ERROR 1.5) (7123 5))", "((123 5) (12 3) (:E 2) (7123 5))",
			"(:PROGRAM-ERROR :PROGRAM-ERROR (BC 4) (A))", "((234 4) (:S :E :V :END :ST))",
			"((2345 5) (:S :E :V :A :B))", "(:ERROR (:S :E :V :END :ST))", "((12 2) (:S :E :V :P))");

	/** One refusal per line: datum, expected type and report. */
	public static final String REPORT_PROGRAM = """
			(defvar *rfr-9* (read-from-string "9"))
			(defvar *rfr-1* (read-from-string "1"))
			(defvar *rfr-3* (read-from-string "3"))
			(defvar *rfr-nil* nil)
			(defun rfr-probe (thunk)
			  (handler-case (funcall thunk)
			    (type-error (c) (list (type-error-datum c) (type-error-expected-type c) (princ-to-string c)))
			    (error (c) (list :not-a-type-error (type-of c)))))
			(print (rfr-probe (lambda () (read-from-string (copy-seq "123") t nil :start *rfr-9*))))
			(print (rfr-probe (lambda () (read-from-string "123" nil nil :start *rfr-3* :end *rfr-1*))))
			(print (rfr-probe (lambda () (read-from-string (copy-seq "123") t nil :start *rfr-nil*))))
			(print (rfr-probe (lambda () (funcall #'read-from-string (copy-seq "123") t nil :end *rfr-9*))))
			""";

	/** What {@link #REPORT_PROGRAM} prints. */
	public static final String REPORT_EXPECTED = String.join("\n",
			"(9 (INTEGER 0 3) \"SUBSEQ: invalid bounds 9, 3 for string of length 3\")",
			"(1 (INTEGER 3 3) \"SUBSEQ: invalid bounds 3, 1 for string of length 3\")",
			"(NIL (INTEGER 0 3) \"SUBSEQ: invalid bounds NIL, 3 for string of length 3\")",
			"(9 (INTEGER 0 3) \"SUBSEQ: invalid bounds 0, 9 for string of length 3\")");

}
