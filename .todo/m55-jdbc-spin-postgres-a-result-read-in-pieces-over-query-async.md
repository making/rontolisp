# m55. jdbc-spin-postgres: a result read in pieces over `query-async`

Difficulty: High

Left by m26 (2026-11-26; `.kb/jdbc-spin-postgres.md`, "A result is read whole"). The host
hands every row of a result over with it (`query`). `query-async` answers the columns, a
`stream<row>` and a `future<result<_, error>>`, so a cursor could take rows as the result
set advances; it is an `async func`, which the Java subset's `@WitImport` refuses
(`src/main/java/am/ik/rontolisp/java/JavaWitImports.java`: "an async func, whose future the
Java subset does not carry yet").

- **`@WitImport` of an `async func`**: what a Java method declares for a `future<T>` and a
  `stream<T>` (a class of `rontolisp-java-api`'s, or `CompletableFuture` and an `Iterator`),
  how a read waits (the component's async lowering exists for Common Lisp:
  `.kb/wit.md`), and what a JDK provider of the same interface implements. Decide it from
  what the Lisp side binds, not from this driver.
- **The driver**: `setFetchSize(n)` with `n > 0` reads through `query-async`, `n` rows at a
  time, as the sibling's cursor would (`.todo/l23-jdbc-postgresql-what-the-first-cut-left.md`,
  "A cursor that fetches in pieces"); the future's error is the statement's refusal, thrown
  where the rows run out. Measure first what a whole result costs the component (memory, the
  time to the first row) against the stream, on a result of 10,000 rows.
- **The Spin of `ci.yaml`** is pinned to 4.3.0 (`Install Spin`), the newest release on
  2026-11-26: move it with a release that changes the interface, and measure a later one's
  answer to a `timestamptz` column and to a session given back inside a transaction (both
  are the host's to change; the stand-in `SocketPostgres` models 4.3.0).
