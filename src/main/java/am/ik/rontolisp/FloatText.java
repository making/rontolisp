package am.ik.rontolisp;

/**
 * The single authority for the text of a float, shared by every backend. The digits are
 * the shortest decimal that reads back as the same IEEE value (Java's
 * {@link Double#toString(double)} / {@link Float#toString(float)} selection, i.e.
 * Schubfach), and the exponent marker is spelled lowercase {@code e} as Common Lisp
 * prints it ({@code 1.0e10}, not {@code 1.0E10}). The interpreter and the JVM-emitted
 * runtime call {@code Double.toString} plus the same marker rewrite; the two WASM
 * backends emit the identical Schubfach selection as wasm bytes ({@code SchubfachTables}
 * carries the shared constants), so all four produce byte-identical text. Scalars are
 * always {@code double-float}; the single-float width exists only as a packed-array
 * element type, and {@code singleText} is what those elements print with so that
 * {@code #f(0.1)} round-trips instead of showing the widened double's digits.
 */
public final class FloatText {

	private FloatText() {
	}

	/**
	 * The text of a double-float value: shortest round-trip digits, lowercase exponent
	 * marker. {@code NaN}, {@code Infinity}, {@code -Infinity} and {@code -0.0} keep
	 * Java's spellings.
	 * @param value the value
	 * @return the printed text
	 */
	public static String doubleText(double value) {
		return Double.toString(value).replace('E', 'e');
	}

	/**
	 * The text of a single-float value (a packed single-float array element): shortest
	 * round-trip digits for the f32 width, lowercase exponent marker.
	 * @param value the value
	 * @return the printed text
	 */
	public static String singleText(float value) {
		return Float.toString(value).replace('E', 'e');
	}

}
