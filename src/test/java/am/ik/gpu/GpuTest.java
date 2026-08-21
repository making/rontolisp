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
 * Five things are pinned. (1) The CHECKED-IN PTX loads on this device and computes the
 * reference values: it is a generated artifact with no other test of its validity, and a
 * regeneration that silently produced the wrong kernel would pass every decline test. (2)
 * The products agree with a scalar Java reference -- exactly, on inputs that are exact at
 * the operand width, and to a tight relative tolerance on inputs that are not, which is
 * the precision contract. (3) BOTH allocator routes compute, because the fallback one is
 * unreachable on a machine whose driver has a memory pool and is otherwise never executed
 * anywhere. (4) Every decline condition still declines with a device present, so the size
 * threshold and the bounds checks are not accidentally bypassed by having hardware. (5)
 * Neither a run of successful products NOR a run of failing ones moves free device
 * memory, which is the leak assertion -- and the failing half is the one that was wrong
 * first, so it is asserted rather than assumed.
 *
 * <h2>Every shape here is sized off {@link Gpu#minWork()}</h2>
 *
 * The size threshold is a property of the MACHINE, not of the build: a driver without a
 * usable stream-ordered allocator has an eleven-times-higher floor and declines up to 16x
 * further up. A test that hard-codes a shape chosen for one of those thresholds turns the
 * whole class red on a machine with the other, and reads as a kernel regression when it
 * is nothing of the sort.
 */
@EnabledIf("am.ik.gpu.GpuTest#gpuIsAvailable")
class GpuTest {

	static boolean gpuIsAvailable() {
		return Gpu.available();
	}

	/**
	 * The smallest square product this machine will actually accept, times a safety
	 * factor so that a shape derived from it is not sitting on the threshold.
	 */
	private static int square() {
		int n = (int) Math.ceil(Math.cbrt((double) Gpu.minWork()));
		return Math.max(64, roundUpToTile(n + n / 4));
	}

	private static int roundUpToTile(int n) {
		return (n + 15) / 16 * 16;
	}

	@Test
	void theCheckedInPtxLoadsAndTheKernelComputes() {
		assertThat(Gpu.description()).contains("sm_");
		int n = square();
		double[] a = new double[n * n], b = new double[n * n];
		for (int i = 0; i < a.length; i++) {
			a[i] = (i % 7) - 3;
			b[i] = (i % 5) - 2;
		}
		double[] c = Gpu.multiply(a, 0, b, 0, n, n, n);
		assertThat(c).isNotNull().hasSize(n * n);
		// Small integers through the reduction are exact at both widths, so the tiled
		// kernel's reordering is invisible and this is an EQUALITY.
		assertThat(c).containsExactly(reference(a, 0, b, 0, n, n, n));
	}

	@Test
	void theSingleFloatKernelComputesTheSameExactValues() {
		int n = square();
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
	void bothAllocatorRoutesComputeTheSameProduct() {
		// cuMemAllocAsync is 0.7 us a pair and cuMemAlloc is 126, which is why the pooled
		// route exists -- but a driver older than CUDA 11.2, or a device without memory
		// pools, takes the other one, and on this machine it would otherwise never run at
		// all. Same shapes, same answers, and the threshold in force is the pooled one
		// either way because the switch is below Gpu.
		CudaGemm gemm = Gpu.device();
		assertThat(gemm).isNotNull();
		int n = square();
		double[] a = new double[n * n], b = new double[n * n];
		float[] af = new float[n * n], bf = new float[n * n];
		for (int i = 0; i < a.length; i++) {
			a[i] = (i % 7) - 3;
			b[i] = (i % 5) - 2;
			af[i] = (float) a[i];
			bf[i] = (float) b[i];
		}
		double[] asProbed = new double[n * n], flipped = new double[n * n];
		float[] asProbedF = new float[n * n], flippedF = new float[n * n];
		boolean probed = gemm.pooled();
		assertThat(gemm.gemm(a, 0, b, 0, asProbed, 0, n, n, n)).isTrue();
		assertThat(gemm.gemmF(af, 0, bf, 0, asProbedF, 0, n, n, n)).isTrue();
		boolean previous = gemm.setPooledAllocation(!probed);
		try {
			assertThat(previous).isEqualTo(probed);
			// False either way, and for two different reasons: a machine whose driver HAS
			// a pool has just been switched off it, and one whose driver has none cannot
			// be switched ONTO it. The second runs the same route twice, which is still
			// the right assertion for that machine.
			assertThat(gemm.pooled()).isFalse();
			assertThat(gemm.gemm(a, 0, b, 0, flipped, 0, n, n, n)).isTrue();
			assertThat(gemm.gemmF(af, 0, bf, 0, flippedF, 0, n, n, n)).isTrue();
		}
		finally {
			gemm.setPooledAllocation(previous);
		}
		assertThat(gemm.pooled()).isEqualTo(probed);
		assertThat(flipped).containsExactly(asProbed);
		assertThat(flippedF).containsExactly(asProbedF);
		assertThat(asProbed).containsExactly(reference(a, 0, b, 0, n, n, n));
	}

	@Test
	void anInexactProductAgreesWithTheScalarOracleToTheWidthsOwnTolerance() {
		// The tiled walk reorders the reduction, so this is a tolerance and not an
		// equality -- and the tolerance is the WIDTH's, not the device's.
		int n = 2 * square();
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
	void everyOperandIncludingTheResultIsReadFromItsOwnOffset() {
		// The compiled backends keep a [rank, dim..., data...] header inside the same
		// array as the data, so an intercepted product hands over an offset per operand
		// AND writes into the array the caller has already shaped.
		int n = square();
		int headerA = 3, headerB = 7, headerOut = 4;
		double[] a = new double[headerA + n * n], b = new double[headerB + n * n];
		double[] out = new double[headerOut + n * n];
		for (int i = 0; i < headerA; i++) {
			a[i] = Double.NaN;
		}
		for (int i = 0; i < headerB; i++) {
			b[i] = Double.NaN;
		}
		for (int i = 0; i < headerOut; i++) {
			out[i] = i + 0.5;
		}
		for (int i = 0; i < n * n; i++) {
			a[headerA + i] = (i % 7) - 3;
			b[headerB + i] = (i % 5) - 2;
		}
		assertThat(Gpu.multiply(a, headerA, b, headerB, out, headerOut, n, n, n)).isTrue();
		double[] expected = reference(a, headerA, b, headerB, n, n, n);
		// A NaN anywhere in the result would mean an operand's header was read as data.
		for (int i = 0; i < n * n; i++) {
			assertThat(out[headerOut + i]).isEqualTo(expected[i]);
		}
		// And the result's own header is where the caller left it.
		for (int i = 0; i < headerOut; i++) {
			assertThat(out[i]).isEqualTo(i + 0.5);
		}
	}

	@Test
	void aRectangularProductUsesAllThreeDimensions() {
		// n, m and p distinct, none a multiple of the 16x16 tile so the kernel's bounds
		// guards are exercised rather than its happy path, and all three above whatever
		// threshold is in force on this machine.
		int base = square();
		int n = base + 6, m = base - 11, p = base + 37;
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
		int n = square();
		double[] a = new double[n * n], b = new double[n * n], out = new double[n * n];
		assertThat(Gpu.worth(8, 8, 8)).isFalse();
		assertThat(Gpu.multiply(a, 0, b, 0, 8, 8, 8)).isNull();
		assertThat(Gpu.multiply(a, 0, b, 0, 4 * n, 4 * n, 4 * n)).isNull();
		assertThat(Gpu.multiply(a, 1, b, 0, n, n, n)).isNull();
		assertThat(Gpu.multiply(a, 0, b, 0, n, n, 0)).isNull();
		// The out-taking form declines on the same conditions, plus its own.
		assertThat(Gpu.multiply(a, 0, b, 0, out, 1, n, n, n)).isFalse();
		assertThat(Gpu.multiply(a, 0, b, 0, out, -1, n, n, n)).isFalse();
		assertThat(Gpu.multiply(a, 0, b, 0, new double[n], 0, n, n, n)).isFalse();
	}

	@Test
	void aRunOfSuccessfulProductsFreesEveryBufferItAllocates() {
		CudaGemm gemm = Gpu.device();
		assertThat(gemm).isNotNull();
		int n = 256;
		double[] a = new double[n * n], b = new double[n * n], c = new double[n * n];
		// One call first, so the driver's pool has reached its working size and the
		// baseline is the steady state rather than the cold one.
		assertThat(gemm.gemm(a, 0, b, 0, c, 0, n, n, n)).isTrue();
		long before = gemm.freeDeviceMemory();
		assertThat(before).isGreaterThan(0);
		for (int i = 0; i < 1000; i++) {
			assertThat(gemm.gemm(a, 0, b, 0, c, 0, n, n, n)).isTrue();
		}
		long after = gemm.freeDeviceMemory();
		// 1000 products of three 512 KB buffers leak 1.5 GB if they leak at all, and
		// leaking ONE of the three still costs 500 MB -- so the bound separates the two
		// outcomes by a wide margin rather than measuring precisely. It cannot be tight:
		// cuMemGetInfo is a property of the DEVICE, not of this thread, and the JVM
		// backend's tests run in a second surefire fork where every compiled class
		// defines its own copy of this binding and loads its own module (~10 MB of
		// device memory each). The assertion stays two-sided on purpose: free memory
		// that GREW would mean this is measuring the rest of the machine rather than
		// the buffers.
		assertThat(Math.abs(before - after)).isLessThan(256L << 20);
	}

	@Test
	void aDeclinedProductCostsTheDeviceNothing() {
		// The failure path, which the successful run above never enters, and the one that
		// was wrong first: a pooled allocation that FAILS grows the driver's pool as far
		// as it can on the way to failing and hands back no pointer to free. Measured
		// before this was handled, ONE declined product took this 128 GB device from
		// 69 GB free to 1 GB free and never gave it back -- to this process or to any
		// other one on the card. An 80 GB operand is refused by the pre-flight; the pool
		// trim covers the same case when the pre-flight cannot see it coming.
		CudaGemm gemm = Gpu.device();
		assertThat(gemm).isNotNull();
		long before = gemm.freeDeviceMemory();
		assertThat(before).isGreaterThan(0);
		int huge = 100_000;
		double[] tiny = new double[1];
		for (int i = 0; i < 12; i++) {
			assertThat(gemm.gemm(tiny, 0, tiny, 0, tiny, 0, huge, huge, huge))
				.as("an %d x %d product must decline", huge, huge)
				.isFalse();
			assertThat(gemm.usable()).as("running out of device memory is not a sticky error").isTrue();
		}
		long after = gemm.freeDeviceMemory();
		assertThat(before - after).as("free device memory after twelve declined products").isLessThan(64L << 20);
		// And the device still works afterwards.
		int n = square();
		double[] a = new double[n * n], b = new double[n * n], c = new double[n * n];
		assertThat(gemm.gemm(a, 0, b, 0, c, 0, n, n, n)).isTrue();
	}

	@Test
	void theSameProductRepeatedIsTheSameAnswer() {
		int n = square();
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

	@Test
	void aBatchedProductIsThePerBatchProductOfEachSlab() {
		// The stacked shape (torch.bmm): every slab is its own product, and small
		// integers through the fold are exact at both widths, so this is an EQUALITY.
		int n = square();
		int batch = 3;
		double[] a = new double[batch * n * n], b = new double[batch * n * n];
		float[] af = new float[a.length], bf = new float[b.length];
		for (int i = 0; i < a.length; i++) {
			a[i] = (i % 7) - 3;
			b[i] = (i % 5) - 2;
			af[i] = (float) a[i];
			bf[i] = (float) b[i];
		}
		double[] c = new double[batch * n * n];
		float[] cf = new float[batch * n * n];
		assertThat(Gpu.multiply(a, 0, n * n, b, 0, n * n, c, 0, batch, n, n, n)).isTrue();
		assertThat(Gpu.multiply(af, 0, n * n, bf, 0, n * n, cf, 0, batch, n, n, n)).isTrue();
		for (int z = 0; z < batch; z++) {
			double[] expected = reference(a, z * n * n, b, z * n * n, n, n, n);
			for (int i = 0; i < n * n; i++) {
				assertThat(c[z * n * n + i]).isEqualTo(expected[i]);
				assertThat((double) cf[z * n * n + i]).isEqualTo(expected[i]);
			}
		}
	}

	@Test
	void aBatchIsBitIdenticalToTheSameSlabsRunOneAtATime() {
		// The precision contract of the stacked member, stated as an assertion: a batched
		// cell is a per-batch device product, not something the batch axis rounds
		// differently. INEXACT operands, so a difference would show.
		int n = square();
		int batch = 4;
		Random random = new Random(4242L);
		double[] a = new double[batch * n * n], b = new double[batch * n * n];
		for (int i = 0; i < a.length; i++) {
			a[i] = random.nextDouble() - 0.5;
			b[i] = random.nextDouble() - 0.5;
		}
		double[] batched = new double[batch * n * n];
		assertThat(Gpu.multiply(a, 0, n * n, b, 0, n * n, batched, 0, batch, n, n, n)).isTrue();
		for (int z = 0; z < batch; z++) {
			double[] alone = new double[n * n];
			assertThat(Gpu.multiply(a, z * n * n, b, z * n * n, alone, 0, n, n, n)).isTrue();
			for (int i = 0; i < n * n; i++) {
				assertThat(batched[z * n * n + i]).isEqualTo(alone[i]);
			}
		}
	}

	@Test
	void aBroadcastOperandIsAZeroStrideAndReadsTheSameSlabEveryBatch() {
		// What linalg::%la-matmul-nd's broadcast leading axis passes -- and every
		// torch:linear over a (B T C) activation, whose right operand is one matrix. The
		// operand array holds exactly ONE slab however long the batch is, which is also
		// the assertion that only that slab is copied to the device.
		int n = square();
		int batch = 5;
		double[] a = new double[batch * n * n], b = new double[n * n];
		for (int i = 0; i < a.length; i++) {
			a[i] = (i % 7) - 3;
		}
		for (int i = 0; i < b.length; i++) {
			b[i] = (i % 5) - 2;
		}
		double[] c = new double[batch * n * n];
		assertThat(Gpu.multiply(a, 0, n * n, b, 0, 0, c, 0, batch, n, n, n)).isTrue();
		for (int z = 0; z < batch; z++) {
			double[] expected = reference(a, z * n * n, b, 0, n, n, n);
			for (int i = 0; i < n * n; i++) {
				assertThat(c[z * n * n + i]).isEqualTo(expected[i]);
			}
		}
		// And the other way round: one left operand against a stack of right ones.
		double[] left = new double[n * n], stack = new double[batch * n * n], out = new double[batch * n * n];
		for (int i = 0; i < left.length; i++) {
			left[i] = (i % 3) - 1;
		}
		for (int i = 0; i < stack.length; i++) {
			stack[i] = (i % 4) - 2;
		}
		assertThat(Gpu.multiply(left, 0, 0, stack, 0, n * n, out, 0, batch, n, n, n)).isTrue();
		for (int z = 0; z < batch; z++) {
			double[] expected = reference(left, 0, stack, z * n * n, n, n, n);
			for (int i = 0; i < n * n; i++) {
				assertThat(out[z * n * n + i]).isEqualTo(expected[i]);
			}
		}
	}

	@Test
	void aBatchedProductReadsEveryOperandFromItsOwnOffset() {
		int n = square();
		int batch = 3, headerA = 4, headerB = 3, headerOut = 5;
		double[] a = new double[headerA + batch * n * n], b = new double[headerB + batch * n * n];
		double[] out = new double[headerOut + batch * n * n];
		for (int i = 0; i < headerA; i++) {
			a[i] = Double.NaN;
		}
		for (int i = 0; i < headerB; i++) {
			b[i] = Double.NaN;
		}
		for (int i = 0; i < headerOut; i++) {
			out[i] = i + 0.25;
		}
		for (int i = 0; i < batch * n * n; i++) {
			a[headerA + i] = (i % 7) - 3;
			b[headerB + i] = (i % 5) - 2;
		}
		assertThat(Gpu.multiply(a, headerA, n * n, b, headerB, n * n, out, headerOut, batch, n, n, n)).isTrue();
		for (int z = 0; z < batch; z++) {
			double[] expected = reference(a, headerA + z * n * n, b, headerB + z * n * n, n, n, n);
			for (int i = 0; i < n * n; i++) {
				assertThat(out[headerOut + z * n * n + i]).isEqualTo(expected[i]);
			}
		}
		for (int i = 0; i < headerOut; i++) {
			assertThat(out[i]).isEqualTo(i + 0.25);
		}
	}

	@Test
	void everyBatchedDeclineConditionStillDeclinesWithADevicePresent() {
		int n = square();
		int batch = 3;
		double[] a = new double[batch * n * n], b = new double[batch * n * n], out = new double[batch * n * n];
		// Below the threshold, which for a stack is the TOTAL work: one 8x8x8 product
		// stays below it however the batch is spelled, and 64 of them still do.
		assertThat(Gpu.worth(64, 8, 8, 8)).isFalse();
		assertThat(Gpu.multiply(a, 0, 512, b, 0, 512, out, 0, 64, 8, 8, 8)).isFalse();
		// An empty batch, and one past the 16-bit gridDim.z.
		assertThat(Gpu.worth(0, n, n, n)).isFalse();
		assertThat(Gpu.multiply(a, 0, n * n, b, 0, n * n, out, 0, 0, n, n, n)).isFalse();
		assertThat(Gpu.multiply(a, 0, n * n, b, 0, n * n, out, 0, 70_000, n, n, n)).isFalse();
		// A stride that walks off the end of an operand, and a negative one.
		assertThat(Gpu.multiply(a, 0, n * n, b, 0, n * n, out, 0, batch + 1, n, n, n)).isFalse();
		assertThat(Gpu.multiply(a, 0, -(n * n), b, 0, n * n, out, 0, batch, n, n, n)).isFalse();
		// A result array that cannot hold the whole stack.
		assertThat(Gpu.multiply(a, 0, n * n, b, 0, n * n, new double[n * n], 0, batch, n, n, n)).isFalse();
		// And a batch above the threshold still costs the device nothing when it is too
		// big for device memory.
		assertThat(Gpu.multiply(a, 0, 0, b, 0, 0, out, 0, 60_000, 20_000, 20_000, 20_000)).isFalse();
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
