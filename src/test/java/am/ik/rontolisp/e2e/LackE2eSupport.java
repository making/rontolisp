package am.ik.rontolisp.e2e;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The programs and the subprocess plumbing the two lack-ecosystem E2E classes share:
 * {@link LackEcosystemE2eTest} (the container-free interpreter and JVM legs) and
 * {@link LackEcosystemWasmE2eTest} (the Docker-gated WASM legs). Split apart so the legs
 * that need no container are not skipped along with the ones that do -- a green suite
 * that skipped the only test covering a regression is what let the Gray-splice ordering
 * bug ship.
 */
final class LackE2eSupport {

	private static final int TIMEOUT_MINUTES = 30;

	static final String JAVA = Path.of(System.getProperty("java.home"), "bin", "java").toString();

	// The classes this test was compiled against, so the CLI subprocess needs no jar.
	static final String CLASSPATH = System.getProperty("java.class.path");

	private LackE2eSupport() {
	}

	/**
	 * The lack chain exercise: a urlencoded body, a multipart body (fast-http's multipart
	 * parser over smart-buffer and a flexi-streams in-memory stream) and a two-request
	 * session round trip through the memory store and the cookie state.
	 */
	static final String LACK_EXERCISE = """
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

	static final String LACK_EXPECTED = """
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
	static final String SUBSTRATE_EXERCISE = """
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

	static final String SUBSTRATE_EXPECTED_SPILLING = """
			T
			7
			2
			:EOF
			7
			(NIL 65 84)
			""";

	static final String SUBSTRATE_EXPECTED_NO_FILESYSTEM = """
			T
			7
			2
			:EOF
			7
			ensure-directories-exist is not supported on the WASM backends
			:SIGNALLED
			""";

	/**
	 * The lack chain over a REAL SERVED request, on every backend: {@code lack:builder}
	 * wraps the app and {@code rontolisp::%http-serve-request} -- the one server-side
	 * request path every transport calls -- runs it over a buffered {@code :raw-body}
	 * (the {@code http-request-body-stream} Gray subclass), which {@code lack-request}
	 * parses through circular-streams.
	 *
	 * <p>
	 * This is the shape the Gray-splice ordering bug broke: the server library's
	 * {@code http-request-body-stream} is spliced at index 0 while the Gray protocol
	 * reaches the program at the trivial-gray-streams shim's splice site, mid-program, so
	 * the subclass preceded its base class and the compile paths rejected the
	 * {@code defclass}. It drives the request DIRECTLY rather than through
	 * {@code clack:clackup} so all four backends can run it: Preview 1 has no incoming
	 * TCP, and a clack program under {@code --component} is a {@code wasmtime serve}
	 * component, not a runnable CLI one ({@code .kb/clack.md}). The clackup-and-fetch
	 * spelling of the same chain is {@link #builderOverClackupExercise}, interpreter and
	 * JVM only for that reason.
	 */
	static final String SERVED_BODY_EXERCISE = """
			(ql:quickload "lack")
			(ql:quickload "lack-request")
			(defvar *app*
			  (lack:builder
			   (lambda (env)
			     (let ((req (lack/request:make-request env)))
			       (list 200 '(:content-type "text/plain")
			             (list (format nil "params=~A"
			                           (lack/request:request-body-parameters req))))))))
			(let ((response
			        (rontolisp:await
			         (rontolisp::%http-serve-request
			          *app*
			          (list "post" "/submit?x=1"
			                '(("Host" . "127.0.0.1:5000")
			                  ("Content-Type" . "application/x-www-form-urlencoded")
			                  ("Content-Length" . "20"))
			                (rontolisp::%http-body-stream "name=ronto&lang=lisp")
			                "HTTP/1.1" "http" "127.0.0.1" 5000 "127.0.0.1" 49152)))))
			  (print (car response))
			  (print (car (cdr (cdr response)))))
			""";

	static final String SERVED_BODY_EXPECTED = """
			200
			"params=((name . ronto) (lang . lisp))"
			""";

	/**
	 * The same chain end to end over a real socket: {@code clack:clackup} serves the
	 * builder-wrapped app on the rontolisp backend and a {@code fetch} POSTs to it, so
	 * {@code lack-request} parses the body off the served {@code :raw-body}. Interpreter
	 * and JVM only -- see {@link #SERVED_BODY_EXERCISE} for why the WASM backends run the
	 * transport-free spelling instead.
	 * @param port a free TCP port
	 * @return the program source
	 */
	static String builderOverClackupExercise(int port) {
		return """
				(ql:quickload "clack")
				(ql:quickload "lack")
				(ql:quickload "lack-request")
				(defvar *handler*
				  (clack:clackup
				   (lack:builder
				    (lambda (env)
				      (let ((req (lack/request:make-request env)))
				        (list 200 '(:content-type "text/plain")
				              (list (format nil "params=~A"
				                            (lack/request:request-body-parameters req)))))))
				   :server :rontolisp
				   :port %d
				   :silent t
				   :debug nil))
				(defvar *post* (rontolisp:await (rontolisp:fetch "http://127.0.0.1:%d/submit"
				                                                 '(:method "POST"
				                                                   :headers (("content-type" . "application/x-www-form-urlencoded"))
				                                                   :body "name=ronto&lang=lisp"))))
				(print (getf *post* :status))
				(print (rontolisp:await (rontolisp:read-all (getf *post* :body))))
				(print (clack:stop *handler*))
				"""
			.formatted(port, port);
	}

	static final String BUILDER_OVER_CLACKUP_EXPECTED = """
			200
			"params=((name . ronto) (lang . lisp))"
			T
			""";

	/** The condition the backtrace-middleware exercise's handler signals. */
	static final String BACKTRACE_CONDITION = "lack-backtrace-boom";

	/**
	 * The DEFAULT middleware every {@code clack:clackup} application is wrapped in:
	 * lack's {@code :backtrace}, which reports a failing handler to
	 * {@code *error-output*}.
	 *
	 * <p>
	 * It reaches that stream the awkward way -- its {@code output} parameter defaults to
	 * the SYMBOL {@code '*error-output*} and the report goes to {@code (symbol-value
	 * output)} -- so on a compiled backend, whose {@code symbol-value} reads the eval
	 * runtime's global-environment mirror rather than the variable's own global cell, the
	 * middleware used to signal {@code The variable *ERROR-OUTPUT* is unbound} and
	 * REPLACE the application's real error with it. Both homes now seed from
	 * {@code compiler.StreamDesignators}' one table ({@code .kb/symbol-runtime-api.md}).
	 * @param port a free TCP port
	 * @return the program source
	 */
	static String backtraceMiddlewareExercise(int port) {
		return """
				(ql:quickload "clack")
				(ql:quickload "lack-middleware-backtrace")
				(defvar *handler*
				  (clack:clackup
				   (lambda (env) env (error "%s"))
				   :server :rontolisp
				   :port %d
				   :silent t
				   :debug nil))
				(defvar *get* (rontolisp:await (rontolisp:fetch "http://127.0.0.1:%d/boom")))
				(print (getf *get* :status))
				(print (clack:stop *handler*))
				""".formatted(BACKTRACE_CONDITION, port, port);
	}

	static final String BACKTRACE_MIDDLEWARE_EXPECTED = """
			500
			T
			""";

	/**
	 * A free TCP port (bound then released; a tiny race, acceptable for tests).
	 * @return the port
	 * @throws Exception when no socket can be bound
	 */
	static int freePort() throws Exception {
		try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
			return socket.getLocalPort();
		}
	}

	static Path writeProgram(Path workDir, String name, String source) throws Exception {
		Path program = workDir.resolve(name);
		Files.writeString(program, source, StandardCharsets.UTF_8);
		return program;
	}

	static String runCli(Path workDir, String... args) throws Exception {
		Result result = runCliResult(workDir, args);
		assertThat(result.exitCode()).as("%s", result).isZero();
		return result.out();
	}

	/** {@link #runCli} keeping the subprocess's STDERR, which some legs assert on. */
	static Result runCliResult(Path workDir, String... args) throws Exception {
		List<String> command = new ArrayList<>(List.of(JAVA, "-cp", CLASSPATH, "am.ik.rontolisp.cli.RontoLispCli"));
		command.addAll(List.of(args));
		return run(workDir, command.toArray(String[]::new));
	}

	static String runSuccessfully(Path workDir, String... command) throws Exception {
		Result result = run(workDir, command);
		assertThat(result.exitCode()).as("%s", result).isZero();
		return result.out();
	}

	/** One finished subprocess: its exit code and its two streams, kept apart. */
	record Result(List<String> command, int exitCode, String out, String err) {
		@Override
		public String toString() {
			return "command: %s%nstdout: %s%nstderr: %s".formatted(String.join(" ", this.command), this.out, this.err);
		}
	}

	static Result run(Path workDir, String... command) throws Exception {
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
