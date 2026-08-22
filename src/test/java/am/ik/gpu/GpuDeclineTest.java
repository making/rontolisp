package am.ik.gpu;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * What {@code am.ik.gpu} must do on EVERY machine, with a GPU or without one: answer,
 * quietly, and never throw. {@link GpuTest} is the half that needs a device; this is the
 * half that must never regress, because it is the half a machine with no NVIDIA driver --
 * which is most machines, and every CI runner this project has -- actually runs.
 *
 * <p>
 * Nothing here is conditional and nothing here asserts a number that only a GPU can
 * produce. Two things are pinned: the probe and the decline path are total (every
 * question has an answer, no exception escapes, no matter what the machine is), and the
 * checked-in PTX is the artifact the loader expects to find.
 */
class GpuDeclineTest {

	@Test
	void theProbeAnswersWithoutThrowingOnAnyMachine() {
		assertThatCode(Gpu::available).doesNotThrowAnyException();
		assertThat(Gpu.description()).isNotBlank();
		// Twice, because the answer is cached and the cache must not be a second code
		// path: a machine with no driver pays one failed dlopen and then answers from a
		// field.
		assertThat(Gpu.available()).isEqualTo(Gpu.available());
		assertThat(Gpu.description()).isEqualTo(Gpu.description());
	}

	@Test
	void worthIsAPureSizePredicateThatCostsNothing() {
		// It is documented as the pre-check a caller makes BEFORE unwrapping its
		// operands,
		// so it must not be the thing that runs the probe: on a machine with a GPU that
		// is
		// a dlopen, a cuInit, a retained primary context and a PTX JIT, on a path that
		// may
		// then never touch the device. Asking it many times must stay free.
		long start = System.nanoTime();
		boolean any = false;
		for (int i = 0; i < 100_000; i++) {
			any |= Gpu.worth(i % 97, 64, 64);
		}
		long elapsed = System.nanoTime() - start;
		assertThat(any).isTrue();
		assertThat(elapsed).as("100k worth() calls in nanoseconds").isLessThan(200_000_000L);
	}

	@Test
	void aProductBelowTheSizeThresholdIsNeverOffered() {
		// The threshold is the whole reason an intercepted call is allowed to be
		// unconditional: a small product declines and the caller's own kernel runs.
		assertThat(Gpu.worth(1, 1, 1)).isFalse();
		assertThat(Gpu.worth(8, 8, 8)).isFalse();
		assertThat(Gpu.worth(32, 32, 32)).isFalse();
		assertThat(Gpu.worth(256, 256, 256)).isTrue();
		// A degenerate shape is not "small work", it is not a product at all.
		assertThat(Gpu.worth(0, 1024, 1024)).isFalse();
		assertThat(Gpu.worth(-1, 1024, 1024)).isFalse();
	}

	@Test
	void everyDeclineConditionDeclinesRatherThanThrows() {
		double[] a = new double[64 * 64], b = new double[64 * 64];
		float[] af = new float[64 * 64], bf = new float[64 * 64];
		// Too small to be worth a round trip.
		assertThat(Gpu.multiply(a, 0, b, 0, 4, 4, 4)).isNull();
		assertThat(Gpu.multiply(af, 0, bf, 0, 4, 4, 4)).isNull();
		// Dimensions that do not fit the arrays they were claimed for.
		assertThat(Gpu.multiply(a, 0, b, 0, 1024, 1024, 1024)).isNull();
		assertThat(Gpu.multiply(af, 0, bf, 0, 1024, 1024, 1024)).isNull();
		// An offset past the end, and a negative one.
		assertThat(Gpu.multiply(a, 4096, b, 0, 64, 64, 64)).isNull();
		assertThat(Gpu.multiply(a, -1, b, 0, 64, 64, 64)).isNull();
		// A degenerate shape.
		assertThat(Gpu.multiply(a, 0, b, 0, 0, 64, 64)).isNull();
		assertThat(Gpu.multiply(a, 0, b, 0, 64, 64, -64)).isNull();
		// A row count past the 16-bit grid axis, on arrays that could never hold it.
		assertThat(Gpu.multiply(a, 0, b, 0, Integer.MAX_VALUE, 64, 64)).isNull();
		// A product bigger than any device's memory: declined by the size check on a
		// machine without a GPU, and by the pre-flight against free device memory on one
		// with. Neither may throw, and on a real device neither may cost the device
		// anything (GpuTest.aDeclinedProductCostsTheDeviceNothing).
		double[] tiny = new double[1];
		assertThat(Gpu.multiply(tiny, 0, tiny, 0, 100_000, 100_000, 100_000)).isNull();
	}

