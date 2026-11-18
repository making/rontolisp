# k03. Java front end: an HTTP client

Difficulty: High

**Important -- licence and naming: read `.kb/java-frontend-provenance.md` before writing
anything.** No JDK jar, source, Javadoc text or test is copied or translated; a behaviour is
matched by running a program under a JDK.

A Java program has no HTTP today: `java.net` has no class in the front end's library
(`.kb/java-frontend.md`). Give it a client on all four backends. The server is `.todo/k04`.

## API

The JDK's own API is the goal; a rontolisp API only where the JDK's cannot be had.

- `java.net.http` -- `HttpClient` (`newHttpClient`, `newBuilder`, `send`, `sendAsync`),
  `HttpRequest` (builder, verbs, headers, `BodyPublishers`), `HttpResponse` (`statusCode`,
  `headers`, `body`, `BodyHandlers`), `HttpHeaders`, `java.net.URI`. `sendAsync` answers a
  `CompletableFuture` (already in the library).
- **Fallback**: a member or behaviour that cannot be matched on some backend is refused BY
  NAME at its position (the front end's invariant), or, when the whole JDK API cannot be
  had, the program uses a rontolisp API under a package name of our own (not `java.*`,
  `javax.*`, `com.sun.*`). Record each such decision and its measurement in `.kb/`, and
  the user-visible difference in `doc/*/java/deviations.md`.

## Leads

- Lower onto `rontolisp:fetch` + `rontolisp:await` (`.kb/fetch-http.md`), adding no
  transport. The Clojure front end did the same: `rontolisp.http-client` over the program's
  own `fetch` (`.kb/clojure-frontend.md`, "HTTP client"); the transport splices only see the
  program's own name for fetch.
- Transport limits already measured: a plain Preview 1 `.wasm` has no fetch (compile error);
  `--no-wasi` needs `--host-fetch`; `--native` fetches through its runner; the component
  through `wasi:http`.
- Blocking `send` on wasm is an `await`; check it inside a `Thread` (`.kb/java-frontend.md`,
  "Threads") and against the k00 work on a suspended thread.
- Redirects: reuse the Clojure client's RFC 3986 resolution (written from the RFC, measured
  against a JDK); do not write a second one. `java.net.URI` may share it too.

## Done when

- A client program (verbs, headers both ways, a request body, a status other than 200,
  `send` and `sendAsync`) gives a JDK's output on the interpreter, JVM, `--native` and the
  component; pinned as spec cases, with the refusals pinned where a backend lacks one.
- `doc/en/java/` + `doc/ja/java/` describe it, `.kb/java-frontend.md` records the design.
