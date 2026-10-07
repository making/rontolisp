package am.ik.rontolisp;

/**
 * Where a compiled fast path signals, against the interpreter, which evaluates every
 * argument of an operation and then applies it: a wrong-typed operand signals after the
 * operation's later arguments ran, an inner operation signals before its outer one's
 * later arguments run, and nothing evaluated after an application may see it undone. The
 * unboxed float paths, the two-argument {@code log} and integer fusion each evaluated in
 * an order of their own. Shared by the backend suites, so every backend is held to one
 * expected text; {@code ci-spec.yaml}'s {@code fast-paths-keep-the-evaluation-order} pins
 * the same calls on the native binary.
 */
public final class FastPathEvaluationOrderFixture {

	private FastPathEvaluationOrderFixture() {
	}

	/**
	 * A program that cannot observe a complex, under handlers (WASM compiles it in EH
	 * mode): each line prints what ran before the report, then the report.
	 */
	public static final String SIGNALS_SOURCE = """
			(defvar *eo-a* 'a)
			(defvar *eo-b* 'b)
			(defvar *eo-v* (make-array 3 :initial-contents '(1 2 3)))
			(defvar *eo-u8* (make-array 3 :element-type '(unsigned-byte 8) :initial-contents '(1 2 3)))
			(defvar *eo-zero* 0)
			(defvar *eo-ten* 10)
			(defmacro eo-report (form)
			  `(handler-case (format t "~a~%" ,form)
			     (error (e) (format t "~a~%" e))))
			(defun eo-mark (s v) (princ s) v)
			(defun eo-dbl (x) (* 2 x))
			(defun eo-stmt (a) (+ a (eo-mark "z " 1.5)) :done)
			(defun eo-let (a) (let ((v (+ a (eo-mark "z " 1.5)))) v))
			(defun eo-fmt (a) (format nil "~a" (+ a (eo-mark "z " 1.5))))
			(defun eo-raw-store (a) (let ((s 0)) (dotimes (k 1) (setq s (+ (* 2 a) (eo-mark "s " 1)))) s))
			(defun eo-raw-leaf (a) (let ((s 0)) (setq s (+ s 1)) (setq s a) (+ (* 2 s) (eo-mark "x " 1))))
			(defun eo-cond (a) (if (< (* 2 a) (eo-mark "c " 1)) :yes :no))
			(defun eo-f64-exit (a) (block nil (+ a (return 7) 1.5)))
			(defun eo-fx-exit (a) (block nil (+ (* 2 a) (return 5))))
			(defun eo-cmp-exit (a) (block nil (< (* 2 a) (return :exited))))
			(defun eo-pair (v i j) (logior (ash (aref v i) 8) (aref v (+ j 1))))
			(eo-report (+ *eo-a* (eo-mark "z " 1.5)))
			(eo-report (eo-stmt *eo-a*))
			(eo-report (eo-let *eo-a*))
			(eo-report (eo-fmt *eo-a*))
			(eo-report (/ *eo-a* (eo-mark "z " 1.5)))
			(eo-report (+ 1.5 *eo-a* (eo-mark "z " 2)))
			(eo-report (+ (* 2.0 *eo-a*) (eo-mark "y " 1.5)))
			(eo-report (+ (eo-mark "w " 1.5) (* 2.0 *eo-a*)))
			(eo-report (+ *eo-a* (* 2.0 *eo-b*)))
			(eo-report (+ *eo-a* (* 2.0 *eo-b*) (eo-mark "z " 1.5)))
			(eo-report (mod *eo-a* (eo-mark "z " 1.5)))
			(eo-report (< (* 2.0 *eo-a*) (+ 1.0 (eo-mark "z " 1.5))))
			(eo-report (< (* 2.0 *eo-a*) 1.0))
			(eo-report (sin (+ *eo-a* (eo-mark "z " 1.5))))
			(eo-report (exp (* 2.0 *eo-a*)))
			(eo-report (atan *eo-a* (eo-mark "z " 1.0)))
			(eo-report (atan (* 2.0 *eo-a*) 1.0))
			(eo-report (abs (* 2.0 *eo-a*)))
			(eo-report (expt (* 2.0 *eo-a*) 2))
			(eo-report (- (* 2.0 *eo-a*)))
			(eo-report (/ (* 2.0 *eo-a*)))
			(eo-report (float (+ *eo-a* (eo-mark "z " 1.5))))
			(eo-report (truncate (+ *eo-a* (eo-mark "z " 1.5))))
			(eo-report (+ (* 2.0 (eo-mark "p " *eo-a*)) (eo-mark "q " 1.5)))
			(eo-report (eo-f64-exit *eo-a*))
			(eo-report (+ (* 2 *eo-a*) (eo-mark "x " 1)))
			(eo-report (* 2 (+ 1 *eo-a*) (eo-mark "u " 3)))
			(eo-report (+ (mod 5 *eo-zero*) (eo-mark "m " 1)))
			(eo-report (+ (rem 5 *eo-zero*) (eo-mark "m " 1)))
			(eo-report (+ (mod 7 (- *eo-ten* 10)) (eo-mark "m " 1)))
			(eo-report (+ (aref *eo-v* *eo-ten*) (eo-mark "r " 1)))
			(eo-report (+ (aref *eo-u8* *eo-ten*) (eo-mark "r " 1)))
			(eo-report (+ (random *eo-zero*) (eo-mark "q " 1)))
			(eo-report (< (* 2 *eo-a*) (eo-mark "c " 1)))
			(eo-report (eo-cond *eo-a*))
			(eo-report (eo-raw-store *eo-a*))
			(eo-report (eo-raw-leaf *eo-a*))
			(eo-report (logand (+ *eo-a* 1) (eo-mark "l " 255)))
			(eo-report (+ (1+ *eo-a*) (eo-mark "i " 1)))
			(eo-report (+ (eo-dbl *eo-a*) (eo-mark "d " 1)))
			(eo-report (+ (* 2 *eo-a*) (* 3 (eo-mark "x " 1))))
			(eo-report (+ (eo-mark "w " 1) (* 2 *eo-a*)))
			(eo-report (+ (* 2 *eo-a*) (* 3 *eo-a*) (eo-mark "x " 1)))
			(eo-report (+ (* 2 1) (eo-mark "x " 1) (* 3 *eo-b*) (eo-mark "y " 2)))
			(eo-report (+ (* (+ *eo-a* 1) (eo-mark "x " 2)) (eo-mark "y " 3)))
			(eo-report (logxor (ash *eo-a* 2) (eo-mark "h " 7)))
			(eo-report (+ (ash 1 (- *eo-a* 20)) (eo-mark "h " 1)))
			(eo-report (- (+ *eo-a* 1) (+ (eo-mark "x " 2) 3)))
			(eo-report (+ (* 2 (eo-mark "p " *eo-a*)) (eo-mark "q " 1)))
			(eo-report (eo-fx-exit *eo-a*))
			(eo-report (eo-cmp-exit *eo-a*))
			(eo-report (+ (* 2 *eo-a*) (car *eo-ten*)))
			(eo-report (+ (aref *eo-v* *eo-ten*) (car *eo-ten*)))
			(eo-report (eo-pair *eo-u8* 10 *eo-b*))
			(eo-report (eo-pair *eo-u8* 10 0))
			(eo-report (logior (ash (aref *eo-u8* 1) 8) (aref *eo-u8* (+ *eo-ten* 1))))""";

