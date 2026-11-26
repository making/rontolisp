# A JDBC driver over Spin's PostgreSQL connection (`jdbc-spin-postgres/`, 2026-11-25, m24)

**Invariant: `jdbc-spin-postgres/` is a second JDBC driver for the URLs that start with
`jdbc:postgresql:`, whose every statement runs on the host's `spin:postgres/postgres`,
imported by `@WitImport`; it depends on `rontolisp-java-api` and on nothing else (not on
`jdbc-postgresql/`, which stays free of rontolisp and unaware of it); where no host
implements the interface its `connect` answers `null`, so `DriverManager` goes on to the
next driver; and whatever the interface does not carry is a
`SQLFeatureNotSupportedException` that names it, never a silent difference.** Asked by the
user (2026-11-25) after m13's example ([jdbc-postgresql.md](jdbc-postgresql.md), "Through
the host's connection"): a host-owned connection gives TLS, verified, and a pool, where
`wasmtime serve` hosts no `wasi:tls`. Coordinates `am.ik.jdbc:jdbc-spin-postgres`, package
`am.ik.jdbc.spin.postgres`, release 21 (the interface's variants are read by record
patterns), outside the root reactor. Provenance as its sibling's: the JDBC specification,
PostgreSQL's documentation, Spin's WIT file and what Spin and the server were measured to
do; no source of pgjdbc, of a JDK or of Spin.

```bash
./mvnw -f rontolisp-java-api/pom.xml install
./mvnw -f jdbc-postgresql/pom.xml install -DskipTests
./mvnw -f jdbc-spin-postgres/pom.xml spring-javaformat:apply test   # 12 tests, ~10 s, needs Docker
```

`.github/workflows/jdbc-spin-postgres.yaml` runs that on a change under the directory, under
`jdbc-postgresql/` or under `rontolisp-java-api/`, and deploys the SNAPSHOT on a push (the
side modules' job, `.kb/session-workflow.md`); a change under its `src/main/` runs `ci.yaml`
too, whose e2e job compiles it as a library and serves it under Spin.

## Decided

- **The URL is `jdbc:postgresql:`, the one a program has already.** The database is
  PostgreSQL; what changes is who owns the connection, which is the deployment's, so it is
  the dependency that says it and not the program's configuration. Rejected: a scheme of
  its own (`jdbc:spin-postgres:`), which makes the URL differ between a JDK and Spin for
  one program.
- **Both drivers on the path: the first that connects, as `DriverManager` goes.** With no
  host of the interface (`ServiceLoader.load(Postgres.class)` finds none: a JDK, the
  interpreter, the JVM) `connect` answers `null`, the answer JDBC gives a driver "of the
  wrong kind" -- not an exception, because `DriverManager` throws the FIRST refusal when no
  driver connects, and a wrong password would then be reported as "no host". So one program
  with both runs over a socket under a JDK and through the host under Spin. Measured: the
  example on a JDK (`mvn exec:exec`, `jdbc-postgresql` a `runtime` dependency), on the
  interpreter and on the JVM (both source paths) answered the lines Spin answered; a
  component holding both, in either source-path order, connected through the host under
  Spin with `allowed_outbound_hosts` naming `postgres://` alone. A component is still
  compiled with this driver alone: both cost 1,882,267 B against 1,477,233 (2026-11-26).
  The wasm goal reads `compile`, `provided` and `system` dependencies, so the socket
  driver is the pom's `runtime` one. `SpinPostgresDataSource` names the driver and throws
  (`08001`, "No host implements ...") where there is no host.
- **No provider for a JDK in the module.** JDBC is already the seam a JDK and Spin share: a
  provider of the WIT interface over a socket would put a second driver under this one to
  reach what that driver reaches directly, and bring its `META-INF/services/java.sql.Driver`
  into every component. The tests have one (below), in `src/test`.
- **Nothing is shared with `jdbc-postgresql/` as code.** That driver's `ResultSet` and
  `Values` read text under type OIDs out of DataRow bodies, and its statements are
  protocol messages written ahead of a read; the host hands over typed cases and takes a
  statement a call. Sharing its classes means depending on its artifact, whose services
  file registers the socket driver in every component (+397 KB, and an order to rely on),
  or a third artifact both depend on, which ends "no dependency but the JDK". What the two
  have alike is written twice and held together otherwise: the `Unsupported*` bases are the
  same script's (given the package), `Sql`'s lexer, `Moment` and the cursor of the result set are the sibling's
  text, and `SpinPostgresDriverTest#whatAValueOfEachTypeReadsAsBesideTheSocketDriver` asks
  both 40 literals (the text, the class of `getObject`, the constant, the type's name, the
  label) and lists the differences: three, all a `varchar` or a `char` the host calls a
  text.
- **The catalog is the sibling's queries written a second time** (m26), for the reasons
  above: `SpinPostgresDatabaseMetaData` is `PostgresDatabaseMetaData`'s text but where a
  query's output column would be an `oid`, which the host does not convert (`name` and
  `"char"` it hands over as their binary form, which is their text). `getColumns` reads a
  column's type by its name (`b.typname` for a domain's base), so the constant, the size
  and the digits are `Values.jdbc(String)` and `SpinPostgresResultSetMetaData.precision` /
  `scale` of a name and a modifier. Held together by
  `SpinPostgresDriverTest#theCatalogBesideTheSocketDriver`: every fact of
  `DatabaseMetaData` and the catalog over one schema (a domain, serial, identity and
  generated columns, an array, a composite and a deferrable key, a partial and an expression
  index, a view, a sequence) asked of both; three differences, the driver's name and
  `supportsMultipleResultSets` / `supportsMultipleOpenResults` (one result a statement here).
