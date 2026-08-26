package am.ik.rontolisp.e2e;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import am.ik.rontolisp.cli.JvmSourceCompiler;
import am.ik.rontolisp.cli.RontoLispCli;
import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.eclipse.jetty.ee10.annotations.AnnotationConfiguration;
import org.eclipse.jetty.ee10.webapp.WebAppContext;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The {@code -o app.war} output end to end: one war compiled by the CLI, deployed
 * UNMODIFIED into embedded Tomcat AND Jetty, serving the rows the {@code .todo/529} spike
 * verified by hand -- and the two invariants only a deployment can pin: the
 * one-virtual-thread-per-request rule under a container pool far smaller than the burst
 * ({@code .kb/concurrent-served-requests.md}), and a broken program failing the
 * DEPLOYMENT rather than 500ing forever.
 *
 * <p>
 * Opt-in ({@code -Drontolisp.war.e2e=true}), like the other served suites: it boots two
 * servlet containers.
 *
 * <pre>
 * ./mvnw -Dtest=WarE2eTest -DfailIfNoTests=false -Drontolisp.war.e2e=true test
 * </pre>
 */
class WarE2eTest {

	@TempDir
	Path tempDir;

	private static void optIn() {
		assumeTrue("true".equals(System.getProperty("rontolisp.war.e2e")),
				"the war E2E is opt-in (it boots embedded Tomcat and Jetty): pass -Drontolisp.war.e2e=true");
	}

	/**
	 * The spike's handler: an echo, a signalling arm, a slow awaited arm, an octet arm.
	 */
	private static final String HANDLER = """
			(rontolisp:async-defun handler (env)
			  (let ((path (getf env :path-info))
			        (method (getf env :request-method))
			        (query (getf env :query-string))
			        (headers (getf env :headers))
			        (body (getf env :raw-body))
			        (len (getf env :content-length)))
			    (cond
			      ((string= path "/error")
			       (error "deliberate handler failure"))
			      ((string= path "/slow")
			       (rontolisp:await (rontolisp:wait-for 300))
			       (list 200 (list :content-type "text/plain")
			             (list (java:call (java:static "java.lang.Thread" "currentThread") "toString"))))
			      ((string= path "/bin")
			       (let ((octets (make-array 3 :element-type '(unsigned-byte 8))))
			         (setf (aref octets 0) #xff)
			         (setf (aref octets 1) #xfe)
			         (setf (aref octets 2) #x41)
			         (list 200 (list :content-type "application/octet-stream") octets)))
			      (t
			       (list 200
			             (list :content-type "text/plain" :x-demo "one" :x-demo "two")
			             (list (format nil "path=~a method=~a query=~a ua=~a len=~a body=~a"
			                           path method query (gethash "user-agent" headers) len
			                           (if (and body len (> len 0))
			                               (let ((s (make-string len))) (read-sequence s body) s)
			                               ""))))))))

			(rontolisp:http-handler 'handler :raw-body :buffered)
			""";

	private Path compileWar(String source) throws Exception {
		Path program = this.tempDir.resolve("app.lisp");
		Files.writeString(program, source);
		Path war = this.tempDir.resolve("app.war");
		new RontoLispCli(new ByteArrayInputStream(new byte[0]),
				new PrintStream(java.io.OutputStream.nullOutputStream()))
			.run(new String[] { program.toString(), "-o", war.toString() });
		return war;
	}

