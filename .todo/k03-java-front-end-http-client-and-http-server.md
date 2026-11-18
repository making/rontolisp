# k03. Java front end: an HTTP client and an HTTP server

Difficulty: High

**Important -- licence and naming: read `.kb/java-frontend-provenance.md` before writing
anything.** No JDK jar, source, Javadoc text or test is copied or translated; a behaviour is
matched by running a program under a JDK.

A Java program has no HTTP today: `java.net` has no class in the front end's library
(`.kb/java-frontend.md`). Give it both sides, on all four backends.

## API

The JDK's own API is the goal; a rontolisp API only where the JDK's cannot be had.

- **Client**: `java.net.http` -- `HttpClient` (`newHttpClient`, `newBuilder`, `send`,
  `sendAsync`), `HttpRequest` (builder, verbs, headers, `BodyPublishers`), `HttpResponse`
  (`statusCode`, `headers`, `body`, `BodyHandlers`), `HttpHeaders`, `java.net.URI`.
  `sendAsync` answers a `CompletableFuture` (already in the library).
- **Server**: `com.sun.net.httpserver` -- `HttpServer` (`create`, `createContext`,
  `setExecutor`, `start`, `stop`), `HttpHandler`, `HttpExchange` (request method, URI,
  headers, body; `sendResponseHeaders`, `getResponseBody`), `Headers`.
- **Fallback**: a member or behaviour that cannot be matched on some backend is refused BY
  NAME at its position (the front end's invariant), or, when the whole JDK API cannot be
  had, the program uses a rontolisp API under a package name of our own (not `java.*`,
  `javax.*`, `com.sun.*`). Record each such decision and its measurement in `.kb/`, and
  the user-visible difference in `doc/*/java/deviations.md`.

## Leads

- Lower onto what exists, adding no transport: the client onto `rontolisp:fetch` +
  `rontolisp:await` (`.kb/fetch-http.md`), the server onto `rontolisp:http-handler` /
  `%http-serve` (`.kb/http-server.md`, `.kb/clack.md`). The Clojure front end did both:
  `rontolisp.http-client` over the program's own `fetch` and `ring.adapter.rontolisp` over
  `%http-serve` (`.kb/clojure-frontend.md`, "HTTP client" and "Ring adapter"); the transport
  splices only see the program's own name for fetch.
- Transport limits already measured: a plain Preview 1 `.wasm` has no fetch (compile error);
  `--no-wasi` needs `--host-fetch`; `--native` serves through its runner and sends a
  response whole, `Content-Length`-framed (no streaming response there); the component serves
  through `wasi:http`. Decide what `HttpServer.create(InetSocketAddress, backlog)`, the
  executor and `stop(delay)` mean where the host owns the socket.
- Blocking `send` on wasm is an `await`; check it inside a `Thread` (`.kb/java-frontend.md`,
  "Threads") and against the k00 work on a suspended thread.
- Redirects: reuse the Clojure client's RFC 3986 resolution (written from the RFC, measured
  against a JDK); do not write a second one.

## Done when

- A client program and a server program (one context, a request body read, headers both
  ways, a status other than 200) give a JDK's output on the interpreter, JVM, `--native` and
  the component; pinned as spec cases, with the refusals pinned where a backend lacks one.
- `doc/en/java/` + `doc/ja/java/` describe both, `.kb/java-frontend.md` records the design.
