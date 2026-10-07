package am.ik.rontolisp;

/**
 * The stream type lattice as {@code type-of}, {@code class-of} and {@code subtypep} see
 * it, shared by the backend suites.
 * <ul>
 * <li>{@link #PROGRAM}: per stream kind, the {@code type-of} name, whether the value is
 * of that type (computed {@code typep}), whether {@code class-of} names the same class,
 * whether the value is of its class metaobject, and whether the name is a subtype of
 * {@code stream} (computed {@code subtypep}); then the literal and computed
 * {@code subtypep} rows of each subtype against {@code stream} and the reverse, and
 * {@code find-class} of the stream class names. sbcl's answers, except three deviations:
 * {@code type-of} and {@code class-of} answer the standard class where sbcl answers its
 * implementation subclass ({@code SB-IMPL::STRING-OUTPUT-STREAM},
 * {@code SB-SYS:FD-STREAM}), so {@code (eq (class-of s) (find-class 'string-stream))} is
 * {@code T}; and an echo stream is not a two-way stream (ANSI's class precedence list for
 * {@code echo-stream} is {@code (echo-stream stream t)}; sbcl's is below
 * {@code two-way-stream}).</li>
 * <li>{@link #UNSPELLED_PROGRAM}: the same questions in a program that spells no stream
 * subtype name, so every name reaches {@code typep}/{@code subtypep} only through
 * {@code type-of}/{@code class-of}. sbcl answers the same.</li>
 * </ul>
 */
public final class StreamTypeLatticeFixture {

	private StreamTypeLatticeFixture() {
	}

	/** The program. */
	public static final String PROGRAM = """
			(defclass stl-gray-out (rontolisp:fundamental-character-output-stream) ())
			(defun stl-ty (x name) (typep x name))
			(defun stl-st (a b) (subtypep a b))
			(defvar *stl-out* (make-string-output-stream))
			(defvar *stl-in* (make-string-input-stream "abc"))
			(defun stl-rows (file)
			  (dolist (s (list *stl-out* *stl-in* file (make-synonym-stream '*stl-out*)
			                   (make-two-way-stream *stl-in* *stl-out*) (make-broadcast-stream *stl-out*)
			                   (make-echo-stream *stl-in* *stl-out*) (make-concatenated-stream *stl-in*)
			                   (make-instance 'stl-gray-out)))
			    (let ((name (type-of s)))
			      (print (list name (stl-ty s name) (eq (class-name (class-of s)) name)
			                   (stl-ty s (class-of s)) (stl-st name 'stream))))))
			(with-open-file (f "stl-kind.txt" :direction :output :if-exists :supersede)
			  (stl-rows f))
			(ignore-errors (delete-file "stl-kind.txt"))
			(print (list (subtypep 'file-stream 'stream) (subtypep 'string-stream 'stream)
			             (subtypep 'synonym-stream 'stream) (subtypep 'two-way-stream 'stream)
			             (subtypep 'broadcast-stream 'stream) (subtypep 'echo-stream 'stream)
			             (subtypep 'concatenated-stream 'stream) (subtypep 'stl-gray-out 'stream)
			             (subtypep 'rontolisp:fundamental-stream 'stream)))
			(print (list (subtypep 'stream 'file-stream) (subtypep 'string-stream 'file-stream)
			             (subtypep 'echo-stream 'two-way-stream)
			             (subtypep 'stream 'rontolisp:fundamental-stream)))
			(print (mapcar (lambda (pair) (if (stl-st (car pair) (cadr pair)) 1 0))
			               '((file-stream stream) (string-stream stream) (synonym-stream stream)
			                 (two-way-stream stream) (broadcast-stream stream) (echo-stream stream)
			                 (concatenated-stream stream) (stl-gray-out stream)
			                 (rontolisp:fundamental-stream stream) (stream file-stream)
			                 (string-stream file-stream) (echo-stream two-way-stream))))
			(print (list (class-name (find-class 'stream)) (class-name (find-class 'file-stream))
			             (class-name (find-class 'concatenated-stream))
			             (eq (class-of *stl-in*) (find-class 'string-stream))))
			""";

	/** What {@link #PROGRAM} prints. */
	public static final String EXPECTED = String.join("\n", "(STRING-STREAM T T T T)", "(STRING-STREAM T T T T)",
			"(FILE-STREAM T T T T)", "(SYNONYM-STREAM T T T T)", "(TWO-WAY-STREAM T T T T)",
			"(BROADCAST-STREAM T T T T)", "(ECHO-STREAM T T T T)", "(CONCATENATED-STREAM T T T T)",
			"(STL-GRAY-OUT T T T T)", "(T T T T T T T T T)", "(NIL NIL NIL NIL)", "(1 1 1 1 1 1 1 1 1 0 0 0)",
			"(STREAM FILE-STREAM CONCATENATED-STREAM T)");

	/** The program spelling no stream subtype name. */
	public static final String UNSPELLED_PROGRAM = """
			(defun usp-ty (x name) (typep x name))
			(defun usp-st (a b) (subtypep a b))
			(let ((o (make-string-output-stream))
			      (tw (make-two-way-stream (make-string-input-stream "a") (make-string-output-stream))))
			  (print (list (usp-ty o (type-of o)) (usp-st (type-of o) 'stream)
			               (usp-ty tw (type-of tw)) (usp-st (type-of tw) 'stream)
			               (usp-ty o (class-name (class-of o))) (usp-st (class-name (class-of tw)) 'stream)
			               (usp-ty *error-output* (type-of *error-output*)))))
			""";

	/** What {@link #UNSPELLED_PROGRAM} prints. */
	public static final String UNSPELLED_EXPECTED = "(T T T T T T T)";

}
