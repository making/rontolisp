package am.ik.rontolisp;

/**
 * What a stream's line start answers where ci-spec cannot look ({@code
 * .kb/standard-output-redirect.md}, "A stream's line start"), SBCL 2.2.9's answers,
 * shared by the backend suites.
 * <ul>
 * <li>{@link #ERROR_OUTPUT_PROGRAM}: standard error's text, a blank line included, where
 * the driver compares standard output and a standalone case each line of standard error
 * on its own.</li>
 * <li>{@link #SOCKET_PROGRAM}: {@code fresh-line} on a loopback connection, on every
 * backend with sockets. An octet moves no line.</li>
 * <li>{@link #SOCKET_FORMAT_PROGRAM}: an opening {@code ~&} there, on the interpreter and
 * the JVM: {@code format} to a socket is refused on the component
 * ({@code .kb/tcp-sockets.md}).</li>
 * </ul>
 */
public final class LineStartFixture {

	private LineStartFixture() {
	}

	/** Writes to standard error around the operators that ask its line start. */
	public static final String ERROR_OUTPUT_PROGRAM = """
			(progn
			  (write-line "0" *error-output*)
			  (fresh-line *error-output*)
			  (format *error-output* "~&a~%")
			  (format *error-output* "~&b")
			  (format *error-output* "~&c~%")
			  (write-string "d" *error-output*)
			  (write-line "e" *error-output*)
			  (fresh-line *error-output*)
			  (write-string "f" *error-output*)
			  (fresh-line *error-output*)
			  (fresh-line *error-output*)
			  (write-string "g" *error-output*)
			  (funcall #'fresh-line *error-output*)
			  (let ((control "~&h~%")) (format *error-output* control))
			  (funcall #'format *error-output* "~&i~%")
			  (force-output *error-output*))""";

	/** What {@link #ERROR_OUTPUT_PROGRAM} writes, its last newline left out. */
	public static final String ERROR_OUTPUT_EXPECTED = "0\na\nb\nc\nde\nf\ng\nh\ni";

	/** {@code fresh-line} over a loopback connection. */
	public static final String SOCKET_PROGRAM = """
			(let* ((listener (rontolisp:tcp-listen 0 "127.0.0.1"))
			       (client (rontolisp:tcp-connect "127.0.0.1" (rontolisp:tcp-local-port listener)))
			       (server (rontolisp:tcp-accept listener)))
			  (print (fresh-line client))
			  (write-string "a" client)
			  (print (list (fresh-line client) (fresh-line client)))
			  (write-char #\\b client)
			  (print (fresh-line client))
			  (write-line "c" client)
			  (print (fresh-line client))
			  (write-string "d" client)
			  (terpri client)
			  (print (fresh-line client))
			  (write-byte 101 client)
			  (print (fresh-line client))
			  (write-byte 10 client)
			  (write-string "f" client)
			  (write-byte 10 client)
			  (print (fresh-line client))
			  (write-sequence "g" client)
			  (print (fresh-line client))
			  (close client)
			  (let ((lines nil) (line nil))
			    (loop
			      (setq line (read-line server nil nil))
			      (if line (setq lines (cons line lines)) (return)))
			    (print (reverse lines)))
			  (close server)
			  (close listener))
			""";

	/** What {@link #SOCKET_PROGRAM} prints. */
	public static final String SOCKET_EXPECTED = String.join("\n", "NIL", "(T NIL)", "T", "NIL", "NIL", "NIL", "T", "T",
			"(\"a\" \"b\" \"c\" \"d\" \"e\" \"f\" \"\" \"g\")");

	/** An opening {@code ~&} over a loopback connection. */
	public static final String SOCKET_FORMAT_PROGRAM = """
			(let* ((listener (rontolisp:tcp-listen 0 "127.0.0.1"))
			       (client (rontolisp:tcp-connect "127.0.0.1" (rontolisp:tcp-local-port listener)))
			       (server (rontolisp:tcp-accept listener)))
			  (format client "~&a~%")
			  (format client "b")
			  (format client "~&c~%")
			  (let ((control "~&d")) (format client control) (format client control))
			  (funcall #'format client "~&e~%")
			  (close client)
			  (print (list (read-line server) (read-line server) (read-line server) (read-line server)
			               (read-line server) (read-line server)))
			  (close server)
			  (close listener))
			""";

	/** What {@link #SOCKET_FORMAT_PROGRAM} prints. */
	public static final String SOCKET_FORMAT_EXPECTED = "(\"a\" \"b\" \"c\" \"d\" \"d\" \"e\")";

}
