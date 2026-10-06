package am.ik.rontolisp;

/**
 * A zero base -- the exact {@code 0}, a float zero, a complex with both parts zero -- on
 * the complex {@code expt} path, and the three arms that decide it before
 * {@code exp(w*log z)} runs (whose {@code log 0} is {@code -inf} and multiplied into NaN
 * parts): a zero power answers one ({@code #C(1.0 0.0)}); a power whose real part is
 * positive answers zero, exact {@code 0} when both operands are exact and
 * {@code #C(0.0 0.0)} otherwise; any other power keeps the IEEE answer of the formula,
 * NaN parts, as a float result of {@code expt} does (`.kb/error-handling.md`, "Per
 * operator"). A nonzero base never reaches the arms. Shared by the backend suites, so
 * every backend is held to one expected text; {@code ci-spec.yaml}'s
 * {@code a-zero-base-to-a-complex-power} pins the same rows on the native binary.
 */
public final class ZeroBaseComplexPowerFixture {

	private ZeroBaseComplexPowerFixture() {
	}

	/** The program. */
	public static final String SOURCE = """
			(defvar *zb-zero* 0)
			(defvar *zb-fzero* 0.0)
			(print (expt 0 #c(1 1)))
			(print (expt *zb-zero* #c(1/2 1)))
			(print (expt 0 #c(1.0 1.0)))
			(print (expt *zb-fzero* #c(1 1)))
			(print (expt -0.0 #c(1 1)))
			(print (expt (complex 0.0 0.0) 2.5))
			(print (expt (complex 0.0 -0.0) 2.5))
			(print (expt (complex -0.0 0.0) 2))
			(print (expt (complex 0.0 0.0) 1/2))
			(print (expt (complex 0.0 0.0) #c(1 1)))
			(print (expt (complex 0.0 0.0) 0))
			(print (expt (complex 0.0 -0.0) 0.0))
			(print (expt 0 #c(0.0 0.0)))
			(print (expt 0 #c(-1 1)))
			(print (expt *zb-zero* #c(0 1)))
			(print (expt 0.0 #c(-1.0 1.0)))
			(print (expt (complex 0.0 0.0) -2))
			(print (expt 2 #c(1 1)))
			(print (expt (complex 1.5 2.0) 0))""";

	/** What every backend prints. */
	public static final String EXPECTED = """
			0
			0
			#C(0.0 0.0)
			#C(0.0 0.0)
			#C(0.0 0.0)
			#C(0.0 0.0)
			#C(0.0 0.0)
			#C(0.0 0.0)
			#C(0.0 0.0)
			#C(0.0 0.0)
			#C(1.0 0.0)
			#C(1.0 0.0)
			#C(1.0 0.0)
			#C(NaN NaN)
			#C(NaN NaN)
			#C(NaN NaN)
			#C(NaN NaN)
			#C(1.5384778027279442 1.2779225526272695)
			#C(1.0 0.0)""";

}
