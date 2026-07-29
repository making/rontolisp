package am.ik.rontolisp.testsupport;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.testcontainers.images.builder.Transferable;

/**
 * Runs {@code wasmtime} as a plain host process, exposing the two operations the WASM
 * integration tests need -- stage a file, run a command -- under the same names
 * {@link org.testcontainers.containers.GenericContainer} uses, so a test class switches
 * between the two by changing the type of its {@code wasmtime} field.
 *
 * <p>
 * Why a second runner exists next to {@link WasmtimeSupport}: on a 4 vCPU CI runner the
 * container path stopped scaling. Making {@code WasmLispCompilerIntegrationTest}'s
 * methods concurrent cut the class from 1667 s to 228 s on a 64-core development machine
 * and did nothing at all on CI (2170 s before, 2186 s after, comparing runs whose
 * parallelism-independent classes agree to within 1%), and raising the thread cap from 4
 * to 8 changed nothing either. Threads inside the JVM are therefore not the limit; the
 * suspicion is the Docker daemon every worker funnels its exec and file-copy through,
 * which each cost real CPU on a box that has none to spare. This class removes that layer
 * for the one class that dominates the build.
 *
 * <p>
 * The trade is the pinned wasmtime: the container image fixes the version at its
 * {@code Dockerfile} ARG, while here the tests run whatever is on {@code PATH}. Keep the
 * CI install step pinned to the same version as the image so the two paths stay
 * comparable, and see {@link #MINIMUM_MAJOR} for the floor enforced at runtime.
 */
public final class HostWasmtime {

	/** The runner; a host process needs no per-JVM instance state. */
	public static final HostWasmtime INSTANCE = new HostWasmtime();

	/** The {@code --component} / WASI 0.3 tests need wasmtime 46 or newer. */
	public static final int MINIMUM_MAJOR = 46;

	// A guest that never exits would otherwise hang the whole build. Every test that
	// waits on something bounds itself well below this (the longest poll loop is 15 s),
	// so this only ever fires on a genuine hang, and it names the command when it does.
	private static final long TIMEOUT_SECONDS = 300;

	private static final Path ROOT = Path.of(System.getProperty("java.io.tmpdir"), "rontolisp-wasmtime");

	private static final String VERSION = readVersion();

	private HostWasmtime() {
	}

	/**
	 * Whether a usable {@code wasmtime} is on {@code PATH}. Used as the class-level
	 * {@code @EnabledIf} condition, so a machine without one skips the WASM integration
	 * tests the way a machine without Docker used to.
	 * @return {@code true} when {@code wasmtime --version} reports {@link #MINIMUM_MAJOR}
	 * or newer
	 */
	public static boolean isAvailable() {
		return major(VERSION) >= MINIMUM_MAJOR;
	}

	/**
	 * Returns what the host wasmtime reports about itself, for the skip message and for
	 * telling two runs apart when they disagree.
	 * @return the {@code wasmtime --version} output, or empty if there is none
	 */
	public static String version() {
		return VERSION;
	}

	/**
	 * Writes {@code content} to {@code path}, creating the parent directories. Mirrors
	 * {@code GenericContainer.copyFileToContainer} including its unchecked failure mode,
	 * so call sites need no try/catch.
	 * @param content the bytes to stage
	 * @param path the absolute host path to write
	 */
	public void copyFileToContainer(Transferable content, String path) {
		Path target = Path.of(path);
		try {
			Files.createDirectories(target.getParent());
			Files.write(target, content.getBytes());
		}
		catch (IOException e) {
			throw new UncheckedIOException("cannot stage " + path, e);
		}
	}

	/**
	 * Runs {@code command} to completion. Both streams are redirected to files rather
	 * than drained by the caller, so a program that outfills a pipe buffer cannot
	 * deadlock the test.
	 * @param command the argv, e.g. {@code "sh", "-c", script}
	 * @return its exit code and captured output
	 * @throws IOException if the process cannot be started or its output cannot be read
	 * @throws InterruptedException if the wait is interrupted
	 */
	public ExecResult execInContainer(String... command) throws IOException, InterruptedException {
		Files.createDirectories(ROOT);
		Path out = Files.createTempFile(ROOT, "exec", ".out");
		Path err = Files.createTempFile(ROOT, "exec", ".err");
		try {
			Process process = new ProcessBuilder(command).directory(ROOT.toFile())
				.redirectOutput(out.toFile())
				.redirectError(err.toFile())
				.start();
			if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
				process.destroyForcibly().waitFor();
				throw new IllegalStateException(
						"wasmtime command timed out after " + TIMEOUT_SECONDS + "s: " + String.join(" ", command));
			}
			return new ExecResult(process.exitValue(), read(out), read(err));
		}
		finally {
			Files.deleteIfExists(out);
			Files.deleteIfExists(err);
		}
	}

	// Decode leniently rather than with Files.readString: a guest is free to write bytes
	// that are not UTF-8, and the container runner substituted rather than throwing.
	private static String read(Path file) throws IOException {
		return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
	}

	private static String readVersion() {
		try {
			Process process = new ProcessBuilder("wasmtime", "--version").redirectErrorStream(true).start();
			String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
			return process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0 ? output : "";
		}
		catch (IOException e) {
			return "";
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return "";
		}
	}

	// "wasmtime 47.0.2 (90fed3c6a 2026-07-21)" -> 47
	private static int major(String version) {
		String[] words = version.split("\\s+");
		if (words.length < 2) {
			return -1;
		}
		try {
			return Integer.parseInt(words[1].split("\\.")[0]);
		}
		catch (NumberFormatException e) {
			return -1;
		}
	}

	/**
	 * The result of {@link #execInContainer}. Named after
	 * {@code org.testcontainers.containers.Container.ExecResult}, whose constructor is
	 * not visible outside its own package, so the tests can swap runners by changing one
	 * import.
	 *
	 * @param exitCode the process exit code
	 * @param stdout everything the process wrote to stdout
	 * @param stderr everything the process wrote to stderr
	 */
	public record ExecResult(int exitCode, String stdout, String stderr) {

		/**
		 * Alias matching the container runner's accessor name.
		 * @return {@link #exitCode()}
		 */
		public int getExitCode() {
			return this.exitCode;
		}

		/**
		 * Alias matching the container runner's accessor name.
		 * @return {@link #stdout()}
		 */
		public String getStdout() {
			return this.stdout;
		}

		/**
		 * Alias matching the container runner's accessor name.
		 * @return {@link #stderr()}
		 */
		public String getStderr() {
			return this.stderr;
		}
	}

}
