package am.ik.rontolisp.e2e;

import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import static am.ik.rontolisp.e2e.ServeComponentE2eSupport.PROJECT_DIR;
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
 * The one place a {@code --component} program reads a rich WIT type BACK from a real host
 * and branches on its raw tag: {@code wasi:http}'s {@code method} variant, which
 * {@code request.get-method} lifts and {@code http.lisp}'s {@code %serve-method-string}
 * inspects. Under the reader's upcase premise that lifted case name reads upcased
 * ({@code :POST}), so a lowercase-only comparison ({@code (eq m :post)}) silently misses
 * and every non-GET method collapses to {@code GET}. This pins the fix end-to-end:
 * compile {@code examples/net/http-handler.lisp} to a component, serve it with
 * {@code wasmtime serve}, and confirm each HTTP method round-trips as itself.
 * <p>
 * The interpreter and JVM serve through a Java-backed server whose method never passes
 * through {@code %serve-method-string} (they are covered by {@code HttpHandlerJvmTest} /
 * {@code HttpHandlerTest}); only the component backend lifts the variant, so only it can
 * regress here.
 * <p>
 * Opt-in and gated as {@link ServeComponentE2eSupport} describes: it needs a driver
 * ({@code -Drontolisp.binary=<native binary>}, or the built {@code target/*-exec.jar}
 * when {@code -Drontolisp.examples=true}) and a {@code wasmtime} 46+ on {@code PATH}. A
 * plain {@code mvn test} skips it.
 */
class ServeMethodCaseComponentE2eTest {

	private static final Path HANDLER_SOURCE = PROJECT_DIR.resolve("examples/net/http-handler.lisp");

	@Test
	void everyHttpMethodRoundTripsAsItselfOnAServedComponent() throws Exception {
		List<String> driver = resolveDriver();
		assumeTrue(driver != null, "serve component E2E is opt-in: pass -Drontolisp.binary=<native binary> or "
				+ "-Drontolisp.examples=true (after ./mvnw clean package -DskipTests)");
		assumeTrue(onPath("wasmtime"), "wasmtime is not on PATH");
		assumeTrue(Files.isRegularFile(HANDLER_SOURCE), () -> "handler example is missing: " + HANDLER_SOURCE);

		Path work = Files.createTempDirectory("rontolisp-serve-");
		Process server = null;
		try {
			Path component = work.resolve("handler-component.wasm");
			compileComponent(driver, HANDLER_SOURCE, component, work);

			int port = freePort();
			server = startServe(component, port, work, List.of());
			waitForPort(port, server, work.resolve("serve.log"));

			// GET is the default arm, so it passes even with the bug -- the non-GET
			// methods
			// are what a lowercase-only compare drops to "GET". http-handler.lisp echoes
			// "<method> <path>" on its second line.
			for (String method : List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS")) {
				HttpResponse<String> response = request(port, method, "/probe");
				assertThat(response.statusCode()).as("%s status", method).isEqualTo(200);
				assertThat(response.body()).as("%s must round-trip as itself, not collapse to GET", method)
					.contains(method + " /probe");
			}
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
