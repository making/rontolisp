package am.ik.rontolisp.cli;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.jspecify.annotations.Nullable;

/**
 * The layout of a {@code --native} output and the one step that writes it: the runner
 * stub, the precompiled module, the module's length as a little-endian {@code u64} and
 * the magic {@code RLNATIVE} -- what {@code rlabi::payload} in {@code rontolisp-native/}
 * defines and the stub reads back from its own executable (.kb/native-output.md).
 */
final class NativeExecutable {

	/** The last eight bytes of every output. */
	static final byte[] MAGIC = "RLNATIVE".getBytes(StandardCharsets.US_ASCII);

	/** Length field plus magic. */
	static final int TRAILER_LEN = 16;

	/**
	 * What a runner stub carries in its read-only data in front of its engine
	 * fingerprint, which runs up to a NUL ({@code rlabi::STUB_MARKER}).
	 */
	static final byte[] STUB_MARKER_PREFIX = "RLNATIVE-FINGERPRINT=".getBytes(StandardCharsets.US_ASCII);

	/** The longest fingerprint a scan will read before giving up on a candidate. */
	private static final int MAX_FINGERPRINT = 1024;

	private NativeExecutable() {
	}

	/**
	 * The executable that runs {@code module} under {@code stub}.
	 * @param stub the runner stub's bytes
	 * @param module the module the shim precompiled
	 * @return {@code stub ++ module ++ u64-le(module.length) ++ "RLNATIVE"}
	 */
	static byte[] assemble(byte[] stub, byte[] module) {
		return ByteBuffer.allocate(Math.addExact(Math.addExact(stub.length, module.length), TRAILER_LEN))
			.order(ByteOrder.LITTLE_ENDIAN)
			.put(stub)
			.put(module)
			.putLong(module.length)
			.put(MAGIC)
			.array();
	}

	/**
	 * Every fingerprint a stub's bytes spell after {@link #STUB_MARKER_PREFIX}, in file
	 * order. A candidate is the bytes up to the next NUL; one with no NUL within reach is
	 * not a marker and is skipped. The runner keeps exactly one marker, but the prefix
	 * could also occur as unrelated data, so the caller asks whether its fingerprint is
	 * AMONG these rather than taking the first.
	 * @param stub the runner stub's bytes
	 * @return the fingerprints, possibly none
	 */
	static List<String> stubFingerprints(byte[] stub) {
		List<String> found = new ArrayList<>();
		int last = stub.length - STUB_MARKER_PREFIX.length;
		outer: for (int i = 0; i <= last; i++) {
			for (int j = 0; j < STUB_MARKER_PREFIX.length; j++) {
				if (stub[i + j] != STUB_MARKER_PREFIX[j]) {
					continue outer;
				}
			}
			int start = i + STUB_MARKER_PREFIX.length;
			int end = Math.min(stub.length, start + MAX_FINGERPRINT);
			for (int k = start; k < end; k++) {
				if (stub[k] == 0) {
					if (k > start) {
						found.add(new String(stub, start, k - start, StandardCharsets.UTF_8));
					}
					break;
				}
			}
		}
		return found;
	}

	/**
	 * Writes an executable to {@code output}: into a temporary file beside it, marked
	 * executable, then moved over the destination in one step. Writing in place would
	 * fail with ETXTBSY while the previous build of the same output is still running, and
	 * a crash midway would leave a truncated program where a working one was.
	 * @param output the path {@code -o} named
	 * @param executable the bytes {@link #assemble} produced
	 */
	static void write(Path output, byte[] executable) {
		Path absolute = output.toAbsolutePath();
		Path dir = absolute.getParent();
		try {
			if (dir != null) {
				Files.createDirectories(dir);
			}
			Path tmp = Files.createTempFile(dir, "." + absolute.getFileName(), ".tmp");
			try {
				Files.write(tmp, executable);
				markExecutable(tmp);
				Files.move(tmp, absolute, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			}
			finally {
				Files.deleteIfExists(tmp);
			}
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/**
	 * Mode 0755, what {@code rlpack} and a linker under the usual umask give an
	 * executable, instead of the temporary file's owner-only 0600.
	 */
	private static void markExecutable(Path file) throws IOException {
		PosixFileAttributeView view = Files.getFileAttributeView(file, PosixFileAttributeView.class);
		if (view == null) {
			if (!file.toFile().setExecutable(true, false)) {
				throw new IOException("cannot mark " + file + " executable");
			}
			return;
		}
		Set<PosixFilePermission> permissions = EnumSet.of(PosixFilePermission.OWNER_READ,
				PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_READ,
				PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_EXECUTE);
		view.setPermissions(permissions);
	}

	/**
	 * The resource directory name of the host, {@code <os>-<arch>} as
	 * {@code rontolisp-native/build.sh} lays it out, or {@code null} for a host no build
	 * exists for.
	 * @param osName the {@code os.name} property
	 * @param osArch the {@code os.arch} property
	 * @return {@code linux-x86_64}, {@code macos-aarch64}, ... or {@code null}
	 */
	static @Nullable String platform(String osName, String osArch) {
		String name = osName.toLowerCase(Locale.ROOT);
		String os = name.startsWith("linux") ? "linux" : name.startsWith("mac") ? "macos" : null;
		String arch = switch (osArch.toLowerCase(Locale.ROOT)) {
			case "amd64", "x86_64" -> "x86_64";
			case "aarch64", "arm64" -> "aarch64";
			default -> null;
		};
		return os == null || arch == null ? null : os + "-" + arch;
	}

}
