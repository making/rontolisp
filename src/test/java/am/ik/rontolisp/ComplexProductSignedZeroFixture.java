package am.ik.rontolisp;

/**
 * The signed zero parts of a product with a complex operand: a complex times a real
 * multiplies each part by the real (so a {@code -0.0} part survives), and a product of
 * two complexes is the textbook formula, folded left to right from the first operand.
 * Every row is the answer SBCL gives. Each form is spelled twice -- with the complex in
 * the call itself and through a variable -- since the compiled backends reach their
 * complex helpers by two routes. Shared by the backend suites.
 */
public final class ComplexProductSignedZeroFixture {

	private ComplexProductSignedZeroFixture() {
	}

	public static final String SOURCE = """
			(defvar *szp-nz* (complex 0.0 -0.0))
			(defvar *szp-nn* (complex -0.0 -0.0))
			(defvar *szp-pz* (complex 0.0 0.0))
			(defvar *szp-mixed* (complex -0.0 0.0))
			(defun szp-mul (a b) (* a b))
			(print (* #c(0.0 -0.0) #c(1 -1)))
			(print (szp-mul *szp-nz* (complex 1 -1)))
			(print (* #c(-0.0 -0.0) 0))
			(print (szp-mul *szp-nn* 0))
			(print (* #c(0.0 -0.0) 1))
			(print (szp-mul *szp-nz* 1))
			(print (* 1.0 #c(0.0 -0.0)))
			(print (szp-mul 1.0 *szp-nz*))
			(print (* 2 #c(0.0 -0.0) 3))
			(print (* #c(0.0 -0.0)))
			(print (* -1.0 #c(0.0 0.0)))
			(print (szp-mul -1.0 *szp-pz*))
			(print (* #c(1.5 -0.0) 2))
			(print (* #c(-0.0 0.0) 1.0))
			(print (szp-mul *szp-mixed* 1.0))
			(print (* 1/2 #c(1.5 -0.0)))
			(print (* #c(1 1) #c(0.0 -0.0)))
			(print (* #c(0 1) #c(-0.0 -0.0)))
			(print (* #c(1.0 -0.0) #c(1.0 -0.0)))
			(print (* #c(1 2) 2 #c(0.0 -0.0)))
			(print (* 2 #c(-0.0 0.0) #c(1 1)))
			(print (* #c(1 2) 0.0))
			(print (* #c(1 2.0) 0))
			(print (* #c(1 2) 0))
			(print (szp-mul *szp-pz* 0))
			(print (* #c(1 2) #c(3 4) 0.5))
			""";

	/** What every backend prints for {@link #SOURCE}. */
	public static final String EXPECTED = """
			#C(0.0 -0.0)
			#C(0.0 -0.0)
			#C(-0.0 -0.0)
			#C(-0.0 -0.0)
			#C(0.0 -0.0)
			#C(0.0 -0.0)
			#C(0.0 -0.0)
			#C(0.0 -0.0)
			#C(0.0 -0.0)
			#C(0.0 -0.0)
			#C(-0.0 -0.0)
			#C(-0.0 -0.0)
			#C(3.0 -0.0)
			#C(-0.0 0.0)
			#C(-0.0 0.0)
			#C(0.75 -0.0)
			#C(0.0 0.0)
			#C(0.0 -0.0)
			#C(1.0 -0.0)
			#C(0.0 0.0)
			#C(-0.0 0.0)
			#C(0.0 0.0)
			#C(0.0 0.0)
			0
			#C(0.0 0.0)
			#C(-2.5 5.0)""";

}
