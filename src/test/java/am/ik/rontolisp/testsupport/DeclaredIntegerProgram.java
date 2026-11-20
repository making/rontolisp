package am.ik.rontolisp.testsupport;

/**
 * The declared-integer program pinned identically on the JVM and both WASM backends
 * ({@code .kb/declarations-type-checks.md}, "Declared integer ranges").
 */
public final class DeclaredIntegerProgram {

	private DeclaredIntegerProgram() {
	}

	/**
	 * Step32's parameters through a deftype, hash32's locals, lanes' long and its shift
	 * count, digit's (unsigned-byte 16) and a conjunction with fixnum are trusted; a
	 * fixnum alone proves no bound, so loose doubles a bignum. Java's int and long
	 * arithmetic, which is what the front end lowers to. A handler-case clause's variable
	 * is no outer binding of its name: not hc's raw e, not its celled k, not nd's
	 * declared n. The values are the interpreter's, and a JDK's for the hash and the
	 * shifts.
	 */
	public static final String PROGRAM = """
			(deftype int32 () '(signed-byte 32))
			(defun step32 (h c)
			  (declare (type int32 h c))
			  (%mask-signed-field 32 (+ (* 31 h) c)))
			(defun hash32 (s)
			  (let ((h 0) (i 0))
			    (declare (type (signed-byte 32) h i))
			    (loop while (< i (length s))
			          do (setq h (step32 h (char-code (char s i))))
			             (setq i (1+ i)))
			    h))
			(defun lanes (x k)
			  (declare (type (signed-byte 64) x) (type (integer 0 63) k))
			  (let ((y (%mask-signed-field 64 (* x 6364136223846793005))))
			    (declare (type (signed-byte 64) y))
			    (list y (ash y (- k)) (%mask-signed-field 64 (ash y k)) (logand y (lognot (ash 1 k))) (< y x))))
			(defun digit (c)
			  (declare (type (unsigned-byte 16) c))
			  (let ((d (- c 48)))
			    (declare (type (and fixnum (integer -48 65487)) d))
			    (if (< d 10) d (- d 7))))
			(defun loose (n)
			  (declare (type fixnum n))
			  (+ n n))
			(defun hc (n)
			  (let ((e 0) (k n))
			    (dotimes (i n) (setq e (logand (+ e i) 255)))
			    (let ((f (lambda () k)))
			      (list (handler-case (error "boom") (error (e) (princ-to-string e)))
			            (handler-case (list e) (:no-error (e) e))
			            (handler-case (error "cell") (error (k) (list (princ-to-string k) (funcall f))))))))
			(defun nd (n)
			  (declare (type (integer 0 100) n))
			  (handler-case (expt 2 n) (:no-error (n) (+ n 1))))
			(print (hash32 "the quick brown fox jumps over the lazy dog"))
			(print (lanes 9223372036854775807 3))
			(print (lanes -9223372036854775808 63))
			(print (mapcar #'digit '(48 57 65 70)))
			(print (loose (expt 2 70)))
			(print (hc 5))
			(print (nd 70))
			""";

	/** What {@link #PROGRAM} prints. */
	public static final String EXPECTED = """
			-2082818701
			(2859235813007982803 357404476625997850 4427142430354310808 2859235813007982803 T)
			(-9223372036854775808 -1 0 -18446744073709551616 NIL)
			(0 9 10 15)
			2361183241434822606848
			("boom" (10) ("cell" 5))
			1180591620717411303425""";

}