	/** What every backend prints for {@link #SIGNALS_SOURCE}. */
	public static final String SIGNALS_EXPECTED = """
			z +: The value A is not of type NUMBER
			z +: The value A is not of type NUMBER
			z +: The value A is not of type NUMBER
			z +: The value A is not of type NUMBER
			z /: The value A is not of type NUMBER
			z +: The value A is not of type NUMBER
			*: The value A is not of type NUMBER
			w *: The value A is not of type NUMBER
			*: The value B is not of type NUMBER
			*: The value B is not of type NUMBER
			z MOD: The value A is not of type REAL
			*: The value A is not of type NUMBER
			*: The value A is not of type NUMBER
			z +: The value A is not of type NUMBER
			*: The value A is not of type NUMBER
			z ATAN: The value A is not of type NUMBER
			*: The value A is not of type NUMBER
			*: The value A is not of type NUMBER
			*: The value A is not of type NUMBER
			*: The value A is not of type NUMBER
			*: The value A is not of type NUMBER
			z +: The value A is not of type NUMBER
			z +: The value A is not of type NUMBER
			p *: The value A is not of type NUMBER
			7
			*: The value A is not of type NUMBER
			+: The value A is not of type NUMBER
			Division by zero
			Division by zero
			Division by zero
			AREF: The value 10 is not of type (INTEGER 0 (3))
			AREF: The value 10 is not of type (INTEGER 0 (3))
			RANDOM: The value 0 is not of type REAL
			*: The value A is not of type NUMBER
			*: The value A is not of type NUMBER
			*: The value A is not of type NUMBER
			*: The value A is not of type NUMBER
			+: The value A is not of type NUMBER
			+: The value A is not of type NUMBER
			*: The value A is not of type NUMBER
			*: The value A is not of type NUMBER
			w *: The value A is not of type NUMBER
			*: The value A is not of type NUMBER
			x *: The value B is not of type NUMBER
			+: The value A is not of type NUMBER
			ASH: The value A is not of type INTEGER
			-: The value A is not of type NUMBER
			+: The value A is not of type NUMBER
			p *: The value A is not of type NUMBER
			*: The value A is not of type NUMBER
			*: The value A is not of type NUMBER
			*: The value A is not of type NUMBER
			AREF: The value 10 is not of type (INTEGER 0 (3))
			AREF: The value 10 is not of type (INTEGER 0 (3))
			AREF: The value 10 is not of type (INTEGER 0 (3))
			AREF: The value 11 is not of type (INTEGER 0 (3))""";

