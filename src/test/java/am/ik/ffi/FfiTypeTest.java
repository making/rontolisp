package am.ik.ffi;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The pure half of the type model: the CFFI designator names resolve to the fixed-width
 * scalars (LP64 aliases included), and a struct lays out exactly as a C compiler would --
 * padding between members, tail padding, nesting. No native access anywhere.
 */
class FfiTypeTest {

	@Test
	void theCffiKeywordNamesResolveToTheFixedWidthScalars() {
		assertThat(FfiType.of("char")).isEqualTo(FfiType.Scalar.INT8);
		assertThat(FfiType.of("uchar")).isEqualTo(FfiType.Scalar.UINT8);
		assertThat(FfiType.of("short")).isEqualTo(FfiType.Scalar.INT16);
		assertThat(FfiType.of("ushort")).isEqualTo(FfiType.Scalar.UINT16);
		assertThat(FfiType.of("int")).isEqualTo(FfiType.Scalar.INT32);
		assertThat(FfiType.of("uint")).isEqualTo(FfiType.Scalar.UINT32);
		// LP64: long, llong and long-long are all 8 bytes.
		assertThat(FfiType.of("long")).isEqualTo(FfiType.Scalar.INT64);
		assertThat(FfiType.of("llong")).isEqualTo(FfiType.Scalar.INT64);
		assertThat(FfiType.of("long-long")).isEqualTo(FfiType.Scalar.INT64);
		assertThat(FfiType.of("ulong")).isEqualTo(FfiType.Scalar.UINT64);
		assertThat(FfiType.of("ullong")).isEqualTo(FfiType.Scalar.UINT64);
		assertThat(FfiType.of("UINT64")).isEqualTo(FfiType.Scalar.UINT64);
		assertThat(FfiType.of("pointer")).isEqualTo(FfiType.Scalar.POINTER);
		assertThat(FfiType.of("string")).isEqualTo(FfiType.Scalar.STRING);
		assertThat(FfiType.of("void")).isEqualTo(FfiType.Scalar.VOID);
		assertThatThrownBy(() -> FfiType.of("flonum")).isInstanceOf(FfiException.class)
			.hasMessage("no such foreign type: :flonum");
	}

	@Test
	void aStructLaysOutWithTheCPaddingRule() {
		FfiType.Struct charInt = new FfiType.Struct(List.of(FfiType.Scalar.INT8, FfiType.Scalar.INT32));
		assertThat(charInt.size()).isEqualTo(8);
		assertThat(charInt.align()).isEqualTo(4);
		FfiType.Struct packed = new FfiType.Struct(List.of(FfiType.Scalar.INT8, FfiType.Scalar.INT8));
		assertThat(packed.size()).isEqualTo(2);
		assertThat(packed.align()).isEqualTo(1);
		FfiType.Struct timeval = new FfiType.Struct(List.of(FfiType.Scalar.INT64, FfiType.Scalar.INT64));
		assertThat(timeval.size()).isEqualTo(16);
		FfiType.Struct nested = new FfiType.Struct(List.of(FfiType.Scalar.INT8, charInt));
		assertThat(nested.size()).isEqualTo(12);
		assertThat(nested.align()).isEqualTo(4);
		assertThat(nested.spelling()).isEqualTo("(:struct :int8 (:struct :int8 :int32))");
	}

	@Test
	void aStructRefusesTheMembersThatCannotLiveInOne() {
		assertThatThrownBy(() -> new FfiType.Struct(List.of())).isInstanceOf(FfiException.class)
			.hasMessageContaining("at least one member");
		assertThatThrownBy(() -> new FfiType.Struct(List.of(FfiType.Scalar.VOID))).isInstanceOf(FfiException.class)
			.hasMessageContaining(":void");
		assertThatThrownBy(() -> new FfiType.Struct(List.of(FfiType.Scalar.STRING))).isInstanceOf(FfiException.class)
			.hasMessageContaining(":string");
	}

}
