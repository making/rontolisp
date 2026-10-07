package am.ik.rontolisp;

/**
 * What {@code get-output-stream-string} of a stream that is no string output stream
 * signals, shared by the backend suites (mirrored by the
 * `get-output-stream-string-refuses-another-kind-of-stream` ci-spec case).
 * <ul>
 * <li>{@link #PROGRAM}: a string input stream, the standard error, a synonym stream (one
 * over the standard error, one over a string output stream), a file stream, a two-way and
 * a broadcast stream and a Gray stream are each a {@code type-error} whose datum is the
 * stream as given and whose expected type is
 * {@code (AND STRING-STREAM (SATISFIES OUTPUT-STREAM-P))} -- in call position and first
 * class -- and the streams stay usable. sbcl's answers, except the expected type: sbcl's
 * is its internal {@code STRING-OUTPUT-STREAM}, which has no standard name. {@code t},
 * the designator {@code *standard-output*} holds, is a stream here.</li>
 * <li>{@link #REPORT_PROGRAM}: the report text.</li>
 * </ul>
 */
public final class OutputStreamStringKindFixture {

	private OutputStreamStringKindFixture() {
	}

	/** The program. */
	public static final String PROGRAM = """
			(defclass goss-gray-out (rontolisp:fundamental-character-output-stream) ())
			(defvar *goss-out* (make-string-output-stream))
			(defvar *goss-in* (make-string-input-stream "abc"))
			(defvar *goss-type* (list 'and 'string-stream (list 'satisfies 'output-stream-p)))
			(defun goss-check (stream thunk)
			  (handler-case (multiple-value-list (funcall thunk))
			    (type-error (c)
			      (list :type-error (eq (type-error-datum c) stream)
			            (equal (type-error-expected-type c) *goss-type*)))
			    (error () :other-error)))
			(defmacro goss-row (&rest streams)
			  `(print (list ,@(mapcar (lambda (s)
			                            `(let ((goss-s ,s))
			                               (goss-check goss-s (lambda () (get-output-stream-string goss-s)))))
			                          streams))))
			(goss-row *goss-in* *error-output* (make-synonym-stream '*error-output*) (make-synonym-stream '*goss-out*))
			(with-open-file (f "goss-kind.txt" :direction :output :if-exists :supersede)
			  (goss-row f (make-two-way-stream *goss-in* *goss-out*) (make-broadcast-stream) (make-instance 'goss-gray-out)))
			(ignore-errors (delete-file "goss-kind.txt"))
			(print (list (goss-check *goss-in* (lambda () (funcall #'get-output-stream-string *goss-in*)))
			             (goss-check *error-output* (lambda () (apply 'get-output-stream-string (list *error-output*))))))
			(write-string "kept" *goss-out*)
			(print (list (handler-case (get-output-stream-string t) (type-error (c) (type-error-datum c)))
			             (get-output-stream-string *goss-out*)
			             (read-char *goss-in*)))
			""";

	/** What {@link #PROGRAM} prints. */
	public static final String EXPECTED = String.join("\n",
			"((:TYPE-ERROR T T) (:TYPE-ERROR T T) (:TYPE-ERROR T T) (:TYPE-ERROR T T))",
			"((:TYPE-ERROR T T) (:TYPE-ERROR T T) (:TYPE-ERROR T T) (:TYPE-ERROR T T))",
			"((:TYPE-ERROR T T) (:TYPE-ERROR T T))", "(T \"kept\" #\\a)");

	/** The report of a refused stream. */
	public static final String REPORT_PROGRAM = """
			(print (handler-case (get-output-stream-string t) (type-error (c) (princ-to-string c))))
			""";

	/** What {@link #REPORT_PROGRAM} prints. */
	public static final String REPORT_EXPECTED = "\"GET-OUTPUT-STREAM-STRING: The value T is not of type (AND STRING-STREAM (SATISFIES OUTPUT-STREAM-P))\"";

}
