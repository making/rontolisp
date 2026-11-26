package am.ik.rontolisp.runtime;

import java.util.stream.IntStream;

import am.ik.rontolisp.BFloat16;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The packed float-array handle a {@code :float-vector} / {@code :float-matrix}
 * {@code rontolisp:jvm-export} hands out ({@code .kb/jvm-export.md}). The compiled
 * boundary is exercised in {@code JvmExportTest}; this pins the handle's own contract —
 * the header layout it reads, the two points where a copy happens, the aliasing
 * everywhere else, and the {@code --gpu} residency seam.
 */
class RontoFloatArrayTest {

	@Test
	void ofCopiesOnceAndBuildsTheDimensionHeaderThePackedRepresentationCarries() {
		double[] source = { 3.0, 4.0 };
		RontoFloatArray handle = RontoFloatArray.of(source);
		// [rank, dim_0, e_0, e_1] -- the layout .kb/vec.md pins.
		assertThat((double[]) handle.packed()).containsExactly(1.0, 2.0, 3.0, 4.0);
		source[0] = 99.0;
		// of() copied: the caller's array is no longer connected to the handle.
		assertThat(handle.get(0)).isEqualTo(3.0);
		assertThat(handle.rank()).isEqualTo(1);
		assertThat(handle.dims()).containsExactly(2);
		assertThat(handle.size()).isEqualTo(2);
		assertThat(handle.width()).isEqualTo(RontoFloatArray.Width.DOUBLE_FLOAT);
	}

	@Test
	void aSingleFloatHandleIsTheSameTypeAtTheOtherWidth() {
		RontoFloatArray handle = RontoFloatArray.of(new float[] { 0.5f, 1.5f, 2.5f });
		assertThat(handle.width()).isEqualTo(RontoFloatArray.Width.SINGLE_FLOAT);
		assertThat(handle.width().lispName()).isEqualTo("single-float");
		assertThat(handle.packed()).isInstanceOf(float[].class);
		assertThat(handle.toArray()).containsExactly(0.5, 1.5, 2.5);
		handle.set(0, 4.25);
		assertThat(handle.get(0)).isEqualTo(4.25);
		assertThat(handle.toFloatArray()).containsExactly(4.25f, 1.5f, 2.5f);
	}

	@Test
	void aBfloat16HandleIsTheSameTypeAtTheThirdWidth() {
		// The elements are BIT PATTERNS: 0x3f80 is 1.0, 0x4020 is 2.5, 0x4060 is 3.5.
		RontoFloatArray handle = RontoFloatArray.of(new short[] { (short) 0x3f80, (short) 0x4020, (short) 0x4060 });
		assertThat(handle.width()).isEqualTo(RontoFloatArray.Width.BFLOAT16);
		assertThat(handle.width().lispName()).isEqualTo("bfloat16");
		// [rank, dim_0 hi, dim_0 lo, e_0, e_1, e_2] -- TWO header slots per dimension,
		// because a short cannot hold an int dimension (.kb/bfloat16.md).
		assertThat((short[]) handle.packed()).containsExactly((short) 1, (short) 0, (short) 3, (short) 0x3f80,
				(short) 0x4020, (short) 0x4060);
		assertThat(handle.rank()).isEqualTo(1);
		assertThat(handle.dims()).containsExactly(3);
		assertThat(handle.size()).isEqualTo(3);
		assertThat(handle.toArray()).containsExactly(1.0, 2.5, 3.5);
		assertThat(handle.toFloatArray()).containsExactly(1.0f, 2.5f, 3.5f);
		assertThat(handle.toShortArray()).containsExactly((short) 0x3f80, (short) 0x4020, (short) 0x4060);
		assertThat(handle).hasToString("RontoFloatArray[bfloat16 [3]]");
		// A store narrows to nearest even, exactly as (setf (aref ...)) does.
		handle.set(0, 0.1);
		assertThat(handle.get(0)).isEqualTo(0.10009765625);
		assertThat(handle.toShortArray()[0]).isEqualTo((short) BFloat16.bits(0.1));
	}

