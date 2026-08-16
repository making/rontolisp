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
 * The REAL postmodern (quickloaded verbatim from the live Quicklisp dist, MOP build --
 * {@code :postmodern-use-mop} on, so {@code table.lisp} and the DAO layer are in) talks
 * to a live PostgreSQL 17 on every backend that can open a TCP socket: the interpreter,
 * the JVM compiler and the WASM component. This is the acceptance test for the
 * query/transaction milestone and the DAO/MOP milestone on top of it --
 * {@link ClPostgresE2eTest} covers the driver underneath, this one covers the programming
 * API.
 *
 * <p>
 * The exercises, each run on all three backends and asserted to produce byte-identical
 * output:
 *
 * <ol>
 * <li><b>the milestone program</b> -- {@code with-connection} around {@code create-table}
 * / {@code insert-into} / {@code query} / {@code with-transaction} / {@code update} /
 * {@code query :single}, all written as S-SQL. Every one of those is expanded at
 * MACROEXPANSION time through {@code *result-styles*}, so the leg is as much a test of
 * the compile-time query machinery as of the wire protocol;</li>
 * <li><b>the run-time SQL</b> -- the other half of that: {@code :insert-rows-into} and a
 * computed column value, which s-sql can only assemble while the program RUNS. It is
 * exactly what the milestone's all-literal forms cannot reach, and it is why
 * {@code .todo/208} went unnoticed until an example was written;</li>
 * <li><b>the reconnect</b> -- the only honest end-to-end exercise of the restart system
 * ({@code .todo/196}), because postmodern is where it is load-bearing rather than
 * decorative. The server terminates the connection under a {@code defprepared} function;
 * the nested {@code handler-bind}s of {@code generate-prepared} must invoke the
 * {@code :reconnect} restart and the SAME call must then answer;</li>
 * <li><b>the retry</b> ({@code INTERPRETER ONLY}) -- a {@code with-transaction} body
 * inserts a row and calls {@code retry-transaction}: the insert has to be rolled back and
 * replayed, which the row's presence (rather than a duplicate-key error) proves. Both
 * compile backends throw out of {@code invoke-restart} here, which is {@code .todo/207};
 * widen this leg to all three when that lands;</li>
 * <li><b>the DAO round trip</b> -- the object-mapping layer the MOP build exists for: a
 * {@code dao-class} class definition runs the metaclass protocol at definition time,
 * {@code deftable}/{@code create-table} derive the table from it, and
 * {@code insert-dao}/{@code get-dao}/{@code upsert-dao}/{@code select-dao} round-trip
 * instances against the live server.</li>
 * </ol>
 *
 * WASM Preview 1 is the fourth backend and is deliberately absent from both: TCP is a
 * CALL-time error there by design ({@code .kb/tcp-sockets.md}), which
 * {@link #preview1ModuleCompilesAndFailsLoudlyAtTheFirstSocketCall(Path)} pins so the gap
 * stays a checked statement rather than an untested claim.
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

	private static final String RUNTIME_SQL_EXPECTED = """
			INSERT INTO fruits (id) VALUES (1), (2)
			((1 10) (2 20) (3 300))
			""";

	private static final String RECONNECT_EXPECTED = """
			10
			10
			""";

	private static final String RETRY_EXPECTED = """
			(2 20)
			""";

	private static final String DAO_EXPECTED = """
			CREATE TABLE fruit (id INTEGER NOT NULL, name TEXT NOT NULL, PRIMARY KEY (id))
			"alice"
			("bob" NIL)
			("carol" T)
			("bob" "carol")
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
	void runtimeSqlOnTheInterpreter(@TempDir Path workDir) throws Exception {
		assertThat(runOn(Backend.INTERPRETER, workDir, runtimeSql(Backend.INTERPRETER)))
			.isEqualToNormalizingWhitespace(RUNTIME_SQL_EXPECTED);
	}

	@Test
	void runtimeSqlOnJvm(@TempDir Path workDir) throws Exception {
		assertThat(runOn(Backend.JVM, workDir, runtimeSql(Backend.JVM)))
			.isEqualToNormalizingWhitespace(RUNTIME_SQL_EXPECTED);
	}

	@Test
	void runtimeSqlOnWasmComponent(@TempDir Path workDir) throws Exception {
		assertThat(runOn(Backend.COMPONENT, workDir, runtimeSql(Backend.COMPONENT)))
			.isEqualToNormalizingWhitespace(RUNTIME_SQL_EXPECTED);
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
	void daoRoundTripOnTheInterpreter(@TempDir Path workDir) throws Exception {
		assertThat(runOn(Backend.INTERPRETER, workDir, dao(Backend.INTERPRETER)))
			.isEqualToNormalizingWhitespace(DAO_EXPECTED);
	}

	@Test
	void daoRoundTripOnJvm(@TempDir Path workDir) throws Exception {
		assertThat(runOn(Backend.JVM, workDir, dao(Backend.JVM))).isEqualToNormalizingWhitespace(DAO_EXPECTED);
	}

	@Test
	void daoRoundTripOnWasmComponent(@TempDir Path workDir) throws Exception {
		assertThat(runOn(Backend.COMPONENT, workDir, dao(Backend.COMPONENT)))
			.isEqualToNormalizingWhitespace(DAO_EXPECTED);
	}

	@Test
	void preview1ModuleCompilesAndFailsLoudlyAtTheFirstSocketCall(@TempDir Path workDir) throws Exception {
		// The documented fourth-backend gap: Preview 1 has no host socket API. Since
		// the usocket shim grew wait-for-input, `listen` joined the tcp built-ins on
		// the todo-195 CALL-time policy (the shim's listen call site is spliced
		// unpruned into every usocket program and must build as dead code), so the
		// driver underneath postmodern now COMPILES here and the refusal moved to run
		// time: the first socket call raises the message naming the backends that
		// work. WHICH built-in is named is a call-order fact and not pinned.
		Path program = writeExercise(workDir, milestone(Backend.INTERPRETER).source("127.0.0.1", 5432));
		runCli(workDir, program.getFileName().toString(), "-o", "probe-p1.wasm");
		String path = "/tmp/" + workDir.getFileName() + ".p1.wasm";
		WASMTIME.copyFileToContainer(Transferable.of(Files.readAllBytes(workDir.resolve("probe-p1.wasm"))), path);
		ExecResult result = WASMTIME.execInContainer("wasmtime", "run", "-W", "gc=y", "-W", "exceptions=y", path);
		assertThat(result.getExitCode()).as("stderr: %s", result.getStderr()).isNotZero();
		assertThat(result.getStderr() + result.getStdout()).as("stderr: %s", result.getStderr())
			.containsPattern("(TCP-CONNECT|listen) requires the interpreter, the JVM backend or");
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

	// The RUN-TIME SQL leg (.todo/208). Every S-SQL form in the milestone above carries
	// literal values only, so s-sql resolves it entirely at macroexpansion time and the
	// statement reaches the wire as a constant. The two forms here do not:
	// :insert-rows-into and a computed (* 3 100) both make s-sql assemble the string at
	// RUN time, through its `strcat` -- "allocate a (make-string n) buffer, `replace`
	// into it, return it". While make-string built an IMMUTABLE string on the compile
	// backends every one of those writes was silently discarded, so the server got a
	// BLANK statement of exactly the right length: it answered "WARNING: Empty query
	// sent.", the row was not inserted, and nothing signalled. The first line pins the
	// assembled SQL itself (no server involved, hence the fixed table name), the second
	// proves both statements actually landed.
	private static Exercise runtimeSql(Backend backend) {
		String table = "runtime_sql_" + backend.name().toLowerCase(Locale.ROOT);
		return (host, port) -> """
				(ql:quickload "postmodern")
				(format t "~a~%" (pomo:sql (:insert-rows-into 'fruits :columns 'id :values '((1) (2)))))
				(pomo:with-connection '("%s" "%s" "%s" "%s" :port %d)
				  (pomo:execute (:drop-table :if-exists '%s))
				  (pomo:execute (:create-table '%s ((id :type integer :primary-key t)
				                                    (price :type integer))))
				  (pomo:execute (:insert-rows-into '%s :columns 'id 'price :values '((1 10) (2 20))))
				  (pomo:execute (:insert-into '%s :set 'id 3 'price (* 3 100)))
				  (print (pomo:query (:order-by (:select '* :from '%s) 'id)))
				  (pomo:execute (:drop-table '%s)))
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

	// The DAO leg -- the MOP build's acceptance exercise. The fruit class pins the
	// dao-table-definition SQL itself (no server involved, hence the fixed class name);
	// the person class does the full round trip through the metaclass-built methods:
	// deftable + !dao-def + create-table, insert-dao, get-dao (an (eql 'person)
	// dispatch), upsert-dao twice for BOTH answers of its (values dao inserted-p)
	// contract (update an existing row -> nil, fall through to insert-dao -> t), and
	// select-dao materializing DAOs through allocate-instance + runtime-name
	// (setf slot-value). The :keys class option only reaches dao-keys because the
	// initarg re-fill replays it over dao-class's shared-initialize :before reset --
	// the CL initialization-order divergence this leg exists to keep pinned.
	private static Exercise dao(Backend backend) {
		String table = "dao_person_" + backend.name().toLowerCase(Locale.ROOT);
		return (host, port) -> """
				(ql:quickload "postmodern")
				(defclass fruit ()
				  ((id :col-type integer :initarg :id :accessor fruit-id)
				   (name :col-type text :initarg :name :accessor fruit-name))
				  (:metaclass pomo:dao-class)
				  (:keys id))
				(format t "~a~%%" (pomo:dao-table-definition 'fruit))
				(defclass person ()
				  ((id :col-type integer :initarg :id :accessor person-id)
				   (name :col-type text :initarg :name :accessor person-name))
				  (:metaclass pomo:dao-class)
				  (:keys id)
				  (:table-name %s))
				(pomo:deftable person (pomo:!dao-def))
				(pomo:with-connection '("%s" "%s" "%s" "%s" :port %d)
				  (pomo:execute (:drop-table :if-exists '%s))
				  (pomo:create-table 'person)
				  (pomo:insert-dao (make-instance 'person :id 1 :name "alice"))
				  (print (person-name (pomo:get-dao 'person 1)))
				  (multiple-value-bind (dao inserted-p)
				      (pomo:upsert-dao (make-instance 'person :id 1 :name "bob"))
				    (print (list (person-name dao) inserted-p)))
				  (multiple-value-bind (dao inserted-p)
				      (pomo:upsert-dao (make-instance 'person :id 2 :name "carol"))
				    (print (list (person-name dao) inserted-p)))
				  (print (mapcar #'person-name (pomo:select-dao 'person t 'id)))
				  (pomo:execute (:drop-table '%s)))
				""".formatted(table, DATABASE, USER, PASSWORD, host, port, table, table);
	}

	/**
	 * The program's own output, with postmodern's reconnect DIAGNOSTIC dropped. That line
	 * is {@code (format *error-output* "~%Database-connection-error ~a~%" condition)} and
	 * now goes to the error stream on every backend ({@code .todo/149} landed --
	 * {@code .kb/standard-output-redirect.md}), so this filter should no longer match
	 * anything on stdout; it stays as a guard until the OTHER half is owned: the
	 * condition renders as a slot dump rather than through its {@code :report}
	 * ({@code .todo/206}), with one slot ({@code :QUERY}) that the interpreter and the
	 * compile paths fill differently. Delete it when that lands.
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
