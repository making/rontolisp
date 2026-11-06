package am.ik.rontolisp.runtime;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The one subprocess spawn behind {@code uiop:run-program}: start a program, wait for it
 * and answer its exit code. The interpreter calls {@link #spawn}; a compiled class calls
 * {@link #run} with its own value representation, and this class travels with it. Both
 * WASM backends have no process to start, and {@code uiop:run-program} there signals
 * without reaching this (see {@code .kb/uiop.md}).
 *
 * <p>
 * Everything a caller decides about the streams arrives as one {@code modes} word plus a
 * path per stream; everything else -- the slurping of an output into a string, a stream
 * or a function, an input from a string, {@code :if-output-exists :error} -- is the Lisp
 * side's, which hands this a file for each. A stream is the null device, the parent's
 * own, or a file.
 */
public final class RontoProcess {

	/** {@code modes} bit: the child reads this process's standard input. */
	public static final int INHERIT_INPUT = 1;

	/** {@code modes} bit: the child writes this process's standard output. */
	public static final int INHERIT_OUTPUT = 2;

	/** {@code modes} bit: the child writes this process's standard error. */
	public static final int INHERIT_ERROR_OUTPUT = 4;

	/** {@code modes} bit: an output file is appended to rather than superseded. */
	public static final int APPEND_OUTPUT = 8;

	/** {@code modes} bit: an error-output file is appended to. */
	public static final int APPEND_ERROR_OUTPUT = 16;

	/** {@code modes} bit: the error output goes where the output goes. */
	public static final int ERROR_TO_OUTPUT = 32;

	/**
	 * The separator of {@code argv}: the one character no argument of a C program's
	 * {@code argv} can hold, so joining on it loses nothing.
	 */
	public static final char ARGUMENT_SEPARATOR = '\u0000';

	private RontoProcess() {
	}

	/**
	 * Runs a program to completion.
	 * @param argv the program and its arguments, joined by {@link #ARGUMENT_SEPARATOR}
	 * @param directory the working directory, or {@code null} for this process's own
	 * @param input the file the child reads, or {@code null}: the null device unless
	 * {@link #INHERIT_INPUT}
	 * @param output the file the child writes, or {@code null}: the null device unless
	 * {@link #INHERIT_OUTPUT}
	 * @param errorOutput the file the child writes its errors to, or {@code null}: the
	 * null device unless {@link #INHERIT_ERROR_OUTPUT} or {@link #ERROR_TO_OUTPUT}
	 * @param modes the bits above
	 * @return the exit code
	 * @throws IOException when the program cannot be started
	 * @throws InterruptedException when the wait is interrupted
	 */
	public static long spawn(String argv, String directory, String input, String output, String errorOutput, int modes)
			throws IOException, InterruptedException {
		List<String> command = new ArrayList<>();
		int start = 0;
		for (int i = 0; i <= argv.length(); i++) {
			if (i == argv.length() || argv.charAt(i) == ARGUMENT_SEPARATOR) {
				command.add(argv.substring(start, i));
				start = i + 1;
			}
		}
		ProcessBuilder builder = new ProcessBuilder(command);
		if (directory != null) {
			builder.directory(new File(directory));
		}
		boolean closeInput = false;
		if ((modes & INHERIT_INPUT) != 0) {
			builder.redirectInput(ProcessBuilder.Redirect.INHERIT);
		}
		else if (input != null) {
			builder.redirectInput(new File(input));
		}
		else {
			// No null-device redirect for an input exists: an input pipe closed at once
			// reads as an empty file, on every host.
			closeInput = true;
		}
		builder.redirectOutput(redirect(output, (modes & INHERIT_OUTPUT) != 0, (modes & APPEND_OUTPUT) != 0));
		if ((modes & ERROR_TO_OUTPUT) != 0) {
			builder.redirectErrorStream(true);
		}
		else {
			builder.redirectError(
					redirect(errorOutput, (modes & INHERIT_ERROR_OUTPUT) != 0, (modes & APPEND_ERROR_OUTPUT) != 0));
		}
		Process process = builder.start();
		if (closeInput) {
			process.getOutputStream().close();
		}
		return process.waitFor();
	}

	private static ProcessBuilder.Redirect redirect(String file, boolean inherit, boolean append) {
		if (inherit) {
			return ProcessBuilder.Redirect.INHERIT;
		}
		if (file == null) {
			return ProcessBuilder.Redirect.DISCARD;
		}
		return append ? ProcessBuilder.Redirect.appendTo(new File(file)) : ProcessBuilder.Redirect.to(new File(file));
	}

	/**
	 * {@link #spawn} for a compiled class: each string argument a runtime string (the
	 * text between its surrounding quotes) or {@code null} for nil, already rendered from
	 * a character vector by the class, and {@code modes} a fixnum.
	 * @param argv the joined arguments
	 * @param directory the working directory or nil
	 * @param input the input file or nil
	 * @param output the output file or nil
	 * @param errorOutput the error-output file or nil
	 * @param modes the mode bits
	 * @return the exit code, as a fixnum
	 */
	public static Object run(Object argv, Object directory, Object input, Object output, Object errorOutput,
			Object modes) {
		try {
			return spawn(text(argv), text(directory), text(input), text(output), text(errorOutput),
					((Number) modes).intValue());
		}
		catch (IOException ex) {
			throw new IllegalStateException(failure(ex));
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(interrupted());
		}
	}

	/**
	 * What a program that cannot be started reports, on every backend that starts one:
	 * the host's own sentence, which names the program and, given one, the directory.
	 * @param ex the failure
	 * @return the message
	 */
	public static String failure(IOException ex) {
		return "RUN-PROGRAM: " + ex.getMessage();
	}

	/**
	 * What an interrupted wait reports, on every backend that starts a program.
	 * @return the message
	 */
	public static String interrupted() {
		return "RUN-PROGRAM: interrupted while waiting for the program to exit";
	}

	// nil is null in the compiled representation, and spawn takes null for "none"; this
	// package cannot import the annotation that would say so (it would travel with the
	// class).
	@SuppressWarnings("NullAway")
	private static String text(Object value) {
		if (value == null) {
			return null;
		}
		String quoted = (String) value;
		return quoted.substring(1, quoted.length() - 1);
	}

}
