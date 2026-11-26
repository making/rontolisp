package am.ik.rontolisp.cli;

import am.ik.rontolisp.reader.Features;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code :rontolisp-native}: the one feature that lets a source tell a {@code --native}
 * output apart from an ordinary Preview 1 {@code .wasm} -- the module inside is otherwise
 * the identical P1 build either way (nothing in it says "native"), but the runner around
 * it answers imports P1 alone does not have ({@code rontolisp:fetch} through
 * {@code rlrun-net}, {@code objc:}/{@code appkit:}/{@code metal:}/{@code scene:} through
 * {@code rlobjc} on {@code macos-aarch64}), so a source that wants "fetch on native, fall
 * back on Preview 1" needs a read-time guard.
 */
class NativeFeatureTest {

	private static Features features(boolean component, @Nullable String nativePlatform) {
		return CompileFrontend
			.run(CompileFrontend.Request.builder()
				.source("(print 1)")
				.options(CompileFrontend.Options.builder()
					.wasm(true)
					.component(component)
					.nativePlatform(nativePlatform)
					.build())
				.build())
			.features();
	}

	@Test
	void aNativeOutputCarriesTheNativeFeature() {
		assertThat(features(false, "linux-x86_64").contains(Features.NATIVE)).isTrue();
	}

	@Test
	void aPreview1WasmDoesNotCarryIt() {
		assertThat(features(false, null).contains(Features.NATIVE)).isFalse();
	}

	@Test
	void aComponentBuildDoesNotCarryItEvenWithANativePlatformNamed() {
		// The CLI refuses --component beside --native outright (RontoLispCli), but
		// Options.runnerHosted() is the guard that makes that refusal sound: it must stay
		// false whenever component() is set, or a caller that skipped the CLI's own
		// validation would get a module announcing a runner it does not run inside.
		assertThat(features(true, "linux-x86_64").contains(Features.NATIVE)).isFalse();
	}

}
