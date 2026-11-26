package am.ik.gpu;

import am.ik.rontolisp.NativeImageDowncalls;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the native-image downcall registration of {@code am.ik.gpu} against the bindings
 * themselves: every shape either driver asks the linker for must have an entry in
 * {@code reachability-metadata.json}, or the native binary refuses to bind it and
 * {@code --gpu} declines as though the machine had no device.
 * {@link NativeImageDowncalls} carries the why and the same guard for {@code --blas}.
 *
 * <p>
 * Neither driver needs its device here: the constructors are handed a lookup that answers
 * every name with an address that is never called, so both halves run on any machine.
 */
class NativeImageForeignConfigTest {

	@Test
	void everyCudaDowncallShapeIsRegistered() {
		CudaDriver driver = new CudaDriver(NativeImageDowncalls.EVERYTHING);
		assertThat(NativeImageDowncalls.missing(driver.signatures(), driver.criticalSignatures()))
			.as("CUDA downcall shapes with no entry in the native-image metadata -- the binary refuses to bind "
					+ "them, so --gpu declines as though this machine had no NVIDIA driver")
			.isEmpty();
	}

	@Test
	void everyMetalDowncallShapeIsRegistered() {
		MetalDriver driver = new MetalDriver(NativeImageDowncalls.EVERYTHING, NativeImageDowncalls.EVERYTHING,
				NativeImageDowncalls.EVERYTHING);
		assertThat(NativeImageDowncalls.missing(driver.signatures(), Set.of()))
			.as("Metal downcall shapes with no entry in the native-image metadata -- the binary refuses to bind "
					+ "them, so --gpu declines as though this machine were not a Mac")
			.isEmpty();
	}

	@Test
	void theRegistrationACompiledProgramShipsHoldsExactlyBothDriversShapes() {
		// A --gpu output carries am/ik/gpu/reachability-metadata.json into its own
		// META-INF/native-image/ (JvmGpuRuntimeBuilder), so an image built from the jar
		// reaches the device with no configuration. Missing a shape is the same silent
		// CPU fallback as above; an extra one is a shape nothing binds any more.
		CudaDriver cuda = new CudaDriver(NativeImageDowncalls.EVERYTHING);
		MetalDriver metal = new MetalDriver(NativeImageDowncalls.EVERYTHING, NativeImageDowncalls.EVERYTHING,
				NativeImageDowncalls.EVERYTHING);
		Set<String> bound = new LinkedHashSet<>();
		cuda.signatures().forEach(descriptor -> bound.add(NativeImageDowncalls.signature(descriptor, false)));
		cuda.criticalSignatures().forEach(descriptor -> bound.add(NativeImageDowncalls.signature(descriptor, true)));
		metal.signatures().forEach(descriptor -> bound.add(NativeImageDowncalls.signature(descriptor, false)));
		assertThat(NativeImageDowncalls
			.registeredDowncalls(Path.of("src", "main", "resources", "am", "ik", "gpu", "reachability-metadata.json")))
			.containsExactlyInAnyOrderElementsOf(bound);
	}

}
