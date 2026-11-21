# l10. Example: JDBC against PostgreSQL, on Java and on wasm

Difficulty: Medium

Asked by the user (2026-11-21). After l01 (pgjdbc, or our own driver if l01 switched; read its
`.kb` record first). Do not start before l01 is done.

- An example under `examples/java/` (or `examples/db/` beside the Lisp ones; pick by where a
  reader looks) using plain JDBC: `DriverManager.getConnection` from `DATABASE_URL` or JDBC
  properties, create table, insert with a `PreparedStatement`, select into objects, update,
  delete, a transaction rolled back, non-ASCII text.
- The same source runs on the JDK with the driver jar, and through rontolisp on the
  interpreter, the JVM, `--native` and `--component` (`wasmtime run -S tcp=y
  -S inherit-network=y`, as `examples/db/` does for cl-postgres).
- `examples/db/README.md` setup (the Docker PostgreSQL line) is shared; check it in
  `examples/examples.yaml` the way the cl-postgres examples are (opt-in when they need a live
  database).
- Report each output's size.
