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
	void anUnaskableShapeIsNotLaunchable() {
		assertThat(CudaGemm.launchable(64, 64, 64)).isTrue();
		assertThat(CudaGemm.launchable(0, 64, 64)).isFalse();
		assertThat(CudaGemm.launchable(64, 0, 64)).isFalse();
		// gridDim.y is 16 bits, so more than 65535 tiles of rows cannot be launched.
		assertThat(CudaGemm.launchable(65535L * 16, 1, 1)).isTrue();
		assertThat(CudaGemm.launchable(65536L * 16, 1, 1)).isFalse();
		// The result has to be indexable as a Java array.
		assertThat(CudaGemm.launchable(1 << 20, 1, 1 << 20)).isFalse();
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
		// The regeneration command travels with the artifact: without it the .ptx is an
		// unreproducible blob.
		assertThat(ptx).contains("nvcc -arch=compute_" + CudaGemm.PTX_COMPUTE_CAPABILITY + " -ptx");
		assertThat(resource("gemm.cu")).contains("nvcc -arch=compute_" + CudaGemm.PTX_COMPUTE_CAPABILITY + " -ptx");
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

}
