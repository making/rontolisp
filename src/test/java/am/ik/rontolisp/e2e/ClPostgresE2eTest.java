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
 * The REAL cl-postgres (Postmodern's low-level PostgreSQL driver, quickloaded verbatim
 * from the live Quicklisp dist) talks to a live PostgreSQL 17 on every backend that can
 * open a TCP socket: the interpreter, the JVM compiler and the WASM component.
 *
 * <p>
 * Three exercises, each run on all three backends and asserted to produce byte-identical
 * output:
 *
 * <ol>
 * <li><b>the authentication ladder</b> -- {@code trust}, {@code password} (cleartext) and
 * {@code md5}, one probe per method against a role that ONLY that method admits (see
 * {@link #HBA_CONF}), so a broken rung fails to connect rather than quietly succeeding
 * through another one. Each probe also asks for {@code current_user}, which is the proof
 * of which role actually got in;</li>
 * <li><b>SCRAM-SHA-256</b> -- the same probe against the SCRAM-only role. It used to be
 * separately opt-in because its 4096-round PBKDF2 ran for over two minutes interpreted;
 * todo 188 made that ~50 s (~1 s on the JVM, ~3 s on the component), so it is an ordinary
 * leg again and the server runs with the DEFAULT {@code authentication_timeout} (60
 * s);</li>
 * <li><b>CRUD</b> -- create / insert / select / update / delete / drop through
 * {@code exec-query}, plus a parameterised statement run twice through
 * {@code prepare-query} + {@code exec-prepared} so the extended protocol is covered too.
 * Each backend owns its own table, so the three legs never collide.</li>
 * <li><b>non-ASCII text</b> -- the same round trip with Japanese (and one non-BMP
 * character), written both as a literal in the query text and as a bound parameter, then
 * read back and cross-checked against the server's own {@code length()}. This is what
 * selects the driver's UTF-8 string implementation rather than its {@code SQL_ASCII} one
 * -- see the {@code :unicode} feature in {@code .kb/reader-features.md}. Before it
 * existed, a bound {@code "お茶"} reached the server as an invalid byte sequence and
 * desynced the connection, so a passing CRUD leg proved nothing about this one.</li>
 * </ol>
 *
 * WASM Preview 1 is the fourth backend and is deliberately absent from all three: TCP is
 * a compile error there by design ({@code .kb/tcp-sockets.md}), which
 * {@link #failsToCompileOnWasmPreview1()} pins so the gap stays a checked statement
 * rather than an untested claim.
 *
 * <p>
 * Unlike the other library E2Es this one drives the real CLI in a subprocess rather than
 * assembling the compile pipeline in-process: the {@code --component} leg needs the
 * socket library splices that only {@code RontoLispCli} wires up, and the JVM leg needs a
 * path-free {@code -o Probe.class} (the CLI names the class after the output path), which
 * is what the throwaway working directory buys. The subprocess runs the same classes this
 * test was compiled against, so no packaged jar is required.
 *
 * <p>
 * The whole class is opt-in ({@code RONTOLISP_POSTGRES_E2E=1}): it needs Docker and, on
 * the first run, network access ({@code ql:quickload} downloads cl-postgres and its seven
 * dependencies into {@code ~/.rontolisp/quicklisp}).
 *
 * <pre>{@code
 * RONTOLISP_POSTGRES_E2E=1 ./mvnw -Dtest=ClPostgresE2eTest -DfailIfNoTests=false test
 * }</pre>
 */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfEnvironmentVariable(named = "RONTOLISP_POSTGRES_E2E", matches = "1")
class ClPostgresE2eTest {

	/** The database the probes connect to (the image's default). */
	private static final String DATABASE = "postgres";

	/** The backends this driver reaches; Preview 1 cannot open a socket at all. */
	private enum Backend {

		INTERPRETER, JVM, COMPONENT

	}

	/** Builds an exercise's source against a server reachable at {@code host:port}. */
	@FunctionalInterface
	private interface Exercise {

		String source(String host, int port);

	}

	// One rule per role, so each probe can only succeed through the method under test:
	// trust takes no password, password sends it in the clear, md5 and scram-sha-256 each
	// run their own challenge/response. The catch-all keeps any other role on the
	// strongest method, and the "local" line is what the image's own bootstrap (initdb +
	// the init script below) connects over.
	private static final String HBA_CONF = """
			local   all all       trust
			host    all trustuser all trust
			host    all passuser  all password
			host    all md5user   all md5
			host    all scramuser all scram-sha-256
			host    all all       all scram-sha-256
			""";

	// password_encryption decides how the verifier is STORED, which is what md5 and
	// scram-sha-256 authentication each need on their side; cleartext "password" auth
	// works against either, so passuser keeps the modern one. The GRANT is what lets the
	// CRUD exercise create its table: since PostgreSQL 15 a plain role cannot.
	private static final String INIT_SQL = """
			SET password_encryption = 'md5';
			CREATE USER md5user PASSWORD 'md5pw';
			SET password_encryption = 'scram-sha-256';
			CREATE USER trustuser PASSWORD 'trustpw';
			CREATE USER passuser  PASSWORD 'passpw';
			CREATE USER scramuser PASSWORD 'scrampw';
			GRANT CREATE ON SCHEMA public TO trustuser;
			""";

	// The probe every authentication test runs; the two queries are
	// examples/db/postgres-hello.lisp's, so this also pins the output its README
	// documents.
	private static final String PROBE_PREAMBLE = """
			(ql:quickload "cl-postgres")
			(defun probe (user password)
			  (let ((conn (cl-postgres:open-database "%s" user password "%s" %d)))
			    (print (cl-postgres:exec-query conn "select current_user, 42, 'hello'"
			                                   'cl-postgres:list-row-reader))
			    (print (cl-postgres:exec-query conn "select generate_series(1, 3) as n"
			                                   'cl-postgres:list-row-reader))
			    (cl-postgres:close-database conn)))
			""";

	private static final String AUTH_LADDER_EXPECTED = """
			(("trustuser" 42 "hello"))
			((1) (2) (3))
			(("passuser" 42 "hello"))
			((1) (2) (3))
			(("md5user" 42 "hello"))
			((1) (2) (3))
			""";

	private static final String SCRAM_EXPECTED = """
			(("scramuser" 42 "hello"))
			((1) (2) (3))
			""";

	private static final String CRUD_EXPECTED = """
			((1 "alpha") (2 "beta"))
			((1 "alpha") (2 "gamma"))
			((2 "gamma"))
			((1))
			((2))
			NIL
			""";

	// The second row's characters are 2 + 1, and the 1 is non-BMP: PostgreSQL counts
	// code points, so a driver that miscounted UTF-16 units would answer 4 here.
	private static final String UNICODE_EXPECTED = """
			((1 "こんにちは") (2 "お茶🍵"))
			((1 5) (2 3))
			""";

	/** Generous: an opted-in SCRAM leg runs for over two minutes on the interpreter. */
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
		.withEnv("POSTGRES_PASSWORD", "postgres")
		.withCopyToContainer(Transferable.of(HBA_CONF), "/etc/postgresql/pg_hba.conf")
		.withCopyToContainer(Transferable.of(INIT_SQL), "/docker-entrypoint-initdb.d/10-auth-ladder.sql")
		// hba_file rather than an edit of the generated one: the entrypoint passes these
		// args to the bootstrap server too, so the ladder is in force from the start.
		//
		// The SCRAM legs run against the DEFAULT authentication_timeout (60 s). A leg
		// that outruns it dies as "READ-BYTE: end of file" while the server logs
		// "canceling authentication due to timeout"; the slowest leg is the
		// interpreter's 4096-round PBKDF2 at ~50 s (~1 s on the JVM, ~3 s on the
		// component since the WASM module-size tax fell -- final GC types plus the
		// _start heap pre-grow, .kb/wasm-gc-final-types.md /
		// .kb/wasm-gc-heap-pregrow.md).
		.withCommand("postgres", "-c", "hba_file=/etc/postgresql/pg_hba.conf")
		// Twice: once for the bootstrap server that runs the init script, once for real.
		.waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\s", 2));

	// A dedicated wasmtime container rather than the shared WasmtimeSupport one, because
	// this leg needs it ON the PostgreSQL container's network -- the component connects
	// container to container, which is why nothing here depends on host port bridging.
	@Container
	private static final GenericContainer<?> WASMTIME = new GenericContainer<>(WasmtimeSupport.IMAGE)
		.withImagePullPolicy(PullPolicy.alwaysPull())
		.withNetwork(NETWORK)
		.withCommand("sleep", "infinity");

	@Test
	void authLadderOnTheInterpreter(@TempDir Path workDir) throws Exception {
		assertThat(runOn(Backend.INTERPRETER, workDir, ClPostgresE2eTest::authLadder))
			.isEqualToNormalizingWhitespace(AUTH_LADDER_EXPECTED);
	}

	@Test
	void authLadderOnJvm(@TempDir Path workDir) throws Exception {
		assertThat(runOn(Backend.JVM, workDir, ClPostgresE2eTest::authLadder))
			.isEqualToNormalizingWhitespace(AUTH_LADDER_EXPECTED);
	}

	@Test
	void authLadderOnWasmComponent(@TempDir Path workDir) throws Exception {
		assertThat(runOn(Backend.COMPONENT, workDir, ClPostgresE2eTest::authLadder))
			.isEqualToNormalizingWhitespace(AUTH_LADDER_EXPECTED);
	}

	@Test
	void scramAuthOnTheInterpreter(@TempDir Path workDir) throws Exception {
		assertThat(runOn(Backend.INTERPRETER, workDir, ClPostgresE2eTest::scram))
			.isEqualToNormalizingWhitespace(SCRAM_EXPECTED);
	}

	@Test
	void scramAuthOnJvm(@TempDir Path workDir) throws Exception {
		assertThat(runOn(Backend.JVM, workDir, ClPostgresE2eTest::scram))
			.isEqualToNormalizingWhitespace(SCRAM_EXPECTED);
	}

	@Test
	void scramAuthOnWasmComponent(@TempDir Path workDir) throws Exception {
		assertThat(runOn(Backend.COMPONENT, workDir, ClPostgresE2eTest::scram))
			.isEqualToNormalizingWhitespace(SCRAM_EXPECTED);
	}

	@Test
	void crudOnTheInterpreter(@TempDir Path workDir) throws Exception {
		assertThat(runOn(Backend.INTERPRETER, workDir, crud(Backend.INTERPRETER)))
			.isEqualToNormalizingWhitespace(CRUD_EXPECTED);
	}

	@Test
	void crudOnJvm(@TempDir Path workDir) throws Exception {
		assertThat(runOn(Backend.JVM, workDir, crud(Backend.JVM))).isEqualToNormalizingWhitespace(CRUD_EXPECTED);
	}

	@Test
	void crudOnWasmComponent(@TempDir Path workDir) throws Exception {
		assertThat(runOn(Backend.COMPONENT, workDir, crud(Backend.COMPONENT)))
			.isEqualToNormalizingWhitespace(CRUD_EXPECTED);
	}

	@Test
	void unicodeTextOnTheInterpreter(@TempDir Path workDir) throws Exception {
		assertThat(runOn(Backend.INTERPRETER, workDir, unicodeText(Backend.INTERPRETER)))
			.isEqualToNormalizingWhitespace(UNICODE_EXPECTED);
	}

	@Test
	void unicodeTextOnJvm(@TempDir Path workDir) throws Exception {
		assertThat(runOn(Backend.JVM, workDir, unicodeText(Backend.JVM)))
			.isEqualToNormalizingWhitespace(UNICODE_EXPECTED);
	}

	@Test
	void unicodeTextOnWasmComponent(@TempDir Path workDir) throws Exception {
		assertThat(runOn(Backend.COMPONENT, workDir, unicodeText(Backend.COMPONENT)))
			.isEqualToNormalizingWhitespace(UNICODE_EXPECTED);
	}

	@Test
	void failsToCompileOnWasmPreview1(@TempDir Path workDir) throws Exception {
		// The documented fourth-backend gap: Preview 1 has no host socket API, so the
		// driver's tcp-connect is a compile error there -- a loud one naming the built-in
		// and the backends that do work, not a module that fails at run time.
		Path program = writeExercise(workDir, authLadder("127.0.0.1", 5432));
		Result result = run(workDir, JAVA, "-cp", CLASSPATH, "am.ik.rontolisp.cli.RontoLispCli",
				program.getFileName().toString(), "-o", "probe-p1.wasm");
		assertThat(result.exitCode()).as("%s", result).isNotZero();
		assertThat(result.err()).contains("TCP-CONNECT requires the interpreter, the JVM backend or --component");
		assertThat(workDir.resolve("probe-p1.wasm")).doesNotExist();
	}

	private static String authLadder(String host, int port) {
		return PROBE_PREAMBLE.formatted(DATABASE, host, port) + """
				(probe "trustuser" nil)
				(probe "passuser" "passpw")
				(probe "md5user" "md5pw")
				""";
	}

	private static String scram(String host, int port) {
		return PROBE_PREAMBLE.formatted(DATABASE, host, port) + """
				(probe "scramuser" "scrampw")
				""";
	}

	// A table per backend: the three legs run against the same database, and a shared
	// name would make them depend on each other's cleanup.
	private static Exercise crud(Backend backend) {
		String table = "items_" + backend.name().toLowerCase(Locale.ROOT);
		return (host, port) -> """
				(ql:quickload "cl-postgres")
				(let ((conn (cl-postgres:open-database "%s" "trustuser" nil "%s" %d)))
				  (cl-postgres:exec-query conn "drop table if exists %s")
				  (cl-postgres:exec-query conn "create table %s (id integer primary key, name text)")
				  (cl-postgres:exec-query conn "insert into %s (id, name) values (1, 'alpha'), (2, 'beta')")
				  (print (cl-postgres:exec-query conn "select id, name from %s order by id"
				                                 'cl-postgres:list-row-reader))
				  (cl-postgres:exec-query conn "update %s set name = 'gamma' where id = 2")
				  (print (cl-postgres:exec-query conn "select id, name from %s order by id"
				                                 'cl-postgres:list-row-reader))
				  (cl-postgres:exec-query conn "delete from %s where id = 1")
				  (print (cl-postgres:exec-query conn "select id, name from %s order by id"
				                                 'cl-postgres:list-row-reader))
				  (print (cl-postgres:exec-query conn "select count(*) from %s"
				                                 'cl-postgres:list-row-reader))
				  ;; The extended protocol: one parameterised statement, prepared once and
				  ;; run twice -- a hit and a miss.
				  (cl-postgres:prepare-query conn "byname" "select id from %s where name = $1")
				  (print (cl-postgres:exec-prepared conn "byname" (list "gamma")
				                                    'cl-postgres:list-row-reader))
				  (print (cl-postgres:exec-prepared conn "byname" (list "nope")
				                                    'cl-postgres:list-row-reader))
				  (cl-postgres:exec-query conn "drop table %s")
				  (cl-postgres:close-database conn))
				""".formatted(DATABASE, host, port, table, table, table, table, table, table, table, table, table,
				table, table);
	}

	// Both directions of the encoding, and both ways a string can reach the server: a
	// literal inside the query text (simple protocol) and a bound parameter (extended
	// protocol, the one the SQL_ASCII implementation corrupted). length() is the
	// server's own count, so it disagrees with the round trip if either side re-encoded.
	private static Exercise unicodeText(Backend backend) {
		String table = "kanji_" + backend.name().toLowerCase(Locale.ROOT);
		return (host, port) -> """
				(ql:quickload "cl-postgres")
				(let ((conn (cl-postgres:open-database "%s" "trustuser" nil "%s" %d)))
				  (cl-postgres:exec-query conn "drop table if exists %s")
				  (cl-postgres:exec-query conn "create table %s (id integer primary key, body text)")
				  (cl-postgres:exec-query conn "insert into %s values (1, 'こんにちは')")
				  (cl-postgres:prepare-query conn "add" "insert into %s values ($1, $2)")
				  (cl-postgres:exec-prepared conn "add" (list 2 "お茶🍵"))
				  (print (cl-postgres:exec-query conn "select id, body from %s order by id"
				                                 'cl-postgres:list-row-reader))
				  (print (cl-postgres:exec-query conn "select id, length(body) from %s order by id"
				                                 'cl-postgres:list-row-reader))
				  (cl-postgres:exec-query conn "drop table %s")
				  (cl-postgres:close-database conn))
				""".formatted(DATABASE, host, port, table, table, table, table, table, table, table);
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
				// gc for the value representation, exceptions because the driver uses
				// handler-case, tcp + inherit-network because wasmtime gates sockets by
				// permission (without them the socket calls fail and surface as nil).
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
		Path program = workDir.resolve("cl-postgres-probe.lisp");
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
