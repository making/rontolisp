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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.PullPolicy;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The REAL ningle (Eitaro Fukamachi's "super micro framework", quickloaded verbatim from
 * the live Quicklisp dist together with myway and map-set) routes a Clack application
 * served through {@code clack:clackup} on the {@code clack-handler-rontolisp} backend.
 *
 * <p>
 * This is {@link ClackE2eTest}'s tiny-routes pair over the OTHER routing model, and the
 * difference is what makes it a separate test rather than another route table: ningle is
 * CLOS-based (the application is {@code (make-instance 'ningle:app)}, a
 * {@code lack-component}), it brings its own routing engine (myway, whose every rule
 * compiles to a cl-ppcre scanner), and its dispatch runs REQUIREMENT closures compiled
 * when the route is defined -- so a route can be selected by something other than the
 * path. It also reads every request through {@code lack-request}, which tiny-routes never
 * touches: the whole http-body / fast-http / smart-buffer / circular-streams chain is
 * inside these three legs.
 *
 * <p>
 * Three live legs assert the same HTTP round trip over one application:
 *
 * <ol>
 * <li><b>interpreter</b> and <b>JVM class</b> -- the SAME self-driving program: clackup
 * with every default in force, eleven probes fetched against itself
 * ({@code rontolisp:fetch}), then {@code clack:stop} and the proof the port closed;</li>
 * <li><b>WASM component</b> -- the same routes compiled with {@code --component} and
 * served by {@code wasmtime serve}, answered over HTTP from this test.</li>
 * </ol>
 *
 * WASM Preview 1 is the fourth backend and has no incoming TCP by design, so it cannot
 * serve; ningle's ROUTING runs there, which
 * {@code examples/cloudflare-workers/hello-ningle/check.lisp} pins through the reactor
 * path (its manifest entry runs the interpreter, the JVM and Preview 1).
 *
 * <p>
 * The 404 is load-bearing here and not decoration: ningle's {@code not-found} sets the
 * status and returns nil, so lack's {@code finalize-response} answers a body LIST holding
 * NIL -- the response shape {@code .kb/http-server.md} pins, and every ningle 404 is one.
 *
 * <p>
 * Opt-in ({@code RONTOLISP_NINGLE_E2E=1}): it needs Docker (the pinned wasmtime image)
 * and, on the first run, network access ({@code ql:quickload} downloads ningle, myway,
 * map-set, clack, lack and their dependencies into {@code ~/.rontolisp/quicklisp}).
 *
 * <pre>{@code
 * RONTOLISP_NINGLE_E2E=1 ./mvnw -Dtest=NingleE2eTest -DfailIfNoTests=false test
 * }</pre>
 */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfEnvironmentVariable(named = "RONTOLISP_NINGLE_E2E", matches = "1")
class NingleE2eTest {

	private static final int TIMEOUT_MINUTES = 15;

	private static final String JAVA = Path.of(System.getProperty("java.home"), "bin", "java").toString();

	// The classes this test was compiled against, so the CLI subprocess needs no jar.
	private static final String CLASSPATH = System.getProperty("java.class.path");

	// The ningle application every leg below serves. Read inside :ningle-app -- an
	// application uses a routing library from its own package, and that is what a
	// compiled serve component needs HttpLibrary's synthesized bridge to survive.
	private static final String NINGLE_APP = """
			(ql:quickload "clack")
			(ql:quickload "ningle")

			(defpackage :ningle-app (:use :cl))
			(in-package :ningle-app)

			(defvar *app* (make-instance 'ningle:app))

			;; A bare value is a controller: ningle answers it as the body. This is the
			;; first line of ningle's README.
			(setf (ningle:route *app* "/") "Welcome to ningle!")

			;; A :name token binds one path segment into the parameter alist.
			(setf (ningle:route *app* "/hello/:name")
			      (lambda (params) (format nil "Hello, ~A" (cdr (assoc :name params)))))

			;; Splats collect into one :splat entry.
			(setf (ningle:route *app* "/say/*/to/*")
			      (lambda (params) (format nil "splat=~S" (cdr (assoc :splat params)))))

			;; A regex route: the groups arrive under :captures.
			(setf (ningle:route *app* "/re/([\\\\w]+)" :regexp t)
			      (lambda (params) (format nil "cap=~A" (first (cdr (assoc :captures params))))))

			;; Query and body parameters arrive in the same alist, keyed by the STRING
			;; name -- the body one over lack-request's parse of the buffered :raw-body.
			(setf (ningle:route *app* "/search")
			      (lambda (params) (format nil "q=~A" (cdr (assoc "q" params :test #'string=)))))
			(setf (ningle:route *app* "/submit" :method :POST)
			      (lambda (params) (format nil "posted ~A" (cdr (assoc "q" params :test #'string=)))))

			;; A controller may answer the full Clack triple instead of a body.
			(setf (ningle:route *app* "/list")
			      (lambda (params) (declare (ignore params))
			        '(200 (:content-type "text/plain") ("as-list"))))

			;; ... or mutate the response object bound for this request.
			(setf (ningle:route *app* "/teapot")
			      (lambda (params) (declare (ignore params))
			        (setf (lack.response:response-status ningle:*response*) 418)
			        "teapot"))

			;; A user-defined REQUIREMENT: two routes on ONE path, told apart by
			;; something that is not the path at all. The closure is compiled when the
			;; route is defined and run on every dispatch, which is the machinery
			;; tiny-routes has no counterpart for.
			(setf (ningle:requirement *app* :flagged)
			      (lambda (want)
			        (let ((got (gethash "x-flag" (lack.request:request-headers ningle:*request*))))
			          (and got (string= got want) (values t got)))))
			(setf (ningle:route *app* "/flag" :flagged "on")
			      (lambda (params) (format nil "flagged=~A" (cdr (assoc :flagged params)))))
			(setf (ningle:route *app* "/flag")
			      (lambda (params) (declare (ignore params)) "unflagged"))
			""";

	// What the eleven probes echo back, then the stop proofs. The 404 line is ningle's
	// own not-found: it sets the status and returns nil, so the body is the empty string.
	private static final String NINGLE_EXPECTED = """
			200
			"Welcome to ningle!"
			200
			"Hello, Eitaro"
			200
			"splat=(\\"hello\\" \\"world\\")"
			200
			"cap=abc"
			200
			"q=lisp"
			200
			"posted body"
			200
			"as-list"
			418
			"teapot"
			200
			"flagged=on"
			200
			"unflagged"
			404
			""
			T
			NIL
			""";

	/**
	 * The self-driving program the interpreter and JVM legs run. The fetches are spelled
	 * out at top level rather than through a helper: rontolisp:await is only allowed
	 * there or inside an async-defun/async-lambda, so the probe macro expands into one.
	 */
	private static String ningleExercise(int port) {
		return NINGLE_APP + """
				(defvar *server*
				  (clack:clackup *app* :server :rontolisp :port %d :silent t :debug nil))

				(defmacro probe (url &rest options)
				  `(let ((response (rontolisp:await (rontolisp:fetch ,url ,@options))))
				     (print (getf response :status))
				     (print (rontolisp:await (rontolisp:read-all (getf response :body))))))

				(probe "http://127.0.0.1:%d/")
				(probe "http://127.0.0.1:%d/hello/Eitaro")
				(probe "http://127.0.0.1:%d/say/hello/to/world")
				(probe "http://127.0.0.1:%d/re/abc")
				(probe "http://127.0.0.1:%d/search?q=lisp")
				(probe "http://127.0.0.1:%d/submit"
				       '(:method "POST"
				         :headers (("content-type" . "application/x-www-form-urlencoded"))
				         :body "q=body"))
				(probe "http://127.0.0.1:%d/list")
				(probe "http://127.0.0.1:%d/teapot")
				(probe "http://127.0.0.1:%d/flag" '(:headers (("x-flag" . "on"))))
				(probe "http://127.0.0.1:%d/flag")
				;; No rule matches: ningle's not-found answers 404 with a NIL body.
				(probe "http://127.0.0.1:%d/nope")

				(print (clack:stop *server*))
				(print (ignore-errors (rontolisp:await (rontolisp:fetch "http://127.0.0.1:%d/"))))
				""".formatted(port, port, port, port, port, port, port, port, port, port, port, port, port);
	}

	/** The serve-shaped program the component leg compiles (the host owns the socket). */
	private static final String NINGLE_COMPONENT_EXERCISE = NINGLE_APP + """
			(clack:clackup *app* :server :rontolisp :port 8080)
			""";

	@Test
	void ningleRoundTripOnTheInterpreter(@TempDir Path workDir) throws Exception {
		Path program = writeProgram(workDir, ningleExercise(freePort()));
		assertThat(runCli(workDir, program.getFileName().toString())).isEqualToNormalizingWhitespace(NINGLE_EXPECTED);
	}

	@Test
	void ningleRoundTripOnJvm(@TempDir Path workDir) throws Exception {
		Path program = writeProgram(workDir, ningleExercise(freePort()));
		runCli(workDir, program.getFileName().toString(), "-o", "NingleProbe.class");
		assertThat(
				runSuccessfully(workDir, JAVA, "-cp", CLASSPATH + java.io.File.pathSeparator + workDir, "NingleProbe"))
			.isEqualToNormalizingWhitespace(NINGLE_EXPECTED);
	}

	@Test
	void ningleServesOnWasmComponentUnderWasmtimeServe(@TempDir Path workDir) throws Exception {
		Path program = writeProgram(workDir, NINGLE_COMPONENT_EXERCISE);
		runCli(workDir, program.getFileName().toString(), "-o", "ningle.wasm", "--component");
		byte[] component = Files.readAllBytes(workDir.resolve("ningle.wasm"));
		// -S cli/tcp/inherit-network: the spliced usocket shim (a clack dependency)
		// wit-imports wasi:sockets, which wasmtime serve gates by permission.
		try (GenericContainer<?> serve = new GenericContainer<>(WasmtimeSupport.IMAGE)
			.withImagePullPolicy(PullPolicy.alwaysPull())
			.withCopyToContainer(Transferable.of(component), "/ningle.wasm")
			.withExposedPorts(8080)
			.withCommand("wasmtime", "serve", "-W", "gc=y", "-W", "exceptions=y", "-S", "cli=y", "-S", "tcp=y", "-S",
					"inherit-network=y", "--addr", "0.0.0.0:8080", "/ningle.wasm")
			.waitingFor(Wait.forListeningPort())) {
			serve.start();
			String base = "http://" + serve.getHost() + ":" + serve.getMappedPort(8080);
			HttpClient client = HttpClient.newHttpClient();
			HttpResponse<String> welcome = client.send(HttpRequest.newBuilder(URI.create(base + "/")).GET().build(),
					HttpResponse.BodyHandlers.ofString());
			assertThat(welcome.statusCode()).isEqualTo(200);
			assertThat(welcome.body()).isEqualTo("Welcome to ningle!");
			HttpResponse<String> hello = client.send(
					HttpRequest.newBuilder(URI.create(base + "/hello/Eitaro")).GET().build(),
					HttpResponse.BodyHandlers.ofString());
			assertThat(hello.body()).isEqualTo("Hello, Eitaro");
			HttpResponse<String> splat = client.send(
					HttpRequest.newBuilder(URI.create(base + "/say/hello/to/world")).GET().build(),
					HttpResponse.BodyHandlers.ofString());
			assertThat(splat.body()).isEqualTo("splat=(\"hello\" \"world\")");
			HttpResponse<String> regex = client.send(HttpRequest.newBuilder(URI.create(base + "/re/abc")).GET().build(),
					HttpResponse.BodyHandlers.ofString());
			assertThat(regex.body()).isEqualTo("cap=abc");
			HttpResponse<String> query = client.send(
					HttpRequest.newBuilder(URI.create(base + "/search?q=lisp")).GET().build(),
					HttpResponse.BodyHandlers.ofString());
			assertThat(query.body()).isEqualTo("q=lisp");
			HttpResponse<String> posted = client.send(HttpRequest.newBuilder(URI.create(base + "/submit"))
				.header("content-type", "application/x-www-form-urlencoded")
				.POST(HttpRequest.BodyPublishers.ofString("q=body"))
				.build(), HttpResponse.BodyHandlers.ofString());
			assertThat(posted.body()).isEqualTo("posted body");
			HttpResponse<String> teapot = client.send(
					HttpRequest.newBuilder(URI.create(base + "/teapot")).GET().build(),
					HttpResponse.BodyHandlers.ofString());
			assertThat(teapot.statusCode()).isEqualTo(418);
			assertThat(teapot.body()).isEqualTo("teapot");
			HttpResponse<String> flagged = client.send(
					HttpRequest.newBuilder(URI.create(base + "/flag")).header("x-flag", "on").GET().build(),
					HttpResponse.BodyHandlers.ofString());
			assertThat(flagged.body()).isEqualTo("flagged=on");
			HttpResponse<String> unflagged = client.send(
					HttpRequest.newBuilder(URI.create(base + "/flag")).GET().build(),
					HttpResponse.BodyHandlers.ofString());
			assertThat(unflagged.body()).isEqualTo("unflagged");
			// ningle's not-found: status set, controller nil -- the (404 () (NIL)) shape.
			HttpResponse<String> missing = client.send(HttpRequest.newBuilder(URI.create(base + "/nope")).GET().build(),
					HttpResponse.BodyHandlers.ofString());
			assertThat(missing.statusCode()).isEqualTo(404);
			assertThat(missing.body()).isEmpty();
		}
	}

	// A free TCP port (bound then released; a tiny race, acceptable for tests).
	private static int freePort() throws Exception {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
	}

	private static Path writeProgram(Path workDir, String source) throws Exception {
		Path program = workDir.resolve("ningle-probe.lisp");
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
