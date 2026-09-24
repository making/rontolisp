package am.ik.rontolisp.cli;

import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * Where a {@code --native} output runs and what its machine code may assume of the CPU
 * there: {@code --native-target <os>-<arch>} (the resource directory of the runner stub
 * the output starts with; the host by default) and {@code --native-cpu} (a level the
 * precompile shim understands: {@code baseline}, the default, which every CPU of the
 * platform has; {@code host}, every feature of the compiling machine; or a named level
 * such as {@code x86-64-v3}). The shim owns what each level means (.kb/native-output.md,
 * "CPU baseline and cross-targets").
 *
 * @param platform the platform, one of {@link #PLATFORMS}
 * @param cpu the CPU level
 */
record NativeTarget(String platform, String cpu) {

	/** Every platform a runner stub can be built for, as its resource directory. */
	static final List<String> PLATFORMS = List.of("linux-x86_64", "linux-aarch64", "macos-aarch64", "macos-x86_64");

	/** The default CPU level: what every CPU of the platform has. */
	static final String BASELINE = "baseline";

	/**
	 * The target the two options name.
	 * @param platform {@code --native-target}, or {@code null} for the host
	 * @param cpu {@code --native-cpu}, or {@code null} for {@value #BASELINE}
	 * @return the target
	 * @throws IllegalArgumentException when the platform is not one of {@link #PLATFORMS}
	 * or a value is empty
	 * @throws UnsupportedOperationException when no platform is named and the host is not
	 * one of them
	 */
	static NativeTarget of(@Nullable String platform, @Nullable String cpu) {
		if (platform != null && !PLATFORMS.contains(platform)) {
			throw new IllegalArgumentException("--native-target " + (platform.isEmpty() ? "needs a value" : platform)
					+ ": expected one of " + String.join(", ", PLATFORMS));
		}
		if (cpu != null && cpu.isEmpty()) {
			throw new IllegalArgumentException("--native-cpu needs a value: " + BASELINE + " (the default), host, or a"
					+ " level such as x86-64-v3");
		}
		String resolved = platform != null ? platform : hostPlatform();
		if (resolved == null) {
			throw new UnsupportedOperationException("--native is not available for "
					+ System.getProperty("os.name", "?") + "-" + System.getProperty("os.arch", "?"));
		}
		return new NativeTarget(resolved, cpu != null ? cpu : BASELINE);
	}

	/**
	 * The host's platform, or {@code null} for a host no runner stub is built for.
	 * @return {@code linux-x86_64}, {@code macos-aarch64}, ... or {@code null}
	 */
	static @Nullable String hostPlatform() {
		return NativeExecutable.platform(System.getProperty("os.name", ""), System.getProperty("os.arch", ""));
	}

}