	@Test
	void aBfloat16DimensionIsReassembledFromItsTwoHeaderSlots() {
		// 40000 does not fit a short; a one-slot header would answer -25536 and every
		// index past it would read the wrong element. This is the regression the layout
		// exists for, seen from the handle side.
		RontoFloatArray vector = RontoFloatArray.of(new short[40000]);
		assertThat(vector.dims()).containsExactly(40000);
		assertThat(vector.dim(0)).isEqualTo(40000);
		assertThat(vector.size()).isEqualTo(40000);
		vector.set(39999, 10.0);
		assertThat(vector.get(39999)).isEqualTo(10.0);
		assertThat(((short[]) vector.packed())[1 + 2 + 39999]).isEqualTo((short) 0x4120);
		RontoFloatArray matrix = RontoFloatArray.of(new short[80000], 2, 40000);
		assertThat(matrix.rank()).isEqualTo(2);
		assertThat(matrix.dims()).containsExactly(2, 40000);
		assertThat(matrix.dim(1)).isEqualTo(40000);
		matrix.set(1, 39999, 10.0);
		assertThat(matrix.get(79999)).isEqualTo(10.0);
	}

	@Test
	void everyBfloat16PatternWidensExactlyAsTheAuthorityDoes() {
		// All 65536 patterns, against am.ik.rontolisp.BFloat16 -- which the runtime
		// package may not import, so the handle carries a THIRD copy of the arithmetic
		// and this is what keeps it a copy rather than a variant.
		short[] every = new short[65536];
		for (int pattern = 0; pattern < 65536; pattern++) {
			every[pattern] = (short) pattern;
		}
		RontoFloatArray handle = RontoFloatArray.of(every);
		double[] widened = handle.toArray();
		float[] widenedToFloat = handle.toFloatArray();
		short[] back = handle.toShortArray();
		for (int pattern = 0; pattern < 65536; pattern++) {
			assertThat(Double.doubleToRawLongBits(widened[pattern])).as("toArray of pattern %04x", pattern)
				.isEqualTo(Double.doubleToRawLongBits(BFloat16.value(pattern)));
			assertThat(Double.doubleToRawLongBits(handle.get(pattern))).as("get of pattern %04x", pattern)
				.isEqualTo(Double.doubleToRawLongBits(BFloat16.value(pattern)));
			// The f32 widening is the SHIFT ALONE and never goes by way of a double,
			// which would quiet 126 of the 65536 patterns.
			assertThat(Float.floatToRawIntBits(widenedToFloat[pattern])).as("toFloatArray of pattern %04x", pattern)
				.isEqualTo(pattern << 16);
			assertThat(back[pattern]).as("toShortArray of pattern %04x", pattern).isEqualTo((short) pattern);
		}
	}

	@Test
	void everyBfloat16PatternSurvivesASetOfItsOwnWidenedValue() {
		// The round trip the widening's exactness makes possible: store what a pattern
		// widens to and the pattern comes back, sNaN payloads included.
		RontoFloatArray handle = RontoFloatArray.of(new short[1]);
		for (int pattern = 0; pattern < 65536; pattern++) {
			handle.set(0, BFloat16.value(pattern));
			assertThat(((short[]) handle.packed())[3]).as("pattern %04x", pattern).isEqualTo((short) pattern);
		}
	}

