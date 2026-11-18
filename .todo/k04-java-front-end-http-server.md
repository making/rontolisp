# k04. Java front end: an HTTP server

Difficulty: High

**Important -- licence and naming: read `.kb/java-frontend-provenance.md` before writing
anything.** No JDK jar, source, Javadoc text or test is copied or translated; a behaviour is
matched by running a program under a JDK.

A Java program cannot serve HTTP today (`.kb/java-frontend.md`). Give it a server on all
four backends. The client is `.todo/k03`; a test of the server may use it once it lands.

**The primary target is `wasi:http`**: a `--component` build of a Java server program is a
`wasi:http/incoming-handler` component that `wasmtime serve` (and wasmCloud) runs, as the
`rontolisp:http-handler` directive's component leg already is (`.kb/clack.md`,
`.kb/wasi-component.md`). The other backends follow the same program unchanged.

## API

The JDK's own API is the goal; a rontolisp API only where the JDK's cannot be had.

- `com.sun.net.httpserver` -- `HttpServer` (`create`, `createContext`, `setExecutor`,
  `start`, `stop`), `HttpHandler`, `HttpExchange` (request method, URI, headers, body;
  `sendResponseHeaders`, `getResponseBody`, `close`), `Headers`, `java.net.InetSocketAddress`.
- **Fallback**: a member or behaviour that cannot be matched on some backend is refused BY
  NAME at its position (the front end's invariant), or, when the whole JDK API cannot be
  had, the program uses a rontolisp API under a package name of our own (not `java.*`,
  `javax.*`, `com.sun.*`). Record each such decision and its measurement in `.kb/`, and
  the user-visible difference in `doc/*/java/deviations.md`.

## Leads

- Lower onto `rontolisp:http-handler` / `%http-serve` (`.kb/http-server.md`, `.kb/clack.md`),
  adding no transport. The Clojure front end did the same: `ring.adapter.rontolisp` over
  `%http-serve` (`.kb/clojure-frontend.md`, "Ring adapter").
- `wasi:http`: the host owns the socket and calls the component's `handle` per request.
  Decide and record:
  - when `main` runs (it builds the server and its contexts) relative to the first
    `handle`, and what `create(InetSocketAddress, backlog)` (the address is the host's:
    `wasmtime serve --addr`), the executor, `start` and `stop(delay)` mean there;
  - state across requests: `wasmtime serve` may instantiate the component per request, so
    a static field a handler updates need not survive to the next one. Measure, and say so
    in `doc/*/java/deviations.md` if it does not;
  - streaming: `wasi:http`'s outgoing body is a stream, so `sendResponseHeaders(code, 0)`
    (chunked) and writes after the head can stream there. Measure whether the component leg
    of `%http-serve` streams today; if it buffers, make it stream rather than refuse.
- `--native` serves through its runner and sends a response whole, `Content-Length`-framed:
  a chunked response arrives whole there (record it as a deviation, not a refusal).
- A handler runs per request on a thread (`.kb/concurrent-served-requests.md`); check the
  Java front end's `Thread` and `synchronized` (`.kb/java-frontend.md`, "Threads") under it.

## Done when

- A server program (two contexts, a request body read, headers both ways, a status other
  than 200, a fixed-length and a chunked response where the backend has one) gives a JDK's
  answers on the interpreter, JVM, `--native` and the component; pinned as spec cases, with
  the refusals pinned where a backend lacks one.
- `doc/en/java/` + `doc/ja/java/` describe it, `.kb/java-frontend.md` records the design.
