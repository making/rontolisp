# l23. jdbc-postgresql: what the first cut left

Difficulty: Medium

`jdbc-postgresql/` (`.kb/jdbc-postgresql.md`, made by l01 on 2026-11-22) is written from
PostgreSQL's protocol documentation, the RFCs and the JDBC specification; pgjdbc's source is
not its model, pgjdbc run beside it is the oracle (`PostgresDriverTest`). It knows nothing
of rontolisp, and every item below has to keep compiling from source on the four targets.
Each is refused by name today.

Done on 2026-11-24: every fact of `DatabaseMetaData`, the catalog's tables, columns, keys,
indexes, schemas and catalogs, `ResultSetMetaData`'s table, schema, nullability and
numbering, `PostgresDataSource` (`.kb/jdbc-postgresql.md`, "Decisions" and the table beside
pgjdbc). Left, in the order programs are likely to meet them:

- **TLS** (`sslmode=require`, `verify-ca`, `verify-full`; SSLRequest, then the socket
  wrapped): the JDK has `javax.net.ssl`, the subset does not (`.todo/l15`, which waits until a
  web application can use TLS). Decide how one
  source serves both -- a class the subset's program never reaches, or the factory handed
  in. `prefer` connects in the clear today where pgjdbc would use TLS when the server has
  it: say so wherever it is decided. Channel binding (SCRAM-SHA-256-PLUS) after it.
- **A cursor that fetches in pieces** (`setFetchSize` inside a transaction: Execute with a
  row limit and PortalSuspended), so a large result is not held whole. Measure with
  `driver/Bulk.java` first: since l13 the read of its 1.2 MB is 15-115 ms on rontolisp's
  four targets (`.kb/tcp-sockets.md`, "What has come, in one read"), so the case is the
  memory a result holds, not its time.
- **The rest of the catalog**: `getTypeInfo` (check what Hibernate's start does when it is
  refused), `getProcedures` / `getFunctions` and their columns, the privileges,
  `getBestRowIdentifier`, `getVersionColumns`, the UDTs, super types and tables,
  attributes, pseudo columns. Same way as the others: a query of `pg_catalog` each, beside
  pgjdbc in `theMetadataBesidePgjdbc`.
- **A server-side prepared statement after n runs** (a named statement, Close; what a
  pooler in front of the server and a changed table do to it decides the default).
- **Arrays** (`getArray`, `setArray`, `createArrayOf`: the text form in braces), `SQLXML`,
  large objects, `Blob` / `Clob` over `bytea` / `text`. `getColumns`' `DATA_TYPE` of an
  array changes with them (`OTHER` -> `ARRAY`, as `getColumnType`). `jdbc-spin-postgres` answers the
  three since m26 and left `getObject` (the text) and the constant (`OTHER`) as this driver
  answers them: change those in both drivers together (its `SpinPostgresDriverTest`
  compares the two).
- **COPY** (`COPY FROM STDIN` / `TO STDOUT` have no JDBC surface: an API of the driver's
  own), LISTEN / NOTIFY (notifications are dropped today).
- **JDBC escapes** (`{fn ..}`, `{d ..}`, `{ts ..}`, `{oj ..}`, `{call ..}` and
  `CallableStatement`; `getNumericFunctions` and the others answer none until then), more
  than one host in a URL, the GSS and SSPI logins, binary transfer (measure whether text
  costs anything first).
- **Values the text reader refuses**: `infinity` dates and timestamps, the time
  `24:00:00`, a `DateStyle` the program changed.
- **`getDate(int, Calendar)` and its kind on rontolisp** (the `get`/`set` of a `Date`,
  `Time` or `Timestamp` with a `Calendar`): the subset has the calendar and its zones since
  l79, but the driver reads the zone through `TimeZone.toZoneId()`, which the subset lacks
  (no rule of l79's spec: probe a JDK's answers first) -- or the driver reads the wall time
  through the calendar itself. Run beside pgjdbc in `PostgresDriverTest`, and on the four
  targets through `driver/Tour.java`.
- **`getParentLogger`** (the driver's and the data source's) and the JDBC log: the subset's
  `java.util.logging` is there since l78 (`.kb/java-frontend.md`, "java.util.logging").
- **The move to a repository of its own** (user decision), its release to Maven Central
  (SNAPSHOTs are deployed already: `.kb/session-workflow.md`, "The deploy job"), and the
  version `PostgresDriver` answers.
