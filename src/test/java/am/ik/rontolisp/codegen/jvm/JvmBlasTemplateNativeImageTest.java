package am.ik.rontolisp.codegen.jvm;

import java.util.LinkedHashSet;
import java.util.Set;

import am.ik.rontolisp.NativeImageDowncalls;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the downcall registration a compiled {@code --blas} program carries against the
 * shapes its shipped bridge binds. An image built from the program's jar reads only the
 * jar's own {@code META-INF/native-image/}, and one shape missing there makes the image
 * refuse the binding: the bridge then declines as though the machine had no library, with
 * the same printed numbers. {@link NativeImageDowncalls} carries the why.
 */
class JvmBlasTemplateNativeImageTest {

	@Test
	void theRegistrationACompiledProgramShipsHoldsExactlyTheBridgesShapes() {
		// Bound against a lookup that finds everything -- the handles are made, never
		// called -- so every shape, the optional thread-count query included, is recorded
		// on a machine with no CBLAS too. An extra entry is a shape nothing binds any
		// more.
		JvmBlasTemplate.bind(NativeImageDowncalls.EVERYTHING);
		Set<String> bound = new LinkedHashSet<>();
		JvmBlasTemplate.signatures()
			.forEach(descriptor -> bound.add(NativeImageDowncalls.signature(descriptor, false)));
		JvmBlasTemplate.criticalSignatures()
			.forEach(descriptor -> bound.add(NativeImageDowncalls.signature(descriptor, true)));
		assertThat(bound).contains("jint()");
		assertThat(NativeImageDowncalls.registeredDowncalls(NativeImageDowncalls.BLAS))
			.containsExactlyInAnyOrderElementsOf(bound);
	}

}
