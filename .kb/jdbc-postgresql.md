# A JDBC driver for PostgreSQL (`jdbc-postgresql/`, 2026-11-22, l01)

**Invariant: `jdbc-postgresql/` is an ordinary Java project that knows nothing of rontolisp --
no `am.ik.rontolisp` import, no rontolisp artifact, outside the root reactor -- and rontolisp
knows nothing of it; it depends on nothing but the JDK (`java.sql`, `java.net.Socket`,
`java.security`, `javax.crypto`, `java.time`); it takes the URLs that start with
`jdbc:postgresql:` and is found through `META-INF/services/java.sql.Driver`, so a program
written against JDBC changes its dependency and nothing else; it is written from PostgreSQL's
protocol documentation, RFC 5802 / 7677 / 4013 / 3454 and the JDBC specification, and pgjdbc's
source is not its model.** It is to become a repository of its own (user decision):
coordinates `am.ik.jdbc:jdbc-postgresql`, package `am.ik.jdbc.postgresql`, both chosen to
survive the move. It implements a part of JDBC 4.3 and claims no compliance
(`jdbcCompliant()` is false).

Why it exists (user, 2026-11-22): pgjdbc was tried first and measured
(`.kb/java-frontend.md`, "pgjdbc measured": 149 classes the library lacks, a lower bound of
7.8 MB of wasm, threads); the user chose a driver of our own. pgjdbc on rontolisp is the
long-term `.todo/l22`.

```bash
./mvnw -f jdbc-postgresql/pom.xml spring-javaformat:apply test   # its own build, 18 tests, ~15 s, needs Docker
```

`.github/workflows/jdbc-postgresql.yaml` runs that on a change under the directory and, on a
push, deploys it to Maven Central (`.kb/session-workflow.md`, "The deploy job"); no job of
`ci.yaml` builds it, but a change under `jdbc-postgresql/src/main/` runs `ci.yaml` too
(its `paths`, with `servlet-httpserver/src/main/`): the e2e job compiles both as libraries. Provenance: nothing of pgjdbc or of a JDK is in it; pgjdbc is a
test-scope dependency asked the same statements beside it (use, as a JDK is the oracle of the
Java library: `.kb/java-frontend-provenance.md`).

## What it is

- **`PostgresDriver`**: `connect`, `acceptsURL`; registers itself with `DriverManager` in its
  static initializer. A URL of another driver answers `null`. **`PostgresDataSource`** (the
  other public class, l23): the bean properties `url`, `user`, `password` and the login
  timeout, a connection made through `PostgresDriver.connect`, nothing kept.
- **`Target`**: the URL and the properties. `jdbc:postgresql:db`, `jdbc:postgresql:/`,
  `jdbc:postgresql://host[:port]/[db]`, an IPv6 address in brackets, `?name=value&..`
  percent-encoded (a `+` of the query is a space); a setting of the URL wins over the
  properties. Read: `user` (else `user.name`), `password`, `ApplicationName`, `options`,
  `connectTimeout`, `loginTimeout` (else `DriverManager.getLoginTimeout()`), `socketTimeout`
  (seconds), `sslmode` / `ssl`. Any other name is ignored. More than one host is refused.
- **`Wire`**: the socket and the framing. Messages are written into one array and sent by
  one `write`, so a round trip is one segment each way; a message read is whole in the
  input array (8 KiB, grown to the largest message) before a field is asked for, and one
  `InputStream.read` takes what has come.
- **`Session`**: the protocol and nothing of JDBC but its exceptions -- start-up
  (`client_encoding=UTF8`, `DateStyle=ISO`), the four logins, the simple query, the
  extended query, cancel. An exchange is written whole, then read a GROUP at a time (what
  the server answers up to a ReadyForQuery). **Every value travels as text.**
- **`Scram` / `SaslPrep`**: SCRAM-SHA-256 without channel binding, the server's signature
  checked; PBKDF2 through `SecretKeyFactory` (which is where the interpreter has a kernel:
  `.kb/java-frontend.md`, "java.security and javax.crypto").
