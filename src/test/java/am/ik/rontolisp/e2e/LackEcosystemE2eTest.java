package am.ik.rontolisp.e2e;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import am.ik.rontolisp.testsupport.WasmtimeSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The REAL lack-request / lack-response / lack middleware ecosystem (quickloaded verbatim
 * from the live Quicklisp dist) parses request bodies and runs a session round trip on
 * rontolisp -- the {@code .todo/231} half of the Clack milestone.
 *
 * <p>
 * Two exercises, at the two coverage levels the backends allow:
 *
 * <ol>
 * <li><b>the lack chain</b> ({@link #lackRequestParsesBodiesAndRunsASession}) --
 * INTERPRETER ONLY, and deliberately so. {@code lack-request} pulls in http-body ->
 * fast-http, and fast-http's generated state machines exceed two independent, documented
 * compile-backend ceilings: {@code parse-header-field-and-value} outgrows the JVM's
 * signed 16-bit branch offset ({@code .kb/jvm-method-size-limits.md}) and http-body's
 * {@code (concatenate '(simple-array (unsigned-byte 8) (*)) ...)} is outside the WASM
 * backends' literal result-type family ({@code .kb/concatenate-result-families.md}).
 * Neither is caused by this milestone and both fail LOUDLY at compile time, never
 * silently at run time. See {@code .kb/lack.md}.</li>
 * <li><b>the substrate the chain rides on</b> ({@code smart-buffer} +
 * {@code flexi-streams}) -- ALL FOUR backends: an in-memory octet stream read back
 * through {@code read-byte}/{@code read-sequence}/{@code file-position}, and the
 * disk-spill path (a payload past the memory limit lands in a
 * {@code uiop:with-temporary-file} temporary and every further chunk APPENDS to it). The
 * spill is interpreter/JVM only: both WASM backends signal the standard
 * {@code ensure-directories-exist} message at CALL time, the documented divergence
 * ({@code .kb/directory-listing.md}), which the program catches and prints.</li>
 * </ol>
 *
 * Opt-in ({@code RONTOLISP_LACK_E2E=1}): it needs Docker (the pinned wasmtime image) and,
 * on the first run, network access ({@code ql:quickload} downloads lack and its
 * dependencies into {@code ~/.rontolisp/quicklisp}).
 *
 * <pre>{@code
 * RONTOLISP_LACK_E2E=1 ./mvnw -Dtest=LackEcosystemE2eTest -DfailIfNoTests=false test
 * }</pre>
 */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfEnvironmentVariable(named = "RONTOLISP_LACK_E2E", matches = "1")
class LackEcosystemE2eTest {

	private static final int TIMEOUT_MINUTES = 30;

	private static final String JAVA = Path.of(System.getProperty("java.home"), "bin", "java").toString();

	// The classes this test was compiled against, so the CLI subprocess needs no jar.
	private static final String CLASSPATH = System.getProperty("java.class.path");

	/**
	 * The lack chain exercise: a urlencoded body, a multipart body (fast-http's multipart
	 * parser over smart-buffer and a flexi-streams in-memory stream) and a two-request
	 * session round trip through the memory store and the cookie state.
	 */
	private static final String LACK_EXERCISE = """
			(ql:quickload "lack-request")
			(ql:quickload "lack-middleware-session")

			(defun env-for (content-type body)
			  (let ((octets (flex:string-to-octets body :external-format :utf-8)))
			    (list :request-method :post
			          :script-name ""
			          :path-info "/submit"
			          :query-string "a=1&b=2"
			          :server-name "localhost"
			          :server-port 5000
			          :server-protocol :http/1.1
			          :url-scheme "http"
			          :remote-addr "127.0.0.1"
			          :content-type content-type
			          :content-length (length octets)
			          :headers (let ((h (make-hash-table :test 'equal)))
			                     (setf (gethash "content-type" h) content-type)
			                     h)
			          :raw-body (flex:make-in-memory-input-stream octets))))

			(let ((req (lack/request:make-request
			             (env-for "application/x-www-form-urlencoded" "name=ronto&lang=lisp"))))
			  (print (lack/request:request-body-parameters req))
			  (print (lack/request:request-query-parameters req)))

			(let* ((boundary "----rontoboundary")
			       (body (format nil "--~A~C~Ccontent-disposition: form-data; name=\\"title\\"~C~C~C~Chello~C~C--~A--~C~C"
			                     boundary #\\Return #\\Newline #\\Return #\\Newline #\\Return #\\Newline
			                     #\\Return #\\Newline boundary #\\Return #\\Newline))
			       (req (lack/request:make-request
			              (env-for (concatenate 'string "multipart/form-data; boundary=" boundary) body))))
			  (print (lack/request:request-body-parameters req)))

			(defvar *app*
			  (funcall lack/middleware/session:*lack-middleware-session*
			           (lambda (env)
			             (let ((session (getf env :lack.session)))
			               (setf (gethash "hits" session) (+ 1 (or (gethash "hits" session) 0)))
			               (list 200 '(:content-type "text/plain")
			                     (list (format nil "hits=~A" (gethash "hits" session))))))))

			(defun run-session (cookie)
			  (funcall *app*
			           (list :request-method :get :path-info "/" :script-name "" :query-string nil
			                 :headers (let ((h (make-hash-table :test 'equal)))
			                            (when cookie (setf (gethash "cookie" h) cookie))
			                            h))))

			(let* ((first-response (run-session nil))
			       (set-cookie (getf (second first-response) :set-cookie)))
			  (print (third first-response))
			  (print (third (run-session (subseq set-cookie 0 (position #\\; set-cookie))))))
			""";

	private static final String LACK_EXPECTED = """
			(("name" . "ronto") ("lang" . "lisp"))
			(("a" . "1") ("b" . "2"))
			(("title" . "hello"))
			("hits=1")
			("hits=2")
			""";

	/**
	 * The substrate exercise, compilable on every backend: the in-memory octet stream
	 * first, then the disk spill inside a {@code handler-case} so the two WASM backends
	 * report their documented call-time error instead of trapping.
	 */
	private static final String SUBSTRATE_EXERCISE = """
			(ql:quickload "smart-buffer")
			(let* ((v (make-array 3 :element-type '(unsigned-byte 8) :initial-contents '(7 8 9)))
			       (s (flex:make-in-memory-input-stream v))
			       (first-byte (read-byte s))
			       (buf (make-array 2 :element-type '(unsigned-byte 8))))
			  (print (and (typep s 'flex:vector-stream) t))
			  (print first-byte)
			  (print (read-sequence buf s))
			  (print (read-byte s nil :eof))
			  (file-position s 0)
			  (print (read-byte s)))
			(setq smart-buffer:*default-memory-limit* 8)
			(print (handler-case
			           (let ((buf (smart-buffer:make-smart-buffer))
			                 (payload (make-array 20 :element-type '(unsigned-byte 8))))
			             (dotimes (i 20) (setf (aref payload i) (+ 65 (mod i 26))))
			             ;; Two writes: the first spills to the temporary file, the second
			             ;; APPENDS to it (:if-exists :append).
			             (smart-buffer:write-to-buffer buf payload 0 10)
			             (smart-buffer:write-to-buffer buf payload 10 20)
			             (let ((in (smart-buffer:finalize-buffer buf))
			                   (out (make-array 20 :element-type '(unsigned-byte 8))))
			               (read-sequence out in)
			               (close in)
			               (list (smart-buffer:buffer-on-memory-p buf) (aref out 0) (aref out 19))))
			         (error (e) (princ e) (terpri) :signalled)))
			""";

	private static final String SUBSTRATE_EXPECTED_SPILLING = """
			T
			7
			2
			:EOF
			7
			(NIL 65 84)
			""";

	private static final String SUBSTRATE_EXPECTED_NO_FILESYSTEM = """
			T
			7
			2
			:EOF
			7
			ensure-directories-exist is not supported on the WASM backends
			:SIGNALLED
			""";

	@Test
	void lackRequestParsesBodiesAndRunsASession(@TempDir Path workDir) throws Exception {
		Path program = writeProgram(workDir, "lack-exercise.lisp", LACK_EXERCISE);
		assertThat(runCli(workDir, program.getFileName().toString())).isEqualToNormalizingWhitespace(LACK_EXPECTED);
	}

	@Test
	void smartBufferSubstrateOnTheInterpreter(@TempDir Path workDir) throws Exception {
		Path program = writeProgram(workDir, "substrate.lisp", SUBSTRATE_EXERCISE);
		assertThat(runCli(workDir, program.getFileName().toString()))
			.isEqualToNormalizingWhitespace(SUBSTRATE_EXPECTED_SPILLING);
	}

	@Test
	void smartBufferSubstrateOnJvm(@TempDir Path workDir) throws Exception {
		Path program = writeProgram(workDir, "substrate.lisp", SUBSTRATE_EXERCISE);
		runCli(workDir, program.getFileName().toString(), "-o", "Substrate.class");
		assertThat(runSuccessfully(workDir, JAVA, "-cp", CLASSPATH + java.io.File.pathSeparator + workDir, "Substrate"))
			.isEqualToNormalizingWhitespace(SUBSTRATE_EXPECTED_SPILLING);
	}

	@Test
	void smartBufferSubstrateOnWasmPreview1(@TempDir Path workDir) throws Exception {
		assertThat(runWasm(workDir, "substrate-p1.wasm", List.of()))
			.isEqualToNormalizingWhitespace(SUBSTRATE_EXPECTED_NO_FILESYSTEM);
	}

	@Test
	void smartBufferSubstrateOnWasmComponent(@TempDir Path workDir) throws Exception {
		assertThat(runWasm(workDir, "substrate-comp.wasm", List.of("--component")))
			.isEqualToNormalizingWhitespace(SUBSTRATE_EXPECTED_NO_FILESYSTEM);
	}

	// Compiles the substrate exercise to WASM and runs it in the pinned wasmtime
	// container. -W exceptions=y: the handler-case around the spill puts the module in
	// EH mode.
	private String runWasm(Path workDir, String output, List<String> extraFlags) throws Exception {
		Path program = writeProgram(workDir, "substrate.lisp", SUBSTRATE_EXERCISE);
		List<String> args = new ArrayList<>(List.of(program.getFileName().toString(), "-o", output));
		args.addAll(extraFlags);
		runCli(workDir, args.toArray(String[]::new));
		String path = "/tmp/" + workDir.getFileName() + "-" + output;
		WasmtimeSupport.container()
			.copyFileToContainer(Transferable.of(Files.readAllBytes(workDir.resolve(output))), path);
		ExecResult result = WasmtimeSupport.container()
			.execInContainer("wasmtime", "run", "-W", "gc=y", "-W", "exceptions=y", "--dir", "/tmp", path);
		assertThat(result.getExitCode()).as("stderr: %s", result.getStderr()).isZero();
		return result.getStdout();
	}

	private static Path writeProgram(Path workDir, String name, String source) throws Exception {
		Path program = workDir.resolve(name);
		Files.writeString(program, source, StandardCharsets.UTF_8);
		return program;
	}

	private static String runCli(Path workDir, String... args) throws Exception {
		List<String> command = new ArrayList<>(List.of(JAVA, "-cp", CLASSPATH, "am.ik.rontolisp.cli.RontoLispCli"));
		command.addAll(List.of(args));
		return runSuccessfully(workDir, command.toArray(String[]::new));
	}

	private static String runSuccessfully(Path workDir, String... command) throws Exception {
		Result result = run(workDir, command);
		assertThat(result.exitCode()).as("%s", result).isZero();
		return result.out();
	}

	/** One finished subprocess: its exit code and its two streams, kept apart. */
	private record Result(List<String> command, int exitCode, String out, String err) {
		@Override
		public String toString() {
			return "command: %s%nstdout: %s%nstderr: %s".formatted(String.join(" ", this.command), this.out, this.err);
		}
	}

	private static Result run(Path workDir, String... command) throws Exception {
		Path errFile = Files.createTempFile(workDir, "stderr", ".log");
		Process process = new ProcessBuilder(command).directory(workDir.toFile())
			.redirectError(errFile.toFile())
			.start();
		String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		if (!process.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
			process.destroyForcibly();
			throw new AssertionError("timed out after " + TIMEOUT_MINUTES + " minutes: " + String.join(" ", command));
		}
		return new Result(List.of(command), process.exitValue(), out, Files.readString(errFile));
	}

}
