package am.ik.rontolisp.cli;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

import am.ik.rontolisp.NativeImageDowncalls;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
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
		String host = Objects.requireNonNull(NativeTarget.hostPlatform());
		assertThat(NativeExecutable.stubFingerprints(toolchain.stub(host))).singleElement()
			.asString()
			.startsWith(NativeToolchain.ABI + ";");
		assertThatThrownBy(() -> toolchain.precompile(new byte[] { 0, 'a', 's', 'm', 9, 9, 9, 9 }, target(host)))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageStartingWith("--native: wasmtime could not precompile the module: ");
	}

	@Test
	void theHostShimPrecompilesForEveryPlatformAndChecksTheCpuLevel() {
		String host = hostToolchainOrSkip();
		NativeToolchain toolchain = NativeToolchain.load();
		for (String platform : NativeTarget.PLATFORMS) {
			// ELF on every platform: wasmtime's own image format, macOS included.
			assertThat(toolchain.precompile(EMPTY_MODULE, target(platform)))
				.startsWith(new byte[] { 0x7f, 'E', 'L', 'F' });
		}
		toolchain.check(target(host));
		toolchain.check(new NativeTarget(host, "host"));
		assertThatThrownBy(() -> toolchain.check(new NativeTarget(host, "pentium")))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageStartingWith("--native: unknown --native-cpu 'pentium' for " + host + " (known: baseline, host");
		String other = host.equals("linux-aarch64") ? "linux-x86_64" : "linux-aarch64";
		assertThatThrownBy(() -> toolchain.check(new NativeTarget(other, "host")))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining(
					"--native-cpu=host describes this machine, a " + host + "; it cannot target " + other);
	}

	@Test
	void theHostShimAppendsToAnElfStubAndRefusesAMalformedMachO() {
		hostToolchainOrSkip();
		NativeToolchain toolchain = NativeToolchain.load();
		byte[] ascii = "module".getBytes(StandardCharsets.US_ASCII);
		// rlabi::payload::append: stub, module, u64-le length, magic.
		assertThat(toolchain.assemble("STUB".getBytes(StandardCharsets.US_ASCII), ascii))
			.isEqualTo("STUBmodule\6\0\0\0\0\0\0\0RLNATIVE".getBytes(StandardCharsets.US_ASCII));
		assertThatThrownBy(
				() -> toolchain.assemble(new byte[] { (byte) 0xcf, (byte) 0xfa, (byte) 0xed, (byte) 0xfe }, ascii))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageStartingWith("--native: cannot assemble the executable: ")
			.hasMessageContaining("Mach-O");
	}

	@Test
	void aPlatformWithoutAStubIsRefusedNamingTheOnesCarried() {
		String host = hostToolchainOrSkip();
		assumeFalse("macos-x86_64".equals(host), "the one platform no release carries a stub for");
		assertThatThrownBy(() -> NativeToolchain.load().check(target("macos-x86_64")))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageStartingWith("--native-target macos-x86_64: this build of rontolisp carries no runner stub")
			.hasMessageContaining(host);
	}

	private static final byte[] EMPTY_MODULE = { 0, 'a', 's', 'm', 1, 0, 0, 0 };

	private static NativeTarget target(String platform) {
		return new NativeTarget(platform, NativeTarget.BASELINE);
	}

	private static String hostToolchainOrSkip() {
		assumeTrue(NativeToolchain.availableOnHost(), "no --native shim for this host on the classpath");
		return Objects.requireNonNull(NativeTarget.hostPlatform());
	}

}