	@Test
	void theNarrowingMatchesTheAuthorityOnEveryF32PatternAndEveryDoubleNaN() {
		// Every copy of this arithmetic that has broken broke in the narrow/NaN
		// direction, so the sweep is exhaustive rather than a sample. Both storage arms
		// are covered: toShortArray from float[] takes BFloat16.bits(float), from
		// double[] the double overload -- and a float must NOT cross a double on the way
		// there, which is why they are separate arms at all.
		int chunkBits = 18;
		int elements = 1 << chunkBits;
		long mismatches = IntStream.range(0, 1 << (32 - chunkBits)).parallel().mapToLong(chunk -> {
			double[] packedDoubles = new double[2 + elements];
			float[] packedFloats = new float[2 + elements];
			packedDoubles[0] = 1.0;
			packedDoubles[1] = elements;
			packedFloats[0] = 1.0f;
			packedFloats[1] = elements;
			int base = chunk << chunkBits;
			for (int k = 0; k < elements; k++) {
				float value = Float.intBitsToFloat(base + k);
				packedDoubles[2 + k] = value;
				packedFloats[2 + k] = value;
			}
			short[] viaDouble = RontoFloatArray.wrap(packedDoubles).toShortArray();
			short[] viaFloat = RontoFloatArray.wrap(packedFloats).toShortArray();
			long bad = 0;
			for (int k = 0; k < elements; k++) {
				float value = Float.intBitsToFloat(base + k);
				if ((viaDouble[k] & 0xffff) != BFloat16.bits((double) value)) {
					bad++;
				}
				if ((viaFloat[k] & 0xffff) != BFloat16.bits(value)) {
					bad++;
				}
			}
			return bad;
		}).sum();
		assertThat(mismatches).as("f32 patterns whose narrowing differs from BFloat16.bits").isZero();
		// The double NaN arm the f32 sweep cannot reach: a widened f32 NaN arrives
		// quieted, so the sNaN payloads only exist as doubles built by hand. Same shape
		// as JvmBFloat16ArrayTest's sweep of the emitted copy.
		RontoFloatArray one = RontoFloatArray.of(new short[1]);
		long nanMismatches = 0;
		for (int sign = 0; sign < 2; sign++) {
			for (int top = 0; top < 128; top++) {
				for (long low : new long[] { 0L, 1L, (1L << 45) - 1, 0x1234_5678_9abL, 1L << 44 }) {
					long raw = ((long) sign << 63) | 0x7ff0000000000000L | ((long) top << 45) | low;
					double value = Double.longBitsToDouble(raw);
					if (!Double.isNaN(value)) {
						continue; // top == 0 && low == 0 is an infinity, covered above
					}
					one.set(0, value);
					if ((((short[]) one.packed())[3] & 0xffff) != BFloat16.bits(value)) {
						nanMismatches++;
					}
				}
			}
		}
		assertThat(nanMismatches).as("double NaNs whose narrowing differs from BFloat16.bits").isZero();
	}

	@Test
	void theWidthEnumHasThreeMembersAndNotTheTwoItShippedWith() {
		// .kb/jvm-export.md says a caller must not assume the member count; this is the
		// assertion that fails when a fourth width arrives and the handle has not
		// followed the representation.
		assertThat(RontoFloatArray.Width.values()).extracting(RontoFloatArray.Width::lispName)
			.containsExactly("double-float", "single-float", "bfloat16");
	}

	@Test
	void aRankTwoHandleIsTheSameClassWithATwoDimensionHeader() {
		RontoFloatArray matrix = RontoFloatArray.of(new double[] { 1, 2, 3, 4, 5, 6 }, 2, 3);
		assertThat(matrix.rank()).isEqualTo(2);
		assertThat(matrix.dims()).containsExactly(2, 3);
		assertThat(matrix.dim(1)).isEqualTo(3);
		assertThat(matrix.size()).isEqualTo(6);
		assertThat(matrix.get(1, 2)).isEqualTo(6.0);
		matrix.set(0, 1, 20.0);
		assertThat(matrix.get(1)).isEqualTo(20.0);
		assertThat(matrix.toArray()).containsExactly(1, 20, 3, 4, 5, 6);
	}

	@Test
	void wrapAliasesTheVeryArrayItIsGiven() {
		double[] packed = { 1.0, 3.0, 7.0, 8.0, 9.0 };
		RontoFloatArray handle = RontoFloatArray.wrap(packed);
		assertThat(handle.packed()).isSameAs(packed);
		handle.set(2, 42.0);
		assertThat(packed[4]).isEqualTo(42.0);
		packed[2] = 1.0;
		assertThat(handle.get(0)).isEqualTo(1.0);
	}

	@Test
	void zerosBuildsTheDestinationADestinationPassingExportWritesInto() {
		RontoFloatArray destination = RontoFloatArray.zeros(RontoFloatArray.Width.SINGLE_FLOAT, 2, 2);
		assertThat(destination.width()).isEqualTo(RontoFloatArray.Width.SINGLE_FLOAT);
		assertThat(destination.dims()).containsExactly(2, 2);
		assertThat(destination.toArray()).containsExactly(0.0, 0.0, 0.0, 0.0);
		RontoFloatArray bf16 = RontoFloatArray.zeros(RontoFloatArray.Width.BFLOAT16, 2, 2);
		assertThat(bf16.width()).isEqualTo(RontoFloatArray.Width.BFLOAT16);
		assertThat(bf16.dims()).containsExactly(2, 2);
		assertThat(bf16.toArray()).containsExactly(0.0, 0.0, 0.0, 0.0);
		// [rank, 2 hi, 2 lo, 2 hi, 2 lo, e_0..e_3] -- five header slots at rank 2.
		assertThat((short[]) bf16.packed()).hasSize(9);
	}

