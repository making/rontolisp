package am.ik.rontolisp.compiler;

/**
 * The one definition of what the internal {@code %fixed-decimal} built-in renders: a
 * fixed-point decimal string, sign included, built straight out of a {@code double}.
 *
 * <p>
 * It is the renderer behind {@code format}'s {@code ~F} and {@code ~$} on BOTH format
 * paths -- the compile-time expansion of a literal control string
 * ({@code LispMacroExpander}) and the runtime control-string renderer ({@code %fmt-fixed}
 * in {@code macro/format-render.lisp}) -- so the two spellings of one directive cannot
 * drift. Before it existed the directive expanded INLINE into eight ordinary forms (scale
 * by {@code 10^d}, {@code round} to a bignum-capable integer, {@code princ-to-string} it,
 * then punch in a decimal point with {@code subseq} and {@code %string-concat}), each
 * carrying its own numeric type ladder at every call site.
 *
 * <p>
 * <b>The algorithm is the contract</b>, not an implementation detail: the interpreter
 * calls this method, and the JVM and WASM backends emit the same steps as bytecode
 * ({@code _fixdec}) and as a runtime function ({@code _fixed_dec}). Every step is chosen
 * so all four backends can reproduce it exactly:
 *
 * <ul>
 * <li>{@code 10^places} by repeated multiplication -- exact for {@code places <= 22} and
 * identical wherever it is computed (there is no {@code pow} instruction in WASM);</li>
 * <li>{@link Math#rint} -- round half to EVEN, which is WASM's {@code f64.nearest} and
 * the rounding {@code round} already has on every backend;</li>
 * <li>{@code (long)} of a {@code double} -- SATURATING, which is WASM's
 * {@code i64.trunc_sat_f64_s}. A magnitude past {@code 2^63} therefore renders as the
 * digits of {@code Long.MAX_VALUE}, which is what the {@code round}-based expansion did
 * too.</li>
 * </ul>
 *
 * @see am.ik.rontolisp.LispNames#FIXED_DECIMAL
 */
public final class FixedDecimal {

	/**
	 * The upper bound on {@code places} and {@code intDigits}. A {@code double} carries
	 * about 17 significant digits, so nothing under this is a real request; the bound
	 * exists so a computed {@code ~v,vF} parameter cannot ask any backend for an
	 * unbounded digit buffer.
	 */
	public static final int MAX_DIGITS = 1024;

	private FixedDecimal() {
	}

	/**
	 * Renders {@code value} with exactly {@code places} fractional digits.
	 * @param value the number to render
	 * @param places fractional digits (clamped to {@code [0, MAX_DIGITS]}); 0 renders no
	 * decimal point
	 * @param intDigits the minimum number of integer digits, zero-padded on the left
	 * (clamped the same way); 1 means no padding
	 * @param plus whether a non-negative value carries an explicit {@code +}
	 * ({@code ~@F})
	 * @return the rendered text, without the storage quotes the compile backends frame a
	 * string with
	 */
	public static String render(double value, int places, int intDigits, boolean plus) {
		int d = clamp(places);
		int n = clamp(intDigits);
		boolean neg = value < 0.0;
		double scale = 1.0;
		for (int i = 0; i < d; i++) {
			scale *= 10.0;
		}
		// The magnitude as an integer: take the absolute value BEFORE the truncation, so
		// the saturating conversion clamps symmetrically (Long.MIN_VALUE has no positive
		// counterpart) and a NaN still lands on 0.
		long magnitude = (long) Math.abs(Math.rint(value * scale));
		String digits = Long.toString(magnitude);
		int minDigits = Math.max(d + 1, n + d);
		if (digits.length() < minDigits) {
			digits = "0".repeat(minDigits - digits.length()).concat(digits);
		}
		int split = digits.length() - d;
		StringBuilder out = new StringBuilder(digits.length() + 2);
		if (neg) {
			out.append('-');
		}
		else if (plus) {
			out.append('+');
		}
		out.append(digits, 0, split);
		if (d > 0) {
			out.append('.').append(digits, split, digits.length());
		}
		return out.toString();
	}

	private static int clamp(int n) {
		return Math.max(0, Math.min(MAX_DIGITS, n));
	}

}