- **`PostgresConnection`, `PostgresStatement`, `PostgresPreparedStatement`,
  `PostgresResultSet`**, their metadata classes, `Rows` (rows the driver makes itself, as
  DataRow bodies, so a result set reads them as the server's), `Values` / `Moment` (text to and from Java
  values), `Sql` (the `?` of JDBC to `$n`), `Failures` (an exception of the class the JDBC
  specification gives the SQLSTATE's class). `Unsupported{Connection,Statement,ResultSet,
  DatabaseMetaData}` hold every abstract member of the interface as a refusal by name
  (`SQLFeatureNotSupportedException`, `0A000`), written by a script, so the class under
  each holds what the driver does and nothing else.

## Decisions

- **A prepared statement is the unnamed one, parsed each run** (Parse, Bind, Describe,
  Execute, Sync in one write): the server holds nothing between two runs, so a pooler in
  front of the server and a changed table cannot break it. A server-side named statement
  after n runs is `.todo/l23`.
- **A `Statement` goes through the simple query as it is**, so its text may hold several
  commands, each a result of `getMoreResults`. No JDBC escape (`{fn ..}`) is read.
- **All rows are read with their result** (`setFetchSize` is a hint): a result set walks
  both ways (`TYPE_SCROLL_INSENSITIVE`), holds over a commit, and updates nothing.
- **A parameter is sent as text with the type its setter names** (`setInt` int4,
  `setString` varchar, ..), so `where id = ?` with `setString` is the server's refusal, as
  JDBC's mapping says. `setObject(x, Types.OTHER)` and `setNull` of a type the driver does
  not know leave the type to the server (how a json or an enum is written).
- **`setTimestamp` sends the wall's fields with the program's offset and NO type**: the
  server reads the instant into a `timestamptz` column and the fields into a `timestamp`
  one, whatever time zone the session has. Typed either way, one of the two columns is
  wrong whenever the session's zone is not the program's; setting the session's `TimeZone`
  to the program's (what would make a typed value right) fails the login where the server
  does not know the zone's name. The cost: `select ?` alone cannot tell the type (write
  `?::timestamptz`).
- **`timestamptz` and `timetz` read as `java.sql.Timestamp` / `Time` and report
  `Types.TIMESTAMP` / `TIME`**: a program moving from pgjdbc casts `getObject` to those.
  `getObject(i, OffsetDateTime.class)` and the other `java.time` classes are asked by class.
- **A batch of a prepared statement is one group**: Parse once for each run of rows of the
  same types, Bind and Execute a row, one Sync -- one round trip, one transaction of the
  server's. A refusal undoes the rows before it, and every count of the
  `BatchUpdateException` is `EXECUTE_FAILED`. A `Statement`'s batch is a simple query a
  command, each its own transaction when the connection commits each statement.
- **`setQueryTimeout` is the server's `statement_timeout`**, written ahead of the
  statement in the same write when it differs from what the session has; a statement the
  server cancels then is a `SQLTimeoutException` (57014). No timer thread: a thread makes
  every function above a socket read a resumable body on one stack
  (`.kb/java-frontend.md`, "Threads"). A SET inside a transaction is taken back by its
  rollback, so the session forgets the limit when such a transaction ends.
- **A transaction starts with the first statement** when `getAutoCommit()` is false: `BEGIN`
  goes in the same write as the statement. `commit()` of a transaction that failed is
  the server's ROLLBACK and throws (40000).
- **The rows written** (`RETURN_GENERATED_KEYS`, column names) are `RETURNING *` or the
  names appended to an INSERT, UPDATE, DELETE or MERGE that has no RETURNING.
- **SASLprep is the server's, not the RFC's order** (measured against PostgreSQL 17 by
  creating roles and logging in): what RFC 4013 prohibits is looked for in the password as
  given, before the mapping and the normalization (U+0340, which NFKC would replace, and
  U+200D, which is commonly mapped to nothing, both leave the password as given); a code
  point Unicode 3.2 leaves unassigned counts as prohibited; U+200B is a space. A
  prohibited password is used raw, as the documentation says. The tables are RFC 3454's,
  written by `driver/gen-saslprep.py` of the artefacts.
- **The catalog's answers are queries of `pg_catalog`** (l23, 2026-11-24), each column
  JDBC's label: run as a prepared statement of the connection (so inside the program's
  transaction when one is open, as any statement), the patterns, the catalog and the names
  bound as text parameters, a pattern `LIKE`'s with a backslash for its escape. A catalog
  is the session's database: another one answers no rows. `getColumns` is the one built in
  the driver (`Rows`): the type, the size and the digits are `TypeId.jdbc` and
  `PostgresResultSetMetaData.precision` / `scale` of the column's type and modifier, so a
  column of a table and the same column of a result agree; a domain's column is
  `DISTINCT` with its base type's constant as `SOURCE_DATA_TYPE`. The table types are
  pgjdbc's names (`TABLE`, `VIEW`, `SYSTEM TABLE`, `TEMPORARY TABLE`, ..), so a program
  asking for them keeps working when it changes drivers. `ResultSetMetaData`'s table,
  schema, nullability and numbering are one query of `pg_attribute` the first time one is
  asked, for every column of the result that is a table's (the RowDescription's table and
  column number).
- **`getSQLKeywords` is the server's `pg_get_keywords()` less SQL:2003's key words**, the
  list being the SQL:2003 column of PostgreSQL 8.4's "SQL Key Words" (521 words, 4.6 KB of
  text sent as a parameter). pgjdbc's list has 22 more (`connection`, `restrict`,
  `version`, `xml*`, ..): those are SQL:2003's in that table.
- **TLS is refused, never skipped**: `sslmode=require` / `verify-*` and `ssl=true` are a
  `SQLFeatureNotSupportedException`; `prefer`, `allow` and `disable` connect in the clear
  (`.todo/l23`; the subset has no `javax.net.ssl`: `.todo/l15`).
- **One thread at a time, by a monitor on the session** around each exchange; `cancel()`
  and `abort()` do not take it. The regions cost 66 B of a component.

## Where it answers differently from pgjdbc 42.7.14

`PostgresDriverTest#whatAValueOfEachTypeReadsAsBesidePgjdbc` reads 24 literals through both
(the text, the class of `getObject`, the `Types` constant, the type name); they agree but:

