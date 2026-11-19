# k13. A Java httpbin example

Difficulty: Medium

**Important -- licence and naming: read `.kb/java-frontend-provenance.md` before writing
anything.** User surfaces say "an experimental subset of the Java language".

The user asked (2026-11-19) for a Java version of the httpbin example: `examples/net/httpbin.lisp`
(and its Clack / tiny-routes / ningle / CLOS / jzon siblings) as a Java program on
`com.sun.net.httpserver`, the API `examples/java/http/Server.java` already uses
(`.kb/java-frontend.md`, "com.sun.net.httpserver").

## Shape

- `examples/java/httpbin/` (one file, or a small package tree if it reads better, as
  `examples/java/shop/` does). The same endpoints as `examples/net/httpbin.lisp`: `GET /get`,
  `POST /post`, `PUT /put`, `PATCH /patch`, `DELETE /delete` answering a JSON description of
  the request (`args` from the query, `headers`, `method`, `path`; `data` and `json` for a
  body), 405 for a wrong method, 404 for an unknown path. The front end has no JSON library:
  the program writes (and, for `json`, reads) JSON itself, small and readable. The JDK has no
  JSON API either, so the same source runs on a JDK.
- One source for every transport: the interpreter and JVM (a socket), `--native` (the
  runner), `--component` under `wasmtime serve` (the user's main target for a server). The
  file's header gives the run commands for each, as `examples/net/httpbin.lisp` does, and
  a `curl` to try.
- `examples/java/README.md` lists it; `examples/examples.yaml` registers it the way
  `java/http/Server.java` is registered (compile legs: a server does not end).

## Pin the answers

Beyond compiling, the answers should be pinned on every transport against a JDK running
the same source, as `FetchSpecE2eTest#javaHttpServer` does for `java/http/Server.java`
(`java-http-server-spec.yaml`). Extend that corpus or its harness to serve this example
rather than adding a second harness. Header order and the JSON key order must be decided so
the output is the same on every backend (sort, or fixed insertion order).

## When a gap shows

A front-end or server gap the example runs into is fixed if small; otherwise refused by
name, recorded in `doc/*/java/deviations.md`, and moved to a todo (`.todo/k11` holds the
server's known leftovers). The example is not bent around a bug silently.
