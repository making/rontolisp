package am.ik.rontolisp.codegen.wasm;

import java.util.Random;

import am.ik.rontolisp.FloatText;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the Schubfach mirror (the exact u64 arithmetic the WASM backends emit, including
 * the sparse-table recomposition of g) against {@link FloatText}, i.e. against
 * {@code Double.toString} / {@code Float.toString}. The emitted wasm replays the same
 * instructions, so this is the algorithm-level gate; the per-backend integration corpus
 * pins the transcription.
 */
class SchubfachTablesTest {

	private static String text(double value) {
		long bits = Double.doubleToRawLongBits(value);
		boolean sign = bits < 0;
		if (Double.isNaN(value)) {
			return "NaN";
		}
		if (Double.isInfinite(value)) {
			return sign ? "-Infinity" : "Infinity";
		}
		if (value == 0.0) {
			return sign ? "-0.0" : "0.0";
		}
		SchubfachTables.Dec dec = SchubfachTables.f64Dec(Math.abs(value));
		return format(sign, dec.digits(), dec.k());
	}

	private static String textF(float value) {
		int bits = Float.floatToRawIntBits(value);
		boolean sign = bits < 0;
		if (Float.isNaN(value)) {
			return "NaN";
		}
		if (Float.isInfinite(value)) {
			return sign ? "-Infinity" : "Infinity";
		}
		if (value == 0.0f) {
			return sign ? "-0.0" : "0.0";
		}
		SchubfachTables.Dec dec = SchubfachTables.f32Dec(Math.abs(value));
		return format(sign, dec.digits(), dec.k());
	}

	// The same formatting the emitted _dec_fmt performs (Java Double.toString's rules
	// with a lowercase exponent marker).
	private static String format(boolean sign, long digits, int k) {
		String ds = Long.toString(digits);
		int n = ds.length();
		int e = k + n - 1;
		StringBuilder sb = new StringBuilder();
		if (sign) {
			sb.append('-');
		}
		if (0 <= e && e < 7) {
			if (n <= e + 1) {
				sb.append(ds);
				sb.append("0".repeat(e + 1 - n));
				sb.append(".0");
			}
			else {
				sb.append(ds, 0, e + 1).append('.').append(ds, e + 1, n);
			}
		}
		else if (-3 <= e && e < 0) {
			sb.append("0.").append("0".repeat(-1 - e)).append(ds);
		}
		else {
			sb.append(ds.charAt(0)).append('.');
			if (n == 1) {
				sb.append('0');
			}
			else {
				sb.append(ds, 1, n);
			}
			sb.append('e').append(e);
		}
		return sb.toString();
	}

	@Test
	void doubleMirrorMatchesFloatTextOnCorpusAndRandom() {
		double[] corpus = { 1.21, 3.14159, 1.0e10, 1.0 / 3.0, 0.1, 1e-300, Double.MAX_VALUE, Double.MIN_VALUE,
				Double.MIN_NORMAL, -0.0, 0.0, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NaN, 1e7,
				9999999.0, 1e-3, 9.99e-4, 123456.789, 5e-324, 9.9e-323, 2.2250738585072014e-308, 0.001, 0.0001,
				1234567.0, 12345678.0, 2e15, 123.0, 1.0, 100.0, 1e23, 8e23, 8.98846567431158e307, 0.3333333333333333,
				0.5, 2.5, -1.21, Math.PI, Math.E };
		for (double v : corpus) {
			assertThat(text(v)).as("corpus %s", v).isEqualTo(FloatText.doubleText(v));
		}
		for (int p = -340; p <= 310; p++) {
			double v = Double.parseDouble("1e" + p);
			assertThat(text(v)).as("1e%d", p).isEqualTo(FloatText.doubleText(v));
		}
		// exhaustive tiny denormals: the region where the two-digit refinement differs
		// from a naive shortest selection
		for (long b = 1; b <= 300_000L; b++) {
			double v = Double.longBitsToDouble(b);
			assertThat(text(v)).as("denormal bits %d", b).isEqualTo(FloatText.doubleText(v));
		}
		Random r = new Random(20260817);
		for (int i = 0; i < 2_000_000; i++) {
			double v = Double.longBitsToDouble(r.nextLong());
			if (Double.isNaN(v) || Double.isInfinite(v)) {
				continue;
			}
			assertThat(text(v)).as("bits %x", Double.doubleToRawLongBits(v)).isEqualTo(FloatText.doubleText(v));
		}
	}

	@Test
	void singleMirrorMatchesFloatTextOnCorpusAndRandom() {
		float[] corpus = { 0.1f, 1.0f / 3.0f, 1.21f, 3.14159f, 1.0e10f, Float.MAX_VALUE, Float.MIN_VALUE,
				Float.MIN_NORMAL, 1e7f, 0.001f, 1.5f, 2.5f, -0.5f, 8.589973e9f };
		for (float v : corpus) {
			assertThat(textF(v)).as("corpus %s", v).isEqualTo(FloatText.singleText(v));
		}
		for (int b = 1; b <= 300_000; b++) {
			float v = Float.intBitsToFloat(b);
			assertThat(textF(v)).as("denormal bits %d", b).isEqualTo(FloatText.singleText(v));
		}
		Random r = new Random(20260817);
		for (int i = 0; i < 2_000_000; i++) {
			float v = Float.intBitsToFloat(r.nextInt());
			if (Float.isNaN(v) || Float.isInfinite(v)) {
				continue;
			}
			assertThat(textF(v)).as("bits %x", Float.floatToRawIntBits(v)).isEqualTo(FloatText.singleText(v));
		}
	}

	@Test
	void blobLayoutIsStable() {
		byte[] blob = SchubfachTables.blob();
		assertThat(blob).hasSize(SchubfachTables.BLOB_SIZE);
		assertThat(SchubfachTables.BLOB_SIZE).isLessThan(800);
	}

}
