package am.ik.rontolisp;

/**
 * A comparison whose float meets an operand of unproven type: a double literal beside a
 * variable (in value and in test position, on either side), and two variables. Every
 * operand tier meets it -- an integer a double holds exactly, one past 2^53, a bignum, a
 * ratio within an ulp of the float, a float, a signed zero, an infinity, NaN, a
 * non-number -- so the answer is the exact comparison wherever the backend reads the
 * operand raw. Shared by the backend suites.
 */
public final class FloatComparisonOperandFixture {

	private FloatComparisonOperandFixture() {
	}

	/** The program. */
	public static final String SOURCE = """
			(defun fco-id (x) (if (consp x) (car x) x))
			(defun fco-row (x)
			  (list (< x 2.0) (<= x 2.0) (= x 2.0) (>= x 2.0) (> x 2.0) (/= x 2.0)
			        (< 2.0 x) (<= 2.0 x) (= 2.0 x) (>= 2.0 x) (> 2.0 x)))
			(defun fco-test (x)
			  (list (if (< x 2.0) 1 0) (if (= x 2.0) 1 0) (if (>= 2.0 x) 1 0)
			        (if (not (> x 2.0)) 1 0)))
			(defun fco-wide (x)
			  (list (< x 9007199254740992.0) (= x 9007199254740992.0)
			        (> 9007199254740992.0 x) (if (<= x 9007199254740992.0) 1 0)))
			(defun fco-pair (a b) (list (< a b) (= a b) (> a b) (if (<= a b) 1 0)))
			(dolist (v (list 1 2 3 1.5 2.0 5/2 3/2 -0.0 1073741824 (expt 2 70)
			                 (/ 1.0 (fco-id 0.0)) (/ -1.0 (fco-id 0.0)) (/ 0.0 (fco-id 0.0))))
			  (print (fco-row (fco-id v)))
			  (print (fco-test (fco-id v))))
			(dolist (v (list 9007199254740991 9007199254740992 9007199254740993
			                 -9007199254740993 18014398509481984 (expt 2 100) 1/3
			                 9007199254740992.0 0.6666666666666666))
			  (print (fco-wide (fco-id v))))
			(dolist (p (list (list 1.5 2) (list 2 2.0) (list 2.0 2)
			                 (list 9007199254740993 9007199254740992.0)
			                 (list 9007199254740992.0 9007199254740992)
			                 (list -0.0 0) (list 0.0 -0.0) (list 0.6666666666666666 2/3)
			                 (list 1073741823 1073741823.0) (list 1073741824.0 1073741824)
			                 (list -1073741825 -1073741825.0) (list 1.0 1.0)
			                 (list (/ 1.0 (fco-id 0.0)) 5) (list 5 (/ -1.0 (fco-id 0.0)))
			                 (list (/ 0.0 (fco-id 0.0)) 1) (list (/ 0.0 (fco-id 0.0)) 1.0)))
			  (print (fco-pair (fco-id (first p)) (fco-id (second p)))))
			(print (handler-case (< (fco-id 'a) 2.0) (error () :error)))
			(print (handler-case (if (> 2.0 (fco-id "b")) 1 0) (error () :error)))
			""";

	/**
	 * A program that may observe a complex: one arriving through a variable meets the
	 * literal, so {@code =} compares it part-wise and an ordering signals.
	 */
	public static final String COMPLEX_SOURCE = """
			(defun fcc-id (x) (if (consp x) (car x) x))
			(defun fcc-row (x)
			  (list (= x 2.0) (= 2.0 x) (/= x 2.0) (if (= x 2.0) 1 0)
			        (handler-case (< x 2.0) (error () :error))
			        (handler-case (if (>= 2.0 x) 1 0) (error () :error))))
			(print (fcc-row (fcc-id (complex 2 1))))
			(print (fcc-row (fcc-id (complex 2.0 1.0))))
			(print (fcc-row (fcc-id 2)))
			(print (fcc-row (fcc-id 2.5)))""";

	/** What every backend prints for {@link #COMPLEX_SOURCE}. */
	public static final String COMPLEX_EXPECTED = """
			(NIL NIL T 0 :ERROR :ERROR)
			(NIL NIL T 0 :ERROR :ERROR)
			(T T NIL 1 NIL 1)
			(NIL NIL T 0 NIL 0)""";

	/** What every backend prints for {@link #SOURCE}. */
	public static final String EXPECTED = """
			(T T NIL NIL NIL T NIL NIL NIL T T)
			(1 0 1 1)
			(NIL T T T NIL NIL NIL T T T NIL)
			(0 1 1 1)
			(NIL NIL NIL T T T T T NIL NIL NIL)
			(0 0 0 0)
			(T T NIL NIL NIL T NIL NIL NIL T T)
			(1 0 1 1)
			(NIL T T T NIL NIL NIL T T T NIL)
			(0 1 1 1)
			(NIL NIL NIL T T T T T NIL NIL NIL)
			(0 0 0 0)
			(T T NIL NIL NIL T NIL NIL NIL T T)
			(1 0 1 1)
			(T T NIL NIL NIL T NIL NIL NIL T T)
			(1 0 1 1)
			(NIL NIL NIL T T T T T NIL NIL NIL)
			(0 0 0 0)
			(NIL NIL NIL T T T T T NIL NIL NIL)
			(0 0 0 0)
			(NIL NIL NIL T T T T T NIL NIL NIL)
			(0 0 0 0)
			(T T NIL NIL NIL T NIL NIL NIL T T)
			(1 0 1 1)
			(NIL NIL NIL NIL NIL T NIL NIL NIL NIL NIL)
			(0 0 0 1)
			(T NIL T 1)
			(NIL T NIL 1)
			(NIL NIL NIL 0)
			(T NIL T 1)
			(NIL NIL NIL 0)
			(NIL NIL NIL 0)
			(T NIL T 1)
			(NIL T NIL 1)
			(T NIL T 1)
			(T NIL NIL 1)
			(NIL T NIL 1)
			(NIL T NIL 1)
			(NIL NIL T 0)
			(NIL T NIL 1)
			(NIL T NIL 1)
			(NIL T NIL 1)
			(T NIL NIL 1)
			(NIL T NIL 1)
			(NIL T NIL 1)
			(NIL T NIL 1)
			(NIL T NIL 1)
			(NIL NIL T 0)
			(NIL NIL T 0)
			(NIL NIL NIL 0)
			(NIL NIL NIL 0)
			:ERROR
			:ERROR""";

}
