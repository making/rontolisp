package am.ik.rontolisp.e2e;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.cli.RontoLispCli;
import am.ik.rontolisp.eval.LispEvaluator;
import am.ik.rontolisp.eval.ObjcInterop;
import am.ik.rontolisp.reader.LispReader;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.abort;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * An {@code objc:} program as a {@code --native} executable (.kb/objc.md, "--native"):
 * the headless corpus prints byte for byte what the interpreter prints -- sends of every
 * result kind, a class whose methods are Lisp closures, a callback that signals, the
 * {@code :error} slot, the argument refusals -- and {@code sleep} turns thread 0's event
 * loop, so a timer's closure runs while the program waits. No window is opened (CI has no
 * display); a window is verified by hand with {@code examples/macos/counter.lisp}.
 *
 * <p>
 * macOS on Apple silicon only, where the runner answers the {@code rlobjc} imports; the
 * compiler is this JVM's {@link RontoLispCli} or {@code -Drontolisp.binary}, as in
 * {@link NativeOutputE2eTest}, and a compiler without the host's pair skips (fails under
 * {@code -Drontolisp.native.required=true}).
 */
@EnabledOnOs(value = OS.MAC, architectures = "aarch64")
class NativeObjcE2eTest {

	private static final @Nullable String BINARY = System.getProperty("rontolisp.binary");

	private static final boolean REQUIRED = Boolean.getBoolean("rontolisp.native.required");

	private static final String NOT_AVAILABLE = "--native is not available for ";

	@TempDir
	Path tempDir;

	@Test
	void theHeadlessCorpusPrintsWhatTheInterpreterPrints() throws Exception {
		assumeTrue(ObjcInterop.available(), ObjcInterop.description());
		String source;
		try (InputStream in = NativeObjcE2eTest.class.getResourceAsStream("/objc-native-corpus.lisp")) {
			source = new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8);
		}
		Run actual = nativeOutput(source);
		assertThat(actual.exit()).as("stderr: %s", actual.stderr()).isZero();
		assertThat(actual.stdout()).isEqualTo(interpret(source));
		// A method that signals is contained: printed, answered as nil, and the program
		// goes on.
		assertThat(actual.stderr()).isEqualTo("objc: error in a callback: boom inside\n");
	}

	@Test
	void sleepTurnsTheEventLoopSoATimerRunsWhileTheProgramWaits() throws Exception {
		Run run = nativeOutput("""
				(defvar *ticks* 0)
				(appkit:timer 0.02 (lambda () (setq *ticks* (+ *ticks* 1)) (< *ticks* 3)))
				(format t "before ~a~%" *ticks*)
				(sleep 0.5)
				(format t "after ~a~%" *ticks*)
				""");
		assertThat(run.exit()).as("stderr: %s", run.stderr()).isZero();
		assertThat(run.stdout()).isEqualTo("before 0\nafter 3\n");
	}

	@Test
	void anExitInsideACallbackEndsTheProcessWithItsCode() throws Exception {
		Run run = nativeOutput("""
				(let* ((cls (objc:define-class "NativeE2eExit" "NSObject"
				              (list (list "bye:" (lambda (self x) (format t "bye~%") (finish-output) (uiop:quit 7))))))
				       (obj (objc:send (objc:send cls "alloc") "init")))
				  (objc:send obj "performSelector:withObject:" "bye:" nil)
				  (format t "not reached~%"))
				""");
		assertThat(run.stdout()).isEqualTo("bye\n");
		assertThat(run.exit()).as("stderr: %s", run.stderr()).isEqualTo(7);
	}

	private String interpret(String source) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(out, true, StandardCharsets.UTF_8));
		for (LispVal form : LispReader.readAllFromString(source)) {
			evaluator.eval(form);
		}
		return out.toString(StandardCharsets.UTF_8);
	}

	private Run nativeOutput(String source) throws Exception {
		Path dir = Files.createDirectories(this.tempDir.resolve("native"));
		Path src = dir.resolve("prog.lisp");
		Files.writeString(src, source);
		Path exe = dir.resolve("prog");
		List<String> args = new ArrayList<>(List.of(src.toString(), "-o", exe.toString(), "--native"));
		try {
			compile(src, args);
		}
		catch (UnsupportedOperationException ex) {
			String message = String.valueOf(ex.getMessage());
			if (message.contains(NOT_AVAILABLE) && !REQUIRED) {
				abort(message);
			}
			throw ex;
		}
		return exec(Files.createDirectories(dir.resolve("run")), List.of(exe.toString()));
	}

	private static void compile(Path src, List<String> args) throws Exception {
		if (BINARY == null) {
			new RontoLispCli(new ByteArrayInputStream(new byte[0]),
					new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8))
				.run(args.toArray(String[]::new));
			return;
		}
		List<String> command = new ArrayList<>(args);
		command.addFirst(Path.of(BINARY).toAbsolutePath().toString());
		Run run = exec(Objects.requireNonNull(src.toAbsolutePath().getParent()), command);
		if (run.exit() != 0) {
			if (run.stderr().contains(NOT_AVAILABLE)) {
				throw new UnsupportedOperationException(run.stderr().strip());
			}
			throw new IllegalStateException("compile failed (" + run.exit() + "): " + run.stderr());
		}
	}

	private static Run exec(Path dir, List<String> command) throws Exception {
		Path stdout = dir.resolve("stdout.log");
		Path stderr = dir.resolve("stderr.log");
		Process process = new ProcessBuilder(command).directory(dir.toFile())
			.redirectInput(ProcessBuilder.Redirect.from(Path.of("/dev/null").toFile()))
			.redirectOutput(stdout.toFile())
			.redirectError(stderr.toFile())
			.start();
		if (!process.waitFor(120, TimeUnit.SECONDS)) {
			process.destroyForcibly().waitFor();
			throw new IllegalStateException("timed out: " + String.join(" ", command));
		}
		return new Run(Files.readString(stdout), Files.readString(stderr), process.exitValue());
	}

	private record Run(String stdout, String stderr, int exit) {
	}

}
