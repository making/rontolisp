package am.ik.rontolisp;

/**
 * What {@code rontolisp:stream-read}, {@code stream-close} and {@code stream-write}
 * signal over a value that is no asynchronous stream, shared by the backend suites: the
 * operator's {@code type-error} whose expected type is
 * {@code (SATISFIES RONTOLISP:STREAMP)} -- no type name designates rontolisp's stream
 * ({@code type-of} answers {@code T}, and it is no CL {@code stream}).
 * <ul>
 * <li>{@link #PROGRAM} (mirrored by the
 * `stream-verbs-signal-a-type-error-over-a-non-stream` ci-spec case): a module that can
 * hold a stream -- non-streams in call position, one inside an async body and one under
 * {@code read-all}, both signalled at the await (as is any built-in's type-error raised
 * in an async body, which the JVM's await read as a {@code simple-error}), a condition
 * object as the datum (what reached {@code stream-read} on both wasm backends as a
 * cast-failure trap), and a real stream still read and closed.</li>
 * <li>{@link #NO_STREAM_PROGRAM}: a module that can hold none, where every argument is a
 * non-stream; the reports.</li>
 * <li>{@link #WRITE_PROGRAM}: {@code stream-write}, which only the interpreter and the
 * JVM have (the wasm backends refuse it at compile time), and its other refusals' words,
 * one text on both.</li>
 * </ul>
 * The values are read at run time so no backend can fold them.
 */
public final class AsyncStreamOperandFixture {

	private AsyncStreamOperandFixture() {
	}

	/** The program over a module that can hold a stream. */
	public static final String PROGRAM = """
			(defvar *aso-1* (read-from-string "1"))
			(defvar *aso-nil* (read-from-string "nil"))
			(defvar *aso-a* (read-from-string "a"))
			(defvar *aso-text* (concatenate 'string "a" "b"))
			(defvar *aso-chunks* (list 7 8))
			(defvar *aso-closes* 0)
			(defvar *aso-stream*
			  (rontolisp::%stream-new
			   (lambda () (let ((c (car *aso-chunks*))) (setq *aso-chunks* (cdr *aso-chunks*)) c))
			   (lambda () (setq *aso-closes* (+ *aso-closes* 1)) nil)))
			(defun aso-probe (thunk)
			  (handler-case (funcall thunk)
			    (type-error (c) (list :type-error (type-error-datum c) (type-error-expected-type c)))
			    (error () :other-error)))
			(defmacro aso-row (&rest forms)
			  `(print (list ,@(mapcar (lambda (f) `(aso-probe (lambda () ,f))) forms))))
			(aso-row (rontolisp:stream-read *aso-1*) (rontolisp:stream-read *aso-nil*)
			         (rontolisp:stream-read *aso-a*) (rontolisp:stream-read *aso-text*))
			(aso-row (rontolisp:stream-close *aso-1*) (rontolisp:stream-close *aso-nil*)
			         (rontolisp:stream-close *aso-a*) (rontolisp:stream-close *aso-text*))
			(print (handler-case (rontolisp:await
			                      (funcall (rontolisp:async-lambda ()
			                                 (rontolisp:await (rontolisp:stream-read *aso-1*)))))
			         (type-error (c) (list :type-error (type-error-datum c) (type-error-expected-type c)))))
			(print (handler-case (rontolisp:await (rontolisp:read-all *aso-a*))
			         (type-error (c) (list :type-error (type-error-datum c) (type-error-expected-type c)))))
			(print (handler-case (rontolisp:await (funcall (rontolisp:async-lambda () (car *aso-1*))))
			         (type-error (c) (list :type-error (type-error-datum c) (type-error-expected-type c)))))
			(print (handler-case (rontolisp:stream-read (make-condition 'simple-error :format-control "x"))
			         (type-error (c) (list (type-of (type-error-datum c)) (type-error-expected-type c)))))
			(print (rontolisp:await (rontolisp:stream-read *aso-stream*)))
			(print (list (rontolisp:stream-close *aso-stream*) *aso-closes*))
			""";

