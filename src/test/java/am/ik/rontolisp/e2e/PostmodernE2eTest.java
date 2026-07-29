package am.ik.rontolisp.e2e;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import am.ik.rontolisp.testsupport.WasmtimeSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.PullPolicy;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The REAL postmodern (quickloaded verbatim from the live Quicklisp dist, non-MOP build)
 * talks to a live PostgreSQL 17 on every backend that can open a TCP socket: the
 * interpreter, the JVM compiler and the WASM component. This is the acceptance test for
 * the {@code .todo/202} milestone -- {@link ClPostgresE2eTest} covers the driver
 * underneath it, this one covers the programming API on top.
 *
 * <p>
 * Two exercises, each run on all three backends and asserted to produce byte-identical
 * output:
 *
 * <ol>
 * <li><b>the milestone program</b> -- {@code with-connection} around {@code create-table}
 * / {@code insert-into} / {@code query} / {@code with-transaction} / {@code update} /
 * {@code query :single}, all written as S-SQL. Every one of those is expanded at
 * MACROEXPANSION time through {@code *result-styles*}, so the leg is as much a test of
 * the compile-time query machinery as of the wire protocol;</li>
 * <li><b>the reconnect</b> -- the only honest end-to-end exercise of the restart system
 * ({@code .todo/196}), because postmodern is where it is load-bearing rather than
 * decorative. The server terminates the connection under a {@code defprepared} function;
 * the nested {@code handler-bind}s of {@code generate-prepared} must invoke the
 * {@code :reconnect} restart and the SAME call must then answer;</li>
 * <li><b>the retry</b> ({@code INTERPRETER ONLY}) -- a {@code with-transaction} body
 * inserts a row and calls {@code retry-transaction}: the insert has to be rolled back and
 * replayed, which the row's presence (rather than a duplicate-key error) proves. Both
 * compile backends throw out of {@code invoke-restart} here, which is {@code .todo/207};
 * widen this leg to all three when that lands.</li>
 * </ol>
 *
 * WASM Preview 1 is the fourth backend and is deliberately absent from both: TCP is a
 * compile error there by design ({@code .kb/tcp-sockets.md}), which
 * {@link #failsToCompileOnWasmPreview1()} pins so the gap stays a checked statement
 * rather than an untested claim.
 *
 * <p>
 * {@link #programOutput} drops postmodern's own reconnect diagnostic before comparing;
 * see its javadoc for the two open deviations that make it necessary.
 *
 * <p>
 * Like {@link ClPostgresE2eTest} this drives the real CLI in a subprocess: the
 * {@code --component} leg needs the socket library splices only {@code RontoLispCli}
 * wires up, and the JVM leg needs a path-free {@code -o Probe.class}.
 *
 * <p>
 * The whole class is opt-in ({@code RONTOLISP_POSTGRES_E2E=1}): it needs Docker and, on
 * the first run, network access ({@code ql:quickload} downloads postmodern and its
 * dependencies into {@code ~/.rontolisp/quicklisp}).
 *
 * <pre>{@code
 * RONTOLISP_POSTGRES_E2E=1 ./mvnw -Dtest=PostmodernE2eTest -DfailIfNoTests=false test
 * }</pre>
 */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfEnvironmentVariable(named = "RONTOLISP_POSTGRES_E2E", matches = "1")
class PostmodernE2eTest {

	private static final String DATABASE = "postgres";

	private static final String USER = "postgres";

	private static final String PASSWORD = "pomo";

	/** The backends this API reaches; Preview 1 cannot open a socket at all. */
	private enum Backend {

		INTERPRETER, JVM, COMPONENT

	}

	/** Builds an exercise's source against a server reachable at {@code host:port}. */
	@FunctionalInterface
	private interface Exercise {

		String source(String host, int port);

	}

	private static final String MILESTONE_EXPECTED = """
			((1 "alice"))
			"bob"
			""";

	private static final String RECONNECT_EXPECTED = """
			10
			10
			""";

	private static final String RETRY_EXPECTED = """
			(2 20)
			""";

	/** Generous: the component leg compiles a ~5000-line library tree first. */
	private static final int TIMEOUT_MINUTES = 15;

	private static final String JAVA = Path.of(System.getProperty("java.home"), "bin", "java").toString();

	// The classes this test was compiled against, so the CLI subprocess needs no jar.
	private static final String CLASSPATH = System.getProperty("java.class.path");

	private static final Network NETWORK = Network.newNetwork();

	@Container
	private static final GenericContainer<?> POSTGRES = new GenericContainer<>(
			DockerImageName.parse("postgres:17-alpine"))
		.withNetwork(NETWORK)
		.withExposedPorts(5432)
		.withEnv("POSTGRES_PASSWORD", PASSWORD)
		.waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\s", 2));

	// A dedicated wasmtime container rather than the shared WasmtimeSupport one, because
	// this leg needs it ON the PostgreSQL container's network -- the component connects
	// container to container, so nothing here depends on host port bridging.
	@Container
	private static final GenericContainer<?> WASMTIME = new GenericContainer<>(WasmtimeSupport.IMAGE)
		.withImagePullPolicy(PullPolicy.alwaysPull())
		.withNetwork(NETWORK)
		.withCommand("sleep", "infinity");

	@Test
	void milestoneOnTheInterpreter(@TempDir Path workDir) throws Exception {
		assertThat(runOn(Backend.INTERPRETER, workDir, milestone(Backend.INTERPRETER)))
			.isEqualToNormalizingWhitespace(MILESTONE_EXPECTED);
	}

	@Test
	void milestoneOnJvm(@TempDir Path workDir) throws Exception {
		assertThat(runOn(Backend.JVM, workDir, milestone(Backend.JVM)))
			.isEqualToNormalizingWhitespace(MILESTONE_EXPECTED);
	}

	@Test
	void milestoneOnWasmComponent(@TempDir Path workDir) throws Exception {
		assertThat(runOn(Backend.COMPONENT, workDir, milestone(Backend.COMPONENT)))
			.isEqualToNormalizingWhitespace(MILESTONE_EXPECTED);
	}

	@Test
	void reconnectOnTheInterpreter(@TempDir Path workDir) throws Exception {
		assertThat(programOutput(runOn(Backend.INTERPRETER, workDir, reconnect(Backend.INTERPRETER))))
			.isEqualToNormalizingWhitespace(RECONNECT_EXPECTED);
	}

	@Test
	void reconnectOnJvm(@TempDir Path workDir) throws Exception {
		assertThat(programOutput(runOn(Backend.JVM, workDir, reconnect(Backend.JVM))))
			.isEqualToNormalizingWhitespace(RECONNECT_EXPECTED);
	}

	@Test
	void reconnectOnWasmComponent(@TempDir Path workDir) throws Exception {
		assertThat(programOutput(runOn(Backend.COMPONENT, workDir, reconnect(Backend.COMPONENT))))
			.isEqualToNormalizingWhitespace(RECONNECT_EXPECTED);
	}

	@Test
	void retryTransactionOnTheInterpreter(@TempDir Path workDir) throws Exception {
		// Interpreter ONLY: the same program throws "THROW: no enclosing catch for the
		// tag" out of invoke-restart on both compile backends (.todo/207). Widen this
		// back to all three the way the reconnect leg above is when that lands.
		assertThat(runOn(Backend.INTERPRETER, workDir, retryTransaction(Backend.INTERPRETER)))
			.isEqualToNormalizingWhitespace(RETRY_EXPECTED);
	}

	@Test
	void failsToCompileOnWasmPreview1(@TempDir Path workDir) throws Exception {
		// The documented fourth-backend gap: Preview 1 has no host socket API, so the
		// driver underneath postmodern is a compile error there -- a loud one naming the
		// backends that do work, not a module that fails at run time. The socket call the
		// compiler reaches first here is `listen` (cl-postgres probes for pending input
		// before every message read), not tcp-connect.
		Path program = writeExercise(workDir, milestone(Backend.INTERPRETER).source("127.0.0.1", 5432));
		Result result = run(workDir, JAVA, "-cp", CLASSPATH, "am.ik.rontolisp.cli.RontoLispCli",
				program.getFileName().toString(), "-o", "probe-p1.wasm");
		assertThat(result.exitCode()).as("%s", result).isNotZero();
		assertThat(result.err()).contains("requires the interpreter, the JVM backend or a --component socket stream");
		assertThat(workDir.resolve("probe-p1.wasm")).doesNotExist();
	}

	// The .todo/202 program verbatim, except for the per-backend table name (the three
	// legs share one database, so a shared name would make them depend on each other's
	// cleanup) and the leading drop that makes a re-run idempotent.
	private static Exercise milestone(Backend backend) {
		String table = "person_" + backend.name().toLowerCase(Locale.ROOT);
		return (host, port) -> """
				(ql:quickload "postmodern")
				(pomo:with-connection '("%s" "%s" "%s" "%s" :port %d)
				  (pomo:execute (:drop-table :if-exists '%s))
				  (pomo:execute (:create-table '%s ((id :type integer :primary-key t)
				                                    (name :type text))))
				  (pomo:execute (:insert-into '%s :set 'id 1 'name "alice"))
				  (print (pomo:query (:select '* :from '%s)))
				  (pomo:with-transaction ()
				    (pomo:execute (:update '%s :set 'name "bob" :where (:= 'id 1))))
				  (print (pomo:query (:select 'name :from '%s) :single)))
				""".formatted(DATABASE, USER, PASSWORD, host, port, table, table, table, table, table, table);
	}

	// The reconnect leg. pg_terminate_backend on our OWN backend pid is what makes the
	// connection loss real (and reproducible without touching the container): the server
	// drops the socket mid-query, so the next prepared call signals
	// database-connection-error inside generate-prepared's nested handler-binds, which
	// have to invoke the :reconnect restart and let the SAME call answer.
	private static Exercise reconnect(Backend backend) {
		String suffix = backend.name().toLowerCase(Locale.ROOT);
		String table = "reconnect_" + suffix;
		String prepared = "by-id-" + suffix;
		return (host, port) -> """
				(ql:quickload "postmodern")
				(pomo:with-connection '("%s" "%s" "%s" "%s" :port %d)
				  (pomo:execute (:drop-table :if-exists '%s))
				  (pomo:execute (:create-table '%s ((id :type integer :primary-key t)
				                                    (n :type integer))))
				  (pomo:execute (:insert-into '%s :set 'id 1 'n 10))
				  (pomo:defprepared '%s (:select 'n :from '%s :where (:= 'id '$1)) :single)
				  (print (%s 1))
				  ;; The server drops this connection while answering it.
				  (ignore-errors (pomo:query "select pg_terminate_backend(pg_backend_pid())"))
				  (print (%s 1))
				  (pomo:execute (:drop-table '%s)))
				""".formatted(DATABASE, USER, PASSWORD, host, port, table, table, table, prepared, table, prepared,
				prepared, table);
	}

	// The retry leg: the first attempt inserts and calls retry-transaction, so the insert
	// has to be ROLLED BACK -- otherwise the replay would fail on the primary key instead
	// of answering (2 20).
	private static Exercise retryTransaction(Backend backend) {
		String table = "retry_" + backend.name().toLowerCase(Locale.ROOT);
		return (host, port) -> """
				(ql:quickload "postmodern")
				(pomo:with-connection '("%s" "%s" "%s" "%s" :port %d)
				  (pomo:execute (:drop-table :if-exists '%s))
				  (pomo:execute (:create-table '%s ((id :type integer :primary-key t)
				                                    (n :type integer))))
				  (let ((tries 0))
				    (pomo:with-transaction ()
				      (incf tries)
				      (pomo:execute (:insert-into '%s :set 'id 2 'n 20))
				      (when (< tries 2) (pomo:retry-transaction)))
				    (print (list tries (pomo:query (:select 'n :from '%s :where (:= 'id 2)) :single))))
				  (pomo:execute (:drop-table '%s)))
				""".formatted(DATABASE, USER, PASSWORD, host, port, table, table, table, table, table);
	}

	/**
	 * The program's own output, with postmodern's reconnect DIAGNOSTIC dropped. That line
	 * is {@code (format *error-output* "~%Database-connection-error ~a~%" condition)} and
	 * is excluded because BOTH halves of it are known deviations owned elsewhere, neither
	 * this test's subject: {@code *error-output*} reaches stdout rather than the error
	 * stream ({@code .todo/149}), and the condition renders as a slot dump rather than
	 * through its {@code :report} ({@code .todo/206}) -- with one slot ({@code :QUERY})
	 * that the interpreter and the compile paths fill differently. Delete this filter
	 * when those land.
	 */
	private static String programOutput(String stdout) {
		return stdout.lines()
			.filter(line -> !line.isBlank() && !line.startsWith("Database-connection-error "))
			.collect(java.util.stream.Collectors.joining("\n"));
	}

	/** Compiles (where the backend needs it) and runs the exercise, returning stdout. */
	private static String runOn(Backend backend, Path workDir, Exercise exercise) throws Exception {
		// The component reaches the server over the container network, so it is the one
		// backend compiled against the server's container ADDRESS rather than the mapped
		// host port -- an address, not a network alias, because tcp-connect takes only
		// IPv4 literals on WASM (hostname lookup is unwired there).
		boolean component = backend == Backend.COMPONENT;
		String host = component ? containerIpOf(POSTGRES) : POSTGRES.getHost();
		int port = component ? 5432 : POSTGRES.getMappedPort(5432);
		String name = writeExercise(workDir, exercise.source(host, port)).getFileName().toString();
		return switch (backend) {
			case INTERPRETER -> runCli(workDir, name);
			case JVM -> {
				// The CLI names the class after the output path, hence the path-free -o
				// from the working directory.
				runCli(workDir, name, "-o", "Probe.class");
				yield runSuccessfully(workDir, JAVA, "-cp", workDir.toString(), "Probe");
			}
			case COMPONENT -> {
				runCli(workDir, name, "-o", "probe.wasm", "--component", "--optimize");
				String path = "/tmp/" + workDir.getFileName() + ".component.wasm";
				WASMTIME.copyFileToContainer(Transferable.of(Files.readAllBytes(workDir.resolve("probe.wasm"))), path);
				// gc for the value representation, exceptions because every postmodern
				// program has handler-case / unwind-protect, tcp + inherit-network
				// because wasmtime gates sockets by permission.
				ExecResult result = WASMTIME.execInContainer("wasmtime", "run", "-W", "gc=y", "-W", "exceptions=y",
						"-S", "tcp=y", "-S", "inherit-network=y", path);
				assertThat(result.getExitCode()).as("stderr: %s", result.getStderr()).isZero();
				yield result.getStdout();
			}
		};
	}

	// The container's address on NETWORK; it is attached to exactly that one.
	private static String containerIpOf(GenericContainer<?> container) {
		return Objects.requireNonNull(container.getContainerInfo()
			.getNetworkSettings()
			.getNetworks()
			.values()
			.iterator()
			.next()
			.getIpAddress());
	}

	private static Path writeExercise(Path workDir, String source) throws Exception {
		Path program = workDir.resolve("postmodern-probe.lisp");
		Files.writeString(program, source, StandardCharsets.UTF_8);
		return program;
	}

	/**
	 * Runs the CLI from {@code workDir} (interpreting or compiling) and returns stdout.
	 */
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

	// stderr goes to a file rather than into stdout: a JVM launched with
	// JAVA_TOOL_OPTIONS set announces itself on stderr, and the driver reports server
	// notices there too ("drop table if exists" on a fresh database) -- neither belongs
	// in the middle of the program's output.
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
