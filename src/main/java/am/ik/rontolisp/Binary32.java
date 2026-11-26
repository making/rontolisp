package am.ik.rontolisp;

/**
 * What {@code single-float} means here. Every float is one binary64, so the type names
 * the floats a binary32 holds exactly -- the values a library choosing a 32-bit encoding
 * by {@code typep} can write without loss -- and a conversion to it is the binary32
 * nearest the float. The JVM and both WASM backends emit the same two operations
 * ({@code d2f}/{@code f2d}, {@code f32.demote_f64}/{@code f64.promote_f32}); see
 * {@code .kb/declarations-type-checks.md}, "single-float is the floats a binary32 holds".
 */
public final class Binary32 {

	private Binary32() {
	}

	/**
	 * Whether a binary32 holds the value exactly: a NaN does (one of its NaNs), as do
	 * both zeros and both infinities.
	 * @param value the float
	 * @return whether it survives the round trip through binary32
	 */
	public static boolean holds(double value) {
		return Double.compare((float) value, value) == 0;
	}

	/**
	 * The binary32 nearest the value, ties to even, as a binary64.
	 * @param value the float
	 * @return the rounded value
	 */
	public static double nearest(double value) {
		return (float) value;
	}

}
