package am.ik.rontolisp;

/**
 * A real {@code + - * /} over a float, SBCL's way: the fold runs left to right one pair
 * at a time, so the arguments ahead of the first float fold exactly and only their result
 * converts -- {@code (+ 1/10 1/5 0.0)} is {@code 0.3}, not the
 * {@code 0.30000000000000004} of converting every argument first. Literal operands and
 * operands through a call, an exact prefix of three, a bignum prefix past 2^53, an exact
 * zero divisor ahead of the float, an inner operation in and after a prefix, a site whose
 * float only a call shows, a declared float, the fused double path's integer constants.
 * Every value row is the answer SBCL 2.2.9 gives; the traced rows print what ran before
 * the report, which is the interpreter's order (CLHS, and SBCL's full call -- its
 * compiled n-ary arithmetic signals before later arguments run,
 * `.kb/argument-evaluation-order.md`). Shared by the backend suites.
 */
public final class ExactPrefixFloatFoldFixture {

	private ExactPrefixFloatFoldFixture() {
	}

	/** A program that cannot observe a complex. */
	public static final String SOURCE = """
			(defun epf-id (x) (if (consp x) (car x) x))
			(defvar *epf-log* nil)
			(defun epf-note (x) (push x *epf-log*) x)
			(defmacro epf-try (form)
			  `(handler-case ,form (division-by-zero () :division-by-zero) (error () :error)))
			(defmacro epf-trace (form)
			  `(progn (setf *epf-log* nil) (let ((v (epf-try ,form))) (list v (reverse *epf-log*)))))
			(defun epf-sum3 (a b c) (+ a b c 0.5))
			(defun epf-fused (x y) (* (+ 9007199254740993 1 x) y))
			(defun epf-declared (a b) (declare (double-float b)) (+ a 1/5 b))
			(defun epf-mixed (a b c) (* (- a b c) 1.5))
			(print (+ 1/10 1/5 0.0))
			(print (- 1/10 -1/5 0.0))
			(print (* 1/10 3 1.0))
			(print (/ 1/10 1/3 1.0))
			(print (+ (epf-id 1/10) (epf-id 1/5) (epf-id 0.0)))
			(print (- (epf-id 1/10) (epf-id -1/5) (epf-id 0.0)))
			(print (* (epf-id 1/10) (epf-id 3) (epf-id 1.0)))
			(print (/ (epf-id 1/10) (epf-id 1/3) (epf-id 1.0)))
			(print (+ (epf-id 1/10) (epf-id 1/5) 0.0))
			(print (+ 1/10 1/5 (epf-id 0.0)))
			(print (+ 9007199254740993 1 0.0))
			(print (* (epf-id 9007199254740993) (epf-id 3) (epf-id 1.0)))
			(print (+ (epf-id 1/10) (epf-id 1/5) (epf-id 1/7) (epf-id 0.0)))
			(print (+ (epf-id 1/10) (epf-id 1/5) (epf-id 0.0) (epf-id 1/7)))
			(print (+ (epf-id 1/10) (epf-id 0.0) (epf-id 1/5)))
			(print (+ (epf-id 0.0) (epf-id 1/10) (epf-id 1/5)))
			(print (- 0.0 (epf-id 1/10) (epf-id 1/5)))
			(print (- (epf-id 1/10) (epf-id 1/5) 0.0))
			(print (/ (epf-id 1) (epf-id 3) (epf-id 3) 1.0))
			(print (epf-try (/ (epf-id 1/2) (epf-id 0) 1.0)))
			(print (epf-try (/ 1/2 0 1.0)))
			(print (* 1.0 (+ 1/10 1/5 (epf-id 0.0))))
			(print (* 2.0 (+ (epf-id 1/10) (epf-id 1/5) (epf-id 0))))
			(print (+ (+ (epf-id 1/10) 1/5 (epf-id 0)) (epf-id 1/7) 1.0))
			(print (sqrt (+ 1/10 1/5 0.0)))
			(print (= (+ (epf-id 1/10) (epf-id 1/5) 0.0) 0.3))
			(print (+ 1/10 (epf-id 1/5) (if (epf-id nil) 0.0 1)))
			(print (* (epf-id 1/2) (if (epf-id nil) 2.0 7)))
			(print (mod (epf-id 7) (if (epf-id nil) 2.0 3)))
			(print (epf-sum3 1/10 1/5 1/7))
			(print (epf-sum3 0.25 1/5 1/7))
			(print (epf-fused 0.0 1.0))
			(print (epf-fused 0 1))
			(print (epf-declared 1/10 0.0))
			(print (epf-mixed 9007199254740993 1 1))
			(print (epf-trace (+ (epf-note "a") (epf-note 1/2) (epf-note 3) 1.5)))
			(print (epf-trace (/ (epf-note 1) (epf-note 0) (epf-note 3) 1.5)))
			(print (epf-trace (/ (epf-note 1) (epf-note 0) (epf-note 3))))
			(print (epf-trace (- (epf-note 1) (epf-note "b") (epf-note 3))))
			(print (epf-trace (+ 1/10 1/5 (epf-note 1/7) (epf-note 0.5) (epf-note 1/3))))
			""";

