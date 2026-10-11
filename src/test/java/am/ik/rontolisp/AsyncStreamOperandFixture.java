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
 * <li>{@link #VALUE_PROGRAM} (mirrored by the
 * `async-predicates-and-stream-verbs-are-function-values` ci-spec case): {@code streamp},
 * {@code futurep}, {@code stream-read} and {@code stream-close} as function values --
 * through {@code funcall}, {@code apply}, {@code mapcar} and a variable -- over a stream,
 * a future and a non-stream, the latter the operator's type-error there too.
 * {@link #NO_STREAM_VALUE_PROGRAM} is the same over a module that can hold no stream, and
 * {@link #WRITE_VALUE_PROGRAM} is {@code make-stream} and {@code stream-write}, on the
 * interpreter and the JVM.</li>
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

	/** The function values over a module that can hold a stream and a future. */
	public static final String VALUE_PROGRAM = """
			(defvar *afv-1* (read-from-string "1"))
			(defvar *afv-chunks* (list 7 8))
			(defvar *afv-closes* 0)
			(defvar *afv-stream*
			  (rontolisp::%stream-new
			   (lambda () (let ((c (car *afv-chunks*))) (setq *afv-chunks* (cdr *afv-chunks*)) c))
			   (lambda () (setq *afv-closes* (+ *afv-closes* 1)) nil)))
			(defvar *afv-future* (funcall (rontolisp:async-lambda () 5)))
			(defvar *afv-read* #'rontolisp:stream-read)
			(defun afv-probe (thunk)
			  (handler-case (funcall thunk)
			    (type-error (c) (list :type-error (type-error-datum c) (type-error-expected-type c)))
			    (error () :other-error)))
			(print (mapcar #'rontolisp:streamp (list *afv-stream* *afv-future* *afv-1*)))
			(print (mapcar #'rontolisp:futurep (list *afv-stream* *afv-future* *afv-1*)))
			(print (list (funcall #'rontolisp:streamp *afv-stream*) (apply #'rontolisp:futurep (list *afv-future*))))
			(print (rontolisp:await (funcall *afv-read* *afv-stream*)))
			(print (rontolisp:await (car (mapcar #'rontolisp:stream-read (list *afv-stream*)))))
			(print (rontolisp:await (apply #'rontolisp:stream-read (list *afv-stream*))))
			(print (list (funcall #'rontolisp:stream-close *afv-stream*) *afv-closes*))
			(print (list (afv-probe (lambda () (funcall *afv-read* *afv-1*)))
			             (afv-probe (lambda () (apply #'rontolisp:stream-close (list *afv-1*))))
			             (afv-probe (lambda () (mapcar #'rontolisp:stream-read (list "ab"))))))
			""";

	/** What {@link #VALUE_PROGRAM} prints. */
	public static final String VALUE_EXPECTED = String.join("\n", "(T NIL NIL)", "(NIL T NIL)", "(T T)", "7", "8",
			"NIL", "(NIL 1)",
			"((:TYPE-ERROR 1 (SATISFIES RONTOLISP:STREAMP)) (:TYPE-ERROR 1 (SATISFIES RONTOLISP:STREAMP))"
					+ " (:TYPE-ERROR \"ab\" (SATISFIES RONTOLISP:STREAMP)))");

	/**
	 * The function values over a module that can hold no stream and no future: nothing
	 * names {@code %stream-new}, an async form or {@code await}.
	 */
	public static final String NO_STREAM_VALUE_PROGRAM = """
			(defvar *afn-1* (read-from-string "1"))
			(print (mapcar #'rontolisp:streamp (list *afn-1* "x")))
			(print (mapcar #'rontolisp:futurep (list *afn-1* "x")))
			(print (handler-case (funcall #'rontolisp:stream-read *afn-1*)
			         (type-error (c) (list (type-error-datum c) (type-error-expected-type c)))))
			(print (handler-case (apply #'rontolisp:stream-close (list *afn-1*))
			         (type-error (c) (list (type-error-datum c) (type-error-expected-type c)))))
			""";

	/** What {@link #NO_STREAM_VALUE_PROGRAM} prints. */
	public static final String NO_STREAM_VALUE_EXPECTED = String.join("\n", "(NIL NIL)", "(NIL NIL)",
			"(1 (SATISFIES RONTOLISP:STREAMP))", "(1 (SATISFIES RONTOLISP:STREAMP))");

	/**
	 * {@code make-stream} and {@code stream-write} as function values, on the interpreter
	 * and the JVM.
	 */
	public static final String WRITE_VALUE_PROGRAM = """
			(defvar *afw-1* (read-from-string "1"))
			(defvar *afw-s* (funcall #'rontolisp:make-stream))
			(funcall #'rontolisp:stream-write *afw-s* "ab")
			(apply #'rontolisp:stream-write *afw-s* (list "cd"))
			(mapcar #'rontolisp:stream-write (list *afw-s*) (list "ef"))
			(rontolisp:stream-close *afw-s*)
			(print (rontolisp:await (rontolisp:read-all *afw-s*)))
			(print (handler-case (funcall #'rontolisp:stream-write *afw-1* "x")
			         (type-error (c) (list (type-error-datum c) (type-error-expected-type c)))))
			""";

	/** What {@link #WRITE_VALUE_PROGRAM} prints. */
	public static final String WRITE_VALUE_EXPECTED = String.join("\n", "\"abcdef\"",
			"(1 (SATISFIES RONTOLISP:STREAMP))");

}