	@Test
	void theWarServesOnTomcatAndAbsorbsConcurrencyThroughAsync() throws Exception {
		optIn();
		Path war = compileWar(HANDLER);
		Tomcat tomcat = tomcat(war, 4);
		try {
			int port = tomcat.getConnector().getLocalPort();
			serveRows(port);

			// The concurrency invariant, pinned rather than assumed: 16 concurrent
			// requests through a 4-thread connector pool must each see a DISTINCT
			// handler thread -- startAsync releases the container thread and the
			// pipeline runs one virtual thread per request. A throughput number would
			// drift on CI; a distinct-thread count will not.
			HttpClient client = client();
			List<CompletableFuture<HttpResponse<String>>> slow = new ArrayList<>();
			for (int i = 0; i < 16; i++) {
				slow.add(client.sendAsync(HttpRequest.newBuilder(uri(port, "/slow")).build(),
						HttpResponse.BodyHandlers.ofString()));
			}
			Set<String> threads = new LinkedHashSet<>();
			for (CompletableFuture<HttpResponse<String>> response : slow) {
				HttpResponse<String> answered = response.get();
				assertThat(answered.statusCode()).isEqualTo(200);
				threads.add(answered.body());
			}
			assertThat(threads).as("every request runs on its own virtual thread").hasSize(16);
			assertThat(threads).allMatch(thread -> thread.contains("VirtualThread"));

			// And the load-test shape this bug family actually shows up in: concurrent
			// POSTs, expecting every one of them back with its own body.
			List<CompletableFuture<HttpResponse<String>>> posts = new ArrayList<>();
			for (int i = 0; i < 16; i++) {
				posts.add(client.sendAsync(HttpRequest.newBuilder(uri(port, "/echo"))
					.POST(HttpRequest.BodyPublishers.ofString("post-" + i))
					.build(), HttpResponse.BodyHandlers.ofString()));
			}
			for (int i = 0; i < 16; i++) {
				HttpResponse<String> answered = posts.get(i).get();
				assertThat(answered.statusCode()).isEqualTo(200);
				assertThat(answered.body()).contains("body=post-" + i);
			}
		}
		finally {
			tomcat.stop();
			tomcat.destroy();
		}
	}

	@Test
	void theSameWarServesUnmodifiedOnJetty() throws Exception {
		optIn();
		Path war = compileWar(HANDLER);
		Server jetty = new Server(0);
		WebAppContext context = new WebAppContext();
		context.setContextPath("/");
		context.setWar(war.toString());
		// An EMBEDDED Jetty runs initializers only with the annotation configuration
		// added (a standalone Jetty distribution enables the annotations module for a
		// deployed webapp on its own). A property of embedding Jetty, not of the war.
		context.addConfiguration(new AnnotationConfiguration());
		context.setThrowUnavailableOnStartupException(true);
		jetty.setHandler(context);
		jetty.start();
		try {
			serveRows(((ServerConnector) jetty.getConnectors()[0]).getLocalPort());
		}
		finally {
			jetty.stop();
		}
	}

	/** The spike's table, asserted against a running container. */
	private void serveRows(int port) throws Exception {
		HttpClient client = client();

		// :path-info / :request-method / :query-string / the :headers table / the
		// buffered :raw-body read with read-sequence.
		HttpResponse<String> echo = client.send(HttpRequest.newBuilder(uri(port, "/echo?a=1"))
			.header("User-Agent", "ronto-e2e")
			.POST(HttpRequest.BodyPublishers.ofString("hello"))
			.build(), HttpResponse.BodyHandlers.ofString());
		assertThat(echo.statusCode()).isEqualTo(200);
		assertThat(echo.body()).isEqualTo("path=/echo method=POST query=a=1 ua=ronto-e2e len=5 body=hello");
		// Repeated response headers: both pairs emitted.
		assertThat(echo.headers().allValues("x-demo")).containsExactly("one", "two");

		// Percent-decoding + UTF-8.
		HttpResponse<String> decoded = client.send(HttpRequest.newBuilder(uri(port, "/caf%C3%A9%20bar")).build(),
				HttpResponse.BodyHandlers.ofString());
		assertThat(decoded.body()).startsWith("path=/café bar ");

		// HEAD.
		HttpResponse<String> head = client.send(
				HttpRequest.newBuilder(uri(port, "/echo")).method("HEAD", HttpRequest.BodyPublishers.noBody()).build(),
				HttpResponse.BodyHandlers.ofString());
		assertThat(head.statusCode()).isEqualTo(200);
		assertThat(head.body()).isEmpty();

		// An (unsigned-byte 8) body reaches the wire byte-exact -- asserted on the RAW
		// bytes, because the text spelling passes on a double-encode
		// (.kb/http-server.md).
		HttpResponse<byte[]> bin = client.send(HttpRequest.newBuilder(uri(port, "/bin")).build(),
				HttpResponse.BodyHandlers.ofByteArray());
		assertThat(bin.body()).containsExactly(0xff, 0xfe, 0x41);

		// A signalling handler answers 500 PROMPTLY (complete() in a finally) rather
		// than hanging to the async timeout -- the client timeout is what fails this
		// when it regresses.
		HttpResponse<String> failed = client.send(HttpRequest.newBuilder(uri(port, "/error")).build(),
				HttpResponse.BodyHandlers.ofString());
		assertThat(failed.statusCode()).isEqualTo(500);
	}

