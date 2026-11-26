package am.ik.rontolisp.e2e;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import am.ik.rontolisp.testsupport.HostWasmtime;
import am.ik.rontolisp.testsupport.WasmtimeSupport;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
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
 * what a login costs on each backend now is in {@code .kb/asdf.md} ("Libraries that
 * load", the cl-postgres entry). The server's {@code authentication_timeout} is raised,
 * for the reason spelled out at {@link #POSTGRES};</li>
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
 * The same server answers a second driver: <b>the JDBC driver of
 * {@code jdbc-postgresql/}</b>, a Java library read as source, under two Java programs
 * (the four logins, CRUD, a prepared statement run twice, non-ASCII text; and the example
 * {@code examples/java/jdbc}) on the interpreter, the JVM, a {@code --native} executable
 * and the component, each against what the same program prints under the JDK running the
 * test, and under a third, the example {@code examples/java/shiori}, a web application
 * served on each of them -- the component under {@code wasmtime serve} -- and asked the
 * same requests the JDK serving it answered
 * ({@link #theJdbcProgramsAnswerOnEveryBackendWhatTheyAnswerUnderTheJdk};
 * {@code .kb/jdbc-postgresql.md}).
 *
 * <p>
 * WASM Preview 1 is the fourth backend and is deliberately absent from all three: TCP is
 * a CALL-time error there by design ({@code .kb/tcp-sockets.md} -- the module compiles,
 * per the spliced-dead-code policy, and the first socket call raises), which
 * {@link #preview1ModuleCompilesAndFailsLoudlyAtTheFirstSocketCall(Path)} pins so the gap
 * stays a checked statement rather than an untested claim.
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
 * This class runs by DEFAULT (unlike its {@code RONTOLISP_POSTGRES_E2E} siblings
 * {@code MitoE2eTest} / {@code PostmodernE2eTest}): the driver it exercises is the one
 * every higher layer sits on, and the defects it catches are shared-frontend ones rather
 * than library-specific ones -- the packed {@code concatenate} result type once broke 7
 * of its 13 legs while {@code ./mvnw test} stayed green. It needs Docker (the class is
 * skipped without it) and, on the first run, network access ({@code ql:quickload}
 * downloads cl-postgres and its seven dependencies into {@code ~/.rontolisp/quicklisp}).
 *
 * <pre>{@code
 * ./mvnw -Dtest=ClPostgresE2eTest -DfailIfNoTests=false test
 * }</pre>
 *
 * <p>
 * The four {@code --component} legs were {@code @Disabled} for a while: a socket write
 * died mid-message with {@code unknown handle index 0}, because the Gray-streams
 * fall-through arm re-spells {@code (write-sequence bytes socket)} with its
 * {@code :start} / {@code :end} keywords filled in and that shape fell out of the
 * component socket rewrite onto the NATIVE built-in. All four run now; see
 * {@code .kb/tcp-sockets.md}.
 *
 * <p>
 * The legs run CONCURRENTLY: each owns its {@code @TempDir}, its table (per backend) and
 * its module path in the wasmtime container (named after that directory). The one thing
 * they share is the quicklisp cache, and a cold one is filled by up to thirteen CLI
 * processes at once -- safe because {@code DistClient} installs a release by atomic
 * rename ({@code .kb/dists.md}).
 */
@Testcontainers(disabledWithoutDocker = true)
@Execution(ExecutionMode.CONCURRENT)
class ClPostgresE2eTest {

	/** The database the probes connect to (the image's default). */
	private static final String DATABASE = "postgres";

	/**
	 * The backends a driver reaches; Preview 1 cannot open a socket at all. cl-postgres
	 * has no leg on {@code NATIVE}: the JDBC driver's program alone runs there.
	 */
	private enum Backend {

		INTERPRETER, JVM, NATIVE, COMPONENT

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

	/**
	 * A hang guard, not a cost bound (.kb/test-execution.md, "No test reads a clock").
	 */
	private static final int TIMEOUT_MINUTES = 15;

	private static final String JAVA = Path.of(System.getProperty("java.home"), "bin", "java").toString();

	// The JDBC driver's module, a library on the source path of every leg, and the
	// program that asks it: the address in its URL is replaced by the server's.
	private static final Path JDBC_SOURCES = Path.of("jdbc-postgresql/src/main/java").toAbsolutePath();

	private static final Path JDBC_RESOURCES = Path.of("jdbc-postgresql/src/main/resources").toAbsolutePath();

	// What "done" asks of a driver: the four logins, CRUD, a prepared statement run
	// twice,
	// non-ASCII text.
	private static final String LADDER = """
			import java.sql.Connection;
			import java.sql.DriverManager;
			import java.sql.PreparedStatement;
			import java.sql.ResultSet;
			import java.sql.SQLException;
			import java.sql.Statement;

			public class Ladder {

			    static final String URL = "jdbc:postgresql://127.0.0.1:55432/postgres";

			    static void probe(String user, String password) throws SQLException {
			        try (Connection c = DriverManager.getConnection(URL, user, password);
			                Statement s = c.createStatement();
			                ResultSet r = s.executeQuery("select current_user, 42, 'hello'")) {
			            while (r.next()) {
			                System.out.println(r.getString(1) + " " + r.getInt(2) + " " + r.getString(3));
			            }
			        }
			    }

			    static void rows(Statement s, String table) throws SQLException {
			        try (ResultSet r = s.executeQuery("select id, name from " + table + " order by id")) {
			            StringBuilder line = new StringBuilder();
			            while (r.next()) {
			                line.append('(').append(r.getInt(1)).append(' ').append(r.getString(2)).append(')');
			            }
			            System.out.println(line);
			        }
			    }

			    public static void main(String[] args) throws Exception {
			        probe("trustuser", null);
			        probe("passuser", "passpw");
			        probe("md5user", "md5pw");
			        probe("scramuser", "scrampw");
			        String table = args.length > 0 ? args[0] : "items_jdk";
			        try (Connection c = DriverManager.getConnection(URL, "trustuser", null); Statement s = c.createStatement()) {
			            s.execute("drop table if exists " + table);
			            s.execute("create table " + table + " (id integer primary key, name text)");
			            System.out.println(s.executeUpdate("insert into " + table + " (id, name) values (1, 'alpha'), (2, 'beta')"));
			            rows(s, table);
			            System.out.println(s.executeUpdate("update " + table + " set name = 'gamma' where id = 2"));
			            rows(s, table);
			            System.out.println(s.executeUpdate("delete from " + table + " where id = 1"));
			            rows(s, table);
			            try (PreparedStatement p = c.prepareStatement("select id from " + table + " where name = ?")) {
			                for (String name : new String[] { "gamma", "nope" }) {
			                    p.setString(1, name);
			                    try (ResultSet r = p.executeQuery()) {
			                        System.out.println(r.next() ? "hit " + r.getInt(1) : "miss");
			                    }
			                }
			            }
			            try (PreparedStatement p = c.prepareStatement("insert into " + table + " values (?, ?)")) {
			                p.setInt(1, 3);
			                p.setString(2, "お茶🍵");
			                System.out.println(p.executeUpdate());
			            }
			            s.executeUpdate("insert into " + table + " values (4, 'こんにちは')");
			            rows(s, table);
			            try (ResultSet r = s.executeQuery("select id, length(name) from " + table + " order by id")) {
			                while (r.next()) {
			                    System.out.println(r.getInt(1) + " " + r.getInt(2));
			                }
			            }
			            s.execute("drop table " + table);
			        }
			    }
			}
			""";

	private static final String LADDER_ADDRESS = "127.0.0.1:55432";

	/** The table the ladder program writes, which each leg replaces by one of its own. */
	private static final String LADDER_TABLE = "\"items_jdk\"";

	// What the JDK printed with pgjdbc's own jar, when pgjdbc was measured.
	private static final String LADDER_EXPECTED = """
			trustuser 42 hello
			passuser 42 hello
			md5user 42 hello
			scramuser 42 hello
			2
			(1 alpha)(2 beta)
			1
			(1 alpha)(2 gamma)
			1
			(2 gamma)
			hit 2
			miss
			1
			(2 gamma)(3 お茶🍵)(4 こんにちは)
			2 5
			3 3
			4 5
			""";

	// The example: the server's address from DATABASE_URL, its table temporary.
	private static final Path NOTES = Path.of("examples/java/jdbc/src/main/java/jdbc/Notes.java").toAbsolutePath();

	// What the example's README shows it printing.
	private static final Path NOTES_EXPECTED = Path.of("examples/.expected/java-jdbc-notes.txt");

	// The web application, a Maven project: its templates and assets resources of its
	// src/main/resources, the modules it runs on read as source and the libraries by
	// coordinates, as its pom names them.
	private static final ExampleProject SHIORI_PROJECT = ExampleProject.read(Path.of("examples/java/shiori"));

	private static final Path SHIORI = SHIORI_PROJECT.sources().resolve("com/example/shiori/Shiori.java");

	/**
	 * One request to the shiori: a form posted where {@code form} is given, its digest
	 * shown in place of the body where {@code digest} says.
	 */
	private record Asked(String name, String method, String path, @Nullable Map<String, String> form,
			Map<String, String> headers, boolean digest) {

		static Asked get(String name, String path) {
			return new Asked(name, "GET", path, null, Map.of(), false);
		}

		static Asked post(String name, String path, Map<String, String> form) {
			return new Asked(name, "POST", path, form, Map.of(), false);
		}

		Asked with(String field, String value) {
			Map<String, String> more = new java.util.LinkedHashMap<>(this.headers);
			more.put(field, value);
			return new Asked(this.name, this.method, this.path, this.form, more, this.digest);
		}

	}

	private static final String HTMX = "HX-Request";

	// What a book form sends, the fields given over the empty ones.
	private static Map<String, String> bookForm(String... fields) {
		Map<String, String> form = new java.util.LinkedHashMap<>();
		for (String name : List.of("title", "author", "pages", "current_page", "status", "rating", "started_on",
				"finished_on", "tags", "summary")) {
			form.put(name, "");
		}
		for (int i = 0; i < fields.length; i += 2) {
			form.put(fields[i], fields[i + 1]);
		}
		return form;
	}

	// Every route and what it refuses, in an order whose answers do not depend on the
	// time a leg takes: a page, a fragment for htmx, a form refused and one taken, the
	// flash message the redirect leaves, the changes made in place, the 404s and the 403.
	private static final List<Asked> SHIORI_REQUESTS = List.of(Asked.get("dashboard", "/"),
			Asked.get("library", "/books"), Asked.get("search-sorted", "/books?q=lisp&sort=title"),
			Asked.get("status-and-tag",
					"/books?status=finished&tag=" + java.net.URLEncoder.encode("日本文学", StandardCharsets.UTF_8)),
			Asked.get("unknown-filters", "/books?status=lost&sort=nope&page=x"),
			Asked.get("shelf-for-htmx", "/books?q=" + java.net.URLEncoder.encode("夏目", StandardCharsets.UTF_8))
				.with(HTMX, "true")
				.with("HX-Target", "div#shelf"),
			Asked.get("book", "/books/1"), Asked.get("new-form", "/books/new"),
			Asked.post("refused-form", "/books",
					bookForm("title", " ", "author", "x", "pages", "abc", "current_page", "5", "status", "want",
							"started_on", "2026-02-30")),
			Asked.post("created", "/books",
					bookForm("title", "試しの本", "author", "著者", "pages", "120", "current_page", "0", "status", "want",
							"tags", "小説, 試し", "summary", "メモ")),
			Asked.get("flash-shown", "/books/19"),
			Asked.post("progress-htmx", "/books/19/progress", Map.of("current_page", "50")).with(HTMX, "true"),
			Asked.post("progress-refused", "/books/19/progress", Map.of("current_page", "999")).with(HTMX, "true"),
			Asked.post("finished-htmx", "/books/19/progress", Map.of("current_page", "50", "finished", "yes"))
				.with(HTMX, "true"),
			Asked.post("rating-htmx", "/books/19/rating", Map.of("rating", "4")).with(HTMX, "true"),
			Asked.post("rating-refused", "/books/19/rating", Map.of("rating", "9")).with(HTMX, "true"),
			Asked.post("note-htmx", "/books/19/notes", Map.of("kind", "quote", "page", "3", "body", "書き出しの一文。"))
				.with(HTMX, "true"),
			Asked.post("note-plain", "/books/19/notes", Map.of("kind", "note", "page", "", "body", "A note.")),
			Asked.post("note-refused", "/books/19/notes", Map.of("kind", "note", "page", "0", "body", "x"))
				.with(HTMX, "true"),
			Asked.get("book-after", "/books/19"),
			new Asked("note-deleted", "POST", "/notes/15/delete", null, Map.of(HTMX, "true"), false),
			Asked.get("edit-form", "/books/19/edit"),
			Asked.post("updated", "/books/19",
					bookForm("title", "試しの本（改）", "author", "著者", "pages", "120", "current_page", "60", "status",
							"reading", "rating", "5", "started_on", "2026-11-01", "tags", "試し")),
			Asked.post("update-refused", "/books/19",
					bookForm("title", "x", "author", "y", "pages", "10", "current_page", "60", "status", "reading")),
			Asked.post("deleted", "/books/19/delete", Map.of()), Asked.get("library-after", "/books"),
			Asked.get("missing-book", "/books/999"), Asked.get("no-number", "/books/abc"),
			Asked.get("no-page", "/nope"),
			Asked.post("cross-site", "/books/1/rating", Map.of("rating", "1")).with("Sec-Fetch-Site", "cross-site"),
			new Asked("stylesheet", "GET", "/static/app.css", null, Map.of(), true),
			new Asked("script", "GET", "/static/app.js", null, Map.of(), true),
			new Asked("favicon", "GET", "/favicon.svg", null, Map.of(), true));

	// The statuses a JDK answers them with: what keeps the oracle itself honest.
	private static final List<Integer> SHIORI_STATUSES = List.of(200, 200, 200, 200, 200, 200, 200, 200, 422, 303, 200,
			200, 422, 200, 200, 422, 200, 303, 422, 200, 200, 200, 303, 422, 303, 200, 404, 404, 404, 403, 200, 200,
			200);

	// The response fields the application sets; the rest is the transport's.
	private static final List<String> SHIORI_FIELDS = List.of("cache-control", "content-security-policy",
			"content-type", "hx-location", "hx-reswap", "hx-retarget", "hx-trigger", "location", "referrer-policy",
			"set-cookie", "x-content-type-options");

	// What the JDK running the test prints with the driver's module, once for the legs.
	private static @Nullable UnderTheJdk underTheJdk;

	private record UnderTheJdk(String ladder, String notes, List<String> shiori) {
	}

	/**
	 * A build that must carry the host's runner fails the native leg instead of skipping.
	 */
	private static final boolean NATIVE_REQUIRED = Boolean.getBoolean("rontolisp.native.required");

	// The classes this test was compiled against, so the CLI subprocess needs no jar.
	private static final String CLASSPATH = System.getProperty("java.class.path");

	private static final Network NETWORK = Network.newNetwork();

	// The component's host: a NAME, so that leg resolves it through
	// wasi:sockets/ip-name-lookup (Docker's network DNS) as a driver given a host name
	// does.
	private static final String POSTGRES_ALIAS = "postgres-db";

	@Container
	private static final GenericContainer<?> POSTGRES = new GenericContainer<>(
			DockerImageName.parse("postgres:17-alpine"))
		.withNetwork(NETWORK)
		.withNetworkAliases(POSTGRES_ALIAS)
		.withExposedPorts(5432)
		.withEnv("POSTGRES_PASSWORD", "postgres")
		.withCopyToContainer(Transferable.of(HBA_CONF), "/etc/postgresql/pg_hba.conf")
		.withCopyToContainer(Transferable.of(INIT_SQL), "/docker-entrypoint-initdb.d/10-auth-ladder.sql")
		// hba_file rather than an edit of the generated one: the entrypoint passes these
		// args to the bootstrap server too, so the ladder is in force from the start.
		//
		// authentication_timeout is RAISED (when this class stopped being
		// opt-in). A leg that outruns it dies as "Database error: end of file" while the
		// server logs "canceling authentication due to timeout", and the slowest leg --
		// then the interpreter's 4096-round PBKDF2 -- took ~50 s of the DEFAULT 60 s with
		// the machine to itself, 63 s inside a full ./mvnw test (JUnit runs classes
		// 16-wide): the default turned this leg into a stopwatch on ambient load rather
		// than an assertion about SCRAM. What the leg tests is that SCRAM authentication
		// SUCCEEDS; the cost claim lives in .kb/asdf.md, where a regression is a
		// documentation change rather than a flake.
		.withCommand("postgres", "-c", "hba_file=/etc/postgresql/pg_hba.conf", "-c", "authentication_timeout=600")
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

	@ParameterizedTest
	@EnumSource(Backend.class)
	void theJdbcProgramsAnswerOnEveryBackendWhatTheyAnswerUnderTheJdk(Backend backend, @TempDir Path workDir)
			throws Exception {
		UnderTheJdk expected = underTheJdk();
		assertThat(expected.ladder()).isEqualTo(LADDER_EXPECTED);
		assertThat(expected.notes()).isEqualTo(Files.readString(NOTES_EXPECTED, StandardCharsets.UTF_8));
		String address = backend == Backend.COMPONENT ? POSTGRES_ALIAS + ":5432" : hostAddress();
		// A table a leg: they run at once against one database. Written into the source,
		// since a program of the subset is handed no arguments. The example's table is
		// temporary, its session's own.
		String table = "ladder_" + backend.name().toLowerCase(Locale.ROOT);
		Files.writeString(workDir.resolve("Ladder.java"), ladder(address, table), StandardCharsets.UTF_8);
		assertThat(runJava(backend, workDir, "Ladder.java", "Ladder", Map.of())).isEqualTo(expected.ladder());
		assertThat(runJava(backend, workDir, NOTES.toString(), "Notes", databaseUrl(address)))
			.isEqualTo(expected.notes());
		assertThat(statuses(expected.shiori())).isEqualTo(SHIORI_STATUSES);
		assertThat(served(backend, workDir)).isEqualTo(expected.shiori());
	}

	/**
	 * Compiles a program of the Java subset with the driver on its source path (where the
	 * backend needs it) and runs it, returning stdout.
	 */
	private static String runJava(Backend backend, Path workDir, String source, String name, Map<String, String> env,
			String... args) throws Exception {
		String sourcePath = JDBC_SOURCES + java.io.File.pathSeparator + JDBC_RESOURCES;
		String executable = name.toLowerCase(Locale.ROOT);
		return switch (backend) {
			case INTERPRETER ->
				runSuccessfully(workDir, env, concat(cli("--java-source-path", sourcePath, source, "--"), args));
			case JVM -> {
				runCli(workDir, "--java-source-path", sourcePath, source, "-o", name + ".class");
				yield runSuccessfully(workDir, env,
						concat(new String[] { JAVA, "-cp", workDir.toString(), name }, args));
			}
			case NATIVE -> {
				Result compiled = run(workDir, Map.of(),
						cli("--java-source-path", sourcePath, source, "-o", executable, "--native"));
				// The compiler carries the runner of the host it was built on, or none.
				Assumptions.assumeFalse(
						!NATIVE_REQUIRED && compiled.exitCode() != 0
								&& compiled.err().contains("--native is not available for "),
						() -> compiled.err().strip());
				assertThat(compiled.exitCode()).as("%s", compiled).isZero();
				yield runSuccessfully(workDir, env,
						concat(new String[] { workDir.resolve(executable).toString() }, args));
			}
			case COMPONENT -> {
				runCli(workDir, "--java-source-path", sourcePath, source, "-o", executable + ".wasm", "--component");
				String path = "/tmp/" + workDir.getFileName() + "." + executable + ".wasm";
				WASMTIME.copyFileToContainer(Transferable.of(Files.readAllBytes(workDir.resolve(executable + ".wasm"))),
						path);
				List<String> command = new ArrayList<>(List.of("wasmtime", "run", "-S", "tcp=y", "-S",
						"inherit-network=y", "-S", "allow-ip-name-lookup=y"));
				env.forEach((key, value) -> command.addAll(List.of("--env", key + "=" + value)));
				command.add(path);
				command.addAll(List.of(args));
				ExecResult result = WASMTIME.execInContainer(command.toArray(String[]::new));
				assertThat(result.getExitCode()).as("stderr: %s", result.getStderr()).isZero();
				yield result.getStdout();
			}
		};
	}

	private static String hostAddress() {
		return POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432);
	}

	// The form examples/db/ reads, which the example turns into a JDBC URL and
	// properties.
	private static Map<String, String> databaseUrl(String address) {
		return Map.of("DATABASE_URL", databaseUrl(address, DATABASE));
	}

	private static String databaseUrl(String address, String database) {
		return "postgresql://trustuser@" + address + "/" + database;
	}

	private static String[] concat(String[] head, String... tail) {
		List<String> all = new ArrayList<>(List.of(head));
		all.addAll(List.of(tail));
		return all.toArray(String[]::new);
	}

	private static String ladder(String address, String table) throws Exception {
		String source = LADDER;
		assertThat(source).contains(LADDER_ADDRESS, LADDER_TABLE);
		return source.replace(LADDER_ADDRESS, address).replace(LADDER_TABLE, "\"" + table + "\"");
	}

	// The oracle: the driver's module compiled by javac beside the two programs, run by
	// the JDK running the test, which finds the driver through the module's services
	// file.
	private static synchronized UnderTheJdk underTheJdk() throws Exception {
		UnderTheJdk printed = underTheJdk;
		if (printed == null) {
			Path dir = Files.createTempDirectory("jdbc-jdk");
			Files.writeString(dir.resolve("Ladder.java"), ladder(hostAddress(), "items_jdk"), StandardCharsets.UTF_8);
			am.ik.maven.MavenResolver maven = ExamplesE2eTest.mavenResolver();
			// what the driver compiles against and does not bring: javac's alone
			List<String> driverProvided = new ArrayList<>();
			for (String coordinate : ExampleProject.provided(JDBC_SOURCES)) {
				driverProvided.add(maven.artifact(am.ik.maven.Artifact.parse(coordinate)).toString());
			}
			List<String> javac = new ArrayList<>(
					List.of(Path.of(System.getProperty("java.home"), "bin", "javac").toString(), "-d", "classes", "-cp",
							String.join(java.io.File.pathSeparator, driverProvided), "Ladder.java", NOTES.toString()));
			try (Stream<Path> files = Files.walk(JDBC_SOURCES)) {
				files.filter(file -> file.toString().endsWith(".java"))
					.sorted()
					.forEach(file -> javac.add(file.toString()));
			}
			runSuccessfully(dir, javac.toArray(String[]::new));
			String classPath = "classes" + java.io.File.pathSeparator + JDBC_RESOURCES;
			// the web application: javac over it and the modules it runs on, against the
			// libraries' jars; its templates and assets from its resources
			List<String> jars = new ArrayList<>();
			for (String coordinate : SHIORI_PROJECT.coordinates()) {
				jars.add(maven.artifact(am.ik.maven.Artifact.parse(coordinate)).toString());
			}
			// what the modules compile against and do not bring: javac's alone
			List<String> compiledWith = new ArrayList<>(jars);
			for (String coordinate : SHIORI_PROJECT.provided()) {
				compiledWith.add(maven.artifact(am.ik.maven.Artifact.parse(coordinate)).toString());
			}
			List<String> shioriJavac = new ArrayList<>(
					List.of(Path.of(System.getProperty("java.home"), "bin", "javac").toString(), "-d", "shiori-classes",
							"-cp", String.join(java.io.File.pathSeparator, compiledWith)));
			for (Path root : Stream.concat(Stream.of(SHIORI_PROJECT.sources()), SHIORI_PROJECT.sourcePath().stream())
				.toList()) {
				try (Stream<Path> files = Files.walk(root)) {
					files.filter(file -> file.toString().endsWith(".java"))
						.sorted()
						.forEach(file -> shioriJavac.add(file.toString()));
				}
			}
			runSuccessfully(dir, shioriJavac.toArray(String[]::new));
			List<String> shioriClassPath = new ArrayList<>(List.of("shiori-classes"));
			SHIORI_PROJECT.sourcePath().forEach(root -> shioriClassPath.add(root.toString()));
			shioriClassPath.addAll(jars);
			printed = new UnderTheJdk(runSuccessfully(dir, JAVA, "-cp", classPath, "Ladder"),
					runSuccessfully(dir, databaseUrl(hostAddress()), JAVA, "-cp", classPath, "jdbc.Notes"),
					serve(dir, "jdk", List.of(JAVA, "-cp", String.join(java.io.File.pathSeparator, shioriClassPath),
							"com.example.shiori.Shiori")));
			underTheJdk = printed;
		}
		return printed;
	}

	/**
	 * The web application compiled for a backend, served against a database of the leg's
	 * own and asked the requests: on the component's leg by {@code wasmtime serve} on
	 * this host, which reaches the server's mapped port as the other legs do.
	 */
	private static List<String> served(Backend backend, Path workDir) throws Exception {
		List<String> options = SHIORI_PROJECT.options();
		String leg = backend.name().toLowerCase(Locale.ROOT);
		List<String> command = switch (backend) {
			case INTERPRETER -> List.of(cli(concat(options.toArray(String[]::new), SHIORI.toString())));
			case JVM -> {
				runCli(workDir, concat(options.toArray(String[]::new), SHIORI.toString(), "-o", "Shiori.class"));
				yield List.of(JAVA, "-cp", workDir.toString(), "Shiori");
			}
			case NATIVE -> {
				Result compiled = run(workDir, Map.of(),
						cli(concat(options.toArray(String[]::new), SHIORI.toString(), "-o", "shiori", "--native")));
				Assumptions.assumeFalse(
						!NATIVE_REQUIRED && compiled.exitCode() != 0
								&& compiled.err().contains("--native is not available for "),
						() -> compiled.err().strip());
				assertThat(compiled.exitCode()).as("%s", compiled).isZero();
				yield List.of(workDir.resolve("shiori").toString());
			}
			case COMPONENT -> {
				Assumptions.assumeTrue(HostWasmtime.isAvailable(), "no usable wasmtime on PATH");
				runCli(workDir,
						concat(options.toArray(String[]::new), SHIORI.toString(), "-o", "shiori.wasm", "--component"));
				// sockets need the command world's interfaces in a served component; the
				// host's address may be a name
				yield List.of("wasmtime", "serve", "--addr", "127.0.0.1:0", "-S", "cli=y", "-S", "inherit-network=y",
						"-S", "allow-ip-name-lookup=y", "--env",
						"DATABASE_URL=" + databaseUrl(hostAddress(), "shiori_" + leg), "shiori.wasm");
			}
		};
		return serve(workDir, leg, command);
	}

	/**
	 * Starts the web application on port 0 against the database {@code shiori_<leg>},
	 * made for it, asks it the requests in turn and ends it.
	 */
	private static List<String> serve(Path dir, String leg, List<String> command) throws Exception {
		String database = "shiori_" + leg;
		ExecResult made = POSTGRES.execInContainer("psql", "-U", "postgres", "-c",
				"create database " + database + " owner trustuser");
		assertThat(made.getExitCode()).as("%s", made.getStderr()).isZero();
		Path out = dir.resolve("shiori.out");
		Path err = dir.resolve("shiori.err");
		ProcessBuilder builder = new ProcessBuilder(command).directory(dir.toFile())
			.redirectOutput(out.toFile())
			.redirectError(err.toFile());
		builder.environment().put("PORT", "0");
		builder.environment().put("DATABASE_URL", databaseUrl(hostAddress(), database));
		Process process = builder.start();
		try {
			java.util.regex.Pattern said = java.util.regex.Pattern
				.compile("(?:Listening on http://localhost:|Serving HTTP on http://127\\.0\\.0\\.1:)(\\d+)/");
			long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(TIMEOUT_MINUTES);
			while (true) {
				java.util.regex.Matcher port = said.matcher(
						Files.readString(out, StandardCharsets.UTF_8) + Files.readString(err, StandardCharsets.UTF_8));
				if (port.find()) {
					return asked(Integer.parseInt(port.group(1)));
				}
				if (!process.isAlive() || System.nanoTime() > deadline) {
					throw new AssertionError("the shiori never said where it listens on " + leg + "\n--- stdout ---\n"
							+ Files.readString(out) + "--- stderr ---\n" + Files.readString(err));
				}
				Thread.sleep(100);
			}
		}
		finally {
			process.destroyForcibly().waitFor();
		}
	}

	/**
	 * Each answer to the requests in turn: the status, the fields the application sets,
	 * the body (a digest for an asset). The cookies are kept as a browser keeps them.
	 */
	private static List<String> asked(int port) throws Exception {
		Map<String, String> cookies = new java.util.LinkedHashMap<>();
		List<String> answers = new ArrayList<>();
		try (java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
			.version(java.net.http.HttpClient.Version.HTTP_1_1)
			.build()) {
			for (Asked asked : SHIORI_REQUESTS) {
				java.net.http.HttpRequest.Builder request = java.net.http.HttpRequest
					.newBuilder(java.net.URI.create("http://127.0.0.1:" + port + asked.path()))
					.timeout(java.time.Duration.ofMinutes(TIMEOUT_MINUTES));
				asked.headers().forEach(request::header);
				if (!cookies.isEmpty()) {
					request.header("Cookie",
							String.join("; ",
									cookies.entrySet()
										.stream()
										.map(cookie -> cookie.getKey() + "=" + cookie.getValue())
										.toList()));
				}
				if (asked.form() != null) {
					List<String> pairs = new ArrayList<>();
					asked.form()
						.forEach((name, value) -> pairs.add(java.net.URLEncoder.encode(name, StandardCharsets.UTF_8)
								+ "=" + java.net.URLEncoder.encode(value, StandardCharsets.UTF_8)));
					request.header("Content-Type", "application/x-www-form-urlencoded")
						.method(asked.method(),
								java.net.http.HttpRequest.BodyPublishers.ofString(String.join("&", pairs)));
				}
				else {
					request.method(asked.method(), java.net.http.HttpRequest.BodyPublishers.noBody());
				}
				java.net.http.HttpResponse<byte[]> response = client.send(request.build(),
						java.net.http.HttpResponse.BodyHandlers.ofByteArray());
				StringBuilder answer = new StringBuilder("== " + asked.name() + "\n" + response.statusCode() + "\n");
				for (String field : SHIORI_FIELDS) {
					for (String value : response.headers().allValues(field)) {
						answer.append(field).append(": ").append(value).append('\n');
						if (field.equals("set-cookie")) {
							String pair = value.substring(0,
									value.indexOf(';') < 0 ? value.length() : value.indexOf(';'));
							String name = pair.substring(0, pair.indexOf('='));
							String kept = pair.substring(pair.indexOf('=') + 1);
							if (kept.isEmpty()) {
								cookies.remove(name);
							}
							else {
								cookies.put(name, kept);
							}
						}
					}
				}
				answer
					.append(asked.digest()
							? java.util.HexFormat.of()
								.formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(response.body()))
									+ " " + response.body().length
							: new String(response.body(), StandardCharsets.UTF_8));
				answers.add(answer.toString());
			}
		}
		return answers;
	}

	private static List<Integer> statuses(List<String> answers) {
		return answers.stream().map(answer -> Integer.parseInt(answer.split("\n")[1])).toList();
	}

	// The JDBC program whose driver is jdbc-spin-postgres: its connections are the
	// host's.
	private static final ExampleProject SPIN_NOTES_PROJECT = ExampleProject
		.read(Path.of("examples/java/spin-postgres"));

	// The binary -Drontolisp.spin names, else the one on PATH.
	private static final String SPIN = System.getProperty("rontolisp.spin", "spin");

	/**
	 * The notes of {@code examples/java/spin-postgres} -- plain JDBC, the driver
	 * {@code jdbc-spin-postgres} -- compiled to a component and served by Spin, whose
	 * {@code spin:postgres/postgres} the driver runs every statement on: a SCRAM login
	 * the host makes, rows written with their keys returned, a transaction rolled back, a
	 * refusal's SQLSTATE read out of the host's text, a query with a parameter. No other
	 * host implements the interface, so the leg runs where a Spin 4.1 or later is named
	 * ({@code -Drontolisp.spin}) or on PATH, and is skipped elsewhere.
	 */
	@Test
	void theJdbcProgramAnswersThroughSpinsConnection(@TempDir Path workDir) throws Exception {
		Assumptions.assumeTrue(spinServesTheInterface(workDir), "no Spin 4.1 or later: " + SPIN);
		Files.createDirectories(workDir.resolve("target"));
		runCli(workDir,
				concat(SPIN_NOTES_PROJECT.options().toArray(String[]::new),
						SPIN_NOTES_PROJECT.sources().resolve("com/example/notes/App.java").toString(), "-o",
						"target/spin-postgres.wasm", "--component"));
		Files.copy(Path.of("examples/java/spin-postgres/spin.toml").toAbsolutePath(), workDir.resolve("spin.toml"));
		ExecResult made = POSTGRES.execInContainer("psql", "-U", "postgres", "-c",
				"create database spin_notes owner scramuser");
		assertThat(made.getExitCode()).as("%s", made.getStderr()).isZero();
		int port;
		try (java.net.ServerSocket free = new java.net.ServerSocket(0)) {
			port = free.getLocalPort();
		}
		Path log = workDir.resolve("spin.log");
		ProcessBuilder builder = new ProcessBuilder(SPIN, "up", "--listen", "127.0.0.1:" + port, "--env",
				"DATABASE_URL=jdbc:postgresql://" + hostAddress()
						+ "/spin_notes?user=scramuser&password=scrampw&sslmode=disable")
			.directory(workDir.toFile())
			.redirectErrorStream(true)
			.redirectOutput(log.toFile());
		// The one server the manifest lets the component reach.
		builder.environment().put("SPIN_VARIABLE_DB_HOST", hostAddress());
		Process spin = builder.start();
		try (java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
			.version(java.net.http.HttpClient.Version.HTTP_1_1)
			.build()) {
			java.net.URI notes = java.net.URI.create("http://127.0.0.1:" + port + "/");
			long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(TIMEOUT_MINUTES);
			while (true) {
				try {
					if (spinAsked(client, notes, null).startsWith("200\n")) {
						break;
					}
				}
				catch (java.io.IOException ex) {
					// not listening yet
				}
				if (!spin.isAlive() || System.nanoTime() > deadline) {
					throw new AssertionError("Spin never served the notes\n" + Files.readString(log));
				}
				Thread.sleep(200);
			}
			String refused = "SQLSTATE 23514: ERROR: new row for relation \"note\" violates check constraint"
					+ " \"note_title_check\"\n  Detail: Failing row contains (%d, , DATE).\n";
			assertThat(spinAsked(client, notes, "title=Buy+milk")).isEqualTo("201\nadded 1: Buy milk\n");
			assertThat(
					spinAsked(client, notes, "title=" + java.net.URLEncoder.encode("お茶を淹れる🍵", StandardCharsets.UTF_8)))
				.isEqualTo("201\nadded 2: お茶を淹れる🍵\n");
			assertThat(spinAsked(client, notes, "title=")).isEqualTo("400\n" + refused.formatted(3));
			// Two titles are one transaction: the first is not kept when the second is
			// refused.
			assertThat(spinAsked(client, notes, "title=first&title=")).isEqualTo("400\n" + refused.formatted(5));
			assertThat(spinAsked(client, notes, null))
				.isEqualTo("200\nPostgreSQL 17 in the clear\n1  DATE  Buy milk\n2  DATE  お茶を淹れる🍵\n");
			assertThat(spinAsked(client, java.net.URI.create(notes + "?q=MILK"), null))
				.isEqualTo("200\nPostgreSQL 17 in the clear\n1  DATE  Buy milk\n");
		}
		finally {
			// spin up runs the trigger as a child
			spin.descendants().forEach(ProcessHandle::destroyForcibly);
			spin.destroyForcibly().waitFor();
		}
	}

	// A request's status and body, the day and the server's minor version written out of
	// it; a form is posted, nothing is a GET.
	private static String spinAsked(java.net.http.HttpClient client, java.net.URI uri, @Nullable String form)
			throws Exception {
		java.net.http.HttpRequest.Builder request = java.net.http.HttpRequest.newBuilder(uri)
			.timeout(java.time.Duration.ofMinutes(TIMEOUT_MINUTES));
		if (form != null) {
			request.header("Content-Type", "application/x-www-form-urlencoded")
				.POST(java.net.http.HttpRequest.BodyPublishers.ofString(form));
		}
		java.net.http.HttpResponse<String> response = client.send(request.build(),
				java.net.http.HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		return response.statusCode() + "\n"
				+ response.body()
					.replaceAll("\\d{4}-\\d{2}-\\d{2}", "DATE")
					.replaceAll("PostgreSQL 17\\.\\d+", "PostgreSQL 17");
	}

	// spin:postgres/postgres@4.2.0 and a wasmtime that runs wasi:http@0.3.0 came with
	// Spin 4.1.0.
	private static boolean spinServesTheInterface(Path workDir) {
		try {
			Result version = run(workDir, Map.of(), SPIN, "--version");
			java.util.regex.Matcher numbers = java.util.regex.Pattern.compile("^spin (\\d+)\\.(\\d+)")
				.matcher(version.out());
			return version.exitCode() == 0 && numbers.find() && (Integer.parseInt(numbers.group(1)) > 4
					|| Integer.parseInt(numbers.group(1)) == 4 && Integer.parseInt(numbers.group(2)) >= 1);
		}
		catch (Exception ex) {
			return false;
		}
	}

	// A JDBC program a host calls for each request (--no-wasi): a connection is made,
	// used and closed within its request, as a Cloudflare Worker's must be, the server's
	// address the request's -- a reactor has no environment.
	private static final String REACTOR_PROGRAM = """
			import com.sun.net.httpserver.HttpServer;
			import java.io.OutputStream;
			import java.net.InetSocketAddress;
			import java.nio.charset.StandardCharsets;
			import java.sql.Connection;
			import java.sql.DriverManager;
			import java.sql.PreparedStatement;
			import java.sql.ResultSet;
			import java.sql.Statement;

			public class Tally {

			    // a connection one request made and the next uses
			    static Connection kept;

			    static String kept(String url) {
			        try {
			            boolean made = kept == null;
			            if (made) {
			                kept = DriverManager.getConnection(url);
			            }
			            try (Statement statement = kept.createStatement();
			                    ResultSet row = statement.executeQuery("select 42")) {
			                row.next();
			                return (made ? "made " : "kept ") + row.getInt(1) + "\\n";
			            }
			        }
			        catch (java.sql.SQLException e) {
			            kept = null;
			            return e.getClass().getName() + " " + e.getSQLState() + " " + e.getCause() + "\\n";
			        }
			    }

			    static String rows(Connection connection, String url) throws Exception {
			        StringBuilder said = new StringBuilder();
			        try (Statement statement = connection.createStatement()) {
			            try (ResultSet row = statement.executeQuery("select current_user, 42, 'hello'")) {
			                row.next();
			                said.append(row.getString(1)).append(' ').append(row.getInt(2)).append(' ')
			                    .append(row.getString(3)).append('\\n');
			            }
			            if (url.contains("trustuser")) {
			                statement.execute("drop table if exists reactor_tally");
			                statement.execute("create table reactor_tally (id serial primary key, note text)");
			                try (PreparedStatement insert = connection
			                    .prepareStatement("insert into reactor_tally (note) values (?)")) {
			                    for (String note : new String[] { "お茶", "tea 🍵" }) {
			                        insert.setString(1, note);
			                        insert.executeUpdate();
			                    }
			                }
			                try (ResultSet row = statement
			                    .executeQuery("select id, note, char_length(note) from reactor_tally order by id")) {
			                    while (row.next()) {
			                        said.append(row.getInt(1)).append(' ').append(row.getString(2)).append(' ')
			                            .append(row.getInt(3)).append('\\n');
			                    }
			                }
			            }
			            // more rows than one read of the socket takes
			            try (ResultSet row = statement
			                .executeQuery("select count(*), sum(length(t)) from (select repeat('x', n) as t"
			                        + " from generate_series(1, 300) as n) as rows")) {
			                row.next();
			                said.append(row.getLong(1)).append(' ').append(row.getLong(2)).append('\\n');
			            }
			            long total = 0;
			            try (ResultSet row = statement
			                .executeQuery("select repeat('y', 500) from generate_series(1, 400)")) {
			                while (row.next()) {
			                    total += row.getString(1).length();
			                }
			            }
			            said.append(total).append('\\n');
			        }
			        return said.toString();
			    }

			    public static void main(String[] args) throws Exception {
			        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			        server.createContext("/", exchange -> {
			            String url = exchange.getRequestHeaders().getFirst("X-Database-Url");
			            String said;
			            if (exchange.getRequestURI().getPath().equals("/kept")) {
			                said = kept(url);
			            }
			            else try (Connection connection = DriverManager.getConnection(url)) {
			                said = rows(connection, url);
			            }
			            catch (Exception e) {
			                said = e + "\\n";
			            }
			            byte[] body = said.getBytes(StandardCharsets.UTF_8);
			            exchange.sendResponseHeaders(200, body.length);
			            try (OutputStream out = exchange.getResponseBody()) {
			                out.write(body);
			            }
			        });
			        server.start();
			        System.out.println("listening " + server.getAddress().getPort());
			    }

			}
			""";

	// The reactor's host: node, each request through the worker() of the glue
	// --emit-js-glue wrote, its connect() one over node:net.
	private static final String REACTOR_HOST = am.ik.rontolisp.testsupport.TcpLinePeer.NODE_CONNECT + """
			import { createServer } from "node:http";
			import { readFileSync } from "node:fs";
			import { worker } from "./worker.js";

			const module = new WebAssembly.Module(readFileSync(new URL("./worker.wasm", import.meta.url)));
			const served = worker(module, { connect });
			const server = createServer(async (request, response) => {
			  const headers = new Headers();
			  for (let i = 0; i < request.rawHeaders.length; i += 2) {
			    headers.append(request.rawHeaders[i], request.rawHeaders[i + 1]);
			  }
			  const asked = new Request("http://" + request.headers.host + request.url, { headers });
			  const answer = await served.fetch(asked, {}, {});
			  response.writeHead(answer.status);
			  response.end(Buffer.from(await answer.arrayBuffer()));
			});
			server.listen(0, "127.0.0.1", () => console.log("serving " + server.address().port));
			""";

	/**
	 * The driver in a {@code --no-wasi} module, whose sockets are its host's
	 * ({@code --host-sockets}): what a Cloudflare Worker's {@code connect()} is to a
	 * program, here over {@code node:net} -- a login by {@code trust} and one by
	 * SCRAM-SHA-256 (its nonce the host's entropy), statements, a prepared statement,
	 * text outside ASCII, a result of several reads of the socket; each connection its
	 * request's own.
	 */
	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void aJdbcProgramAHostCallsReachesTheServerThroughItsHostsConnect(boolean hostTimer, @TempDir Path workDir)
			throws Exception {
		Assumptions.assumeTrue(am.ik.rontolisp.testsupport.HostBoundaryRuns.jspiAvailable(),
				"no node with JSPI: a socket is waited for suspended");
		// a table of its own: the two builds run at once
		String table = hostTimer ? "reactor_tally_waits" : "reactor_tally";
		Files.writeString(workDir.resolve("Tally.java"), REACTOR_PROGRAM.replace("reactor_tally", table),
				StandardCharsets.UTF_8);
		// with the host's waits too (--host-timer), a call that waits for a socket keeps
		// the module, and a socket is the call's that was in the module when it was made
		List<String> build = new ArrayList<>(List.of("--java-source-path",
				JDBC_SOURCES + java.io.File.pathSeparator + JDBC_RESOURCES, "Tally.java", "-o", "worker.wasm",
				"--no-wasi", "--host-random", "--host-output", "--host-sockets", "--emit-js-glue"));
		if (hostTimer) {
			build.add("--host-timer");
		}
		runCli(workDir, build.toArray(String[]::new));
		Files.writeString(workDir.resolve("host.mjs"), REACTOR_HOST, StandardCharsets.UTF_8);
		Path out = workDir.resolve("host.out");
		Path err = workDir.resolve("host.err");
		Process process = new ProcessBuilder("node", am.ik.rontolisp.testsupport.HostBoundaryRuns.JSPI_FLAG, "host.mjs")
			.directory(workDir.toFile())
			.redirectOutput(out.toFile())
			.redirectError(err.toFile())
			.start();
		try {
			java.util.regex.Pattern said = java.util.regex.Pattern.compile("serving (\\d+)");
			long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(TIMEOUT_MINUTES);
			java.util.regex.Matcher port = said.matcher(Files.readString(out, StandardCharsets.UTF_8));
			while (!port.find()) {
				if (!process.isAlive() || System.nanoTime() > deadline) {
					throw new AssertionError("the host never said where it serves\n" + Files.readString(err));
				}
				Thread.sleep(100);
				port = said.matcher(Files.readString(out, StandardCharsets.UTF_8));
			}
			String large = "300 45150\n200000\n";
			// each login twice: the second request's connection is its own
			List<String> logins = List.of("user=trustuser", "user=scramuser&password=scrampw", "user=trustuser",
					"user=scramuser&password=scrampw");
			try (java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient()) {
				java.net.URI uri = java.net.URI.create("http://127.0.0.1:" + port.group(1) + "/");
				for (String login : logins) {
					java.net.http.HttpResponse<String> response = client.send(java.net.http.HttpRequest.newBuilder(uri)
						.header("X-Database-Url", "jdbc:postgresql://" + hostAddress() + "/" + DATABASE + "?" + login)
						.timeout(java.time.Duration.ofMinutes(TIMEOUT_MINUTES))
						.build(), java.net.http.HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
					assertThat(response.body())
						.as("%s; the host's standard error: %s", login, Files.readString(err, StandardCharsets.UTF_8))
						.isEqualTo(login.startsWith("user=trustuser")
								? "trustuser 42 hello\n1 お茶 2\n2 tea 🍵 5\n" + large : "scramuser 42 hello\n" + large);
				}
				// a connection kept for the next request has ended with its own: the
				// driver's exception of a connection lost, its cause the socket's
				List<String> kept = new ArrayList<>();
				for (int i = 0; i < 3; i++) {
					kept.add(
							client.send(
									java.net.http.HttpRequest.newBuilder(uri.resolve("/kept"))
										.header("X-Database-Url",
												"jdbc:postgresql://" + hostAddress() + "/" + DATABASE
														+ "?user=trustuser")
										.timeout(java.time.Duration.ofMinutes(TIMEOUT_MINUTES))
										.build(),
									java.net.http.HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
								.body());
				}
				assertThat(kept).as("the host's standard error: %s", Files.readString(err, StandardCharsets.UTF_8))
					.containsExactly("made 42\n",
							"java.sql.SQLNonTransientConnectionException 08006 java.net.SocketException: Socket closed:"
									+ " the request that opened it has ended\n",
							"made 42\n");
			}
		}
		finally {
			process.destroyForcibly().waitFor();
		}
	}

	@Test
	void preview1ModuleCompilesAndFailsLoudlyAtTheFirstSocketCall(@TempDir Path workDir) throws Exception {
		// The documented fourth-backend gap: Preview 1 has no host socket API. Since
		// the usocket shim grew wait-for-input (a listen-based poll spliced unpruned
		// into every usocket program), `listen` joined the tcp built-ins on the
		// CALL-time policy -- so the driver now COMPILES here like any other
		// spliced-socket program and the refusal moved to run time: the first socket
		// call raises the message naming the built-in and the backends that do work.
		// The real Preview 1 probe decision stays with its own todo.
		//
		// WHICH built-in is named is deliberately not pinned as a single name: the
		// driver trips several Preview 1 gaps and the one reported is whichever the
		// run reaches first, a call-order fact rather than a contract.
		Path program = writeExercise(workDir, authLadder("127.0.0.1", 5432));
		runCli(workDir, program.getFileName().toString(), "-o", "probe-p1.wasm");
		String path = "/tmp/" + workDir.getFileName() + ".p1.wasm";
		WASMTIME.copyFileToContainer(Transferable.of(Files.readAllBytes(workDir.resolve("probe-p1.wasm"))), path);
		ExecResult result = WASMTIME.execInContainer("wasmtime", "run", path);
		assertThat(result.getExitCode()).as("stderr: %s", result.getStderr()).isNotZero();
		assertThat(result.getStderr() + result.getStdout()).as("stderr: %s", result.getStderr())
			.containsPattern("(TCP-CONNECT|listen) requires the interpreter, the JVM backend");
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
		// backend compiled against the server's network alias rather than the mapped
		// host port.
		boolean component = backend == Backend.COMPONENT;
		String host = component ? POSTGRES_ALIAS : POSTGRES.getHost();
		int port = component ? 5432 : POSTGRES.getMappedPort(5432);
		String name = writeExercise(workDir, exercise.source(host, port)).getFileName().toString();
		return switch (backend) {
			case INTERPRETER -> runCli(workDir, name);
			case NATIVE -> throw new IllegalArgumentException("cl-postgres has no leg on a native executable");
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
				// handler-case, tcp + inherit-network + allow-ip-name-lookup because
				// wasmtime gates sockets and the lookup by permission (without them the
				// socket calls fail and surface as nil).
				ExecResult result = WASMTIME.execInContainer("wasmtime", "run", "-S", "tcp=y", "-S",
						"inherit-network=y", "-S", "allow-ip-name-lookup=y", path);
				assertThat(result.getExitCode()).as("stderr: %s", result.getStderr()).isZero();
				yield result.getStdout();
			}
		};
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
		return runSuccessfully(workDir, cli(args));
	}

	private static String[] cli(String... args) {
		List<String> command = new ArrayList<>(List.of(JAVA, "-cp", CLASSPATH, "am.ik.rontolisp.cli.RontoLispCli"));
		command.addAll(List.of(args));
		return command.toArray(String[]::new);
	}

	private static String runSuccessfully(Path workDir, String... command) throws Exception {
		return runSuccessfully(workDir, Map.of(), command);
	}

	private static String runSuccessfully(Path workDir, Map<String, String> env, String... command) throws Exception {
		Result result = run(workDir, env, command);
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
	private static Result run(Path workDir, Map<String, String> env, String... command) throws Exception {
		Path errFile = Files.createTempFile(workDir, "stderr", ".log");
		ProcessBuilder builder = new ProcessBuilder(command).directory(workDir.toFile())
			.redirectError(errFile.toFile());
		builder.environment().putAll(env);
		Process process = builder.start();
		String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		if (!process.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
			process.destroyForcibly();
			throw new AssertionError("timed out after " + TIMEOUT_MINUTES + " minutes: " + String.join(" ", command));
		}
		return new Result(List.of(command), process.exitValue(), out, Files.readString(errFile));
	}

}