	/**
	 * The answers, with no handler anywhere (WASM compiles it outside EH mode): an aref
	 * read sees the array as it stood before a later operand stored into it, an exit
	 * leaves before an operation it skips can signal, and every guard a fused tree runs
	 * between its leaves answers through its fallback when a leaf is no fixnum.
	 */
	public static final String VALUES_SOURCE = """
			(defvar *ev-a* 'a)
			(defvar *ev-v* (make-array 3 :initial-contents '(1 2 3)))
			(defvar *ev-u8* (make-array 3 :element-type '(unsigned-byte 8) :initial-contents '(1 2 3)))
			(defvar *ev-three* 3)
			(defun ev-id (x) x)
			(defun ev-f64-exit (a) (block nil (+ a (return 7) 1.5)))
			(defun ev-aref-after (v) (+ (aref v 0) (progn (setf (aref v 0) 99) 1)))
			(defun ev-aref-shift (v) (+ (* 2 (aref v 1)) (progn (setf (aref v 1) 50) 1)))
			(defun ev-aref-mask (v) (logand (+ (aref v 2) (progn (setf (aref v 2) 7) 0)) 255))
			(defun ev-mix (x) (+ (* 2 x) (ev-id 1)))
			(defun ev-f64-mix (x) (+ x (ev-id 2) 0.5))
			(defun ev-octets (v i) (logior (ash (logand (aref v (+ i 1)) 63) 6) (logand (aref v (+ i 2)) 63)))
			(defun ev-loop (n) (let ((s 0)) (dotimes (k n) (setq s (+ s (* 2 k) (ev-id 1)))) s))
			(print (ev-f64-exit *ev-a*))
			(print (ev-aref-after *ev-v*))
			(print (ev-aref-after *ev-u8*))
			(print (ev-aref-shift *ev-v*))
			(print (ev-aref-shift *ev-u8*))
			(print (ev-aref-mask *ev-v*))
			(print (ev-aref-mask *ev-u8*))
			(print (list *ev-v* *ev-u8*))
			(print (list (ev-mix 3) (ev-mix 1.5) (ev-mix (expt 2 70)) (ev-mix 1/2)))
			(print (list (ev-f64-mix 1) (ev-f64-mix 1/4) (ev-f64-mix (expt 2 70))))
			(print (ev-octets (make-array 4 :element-type '(unsigned-byte 8) :initial-contents '(224 162 130 0)) 0))
			(print (ev-octets (vector 224 162 130 0) 0))
			(print (ev-loop 4))
			(print (list (+ (random 1) (ev-id 1)) (+ (mod 7 *ev-three*) (ev-id 1)) (+ (ash 1 *ev-three*) (ev-id 1))))""";

	/** What every backend prints for {@link #VALUES_SOURCE}. */
	public static final String VALUES_EXPECTED = """
			7
			2
			2
			5
			5
			3
			3
			(#(99 50 7) #(99 50 7))
			(7 4.0 2361183241434822606849 2)
			(3.5 2.75 1.1805916207174113e21)
			2178
			2178
			16
			(1 2 9)""";

	/**
	 * A program that may observe a complex: the two-argument {@code log} evaluates its
	 * base before the number's logarithm can signal, beside the float and fused shapes.
	 */
	public static final String COMPLEX_SOURCE = """
			(defvar *ec-a* 'a)
			(defvar *ec-z* (sqrt -4))
			(defvar *ec-zero* 0)
			(defmacro ec-report (form)
			  `(handler-case (format t "~a~%" ,form)
			     (error (e) (format t "~a~%" e))))
			(defun ec-mark (s v) (princ s) v)
			(ec-report (log *ec-a* (ec-mark "z " 2.0)))
			(ec-report (log (ec-mark "n " 8) (ec-mark "b " *ec-a*)))
			(ec-report (log 8 (ec-mark "b " 2)))
			(ec-report (+ *ec-a* (ec-mark "z " 1.5)))
			(ec-report (+ (* 2.0 *ec-a*) (ec-mark "y " 1.5)))
			(ec-report (+ (* 2 *ec-a*) (ec-mark "x " 1)))
			(ec-report (+ (mod 5 *ec-zero*) (ec-mark "m " 1)))
			(ec-report (< (* 2 *ec-a*) (ec-mark "c " 1)))
			(ec-report (+ (* 2.0 *ec-z*) (ec-mark "w " 1.5)))
			(ec-report (* 2 (+ 1 *ec-a*) (ec-mark "u " 3)))""";

	/** What every backend prints for {@link #COMPLEX_SOURCE}. */
	public static final String COMPLEX_EXPECTED = """
			z LOG: The value A is not of type NUMBER
			n b LOG: The value A is not of type NUMBER
			b 3.0
			z +: The value A is not of type NUMBER
			*: The value A is not of type NUMBER
			*: The value A is not of type NUMBER
			Division by zero
			*: The value A is not of type NUMBER
			w #C(1.5 4.0)
			+: The value A is not of type NUMBER""";

}