	@Test
	void aTopLevelThatSignalsFailsTheDeploymentNotEveryRequest() throws Exception {
		optIn();
		// ExceptionInInitializerError poisons the class permanently; in a container that
		// would be a permanently broken context answering 500 forever. Rethrowing from
		// onStartup turns it into a failed DEPLOYMENT, which is where a broken program
		// belongs.
		Path war = compileWar("""
				(defun handler (env)
				  (list 200 '(:content-type "text/plain") (list "ok")))
				(rontolisp:http-handler 'handler)
				(error "boom at the top level")
				""");
		Tomcat tomcat = tomcat(war, 0);
		try {
			Context context = (Context) tomcat.getHost().findChildren()[0];
			assertThat(context.getState().isAvailable()).as("the deployment must fail, not the requests").isFalse();
		}
		finally {
			tomcat.stop();
			tomcat.destroy();
		}
	}

	@Test
	void aWarBuiltWithoutTheClinitMoveFailsTheDeploymentLoudly() throws Exception {
		optIn();
		// The .todo/529 spike's failure mode: a class whose top level stayed in main
		// deploys, is found, and 500s on every request with an unfilled handler slot.
		// The initializer's post-init check turns that into a failed deployment. The war
		// is hand-assembled here because -o app.war always makes the move -- this pins
		// the guard against a compiler regression.
		JvmSourceCompiler.Result compiled = new JvmSourceCompiler("BrokenApp").compile("""
				(defun handler (env)
				  (list 200 '(:content-type "text/plain") (list "ok")))
				(rontolisp:http-handler 'handler)
				""", null);
		Path war = this.tempDir.resolve("broken.war");
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(war))) {
			zipEntry(zip, "WEB-INF/classes/BrokenApp.class", compiled.classBytes());
			for (Map.Entry<String, byte[]> runtimeClass : compiled.runtimeClasses().entrySet()) {
				zipEntry(zip, "WEB-INF/classes/" + runtimeClass.getKey(), runtimeClass.getValue());
			}
			for (String servletClass : List.of("am/ik/rontolisp/runtime/RontoHttpServlet.class",
					"am/ik/rontolisp/runtime/RontoHttpServletInitializer.class")) {
				zipEntry(zip, "WEB-INF/classes/" + servletClass, classpathResource(servletClass));
			}
			zipEntry(zip, "WEB-INF/classes/META-INF/services/jakarta.servlet.ServletContainerInitializer",
					"am.ik.rontolisp.runtime.RontoHttpServletInitializer\n".getBytes(StandardCharsets.UTF_8));
		}
		Tomcat tomcat = tomcat(war, 0);
		try {
			Context context = (Context) tomcat.getHost().findChildren()[0];
			assertThat(context.getState().isAvailable()).as("an unregistered handler must fail the deployment")
				.isFalse();
		}
		finally {
			tomcat.stop();
			tomcat.destroy();
		}
	}

	private Tomcat tomcat(Path war, int maxThreads) throws Exception {
		Path base = Files.createDirectories(this.tempDir.resolve("tomcat-" + war.getFileName()));
		Files.createDirectories(base.resolve("webapps"));
		Tomcat tomcat = new Tomcat();
		tomcat.setBaseDir(base.toAbsolutePath().toString());
		tomcat.setPort(0);
		tomcat.getConnector();
		if (maxThreads > 0) {
			tomcat.getConnector().setProperty("maxThreads", String.valueOf(maxThreads));
			tomcat.getConnector().setProperty("minSpareThreads", String.valueOf(maxThreads));
		}
		tomcat.addWebapp("", new File(war.toString()).getAbsolutePath());
		tomcat.start();
		return tomcat;
	}

	private static byte[] classpathResource(String path) throws Exception {
		try (InputStream in = WarE2eTest.class.getClassLoader().getResourceAsStream(path)) {
			assertThat(in).as(path).isNotNull();
			return in.readAllBytes();
		}
	}

	private static void zipEntry(ZipOutputStream zip, String name, byte[] content) throws Exception {
		zip.putNextEntry(new ZipEntry(name));
		zip.write(content);
		zip.closeEntry();
	}

	private static HttpClient client() {
		return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
	}

	private static URI uri(int port, String pathAndQuery) {
		return URI.create("http://127.0.0.1:" + port + pathAndQuery);
	}

}
