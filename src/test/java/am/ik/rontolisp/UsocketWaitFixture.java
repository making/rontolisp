package am.ik.rontolisp;

/**
 * What {@code listen}, {@code usocket:wait-for-input} and the {@code :receive-timeout}
 * read deadline answer over a loopback connection ({@code .kb/tcp-sockets.md}, "Read
 * deadlines"), on every backend with sockets: the interpreter, the JVM, the WASM
 * component and a {@code --native} executable. On a quiet socket {@code listen} answers
 * nil and {@code wait-for-input} WAITS its timeout, answering no socket and no time left;
 * the read past the deadline signals the interpreter's text; once the peer writes,
 * {@code wait-for-input} answers the socket with time to spare and {@code listen} t, nil
 * again once the line is read and at the peer's close.
 */
public final class UsocketWaitFixture {

	private UsocketWaitFixture() {
	}

	/** The loopback exchange. */
	public static final String PROGRAM = """
			(let* ((listener (usocket:socket-listen "127.0.0.1" 0))
			       (port (usocket:get-local-port listener))
			       (client (usocket:socket-connect "127.0.0.1" port)))
			  (setf (usocket:socket-option client :receive-timeout) 0.2)
			  (print (usocket:socket-option client :receive-timeout))
			  (let ((server (usocket:socket-accept listener))
			        (start (get-internal-real-time)))
			    (multiple-value-bind (ready remaining)
			        (usocket:wait-for-input (list client) :timeout 0.3 :ready-only t)
			      (print (list (listen client) ready remaining
			                   (>= (- (get-internal-real-time) start)
			                       (* 0.25 internal-time-units-per-second)))))
			    (print (handler-case (read-line client) (error (e) (princ-to-string e))))
			    (setf (usocket:socket-option client :receive-timeout) nil)
			    (print (list (usocket:socket-option client :receive-timeout)
			                 (handler-case (setf (usocket:socket-option client :tcp-nodelay) t)
			                   (error () :unsupported))))
			    (write-line "ping" server)
			    (multiple-value-bind (ready remaining)
			        (usocket:wait-for-input (list client) :timeout 5 :ready-only t)
			      (print (list (eql (car ready) client) (if remaining t nil) (listen client))))
			    (print (eql (usocket:wait-for-input client) client))
			    (print (list (read-line client) (listen client)))
			    (usocket:socket-close server)
			    (print (list (listen client) (read-line client nil :eof))))
			  (usocket:socket-close client)
			  (usocket:socket-close listener))
			""";

	/** What {@link #PROGRAM} prints. */
	public static final String EXPECTED = String.join("\n", "0.2", "(NIL NIL NIL T)", "\"read-line: Read timed out\"",
			"(NIL :UNSUPPORTED)", "(T T T)", "T", "(\"ping\" NIL)", "(NIL :EOF)");

}
