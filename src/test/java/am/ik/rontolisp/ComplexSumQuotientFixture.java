package am.ik.rontolisp;

/**
 * A sum, difference or quotient with a complex operand, SBCL's way: the fold runs left to
 * right one pair at a time (an exact step stays exact whatever float follows), a real
 * beside a complex counts as a zero-imagined complex for {@code +} and {@code -}, and
 * {@code /} dispatches on the operands' shapes -- a complex over a real divides each
 * part, a real over a complex and two complexes take SBCL's own forms, with every
 * operation converting an exact operand only where it meets a float. The rows pin the
 * signed zero parts and the last digits that follow from that. Every row is the answer
 * SBCL 2.2.9 gives. Most forms are spelled with the complex in the call and through a
 * variable, since the compiled backends reach their complex helpers by two routes. Shared
 * by the backend suites.
 */
public final class ComplexSumQuotientFixture {

	private ComplexSumQuotientFixture() {
	}

	public static final String SOURCE = """
			(defvar *szq-z* (complex 1 2))
			(defvar *szq-m* (complex -1 -2))
			(defvar *szq-nz* (complex 0.0 -0.0))
			(defvar *szq-nn* (complex -0.0 -0.0))
			(defvar *szq-pz* (complex 0.0 0.0))
			(defvar *szq-one* (complex 1.0 0.0))
			(defvar *szq-zero* 0)
			(defvar *szq-i* (complex 0 1))
			(defun szq-add (a b) (+ a b))
			(defun szq-sub (a b) (- a b))
			(defun szq-div (a b) (/ a b))
			(print (+ *szq-z* *szq-m* 1.5))
			(print (- *szq-z* *szq-z* 1.5))
			(print (/ *szq-z* *szq-z* 1.5))
			(print (handler-case (/ *szq-z* *szq-zero* 1.5) (division-by-zero () :division-by-zero)))
			(print (- *szq-z* 1/2 *szq-z* 1.5))
			(print (+ 1/10 1/5 *szq-i* 0.0))
			(print (+ 1/10 1/5 0.0 *szq-i*))
			(print (+ *szq-z* 1.5 *szq-m*))
			(print (+ #c(0.0 -0.0) 1))
			(print (szq-add *szq-nz* 1))
			(print (+ -0.0 #c(-0.0 -0.0)))
			(print (szq-add 0.0 *szq-nn*))
			(print (+ #c(-0.0 -0.0) #c(-0.0 -0.0)))
			(print (+ #c(-0.0 -0.0)))
			(print (- #c(0.0 -0.0) 1))
			(print (szq-sub *szq-nz* 1))
			(print (- -0.0 #c(0.0 0.0)))
			(print (szq-sub 0.0 *szq-pz*))
			(print (- #c(-0.0 -0.0) 0))
			(print (- #c(0.0 0.0)))
			(print (- #c(-0.0 -0.0) #c(0 1)))
			(print (/ #c(-0.0 -0.0) 1.0))
			(print (szq-div *szq-nn* 1))
			(print (/ #c(1.0 -0.0) -1.0))
			(print (/ #c(-0.0 1.0) 2))
			(print (/ 1 #c(1.0 0.0)))
			(print (szq-div 1 *szq-one*))
			(print (/ 0.0 #c(1 1)))
			(print (/ -0.0 #c(1 1)))
			(print (/ 0 #c(1.0 -2.0)))
			(print (/ 2 #c(-0.0 1.0)))
			(print (/ #c(1.5 -0.0)))
			(print (/ 1.0 #c(0.2 1.3)))
			(print (/ 1.5 #c(3 1)))
			(print (/ 0.5 #c(1 6)))
			(print (/ #c(1.0 1.0) #c(1.0 -1.0)))
			(print (/ #c(1.0 -1.0) #c(2.0 -2.0)))
			(print (/ #c(1.5 2.5) #c(3 1)))
			(print (/ #c(0.5 1.5) #c(3 7)))
			(print (/ #c(1 2) #c(3 4) 0.5))
			""";

	/** What every backend prints for {@link #SOURCE}. */
	public static final String EXPECTED = """
			1.5
			-1.5
			0.6666666666666666
			:DIVISION-BY-ZERO
			-2.0
			#C(0.3 1.0)
			#C(0.3 1.0)
			#C(1.5 0.0)
			#C(1.0 0.0)
			#C(1.0 0.0)
			#C(-0.0 0.0)
			#C(0.0 0.0)
			#C(-0.0 -0.0)
			#C(-0.0 -0.0)
			#C(-1.0 -0.0)
			#C(-1.0 -0.0)
			#C(-0.0 0.0)
			#C(0.0 0.0)
			#C(-0.0 -0.0)
			#C(-0.0 -0.0)
			#C(-0.0 -1.0)
			#C(-0.0 -0.0)
			#C(-0.0 -0.0)
			#C(-1.0 0.0)
			#C(-0.0 0.5)
			#C(1.0 -0.0)
			#C(1.0 -0.0)
			#C(0.0 -0.0)
			#C(-0.0 0.0)
			#C(0.0 -0.0)
			#C(-0.0 -2.0)
			#C(0.6666666666666666 0.0)
			#C(0.11560693641618498 -0.7514450867052024)
			#C(0.44999999999999996 -0.15)
			#C(0.013513513513513513 -0.08108108108108107)
			#C(-0.0 1.0)
			#C(0.5 -0.0)
			#C(0.6999999999999998 0.6)
			#C(0.2068965517241379 0.017241379310344817)
			#C(0.88 0.16)""";

}