	@Test
	void theDestinationTakingFormDeclinesOnTheSameConditions() {
		// The form an interceptor will actually call: it writes into the caller's array
		// at the caller's offset, so it has a third set of bounds to refuse.
		double[] a = new double[64 * 64], b = new double[64 * 64], out = new double[64 * 64];
		float[] af = new float[64 * 64], bf = new float[64 * 64], outF = new float[64 * 64];
		assertThat(Gpu.multiply(a, 0, b, 0, out, 0, 4, 4, 4)).isFalse();
		assertThat(Gpu.multiply(af, 0, bf, 0, outF, 0, 4, 4, 4)).isFalse();
		// The result would not fit: one element short, and a negative offset.
		assertThat(Gpu.multiply(a, 0, b, 0, out, 1, 64, 64, 64)).isFalse();
		assertThat(Gpu.multiply(a, 0, b, 0, out, -1, 64, 64, 64)).isFalse();
		assertThat(Gpu.multiply(af, 0, bf, 0, new float[64], 0, 64, 64, 64)).isFalse();
		// And a declined call leaves the destination alone.
		assertThat(out).containsOnly(0.0);
	}

	@Test
	void anElementWiseMapBelowTheSizeThresholdIsNeverOffered() {
		// The element-wise threshold counts ELEMENTS, not multiply-adds, so it is four
		// orders of magnitude below the product's: a map is one libm call per element.
		assertThat(Gpu.worthMap(1)).isFalse();
		assertThat(Gpu.worthMap(4096)).isFalse();
		assertThat(Gpu.worthMap(16383)).isFalse();
		assertThat(Gpu.worthMap(16384)).isTrue();
		assertThat(Gpu.worthMap(0)).isFalse();
		assertThat(Gpu.worthMap(-1)).isFalse();
	}

	@Test
	void everyElementWiseDeclineConditionDeclinesRatherThanThrows() {
		int n = (int) Gpu.mapMinElements() * 2;
		double[] a = new double[n], out = new double[n];
		float[] af = new float[n], outF = new float[n];
		assertThatCode(() -> {
			// Below the threshold, at every width.
			assertThat(Gpu.map(Gpu.MAP_EXP, a, 0, out, 0, 8)).isFalse();
			assertThat(Gpu.map(Gpu.MAP_EXP, af, 0, outF, 0, 8)).isFalse();
			// An op code this library does not name. It must NOT quietly compute some
			// other member: the kernel's default case is the identity and this is the
			// guard in front of it.
			assertThat(Gpu.map(-1, a, 0, out, 0, n)).isFalse();
			assertThat(Gpu.map(Gpu.MAP_OPS, a, 0, out, 0, n)).isFalse();
			assertThat(Gpu.map(Integer.MAX_VALUE, af, 0, outF, 0, n)).isFalse();
			// Elements that are not inside the arrays they were promised in.
			assertThat(Gpu.map(Gpu.MAP_EXP, a, 1, out, 0, n)).isFalse();
			assertThat(Gpu.map(Gpu.MAP_EXP, a, 0, out, 1, n)).isFalse();
			assertThat(Gpu.map(Gpu.MAP_EXP, a, -1, out, 0, n)).isFalse();
			assertThat(Gpu.map(Gpu.MAP_EXP, a, 0, new double[8], 0, n)).isFalse();
			assertThat(Gpu.map(Gpu.MAP_EXP, af, 0, new float[8], 0, n)).isFalse();
			// A degenerate count.
			assertThat(Gpu.map(Gpu.MAP_EXP, a, 0, out, 0, 0)).isFalse();
			assertThat(Gpu.map(Gpu.MAP_EXP, a, 0, out, 0, -1)).isFalse();
		}).doesNotThrowAnyException();
		// A declined map leaves the destination alone, on a machine with a device and on
		// one without.
		assertThat(out).containsOnly(0.0);
		assertThat(outF).containsOnly(0.0f);
	}

