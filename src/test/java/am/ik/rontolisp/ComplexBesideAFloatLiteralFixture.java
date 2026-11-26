package am.ik.rontolisp;

/**
 * A complex reaching an operation that spells a float literal: {@code (* 2.0 z)},
 * {@code (exp (* 1.0 z))}, {@code (= (* 2.0 z) 1.0)}. The literal routes such a form onto
 * the compiled backends' unboxed float path, so the complex its variable holds has to be
 * found there at run time, where {@link ComplexThroughAVariableFixture}'s helpers never
 * see it. Shared by the backend suites, so every backend is held to one expected text;
 * {@code ci-spec.yaml}'s {@code complex-beside-a-float-literal} pins the same calls on
 * the native binary.
 */
public final class ComplexBesideAFloatLiteralFixture {

	private ComplexBesideAFloatLiteralFixture() {
	}

	/** The answers, with no handler anywhere (WASM compiles it outside EH mode). */
	public static final String SOURCE = """
			(defvar *cbf-z* (complex 1 2))
			(defvar *cbf-w* (complex 1.5 -2.0))
			(defvar *cbf-q* (complex 1/2 3))
			(defvar *cbf-n* (complex 0.0 -0.0))
			(defvar *cbf-r* 3)
			(defvar *cbf-d* -2.5)
			(defun cbf-id (x) x)
			(defun cbf-scale (a) (* 2.0 a))
			(defun cbf-shift (a) (+ a 1.5))
			(defun cbf-poly (a) (+ (* 0.5 a a) (* -2.0 a) 1.0))
			(print (+ *cbf-z* 1.5))
			(print (+ 1.5 *cbf-z*))
			(print (* 2.0 *cbf-z*))
			(print (* *cbf-z* 2.0))
			(print (- *cbf-z* 0.5))
			(print (- 0.5 *cbf-z*))
			(print (/ *cbf-z* 2.0))
			(print (/ 2.0 *cbf-z*))
			(print (/ (* 1.0 *cbf-z*)))
			(print (- (* 1.0 *cbf-w*)))
			(print (- (* 1.0 *cbf-n*)))
			(print (* *cbf-n* -1.0))
			(print (+ *cbf-z* 1.5 *cbf-w*))
			(print (* 2 *cbf-z* 1.5))
			(print (* 1.5 *cbf-q*))
			(print (cbf-scale *cbf-q*))
			(print (cbf-shift *cbf-w*))
			(print (cbf-poly *cbf-z*))
			(print (+ (* 0.5 *cbf-r*) (* 2.0 *cbf-z*)))
			(print (* 2.0 (+ *cbf-z* 1)))
			(print (+ (cbf-id *cbf-z*) 0.25))
			(print (+ (* 2.0 *cbf-z*) (- (* 2.0 *cbf-z*)) 1.5))
			(print (1+ (* 1.0 *cbf-z*)))
			(print (let ((x *cbf-z*)) (setq x (* x 0.5)) (incf x 0.25) x))
			(print (exp (* 1.0 *cbf-z*)))
			(print (sin (+ *cbf-z* 0.5)))
			(print (abs (* 2.0 *cbf-z*)))
			(print (abs (+ *cbf-w* 1.5)))
			(print (sqrt (* 2.0 *cbf-z*)))
			(print (log (* 1.0 *cbf-z*)))
			(print (expt 2.0 *cbf-z*))
			(print (expt (* 1.0 *cbf-z*) 0.5))
			(print (signum (* 2.0 *cbf-z*)))
			(print (= (* 2.0 *cbf-z*) 1.0))
			(print (= (* 2.0 *cbf-z*) (* 2 *cbf-z*)))
			(print (= 2.5 (+ *cbf-w* 1.0)))
			(print (zerop (* 0.0 *cbf-z*)))
			(print (funcall (lambda (a) (* a 0.5)) *cbf-z*))
			(print (mapcar (lambda (a) (+ a 0.5)) (list *cbf-z* 1 *cbf-w*)))
			(princ (* 2.0 *cbf-w*))
			(terpri)
			(print (list (+ *cbf-r* 1.5) (* 2.0 *cbf-d*) (exp (* 1.0 *cbf-r*)) (abs (* 2.0 *cbf-d*))
			             (= (* 2.0 *cbf-r*) 6.0) (< (* 2.0 *cbf-d*) 1.0) (max (* 2.0 *cbf-r*) 1.0)
			             (expt 2.0 *cbf-r*) (signum (* 2.0 *cbf-d*)) (- (* 1.0 *cbf-d*))))""";

