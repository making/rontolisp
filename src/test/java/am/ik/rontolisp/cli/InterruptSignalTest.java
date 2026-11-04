package am.ik.rontolisp.cli;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sun.misc.Signal;
import sun.misc.SignalHandler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * A real SIGINT at the interpreter's process: signalled as
 * {@code rontolisp:interactive-interrupt} once a handler names it, and the host's own
 * ending otherwise. Each program prints {@code ready} where it wants the signal.
 */
class InterruptSignalTest {

	private static final String READY = "(write-line \"ready\") (finish-output)";

	@TempDir
	Path dir;

	/**
	 * A process started with SIGINT ignored (a non-interactive shell's {@code &}) passes
	 * that to its children, and a JVM honours it, as {@code nohup} asks.
	 */
	@BeforeAll
	static void sigintIsNotIgnored() {
		Signal sigint = new Signal("INT");
		SignalHandler current = Signal.handle(sigint, SignalHandler.SIG_DFL);
		Signal.handle(sigint, current);
		assumeFalse(current == SignalHandler.SIG_IGN, "this process was started with SIGINT ignored");
	}

	@Test
	void aHandlerTakesEverySignalWhereverTheProgramIs() throws Exception {
		Result result = run(List.of(), false, """
				(format t "~S~%%"
				        (handler-case (unwind-protect (progn %1$s (sleep 600))
				                        (write-line "cleanup"))
				          (rontolisp:interactive-interrupt (c) (type-of c))))
				(format t "~S~%%"
				        (block nil
				          (handler-bind ((rontolisp:interactive-interrupt
				                           (lambda (c) (declare (ignore c)) (return :left))))
				            %1$s
				            (loop))))
				""".formatted(READY));
		assertThat(result.exit()).as(result.lines().toString()).isZero();
		assertThat(result.lines()).containsExactly("ready", "cleanup", "RONTOLISP:INTERACTIVE-INTERRUPT", "ready",
				":LEFT");
	}

	@Test
	void withUserAbortEndsAProgramAsClingonDoes() throws Exception {
		String fixture = Path.of("src/test/resources/with-user-abort").toAbsolutePath().toString();
		Result result = run(List.of("--system-path", fixture), false, """
				(asdf:load-system :with-user-abort)
				(handler-case (with-user-abort:with-user-abort %s (sleep 600))
				  (with-user-abort:user-abort ()
				    (write-line "Received SIGINT, exiting.")
				    (uiop:quit 130)))
				""".formatted(READY));
		assertThat(result.exit()).as(result.lines().toString()).isEqualTo(130);
		assertThat(result.lines()).containsExactly("ready", "Received SIGINT, exiting.");
	}

	@Test
	void pastADecliningHandlerTheProcessEndsWithTheStatusOfSigint() throws Exception {
		Result result = run(List.of(), false,
				"""
						(handler-bind ((rontolisp:interactive-interrupt (lambda (c) (declare (ignore c)) (write-line "declined"))))
						  (unwind-protect (progn %s (sleep 600))
						    (write-line "cleanup")))
						(write-line "not reached")
						"""
					.formatted(READY));
		assertThat(result.exit()).as(result.lines().toString()).isEqualTo(130);
		// no cleanup: the ending is a quit, as SBCL's for an interrupt nothing handled
		assertThat(result.lines()).containsExactly("ready", "declined");
	}

	@Test
	void aProgramNamingNoHandlerKeepsTheHostsEnding() throws Exception {
		Result result = run(List.of(), false, """
				(handler-case (progn %s (sleep 600))
				  (serious-condition () (write-line "caught")))
				""".formatted(READY));
		assertThat(result.exit()).as(result.lines().toString()).isEqualTo(130);
		assertThat(result.lines()).containsExactly("ready");
	}

	@Test
	void aSignalNotReachedLeavesTheNextOneEndingTheProcess() throws Exception {
		Result result = run(List.of(), true, """
				(handler-case (progn %s (read-line))
				  (rontolisp:interactive-interrupt () (write-line "caught")))
				""".formatted(READY));
		assertThat(result.exit()).as(result.lines().toString()).isEqualTo(130);
		assertThat(result.lines()).containsExactly("ready");
	}

	/**
	 * Runs the CLI's {@code main} as its own process, standard input open, sending SIGINT
	 * on each {@code ready} line and, if asked to insist, then every half second.
	 */
	private Result run(List<String> options, boolean insist, String program) throws Exception {
		Path file = this.dir.resolve("program.lisp");
		Files.writeString(file, program);
		List<String> command = new ArrayList<>(List.of(ProcessHandle.current().info().command().orElse("java"), "-cp",
				System.getProperty("java.class.path"), "am.ik.rontolisp.cli.RontoLispCli"));
		command.addAll(options);
		command.add(file.toString());
		Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
		try {
			List<String> lines = new ArrayList<>();
			try (BufferedReader in = new BufferedReader(
					new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
				for (String line = in.readLine(); line != null; line = in.readLine()) {
					lines.add(line);
					if ("ready".equals(line)) {
						interrupt(process);
						if (insist) {
							Thread.ofVirtual().start(() -> insist(process));
						}
					}
				}
			}
			assertThat(process.waitFor(60, TimeUnit.SECONDS)).as("still running: %s", lines).isTrue();
			return new Result(process.exitValue(), lines);
		}
		finally {
			process.destroyForcibly();
		}
	}

	private static void insist(Process process) {
		try {
			while (!process.waitFor(500, TimeUnit.MILLISECONDS)) {
				interrupt(process);
			}
		}
		catch (InterruptedException | IOException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static void interrupt(Process process) throws IOException, InterruptedException {
		new ProcessBuilder("kill", "-INT", String.valueOf(process.pid())).inheritIO().start().waitFor();
	}

	private record Result(int exit, List<String> lines) {
	}

}