	@Test
	void aStackBelowTheSizeThresholdIsNeverOffered() {
		// A batch is ONE round trip and one launch, so the threshold is over the TOTAL
		// work: what the device has to beat is the CPU's cost for the whole stack, and
		// the floor it has to clear is paid once. Measured, that is the same crossover
		// the unbatched product has (.kb/gpu.md).
		assertThat(Gpu.worth(1, 32, 32, 32)).isFalse();
		assertThat(Gpu.worth(3, 32, 32, 32)).isFalse();
		assertThat(Gpu.worth(4, 32, 32, 32)).isTrue();
		assertThat(Gpu.worth(256, 8, 8, 8)).isTrue();
		assertThat(Gpu.worth(64, 64, 64, 64)).isTrue();
		// A batch and a single product of the same total work agree, and the unbatched
		// predicate is the batch-of-one.
		assertThat(Gpu.worth(1, 64, 64, 64)).isEqualTo(Gpu.worth(64, 64, 64));
		// A degenerate batch is not small work, it is not a product at all.
		assertThat(Gpu.worth(0, 1024, 1024, 1024)).isFalse();
		assertThat(Gpu.worth(-1, 1024, 1024, 1024)).isFalse();
	}

	@Test
	void everyBatchedDeclineConditionDeclinesRatherThanThrows() {
		double[] a = new double[8 * 64 * 64], b = new double[8 * 64 * 64], out = new double[8 * 64 * 64];
		float[] af = new float[8 * 64 * 64], bf = new float[8 * 64 * 64], outF = new float[8 * 64 * 64];
		// Below the threshold, an empty batch, and one past the 16-bit gridDim.z.
		assertThat(Gpu.multiply(a, 0, 16, b, 0, 16, out, 0, 8, 4, 4, 4)).isFalse();
		assertThat(Gpu.multiply(a, 0, 4096, b, 0, 4096, out, 0, 0, 64, 64, 64)).isFalse();
		assertThat(Gpu.multiply(a, 0, 0, b, 0, 0, out, 0, 70_000, 64, 64, 64)).isFalse();
		// Strides that walk off the end of an operand, and negative ones.
		assertThat(Gpu.multiply(a, 0, 4096, b, 0, 4096, out, 0, 9, 64, 64, 64)).isFalse();
		assertThat(Gpu.multiply(af, 0, -4096, bf, 0, 4096, outF, 0, 8, 64, 64, 64)).isFalse();
		// A destination that cannot hold the whole stack.
		assertThat(Gpu.multiply(a, 0, 4096, b, 0, 4096, new double[4096], 0, 8, 64, 64, 64)).isFalse();
		// And a declined call leaves the destination alone, on a machine with a device
		// as much as on one without.
		assertThat(out).containsOnly(0.0);
	}

	@Test
	void anUnaskableShapeIsNotLaunchable() {
		assertThat(CudaGemm.launchable(64, 64, 64)).isTrue();
		assertThat(CudaGemm.launchable(0, 64, 64)).isFalse();
		assertThat(CudaGemm.launchable(64, 0, 64)).isFalse();
		// gridDim.y is 16 bits, so more than 65535 tiles of rows cannot be launched.
		assertThat(CudaGemm.launchable(65535L * 16, 1, 1)).isTrue();
		assertThat(CudaGemm.launchable(65536L * 16, 1, 1)).isFalse();
		// The result has to be indexable as a Java array.
		assertThat(CudaGemm.launchable(1 << 20, 1, 1 << 20)).isFalse();
		// A stack rides on gridDim.z, which is 16 bits too, and the whole stack has to be
		// one Java array.
		assertThat(CudaGemm.launchable(65535, 64, 64, 64)).isTrue();
		assertThat(CudaGemm.launchable(65536, 64, 64, 64)).isFalse();
		assertThat(CudaGemm.launchable(0, 64, 64, 64)).isFalse();
		assertThat(CudaGemm.launchable(1 << 16, 1 << 16, 1, 1 << 16)).isFalse();
	}

