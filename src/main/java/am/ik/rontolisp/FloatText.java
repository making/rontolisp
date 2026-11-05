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
	 * How many prints on this thread spell a float the reader marked as a double
	 * ({@link LispDouble#doubleSpelled}) with its {@code d} marker: coalton prints a
	 * macro's forms to read them again with its own reader, which types a literal by that
	 * spelling (the interpreter's {@code rontolisp::%prin1-keeping-float-spelling}).
	 */
	private static final ThreadLocal<int[]> KEEPING_SPELLING = ThreadLocal.withInitial(() -> new int[1]);

	/**
	 * Runs a print that keeps the double spelling of the floats it prints.
	 * @param <T> what the print answers
	 * @param print the print
	 * @return what it answered
	 */
	public static <T> T keepingDoubleSpelling(java.util.function.Supplier<T> print) {
		int[] depth = KEEPING_SPELLING.get();
		depth[0]++;
		try {
			return print.get();
		}
		finally {
			depth[0]--;
		}
	}

	/**
	 * The text of a float as {@code prin1} prints it: {@link #doubleText}, with a
	 * {@code d} exponent marker when it was spelled so and a print asked to keep that
	 * ({@link #keepingDoubleSpelling}).
	 * @param value the float
	 * @return the printed text
	 */
	public static String text(LispDouble value) {
		String text = doubleText(value.value());
		if (!value.doubleSpelled() || KEEPING_SPELLING.get()[0] == 0 || !Double.isFinite(value.value())) {
			return text;
		}
		int exponent = text.indexOf('e');
		return exponent < 0 ? text + "d0" : text.substring(0, exponent) + "d" + text.substring(exponent + 1);
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

	/**
	 * The text of a bfloat16 value (a packed bfloat16 array element): the SHORTEST
	 * decimal that reads back as the same bfloat16, lowercase exponent marker. Seven
	 * mantissa bits is far less than an f32 carries, so {@link #singleText} would print
	 * the widened f32's digits ({@code 0.10009765625} where {@code 0.1} already
	 * round-trips); this walks the significant-digit counts upwards instead and takes the
	 * first whose value narrows back to the same pattern, then hands that value to
	 * {@code singleText} so the plain-versus-exponent decision and the marker stay the
	 * one shared choice rather than {@code BigDecimal}'s.
	 * @param value the value, which must be one {@link BFloat16#value(int)} answers
	 * @return the printed text
	 */
	public static String bfloat16Text(double value) {
		if (Double.isNaN(value) || Double.isInfinite(value) || value == 0.0) {
			return singleText((float) value);
		}
		int bits = BFloat16.bits(value);
		// Three significant digits carry most patterns and nine carry the rest; the loop
		// is bounded by the f32 round-trip length because the search is over f32 values.
		for (int digits = 1; digits <= 9; digits++) {
			float candidate = new java.math.BigDecimal(value).round(new java.math.MathContext(digits)).floatValue();
			if (BFloat16.bits(candidate) == bits) {
				return singleText(candidate);
			}
		}
		return singleText((float) value);
	}

}
