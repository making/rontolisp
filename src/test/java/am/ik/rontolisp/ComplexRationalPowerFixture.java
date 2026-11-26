package am.ik.rontolisp;

/**
 * A complex base to a rational power, SBCL's polar form: {@code (expt z p)} is
 * {@code (* (expt (abs z) p) (cis (* p (phase z))))} for every complex base and rational
 * power except an exact base to an integer, which stays exact by squaring. The polar form
 * keeps a {@code -0.0} part through the phase ({@code (expt #c(0.0 -0.0) 2)} is
 * {@code #C(0.0 -0.0)}), rounds as SBCL does where {@code exp(p*log z)} lands a ulp away,
 * and agrees with a real negative base's rotation ({@code (expt -8.0 1/3)}). Any zero
 * power answers {@code #C(1.0 0.0)}. Every row is the answer SBCL gives with
 * {@code *read-default-float-format*} {@code double-float}, except
 * {@code (expt #c(1 2) 1/3)}, which SBCL answers in single floats and this runtime in
 * doubles by the same form. Each base reaches the compiled backends both in the call and
 * through a parameter. Shared by the backend suites; {@code ci-spec.yaml}'s
 * {@code a-complex-base-to-a-rational-power} pins the same rows on the native binary.
 */
public final class ComplexRationalPowerFixture {

	private ComplexRationalPowerFixture() {
	}

	/** The program. */
	public static final String SOURCE = """
			(defvar *crp-nz* (complex 0.0 -0.0))
			(defvar *crp-z* (complex 1.1 2.2))
			(defun crp-expt (a b) (expt a b))
			(print (expt #c(0.0 -0.0) 2))
			(print (crp-expt *crp-nz* 2))
			(print (expt #c(1.5 -0.0) 3))
			(print (crp-expt (complex 1.5 -0.0) 3))
			(print (expt #c(1.5 -0.0) -3))
			(print (expt #c(-0.0 -0.0) 3))
			(print (expt #c(-2.0 -0.0) 3))
			(print (expt #c(1.1 2.2) 2))
			(print (crp-expt *crp-z* 2))
			(print (expt #c(1.1 2.2) 1))
			(print (expt #c(1.1 2.2) -3))
			(print (crp-expt *crp-z* -3))
			(print (expt #c(3.0 4.0) 2))
			(print (expt #c(2.0 3.0) 10))
			(print (expt #c(0.6 0.8) 100000000000000000001))
			(print (crp-expt (complex 0.6 0.8) 100000000000000000001))
			(print (expt #c(-8.0 0.0) 1/3))
			(print (crp-expt (complex -8.0 0.0) 1/3))
			(print (expt -8.0 1/3))
			(print (expt #c(1.1 2.2) 1/2))
			(print (expt #c(1.1 2.2) -1/2))
			(print (expt #c(1.5 -0.0) 1/2))
			(print (crp-expt *crp-nz* 5/2))
			(print (expt #c(1 2) 1/3))
			(print (expt #c(1.1 -2.2) 0))
			(print (crp-expt (complex 0.1 -0.2) 0))
			(print (expt #c(0.1 -0.2) 0.0))
			(print (expt #c(0.1 -0.2) #c(0.0 0.0)))
			(print (expt #c(1 2) 3))
			(print (expt #c(1/2 2) -2))""";

	/** What every backend prints. */
	public static final String EXPECTED = """
			#C(0.0 -0.0)
			#C(0.0 -0.0)
			#C(3.375 -0.0)
			#C(3.375 -0.0)
			#C(0.2962962962962963 0.0)
			#C(-0.0 -0.0)
			#C(-8.0 -2.9391523179536475e-15)
			#C(-3.6300000000000003 4.8400000000000025)
			#C(-3.6300000000000003 4.8400000000000025)
			#C(1.1000000000000003 2.2)
			#C(-0.06611570247933882 0.01202103681442522)
			#C(-0.06611570247933882 0.01202103681442522)
			#C(-6.999999999999998 24.000000000000004)
			#C(-341524.9999999998 -145667.99999999994)
			#C(0.9229746253726842 -0.38486080719937354)
			#C(0.9229746253726842 -0.38486080719937354)
			#C(1.0000000000000002 1.7320508075688772)
			#C(1.0000000000000002 1.7320508075688772)
			#C(1.0000000000000002 1.7320508075688772)
			#C(1.3341054634566507 0.8245225209931408)
			#C(0.542391000989624 -0.33521607380366547)
			#C(1.224744871391589 -0.0)
			#C(0.0 -0.0)
			#C(1.2196165079717578 0.47171126778938893)
			#C(1.0 0.0)
			#C(1.0 0.0)
			#C(1.0 0.0)
			#C(1.0 0.0)
			#C(-11 -2)
			#C(-60/289 -32/289)""";

}
