# l11. Example: a web application of Servlets and PostgreSQL, on Java and on wasm

Difficulty: High

Asked by the user (2026-11-21): a web + DB example (Servlet + PostgreSQL) that runs on Java
and on wasm. After l09 and l10.

- A small but real application (e.g. a notes or todo app: list, create, edit, delete; HTML
  forms and/or a JSON API), Servlets over JDBC. The Lisp counterpart is
  `examples/db/postgres-web.lisp` (and `shiori/`): compare.
- Runs on the JDK (embedded `servlet-httpserver`, and a servlet container if l09 found the WAR
  cheap) and through rontolisp on the interpreter, the JVM, `--native` and `--component`.
- **Measure first: a `wasi:http` component that also opens a TCP connection.** Whether
  `wasmtime serve` grants sockets to a served component (flags), and how Spin does it
  (`examples/db/postgres-web/spin.toml`, `allowed_outbound_hosts`). A connection per request
  versus one kept across requests (an instance may serve several requests,
  `.kb/java-frontend.md`, "com.sun.net.httpserver"); what the pool/`DataSource` does there.
  Record the numbers in `.kb`.
- Check it in `examples/examples.yaml` (opt-in, a live database) on the four backends;
  `examples/java/README.md` or `examples/db/README.md`; link from `doc/en|ja/java/servlet.md`.
- Report each output's size and a request's latency on each backend.
