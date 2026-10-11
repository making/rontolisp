package am.ik.rontolisp;

/**
 * The {@code rontolisp:} native built-ins as function values ({@code #'rontolisp:NAME}
 * through {@code funcall}, {@code apply}, {@code mapcar} and a variable), on the backends
 * whose call position has each:
 * <ul>
 * <li>{@link #PORTABLE_PROGRAM} (mirrored by the `rontolisp-natives-are-function-values`
 * ci-spec case): the mutex primitives, {@code version} and the {@code json-*} pair, on
 * all four.</li>
 * <li>{@link #THREAD_PROGRAM}: the thread primitives, interpreter and JVM.
 * {@link #NO_THREAD_PROGRAM} is the WASM backends' side: a value of one is the
 * undefined-function its call is.</li>
 * <li>{@link #SOCKET_PROGRAM}: the {@code tcp-*} family over a loopback connection, on
 * the interpreter, the JVM and the component; {@link #NO_SOCKET_PROGRAM} is Preview 1's
 * call-time refusal.</li>
 * <li>{@link #ASYNC_PROGRAM}: {@code wait-for} and {@code fetch}, on the interpreter, the
 * JVM and the component (Preview 1 refuses either at compile time, as a call).</li>
 * <li>{@link #TLS_PROGRAM}: {@code tls-connect} and {@code tls-upgrade} over a refused
 * connection and a keyword the lowering does not take, and {@code tcp-set-timeout} (which
 * the component refuses), interpreter and JVM.</li>
 * </ul>
 * The compiled backends had no value for any but {@code json-*} and the component's
 * library defuns: {@code The function RONTOLISP:MAKE-MUTEX is undefined} (measured
 * 2026-10-11).
 */
public final class RontolispNativeValueFixture {

	private RontolispNativeValueFixture() {
	}

	/** The natives every backend has. */
	public static final String PORTABLE_PROGRAM = """
			(defvar *rnv-m* (funcall #'rontolisp:make-mutex))
			(defvar *rnv-acquire* #'rontolisp:mutex-acquire)
			(defun rnv-probe (thunk) (handler-case (progn (funcall thunk) :ok) (error () :error)))
			(print (list (rnv-probe (lambda () (funcall *rnv-acquire* *rnv-m*) (rontolisp:mutex-release *rnv-m*)))
			             (rnv-probe (lambda () (rontolisp:mutex-acquire *rnv-m*)
			                          (apply #'rontolisp:mutex-release (list *rnv-m*))))
			             (rnv-probe (lambda () (mapc #'rontolisp:mutex-acquire (list *rnv-m*))
			                          (mapc #'rontolisp:mutex-release (list *rnv-m*))))))
			(print (list (equal (funcall #'rontolisp:version) (rontolisp:version))
			             (stringp (getf (apply #'rontolisp:version nil) :version))))
			(print (mapcar #'rontolisp:json-stringify (mapcar #'rontolisp:json-parse (list "[1,2]" "\\"a\\""))))
			""";

	/** What {@link #PORTABLE_PROGRAM} prints. */
	public static final String PORTABLE_EXPECTED = String.join("\n", "(:OK :OK :OK)", "(T T)",
			"(\"[1,2]\" \"\\\"a\\\"\")");

	/** The thread primitives, interpreter and JVM. */
	public static final String THREAD_PROGRAM = """
			(defvar *rnt-x* 0)
			(defvar *rnt-make* #'rontolisp:make-thread)
			(defvar *rnt-a* (funcall *rnt-make* (lambda () (+ 1 2))))
			(defvar *rnt-b* (apply #'rontolisp:make-thread (list (lambda () *rnt-x*) '((*rnt-x* . 5)))))
			(print (mapcar #'rontolisp:join-thread (list *rnt-a* *rnt-b*)))
			(print (mapcar #'rontolisp:threadp (list *rnt-a* 1)))
			(print (funcall #'rontolisp:thread-alive-p *rnt-a*))
			(print (list (eq (funcall #'rontolisp:current-thread) (rontolisp:current-thread))
			             (rontolisp:threadp (apply #'rontolisp:current-thread nil))))
			(funcall #'rontolisp:destroy-thread *rnt-b*)
			(print :destroyed)
			""";

	/** What {@link #THREAD_PROGRAM} prints. */
	public static final String THREAD_EXPECTED = String.join("\n", "(3 5)", "(T NIL)", "NIL", "(T T)", ":DESTROYED");

	/** The thread primitives on the WASM backends, which have none. */
	public static final String NO_THREAD_PROGRAM = """
			(defun rnw-probe (thunk)
			  (handler-case (funcall thunk) (undefined-function (c) (cell-error-name c))))
			(print (list (rnw-probe (lambda () (funcall #'rontolisp:threadp 1)))
			             (rnw-probe (lambda () (rontolisp:threadp 1)))
			             (rnw-probe (lambda () (mapcar #'rontolisp:make-thread (list 1))))))
			""";