	@Test
	void aPlainJavaArrayIsRefusedRatherThanReadAsAPackedOne() {
		// The failure mode the whole boundary type exists to stop: new double[]{3, 4} is
		// NOT a packed float array, and reading it as one answers a wrong number.
		assertThatThrownBy(() -> RontoFloatArray.wrap("nope")).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("not a packed float array");
		assertThatThrownBy(() -> RontoFloatArray.wrap(new double[0])).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> RontoFloatArray.wrap(new double[] { 0.0 }))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("rank 0");
		assertThatThrownBy(() -> RontoFloatArray.of(new double[] { 1, 2, 3 }, 2, 2))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("needs 4 elements");
		assertThatThrownBy(() -> RontoFloatArray.of(new double[] { 1, 2 }).get(2))
			.isInstanceOf(IndexOutOfBoundsException.class);
	}

	/**
	 * The {@code --gpu} seam, exercised without a device: a lazy result's host array is
	 * the HEADER ALONE and the elements live in a backing the residency guard answers
	 * ({@code .kb/gpu.md}, "Lazy results"). Every host read of a handle must therefore
	 * read what the guard answers, and every host write must land on it — which is what
	 * {@link GpuOwner} stands in for here.
	 */
	@Test
	void aHostReadGoesThroughTheOwnerClassResidencyGuard() {
		double[] stub = { 1.0, 3.0 };
		GpuOwner.backing = new double[] { 1.0, 3.0, 10.0, 20.0, 30.0 };
		GpuOwner.materialized = 0;
		RontoFloatArray handle = RontoBoundary.floatArrayResult(stub, 1, GpuOwner.class, "test ");
		// The header alone answers rank/dims/size: it is written at allocation and is
		// never the stale half.
		assertThat(handle.packed()).isSameAs(stub);
		assertThat(handle.size()).isEqualTo(3);
		assertThat(GpuOwner.materialized).isZero();
		// ... and the ELEMENTS come home only now, when the caller actually reads one.
		assertThat(handle.toArray()).containsExactly(10.0, 20.0, 30.0);
		assertThat(handle.get(1)).isEqualTo(20.0);
		assertThat(GpuOwner.materialized).isEqualTo(2);
		handle.set(1, 21.0);
		assertThat(GpuOwner.written).isEqualTo(1);
		assertThat(GpuOwner.backing[3]).isEqualTo(21.0);
	}

	@Test
	void aClassWithNoResidencyGuardsCostsTheHandleNothing() {
		RontoFloatArray handle = RontoBoundary.floatArrayResult(new double[] { 1.0, 2.0, 5.0, 6.0 }, 1,
				RontoFloatArrayTest.class, "test ");
		assertThat(handle.toArray()).containsExactly(5.0, 6.0);
	}

	@Test
	@SuppressWarnings("NullAway") // the seam is called from bytecode, which can hand it
									// null
	void theBoundarySeamRefusesAWrongRankAndANonArrayResult() {
		RontoFloatArray vector = RontoFloatArray.of(new double[] { 1.0 });
		assertThatThrownBy(() -> RontoBoundary.floatArrayArgument(vector, 2, RontoFloatArrayTest.class, "here "))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("here expects rank 2, got rank 1");
		assertThatThrownBy(() -> RontoBoundary.floatArrayArgument(null, 1, RontoFloatArrayTest.class, "here "))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("here must not be null");
		assertThatThrownBy(() -> RontoBoundary.floatArrayResult(42L, 1, RontoFloatArrayTest.class, "here "))
			.isInstanceOf(ClassCastException.class)
			.hasMessageContaining("not a packed float array");
	}

	/** A stand-in for a {@code --gpu} compiled class: the two private guards it emits. */
	static final class GpuOwner {

		static double[] backing = new double[0];

		static int materialized;

		static int written;

		private static Object _gpuMaterialize(Object array) {
			materialized++;
			return backing;
		}

		private static Object _gpuWritten(Object array) {
			written++;
			return backing;
		}

	}

}
