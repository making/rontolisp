package am.ik.gpu;

import am.ik.rontolisp.NativeImageDowncalls;
import org.junit.jupiter.api.Test;

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

}
