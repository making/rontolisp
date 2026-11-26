package am.ik.rontolisp;

/**
 * What a socket's reads of several octets answer over a loopback connection, on every
 * backend with sockets: the interpreter, the JVM, the WASM component and a
 * {@code --native} executable ({@code .kb/tcp-sockets.md}, "What has come, in one read").
 * A byte, then {@code rontolisp::%tcp-read-some} with no wait on a quiet socket (nothing
 * stored), then with a wait once three octets were sent (all three, in one read), then a
 * byte of three more, after which {@code listen} and a read with no wait answer the two
 * the socket's buffer holds and the host no longer does, then {@code read-sequence} of an
 * octet vector filled across the host's chunks up to the peer's close (a short read),
 * then the end.
 */
public final class TcpReadSomeFixture {

	private TcpReadSomeFixture() {
	}

	/** The loopback exchange. */
	public static final String PROGRAM = """
			(let* ((listener (rontolisp:tcp-listen 0 "127.0.0.1"))
			       (client (rontolisp:tcp-connect "127.0.0.1" (rontolisp:tcp-local-port listener)))
			       (server (rontolisp:tcp-accept listener))
			       (octets (make-array 20010 :element-type '(unsigned-byte 8)))
			       (sent (make-array 20000 :element-type '(unsigned-byte 8))))
			  (dotimes (i 20000) (setf (aref sent i) (mod (* i 7) 256)))
			  (write-byte 65 client)
			  (print (read-byte server))
			  (print (rontolisp::%tcp-read-some server octets 0 4 nil))
			  (write-sequence sent client :end 3)
			  (print (list (rontolisp::%tcp-read-some server octets 1 9 t)
			               (aref octets 1) (aref octets 2) (aref octets 3)))
			  (write-sequence sent client :start 1 :end 4)
			  (print (list (read-byte server) (listen server)
			               (rontolisp::%tcp-read-some server octets 0 9 nil)
			               (aref octets 0) (aref octets 1)))
			  (write-sequence sent client)
			  (close client)
			  (let ((filled (read-sequence octets server :start 3)) (same t))
			    (dotimes (i 20000) (unless (= (aref octets (+ i 3)) (aref sent i)) (setq same nil)))
			    (print (list filled same)))
			  (print (rontolisp::%tcp-read-some server octets 0 4 t))
			  (close server)
			  (close listener))
			""";

	/** What {@link #PROGRAM} prints. */
	public static final String EXPECTED = String.join("\n", "65", "0", "(4 0 7 14)", "(7 T 2 14 21)", "(20003 T)", "0");

}