	@Test
	void theResultTableIsTotalAndNeverThrows() {
		assertThat(CuResult.of(0)).isEqualTo(CuResult.CUDA_SUCCESS);
		assertThat(CuResult.of(700)).isEqualTo(CuResult.CUDA_ERROR_ILLEGAL_ADDRESS);
		// A status a newer driver invents is not an error condition of its own.
		assertThat(CuResult.of(123456)).isNull();
		assertThat(CuResult.describe(123456)).isEqualTo("CUresult 123456");
		assertThat(CuResult.describe(2)).isEqualTo("CUDA_ERROR_OUT_OF_MEMORY (2)");
		for (CuResult result : CuResult.values()) {
			assertThat(CuResult.describe(result.code())).contains(result.name());
		}
	}

	@Test
	void everyStatusCodeIsDistinct() {
		Map<Integer, CuResult> seen = new HashMap<>();
		for (CuResult result : CuResult.values()) {
			assertThat(seen.put(result.code(), result)).as("duplicate code %d", result.code()).isNull();
		}
		assertThat(seen).hasSameSizeAs(CuResult.values());
	}

	@Test
	void onlyTheStatusesThatCorruptTheContextAreSticky() {
		// The decline-on-error rule turns the feature OFF for the rest of the process on
		// a
		// sticky status, so the classification has to be narrow: a product too big for
		// device memory must not retire a working GPU.
		assertThat(CuResult.CUDA_ERROR_OUT_OF_MEMORY.sticky()).isFalse();
		assertThat(CuResult.CUDA_ERROR_INVALID_VALUE.sticky()).isFalse();
		assertThat(CuResult.CUDA_ERROR_NO_DEVICE.sticky()).isFalse();
		assertThat(CuResult.CUDA_ERROR_LAUNCH_FAILED.sticky()).isTrue();
		assertThat(CuResult.CUDA_ERROR_ILLEGAL_ADDRESS.sticky()).isTrue();
		assertThat(CuResult.CUDA_ERROR_ECC_UNCORRECTABLE.sticky()).isTrue();
		assertThat(CuResult.isSticky(CuResult.SUCCESS)).isFalse();
		// An unrecognised failure is assumed to be the dangerous kind.
		assertThat(CuResult.isSticky(123456)).isTrue();
	}

	@Test
	void theCheckedInPtxIsTheArtifactTheLoaderExpects() throws IOException {
		String ptx = resource(CudaGemm.PTX_RESOURCE);
		// The virtual architecture is not a free choice -- CUDA 13 refuses to target
		// anything below compute_75 -- and it is what the probe's capability check is
		// written against, so the two must not drift apart.
		assertThat(ptx).contains(".target sm_" + CudaGemm.PTX_COMPUTE_CAPABILITY);
		assertThat(ptx).contains(".visible .entry " + CudaGemm.KERNEL_F64);
		assertThat(ptx).contains(".visible .entry " + CudaGemm.KERNEL_F32);
		// The stacked siblings regenerate from the same source, so a regeneration that
		// dropped them would leave a build whose probe declines on every machine.
		assertThat(ptx).contains(".visible .entry " + CudaGemm.KERNEL_BATCHED_F64);
		assertThat(ptx).contains(".visible .entry " + CudaGemm.KERNEL_BATCHED_F32);
		assertThat(ptx).contains(".visible .entry " + CudaGemm.KERNEL_BATCHED_F32_T4);
		assertThat(ptx).contains(".visible .entry " + CudaGemm.KERNEL_BATCHED_F32_T8);
		// And the element-wise pair, whose op codes are the other half of a mirror
		// nothing links: Gpu.MAP_* names them and gemm.cu switches on them.
		assertThat(ptx).contains(".visible .entry " + CudaGemm.KERNEL_MAP_F64);
		assertThat(ptx).contains(".visible .entry " + CudaGemm.KERNEL_MAP_F32);
		assertThat(resource("gemm.cu")).contains("case " + Gpu.MAP_ERF + ": return erf(x);");
		// The strided tier's six, whose op codes are a THREE-way mirror -- Gpu.BIN_* /
		// Gpu.FOLD_* here, the switches in gemm.cu, and LinalgSimdKernels.BOP_* as the
		// oracle they must agree with.
		for (String kernel : CudaGemm.KERNELS_STRIDED) {
			assertThat(ptx).contains(".visible .entry " + kernel);
		}
		assertThat(resource("gemm.cu")).contains("case " + Gpu.BIN_DIV + ": return x / y;");
		// The GEMV pair behind vec:matvec, the one member outside linalg:.
		assertThat(ptx).contains(".visible .entry " + CudaGemm.KERNEL_GEMV_F64);
		assertThat(ptx).contains(".visible .entry " + CudaGemm.KERNEL_GEMV_F32);
		// The resident tier's eight, and the mirrors it added: the four maps past the
		// libm ones and the five comparison masks.
		for (String kernel : CudaGemm.KERNELS_RESIDENT) {
			assertThat(ptx).contains(".visible .entry " + kernel);
		}
		assertThat(resource("gemm.cu")).contains("case " + Gpu.MAP_SQRT + ": {").contains("sqrt((double) x)");
		assertThat(resource("gemm.cu")).contains("case " + Gpu.BIN_EQ + ": return x == y ? 1.0 : 0.0;");
		// The regeneration command travels with the artifact: without it the .ptx is an
		// unreproducible blob.
		assertThat(ptx).contains("nvcc -arch=compute_" + CudaGemm.PTX_COMPUTE_CAPABILITY + " -ptx");
		assertThat(resource("gemm.cu")).contains("nvcc -arch=compute_" + CudaGemm.PTX_COMPUTE_CAPABILITY + " -ptx");
	}

