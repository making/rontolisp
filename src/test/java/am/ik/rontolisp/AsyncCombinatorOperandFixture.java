package am.ik.rontolisp;

/**
 * What {@code rontolisp:then}, {@code then*}, {@code catch} and {@code finally} signal
 * over a first argument that is no future, shared by the backend suites (mirrored by the
 * `future-combinators-signal-a-type-error-over-a-non-future` ci-spec case): the
 * operator's {@code type-error} whose expected type is
 * {@code (SATISFIES RONTOLISP:FUTUREP)} -- no type name designates a future. The values
 * are read at run time so no backend can fold them; a real future still threads through.
 */
public final class AsyncCombinatorOperandFixture {

	private AsyncCombinatorOperandFixture() {
	}

	/** The program. */
	public static final String PROGRAM = """
			(defvar *aco-42* (read-from-string "42"))
			(defvar *aco-nil* (read-from-string "nil"))
			(defvar *aco-a* (read-from-string "a"))
			(defun aco-probe (thunk)
			  (handler-case (funcall thunk)
			    (type-error (c) (list :type-error (type-error-datum c) (type-error-expected-type c)
			                          (princ-to-string c)))
			    (error () :other-error)))
			(print (aco-probe (lambda () (rontolisp:then *aco-42* #'identity))))
			(print (aco-probe (lambda () (rontolisp:then* *aco-nil* #'identity))))
			(print (aco-probe (lambda () (rontolisp:catch *aco-a* (lambda (c) c)))))
			(print (aco-probe (lambda () (rontolisp:finally *aco-42* (lambda () nil)))))
			(print (catch 'aco-tag (throw 'aco-tag :thrown)))
			(print (rontolisp:await (rontolisp:then (funcall (rontolisp:async-lambda () 20)) #'1+)))
			""";

	/** What {@link #PROGRAM} prints. */
	public static final String EXPECTED = String.join("\n",
			"(:TYPE-ERROR 42 (SATISFIES RONTOLISP:FUTUREP) \"THEN: The value 42 is not of type (SATISFIES RONTOLISP:FUTUREP)\")",
			"(:TYPE-ERROR NIL (SATISFIES RONTOLISP:FUTUREP) \"THEN*: The value NIL is not of type (SATISFIES RONTOLISP:FUTUREP)\")",
			"(:TYPE-ERROR A (SATISFIES RONTOLISP:FUTUREP) \"CATCH: The value A is not of type (SATISFIES RONTOLISP:FUTUREP)\")",
			"(:TYPE-ERROR 42 (SATISFIES RONTOLISP:FUTUREP) \"FINALLY: The value 42 is not of type (SATISFIES RONTOLISP:FUTUREP)\")",
			":THROWN", "21");

}
