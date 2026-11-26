package am.ik.rontolisp;

/**
 * A complex reaching arithmetic through a variable -- a function parameter, a global, a
 * first-class designator's argument -- rather than a literal or a {@code complex} form in
 * the call itself. The compiled backends steer a SYNTACTIC complex onto their complex
 * helpers at compile time, so a call site like {@code (defun m (a b) (* a b))} has to
 * find the complex at run time, in the generic helpers its operands land in. Shared by
 * the backend suites, so every backend is held to one expected text;
 * {@code ci-spec.yaml}'s {@code complex-arithmetic-through-a-variable} pins the same
 * calls on the native binary.
 */
public final class ComplexThroughAVariableFixture {

	private ComplexThroughAVariableFixture() {
	}

	/** The answers, with no handler anywhere (WASM compiles it outside EH mode). */
	public static final String SOURCE = """
			(defvar *ctv-z* (complex 1 2))
			(defvar *ctv-w* (complex 1.5 -2.0))
			(defvar *ctv-q* (complex 1/2 3))
			(defun ctv-add (a b) (+ a b))
			(defun ctv-sub (a b) (- a b))
			(defun ctv-mul (a b) (* a b))
			(defun ctv-div (a b) (/ a b))
			(defun ctv-neg (a) (- a))
			(defun ctv-inv (a) (/ a))
			(defun ctv-num= (a b) (= a b))
			(defun ctv-num/= (a b) (/= a b))
			(defun ctv-abs (a) (abs a))
			(defun ctv-expt (a b) (expt a b))
			(print (ctv-mul (complex 1 1) 2))
			(print (ctv-mul *ctv-z* 2))
			(print (ctv-add *ctv-z* 3))
			(print (ctv-add 3 *ctv-z*))
			(print (ctv-add 1.5 *ctv-z*))
			(print (ctv-add *ctv-z* 1.5))
			(print (ctv-add *ctv-z* *ctv-w*))
			(print (ctv-add *ctv-q* 1/3))
			(print (ctv-add *ctv-z* (complex 0 -2)))
			(print (ctv-sub *ctv-z* 3))
			(print (ctv-sub 1.5 *ctv-z*))
			(print (ctv-mul *ctv-z* *ctv-z*))
			(print (ctv-mul 1.5 *ctv-z*))
			(print (ctv-mul *ctv-z* (conjugate *ctv-z*)))
			(print (ctv-div *ctv-z* 3))
			(print (ctv-div 3 *ctv-z*))
			(print (ctv-div 1.5 *ctv-z*))
			(print (ctv-div *ctv-z* *ctv-w*))
			(print (ctv-neg *ctv-z*))
			(print (ctv-neg *ctv-w*))
			(print (ctv-inv *ctv-z*))
			(print (+ *ctv-z* 1 *ctv-w*))
			(print (* 3 *ctv-z* *ctv-z*))
			(print (- *ctv-w* *ctv-z* 1))
			(print (1+ *ctv-z*))
			(print (1- *ctv-w*))
			(print (let ((x *ctv-z*)) (incf x 2) (decf x *ctv-q*) x))
			(print (ctv-num= *ctv-z* (complex 1 2)))
			(print (ctv-num= *ctv-z* 1))
			(print (ctv-num= *ctv-w* (complex 1.5 -2)))
			(print (ctv-num= 1.5 *ctv-w*))
			(print (ctv-num= (complex 0.0 0.0) 0))
			(print (ctv-num/= *ctv-z* *ctv-w*))
			(print (= *ctv-z* *ctv-z* (complex 1 2)))
			(print (zerop *ctv-z*))
			(print (equalp *ctv-z* (complex 1.0 2.0)))
			(print (ctv-abs *ctv-z*))
			(print (ctv-abs *ctv-q*))
			(print (ctv-abs *ctv-w*))
			(print (exp *ctv-z*))
			(print (sin *ctv-z*))
			(print (cos *ctv-w*))
			(print (tan *ctv-z*))
			(print (atan *ctv-q*))
			(print (sinh *ctv-z*))
			(print (cosh *ctv-w*))
			(print (tanh *ctv-z*))
			(print (ctv-expt *ctv-z* 3))
			(print (ctv-expt *ctv-z* -2))
			(print (ctv-expt *ctv-q* 2))
			(print (ctv-expt 2 *ctv-z*))
			(print (ctv-expt *ctv-z* *ctv-z*))
			(print (ctv-expt *ctv-z* 1.5))
			(print (ctv-expt *ctv-w* 2))
			(print (expt *ctv-z* 2))
			(print (funcall #'expt 2 (complex 1 1)))
			(print (funcall #'* 2 *ctv-z*))
			(print (funcall #'- *ctv-z*))
			(print (funcall #'exp *ctv-z*))
			(print (funcall #'abs *ctv-w*))
			(print (apply #'+ (list *ctv-z* 1 *ctv-w*)))
			(print (reduce #'* (list *ctv-z* *ctv-z* *ctv-z*)))
			(print (mapcar #'abs (list *ctv-z* -3)))
			(print (mapcar #'1+ (list *ctv-z* 1)))
			(print (list (ctv-add 1.5 2.25) (ctv-mul 2 3) (ctv-div 1 3) (ctv-sub 1/2 2) (ctv-neg 0.0)
			             (ctv-num= 1.0 1) (ctv-abs -2.5) (ctv-expt 2 10) (ctv-expt 2.0 0.5) (exp 0.5)))""";