	@Test
	void aStridedCallBelowTheSizeThresholdIsNeverOffered() {
		// The pure size predicates, which must answer without touching a driver.
		assertThat(Gpu.worthStrided(1L << 15)).isTrue();
		assertThat(Gpu.worthStrided((1L << 15) - 1)).isFalse();
		assertThat(Gpu.worthFold(1L << 17)).isTrue();
		assertThat(Gpu.worthFold((1L << 17) - 1)).isFalse();
		double[] small = new double[64], out = new double[64];
		int[] dims = { 8, 8 };
		int[] strides = { 8, 1 };
		assertThat(Gpu.bcast(Gpu.BIN_ADD, small, 0, strides, small, 0, strides, out, 0, dims)).isFalse();
		assertThat(Gpu.gather(small, 0, strides, out, 0, dims)).isFalse();
		assertThat(Gpu.fold(Gpu.FOLD_SUM, small, 0, out, 0, 8, 8, 1)).isFalse();
		assertThat(out).containsOnly(0.0);
	}

	@Test
	void aGeneratorFillBelowTheSizeThresholdIsNeverOffered() {
		assertThat(Gpu.worthRng(1L << 13)).isTrue();
		assertThat(Gpu.worthRng((1L << 13) - 1)).isFalse();
		double[] small = new double[64];
		float[] smallF = new float[64];
		assertThat(Gpu.rngFill(small, 0, 64, 0, 0.0, 1.0, 1, 2, 3)).isFalse();
		assertThat(Gpu.rngFill(smallF, 0, 64, 1, 0.0, 1.0, 1, 2, 3)).isFalse();
		assertThat(small).containsOnly(0.0);
		assertThat(smallF).containsOnly(0.0f);
	}

	@Test
	void aMatrixByVectorProductBelowTheSizeThresholdIsNeverOffered() {
		assertThat(Gpu.worthMatvec(512, 256)).isTrue();
		assertThat(Gpu.worthMatvec(511, 256)).isFalse();
		assertThat(Gpu.worthMatvec(0, 512)).isFalse();
		double[] w = new double[64 * 64], x = new double[64], y = new double[64];
		float[] wf = new float[64 * 64], xf = new float[64], yf = new float[64];
		assertThat(Gpu.matvec(w, 0, x, 0, y, 0, 64, 64)).isFalse();
		assertThat(Gpu.matvec(wf, 0, xf, 0, yf, 0, 64, 64)).isFalse();
		assertThat(y).containsOnly(0.0);
		assertThat(yf).containsOnly(0.0f);
	}

