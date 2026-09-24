package am.ik.rontolisp.cli;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code --native} output layout, byte for byte what {@code rlabi::payload} in
 * {@code rontolisp-native/abi} writes and the runner stub reads back.
 */
class NativeExecutableTest {

	@TempDir
	Path tempDir;

	@Test
	void anOutputIsStubModuleLittleEndianLengthAndMagic() {
		byte[] exe = NativeExecutable.assemble(ascii("STUB"), ascii("module"));
		// The same bytes rlabi's payload_round_trips test pins on the Rust side.
		assertThat(exe)
			.containsExactly(concat(ascii("STUBmodule"), new byte[] { 6, 0, 0, 0, 0, 0, 0, 0 }, ascii("RLNATIVE")));
		assertThat(exe).hasSize(4 + 6 + NativeExecutable.TRAILER_LEN);
	}

	@Test
	void theLengthFieldLocatesTheModuleFromTheEnd() {
		byte[] module = new byte[70_000];
		Arrays.fill(module, (byte) 7);
		byte[] exe = NativeExecutable.assemble(new byte[123], module);
		ByteBuffer trailer = ByteBuffer.wrap(exe, exe.length - NativeExecutable.TRAILER_LEN, 8)
			.order(ByteOrder.LITTLE_ENDIAN);
		long length = trailer.getLong();
		assertThat(length).isEqualTo(module.length);
		int start = (int) (exe.length - NativeExecutable.TRAILER_LEN - length);
		assertThat(start).isEqualTo(123);
		assertThat(Arrays.copyOfRange(exe, start, start + module.length)).isEqualTo(module);
	}

	@Test
	void theStubFingerprintIsReadUpToItsNul() {
		byte[] stub = concat(ascii("\u007fELF..."), ascii("RLNATIVE-FINGERPRINT=rlnative-abi=1;wasmtime=49.0.0"),
				new byte[] { 0 }, ascii("rest"));
		assertThat(NativeExecutable.stubFingerprints(stub)).containsExactly("rlnative-abi=1;wasmtime=49.0.0");
	}

	@Test
	void aPrefixWithNoNulInReachOrNothingAfterItIsNotAMarker() {
		byte[] unterminated = concat(ascii("RLNATIVE-FINGERPRINT="), new byte[2000]);
		Arrays.fill(unterminated, 21, unterminated.length, (byte) 'x');
		assertThat(NativeExecutable.stubFingerprints(unterminated)).isEmpty();
		assertThat(NativeExecutable.stubFingerprints(concat(ascii("RLNATIVE-FINGERPRINT="), new byte[] { 0 })))
			.isEmpty();
		assertThat(NativeExecutable.stubFingerprints(ascii("no marker at all"))).isEmpty();
		// A prefix cut off by the end of the file is not a marker either.
		assertThat(NativeExecutable.stubFingerprints(ascii("RLNATIVE-FINGER"))).isEmpty();
	}

	@Test
	void everyMarkerIsACandidate() {
		byte[] stub = concat(ascii("RLNATIVE-FINGERPRINT=a"), new byte[] { 0 }, ascii("RLNATIVE-FINGERPRINT=b"),
				new byte[] { 0 });
		assertThat(NativeExecutable.stubFingerprints(stub)).containsExactly("a", "b");
	}

	@Test
	void theHostMapsToBuildShsPlatformNames() {
		assertThat(NativeExecutable.platform("Linux", "amd64")).isEqualTo("linux-x86_64");
		assertThat(NativeExecutable.platform("Linux", "aarch64")).isEqualTo("linux-aarch64");
		assertThat(NativeExecutable.platform("Mac OS X", "aarch64")).isEqualTo("macos-aarch64");
		assertThat(NativeExecutable.platform("Mac OS X", "x86_64")).isEqualTo("macos-x86_64");
		assertThat(NativeExecutable.platform("Windows 11", "amd64")).isNull();
		assertThat(NativeExecutable.platform("Linux", "riscv64")).isNull();
	}

	@Test
	void writingReplacesAnOutputWholeAndMarksItExecutable() throws Exception {
		Path output = this.tempDir.resolve("sub/prog");
		NativeExecutable.write(output, ascii("first build, longer than the second"));
		NativeExecutable.write(output, ascii("second"));
		assertThat(output).hasBinaryContent(ascii("second"));
		assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(output))).isEqualTo("rwxr-xr-x");
		assertThat(output.getParent()).isDirectoryNotContaining("glob:**.tmp");
	}

	private static byte[] ascii(String s) {
		return s.getBytes(StandardCharsets.ISO_8859_1);
	}

	private static byte[] concat(byte[]... parts) {
		ByteBuffer buffer = ByteBuffer.allocate(Arrays.stream(parts).mapToInt(p -> p.length).sum());
		for (byte[] part : parts) {
			buffer.put(part);
		}
		return buffer.array();
	}

}