	/** What every backend prints for {@link #SOURCE}. */
	public static final String EXPECTED = """
			#C(2 2)
			#C(2 4)
			#C(4 2)
			#C(4 2)
			#C(2.5 2.0)
			#C(2.5 2.0)
			#C(2.5 0.0)
			#C(5/6 3)
			1
			#C(-2 2)
			#C(0.5 -2.0)
			#C(-3 4)
			#C(1.5 3.0)
			5
			#C(1/3 2/3)
			#C(3/5 -6/5)
			#C(0.3 -0.6)
			#C(-0.4 0.8)
			#C(-1 -2)
			#C(-1.5 2.0)
			#C(1/5 -2/5)
			#C(3.5 0.0)
			#C(-9 12)
			#C(-0.5 -4.0)
			#C(2 2)
			#C(0.5 -2.0)
			#C(5/2 -1)
			T
			NIL
			T
			NIL
			T
			T
			T
			NIL
			T
			2.23606797749979
			3.0413812651491097
			2.5
			#C(-1.1312043837568138 2.471726672004819)
			#C(3.165778513216168 1.9596010414216063)
			#C(0.26612719531354573 3.6177750739401375)
			#C(0.0338128260798966 1.0147936161466335)
			#C(1.510484492504845 0.33529348145985527)
			#C(-0.4890562590412937 1.4031192506220407)
			#C(-0.9789478196465577 -1.936148329510507)
			#C(1.16673625724092 -0.2434582011857254)
			#C(-11 -2)
			#C(-3/25 -4/25)
			#C(-35/4 3)
			#C(0.36691394948660344 1.9660554808224875)
			#C(-0.22251715680177267 0.10070913113607541)
			#C(-0.3002831060007772 3.3301906767855614)
			#C(-1.7499999999999996 -6.000000000000001)
			#C(-3 4)
			#C(1.5384778027279442 1.2779225526272695)
			#C(2 4)
			#C(-1 -2)
			#C(-1.1312043837568138 2.471726672004819)
			2.5
			#C(3.5 0.0)
			#C(-11 -2)
			(2.23606797749979 3)
			(#C(2 2) 2)
			(3.75 6 1/3 -3/2 -0.0 T 2.5 1024 1.4142135623730951 1.6487212707001282)""";

	/**
	 * What a complex through a variable must still REFUSE, under handlers (WASM compiles
	 * it in EH mode): an ordering signals REAL, a non-number beside a complex is the
	 * operand reported, and a complex over an exact zero divides by zero.
	 */
	public static final String SIGNALS_SOURCE = """
			(defvar *cts-z* (complex 1 2))
			(defun cts-lt (a b) (< a b))
			(defun cts-ge (a b) (>= a b))
			(defun cts-mul (a b) (* a b))
			(defun cts-add (a b) (+ a b))
			(defun cts-div (a b) (/ a b))
			(defun cts-exp (a) (exp a))
			(defun cts-expt (a b) (expt a b))
			(defmacro cts-report (form)
			  `(handler-case (format t "~a~%" ,form)
			     (type-error (e) (format t "~a / ~a~%" e (type-error-expected-type e)))
			     (division-by-zero () (format t "division-by-zero~%"))))
			(cts-report (cts-lt *cts-z* 1))
			(cts-report (cts-ge 1 *cts-z*))
			(cts-report (if (< *cts-z* 2) 'yes 'no))
			(cts-report (plusp *cts-z*))
			(cts-report (min *cts-z* 1))
			(cts-report (cts-mul *cts-z* 'a))
			(cts-report (cts-add 'a *cts-z*))
			(cts-report (cts-div *cts-z* 0))
			(cts-report (cts-exp 'a))
			(cts-report (cts-expt *cts-z* 'a))
			(cts-report (cts-mul *cts-z* 2))""";

	/** What every backend prints for {@link #SIGNALS_SOURCE}. */
	public static final String SIGNALS_EXPECTED = """
			<: The value #C(1 2) is not of type REAL / REAL
			>=: The value #C(1 2) is not of type REAL / REAL
			<: The value #C(1 2) is not of type REAL / REAL
			>: The value #C(1 2) is not of type REAL / REAL
			MIN: The value #C(1 2) is not of type REAL / REAL
			*: The value A is not of type NUMBER / NUMBER
			+: The value A is not of type NUMBER / NUMBER
			division-by-zero
			EXP: The value A is not of type NUMBER / NUMBER
			EXPT: The value A is not of type NUMBER / NUMBER
			#C(2 4)""";

}