	@Test
	void everyMatrixByVectorDeclineConditionDeclinesRatherThanThrows() {
		// A vector shorter than the matrix is wide, a result shorter than it is tall, an
		// offset that overruns, a negative one, an empty extent: each declines with the
		// result untouched -- on a machine with a device as on one without. (With a
		// device the well-formed call on a matrix seen for the first time declines too,
		// by design; GpuTest pins the second sight.)
		int rows = 512, cols = 256;
		double[] w = new double[rows * cols], x = new double[cols], y = new double[rows];
		assertThat(Gpu.matvec(w, 0, new double[cols - 1], 0, y, 0, rows, cols)).isFalse();
		assertThat(Gpu.matvec(w, 0, x, 0, new double[rows - 1], 0, rows, cols)).isFalse();
		assertThat(Gpu.matvec(w, 1, x, 0, y, 0, rows, cols)).isFalse();
		assertThat(Gpu.matvec(w, 0, x, 1, y, 0, rows, cols)).isFalse();
		assertThat(Gpu.matvec(w, 0, x, 0, y, 1, rows, cols)).isFalse();
		assertThat(Gpu.matvec(w, -1, x, 0, y, 0, rows, cols)).isFalse();
		assertThat(Gpu.matvec(w, 0, x, 0, y, 0, 0, cols)).isFalse();
		assertThat(Gpu.matvec(w, 0, x, 0, y, 0, rows, 0)).isFalse();
		assertThat(y).containsOnly(0.0);
	}

	@Test
	void everyGeneratorFillDeclineConditionDeclinesRatherThanThrows() {
		// A mode outside 0..2, a state word outside the generator's range, a fill that
		// does not fit inside the destination: each declines with the destination
		// untouched -- on a machine with a device as on one without.
		int n = 1 << 14;
		double[] out = new double[n];
		assertThat(Gpu.rngFill(out, 0, n, 3, 0.0, 1.0, 1, 2, 3)).isFalse();
		assertThat(Gpu.rngFill(out, 0, n, -1, 0.0, 1.0, 1, 2, 3)).isFalse();
		assertThat(Gpu.rngFill(out, 0, n, 0, 0.0, 1.0, -1, 2, 3)).isFalse();
		assertThat(Gpu.rngFill(out, 0, n, 0, 0.0, 1.0, 1, 1 << 23, 3)).isFalse();
		assertThat(Gpu.rngFill(out, 1, n, 0, 0.0, 1.0, 1, 2, 3)).isFalse();
		assertThat(Gpu.rngFill(out, 0, n + 1, 0, 0.0, 1.0, 1, 2, 3)).isFalse();
		assertThat(Gpu.rngFill(out, -1, n, 0, 0.0, 1.0, 1, 2, 3)).isFalse();
		assertThat(out).containsOnly(0.0);
	}

	@Test
	void theClosedFormAdvanceIsTheSequentialWalksEndState() {
		// Pure integer arithmetic, so it runs on every machine: the end state the device
		// fill reports must be the state a sequential walk of the same length reaches,
		// including across a wrap of every modulus and from the seed words linalg:seed
		// produces.
		int[] st = { 4321, 8765, 2468 };
		int[] walk = st.clone();
		for (long steps = 0; steps <= 100_000; steps++) {
			if (steps % 997 == 0 || steps < 20) {
				assertThat(Gpu.rngAdvance(st[0], st[1], st[2], steps)).as("after %d steps", steps).isEqualTo(walk);
			}
			walk[0] = 171 * walk[0] % 30269;
			walk[1] = 172 * walk[1] % 30307;
			walk[2] = 170 * walk[2] % 30323;
		}
		assertThat(Gpu.rngAdvance(1, 1, 1, 0)).isEqualTo(new int[] { 1, 1, 1 });
		assertThat(Gpu.rngAdvance(0, 5, 7, 12))
			.isEqualTo(new int[] { 0, 5 * pow(172, 12, 30307) % 30307, 7 * pow(170, 12, 30323) % 30323 });
	}

	private static int pow(long a, int e, int m) {
		long r = 1;
		for (int i = 0; i < e; i++) {
			r = r * a % m;
		}
		return (int) r;
	}

