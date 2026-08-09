package am.ik.rontolisp.e2e;

import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import static am.ik.rontolisp.e2e.ServeComponentE2eSupport.compileComponent;
import static am.ik.rontolisp.e2e.ServeComponentE2eSupport.deleteRecursively;
import static am.ik.rontolisp.e2e.ServeComponentE2eSupport.freePort;
import static am.ik.rontolisp.e2e.ServeComponentE2eSupport.onPath;
import static am.ik.rontolisp.e2e.ServeComponentE2eSupport.request;
import static am.ik.rontolisp.e2e.ServeComponentE2eSupport.resolveDriver;
import static am.ik.rontolisp.e2e.ServeComponentE2eSupport.startServe;
import static am.ik.rontolisp.e2e.ServeComponentE2eSupport.waitForPort;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A served component request whose handler SIGNALS a condition and CATCHES it must answer
 * its response, not kill the worker. The regression this pins: a {@code clack:clackup}
 * application is wrapped in lack's default {@code :backtrace} middleware, whose
 * {@code handler-bind} handler runs at the SIGNAL point and reports through
 * {@code (symbol-value '*error-output*)} -- and when that read signalled "The variable
 * *ERROR-OUTPUT* is unbound" (the eval runtime's global-environment mirror was never
 * seeded with the standard streams, {@code .kb/symbol-runtime-api.md}), the synthesized
 * signal escaped as a wasm {@code unreachable} trap: the host answered a bare 500 and the
 * handler's own {@code handler-case} never saw the original condition. The same program
 * answered correctly on the interpreter and the JVM, and the suite's other component legs
 * never drive a SERVED build over HTTP with a signaling handler, which is why only a
 * hand-run {@code curl} could see it.
 * <p>
 * The interpreter/JVM halves of the middleware seam are pinned by
 * {@code LackEcosystemE2eTest#backtraceMiddleware*}; the wasm mirror-seed itself by the
 * {@code symbol-runtime-api} ci-spec case. This is the missing leg: the full
 * clackup-wrapped signal-and-catch round trip on a component under
 * {@code wasmtime serve}.
 * <p>
 * Opt-in and gated as {@link ServeComponentE2eSupport} describes: it needs a driver
 * ({@code -Drontolisp.binary=<native binary>}, or the built {@code target/*-exec.jar}
 * when {@code -Drontolisp.examples=true}) and a {@code wasmtime} 46+ on {@code PATH}; the
 * first compile also downloads clack into {@code ~/.rontolisp/quicklisp}. A plain
 * {@code mvn test} skips it.
 */
class ServeConditionCatchComponentE2eTest {

	/**
	 * The clackup shape, verbatim from the field report: the default middlewares stay ON
	 * (that is where the trap lived), the handler signals via {@code parse-integer} and
	 * catches with {@code handler-case}.
	 */
	private static final String PROGRAM = """
			(ql:quickload "clack")
			(defun app (env)
			  (declare (ignore env))
			  (list 200 '(:content-type "text/plain")
			        (list (format nil "~a~%" (handler-case (parse-integer "x") (error () "caught"))))))
			(clack:clackup #'app :server :rontolisp :port 8080 :use-thread nil)
			""";

	@Test
	void aCaughtConditionInsideAServedRequestAnswersTheHandlersResponse() throws Exception {
		List<String> driver = resolveDriver();
		assumeTrue(driver != null, "serve component E2E is opt-in: pass -Drontolisp.binary=<native binary> or "
				+ "-Drontolisp.examples=true (after ./mvnw clean package -DskipTests)");
		assumeTrue(onPath("wasmtime"), "wasmtime is not on PATH");

		Path work = Files.createTempDirectory("rontolisp-serve-catch-");
		Process server = null;
		try {
			Path source = work.resolve("catch-probe.lisp");
			Files.writeString(source, PROGRAM);
			Path component = work.resolve("catch-probe.wasm");
			compileComponent(driver, source, component, work);

			int port = freePort();
			// clack's quickload closure reaches usocket, so the component imports
			// wasi:cli and wasi:sockets and the linker must be granted both.
			server = startServe(component, port, work,
					List.of("-S", "cli=y", "-S", "tcp=y", "-S", "inherit-network=y"));
			waitForPort(port, server, work.resolve("serve.log"));

			HttpResponse<String> response = request(port, "GET", "/");
			assertThat(response.statusCode()).as("the handler's own response, not the host's trap 500").isEqualTo(200);
			assertThat(response.body()).isEqualToNormalizingWhitespace("caught");
		}
		finally {
			if (server != null) {
				server.destroyForcibly();
				server.waitFor(10, TimeUnit.SECONDS);
			}
			deleteRecursively(work);
		}
	}

}
