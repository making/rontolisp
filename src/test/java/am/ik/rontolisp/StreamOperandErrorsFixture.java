package am.ik.rontolisp;

/**
 * What {@code load} of a missing file and the string-stream operators and {@code close}
 * over a value they cannot take signal, shared by the backend suites.
 * <ul>
 * <li>{@link #PROGRAM} (mirrored by the
 * `load-and-stream-operators-signal-their-condition` ci-spec case): a missing file is a
 * {@code file-error} carrying the designator as given; {@code close} and
 * {@code get-output-stream-string} of a non-stream are a {@code type-error} expecting
 * {@code STREAM}, {@code make-string-input-stream} of a non-string one expecting
 * {@code STRING}, and its bounds are {@code subseq}'s {@code type-error} (printed without
 * the datum: sbcl's range is a cons, rontolisp's an {@code INTEGER} type) -- in call
 * position and first class; a {@code with-open-file} whose open answers nil closes
 * nothing. sbcl's answers, except two: its {@code get-output-stream-string} expects its
 * internal {@code STRING-OUTPUT-STREAM} type, which has no standard name, and
 * {@code (close *standard-output*)} closes its standard output, where rontolisp's
 * standard streams survive a close that answers {@code t}.</li>
 * <li>{@link #REPORT_PROGRAM}: the datum, expected type and report text the conditions
 * carry.</li>
 * </ul>
 * The values are read at run time so no backend can fold them.
 */
public final class StreamOperandErrorsFixture {

	private StreamOperandErrorsFixture() {
	}

	/** The program; each row prints four probes. */
	public static final String PROGRAM = """
			(defvar *soe-1* (read-from-string "1"))
			(defvar *soe-5* (read-from-string "5"))
			(defvar *soe-nil* (read-from-string "nil"))
			(defvar *soe-a* (read-from-string "a"))
			(defvar *soe-missing* (concatenate 'string "soe-no-such-" "file.lisp"))
			(defun soe-probe (thunk)
			  (handler-case (multiple-value-list (funcall thunk))
			    (type-error (c)
			      (if (symbolp (type-error-expected-type c))
			          (list :type-error (type-error-datum c) (type-error-expected-type c))
			          :type-error))
			    (file-error (c) (list :file-error (file-error-pathname c)))
			    (error () :other-error)))
			(defmacro soe-row (&rest forms)
			  `(print (list ,@(mapcar (lambda (f) `(soe-probe (lambda () ,f))) forms))))
			(soe-row (load *soe-missing*) (load "soe-no-such-file.lisp") (funcall #'load *soe-missing*) (load *soe-missing* :if-does-not-exist nil))
			(soe-row (close *soe-1*) (close *soe-nil*) (close *soe-a*) (funcall #'close *soe-1*))
			(soe-row (get-output-stream-string *soe-1*) (get-output-stream-string *soe-nil*) (funcall #'get-output-stream-string *soe-a*) (get-output-stream-string (make-string-output-stream)))
			(soe-row (make-string-input-stream *soe-1*) (make-string-input-stream *soe-nil*) (funcall #'make-string-input-stream *soe-a*) (make-string-input-stream "abc" *soe-5*))
			(soe-row (make-string-input-stream "abc" 2 1) (make-string-input-stream "abc" -1) (funcall #'make-string-input-stream "abc" 0 *soe-5*) (values (read-line (make-string-input-stream "abcdef" 1 3))))
			(soe-row (close (make-string-output-stream)) (let ((s (make-string-input-stream "x"))) (close s) (close s)) (apply #'close (list *soe-5*)) (close *standard-output*))
			(soe-row (with-open-file (s *soe-missing* :if-does-not-exist nil) s) (with-open-file (s *soe-missing* :direction :probe) s) (with-open-stream (s *soe-nil*) s) (with-open-file (s *soe-missing* :if-does-not-exist *soe-nil*) s))
			""";

	/** What {@link #PROGRAM} prints. */
	public static final String EXPECTED = String.join("\n",
			"((:FILE-ERROR \"soe-no-such-file.lisp\") (:FILE-ERROR \"soe-no-such-file.lisp\") (:FILE-ERROR \"soe-no-such-file.lisp\") (NIL))",
			"((:TYPE-ERROR 1 STREAM) (:TYPE-ERROR NIL STREAM) (:TYPE-ERROR A STREAM) (:TYPE-ERROR 1 STREAM))",
			"((:TYPE-ERROR 1 STREAM) (:TYPE-ERROR NIL STREAM) (:TYPE-ERROR A STREAM) (\"\"))",
			"((:TYPE-ERROR 1 STRING) (:TYPE-ERROR NIL STRING) (:TYPE-ERROR A STRING) :TYPE-ERROR)",
			"(:TYPE-ERROR :TYPE-ERROR :TYPE-ERROR (\"bc\"))", "((T) (T) (:TYPE-ERROR 5 STREAM) (T))",
			"((NIL) (NIL) (:TYPE-ERROR NIL STREAM) (NIL))");

	/** The conditions' datum or pathname, expected type and report. */
	public static final String REPORT_PROGRAM = """
			(defvar *soer-1* (read-from-string "1"))
			(defvar *soer-missing* (concatenate 'string "soer-no-such-" "file.lisp"))
			(defun soer-probe (thunk)
			  (handler-case (funcall thunk)
			    (type-error (c)
			      (list (type-error-datum c) (type-error-expected-type c) (princ-to-string c)))
			    (file-error (c) (list (file-error-pathname c) (princ-to-string c)))))
			(print (soer-probe (lambda () (load *soer-missing*))))
			(print (soer-probe (lambda () (close *soer-1*))))
			(print (soer-probe (lambda () (get-output-stream-string *soer-1*))))
			(print (soer-probe (lambda () (make-string-input-stream *soer-1*))))
			(print (soer-probe (lambda () (make-string-input-stream "abc" 0 (+ *soer-1* 4)))))
			""";

	/** What {@link #REPORT_PROGRAM} prints. */
	public static final String REPORT_EXPECTED = String.join("\n",
			"(\"soer-no-such-file.lisp\" \"LOAD: cannot open file soer-no-such-file.lisp\")",
			"(1 STREAM \"CLOSE: The value 1 is not of type STREAM\")",
			"(1 STREAM \"GET-OUTPUT-STREAM-STRING: The value 1 is not of type STREAM\")",
			"(1 STRING \"MAKE-STRING-INPUT-STREAM: The value 1 is not of type STRING\")",
			"(5 (INTEGER 0 3) \"SUBSEQ: invalid bounds 0, 5 for string of length 3\")");

}
