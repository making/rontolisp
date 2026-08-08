package am.ik.rontolisp.e2e;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.PullPolicy;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The REAL Clack (Eitaro Fukamachi's web application environment, quickloaded verbatim
 * from the live Quicklisp dist) runs a Clack application through {@code clack:clackup} on
 * the {@code clack-handler-rontolisp} backend -- the {@code .todo/223} milestone.
 *
 * <p>
 * Three live legs assert the same HTTP round trip:
 *
 * <ol>
 * <li><b>interpreter</b> and <b>JVM class</b> -- the SAME self-driving program:
 * {@code clackup} with every default in force ({@code :use-thread t}, so the acceptor
 * runs on a spawned thread; {@code :use-default-middlewares t}, so lack's builder wraps
 * the app in the backtrace middleware through {@code find-middleware}'s runtime
 * {@code find-package-or-load} path), a GET with a query string and a POST with a body
 * fetched against itself ({@code rontolisp:fetch}), then {@code clack:stop} and the proof
 * the port really closed;</li>
 * <li><b>WASM component</b> -- the same clackup program compiled with {@code --component}
 * and served by {@code wasmtime serve} (the host owns the socket: {@code HttpLibrary}
 * extracts the shim's NESTED {@code rontolisp:http-handler} call for the export wiring),
 * answered over HTTP from this test.</li>
 * </ol>
 *
 * WASM Preview 1 is the fourth backend and has no incoming TCP by design
 * ({@code .kb/tcp-sockets.md}): the program COMPILES (the directive inside the shim's
 * {@code run} is a call-time-error stub, the todo-195 socket policy) and {@code clackup}
 * signals the standard message at run time, which
 * {@link #clackupSignalsTheStandardErrorOnWasmPreview1} pins through
 * {@code handler-case}.
 *
 * <p>
 * The same three legs run once more over a REAL application with routes: tiny-routes
 * v0.1.1 plus its cookie middleware, both quickloaded UNPATCHED, composed with
 * {@code pipe} and served through the same {@code clackup}. That pair is the answer to
 * "what do I write after {@code clackup}", and it covers what the transport-free
 * {@link TinyRoutesE2eTest} cannot: the live {@code ql:quickload} of the upstream dist
 * (rather than the vendored copy), the cookie system's own dependency graph (cl-cookie /
 * quri / local-time / proc-parse) and the Clack request environment reaching the
 * middleware chain as it really arrives -- {@code :raw-body} stream, {@code :headers}
 * table and all. The routes are read inside the application's OWN package, which is what
 * a compiled serve component needs {@code HttpLibrary}'s synthesized bridge to survive.
 *
 * <p>
 * Opt-in ({@code RONTOLISP_CLACK_E2E=1}): it needs Docker (the pinned wasmtime image)
 * and, on the first run, network access ({@code ql:quickload} downloads clack, lack and
 * their dependencies into {@code ~/.rontolisp/quicklisp}).
 *
 * <pre>{@code
 * RONTOLISP_CLACK_E2E=1 ./mvnw -Dtest=ClackE2eTest -DfailIfNoTests=false test
 * }</pre>
 */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfEnvironmentVariable(named = "RONTOLISP_CLACK_E2E", matches = "1")
class ClackE2eTest {

	private static final int TIMEOUT_MINUTES = 15;

	private static final String JAVA = Path.of(System.getProperty("java.home"), "bin", "java").toString();

	// The classes this test was compiled against, so the CLI subprocess needs no jar.
	private static final String CLASSPATH = System.getProperty("java.class.path");

	// What the app echoes for the two self-driven probes, then the stop proofs. The
	// content-type and the raw body prove the Clack env mapping end to end (headers
	// table, :content-type, the pre-drained :raw-body stream).
	private static final String SELF_DRIVING_EXPECTED = """
			200
			"clack GET /echo q=a=1 ct=NIL body="
			200
			"clack POST /submit q=NIL ct=text/plain body=hello-clack"
			T
			NIL
			""";

	/**
	 * The self-driving interpreter/JVM program: clackup with the DEFAULTS in force, probe
	 * it over real HTTP via rontolisp:fetch, stop it, and prove the port closed (the
	 * second fetch signals, so ignore-errors prints NIL).
	 */
	private static String selfDrivingExercise(int port) {
		return """
				(ql:quickload "clack")
				(defvar *handler*
				  (clack:clackup
				   ;; :raw-body is nil for a bodiless request (the upstream (when raw-body
				   ;; ...) guard is the app's job), so the GET probe guards it.
				   (lambda (env)
				     (list 200 (list :content-type "text/plain")
				           (list (format nil "clack ~A ~A q=~A ct=~A body=~A"
				                         (getf env :request-method) (getf env :path-info)
				                         (getf env :query-string) (getf env :content-type)
				                         (let ((s (getf env :raw-body)))
				                           (if s (read-line s nil "") ""))))))
				   :server :rontolisp
				   :port %d
				   ;; Keep the round-trip output deterministic: the banner embeds the
				   ;; (ephemeral) port and the NOTICE block is debug chatter. Everything
				   ;; semantically load-bearing stays at its default (:use-thread t,
				   ;; :use-default-middlewares t).
				   :silent t
				   :debug nil))
				(defvar *get* (rontolisp:await (rontolisp:fetch "http://127.0.0.1:%d/echo?a=1")))
				(print (getf *get* :status))
				(print (rontolisp:await (rontolisp:read-all (getf *get* :body))))
				(defvar *post* (rontolisp:await (rontolisp:fetch "http://127.0.0.1:%d/submit"
				                                                 '(:method "POST"
				                                                   :headers (("content-type" . "text/plain"))
				                                                   :body "hello-clack"))))
				(print (getf *post* :status))
				(print (rontolisp:await (rontolisp:read-all (getf *post* :body))))
				(print (clack:stop *handler*))
				(print (ignore-errors
				         (rontolisp:await (rontolisp:fetch "http://127.0.0.1:%d/echo?a=1"))))
				""".formatted(port, port, port, port);
	}

	/** The serve-shaped program the component leg compiles (the host owns the socket). */
	private static final String COMPONENT_EXERCISE = """
			(ql:quickload "clack")
			(clack:clackup
			 (lambda (env)
			   (list 200 (list :content-type "text/plain")
			         (list (format nil "clack ~A ~A q=~A"
			                       (getf env :request-method) (getf env :path-info)
			                       (getf env :query-string)))))
			 :server :rontolisp
			 :port 8080)
			""";

	// The tiny-routes application every leg below serves: the route macros, the :id path
	// template, the method matcher, wrap-request-body over the Clack :raw-body stream,
	// wrap-query-parameters, and the cookie middleware in both directions (the request
	// Cookie header parsed into :cookies, a Set-Cookie written onto every response).
	// Read inside :tr-app -- an application uses a routing library from its own package,
	// and that is what the compile paths have to keep working.
	private static final String TINY_ROUTES_APP = """
			(ql:quickload "clack")
			(ql:quickload "tiny-routes")
			(ql:quickload "tiny-routes-middleware-cookie")

			(defpackage :tr-app (:use :cl :tiny-routes :tiny-routes.middleware.cookie))
			(in-package :tr-app)

			(define-routes *app*
			  (define-get "/hello" () (ok "hello world"))
			  (define-get "/users/:id" (req) (ok (format nil "user ~A" (path-parameter req :id))))
			  (define-post "/echo" (req) (ok (format nil "echo:~A" (request-body req))))
			  (define-get "/cookies" (req)
			    (ok (format nil "~{~A=~A~^,~}"
			                (loop for c in (request-get req :cookies)
			                      append (list (cl-cookie:cookie-name c) (cl-cookie:cookie-value c))))))
			  (define-any "*" () (not-found "nope")))

			(defparameter *routes*
			  (pipe *app*
			    (wrap-request-body)
			    (wrap-query-parameters)
			    (wrap-request-cookies)
			    (wrap-response-cookies (cl-cookie:make-cookie :name "sid" :value "42" :path "/"))))
			""";

	// What the four probes echo back, then the stop proofs. The Set-Cookie line is the
	// response half of the cookie middleware, read off the real HTTP response.
	private static final String TINY_ROUTES_EXPECTED = """
			200
			"hello world"
			"sid=42; Path=/"
			200
			"user 42"
			200
			"echo:abc"
			200
			"a=1,b=2"
			404
			T
			NIL
			""";

	/**
	 * The self-driving tiny-routes program the interpreter and JVM legs run. The fetches
	 * are spelled out at top level rather than through a helper: rontolisp:await is only
	 * allowed there or inside an async-defun/async-lambda.
	 */
	private static String tinyRoutesExercise(int port) {
		return TINY_ROUTES_APP + """
				(defvar *server*
				  (clack:clackup *routes* :server :rontolisp :port %d :silent t :debug nil))

				(defvar *hello* (rontolisp:await (rontolisp:fetch "http://127.0.0.1:%d/hello")))
				(print (getf *hello* :status))
				(print (rontolisp:await (rontolisp:read-all (getf *hello* :body))))
				;; The Set-Cookie header wrap-response-cookies added, off the wire.
				(print (cdr (assoc "set-cookie" (getf *hello* :headers) :test #'string-equal)))

				(defvar *user* (rontolisp:await (rontolisp:fetch "http://127.0.0.1:%d/users/42")))
				(print (getf *user* :status))
				(print (rontolisp:await (rontolisp:read-all (getf *user* :body))))

				(defvar *echo* (rontolisp:await (rontolisp:fetch "http://127.0.0.1:%d/echo"
				                                                 '(:method "POST" :body "abc"))))
				(print (getf *echo* :status))
				(print (rontolisp:await (rontolisp:read-all (getf *echo* :body))))

				(defvar *cookies*
				  (rontolisp:await (rontolisp:fetch "http://127.0.0.1:%d/cookies"
				                                    '(:headers (("cookie" . "a=1; b=2"))))))
				(print (getf *cookies* :status))
				(print (rontolisp:await (rontolisp:read-all (getf *cookies* :body))))

				(defvar *missing* (rontolisp:await (rontolisp:fetch "http://127.0.0.1:%d/zzz")))
				(print (getf *missing* :status))

				(print (clack:stop *server*))
				(print (ignore-errors (rontolisp:await (rontolisp:fetch "http://127.0.0.1:%d/hello"))))
				""".formatted(port, port, port, port, port, port, port);
	}

	/** The serve-shaped tiny-routes program the component leg compiles. */
	private static final String TINY_ROUTES_COMPONENT_EXERCISE = TINY_ROUTES_APP + """
			(clack:clackup *routes* :server :rontolisp :port 8080)
			""";

	@Test
	void clackupRoundTripOnTheInterpreter(@TempDir Path workDir) throws Exception {
		Path program = writeProgram(workDir, selfDrivingExercise(freePort()));
		assertThat(runCli(workDir, program.getFileName().toString()))
			.isEqualToNormalizingWhitespace(SELF_DRIVING_EXPECTED);
	}

	@Test
	void clackupRoundTripOnJvm(@TempDir Path workDir) throws Exception {
		Path program = writeProgram(workDir, selfDrivingExercise(freePort()));
		runCli(workDir, program.getFileName().toString(), "-o", "ClackProbe.class");
		assertThat(
				runSuccessfully(workDir, JAVA, "-cp", CLASSPATH + java.io.File.pathSeparator + workDir, "ClackProbe"))
			.isEqualToNormalizingWhitespace(SELF_DRIVING_EXPECTED);
	}

	@Test
	void clackupServesOnWasmComponentUnderWasmtimeServe(@TempDir Path workDir) throws Exception {
		Path program = writeProgram(workDir, COMPONENT_EXERCISE);
		runCli(workDir, program.getFileName().toString(), "-o", "clack.wasm", "--component");
		byte[] component = Files.readAllBytes(workDir.resolve("clack.wasm"));
		// -S cli/tcp/inherit-network: the spliced usocket shim (a clack dependency)
		// wit-imports wasi:sockets, which wasmtime serve gates by permission.
		try (GenericContainer<?> serve = new GenericContainer<>(WasmtimeSupport.IMAGE)
			.withImagePullPolicy(PullPolicy.alwaysPull())
			.withCopyToContainer(Transferable.of(component), "/clack.wasm")
			.withExposedPorts(8080)
			.withCommand("wasmtime", "serve", "-W", "gc=y", "-W", "exceptions=y", "-S", "cli=y", "-S", "tcp=y", "-S",
					"inherit-network=y", "--addr", "0.0.0.0:8080", "/clack.wasm")
			.waitingFor(Wait.forListeningPort())) {
			serve.start();
			HttpResponse<String> response = HttpClient.newHttpClient()
				.send(HttpRequest
					.newBuilder(URI.create("http://" + serve.getHost() + ":" + serve.getMappedPort(8080) + "/echo?a=1"))
					.GET()
					.build(), HttpResponse.BodyHandlers.ofString());
			assertThat(response.statusCode()).isEqualTo(200);
			assertThat(response.body()).isEqualTo("clack GET /echo q=a=1");
		}
	}

	@Test
	void tinyRoutesRoundTripOnTheInterpreter(@TempDir Path workDir) throws Exception {
		Path program = writeProgram(workDir, tinyRoutesExercise(freePort()));
		assertThat(runCli(workDir, program.getFileName().toString()))
			.isEqualToNormalizingWhitespace(TINY_ROUTES_EXPECTED);
	}

	@Test
	void tinyRoutesRoundTripOnJvm(@TempDir Path workDir) throws Exception {
		Path program = writeProgram(workDir, tinyRoutesExercise(freePort()));
		runCli(workDir, program.getFileName().toString(), "-o", "TinyRoutesProbe.class");
		assertThat(runSuccessfully(workDir, JAVA, "-cp", CLASSPATH + java.io.File.pathSeparator + workDir,
				"TinyRoutesProbe"))
			.isEqualToNormalizingWhitespace(TINY_ROUTES_EXPECTED);
	}

	@Test
	void tinyRoutesServesOnWasmComponentUnderWasmtimeServe(@TempDir Path workDir) throws Exception {
		Path program = writeProgram(workDir, TINY_ROUTES_COMPONENT_EXERCISE);
		runCli(workDir, program.getFileName().toString(), "-o", "tiny-routes.wasm", "--component");
		byte[] component = Files.readAllBytes(workDir.resolve("tiny-routes.wasm"));
		try (GenericContainer<?> serve = new GenericContainer<>(WasmtimeSupport.IMAGE)
			.withImagePullPolicy(PullPolicy.alwaysPull())
			.withCopyToContainer(Transferable.of(component), "/tiny-routes.wasm")
			.withExposedPorts(8080)
			.withCommand("wasmtime", "serve", "-W", "gc=y", "-W", "exceptions=y", "-S", "cli=y", "-S", "tcp=y", "-S",
					"inherit-network=y", "--addr", "0.0.0.0:8080", "/tiny-routes.wasm")
			.waitingFor(Wait.forListeningPort())) {
			serve.start();
			String base = "http://" + serve.getHost() + ":" + serve.getMappedPort(8080);
			HttpClient client = HttpClient.newHttpClient();
			HttpResponse<String> hello = client.send(HttpRequest.newBuilder(URI.create(base + "/hello")).GET().build(),
					HttpResponse.BodyHandlers.ofString());
			assertThat(hello.statusCode()).isEqualTo(200);
			assertThat(hello.body()).isEqualTo("hello world");
			assertThat(hello.headers().firstValue("set-cookie")).contains("sid=42; Path=/");
			HttpResponse<String> user = client.send(
					HttpRequest.newBuilder(URI.create(base + "/users/42")).GET().build(),
					HttpResponse.BodyHandlers.ofString());
			assertThat(user.body()).isEqualTo("user 42");
			HttpResponse<String> echo = client.send(HttpRequest.newBuilder(URI.create(base + "/echo"))
				.POST(HttpRequest.BodyPublishers.ofString("abc"))
				.build(), HttpResponse.BodyHandlers.ofString());
			assertThat(echo.body()).isEqualTo("echo:abc");
			HttpResponse<String> cookies = client.send(
					HttpRequest.newBuilder(URI.create(base + "/cookies")).header("cookie", "a=1; b=2").GET().build(),
					HttpResponse.BodyHandlers.ofString());
			assertThat(cookies.body()).isEqualTo("a=1,b=2");
			HttpResponse<String> missing = client.send(HttpRequest.newBuilder(URI.create(base + "/zzz")).GET().build(),
					HttpResponse.BodyHandlers.ofString());
			assertThat(missing.statusCode()).isEqualTo(404);
		}
	}

	@Test
	void clackupSignalsTheStandardErrorOnWasmPreview1(@TempDir Path workDir) throws Exception {
		// The documented fourth-backend gap: no incoming TCP on Preview 1, as a
		// CALL-time error a handler-case can report (an uncaught error is a silent
		// trap there), never a compile crash of the whole clack graph.
		Path program = writeProgram(workDir, """
				(ql:quickload "clack")
				(handler-case
				    (clack:clackup
				     (lambda (env) (list 200 (list :content-type "text/plain") (list "hi")))
				     :server :rontolisp
				     :port 8080)
				  (error (e)
				    (print :caught)
				    (princ e)
				    (terpri)))
				""");
		runCli(workDir, program.getFileName().toString(), "-o", "clack-p1.wasm");
		String path = "/tmp/" + workDir.getFileName() + "-clack-p1.wasm";
		WasmtimeSupport.container()
			.copyFileToContainer(Transferable.of(Files.readAllBytes(workDir.resolve("clack-p1.wasm"))), path);
		ExecResult result = WasmtimeSupport.container()
			.execInContainer("wasmtime", "run", "-W", "gc=y", "-W", "exceptions=y", path);
		assertThat(result.getExitCode()).as("stderr: %s", result.getStderr()).isZero();
		assertThat(result.getStdout()).contains(":CAUGHT").contains("HTTP-HANDLER requires --component");
	}

	// A free TCP port (bound then released; a tiny race, acceptable for tests).
	private static int freePort() throws Exception {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
	}

	private static Path writeProgram(Path workDir, String source) throws Exception {
		Path program = workDir.resolve("clack-probe.lisp");
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