	@Test
	void everyStridedDeclineConditionDeclinesRatherThanThrows() {
		// The bounds are what stop a kernel indexing outside the caller's array: the
		// kernel walks strides freely, so the library has to bound the whole reachable
		// span rather than the element count.
		int rows = 1 << 12, cols = 64, n = rows * cols;
		double[] x = new double[n], y = new double[rows], out = new double[n];
		int[] dims = { rows, cols };
		int[] sx = { cols, 1 };
		int[] sy = { 1, 0 };
		assertThat(Gpu.bcast(Gpu.BIN_OPS, x, 0, sx, y, 0, sy, out, 0, dims)).isFalse();
		assertThat(Gpu.bcast(-1, x, 0, sx, y, 0, sy, out, 0, dims)).isFalse();
		// An operand whose span runs past its array, a negative stride, a mismatched
		// stride vector, an empty or absurdly deep shape, an offset that does not fit.
		assertThat(Gpu.bcast(Gpu.BIN_ADD, x, 1, sx, y, 0, sy, out, 0, dims)).isFalse();
		assertThat(Gpu.bcast(Gpu.BIN_ADD, x, 0, new int[] { cols, -1 }, y, 0, sy, out, 0, dims)).isFalse();
		assertThat(Gpu.bcast(Gpu.BIN_ADD, x, 0, new int[] { cols }, y, 0, sy, out, 0, dims)).isFalse();
		assertThat(Gpu.bcast(Gpu.BIN_ADD, x, 0, sx, y, 0, sy, out, 0, new int[0])).isFalse();
		assertThat(Gpu.bcast(Gpu.BIN_ADD, x, 0, sx, y, 0, sy, out, 0, new int[] { rows, 0 })).isFalse();
		assertThat(Gpu.gather(x, 0, sx, out, 1, dims)).isFalse();
		assertThat(Gpu.gather(x, -1, sx, out, 0, dims)).isFalse();
		// A fold with too few OUTPUT cells is a single-threaded device loop and declines
		// however big its input is; so does an empty extent and an unnamed op.
		assertThat(Gpu.fold(Gpu.FOLD_OPS, x, 0, out, 0, rows, cols, 1)).isFalse();
		assertThat(Gpu.fold(Gpu.FOLD_SUM, x, 0, out, 0, 1, n, 1)).isFalse();
		assertThat(Gpu.fold(Gpu.FOLD_SUM, x, 0, out, 0, rows, 0, 1)).isFalse();
		assertThat(Gpu.fold(Gpu.FOLD_SUM, x, 0, out, 0, rows, cols, 1) && out[0] == 0.0).isIn(true, false);
		float[] xf = new float[n], yf = new float[rows], outf = new float[n];
		assertThat(Gpu.bcast(Gpu.BIN_ADD, xf, 0, new int[] { cols, -1 }, yf, 0, sy, outf, 0, dims)).isFalse();
		assertThat(Gpu.gather(xf, 0, sx, outf, 1, dims)).isFalse();
		assertThat(Gpu.fold(Gpu.FOLD_SUM, xf, 0, outf, 0, 1, n, 1)).isFalse();
	}

	@Test
	void theCheckedInMetalSourceIsTheArtifactTheLoaderExpects() throws IOException {
		// gemm.metal's counterpart of the PTX assertion above, and it has to hold on a
		// Linux CI runner too: the MSL text travels in every --gpu class whichever
		// machine
		// emitted it, so a source that named the wrong kernels would break Apple users of
		// a class compiled anywhere.
		String msl = resource(MetalGemm.KERNEL_RESOURCE);
		assertThat(msl).contains("kernel void " + MetalGemm.KERNEL_BATCHED_F32);
		assertThat(msl).contains("kernel void " + MetalGemm.KERNEL_MAP_F32);
		assertThat(msl).contains("kernel void " + MetalGemm.KERNEL_BCAST_F32);
		assertThat(msl).contains("kernel void " + MetalGemm.KERNEL_GATHER_F32);
		assertThat(msl).contains("kernel void " + MetalGemm.KERNEL_GEMV_F32);
		// The op-code mirrors, the third copy of the table gemm.cu and Gpu.MAP_* /
		// Gpu.BIN_* hold the other two of.
		assertThat(msl).contains("case " + Gpu.MAP_ERF + ": return erf1(x);");
		assertThat(msl).contains("case " + Gpu.BIN_DIV + ": return x / y;");
		// MSL rejects `double` outright, so a `double` in the CODE is a source that
		// cannot
		// compile on any Mac -- which no machine without one would notice. The comments
		// discuss the word at length and are stripped first.
		assertThat(msl.lines().filter(line -> !line.strip().startsWith("//")).toList())
			.as("no declared double survives the comments")
			.noneMatch(line -> line.contains("double"));
	}

