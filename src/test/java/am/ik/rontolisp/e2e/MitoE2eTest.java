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
 * The REAL mito (quickloaded verbatim from the live Quicklisp dist -- {@code mito-core} +
 * {@code mito-migration} + {@code lack-middleware-mito}, over the real sxql and
 * {@code dbd-postgres}) talks to a live PostgreSQL 17 on every backend that can open a
 * TCP socket: the interpreter, the JVM compiler and the WASM component. This is the
 * acceptance test for the mito milestone ({@code .todo/238}); {@link ClPostgresE2eTest}
 * covers the driver underneath and {@link PostmodernE2eTest} the other PostgreSQL stack.
 * The topic file is {@code .kb/mito.md}.
 *
 * <p>
 * The exercises, each run on all three backends and asserted to produce byte-identical
 * output:
 *
 * <ol>
 * <li><b>the DAO round trip</b> -- {@code connect-toplevel} (:postgres),
 * {@code deftable}'s metaclass protocol, the {@code table-definition} DDL text,
 * {@code ensure-table-exists}, then {@code create-dao} / {@code insert-dao} /
 * {@code find-dao} / {@code save-dao} / {@code select-dao} with an sxql {@code where} +
 * {@code order-by} / {@code delete-dao}. The DDL is pinned off a SECOND class with a
 * fixed table name, so the assertion stays one shared string while the round trip runs
 * against a per-backend table;</li>
 * <li><b>the migration diff cycle</b> -- a {@code deftable} REdefinition one column
 * wider, {@code migration-expressions} (the diff against the live schema, not a file),
 * {@code migrate-table}, and a re-diff that has to come back empty with the existing row
 * intact. Writing migration FILES ({@code generate-migrations}) is interpreter + JVM by
 * the scope decision in {@code .kb/mito.md} -- no WASI directory-creation call is
 * imported -- so this leg exercises the DB-side workflow, which is the part all three
 * share;</li>
 * <li><b>{@code count-dao}</b> ({@code INTERPRETER ONLY}) -- see
 * {@link #countDaoIsUndefinedOnTheCompiledBackends()}.</li>
 * </ol>
 *
 * WASM Preview 1 is the fourth backend and is deliberately absent: TCP is a compile error
 * there by design ({@code .kb/tcp-sockets.md}), which
 * {@link #failsToCompileOnWasmPreview1()} pins so the gap stays a checked statement
 * rather than an untested claim.
 *
 * <p>
 * The container takes {@code POSTGRES_HOST_AUTH_METHOD=trust} deliberately: a
 * scram-sha-256 handshake costs ~60 s here (PBKDF2 x4096 in interpreted ironclad) and
 * races PostgreSQL's own 60 s {@code authentication_timeout}, surfacing as the misleading
 * {@code Database error: end of file}. Reason and the fix path: {@code .todo/253}.
 *
 * <p>
 * Like {@link PostmodernE2eTest} this drives the real CLI in a subprocess: the
 * {@code --component} leg needs the socket library splices only {@code RontoLispCli}
 * wires up, and the JVM leg needs a path-free {@code -o Probe.class}.
 *
 * <p>
 * The whole class is opt-in ({@code RONTOLISP_POSTGRES_E2E=1}): it needs Docker and, on
 * the first run, network access ({@code ql:quickload} downloads mito and its dependencies
 * into {@code ~/.rontolisp/quicklisp}).
 *
 * <pre>{@code
 * RONTOLISP_POSTGRES_E2E=1 ./mvnw -Dtest=MitoE2eTest -DfailIfNoTests=false test
 * }</pre>
 */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfEnvironmentVariable(named = "RONTOLISP_POSTGRES_E2E", matches = "1")
class MitoE2eTest {

	private static final String DATABASE = "postgres";

	private static final String USER = "postgres";

	// Never actually checked (the server takes trust), but cl-postgres sends a startup
	// packet either way and the driver's password argument is not optional.
	private static final String PASSWORD = "mito";

	/** The backends mito reaches; Preview 1 cannot open a socket at all. */
	private enum Backend {

		INTERPRETER, JVM, COMPONENT

	}

	/** Builds an exercise's source against a server reachable at {@code host:port}. */
	@FunctionalInterface
	private interface Exercise {

		String source(String host, int port);

	}

	private static final String CRUD_EXPECTED = """
			CREATE TABLE mito_spec (
			    id BIGSERIAL NOT NULL PRIMARY KEY,
			    title VARCHAR(64) NOT NULL,
			    body TEXT,
			    created_at TIMESTAMPTZ,
			    updated_at TIMESTAMPTZ
			)
			created 1 alpha
			found 1 first
			after-save edited
			selected (alpha beta)
			remaining 1
			""";

	private static final String COUNT_EXPECTED = """
			count 2
			""";

	/** Generous: the component leg compiles the whole mito tree first. */
	private static final int TIMEOUT_MINUTES = 20;

	private static final String JAVA = Path.of(System.getProperty("java.home"), "bin", "java").toString();

	// The classes this test was compiled against, so the CLI subprocess needs no jar.
	private static final String CLASSPATH = System.getProperty("java.class.path");

	private static final Network NETWORK = Network.newNetwork();

	@Container
	private static final GenericContainer<?> POSTGRES = new GenericContainer<>(
			DockerImageName.parse("postgres:17-alpine"))
		.withNetwork(NETWORK)
		.withExposedPorts(5432)
		// trust, not a password: see the class javadoc and .todo/253.
		.withEnv("POSTGRES_HOST_AUTH_METHOD", "trust")
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
	void daoRoundTripOnTheInterpreter(@TempDir Path workDir) throws Exception {
		assertThat(runOn(Backend.INTERPRETER, workDir, crud(Backend.INTERPRETER)))
			.isEqualToNormalizingWhitespace(CRUD_EXPECTED);
	}

	@Test
	void daoRoundTripOnJvm(@TempDir Path workDir) throws Exception {
		assertThat(runOn(Backend.JVM, workDir, crud(Backend.JVM))).isEqualToNormalizingWhitespace(CRUD_EXPECTED);
	}

	@Test
	void daoRoundTripOnWasmComponent(@TempDir Path workDir) throws Exception {
		assertThat(runOn(Backend.COMPONENT, workDir, crud(Backend.COMPONENT)))
			.isEqualToNormalizingWhitespace(CRUD_EXPECTED);
	}

	@Test
	void migrationOnTheInterpreter(@TempDir Path workDir) throws Exception {
		assertThat(runOn(Backend.INTERPRETER, workDir, migration(Backend.INTERPRETER)))
			.isEqualToNormalizingWhitespace(migrationExpected(Backend.INTERPRETER));
	}

	@Test
	void migrationOnJvm(@TempDir Path workDir) throws Exception {
		assertThat(runOn(Backend.JVM, workDir, migration(Backend.JVM)))
			.isEqualToNormalizingWhitespace(migrationExpected(Backend.JVM));
	}

	@Test
	void migrationOnWasmComponent(@TempDir Path workDir) throws Exception {
		assertThat(runOn(Backend.COMPONENT, workDir, migration(Backend.COMPONENT)))
			.isEqualToNormalizingWhitespace(migrationExpected(Backend.COMPONENT));
	}

	@Test
	void countDaoOnTheInterpreter(@TempDir Path workDir) throws Exception {
		assertThat(runOn(Backend.INTERPRETER, workDir, count(Backend.INTERPRETER)))
			.isEqualToNormalizingWhitespace(COUNT_EXPECTED);
	}

	@Test
	void countDaoIsUndefinedOnTheCompiledBackends(@TempDir Path workDir) throws Exception {
		// The one shape of this milestone that is NOT identical across the three
		// backends, kept here as a checked statement rather than an untested claim.
		// count-dao builds (:count :*), and sxql resolves an operator it has no
		// op-struct for through find-make-op -> (find-symbol "MAKE-COUNT-OP" pkg) with
		// :errorp nil, expecting NIL and falling back to a generic function-op. The
		// compiled backends answer a SYMBOL for an unknown name instead
		// (.kb/symbol-runtime-api.md), so the fallback never runs and symbol-function
		// signals. That hits every sxql SQL FUNCTION call (:count / :sum / :max / ...),
		// not just count-dao. Filed as .todo/254; delete this test and widen
		// countDaoOnTheInterpreter to all three legs when it lands.
		Path program = writeExercise(workDir,
				count(Backend.JVM).source(POSTGRES.getHost(), POSTGRES.getMappedPort(5432)));
		runCli(workDir, program.getFileName().toString(), "-o", "Probe.class");
		Result result = run(workDir, JAVA, "-cp", workDir.toString(), "Probe");
		assertThat(result.exitCode()).as("%s", result).isNotZero();
		assertThat(result.err()).contains("The function SXQL/OPERATOR:MAKE-COUNT-OP is undefined");
	}

	@Test
	void failsToCompileOnWasmPreview1(@TempDir Path workDir) throws Exception {
		// The documented fourth-backend gap: Preview 1 has no host socket API, so the
		// cl-postgres driver under mito is a compile error there -- a loud one naming the
		// backends that do work, not a module that fails at run time. The socket call the
		// compiler reaches first is `listen` (cl-postgres probes for pending input before
		// every message read), not tcp-connect.
		Path program = writeExercise(workDir, crud(Backend.INTERPRETER).source("127.0.0.1", 5432));
		Result result = run(workDir, JAVA, "-cp", CLASSPATH, "am.ik.rontolisp.cli.RontoLispCli",
				program.getFileName().toString(), "-o", "probe-p1.wasm");
		assertThat(result.exitCode()).as("%s", result).isNotZero();
		assertThat(result.err()).contains("requires the interpreter, the JVM backend or a --component socket stream");
		assertThat(workDir.resolve("probe-p1.wasm")).doesNotExist();
	}

	// The DAO round trip. `spec` exists only to pin the generated DDL: table-definition
	// needs a connection (it asks the driver type) but not a table, so its fixed name
	// keeps the assertion backend-independent while `article` carries the per-backend
	// name the three legs need to stay out of each other's way. Accessors are NOT
	// generated from a deftable's :conc-name (.kb/mito.md), hence slot-value.
	private static Exercise crud(Backend backend) {
		String table = "mito_article_" + backend.name().toLowerCase(Locale.ROOT);
		return (host, port) -> """
				(ql:quickload '("mito" "dbd-postgres"))
				(mito:deftable spec ()
				  ((title :col-type (:varchar 64))
				   (body :col-type (or :text :null)))
				  (:table-name "mito_spec"))
				(mito:deftable article ()
				  ((title :col-type (:varchar 64))
				   (body :col-type (or :text :null)))
				  (:table-name "%s"))
				(mito:connect-toplevel :postgres :database-name "%s" :username "%s"
				                       :password "%s" :host "%s" :port %d)
				(dolist (statement (mito:table-definition 'spec))
				  (format t "~a~%%" (sxql:yield statement)))
				(mito:execute-sql "DROP TABLE IF EXISTS %s")
				(mito:ensure-table-exists 'article)
				(let ((a (mito:create-dao 'article :title "alpha" :body "first")))
				  (format t "created ~a ~a~%%" (mito:object-id a) (slot-value a 'title)))
				(mito:insert-dao (make-instance 'article :title "beta" :body nil))
				(let ((found (mito:find-dao 'article :title "alpha")))
				  (format t "found ~a ~a~%%" (mito:object-id found) (slot-value found 'body))
				  (setf (slot-value found 'body) "edited")
				  (mito:save-dao found))
				(format t "after-save ~a~%%" (slot-value (mito:find-dao 'article :title "alpha") 'body))
				(format t "selected ~a~%%"
				        (mapcar (lambda (o) (slot-value o 'title))
				                (mito:select-dao 'article
				                                 (sxql:where (:like :title "%%a"))
				                                 (sxql:order-by :id))))
				(mito:delete-dao (mito:find-dao 'article :title "beta"))
				(format t "remaining ~a~%%" (length (mito:select-dao 'article)))
				(mito:disconnect-toplevel)
				""".formatted(table, DATABASE, USER, PASSWORD, host, port, table);
	}

	// The migration diff cycle. The added column is NULLABLE on purpose: a NOT NULL
	// column with an :initform makes migrate-table emit `DEFAULT ?` with an EMPTY bind
	// list and PostgreSQL answers "there is no parameter $1" -- an UPSTREAM sxql defect
	// reproduced identically by SBCL, documented in .kb/mito.md, not a shape to pin here.
	private static Exercise migration(Backend backend) {
		String table = "mito_note_" + backend.name().toLowerCase(Locale.ROOT);
		return (host, port) -> """
				(ql:quickload '("mito" "dbd-postgres"))
				(mito:connect-toplevel :postgres :database-name "%s" :username "%s"
				                       :password "%s" :host "%s" :port %d)
				(mito:execute-sql "DROP TABLE IF EXISTS %s")
				(mito:deftable note ()
				  ((body :col-type (:varchar 32)))
				  (:table-name "%s"))
				(mito:ensure-table-exists 'note)
				(mito:create-dao 'note :body "before")
				;; The same table, one column wider: the redefinition is what gets diffed.
				(mito:deftable note ()
				  ((body :col-type (:varchar 32))
				   (tag :col-type (or (:varchar 8) :null)))
				  (:table-name "%s"))
				(dolist (statement (mito.migration:migration-expressions 'note))
				  (format t "~a~%%" (sxql:yield statement)))
				(mito.migration:migrate-table 'note)
				(format t "settled ~a~%%" (mito.migration:migration-expressions 'note))
				(format t "kept ~a~%%"
				        (mapcar (lambda (o) (list (slot-value o 'body) (slot-value o 'tag)))
				                (mito:select-dao 'note (sxql:order-by :id))))
				(mito:disconnect-toplevel)
				""".formatted(DATABASE, USER, PASSWORD, host, port, table, table, table);
	}

	private static String migrationExpected(Backend backend) {
		return """
				ALTER TABLE mito_note_%s ADD COLUMN tag character varying(8)
				settled NIL
				kept ((before NIL))
				""".formatted(backend.name().toLowerCase(Locale.ROOT));
	}

	// count-dao: the aggregate leg, interpreter-only (see
	// countDaoIsUndefinedOnTheCompiledBackends).
	private static Exercise count(Backend backend) {
		String table = "mito_tally_" + backend.name().toLowerCase(Locale.ROOT);
		return (host, port) -> """
				(ql:quickload '("mito" "dbd-postgres"))
				(mito:deftable tally ()
				  ((label :col-type (:varchar 16)))
				  (:table-name "%s"))
				(mito:connect-toplevel :postgres :database-name "%s" :username "%s"
				                       :password "%s" :host "%s" :port %d)
				(mito:execute-sql "DROP TABLE IF EXISTS %s")
				(mito:ensure-table-exists 'tally)
				(mito:create-dao 'tally :label "x")
				(mito:create-dao 'tally :label "y")
				(format t "count ~a~%%" (mito:count-dao 'tally))
				(mito:disconnect-toplevel)
				""".formatted(table, DATABASE, USER, PASSWORD, host, port, table);
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
				// gc for the value representation, exceptions because every mito program
				// has handler-case / unwind-protect, tcp + inherit-network because
				// wasmtime gates sockets by permission.
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
		Path program = workDir.resolve("mito-probe.lisp");
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
