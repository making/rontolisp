package am.ik.rontolisp.runtime;

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
	 * ({@code .kb/gpu.md}, "A lazy result allocates no host array"). Every host read of a
	 * handle must therefore read what the guard answers, and every host write must land
	 * on it — which is what {@link GpuOwner} stands in for here.
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