- **A statement is described before it runs by statements of the driver's** (m26).
  `getMetaData()` of a query (`Sql.query`: SELECT, VALUES, TABLE, after WITH too) runs it
  inside `SELECT * FROM (..) AS described WHERE false`, its parameters NULL: the host
  answers an empty result's columns, labels and cases as the run's own (measured: a
  volatile `nextval` in it is not advanced). A command answering no rows is `null`; one
  that returns rows (`RETURNING`, `SHOW`, `EXPLAIN`) is refused until it has run --
  `PREPARE` tells its types (`result_types`, PostgreSQL 16+) but no column's name.
  `getParameterMetaData()` is three calls: `PREPARE` under a name of the call's own,
  `parameter_types` joined to `pg_type.typname`, `DEALLOCATE`. The name is
  `jdbc_spin_postgres_` + `nanoTime` + a count, not a fixed one: a name left by a failed call
  stays in a session the host hands out again, a taken name is an error, and an error inside
  a transaction aborts it (no `DEALLOCATE` can run there to clean up). A parameter with a
  value is the type its setter casts to; one without is the server's inference (`select ?`
  is `text`). A statement's trailing semicolons, comments and white space (`Sql.trailing`)
  are cut before it is wrapped or a `RETURNING` appended: one appended after `-- c` was a
  comment until m26 (`SqlTest`).
- **Arrays: `getArray`, `createArrayOf`, `setArray` here, refused by the sibling** (m26).
  The host's four array cases read as `Integer[]`, `Long[]`, `BigDecimal[]`, `String[]`
  (`varchar[]` is text); another element type is `unsupported` and refused with the cast
  (`::text[]`). A parameter is the host's case of its element type when every element is of
  that type's class, else `array-str` of the elements' texts cast `text[]::T[]` (measured:
  the server casts `text[]` to `float8[]`, `bool[]`). `getObject` and `getColumnType` of an
  array stay the server's text and `OTHER`, as the sibling answers, so the two change
  together when the sibling has arrays (`.todo/l23-jdbc-postgresql-what-the-first-cut-left.md`).
  A range has no JDBC class: its text, as there.
