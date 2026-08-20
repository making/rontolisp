package am.ik.gpu;

import java.util.Random;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The half of {@code am.ik.gpu} that needs a GPU on the machine, and therefore runs only
 * where there is one -- the same answer the library itself gives. {@link GpuDeclineTest}
 * covers what every machine must do; nothing here is allowed to be a requirement.
 *
 * <p>
 * Four things are pinned. (1) The CHECKED-IN PTX loads on this device and computes the
 * reference values: it is a generated artifact with no other test of its validity, and a
 * regeneration that silently produced the wrong kernel would pass every decline test. (2)
 * The products agree with a scalar Java reference -- exactly, on inputs that are exact at
 * the operand width, and to a tight relative tolerance on inputs that are not, which is
 * the precision contract. (3) Every decline condition still declines with a device
 * present, so the size threshold and the bounds checks are not accidentally bypassed by
 * having hardware. (4) A run of products does not move free device memory, which is the
 * leak assertion: three buffers are allocated per call and three are freed, on the
 * failure path too.
 */
@EnabledIf("am.ik.gpu.GpuTest#gpuIsAvailable")
class GpuTest {

	static boolean gpuIsAvailable() {
		return Gpu.available();
	}

	@Test
	void theCheckedInPtxLoadsAndTheKernelComputes() {
		assertThat(Gpu.description()).contains("sm_");
		int n = 64;
		double[] a = new double[n * n], b = new double[n * n];
		for (int i = 0; i < a.length; i++) {
			a[i] = (i % 7) - 3;
			b[i] = (i % 5) - 2;
		}
		double[] c = Gpu.multiply(a, 0, b, 0, n, n, n);
		assertThat(c).isNotNull().hasSize(n * n);
		// Small integers through a 64-long reduction are exact at both widths, so the
		// tiled kernel's reordering is invisible and this is an EQUALITY.
		assertThat(c).containsExactly(reference(a, 0, b, 0, n, n, n));
	}

	@Test
	void theSingleFloatKernelComputesTheSameExactValues() {
		int n = 64;
		float[] a = new float[n * n], b = new float[n * n];
		double[] ad = new double[n * n], bd = new double[n * n];
		for (int i = 0; i < a.length; i++) {
			ad[i] = (i % 7) - 3;
			bd[i] = (i % 5) - 2;
			a[i] = (float) ad[i];
			b[i] = (float) bd[i];
		}
		float[] c = Gpu.multiply(a, 0, b, 0, n, n, n);
		assertThat(c).isNotNull().hasSize(n * n);
		double[] expected = reference(ad, 0, bd, 0, n, n, n);
		for (int i = 0; i < c.length; i++) {
			assertThat((double) c[i]).isEqualTo(expected[i]);
		}
	}

	@Test
	void anInexactProductAgreesWithTheScalarOracleToTheWidthsOwnTolerance() {
		// The tiled walk reorders the reduction, so this is a tolerance and not an
		// equality -- and the tolerance is the WIDTH's, not the device's.
		int n = 128;
		Random random = new Random(20260820L);
		double[] a = new double[n * n], b = new double[n * n];
		float[] af = new float[n * n], bf = new float[n * n];
		for (int i = 0; i < a.length; i++) {
			a[i] = random.nextDouble() - 0.5;
			b[i] = random.nextDouble() - 0.5;
			af[i] = (float) a[i];
			bf[i] = (float) b[i];
		}
		double[] expected = reference(a, 0, b, 0, n, n, n);
		double scale = 0;
		for (double value : expected) {
			scale = Math.max(scale, Math.abs(value));
		}
		double[] c = Gpu.multiply(a, 0, b, 0, n, n, n);
		float[] cf = Gpu.multiply(af, 0, bf, 0, n, n, n);
		assertThat(c).isNotNull();
		assertThat(cf).isNotNull();
		double worst64 = 0, worst32 = 0;
		for (int i = 0; i < expected.length; i++) {
			worst64 = Math.max(worst64, Math.abs(c[i] - expected[i]) / scale);
			worst32 = Math.max(worst32, Math.abs(cf[i] - expected[i]) / scale);
		}
		assertThat(worst64).isLessThan(1e-14);
		assertThat(worst32).isLessThan(1e-6);
	}

	@Test
	void eachOperandIsReadFromItsOwnOffset() {
		// The compiled backends keep a [rank, dim..., data...] header inside the same
		// array as the data, so an intercepted product hands over an offset per operand.
		int n = 64;
		int headerA = 3, headerB = 7;
		double[] a = new double[headerA + n * n], b = new double[headerB + n * n];
		for (int i = 0; i < headerA; i++) {
			a[i] = Double.NaN;
		}
		for (int i = 0; i < headerB; i++) {
			b[i] = Double.NaN;
		}
		for (int i = 0; i < n * n; i++) {
			a[headerA + i] = (i % 7) - 3;
			b[headerB + i] = (i % 5) - 2;
		}
		double[] c = Gpu.multiply(a, headerA, b, headerB, n, n, n);
		assertThat(c).isNotNull().hasSize(n * n);
		// A NaN anywhere in the result would mean the header was read as data.
		assertThat(c).containsExactly(reference(a, headerA, b, headerB, n, n, n));
	}