	@Test
	void suppliedMetalKernelsAreAcceptedWithoutProbingAndWithoutThrowing() throws IOException {
		// useKernels' Apple sibling, and the same rule applies: the REAL checked-in text
		// and nothing else, because the override is process-wide and read at probe time.
		Gpu.useMetalKernels(resource(MetalGemm.KERNEL_RESOURCE));
		assertThat(Gpu.description()).isNotBlank();
	}

	@Test
	void suppliedKernelsAreAcceptedWithoutProbingAndWithoutThrowing() throws IOException {
		// The seam an embedder that carries the CLASSES but not the resources needs:
		// rontolisp's JVM backend renames these classes into a compiled program's own
		// package, where getResourceAsStream can never find gemm.ptx again
		// (.kb/gpu.md). It is deliberately handed the REAL checked-in text here and
		// nowhere else -- a test that poisoned it with a placeholder would decide what
		// the whole suite's probe compiles, whichever class ran first.
		Gpu.useKernels(resource(CudaGemm.PTX_RESOURCE));
		assertThat(Gpu.description()).isNotBlank();
	}

	private String resource(String name) throws IOException {
		try (InputStream in = CudaGemm.class.getResourceAsStream(name)) {
			assertThat(in).as("resource %s", name).isNotNull();
			return new String(in.readAllBytes(), StandardCharsets.US_ASCII);
		}
	}

	@Test
	void theResidentTierAndTheLazyHooksDeclineOrDoNothingOnAMachineWithoutADevice() {
		// The hooks the interceptors call from every packed-array read and write never
		// throw, never probe and never touch a driver -- a program that writes a double
		// into an array must not pay for a dlopen.
		assertThatCode(() -> {
			Gpu.written(new double[4]);
			Gpu.materialize(new double[4]);
			Gpu.materialize("not an array");
			assertThat(Gpu.resident(new float[4])).isFalse();
			Gpu.lazyResults(true);
			Gpu.lazyResults(false);
		}).doesNotThrowAnyException();
		// The resident tier is offered over a resident operand only, and with no device
		// nothing is ever resident: every member declines, whatever its arguments.
		int n = 1 << 16;
		double[] a = new double[n], b = new double[n], out = new double[n];
		float[] af = new float[n], bf = new float[n], outF = new float[n];
		double[] rule = { 0.01, 0.001, 0.1, 0.9, 0.1, 0.999, 0.001, 1e-8, 0.19, 0.001999, 0.0 };
		assertThatCode(() -> {
			assertThat(Gpu.zip(Gpu.BIN_ADD, a, 0, b, 0, out, 0, n)).isFalse();
			assertThat(Gpu.zip(Gpu.BIN_MUL, af, 0, bf, 0, outF, 0, n)).isFalse();
			assertThat(Gpu.scale(Gpu.BIN_DIV, a, 0, 2.0, false, out, 0, n)).isFalse();
			assertThat(Gpu.scale(Gpu.BIN_SUB, af, 0, 2.0, true, outF, 0, n)).isFalse();
			assertThat(Gpu.map(Gpu.MAP_SQRT, a, 0, out, 0, n)).isFalse();
			assertThat(Gpu.map(Gpu.MAP_SIGN, af, 0, outF, 0, n)).isFalse();
			assertThat(Gpu.where(a, 0, new int[] { 1 }, 0.0, b, 0, new int[] { 1 }, 0.0, null, 0, new int[] { 0 }, 1.0,
					out, 0, new int[] { n }))
				.isFalse();
			assertThat(Gpu.where(af, 0, new int[] { 1 }, 0.0, bf, 0, new int[] { 1 }, 0.0, null, 0, new int[] { 0 },
					1.0, outF, 0, new int[] { n }))
				.isFalse();
			assertThat(Gpu.adamStep(a, 0, b, 0, out, 0, new double[n], 0, n, rule)).isFalse();
			assertThat(Gpu.adamStep(af, 0, bf, 0, outF, 0, new float[n], 0, n, rule)).isFalse();
			assertThat(Gpu.copy(a, 0, new int[] { 1 }, new int[] { 0, n }, out, 0, new int[] { 1 }, new int[] { 0, n },
					new int[] { n }))
				.isFalse();
			assertThat(Gpu.copy(af, 0, new int[] { 1 }, new int[] { 0, n }, outF, 0, new int[] { 1 },
					new int[] { 0, n }, new int[] { n }))
				.isFalse();
		}).doesNotThrowAnyException();
	}

}