	/** What every backend prints for {@link #SOURCE}. */
	public static final String EXPECTED = """
			#C(2.5 2.0)
			#C(2.5 2.0)
			#C(2.0 4.0)
			#C(2.0 4.0)
			#C(0.5 2.0)
			#C(-0.5 -2.0)
			#C(0.5 1.0)
			#C(0.4 -0.8)
			#C(0.2 -0.4)
			#C(-1.5 2.0)
			#C(-0.0 0.0)
			#C(-0.0 0.0)
			#C(4.0 0.0)
			#C(3.0 6.0)
			#C(0.75 4.5)
			#C(1.0 6.0)
			#C(3.0 -2.0)
			#C(-2.5 -2.0)
			#C(3.5 4.0)
			#C(4.0 4.0)
			#C(1.25 2.0)
			#C(1.5 0.0)
			#C(2.0 2.0)
			#C(0.75 1.0)
			#C(-1.1312043837568138 2.471726672004819)
			#C(3.752771340479298 0.2565539560904818)
			4.47213595499958
			3.605551275463989
			#C(1.7989074399478673 1.1117859405028423)
			#C(0.8047189562170503 1.1071487177940904)
			#C(0.36691394948660344 1.9660554808224875)
			#C(1.272019649514069 0.7861513777574233)
			#C(0.4472135954999579 0.8944271909999159)
			NIL
			T
			NIL
			T
			#C(0.5 1.0)
			(#C(1.5 2.0) 1.5 #C(2.0 -2.0))
			#C(3.0 -4.0)
			(4.5 -5.0 20.085536923187668 5.0 T T 6.0 8.0 -1.0 2.5)""";

	/**
	 * What such an operation must still REFUSE, and in which order it evaluates, under
	 * handlers (WASM compiles it in EH mode): an operator that wants a real reports the
	 * complex the inner operation computed -- a literal one's too -- a non-number is
	 * reported by the operation it is an operand of, and every operand of an operation is
	 * evaluated before the operation signals, but an inner operation signals before its
	 * outer one's later operands run.
	 */
	public static final String SIGNALS_SOURCE = """
			(defvar *cbs-z* (complex 1 2))
			(defvar *cbs-a* 'a)
			(defmacro cbs-report (form)
			  `(handler-case (format t "~a~%" ,form)
			     (type-error (e) (format t "~a / ~a~%" e (type-error-expected-type e)))))
			(cbs-report (* 1.5 *cbs-z*))
			(cbs-report (< (* 2.0 *cbs-z*) 1.0))
			(cbs-report (>= 1.0 (* 2.0 *cbs-z*)))
			(cbs-report (max (* 2.0 *cbs-z*) 1.0))
			(cbs-report (min 1.0 (* 2.0 *cbs-z*)))
			(cbs-report (max (+ #c(1 2) 0.5) 1.0))
			(cbs-report (atan (* 2.0 *cbs-z*) 1.0))
			(cbs-report (mod (* 2.0 *cbs-z*) 1.5))
			(cbs-report (floor (* 2.0 *cbs-z*)))
			(cbs-report (random (* 2.0 *cbs-z*)))
			(cbs-report (+ (* 2.0 *cbs-z*) *cbs-a*))
			(cbs-report (+ (* 2.0 *cbs-a*) 1.5))
			(cbs-report (+ *cbs-a* (progn (princ "z ") 1.5)))
			(cbs-report (+ (* 2.0 *cbs-a*) (progn (princ "y ") 1.5)))
			(cbs-report (* 2.0 (progn (princ "x ") *cbs-z*) (progn (princ "w ") 1)))""";

	/** What every backend prints for {@link #SIGNALS_SOURCE}. */
	public static final String SIGNALS_EXPECTED = """
			#C(1.5 3.0)
			<: The value #C(2.0 4.0) is not of type REAL / REAL
			>=: The value #C(2.0 4.0) is not of type REAL / REAL
			MAX: The value #C(2.0 4.0) is not of type REAL / REAL
			MIN: The value #C(2.0 4.0) is not of type REAL / REAL
			MAX: The value #C(1.5 2.0) is not of type REAL / REAL
			ATAN: The value #C(2.0 4.0) is not of type REAL / REAL
			MOD: The value #C(2.0 4.0) is not of type REAL / REAL
			FLOOR: The value #C(2.0 4.0) is not of type REAL / REAL
			RANDOM: The value #C(2.0 4.0) is not of type REAL / REAL
			+: The value A is not of type NUMBER / NUMBER
			*: The value A is not of type NUMBER / NUMBER
			z +: The value A is not of type NUMBER / NUMBER
			*: The value A is not of type NUMBER / NUMBER
			x w #C(2.0 4.0)""";

}