	@Test
	void aRectangularProductUsesAllThreeDimensions() {
		// n, m and p distinct, and none a multiple of the 16x16 tile, so the kernel's
		// bounds guards are exercised rather than its happy path.
		int n = 70, m = 37, p = 101;
		double[] a = new double[n * m], b = new double[m * p];
		for (int i = 0; i < a.length; i++) {
			a[i] = (i % 9) - 4;
		}
		for (int i = 0; i < b.length; i++) {
			b[i] = (i % 6) - 2;
		}
		double[] c = Gpu.multiply(a, 0, b, 0, n, m, p);
		assertThat(c).isNotNull().hasSize(n * p);
		assertThat(c).containsExactly(reference(a, 0, b, 0, n, m, p));
	}

	@Test
	void anOperandTooBigForOneCriticalCopyIsSplitAndStillAgrees() {
		// Past the critical chunk size a copy is issued in several pieces, and the kernel
		// is awaited outside them; the split must not move a byte. 3072x3072 doubles is
		// 72 MB an operand, over the 64 MB chunk.
		int n = 3072;
		double[] a = new double[n * n], b = new double[n * n];
		for (int i = 0; i < a.length; i++) {
			a[i] = i % 3;
			b[i] = i % 2;
		}
		double[] c = Gpu.multiply(a, 0, b, 0, n, n, n);
		assertThat(c).isNotNull().hasSize(n * n);
		// Spot-check one row rather than the whole 9 M cells: the reference is a triple
		// loop and the point here is the transfer route, not the arithmetic. Every value
		// is a small integer, so the reordered reduction is still exact.
		for (int j = 0; j < 8; j++) {
			double expected = 0;
			for (int k = 0; k < n; k++) {
				expected += a[k] * b[k * n + j];
			}
			assertThat(c[j]).isEqualTo(expected);
		}
	}

	@Test
	void everyDeclineConditionStillDeclinesWithADevicePresent() {
		double[] a = new double[64 * 64], b = new double[64 * 64];
		assertThat(Gpu.worth(8, 8, 8)).isFalse();
		assertThat(Gpu.multiply(a, 0, b, 0, 8, 8, 8)).isNull();
		assertThat(Gpu.multiply(a, 0, b, 0, 128, 128, 128)).isNull();
		assertThat(Gpu.multiply(a, 1, b, 0, 64, 64, 64)).isNull();
		assertThat(Gpu.multiply(a, 0, b, 0, 64, 64, 0)).isNull();
	}

	@Test
	void aRunOfProductsFreesEveryBufferItAllocates() {
		CudaGemm gemm = Gpu.device();
		assertThat(gemm).isNotNull();
		int n = 256;
		double[] a = new double[n * n], b = new double[n * n], c = new double[n * n];
		// One call first, so the driver's pool has reached its working size and the
		// baseline is the steady state rather than the cold one.
		assertThat(gemm.gemm(a, 0, b, 0, c, n, n, n)).isTrue();
		long before = gemm.freeDeviceMemory();
		assertThat(before).isGreaterThan(0);
		for (int i = 0; i < 500; i++) {
			assertThat(gemm.gemm(a, 0, b, 0, c, n, n, n)).isTrue();
		}
		long after = gemm.freeDeviceMemory();
		// 500 products of three 512 KB buffers leak 750 MB if they leak at all; the slack
		// is for the rest of the machine, which is also using this device.
		assertThat(before - after).isLessThan(64L << 20);
	}

	@Test
	void theSameProductRepeatedIsTheSameAnswer() {
		int n = 96;
		Random random = new Random(7L);
		double[] a = new double[n * n], b = new double[n * n];
		for (int i = 0; i < a.length; i++) {
			a[i] = random.nextDouble();
			b[i] = random.nextDouble();
		}
		double[] first = Gpu.multiply(a, 0, b, 0, n, n, n);
		assertThat(first).isNotNull();
		for (int i = 0; i < 20; i++) {
			assertThat(Gpu.multiply(a, 0, b, 0, n, n, n)).containsExactly(first);
		}
	}

	/** The scalar row-by-column product the accelerated one has to agree with. */
	private static double[] reference(double[] a, int offsetA, double[] b, int offsetB, int n, int m, int p) {
		double[] c = new double[n * p];
		for (int i = 0; i < n; i++) {
			for (int k = 0; k < m; k++) {
				double left = a[offsetA + i * m + k];
				for (int j = 0; j < p; j++) {
					c[i * p + j] += left * b[offsetB + k * p + j];
				}
			}
		}
		return c;
	}

}
