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
	 * declared n. Java's quotients: an int's, a long's under its mask and the exact one,
	 * each over MIN_VALUE / -1, and a zero divisor; qf's undeclared dividend takes the
	 * generic quotient. Rho's locals are a Keccak step's: c and d bound once, a, b and i
	 * reassigned, every tree over them alone. Sched is a message schedule over the arrays
	 * Java declares, its long[] read and stored as an int[] and a byte[] are; put64's
	 * last store finds the array widened by the one before (Long.MIN_VALUE is its packed
	 * shape's nil). The values are the interpreter's, and a JDK's for the hash, the
	 * shifts, the masked quotients, rho and sched.
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
			(defun quot (a b c)
			  (declare (type (signed-byte 32) a b) (type (signed-byte 64) c))
			  (list (%mask-signed-field 32 (the (integer -2147483648 2147483648) (truncate a b)))
			        (%mask-signed-field 64 (truncate c b)) (truncate c b) (+ 1 (truncate c 7))))
			(defun qf (x) (+ 6 (truncate x 2)))
			(defun rho (lanes rounds)
			  (declare (type (signed-byte 32) rounds))
			  (let ((a (first lanes)) (b (second lanes)) (n rounds))
			    (declare (type (signed-byte 64) a b) (type (signed-byte 32) n))
			    (let ((i 0))
			      (declare (type (signed-byte 32) i))
			      (loop while (< i n)
			            do (let ((c (logxor a b)))
			                 (declare (type (signed-byte 64) c))
			                 (let ((d (logior (%mask-signed-field 64 (ash c 1)) (logand (ash c -63) 1))))
			                   (declare (type (signed-byte 64) d))
			                   (setq a (logxor d (logand (lognot c) b)))
			                   (setq b (%mask-signed-field 64 (+ b d)))))
			               (setq i (%mask-signed-field 32 (+ i 1)))))
			    (list a b (< a b))))
			(defun sched (w data k n)
			  (declare (type (or null (simple-array (signed-byte 64) (*))) w)
			           (type (or null (simple-array (unsigned-byte 8) (*))) data)
			           (type (or null (simple-array (unsigned-byte 32) (*))) k) (type (signed-byte 32) n))
			  (let ((i 0))
			    (declare (type (signed-byte 32) i))
			    (loop while (< i 4)
			          do (setf (aref w i) (logior (%mask-signed-field 64 (ash (%mask-signed-field 8 (aref data (* 2 i))) 56))
			                                      (logand (%mask-signed-field 8 (aref data (+ (* 2 i) 1))) 255)))
			             (setq i (%mask-signed-field 32 (+ i 1))))
			    (loop while (< i n)
			          do (setf (aref w i) (%mask-signed-field 64 (+ (the (signed-byte 64) (aref w (- i 4)))
			                                                         (* (the (signed-byte 64) (aref w (- i 1)))
			                                                            6364136223846793005))))
			             (setf (aref k (- i 4)) (%mask-signed-field 32 (the (signed-byte 64) (aref w i))))
			             (setq i (%mask-signed-field 32 (+ i 1))))
			    (list w k)))
			(defun put64 (w i x)
			  (declare (type (simple-array (signed-byte 64) (*)) w) (type (signed-byte 32) i) (type (signed-byte 64) x))
			  (setf (aref w i) x)
			  (+ (the (signed-byte 64) (aref w i)) 1))
			(print (hash32 "the quick brown fox jumps over the lazy dog"))
			(print (lanes 9223372036854775807 3))
			(print (lanes -9223372036854775808 63))
			(print (mapcar #'digit '(48 57 65 70)))
			(print (loose (expt 2 70)))
			(print (hc 5))
			(print (nd 70))
			(print (quot -2147483648 -1 -9223372036854775808))
			(print (quot 7 -2 -7))
			(print (handler-case (quot 1 0 1) (division-by-zero () :zero)))
			(print (list (qf 7.5) (qf 7/2) (qf (expt 2 70))))
			(print (rho (list 1 2) 0))
			(print (rho (list 6364136223846793005 -9223372036854775807) 24))
			(print (sched (make-array 8 :initial-element 0)
			              (make-array 8 :element-type '(unsigned-byte 8) :initial-contents '(1 255 128 7 0 0 254 9))
			              (make-array 4 :element-type '(unsigned-byte 32)) 8))
			(print (let ((w (make-array 2 :initial-element 0)))
			         (list (put64 w 0 9223372036854775806) (put64 w 1 -9223372036854775808) (put64 w 0 3) w)))
			""";

	/** What {@link #PROGRAM} prints. */
	public static final String EXPECTED = """
			-2082818701
			(2859235813007982803 357404476625997850 4427142430354310808 2859235813007982803 T)
			(-9223372036854775808 -1 0 -18446744073709551616 NIL)
			(0 9 10 15)
			2361183241434822606848
			("boom" (10) ("cell" 5))
			1180591620717411303425
			(-2147483648 -9223372036854775808 9223372036854775808 -1317624576693539400)
			(-3 3 3 0)
			:ZERO
			(9 7 590295810358705651718)
			(1 2 T)
			(-4678252771848532188 -4437309255459745240 T)
			(#(72057594037928191 -9223372036854775801 0 -144115188075855863 -4476132075883103852 9222974912752700171 4872450367180449263 -380189227759289332) #(2973858196 1459014411 696460783 1677343756))
			(9223372036854775807 -9223372036854775807 4 #(3 -9223372036854775808))""";

}
