package am.ik.rontolisp.codegen.jvm;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a throwable carries is TAKEN by the landing pad that catches it
 * ({@link JvmThrowableRecords}), not merely read: HotSpot's C2 throws one preallocated
 * exception per class from a hot site ({@code OmitStackTraceInFastThrow}, on by default),
 * so the instance a {@code %hb-guard} pad synthesized and recorded for one failure would
 * otherwise describe the next -- and its record, which says the handlers ran for it
 * ({@code _condRan}), would skip every later failure's handlers.
 *
 * <p>
 * Measured 2026-09-26 on this loop under {@code -XX:-UseJVMCICompiler}: with the record
 * read and left in place, the handler ran 5,332 times out of 300,000 (the stale instance
 * matched the global mark that said then whether the handlers ran). Graal, this machine's
 * default JIT, allocates a fresh exception every time, so only a child JVM on C2 can see
 * it; a stock OpenJDK ignores the JVMCI option and is on C2 anyway.
 */
class JvmThrowableRecordsTest {

	@TempDir
	Path tempDir;

	@Test
	void aPreallocatedExceptionCarriesNoRecordOfAnEarlierFailure() throws Exception {
		assertThat(runOnC2("""
				(defvar *handled* 0)
				(defvar *caught* 0)
				(defun poke (x) (char-code x))
				(dotimes (i 300000)
				  (handler-case
				      (handler-bind ((error (lambda (c) (declare (ignore c)) (incf *handled*))))
				        (poke i))
				    (error () (incf *caught*))))
				(print (list *handled* *caught*))
				""", "FastThrowProg")).isEqualTo("(300000 300000)");
	}

	private String runOnC2(String lispCode, String className) throws Exception {
		JvmLispCompiler compiler = new JvmLispCompiler(className);
		Files.write(this.tempDir.resolve(className + ".class"),
				compiler.compile(LispReader.readAllFromString(lispCode)));
		for (Map.Entry<String, byte[]> travelling : compiler.runtimeClassFiles().entrySet()) {
			Path target = this.tempDir.resolve(travelling.getKey());
			Files.createDirectories(target.getParent());
			Files.write(target, travelling.getValue());
		}
		List<String> command = List.of(ProcessHandle.current().info().command().orElse("java"),
				"-XX:+IgnoreUnrecognizedVMOptions", "-XX:-UseJVMCICompiler", "-XX:+OmitStackTraceInFastThrow", "-cp",
				this.tempDir.toString(), className);
		// Into files, not pipes: a program that hangs must fail the test, not hang it.
		Path out = this.tempDir.resolve("stdout.txt");
		Path err = this.tempDir.resolve("stderr.txt");
		Process process = new ProcessBuilder(command).redirectOutput(out.toFile()).redirectError(err.toFile()).start();
		if (!process.waitFor(2, TimeUnit.MINUTES)) {
			process.destroyForcibly().waitFor();
			throw new AssertionError(command + " still running after 2 minutes");
		}
		assertThat(Files.readString(err, StandardCharsets.UTF_8)).isEmpty();
		assertThat(process.exitValue()).isZero();
		return Files.readString(out, StandardCharsets.UTF_8).trim();
	}

}
