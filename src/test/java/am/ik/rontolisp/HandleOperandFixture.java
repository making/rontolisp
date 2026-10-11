package am.ik.rontolisp;

/**
 * What the {@code rontolisp:} thread and socket operators signal over an argument they
 * cannot take, shared by the interpreter's and the JVM backend's suites (no wasm backend
 * holds a thread, and the component's socket layer answers nil): a thread operator over a
 * non-thread is its {@code type-error} expecting {@code (SATISFIES RONTOLISP:THREADP)}; a
 * host, path or password that is no string, a port outside {@code (INTEGER 0 65535)} and
 * a deadline outside {@code (OR NULL (INTEGER 0))} are the operator's {@code type-error};
 * a handle naming no open entry of the operator's kind is its
 * {@code OP expects a ... handle} {@code simple-error}; a bad bindings alist is
 * {@code make-thread}'s, before anything starts. The values are read at run time so no
 * backend can fold them, and the non-handle probes run first, while the program has no
 * stream table at all.
 */
public final class HandleOperandFixture {

	private HandleOperandFixture() {
	}

	/** The program. */
	public static final String PROGRAM = """
			(defvar *ho-1* (read-from-string "1"))
			(defvar *ho-nil* (read-from-string "nil"))
			(defvar *ho-str* (read-from-string "\\"x\\""))
			(defvar *ho-big* (read-from-string "100000000000000000000"))
			(defun ho-probe (thunk)
			  (handler-case (funcall thunk)
			    (type-error (c) (list :type-error (type-error-datum c) (type-error-expected-type c)
			                          (princ-to-string c)))
			    (simple-error (c) (list :simple-error (princ-to-string c)))
			    (error () :other-error)))
			(print (ho-probe (lambda () (rontolisp:tcp-local-port *ho-1*))))
			(print (ho-probe (lambda () (rontolisp:tcp-peer-port *ho-nil*))))
			(print (ho-probe (lambda () (rontolisp:tcp-accept *ho-str*))))
			(print (ho-probe (lambda () (rontolisp:thread-alive-p *ho-1*))))
			(print (ho-probe (lambda () (rontolisp:join-thread *ho-nil*))))
			(print (ho-probe (lambda () (funcall #'rontolisp:destroy-thread *ho-str*))))
			(print (ho-probe (lambda () (rontolisp:make-thread (lambda () 1) (list *ho-1*)))))
			(print (ho-probe (lambda () (rontolisp:tcp-connect *ho-1* 80))))
			(print (ho-probe (lambda () (rontolisp:tcp-connect "127.0.0.1" *ho-str*))))
			(print (ho-probe (lambda () (rontolisp:tls-connect *ho-nil* 443))))
			(print (ho-probe (lambda () (rontolisp:tcp-listen *ho-big*))))
			(print (ho-probe (lambda () (rontolisp:tcp-listen 0 *ho-1*))))
			(print (ho-probe (lambda () (rontolisp:tls-listen "/nonexistent.p12" *ho-1* 0))))
			(let* ((l (rontolisp:tcp-listen 0 *ho-nil*))
			       (c (rontolisp:tcp-connect "127.0.0.1" (rontolisp:tcp-local-port l)))
			       (a (rontolisp:tcp-accept l)))
			  (print (ho-probe (lambda () (rontolisp:tcp-peer-port l))))
			  (print (ho-probe (lambda () (rontolisp:tcp-accept c))))
			  (print (ho-probe (lambda () (rontolisp:tcp-local-port *standard-output*))))
			  (print (ho-probe (lambda () (rontolisp:tls-upgrade c *ho-1*))))
			  (print (ho-probe (lambda () (rontolisp:tcp-set-timeout c (- *ho-1* 2)))))
			  (print (list (rontolisp:tcp-set-timeout c *ho-big*) (rontolisp:tcp-set-timeout c *ho-nil*)))
			  (close a)
			  (close c)
			  (print (ho-probe (lambda () (rontolisp:tcp-peer-address c))))
			  (close l))
			(print (rontolisp:join-thread (rontolisp:make-thread (lambda () :joined) *ho-nil*)))
			""";

	/** What {@link #PROGRAM} prints. */
	public static final String EXPECTED = String.join("\n",
			"(:SIMPLE-ERROR \"TCP-LOCAL-PORT expects a socket or listener handle\")",
			"(:SIMPLE-ERROR \"TCP-PEER-PORT expects a connected socket handle\")",
			"(:SIMPLE-ERROR \"TCP-ACCEPT expects a listener handle\")",
			"(:TYPE-ERROR 1 (SATISFIES RONTOLISP:THREADP) \"THREAD-ALIVE-P: The value 1 is not of type (SATISFIES RONTOLISP:THREADP)\")",
			"(:TYPE-ERROR NIL (SATISFIES RONTOLISP:THREADP) \"JOIN-THREAD: The value NIL is not of type (SATISFIES RONTOLISP:THREADP)\")",
			"(:TYPE-ERROR \"x\" (SATISFIES RONTOLISP:THREADP) \"DESTROY-THREAD: The value \\\"x\\\" is not of type (SATISFIES RONTOLISP:THREADP)\")",
			"(:SIMPLE-ERROR \"MAKE-THREAD expects an alist of (symbol . value) bindings, got (1)\")",
			"(:TYPE-ERROR 1 STRING \"TCP-CONNECT: The value 1 is not of type STRING\")",
			"(:TYPE-ERROR \"x\" (INTEGER 0 65535) \"TCP-CONNECT: The value \\\"x\\\" is not of type (INTEGER 0 65535)\")",
			"(:TYPE-ERROR NIL STRING \"TLS-CONNECT: The value NIL is not of type STRING\")",
			"(:TYPE-ERROR 100000000000000000000 (INTEGER 0 65535) \"TCP-LISTEN: The value 100000000000000000000 is not of type (INTEGER 0 65535)\")",
			"(:TYPE-ERROR 1 STRING \"TCP-LISTEN: The value 1 is not of type STRING\")",
			"(:TYPE-ERROR 1 STRING \"TLS-LISTEN: The value 1 is not of type STRING\")",
			"(:SIMPLE-ERROR \"TCP-PEER-PORT expects a connected socket handle\")",
			"(:SIMPLE-ERROR \"TCP-ACCEPT expects a listener handle\")",
			"(:SIMPLE-ERROR \"TCP-LOCAL-PORT expects a socket or listener handle\")",
			"(:TYPE-ERROR 1 STRING \"TLS-UPGRADE: The value 1 is not of type STRING\")",
			"(:TYPE-ERROR -1 (OR NULL (INTEGER 0)) \"TCP-SET-TIMEOUT: The value -1 is not of type (OR NULL (INTEGER 0))\")",
			"(100000000000000000000 NIL)", "(:SIMPLE-ERROR \"TCP-PEER-ADDRESS expects a connected socket handle\")",
			":JOINED");

}