| | here | pgjdbc |
|---|---|---|
| `boolean`'s constant | `BOOLEAN` | `BIT` |
| `jsonb`, an interval, an array, `xml` by `getObject` | `String` (the server's text) | `PGobject`, `PGInterval`, `PgArray`, `PgSQLXML` |
| an array's constant | `OTHER` | `ARRAY` |

`PostgresDriverTest#theMetadataBesidePgjdbc` asks both every fact of `DatabaseMetaData`
(each method without an argument, and with the constants it takes) and the catalog's
answers over one schema (a domain, serial and identity columns, a generated one, an array,
a composite and a deferrable foreign key, a partial and an expression index, a view, a
sequence). They agree but (l23, 2026-11-24, pgjdbc 42.7.14 against PostgreSQL 17):

| | here | pgjdbc | why |
|---|---|---|---|
| `allTablesAreSelectable`, `allProceduresAreCallable` | false | true | the catalog lists what the user may not reach |
| the functions of `{fn ..}`, `supportsStoredProcedures`, `..UsingCallSyntax` | none, false | lists, true | no escape is read |
| `getExtraNameCharacters`, `getProcedureTerm` | `$`, `procedure` | empty, `function` | "Identifiers and Key Words"; the server has procedures |
| `supportsOpenCursorsAcross{Commit,Rollback}` | true | false | a result set holds its rows |
| `own{Updates,Deletes,Inserts}AreVisible` | false | true | a result set updates nothing |
| `getMaxColumnsInSelect`, `getMaxConnections`, `getMaxRowSize` | 1664, `max_connections`, 0 | 0, 8192, 1 GB | "PostgreSQL Limits" |
| `getSchemas` | lists `pg_toast` | does not | |
| `getTables`' `TYPE_CAT` .. `REF_GENERATION` | null | empty | JDBC: "may be null" |
| `getColumns`' `DATA_TYPE` of an array / a boolean | `OTHER` / `BOOLEAN` | `ARRAY` / `BIT` | what a result set of the column says |
| `getColumns`' `TYPE_NAME` of a serial, `COLUMN_SIZE` of a date | `int4`, 10 | `serial`, 13 | the same |
| `getColumns`' `DECIMAL_DIGITS` of a text, a boolean, .. | null | 0 | JDBC: null where not applicable |
| `getPrimaryKeys`' order | by `COLUMN_NAME` | by `KEY_SEQ` | JDBC's order |
| `getIndexInfo`'s columns | JDBC's 13 | and `REMARKS` | |
| `ResultSetMetaData.getSchemaName` | the schema | empty | |
| `getColumnTypeName` of an identity `bigint` | `int8` | `bigserial` | |

Not in the tables because they are no reading of a value: an exception is of the class of
its SQLSTATE (`SQLSyntaxErrorException`, `SQLIntegrityConstraintViolationException`, ..)
where pgjdbc throws its `PSQLException`; a prepared statement is never server-side.

## Not there (each refused where it is asked for)

TLS, GSS and SSPI logins, channel binding, more than one host, `CallableStatement`, COPY
(`COPY FROM STDIN` is ended with a CopyFail, `TO STDOUT` is refused after its rows are
dropped), LISTEN / NOTIFY, arrays, large objects, `SQLXML`, updatable result sets, a cursor
that fetches in pieces, binary transfer, the catalog's `getTypeInfo`, `getProcedures`,
`getFunctions` and their columns, the privileges, `getBestRowIdentifier`,
`getVersionColumns`, the UDTs, super types and tables, attributes and pseudo columns, JDBC
escapes: `.todo/l23`.

## Compiled by rontolisp (measured 2026-11-22, Xeon E5-2697A v4, wasmtime 49, PostgreSQL 17)

`--java-source-path jdbc-postgresql/src/main/java:jdbc-postgresql/src/main/resources` (the
second root holds the services file), or the module's `-sources.jar`, which packs both.
**Nothing was refused and nothing in the front end changed**: the driver was written in one
piece and its first run printed the JDK's lines on the four targets.

- **Sizes** (bytes; `probes/Ladder.java` of the l01 artefacts: the four logins, CRUD, a
  prepared statement run twice, non-ASCII text; `probes/Hello.java`: a login and a query):

  | | component | Preview 1 | `--native` | JVM class | compiled in |
  |---|---|---|---|---|---|
  | `Ladder` | 813,597 | 653,810 | 7,250,648 | 1,053,695 | 8.5-11.8 s |
  | `Hello` | 777,722 | | 7,009,712 | 1,007,520 | 8.7-11.1 s |
  | `driver/Tour.java` | 1,018,535 | | 8,180,136 | 1,321,508 | 12.5-15.3 s |
  | `examples/java/jdbc/src/main/java/jdbc/Notes.java` (wasmtime 51) | 1,019,302 | | 8,009,296 | 1,284,387 | 10.7-13.5 s |

  `Notes` (the example: CRUD into a record, a transaction rolled back, `java.time`,
  `java.net.URI`) runs in 0.05 s as a native executable, 0.1 s as a component, 0.35 s on
  the JVM and 4.5 s on the interpreter (compiling the driver included), against 1.0 s
  under the JDK's source launcher (2026-11-22, load average 5).
  pgjdbc's lower bound for `Hello` was 7,877,748 B and 52 s. Of `Ladder`'s 631,367 B of
  functions (`-Drontolisp.wasm.debug-func-sizes`): the driver 68,241 (213 functions), the
  program 9,578, the library's classes 136,653, the Java run time 73,477, dispatch 49,535,
  normalization and its tables 23,377, the rest the Lisp run time. SASLprep costs 59 KB
  of a component (NFKC and RFC 3454's tables: 753,308 B without it), paid by every program
  since SCRAM is reached from every login.
- **A login and a query, ms** (`probes/Timing.java`: one process, five logins, two queries
  each; the first of a process aside):

  | | trust / md5 | `password` | SCRAM-SHA-256 | a query |
  |---|---|---|---|---|
  | JDK, pgjdbc | 18-31 | | the same | 0.45-1.2 |
  | JDK, this driver | 15-21 | 15 | 28 | 0.18-0.5 |
  | interpreter | 35-47 | 47 | 42-82 | 2.4-4.6 |
  | JVM | 16-21 | 21 | 76-110 | 1.1-1.5 |
  | `--native` | 4.5-6.3 | 14.5 | 82-92 | 0.28-0.34 |
  | component | 5.1-6.6 | 15.3 | 70-79 | 0.44-0.62 |

  A SCRAM login on wasm is its PBKDF2 (55-77 ms of the library's Java: "java.security and
  javax.crypto"); `password` is the server deriving the verifier.
- **A large answer was the socket's read** (`driver/Bulk.java`: 10,000 rows of an integer
  and a text of 100 characters, 1.2 MB on the wire; ms to read / to walk): JDK 6 / 3,
  `--native` 507 / 89, component 1,105 / 89, JVM 2,110 / 88, interpreter 3,179 / 889 -- a
  call a byte of the socket, not the driver's cost. Since l13 (2026-11-24) a read is one host
  read ([tcp-sockets.md](tcp-sockets.md), "What has come, in one read"): `--native` 15-17,
  component 18, JVM 23-29, interpreter 94-115 to read, the walk as before.
- **The catalog and the data source on the four** (l23, 2026-11-24, wasmtime 51, load
  30-45): a probe (a `PostgresDataSource`, the facts, `getSchemas`, `getTables` with types,
  `getColumns` of a domain, an identity, a generated column and an array, the keys, the
  indexes, the table types, a result's tables and nullability) printed the JDK's lines on
  the interpreter, the JVM, `--native` and the component: 845,933 B of component, 1,088,149
  of class, 7,286,512 of `--native`. What it costs a program that asks no catalog question,
  components against the driver before: `Hello` 782,274 and `Ladder` 818,231 both
  unchanged, `driver/Tour.java` (which calls `getMetaData`) 1,021,343 -> 1,021,654. It
  was 1,029,933 while the SQL:2003 key words and the table types' text were fields: a
  class's static text is held by every program that reaches the class, so the text of a
  question lives in the method that asks it. `driver/Bulk.java` the same day (ms to read /
  to walk, the third run): JDK 10 / 5, `--native` 17 / 143, component 16 / 100, JVM 39 /
  133, interpreter 116 / 1,055.
- **Pinned by** `ClPostgresE2eTest#theJdbcProgramsAnswerOnEveryBackendWhatTheyAnswerUnderTheJdk`
  (two programs a leg, on the interpreter, the JVM, `--native` and the component, against
  the same programs under the JDK with the module javac compiled: the artefacts' `Ladder.java`,
  which has to print what pgjdbc printed, `Ladder.out` -- its table a leg's own, written
  into the leg's source: a program of the subset is handed no arguments, so until
  2026-11-23 every leg wrote the default `items_jdk` at once, and one failed now and then
  with a duplicate key of `pg_type` (seen on the native leg) --, and the example
  `examples/java/jdbc/src/main/java/jdbc/Notes.java`, which has to print `examples/.expected/java-jdbc-notes.txt`
  and reads the server from `DATABASE_URL`; the component reaches the server by name over
  the Docker network; and since l11 the Java shiori served on each leg -- the component
  under this host's `wasmtime serve` -- against a database of the leg's, asked 33 requests
  in turn and answering what the JDK serving it answered, status, the application's fields
  and body, the JDK's statuses checked too). By hand, not a test: `driver/Tour.java` (the types through setters,
  `setObject` and `getObject`, a transaction, a savepoint, batches, the rows written,
  several results, a scrolling result set, the metadata, the refusals, a statement out of
  time) prints the JDK's 32 lines on the four (`driver/legs.sh`).
- A Preview 1 module compiles and ends at the connect with `SQLException: .. a socket needs
  the host's network` (`08001`).

## In a served program (l11, 2026-11-22)

**Invariant: a request that reads or writes the database runs its statements on a
connection an earlier request gave back, as a pool keeps them; under `wasmtime serve` the
connections are the instance's and end with it.** Measured first, on a WebMvc.fn probe (a
route connecting for the request, one using a connection kept in a static field) over
`servlet-httpserver/`, JDK 25.0.5, wasmtime 51.0.0, PostgreSQL 17 on the loopback, load
average 4-7.

- **What `wasmtime serve` grants a served component**: without `-S cli=y` the component is
  refused at its start (`component imports instance wasi:sockets/types@0.3.0, but a
  matching implementation was not found in the linker` .. `instance export tcp-socket has
  the wrong type` .. `resource implementation is missing`), with `-S inherit-network=y` or
  without; with `-S cli=y` alone every connect is refused (`The connection to
  127.0.0.1:54371 failed: Permission denied`, the 500 page); with both it connects
  ([tcp-sockets.md](tcp-sockets.md), "serve+tcp"). The driver under Spin was not measured:
  `examples/db/README.md` has the Lisp component on it, a plain TCP connect checked under
  `allowed_outbound_hosts`' `tcp://host:port`; Spin's own connection is "Through the
  host's connection" below.
- **A connection a request against one kept**, ms, the median of 50 sequential requests
  after 5 (30 for SCRAM); in brackets what the program measured of one request (connect /
  a prepared query):

  | | no database | a login a request (trust) | kept | a login a request (SCRAM) | kept |
  |---|---|---|---|---|---|
  | JDK | 1.2 | 7.4 (5.6 / 1.0) | 2.0 (0.6) | 14.9 (19.4) | 1.7 |
  | interpreter | 14.0 | 31.9 (19.4 / 6.2) | 15.0 (5.7) | 45.2 (40.5) | 15.0 |
  | JVM | 6.1 | 33.9 (9.7 / 2.5) | 6.8 (2.4) | 68.7 (98.5) | 6.1 |
  | `--native` | 1.5 | 24.7 (12.2 / 10.8) | 12.3 (10.8) | 123.4 (140.9) | 12.5 |
  | component | 1.2 | 12.1 (6.8 / 1.6) | 2.5 (0.8) | 125.9 (122.8) | 2.8 |

  A login a request costs a few round trips with `trust`, and on wasm a SCRAM login is its
  PBKDF2 ("Compiled by rontolisp" above). A query of a served `--native` program waited out
  the server's ten-millisecond wait for its next request until l44 (one wait for both,
  [java-frontend.md](java-frontend.md), "java.net.Socket and ServerSocket"); measured
  2026-11-23 on the probe (`com.sun.net.httpserver`, a pool of four, a kept connection,
  PostgreSQL 17 on the loopback, load 3-7, the median of 50 after 5; the program's own
  time in brackets), ms: one query `--native` 12.0 (10.6) -> 1.9 (0.83-0.88), JDK 2.3
  (0.6), component 1.6 (0.7); six statements 65.3 (63.6) -> 5.0-6.0 (4.0-4.7), component
  7.0 (5.1); a request without the database while another waits 0.3 s for `pg_sleep`,
  1.0-1.2 before and after.
- **How long a kept connection lives under `wasmtime serve`** (the probe's `servlet-httpserver`
  program): 128 requests an instance (`--max-instance-reuse-count`), the 129th a fresh
  instance with a connection of its own, the old one's still open; 3 s idle changed
  nothing (measured 2026-11-22, when the container's cached pool kept a thread waiting 60 s,
  and an instance with a thread waiting is not idle; on the virtual-thread executor since
  2026-11-24 an instance is dropped after 1 s idle: [servlet-httpserver.md](servlet-httpserver.md),
  "What a session in an instance's memory is worth under `wasmtime serve`"; the connection
  under it not re-measured); 16
  requests at once went to several instances, 11 connections in all; 65 s idle and every
  instance was dropped and its connection closed by the host, nothing in the server's log.
  So a pool lives as an instance does, and no connection outlives its instance.
- **Decided: kept** (the Java shiori's `jdbc/Database`; since l46 a statement's, not a
  request's -- a request's statements run one after another and the latest given back is
  taken first, so they share one): an idle connection is taken first, asked
  `isValid` only when it waited more than two seconds (the server may have ended it), given
  back unless it failed (SQLSTATE class 08) or was left out of auto-commit, four kept at
  most, the deque a `ConcurrentLinkedDeque` (no `synchronized`, as `examples/java` writes
  shared state). The pool is the application's: the driver's `PostgresDataSource` (l23)
  makes connections and keeps none.
- **The Java shiori** (`examples/java/shiori`, WebMvc.fn, jmustache, YAVI 0.18.1, tiny-json
  0.4.1; 2026-11-22, the tree of its merge, from the exec jar, load 3-30): `Shiori.class`
  3,834,187 B with `Shiori$Part1.class` 1,610,105, the component 5,396,825, the `--native`
  executable 93,214,184 (the Lisp version: 7,172,975 B of class, 6,316,187 of component);
  compiled in 59 / 68 / 338 s, and `wasmtime serve` compiles the component for 240 s at
  its first start (then its cache). Of the component's 4,710,705 B of functions the resume
  bodies are 2,171,154 (1,159; 1,705 of 6,046 functions and lambdas are resumable), the
  dispatchers 494,368, the library's classes 289,056 (`java.math` 15,476: the driver's, not
  tiny-json's), lambdas 149,687, YAVI 136,173, the application 87,134, jmustache 80,488, the
  driver 58,432, webmvc-fn-lite 40,627, the container 27,113, tiny-json 16,950. What made
  so much resumable was the analysis's (l34, [java-frontend.md](java-frontend.md), "What
  made functions resumable that cannot suspend"): a `Properties` whose `load` could hold
  its monitor across a socket read made `%string`, `%equals` and `%hash` resumable, and
  with them every caller of a `toString`, an `equals`, a `hashCode` or a map; a lazy
  class's initialization counted as a synchronization action. Since l34 (the same
  sources): the component 3,719,642 B, its resume bodies 378,944 (234 of 6,035 resumable),
  `--native` 38,796,784 B in 75 s, `wasmtime serve` compiling it in 1.9 s; a request's
  time is what it was. The shiori as l45 and l46 left it: 6,229,652 -> 4,155,184 B (2,099
  -> 251 resumable). Since l47 (2026-11-23, a reader or a stream read through its abstract
  class reaching only what it may be): 4,155,515 -> 4,071,814 B, 168 resumable
  ([java-frontend.md](java-frontend.md), "What a receiver may be").
  A request's time, ms, the median of 30 after 5, load 3-6 (the dashboard of seven
  statements / the library / a book / an htmx progress / the stylesheet): JDK 16.9 / 7.5 /
  5.4 / 7.0 / 1.7, interpreter 353 / 194 / 94 / 69 / 65, JVM 83-157 / 52-72 / 30-40 / 25-32
  / 6-9 (two rounds), `--native` 126 / 60 / 32 / 41 / 8, component 122-125 / 59-109 / 28-38
  / 21-22 / 16-23 (rounds of one instance vary by twice: a fresh instance runs `main`, the
  schema's statements, in its first request). The start: JDK 0.6 s, JVM 1.8 s, `--native`
  0.4 s, interpreter 34-48 s (the program lowered, then the schema). Since l44 (2026-11-23,
  the tree l34 left, load 3-4, the same four pages less the htmx progress, two rounds):
  `--native` 116-156 / 62-68 / 37 / 7.7 -> 37-38 / 20 / 10 / 6.8. Eight dashboards at once
  (ten each) ~30 a second before and after: one thread's CPU, a reply's wait no longer
  counting.
- **Its forms in YAVI's idioms** (l45, 2026-11-23): each input a record holding its schema,
  a `switch` over `Validated`, `Status` an enum a value maps to, two validators shared by
  two forms, the path's id read by a validator, the book form reversible (an edited book
  written back by `toMap` and `ParameterTree.ofValues`). Every answer as before: the 33
  requests of the test and 62 more (each stored book's edit form, ids such as `007`, `+1`,
  19 digits, filters sent twice or in capitals, keys no form names, each refusal), field
  for field on a JDK. Measured from the exec jar of each tree, at load 10-60: `Shiori.class`
  5,448,324 -> 5,938,360 B (with `Shiori$Part1.class`), the component 5,396,881 ->
  5,993,780, the `--native` executable 93,214,184 -> 98,530,528. Of the component's
  functions, 4,710,705 -> 5,224,028 B: the resume bodies +312,018 (`.todo/l34`'s: what
  reaches a `toString`, an `equals` or a `hashCode` is resumable, and writing back walks
  maps), dispatch +52,469, `java.time` +48,923 (a `LocalDate` written back as text),
  `java.util` +24,869, YAVI +25,661, lambdas +16,539, the application -5,744. The compiler's
  change alone moves nothing measured (YAVI's `Booking` 983,445 B on either).
- **Its classes by feature and layer** (l46, 2026-11-23; the user's request: routing, views,
  data access and the domain apart): `book/`, `note/` (domain records with their schemas, a
  repository holding the SQL), each with `web/` (handlers, view models), `home/web/`, `web/`
  (templates, assets, `Page`/`Fragment`/`Redirect`, filters, `Formats`), `jdbc/` (the pool,
  a `JdbcClient` of statements, a transaction holding one connection), the root the start
  and `ShioriRouter`. Package by feature over package by layer: the dashboard reads books and
  notes, and the two features' handlers need each other's views one way only
  (`book.web` -> `note.web`), which a layer package would hide in one bag. No cycle; no
  domain record imports HTTP, SQL or a template. Formatting moved out of SQL into `Formats`
  (`to_char` gave way to `LocalDate`s read with `getObject`), except today, still the
  database's `current_date`, and `book_cards` lost its label columns. A connection is now a
  statement's, not a request's. Every answer as before: the test's 33 requests, l45's 62 and
  60 more (40 books created, the library paged, sorted and filtered across pages), field for
  field on a JDK, the four legs and Tomcat 11.0.27. Sizes, the exec jar of the base tree, load
  4-10: `Shiori.class` 5,943,382 -> 6,248,075 B (with `Shiori$Part1.class`), the component
  5,995,225 -> 6,229,652, `--native` 98,559,328 -> 100,519,840; compiled in 74 / 86 / 338 s.
  Of the component's functions, 5,225,356 -> 5,441,683 B: resume bodies +123,495 (51 more
  resumable: a statement goes through `StatementSpec`, a `Connector` and a lambda, each
  able to suspend; `.todo/l34`'s), dispatch +30,517, lambdas +12,132, the application
  +10,968, the driver +7,514 (`getObject` of a class), `java.time` +6,856.
- **The same classes as a WAR on Tomcat 11.0.27** (embedded, by hand; `@WebListener` starts
  the application with its context, `main` starts `servlet-httpserver` and nothing else):
  the 33 requests of the test answered field for field as on `servlet-httpserver`, once
  a filter read every form as UTF-8 (`Http.utf8`, since l46 `RequestFilters.utf8`); before it a title `試し` posted was
  `è©¦ã` there, Tomcat's default for a form being ISO-8859-1
  ([servlet-httpserver.md](servlet-httpserver.md), "Where the specification leaves room").

## Through the host's connection (m13, 2026-11-24)

TLS from a served component waits on a host of `wasi:tls` (`.todo/l15`). A host that owns the
connection answers it another way: Spin's `spin:postgres/postgres`, imported by
`@WitImport` in `examples/java/spin-postgres` (a servlet; the user's request: an example
first, a host transport under this driver the user's decision after it -- decided
2026-11-25, m24: a second driver, [jdbc-spin-postgres.md](jdbc-spin-postgres.md), which
holds the declaration since and under which the example is plain JDBC). Measured on
Spin 4.3.0 (wasmtime 50.0.1), 4.1.0 (48.0.0, the first with wasmtime 46+) and canary
4.4.0-pre0 (51.0.0), PostgreSQL 17.12, load 18-70; Spin installed under the scratchpad.

- **The interface** is `spin:postgres@4.2.0` (Spin's `wit/deps`, served from 4.1.0): a
  `connection` resource (`open` static, `query` -> `row-set`, `execute` -> `u64`, each with
  an `async` twin), a `connection-builder` resource (constructor, `set-ca-root`, `build`),
  variants `error`, `query-error`, `db-data-type` (25 cases), `db-value` (26),
  `parameter-value` (25), records `db-error` (with `option` and `list<tuple<..>>`),
  `column`, `row-set` (`list<list<db-value>>` through an alias), `interval`, tuples nested
  in `option`, an enum. **`@WitImport` took the file verbatim** (`@since` gates and `async`
  members included; the async ones not declared, hence not bound) and mapped every type;
  the one thing that failed was the front end's, not the boundary's: a record named
  `Boolean` (a case) could not be compiled ([java-frontend.md](java-frontend.md), "A
  record"), fixed. The cost is the declaration: every case of every variant a declared
  member reaches: 372 lines of Java, comments included, for the six functions the
  program calls.
- **Spin's TLS** is native-tls (OpenSSL on Linux; Spin's `factor-outbound-pg`) under
  tokio-postgres: `sslmode` is `disable`, `prefer` (the default) or `require`;
  `verify-full` is refused (`invalid value for option sslmode`). Whenever TLS is tried the
  chain and the host name are verified (`unable to get local issuer certificate`, `IP
  address mismatch` seen), so `prefer` against a server offering TLS with a certificate
  Spin cannot verify fails rather than falling back; `require` against a server taking only
  `hostssl` connects over TLSv1.3. A CA of the program's own: the builder's `set-ca-root`
  (PEM text, here from `spin up --env DATABASE_CA="$(cat ca.crt)"`), or `SSL_CERT_FILE`
  in Spin's own environment with a plain `open`. SCRAM logins over both.
- **The connection**: `open` takes a client out of a pool Spin keeps per (address, CA)
  (its source; a session closed was the next `open`'s); a `connection` resource is one session until
  closed -- a transaction across `execute` calls holds and another connection does not see
  it; a session given back inside a FAILED transaction is dropped, and one given back
  inside an open one is handed out again still inside it, its settings reset (corrected
  2026-11-25, m24: [jdbc-spin-postgres.md](jdbc-spin-postgres.md), "What the host does";
  m13 had "handed out again with none"). `allowed_outbound_hosts = ["postgres://{{ db_host }}"]` is enforced
  (`address postgres://127.0.0.1:33004 is not permitted`). Instances are reused as under
  `wasmtime serve` (a static counter grew across sequential requests; requests at once
  started instances of their own).
- **Values read** (`db-value`): `int2`/`int4`/`int8`, `float4`, `numeric` as its text,
  `text`, `bytea`, `date`, `time`, `timestamp` (a tuple of seven), `interval`, `uuid`,
  `jsonb` (bytes), `int4[]`, null. **`timestamptz` is refused**: the whole query fails
  (`query-failed(text("cannot convert between the Rust type Option<NaiveDateTime> and the
  Postgres type timestamptz"))`), so a column of it must be cast in the SQL. A refused
  statement's `db-error` carries the SQLSTATE, message, detail and the rest as pairs
  (`routine`, `file`, `line`).
- **Costs**: the example's component 1,156,268 B (the same with `--optimize`), compiled in
  16-19 s; `hello-servlet` 802,020. Of its 926,873 B of functions the binding is 42,769
  (75), the declared types' members 58,052 (425: each case record's `toString`, `equals`,
  `hashCode`, reached since the binding can make every case), the program 10,939. A
  `GET /` of two statements, each on a connection out of Spin's pool, the median of 30:
  4.8 ms in the clear, 5.7 ms over TLS (JDBC under `wasmtime serve` above: 2.5 for one
  query on a kept connection, 12.1 logging in a request).

**What it says about a host transport under this driver** (the user's decision): Spin's
interface is not a transport. It takes statements and answers rows; there is no byte
stream to put the driver's protocol on, and so nothing under the driver's
`java.net.Socket` it could replace. Putting it under the JDBC API means a second
implementation of `Connection`, `PreparedStatement` and `ResultSet` over `query` /
`execute` -- parameters through `parameter-value`, getters over `db-value`, the metadata
of a result from `column`, `SQLException`'s SQLSTATE from `db-error.code`, transactions
as statements on one `connection` (measured to hold) -- with its limits: no
`timestamptz`, no binding by type beyond the 25 cases, no `cancel`, no COPY or
LISTEN/NOTIFY, a result read whole (`query-async`'s stream is the async twin
`@WitImport` refuses). It would also need `@WitImport`, a rontolisp artifact, which the
invariant above keeps out of this module: it would be a module of its own beside it,
chosen where the program runs on Spin. Its size would be the binding and the types (100
KB of functions here) against the driver's protocol (68 KB in `Ladder`). What it buys is
TLS and a pool the host keeps; a program not tied to JDBC gets both by calling the
interface, as the example did until m24. **Made, 2026-11-25 (m24)**: `jdbc-spin-postgres/`,
[jdbc-spin-postgres.md](jdbc-spin-postgres.md). Its numbers against this estimate: the
binding and the types 83 KB and the driver's own classes 74 KB of functions, the example
1,490,292 B against 1,618,536 for the same program through this driver.

## On a Cloudflare Worker (m44, 2026-11-25)

The driver compiles into a `--no-wasi --host-sockets` module unchanged
([tcp-sockets.md](tcp-sockets.md), "A reactor's sockets"); `--host-random` is the SCRAM
nonce. Measured by hand under `wrangler dev` 4.168.0 (workerd), a Hyperdrive binding's
`localConnectionString` (PostgreSQL 17 on the loopback, load 5-15),
`examples/cloudflare-workers/hello-jdbc/` (connect, `create table if not exists`, an upsert,
close, each request): the first request 0.35-0.55 s (the instance and `main`), then
17-22 ms with `trust` and 210-220 ms with `scram-sha-256` -- the login's PBKDF2, paid by
every connection since none outlives its request (node over `node:net`: 10-14 and
220-270). The module 1,039,941 B at `--optimize=size`, 274,869 `gzip -9`.

- **Not measured: a deployed Worker.** Hyperdrive's own connection string, what login it
  asks of the Worker and whether it takes a connection in the clear (the driver speaks no
  TLS) are open; Cloudflare's pages say a client is made per request and Hyperdrive keeps
  the pool, in transaction mode -- which the driver's unnamed prepared statement suits.
- A kept connection is `SQLNonTransientConnectionException` (08006) at its next request;
  a connect in `main` traps the instance without `--host-timer`.

## JSpecify and NullAway (l55, 2026-11-23)

The package is `@NullMarked`, what may be null `@Nullable`; the `nullability-maven-plugin`
(0.5.3, as servlet-httpserver's: [servlet-httpserver.md](servlet-httpserver.md), "The
builder") runs NullAway on the main compile, `-Werror`, so `jdbc-postgresql.yaml` enforces
it. jspecify is `provided`: the driver still needs nothing but the JDK where it runs.

- **What JDBC lets be null is `@Nullable`**: a `ResultSet` getter's answer for SQL `NULL`
  (and the by-label ones), `getStatement()` of a result set the connection made,
  `getWarnings()`, `getResultSet()`, `getMetaData()` of a statement answering no rows,
  `getClientInfo`, `getSchema`, `connect` for a URL of another driver; a setter's value
  where it is tested for null. The `Unsupported*` bases carry the same return on the members
  a driver class overrides with one (an override may not widen a non-null return).
- **81 findings at first**, ~50 more as returns became `@Nullable`. Besides the annotations:
  `PostgresResultSet` reads a value's text in `text(index)`, so `getObject` passes no
  nullable `getString` on; its columns are `requireNonNull` (a result set is made of a result
  with columns alone); `getMetaData` tests the described columns; `run`'s two `Sql` are
  made in one branch. `Values.date` / `time` with a zone threw a `NullPointerException` they
  caught for a time alone / a date alone; they throw the `DateTimeException` themselves.
- **Pinned by** the module's build and tests, and `ClPostgresE2eTest` on the four legs (17
  tests, 2026-11-23). Its oracle compiles the driver with javac beside the two JDBC programs:
  that javac failed on `org.jspecify` until it was given `ExampleProject.provided(JDBC_SOURCES)`
  as the shiori's is given its project's.

## Tests

`jdbc-postgresql/src/test/java/am/ik/jdbc/postgresql/`: `PostgresDriverTest` (a PostgreSQL
17 in Docker, a role a login method; ten families -- the logins, passwords outside ASCII,
every type written and read, the table beside pgjdbc, what a statement answers, a result
set walked both ways, transactions, refusals, a statement out of time and one cancelled,
the metadata and the catalog beside pgjdbc), `ScramTest` (RFC 7677's exchange, RFC 4013's examples), `SqlTest` (the
lexical cases of `?`), `TargetTest` (the URL forms). A cancel is sent once the server shows
the statement running (`pg_stat_activity`), no sleep.
