package am.ik.rontolisp.macro;

import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class StreamElementTypeTest {

	// The widened type each specifier opens as, measured against sbcl 2.x
	// (stream-element-type of a fresh output stream); NONE = refused.
	@ParameterizedTest
	@CsvSource(delimiter = '|', value = { "character|CHARACTER", "base-char|CHARACTER", ":default|CHARACTER",
			"(unsigned-byte 1)|(UNSIGNED-BYTE 8)", "(unsigned-byte 7)|(UNSIGNED-BYTE 8)",
			"(unsigned-byte 8)|(UNSIGNED-BYTE 8)", "(unsigned-byte 9)|(UNSIGNED-BYTE 16)",
			"(unsigned-byte 17)|(UNSIGNED-BYTE 32)", "(unsigned-byte 33)|(UNSIGNED-BYTE 64)",
			"(unsigned-byte 64)|(UNSIGNED-BYTE 64)", "(unsigned-byte 100)|(UNSIGNED-BYTE 104)",
			"(signed-byte 5)|(SIGNED-BYTE 8)", "(signed-byte 16)|(SIGNED-BYTE 16)", "(signed-byte 33)|(SIGNED-BYTE 64)",
			"bit|(UNSIGNED-BYTE 8)", "unsigned-byte|(UNSIGNED-BYTE 8)", "signed-byte|(SIGNED-BYTE 8)",
			"(unsigned-byte *)|(UNSIGNED-BYTE 8)", "(integer 0 1)|(UNSIGNED-BYTE 8)",
			"(integer 100 200)|(UNSIGNED-BYTE 8)", "(integer -5 5)|(SIGNED-BYTE 8)",
			"(integer 0 (256))|(UNSIGNED-BYTE 8)", "(integer 0 256)|(UNSIGNED-BYTE 16)", "(mod 10)|(UNSIGNED-BYTE 8)",
			"(or (integer 0 1) (integer 100 200))|(UNSIGNED-BYTE 8)", "integer|NONE", "(integer 0 *)|NONE",
			"single-float|NONE", "(integer 5 4)|NONE", "(unsigned-byte 0)|NONE" })
	void classifiesLikeSbcl(String source, String expected) {
		StreamElementType type = StreamElementType.of(LispReader.readFromString(source));
		assertThat(type == null ? "NONE" : type.spec().print()).isEqualTo(expected);
	}

}