	/** What every backend prints for {@link #SOURCE}. */
	public static final String EXPECTED = """
			0.3
			0.3
			0.3
			0.3
			0.3
			0.3
			0.3
			0.3
			0.3
			0.3
			9.007199254740994e15
			2.702159776422298e16
			0.44285714285714284
			0.44285714285714284
			0.30000000000000004
			0.30000000000000004
			-0.30000000000000004
			-0.1
			0.1111111111111111
			:DIVISION-BY-ZERO
			:DIVISION-BY-ZERO
			0.3
			0.6
			1.4428571428571428
			0.5477225575051661
			T
			13/10
			7/2
			1
			0.9428571428571428
			1.092857142857143
			9.007199254740994e15
			9007199254740994
			0.3
			1.3510798882111486e16
			(:ERROR ("a" 1/2 3))
			(:DIVISION-BY-ZERO (1 0 3))
			(:DIVISION-BY-ZERO (1 0 3))
			(:ERROR (1 "b" 3))
			(1.276190476190476 (1/7 0.5 1/3))""";

	/**
	 * A program that may observe a complex, where an operand a variable or a call
	 * produces may be one: the guarded float sites fold the same exact prefix, and a fold
	 * with a complex literal holds its steps back like the real one.
	 */
	public static final String COMPLEX_SOURCE = """
			(defvar *epc-i* (complex 0 1))
			(defun epc-id (x) (if (consp x) (car x) x))
			(print (+ (epc-id 1/10) (epc-id 1/5) 0.0))
			(print (+ (epc-id 1/10) (epc-id 1/5) (epc-id 0.0)))
			(print (* (epc-id 1/10) (epc-id 3) 1.0))
			(print (+ (epc-id #c(1 1)) (epc-id 1/2) 1.0))
			(print (+ (epc-id 1/2) (epc-id #c(1 1)) (epc-id 1/3) 1.0))
			(print (+ (epc-id 1/10) (epc-id 1/5) *epc-i* 0.0))
			(print (* 2.0 (+ (epc-id 1/10) (epc-id 1/5) (epc-id 0))))
			(print (* 2.0 (+ (epc-id 1/10) (epc-id 1/5) *epc-i* 0.0)))
			(print (+ (+ (epc-id 1/10) 1/5 (epc-id 0.0)) (epc-id 1/7) 1.0))
			(print (- (epc-id 1/10) (epc-id 1/5) (epc-id #c(0.0 1.0)) 1.0))
			(print (/ (epc-id 1/10) (epc-id 1/3) (epc-id 1) 1.0))
			(print (handler-case (/ (epc-id 1/2) (epc-id 0) *epc-i* 1.0) (division-by-zero () :division-by-zero)))
			(defvar *epc-log* nil)
			(defun epc-note (x) (push x *epc-log*) x)
			(print (list (handler-case (+ #c(1 1) (epc-note "x") (epc-note 2)) (error () :error)) (reverse *epc-log*)))
			""";

	/** What every backend prints for {@link #COMPLEX_SOURCE}. */
	public static final String COMPLEX_EXPECTED = """
			0.3
			0.3
			0.3
			#C(2.5 1.0)
			#C(2.833333333333333 1.0)
			#C(0.3 1.0)
			0.6
			#C(0.6 2.0)
			1.4428571428571428
			#C(-1.1 -1.0)
			0.3
			:DIVISION-BY-ZERO
			(:ERROR ("x" 2))""";

}