	/** What {@link #PROGRAM} prints. */
	public static final String EXPECTED = String.join("\n",
			"((:TYPE-ERROR 1 (SATISFIES RONTOLISP:STREAMP)) (:TYPE-ERROR NIL (SATISFIES RONTOLISP:STREAMP))"
					+ " (:TYPE-ERROR A (SATISFIES RONTOLISP:STREAMP)) (:TYPE-ERROR \"ab\" (SATISFIES RONTOLISP:STREAMP)))",
			"((:TYPE-ERROR 1 (SATISFIES RONTOLISP:STREAMP)) (:TYPE-ERROR NIL (SATISFIES RONTOLISP:STREAMP))"
					+ " (:TYPE-ERROR A (SATISFIES RONTOLISP:STREAMP)) (:TYPE-ERROR \"ab\" (SATISFIES RONTOLISP:STREAMP)))",
			"(:TYPE-ERROR 1 (SATISFIES RONTOLISP:STREAMP))", "(:TYPE-ERROR A (SATISFIES RONTOLISP:STREAMP))",
			"(:TYPE-ERROR 1 LIST)", "(SIMPLE-ERROR (SATISFIES RONTOLISP:STREAMP))", "7", "(NIL 1)");

	/**
	 * The program over a module that can hold no stream: nothing names
	 * {@code %stream-new} or awaits.
	 */
	public static final String NO_STREAM_PROGRAM = """
			(defvar *asn-1* (read-from-string "1"))
			(defvar *asn-nil* (read-from-string "nil"))
			(defun asn-probe (thunk)
			  (handler-case (funcall thunk)
			    (type-error (c) (list (type-error-datum c) (type-error-expected-type c) (princ-to-string c)))
			    (error () :other-error)))
			(print (asn-probe (lambda () (rontolisp:stream-read *asn-1*))))
			(print (asn-probe (lambda () (rontolisp:stream-close *asn-nil*))))
			(print (rontolisp:streamp *asn-1*))
			""";

	/** What {@link #NO_STREAM_PROGRAM} prints. */
	public static final String NO_STREAM_EXPECTED = String.join("\n",
			"(1 (SATISFIES RONTOLISP:STREAMP) \"STREAM-READ: The value 1 is not of type (SATISFIES RONTOLISP:STREAMP)\")",
			"(NIL (SATISFIES RONTOLISP:STREAMP) \"STREAM-CLOSE: The value NIL is not of type (SATISFIES RONTOLISP:STREAMP)\")",
			"NIL");

	/** {@code stream-write}'s refusals, on the interpreter and the JVM. */
	public static final String WRITE_PROGRAM = """
			(defvar *asw-1* (read-from-string "1"))
			(defun asw-probe (thunk)
			  (handler-case (funcall thunk)
			    (type-error (c) (list :type-error (type-error-datum c) (type-error-expected-type c) (princ-to-string c)))
			    (error (c) (list :error (princ-to-string c)))))
			(print (asw-probe (lambda () (rontolisp:stream-write *asw-1* "x"))))
			(print (asw-probe (lambda () (let ((s (rontolisp:make-stream))) (rontolisp:stream-close s) (rontolisp:stream-write s "x")))))
			(print (asw-probe (lambda () (rontolisp:stream-write (rontolisp:make-stream) nil))))
			(print (asw-probe (lambda () (rontolisp:stream-write (rontolisp::%stream-new (lambda () nil) (lambda () nil)) "x"))))
			""";

	/** What {@link #WRITE_PROGRAM} prints. */
	public static final String WRITE_EXPECTED = String.join("\n",
			"(:TYPE-ERROR 1 (SATISFIES RONTOLISP:STREAMP) \"STREAM-WRITE: The value 1 is not of type (SATISFIES RONTOLISP:STREAMP)\")",
			"(:ERROR \"STREAM-WRITE: the stream is closed\")", "(:ERROR \"STREAM-WRITE: a chunk must not be nil\")",
			"(:ERROR \"STREAM-WRITE: the stream has no write end\")");

}
