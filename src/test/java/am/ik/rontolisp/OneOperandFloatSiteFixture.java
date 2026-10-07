package am.ik.rontolisp;

/**
 * A one-operand float site -- {@code (- x)}, {@code (/ x)}, {@code abs}, {@code signum},
 * {@code random}, and {@code expt}'s base or power -- whose float literal sits in a
 * branch the operand need not take: the operand is converted only where it is proven a
 * float, so an exact operand answers exactly ({@code (abs (if c 1.5 -2))} is {@code 2}).
 * The reciprocal of an exact operand inside a float site converts the exact quotient, not
 * the quotient of the converted operand, and an exact zero divides by zero. Every value
 * row is the answer SBCL 2.2.9 gives. Shared by the backend suites.
 */
public final class OneOperandFloatSiteFixture {

	private OneOperandFloatSiteFixture() {
	}

	/** A program that cannot observe a complex. */
	public static final String SOURCE = """
			(defun ofl-id (x) (if (consp x) (car x) x))
			(defmacro ofl-try (form)
			  `(handler-case ,form (division-by-zero () :division-by-zero) (error () :error)))
			(defun ofl-neg (x) (- (if (ofl-id nil) 1.5 x)))
			(defun ofl-declared (x) (declare (double-float x)) (abs x))
			(print (abs (if (ofl-id nil) 1.5 -2)))
			(print (abs (if (ofl-id nil) 1.5 -1/2)))
			(print (abs (if (ofl-id t) -1.5 2)))
			(print (abs -1.5))
			(print (- (if (ofl-id nil) 1.5 2)))
			(print (- (if (ofl-id nil) 1.5 1/3)))
			(print (- (if (ofl-id t) 0.0 2)))
			(print (ofl-neg 9007199254740993))
			(print (/ (if (ofl-id nil) 2.0 4)))
			(print (/ (if (ofl-id nil) 2.0 9007199254740993)))
			(print (/ (if (ofl-id t) 2.0 4)))
			(print (ofl-try (/ (if (ofl-id nil) 2.0 0))))
			(print (signum (if (ofl-id nil) 1.5 -2)))
			(print (signum (if (ofl-id nil) 1.5 1/2)))
			(print (signum (if (ofl-id t) -1.5 2)))
			(print (expt (if (ofl-id nil) 2.0 2) 3))
			(print (expt (if (ofl-id nil) 2.0 2) -1))
			(print (expt 2 (if (ofl-id nil) 2.0 3)))
			(print (expt (if (ofl-id t) 2.0 2) 3))
			(print (expt 2.0 (ofl-id 3)))
			(print (integerp (random (if (ofl-id nil) 1.0 10))))
			(print (floatp (random (if (ofl-id t) 1.0 10))))
			(print (random (if (ofl-id nil) 1.0 1)))
			(print (* 1.0 (/ (if (ofl-id nil) 2.0 9007199254740993))))
			(print (* 1.0 (- (if (ofl-id nil) 2.0 1/3))))
			(print (sqrt (+ 0.0 (/ (if (ofl-id nil) 2.0 4)))))
			(print (+ 1.0 (abs (if (ofl-id nil) 1.5 -2))))
			(print (ofl-declared -2.5))
			""";

	/** What every backend prints for {@link #SOURCE}. */
	public static final String EXPECTED = """
			2
			1/2
			1.5
			1.5
			-2
			-1/3
			-0.0
			-9007199254740993
			1/4
			1/9007199254740993
			0.5
			:DIVISION-BY-ZERO
			-1
			1
			-1.0
			8
			1/2
			8
			8.0
			8.0
			T
			T
			0
			1.1102230246251564e-16
			-0.3333333333333333
			0.5
			3.0
			2.5""";

	/**
	 * A program that may observe a complex, where the operand a call produces may be one:
	 * an exact operand answers exactly, a complex answers through the generic helpers,
	 * and a proven float site keeps its guarded raw path.
	 */
	public static final String COMPLEX_SOURCE = """
			(defvar *ofc-i* (complex 0 1))
			(defvar *ofc-z* (complex 3 4))
			(defvar *ofc-w* (complex 3.0 4.0))
			(defvar *ofc-j* (complex 0.0 1.0))
			(defun ofc-id (x) (if (consp x) (car x) x))
			(print (abs (ofc-id (if (ofc-id nil) 1.5 -2))))
			(print (- (ofc-id (if (ofc-id nil) 1.5 2))))
			(print (/ (ofc-id (if (ofc-id nil) 2.0 4))))
			(print (signum (ofc-id (if (ofc-id nil) 1.5 -2))))
			(print (expt (ofc-id (if (ofc-id nil) 2.0 2)) 3))
			(print (random (ofc-id (if (ofc-id nil) 1.0 1))))
			(print (abs (ofc-id (if (ofc-id nil) 1.5 *ofc-w*))))
			(print (- (ofc-id (if (ofc-id nil) 1.5 *ofc-z*))))
			(print (/ (ofc-id (if (ofc-id nil) 2.0 *ofc-i*))))
			(print (signum (ofc-id (if (ofc-id nil) 1.5 *ofc-j*))))
			(print (* 1.0 (/ (ofc-id (if (ofc-id nil) 2.0 9007199254740993)))))
			(print (* 1.0 (/ (ofc-id (if (ofc-id nil) 2.0 *ofc-i*)))))
			(print (abs (* 2.0 *ofc-z*)))
			(print (- (* 1.0 *ofc-i*)))
			(print (abs (ofc-id (if (ofc-id t) -1.5 2))))
			""";

	/** What every backend prints for {@link #COMPLEX_SOURCE}. */
	public static final String COMPLEX_EXPECTED = """
			2
			-2
			1/4
			-1
			8
			0
			5.0
			#C(-3 -4)
			#C(0 -1)
			#C(0.0 1.0)
			1.1102230246251564e-16
			#C(0.0 -1.0)
			10.0
			#C(-0.0 -1.0)
			1.5""";

}
