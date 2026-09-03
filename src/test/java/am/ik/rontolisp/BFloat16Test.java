package am.ik.rontolisp;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The bfloat16 conversion pair and the text of a bfloat16 element. Every claim here is
 * over ALL 65536 patterns, because the width's whole job is to be exact about which bits
 * survive.
 */
class BFloat16Test {

	@Test
	void wideningIsExactAndTotalOverEveryPattern() {
		for (int pattern = 0; pattern < 65536; pattern++) {
			assertThat(BFloat16.bits(BFloat16.value(pattern))).as("pattern %04x", pattern).isEqualTo(pattern);
		}
	}

	@Test
	void wideningIsTheTopHalfOfAnF32ForEveryNonNanPattern() {
		for (int pattern = 0; pattern < 65536; pattern++) {
			float widened = Float.intBitsToFloat(pattern << 16);
			if (Float.isNaN(widened)) {
				// A NaN carries its payload across by hand; the f32 comparison below
				// would only be asking whether the hardware quiets it.
				assertThat(Double.isNaN(BFloat16.value(pattern))).as("pattern %04x is a NaN", pattern).isTrue();
				continue;
			}
			assertThat(BFloat16.value(pattern)).as("pattern %04x", pattern).isEqualTo((double) widened);
		}
	}

	@Test
	void narrowingRoundsToNearestEven() {
		// A tie whose surviving low bit is even rounds DOWN, one whose low bit is odd
		// rounds UP. A truncating >>> 16 answers the lower neighbour for both.
		assertThat(BFloat16.bits(Float.intBitsToFloat(0x3f808000))).isEqualTo(0x3f80);
		assertThat(BFloat16.bits(Float.intBitsToFloat(0x3f818000))).isEqualTo(0x3f82);
		// Either side of those ties: below rounds down, above rounds up, both times.
		assertThat(BFloat16.bits(Float.intBitsToFloat(0x3f807fff))).isEqualTo(0x3f80);
		assertThat(BFloat16.bits(Float.intBitsToFloat(0x3f808001))).isEqualTo(0x3f81);
		assertThat(BFloat16.bits(Float.intBitsToFloat(0x3f817fff))).isEqualTo(0x3f81);
		assertThat(BFloat16.bits(Float.intBitsToFloat(0x3f818001))).isEqualTo(0x3f82);
	}

	@Test
	void narrowingKeepsZerosInfinitiesAndSubnormals() {
		assertThat(BFloat16.bits(0.0)).isEqualTo(0x0000);
		assertThat(BFloat16.bits(-0.0)).isEqualTo(0x8000);
		assertThat(BFloat16.bits(Double.POSITIVE_INFINITY)).isEqualTo(0x7f80);
		assertThat(BFloat16.bits(Double.NEGATIVE_INFINITY)).isEqualTo(0xff80);
		// Beyond the widest bfloat16 the rounding overflows into an infinity.
		assertThat(BFloat16.bits(1.0e39)).isEqualTo(0x7f80);
		// The smallest subnormal and the largest finite survive both directions.
		assertThat(BFloat16.value(0x0001)).isEqualTo(Float.intBitsToFloat(0x00010000));
		assertThat(BFloat16.value(0x7f7f)).isEqualTo(Float.intBitsToFloat(0x7f7f0000));
	}

	@Test
	void narrowingNeverTurnsANanIntoAnInfinity() {
		assertThat(Double.isNaN(BFloat16.value(0x7f81))).isTrue();
		// A payload whose top seven bits are all zero would read back as an infinity,
		// so it becomes the smallest NaN instead.
		double lowPayloadOnly = Double.longBitsToDouble(0x7ff0000000000001L);
		assertThat(BFloat16.bits(lowPayloadOnly)).isEqualTo(0x7f81);
		assertThat(BFloat16.bits(Double.NaN)).isEqualTo(0x7fc0);
	}

	@Test
	void narrowingKeepsASignallingNanSignalling() {
		// The hardware's own f64 -> f32 narrowing quiets these 126 patterns; carrying
		// the payload across by hand is what keeps the round trip exact.
		for (int payload = 1; payload < 0x40; payload++) {
			int pattern = 0x7f80 | payload;
			assertThat(BFloat16.bits(BFloat16.value(pattern))).as("sNaN %04x", pattern).isEqualTo(pattern);
			assertThat(BFloat16.bits(BFloat16.value(pattern | 0x8000))).as("-sNaN %04x", pattern)
				.isEqualTo(pattern | 0x8000);
		}
	}

	@Test
	void textIsTheShortestDecimalThatReadsBackAsTheSamePattern() {
		for (int pattern = 0; pattern < 65536; pattern++) {
			double value = BFloat16.value(pattern);
			String text = FloatText.bfloat16Text(value);
			if (Double.isNaN(value)) {
				assertThat(text).as("pattern %04x", pattern).isEqualTo("NaN");
				continue;
			}
			assertThat(BFloat16.bits(Double.parseDouble(text))).as("pattern %04x printed %s", pattern, text)
				.isEqualTo(pattern);
		}
	}

	@Test
	void textPrintsTheBfloat16DigitsRatherThanTheWidenedF32Digits() {
		// 0.1 is not a bfloat16, but the bfloat16 nearest it prints as 0.1 because
		// that is what reads back; singleText would show all of the widened f32.
		assertThat(FloatText.bfloat16Text(BFloat16.value(BFloat16.bits(0.1)))).isEqualTo("0.1");
		assertThat(FloatText.singleText((float) BFloat16.value(BFloat16.bits(0.1)))).isEqualTo("0.100097656");
		assertThat(FloatText.bfloat16Text(BFloat16.value(BFloat16.bits(3.14159)))).isEqualTo("3.14");
		assertThat(FloatText.bfloat16Text(0.0)).isEqualTo("0.0");
		assertThat(FloatText.bfloat16Text(-0.0)).isEqualTo("-0.0");
		assertThat(FloatText.bfloat16Text(Double.POSITIVE_INFINITY)).isEqualTo("Infinity");
		// The exponent marker is the lowercase one Common Lisp prints, as elsewhere.
		assertThat(FloatText.bfloat16Text(BFloat16.value(BFloat16.bits(1.0e38)))).isEqualTo("1.0e38");
	}

}
