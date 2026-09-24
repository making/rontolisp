package am.ik.rontolisp.cli;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

import am.ik.rontolisp.NativeImageDowncalls;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The loader of the {@code --native} shim and stub: the refusals, the extraction cache
 * and the native-image registration of its downcalls. The halves that need the built shim
 * run only where {@code rontolisp-native/build.sh} put it on the classpath, and fail
 * without it under {@code -Drontolisp.native.required=true}.
 */
class NativeToolchainTest {

	@TempDir
	Path tempDir;

	@Test
	void everyDowncallShapeIsRegisteredForNativeImage() {
		assertThat(NativeImageDowncalls.missing(new LinkedHashSet<>(NativeToolchain.DOWNCALLS), Set.of()))
			.as("--native downcall shapes with no entry in the native-image metadata -- the binary would refuse "
					+ "to bind the precompile shim")
			.isEmpty();
	}

	@Test
	void anUnknownHostIsRefusedByName() {
		assertThatThrownBy(() -> NativeToolchain.open(null, this.tempDir))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageStartingWith("--native is not available for ");
	}

	@Test
	void aPlatformThisBuildCarriesNothingForIsRefusedBeforeAnythingIsExtracted() {
		assertThatThrownBy(() -> NativeToolchain.open("linux-riscv64", this.tempDir))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("--native is not available for linux-riscv64")
			.hasMessageContaining("rontolisp-native/build.sh");
		assertThat(this.tempDir).isEmptyDirectory();
	}

	@Test
	void extractionWritesOnceAndLeavesNoTemporaryFile() throws Exception {
		Path target = this.tempDir.resolve("native/0123456789abcdef/librlprecomp.so");
		byte[] bytes = "shim".getBytes(StandardCharsets.US_ASCII);
		assertThat(NativeToolchain.extract(bytes, target)).hasBinaryContent(bytes);
		// A second compile finds it in place and leaves it alone.
		long modified = Files.getLastModifiedTime(target).toMillis();
		Files.setLastModifiedTime(target, java.nio.file.attribute.FileTime.fromMillis(modified - 60_000));
		NativeToolchain.extract(bytes, target);
		assertThat(Files.getLastModifiedTime(target).toMillis()).isEqualTo(modified - 60_000);
		assertThat(target.getParent()).isDirectoryContaining(path -> path.equals(target))
			.isDirectoryNotContaining("glob:**.tmp");
	}

	@Test
	void aTruncatedExtractionIsReplaced() throws Exception {
		Path target = this.tempDir.resolve("native/0123456789abcdef/librlprecomp.so");
		Files.createDirectories(target.getParent());
		Files.writeString(target, "sh");
		byte[] bytes = "shim".getBytes(StandardCharsets.US_ASCII);
		assertThat(NativeToolchain.extract(bytes, target)).hasBinaryContent(bytes);
	}

	@Test
	void theCacheIsThePropertyThenXdgThenThePlatformsOwn() {
		assertThat(NativeToolchain.cacheRoot("/c", "/x", "/home/u", "Linux")).isEqualTo(Path.of("/c"));
		assertThat(NativeToolchain.cacheRoot(null, "/x", "/home/u", "Linux")).isEqualTo(Path.of("/x/rontolisp"));
		// A relative XDG_CACHE_HOME is invalid by the spec and ignored.
		assertThat(NativeToolchain.cacheRoot(null, "x", "/home/u", "Linux"))
			.isEqualTo(Path.of("/home/u/.cache/rontolisp"));
		assertThat(NativeToolchain.cacheRoot("", null, "/Users/u", "Mac OS X"))
			.isEqualTo(Path.of("/Users/u/Library/Caches/rontolisp"));
	}

	@Test
	void theHostShimReportsTheStubsFingerprintAndReportsARefusedModule() {
		if (Boolean.getBoolean("rontolisp.native.required")) {
			assertThat(NativeToolchain.availableOnHost()).as("-Drontolisp.native.required=true: the host's pair")
				.isTrue();
		}
		assumeTrue(NativeToolchain.availableOnHost(), "no --native shim for this host on the classpath");
		NativeToolchain toolchain = NativeToolchain.load();
		assertThat(NativeExecutable.stubFingerprints(toolchain.stub())).singleElement()
			.asString()
			.startsWith("rlnative-abi=");
		assertThatThrownBy(() -> toolchain.precompile(new byte[] { 0, 'a', 's', 'm', 9, 9, 9, 9 }))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageStartingWith("--native: wasmtime could not precompile the module: ");
	}

}