- **A parameter's type is a cast in the text: `?` becomes `($1::int4)`.** Measured: Spin
  converts a parameter by the type the SERVER infers and refuses any other case (`where id
  = $1` with an `int32` against a `bigint`: `Error { kind: ToSql(0), cause:
  Some(WrongType { postgres: Int8, rust: "i32" }) }`; a `str` for an `int8`, a `jsonb` or an
  enum likewise). The interface has no place to declare a type, and a parameter cast
  directly IS that declaration to the server (PostgreSQL's documentation, PREPARE: a
  parameter's type is told by the context it first stands in), so `setInt` behaves as it
  does over a socket, where the type goes in Parse. In parentheses: `::` binds tighter than
  a subscript, and a bare cast is no operand of `FETCH FIRST`. A value the host has no case
  for goes as text the server casts (`($1::text::timestamptz)`: an `OffsetDateTime`, a
  `ZonedDateTime`, an `Instant`, an `OffsetTime`); NULL is `db-null`, which every type takes,
  cast where `setNull` names a type.
- **So a parameter always has a type**, where the sibling leaves three to the server:
  `setObject(x, Types.OTHER)` is a `text` (a json or an enum is cast in the statement,
  `?::jsonb`); `setTimestamp` is a `timestamp` -- the wall's fields, exact for a `timestamp`
  column and read on the session's wall for a `timestamptz` one, where the sibling's untyped
  text is right for both; `setObject(x, type)` sends the value as its own class and casts it
  on (`($1::varchar::int4)`).
- **`executeQuery` asks for rows, `executeUpdate` for a count, `execute` reads the
  statement's words** (`Sql.rows`): the host has a call for each and tells neither which a
  statement is nor a command tag. `SELECT`, `VALUES`, `TABLE`, `SHOW`, `EXPLAIN`, `FETCH`,
  after `WITH` the first of the verbs outside every parenthesis, and a writing verb with a
  `RETURNING` outside every parenthesis. A statement asked for rows that has no column was a
  command: a count of 0 (the host told none). The rows written (`RETURN_GENERATED_KEYS`)
  are a `RETURNING` appended and the count their number.
- **A transaction is the statements the connection sends** (`BEGIN` ahead of the first,
  `COMMIT`, `ROLLBACK`, `SAVEPOINT`), a call each: the host tells nothing of the session's
  state. `commit()` of a transaction in which the SERVER refused a statement asks `SELECT
  1` first (refused in an aborted one), then commits, which the server makes a rollback,
  and throws `40000` as the sibling does; a refusal of the host's own (a `timestamptz`
  read) aborts nothing and is not counted.
- **`close()` rolls back a transaction left open**, the connection's own or one a
  statement's text began (`Sql.begins`): the host hands the session out again inside it
  (below). A round trip only when one is open.
- **A batch of a prepared statement is one transaction** where the connection commits each
  statement (`BEGIN` .. `COMMIT` around its rows, a call a row), every count
  `EXECUTE_FAILED` at a refusal, as the sibling's one group; a statement's batch is a
  command at a time.
- **`setQueryTimeout` is the server's `statement_timeout`**, set by a call of its own when
  it differs from what the connection last set, and set again after a rollback (which takes
  a `SET` back); `57014` under a limit is a `SQLTimeoutException`.
- **Reading goes through the server's text of a value** unless the getter asks for the
  case's own class: `Values.text` writes what the server writes (a float as `1e+15`, an
  interval in the `postgres` style, an array and a range in braces and brackets), measured
  against the server's `::text`, and the conversions on it are the sibling's. So `getInt`
  of a numeric, `getString` of a date and `getObject(i, OffsetDateTime.class)` of a
  `timestamptz` cast to text answer as they do over a socket.
- **The interface is package-private** (`Postgres`): a program that wants the host's
  interface itself declares its own.
- **A result is read whole.** `query-async` answers a `stream<row>` and a `future`, an
  `async func` that `@WitImport` refuses
  (`src/main/java/am/ik/rontolisp/java/JavaWitImports.java`), so a cursor in pieces waits on
  that. Spin 4.3.0 is still the newest release on 2026-11-26 (canary 4.4.0-pre0
  b8f889f answers as 4.3.0).

## What the host does (measured, Spin 4.3.0; 4.1.0 and canary 4.4.0-pre0 alike)

- **`PREPARE` and `pg_prepared_statements` work through `execute` and `query`** (m26): the
  host's own statements are there too (`s4`, `from_sql` false, `parameter_types` empty);
  a `PREPARE` of `select $2` with no `$1` is `42P18`; `parameter_types::text[]` is the SQL
  names (`integer`, `character varying`), hence the join to `pg_type`.

