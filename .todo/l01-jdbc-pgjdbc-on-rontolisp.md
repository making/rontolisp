# l01. JDBC: pgjdbc compiled by rontolisp talks to a live PostgreSQL

Difficulty: High

**Read `.kb/java-frontend-provenance.md` before writing anything.** Needs k98 (`Socket`), k99
(digests), l00 (`java.sql`). Theme (user, 2026-11-21): JDBC, PostgreSQL first. cl-postgres
already reaches PostgreSQL from wasm (`.todo/115`, `ClPostgresE2eTest`), so the transport is
proven.

Decided by the user: **try pgjdbc (`org.postgresql:postgresql`, BSD-2) first; if it is too
hard or its wasm is too large, write a driver of our own** (a v3-protocol JDBC driver as a
module of this repository depending on nothing of rontolisp, like k96's).

- **Measure first:** compile a program calling `DriverManager.getConnection("jdbc:postgresql:..")`
  + `executeQuery("select 42, 'hello'")` with `--java-dep org.postgresql:postgresql:<latest>`
  (sources jars of it and its dependencies). List every refusal by name, the families, and the
  wasm size once it compiles; write them in `.kb`. Expected weight: `java.util.logging`,
  reflection (`Class.forName` of socket and SSL factories, `.todo/k89`), `Properties`, SSL
  classes reached even with `sslmode=disable`, timers, `java.lang.ref` (`.todo/k89`).
- Decide with numbers: what remains to make pgjdbc run and its size, against an own driver's
  estimate. Record the decision and why in `.kb`; tell the user before switching.
- Done means: the auth ladder (`trust`, `password`, `md5`, SCRAM-SHA-256), CRUD, a prepared
  statement run twice, non-ASCII text (as `ClPostgresE2eTest` covers) on the interpreter, the
  JVM, `--native` and `--component`, output identical to the JDK. Extend
  `ClPostgresE2eTest`'s harness (its Docker PostgreSQL, opt-in) rather than a new one.
- Claim items for what is left (`.todo/claim-number.sh`).
