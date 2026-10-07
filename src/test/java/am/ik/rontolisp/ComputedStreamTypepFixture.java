package am.ik.rontolisp;

/**
 * A stream type name held in a VALUE, shared by the backend suites: {@code typep} with a
 * computed specifier answers what the literal spelling answers.
 * <ul>
 * <li>Each row is one name against a string output stream, a string input stream, a file
 * stream, a synonym, two-way, broadcast, echo and concatenated stream, a Gray stream and
 * three non-streams. sbcl's answers, except the echo stream under {@code two-way-stream}:
 * sbcl's echo stream is a two-way stream, ANSI's class precedence list for
 * {@code echo-stream} is {@code (echo-stream stream t)}, and the literal spelling answers
 * {@code NIL} here.</li>
 * <li>The compound {@code (and string-stream (satisfies output-stream-p))} computed, and
 * the same specifier read back out of {@code get-output-stream-string}'s
 * {@code type-error}.</li>
 * </ul>
 */
public final class ComputedStreamTypepFixture {

	private ComputedStreamTypepFixture() {
	}

	/** The program. */
	public static final String PROGRAM = """
			(defclass cst-gray-out (rontolisp:fundamental-character-output-stream) ())
			(defun cst-ty (x name) (typep x name))
			(defvar *cst-out* (make-string-output-stream))
			(defvar *cst-in* (make-string-input-stream "abc"))
			(defun cst-rows (file)
			  (let ((values (list *cst-out* *cst-in* file (make-synonym-stream '*cst-out*)
			                      (make-two-way-stream *cst-in* *cst-out*) (make-broadcast-stream *cst-out*)
			                      (make-echo-stream *cst-in* *cst-out*) (make-concatenated-stream *cst-in*)
			                      (make-instance 'cst-gray-out) 5 "s" nil)))
			    (dolist (name '(stream file-stream string-stream synonym-stream two-way-stream
			                    broadcast-stream echo-stream concatenated-stream))
			      (print (cons name (mapcar (lambda (x) (if (cst-ty x name) 1 0)) values))))))
			(with-open-file (f "cst-kind.txt" :direction :output :if-exists :supersede)
			  (cst-rows f))
			(ignore-errors (delete-file "cst-kind.txt"))
			(defvar *cst-type* (list 'and 'string-stream (list 'satisfies 'output-stream-p)))
			(print (list (cst-ty *cst-out* *cst-type*) (cst-ty *cst-in* *cst-type*)))
			(print (handler-case (get-output-stream-string *cst-in*)
			         (type-error (c)
			           (list (cst-ty *cst-out* (type-error-expected-type c))
			                 (cst-ty *cst-in* (type-error-expected-type c))))))
			""";

	/** What {@link #PROGRAM} prints. */
	public static final String EXPECTED = String.join("\n", "(STREAM 1 1 1 1 1 1 1 1 1 0 0 0)",
			"(FILE-STREAM 0 0 1 0 0 0 0 0 0 0 0 0)", "(STRING-STREAM 1 1 0 0 0 0 0 0 0 0 0 0)",
			"(SYNONYM-STREAM 0 0 0 1 0 0 0 0 0 0 0 0)", "(TWO-WAY-STREAM 0 0 0 0 1 0 0 0 0 0 0 0)",
			"(BROADCAST-STREAM 0 0 0 0 0 1 0 0 0 0 0 0)", "(ECHO-STREAM 0 0 0 0 0 0 1 0 0 0 0 0)",
			"(CONCATENATED-STREAM 0 0 0 0 0 0 0 1 0 0 0 0)", "(T NIL)", "(T NIL)");

}