PostgreSQL 17.12, load 20-60.

- **A session given back inside an open transaction is handed out again INSIDE it**, to
  the next `open` of the address, across requests: `BEGIN`, an insert, the connection
  closed; the next one had the same backend pid, `txid_current_if_assigned()` not null and
  the row visible, until a `ROLLBACK`. Its settings are reset on the way (`statement_timeout`
  back to 0, `search_path`, read-only). A session given back in a FAILED transaction is
  dropped (another pid). This corrects m13's "handed out again with none".
- **An empty result has its columns**; a row-less statement asked for rows has none (`query`
  of an INSERT: `columns []`, no count); `execute` of a SELECT counts its rows.
- **A refusal has two shapes.** `query-failed(db-error(..))`, the fields apart (the SQLSTATE
  in `code`, `detail`, the hint among `extras`), for what the server refuses at Parse or
  Bind and for every `execute`. `query-failed(text(..))` for a `query` the server refuses
  while its rows are read -- an `INSERT .. RETURNING` breaking a constraint, a division by
  zero in the second row, a statement out of time: the text is Rust's debug form,
  `Error::QueryFailed(QueryError::DbError(DbError { as-text: "..", severity: "ERROR", code:
  "23505", message: "..", detail: Some(".."), extras: [("constraint", ".."), ..] }))`, which
  `Failures.wrapped` reads (a string's escapes: quote, backslash, `n`, `r`, `t`, `0`, a code
  point in braces).
- **What it cannot convert fails the whole query**, as text: a `timestamptz` column even
  when its value is NULL (`error deserializing column 0 .. cannot convert between the Rust
  type .. NaiveDateTime .. and the Postgres type timestamptz`; the column is listed as
  `datetime`, so an empty result passes), a numeric `NaN` (`Error while converting to
  NaN`), an infinite date or timestamp (`value too large to decode`), an array of two
  dimensions. The driver's refusal names the type and the cast.
- **Types**: the cases m13 listed, and `varchar` / `bpchar` as `str` (a `char(3)` padded);
  `int8[]`, `numeric[]`, `text[]`, `varchar[]` and the three ranges as their cases (an EMPTY
  range as no bound at all, which is `(,)`); a `jsonb` as the text the host writes for it
  (`{"a":1}` for `{"a": 1}`); a BC date as an astronomical year; `time '24:00:00'` as
  midnight. Any other type is `other(name)` with `unsupported(octets)`, the server's binary
  form: `name`, `"char"`, `json`, `xml`, an enum (its label), `timetz`, `oid`, `inet`,
  `money`, `bit`, `point`, `bool[]`, `float8[]`. The driver reads `json`, `xml`, `name` and
  `char` as text (their binary form is the text) and refuses the others but for `getBytes`.
- **Parameters**: each case for its type and no other (`int8` is `"char"`; `timestamp` is
  `DateTime<Utc>`, a `timestamptz` of whole seconds, unused: the text keeps the fraction);
  a `decimal` of `NaN` is `bad-parameter`; a count that differs `Error { kind:
  Parameters(1, 2) }`; two commands `42601` from the server (every statement is prepared).
- **The address** takes `sslmode` (`disable`, `prefer`, `require`), `application_name`,
  `options`, `connect_timeout`; `verify-full` or an unknown setting is `invalid connection
  string`. The driver maps `verify-ca` and `verify-full` to `require` (Spin verifies chain
  and name whenever it speaks TLS: m13), `ssl=true` likewise, and passes no other setting. A
  login refused is `connection-failed` with the server's words and no SQLSTATE: `08001`.

## Compiled by rontolisp (2026-11-25, the tree of the merge, load 30)

Nothing was refused and nothing in the front end changed: the driver's first compile ran.
`examples/java/spin-postgres` (the notes servlet over JDBC; before m24 the same notes over
the interface), as a component (the last three rows 2026-11-26: the making of what a `throw`
throws gives a loop no turn; before it 1,490,030 B and 27, 1,623,174 B and 63, 1,892,736 B and 72):

| | bytes | resumable |
|---|---|---|
| over the interface itself (before) | 1,154,126 | 6 of 1,833 |
| through `jdbc-spin-postgres` | 1,477,233 | 6 of 2,192 |
| the same program through `jdbc-postgresql` | 1,612,909 | 61 of 1,897 |
| through both | 1,882,267 | 70 of 2,625 |

