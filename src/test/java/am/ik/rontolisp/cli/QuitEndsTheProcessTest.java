package am.ik.rontolisp.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An explicit quit ends the interpreter's process with its status even while a thread the
 * program started is still running -- status 0 included, as {@code System.exit} ends a
 * compiled program. A program that just runs off its end still waits for its threads (a
 * {@code run-server} with {@code :join? false} keeps serving), so only the quit is
 * forced.
 */
class QuitEndsTheProcessTest {

	@TempDir
	Path dir;

	@Test
	void uiopQuitZeroEndsTheProcessWhileAJavaThreadRuns() throws Exception {
		Result result = run("quit.lisp", """
				(java:call (java:new "java.lang.Thread"
				                     (lambda (self) (declare (ignore self)) (sleep 600)))
				           "start")
				(write-line "started")
				(uiop:quit 0)
				""");
		assertThat(result.exit()).as(result.stderr()).isZero();
		assertThat(result.stdout()).isEqualTo("started\n");
	}

	@Test
	void clojureSystemExitZeroEndsTheProcessWhileAJavaThreadRuns() throws Exception {
		Result result = run("quit.clj", """
				(.start (Thread. (fn [] (Thread/sleep 600000))))
				(println :started)
				(System/exit 0)
				""");
		assertThat(result.exit()).as(result.stderr()).isZero();
		assertThat(result.stdout()).isEqualTo(":started\n");
	}

	@Test
	void aNonzeroQuitStillEndsTheProcessWithItsStatus() throws Exception {
		Result result = run("quit3.clj", """
				(.start (Thread. (fn [] (Thread/sleep 600000))))
				(System/exit 3)
				""");
		assertThat(result.exit()).as(result.stderr()).isEqualTo(3);
	}

	/** The CLI's {@code main} as its own process; a run past 60 seconds did not end. */
	private Result run(String name, String program) throws Exception {
		Path file = this.dir.resolve(name);
		Files.writeString(file, program);
		String java = ProcessHandle.current().info().command().orElse("java");
		Path errors = this.dir.resolve(name + ".stderr");
		Process process = new ProcessBuilder(List.of(java, "-cp", System.getProperty("java.class.path"),
				"am.ik.rontolisp.cli.RontoLispCli", file.toString()))
			.directory(this.dir.toFile())
			.redirectError(errors.toFile())
			.start();
		process.getOutputStream().close();
		try {
			boolean ended = process.waitFor(60, TimeUnit.SECONDS);
			assertThat(ended).as("%s still running 60 s after its quit", name).isTrue();
			String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			return new Result(process.exitValue(), stdout, read(errors));
		}
		finally {
			process.destroyForcibly();
		}
	}

	private static String read(Path file) throws IOException {
		return Files.exists(file) ? Files.readString(file) : "";
	}

	private record Result(int exit, String stdout, String stderr) {
	}

}
