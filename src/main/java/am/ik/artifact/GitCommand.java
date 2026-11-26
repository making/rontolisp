package am.ik.artifact;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs the {@code git} command line: the one place this package starts a process. Every
 * invocation is non-interactive (stdin closed; {@code GIT_TERMINAL_PROMPT=0} unless the
 * user's environment sets it, so a missing credential fails instead of waiting for a
 * prompt nobody sees) and ignores the repository variables of the caller's environment
 * ({@code GIT_DIR} and kin), so a run started from inside a git hook still works on the
 * repository it names. {@code protocol.ext.allow=never} keeps a URL from running a
 * command.
 *
 * <p>
 * The browser build substitutes {@link #exec} (no processes there).
 */
final class GitCommand {

	/** The variables that would point git at another repository than the one named. */
	private static final List<String> REPOSITORY_VARIABLES = List.of("GIT_DIR", "GIT_WORK_TREE", "GIT_INDEX_FILE",
			"GIT_OBJECT_DIRECTORY", "GIT_ALTERNATE_OBJECT_DIRECTORIES", "GIT_COMMON_DIR", "GIT_NAMESPACE");

	private final String executable;

	GitCommand(String executable) {
		this.executable = executable;
	}

	/**
	 * The outcome of one git run.
	 *
	 * @param exitCode the exit status
	 * @param stdout the standard output, UTF-8
	 * @param stderr the standard error, UTF-8
	 */
	record Result(int exitCode, String stdout, String stderr) {

		boolean ok() {
			return this.exitCode == 0;
		}

	}

	/**
	 * Runs git and answers its outcome whatever the exit status.
	 * @param env variables to set for this run
	 * @param args the arguments after {@code git}
	 * @return the outcome
	 * @throws IOException if git cannot be started (not on {@code PATH}) or is
	 * interrupted
	 */
	Result exec(Map<String, String> env, List<String> args) throws IOException {
		List<String> command = new ArrayList<>();
		command.add(this.executable);
		command.add("-c");
		command.add("protocol.ext.allow=never");
		command.addAll(args);
		ProcessBuilder builder = new ProcessBuilder(command);
		Map<String, String> environment = builder.environment();
		REPOSITORY_VARIABLES.forEach(environment::remove);
		environment.putIfAbsent("GIT_TERMINAL_PROMPT", "0");
		environment.putAll(env);
		Process process;
		try {
			process = builder.start();
		}
		catch (IOException ex) {
			throw new IOException("the git command '" + this.executable
					+ "' cannot be run (is git installed and on PATH?): " + ex.getMessage(), ex);
		}
		process.getOutputStream().close();
		AtomicReference<byte[]> stderr = new AtomicReference<>(new byte[0]);
		Thread errReader = Thread.ofPlatform()
			.daemon()
			.name("git-stderr")
			.start(() -> stderr.set(readAll(process.getErrorStream())));
		try {
			byte[] stdout = readAll(process.getInputStream());
			int exitCode = process.waitFor();
			errReader.join();
			return new Result(exitCode, new String(stdout, StandardCharsets.UTF_8),
					new String(stderr.get(), StandardCharsets.UTF_8));
		}
		catch (InterruptedException ex) {
			process.destroyForcibly();
			Thread.currentThread().interrupt();
			throw new IOException("git " + String.join(" ", args) + " was interrupted", ex);
		}
		catch (UncheckedIOException ex) {
			throw ex.getCause();
		}
	}

	/**
	 * Runs git and answers its standard output, failing on a non-zero exit status.
	 * @param env variables to set for this run
	 * @param args the arguments after {@code git}
	 * @return the standard output
	 * @throws IOException if git cannot be started or exits non-zero; the message carries
	 * git's own error output
	 */
	String run(Map<String, String> env, List<String> args) throws IOException {
		Result result = exec(env, args);
		if (!result.ok()) {
			throw new IOException("git " + String.join(" ", args) + " failed (exit " + result.exitCode() + "): "
					+ result.stderr().strip());
		}
		return result.stdout();
	}

	private static byte[] readAll(InputStream in) {
		try (in) {
			return in.readAllBytes();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
