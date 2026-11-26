package am.ik.rontolisp.compiler;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code %fixed-decimal} contract. Every assertion here is a rendering the JVM
 * ({@code _fixdec}) and WASM ({@code _fixed_dec}) emissions have to reproduce exactly --
 * a change to any of them is a cross-backend change, not a local one.
 */
class FixedDecimalTest {

	@Test
	void rendersTheRequestedNumberOfFractionalDigits() {
		assertThat(FixedDecimal.render(3.141591653589774, 15, 1, false)).isEqualTo("3.141591653589774");
		assertThat(FixedDecimal.render(3.14159, 2, 1, false)).isEqualTo("3.14");
		assertThat(FixedDecimal.render(2.5, 0, 1, false)).isEqualTo("2");
		assertThat(FixedDecimal.render(100.0, 2, 1, false)).isEqualTo("100.00");
	}

	@Test
	void padsTheIntegerPartToTheRequestedWidth() {
		assertThat(FixedDecimal.render(-0.5, 2, 3, false)).isEqualTo("-000.50");
		assertThat(FixedDecimal.render(7.0, 2, 4, false)).isEqualTo("0007.00");
		// intDigits 0 still leaves one integer digit -- a bare ".50" is not a rendering
		// either format path has ever produced.
		assertThat(FixedDecimal.render(0.5, 2, 0, false)).isEqualTo("0.50");
	}

	@Test
	void roundsHalfToEvenLikeRound() {
		assertThat(FixedDecimal.render(0.25, 1, 1, false)).isEqualTo("0.2");
		assertThat(FixedDecimal.render(0.75, 1, 1, false)).isEqualTo("0.8");
		// 1.005 is below its decimal spelling in binary, so it rounds DOWN -- the same
		// answer the scale-and-round expansion gave.
		assertThat(FixedDecimal.render(1.005, 2, 1, false)).isEqualTo("1.00");
	}

	@Test
	void signsTheValueAndHonoursTheExplicitPlus() {
		assertThat(FixedDecimal.render(-1.005, 2, 1, false)).isEqualTo("-1.00");
		assertThat(FixedDecimal.render(3.14159, 2, 1, true)).isEqualTo("+3.14");
		assertThat(FixedDecimal.render(-3.14159, 2, 1, true)).isEqualTo("-3.14");
		// A negative that rounds to zero keeps its sign; -0.0 does not have one, since
		// the test is `value < 0.0`.
		assertThat(FixedDecimal.render(-0.4, 0, 1, false)).isEqualTo("-0");
		assertThat(FixedDecimal.render(-0.0, 2, 1, false)).isEqualTo("0.00");
		assertThat(FixedDecimal.render(0.0, 2, 1, false)).isEqualTo("0.00");
	}

	@Test
	void saturatesRatherThanWrappingPastTheSignedSixtyFourBitRange() {
		// The scaled magnitude is far past 2^63, so the conversion clamps to
		// Long.MAX_VALUE's digits -- which is what the `round`-based expansion produced
		// on every backend before the primitive existed, and what
		// i64.trunc_sat_f64_s produces now.
		assertThat(FixedDecimal.render(1e30, 2, 1, false)).isEqualTo("92233720368547758.07");
		assertThat(FixedDecimal.render(-1e30, 2, 1, false)).isEqualTo("-92233720368547758.07");
		assertThat(FixedDecimal.render(Double.POSITIVE_INFINITY, 0, 1, false)).isEqualTo("9223372036854775807");
	}

	@Test
	void aNanRendersAsZeroBecauseTheTruncationYieldsZero() {
		assertThat(FixedDecimal.render(Double.NaN, 2, 1, false)).isEqualTo("0.00");
	}

	@Test
	void clampsTheDigitCountsSoNoBackendCanBeAskedForAnUnboundedBuffer() {
		assertThat(FixedDecimal.render(1.5, -3, 1, false)).isEqualTo("2");
		assertThat(FixedDecimal.render(1.5, 1, -3, false)).isEqualTo("1.5");
		assertThat(FixedDecimal.render(1.5, FixedDecimal.MAX_DIGITS + 99, 1, false))
			.hasSize(FixedDecimal.MAX_DIGITS + 2);
	}

}