	/** What {@link #NO_THREAD_PROGRAM} prints. */
	public static final String NO_THREAD_EXPECTED = "(RONTOLISP:THREADP RONTOLISP:THREADP RONTOLISP:MAKE-THREAD)";

	/** The {@code tcp-*} family over a loopback connection. */
	public static final String SOCKET_PROGRAM = """
			(let* ((listener (funcall #'rontolisp:tcp-listen 0 "127.0.0.1"))
			       (port (car (mapcar #'rontolisp:tcp-local-port (list listener))))
			       (client (apply #'rontolisp:tcp-connect (list "127.0.0.1" port))))
			  (write-line "hello" client)
			  (let ((server (funcall #'rontolisp:tcp-accept listener)))
			    (print (read-line server))
			    (print (list (= (funcall #'rontolisp:tcp-peer-port server) (rontolisp:tcp-peer-port server))
			                 (equal (funcall #'rontolisp:tcp-peer-address server) (rontolisp:tcp-peer-address server))
			                 (equal (funcall #'rontolisp:tcp-local-address listener)
			                        (rontolisp:tcp-local-address listener))))
			    (close client)
			    (close server)
			    (close listener)))
			""";

	/** What {@link #SOCKET_PROGRAM} prints. */
	public static final String SOCKET_EXPECTED = String.join("\n", "\"hello\"", "(T T T)");

	/** The {@code tcp-*} family on Preview 1: the call-time refusal a call is. */
	public static final String NO_SOCKET_PROGRAM = """
			(print (handler-case (funcall #'rontolisp:tcp-connect "127.0.0.1" 1) (error (c) (princ-to-string c))))
			(print (handler-case (mapcar #'rontolisp:tcp-local-port (list 1)) (error (c) (princ-to-string c))))
			""";

	/** What {@link #NO_SOCKET_PROGRAM} prints. */
	public static final String NO_SOCKET_EXPECTED = String.join("\n",
			"\"rontolisp:TCP-CONNECT requires the interpreter, the JVM backend or --component"
					+ " (no host socket API is wired on Preview 1 WASM)\"",
			"\"rontolisp:TCP-LOCAL-PORT requires the interpreter, the JVM backend or --component"
					+ " (no host socket API is wired on Preview 1 WASM)\"");

	/** {@code wait-for} and {@code fetch}. */
	public static final String ASYNC_PROGRAM = """
			(print (rontolisp:await (funcall #'rontolisp:wait-for 1)))
			(print (mapcar #'rontolisp:futurep (mapcar #'rontolisp:wait-for (list 1 2))))
			(defvar *rna-fetch* #'rontolisp:fetch)
			(print (rontolisp:futurep (apply *rna-fetch* (list "http://127.0.0.1:1/"))))
			(print (handler-case (rontolisp:await (funcall #'rontolisp:fetch "http://127.0.0.1:1/" '(:method "GET")))
			         (error () :failed)))
			""";

	/** What {@link #ASYNC_PROGRAM} prints. */
	public static final String ASYNC_EXPECTED = String.join("\n", "NIL", "(T T)", "T", ":FAILED");

	/**
	 * {@code tls-connect}, {@code tls-upgrade} and {@code tcp-set-timeout}, interpreter
	 * and JVM.
	 */
	public static final String TLS_PROGRAM = """
			(defvar *rns-connect* #'rontolisp:tls-connect)
			(print (handler-case (funcall *rns-connect* "127.0.0.1" 1) (error () :refused)))
			(print (handler-case (apply #'rontolisp:tls-connect (list "127.0.0.1" 1 :insecure t)) (error () :refused)))
			(print (handler-case (funcall #'rontolisp:tls-connect "127.0.0.1" 1 :verify t)
			         (error (c) (princ-to-string c))))
			(defvar *rns-listener* (rontolisp:tcp-listen 0 "127.0.0.1"))
			(defvar *rns-sock* (rontolisp:tcp-connect "127.0.0.1" (rontolisp:tcp-local-port *rns-listener*)))
			(print (handler-case (funcall #'rontolisp:tls-upgrade *rns-sock* "h" :verify t)
			         (error (c) (princ-to-string c))))
			(mapc #'rontolisp:tcp-set-timeout (list *rns-sock*) (list 5000))
			(print :timeout-set)
			(close *rns-sock*)
			(close *rns-listener*)
			""";

	/** What {@link #TLS_PROGRAM} prints. */
	public static final String TLS_EXPECTED = String.join("\n", ":REFUSED", ":REFUSED",
			"\"TLS-CONNECT expects :insecure, got: :VERIFY\"", "\"TLS-UPGRADE expects :insecure, got: :VERIFY\"",
			":TIMEOUT-SET");

}