Compiled in 20 s; `--optimize` the same bytes. Of its 1,189,891 B of functions
(`-Drontolisp.wasm.debug-func-sizes`): the binding and the declared types 83,005 (378:
fewer than the example's 100,574, whose program named more cases), the driver's own
classes 74,378 (203: `Values` 23 KB, `Sql` 11, `Failures` 7, `Moment` 6, the JDBC classes
20), `java.math` 21,507, `java.time` 21,808 (4,993 before: `getObject(i, Class)` reaches
every class it answers), `java.sql` 8,977, the time zone reader ~35 KB, resume bodies 39,785
(18; 38,281 and 17 over the interface). **The program's `while (rows.next())` takes no
turn**: `next()` can throw an `SQLException`, whose constructor reads `DriverManager`'s log
writer, a `volatile`; that read is the making of what a `throw` throws, which decides no
loop ([java-frontend.md](java-frontend.md), "A loop's turn"). Taking the turn made every
function from the loop up to the container's dispatch resumable (27 of 2,192, resume bodies
59,247 B in 30) and cost no time measured: a loop over 10,000 rows of
`generate_series` under Spin 4.3.0, the median of 31 requests in each of four alternating
rounds, 8.0-10.5 ms with the turn against 8.9-9.9 without (about 1 us a row either way; the
request 31-40 ms, the host's query), load 25-40.

m26 (2026-11-26, the compiler of the table): the example 1,477,233 -> 1,482,608 B
(functions +3,981). The program asks nothing of the catalog; `getObject(i, Class)` reaches `getArray`
(`Array.class` is one of JDBC's answers), whose `SpinPostgresArray.read` and `fill` are
1,874 B, and `Sql.parse` grew 720 B for `trailing`. A stream in `read` cost more (the flow
classes), as did a `toString` (`Arrays.toString`).

A `GET /` of the example (two statements, each on a connection out of the host's pool),
the median of 30: 5.4 ms in the clear, 6.0 ms over TLS (the interface itself, m13: 4.8 and
5.7).

## Tests

- `jdbc-spin-postgres/src/test/java/am/ik/jdbc/spin/postgres/`: `SpinPostgresDriverTest`
  under a JDK against PostgreSQL 17 in Docker, the host a stand-in (`SocketPostgres`, the
  provider `src/test/resources` lists; the drivers registered in class-path order before
  any test, so `DriverManager`'s first driver does not hang on which test made one first):
  the interface answered through `jdbc-postgresql`,
  each rule above modelled (the pool that keeps a session and its transaction, the
  parameter check against the described types, the two shapes of a refusal, the conversion
  failures). Seven families: a program's reads and writes (its statements described before
  they run), every setter and arrays, the table beside the socket driver, the catalog
  beside it, transactions (the close that rolls back, the aborted commit, batches),
  refusals and their SQLSTATEs, and no host at all (a context class loader hiding the
  provider: `connect` null, `DriverManager` on to the socket driver, the data source's
  refusal). `AddressTest` (the URL as the host's address), `SqlTest` (the casts, the words,
  the `RETURNING`).
- **The stand-in against Spin**: the tour (142 lines since m26: the types, the setters, arrays, a statement described before it
  runs, the catalog, the refusals, a timeout, batches, transactions, the close) printed the
  same under Spin 4.1.0, 4.3.0 and 4.4.0-pre0, and under a JDK through the stand-in but two
  lines the model does not make: a jsonb's text and the octets of a `point`.
- `ClPostgresE2eTest#theJdbcProgramAnswersThroughSpinsConnection`: the example compiled to
  a component and served by `spin up` against the test's PostgreSQL (a SCRAM login the
  host makes), six requests. It runs where `-Drontolisp.spin` names a Spin 4.1 or later or
  one is on PATH (`ci.yaml`'s e2e job installs 4.3.0) and is skipped elsewhere. TLS is by
  hand: the example's README has the server and the certificate.
- `ExamplesE2eTest` compiles the example (`jvm-compile`, `wasm-component`).
