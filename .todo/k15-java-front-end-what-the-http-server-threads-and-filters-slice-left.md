# k15. Java front end: what the HTTP server's threads and filters slice (k11) left

Difficulty: High

**Important -- licence and naming: read `.kb/java-frontend-provenance.md` before writing
anything.** No JDK jar, source, Javadoc text or test is copied or translated; a behaviour is
matched by running a program under a JDK.

State after k11 (2026-11-19, `.kb/java-frontend.md`, "com.sun.net.httpserver"): a request
is a task the server's executor runs; on both wasm targets it is a thread of the program's
scheduler, which serves where no thread can go on, so `main` and a handler may wait there;
`Filter`, `Authenticator` / `BasicAuthenticator`, `HttpHandlers`, `HttpPrincipal`,
`stop(delay)`, `InetAddress.getByName` of numbers, and a `--native` server's port for port
0 are there. What `deviations.md` ("The HTTP server") still states:

- **A body sent as it is written, on the interpreter and the JVM.** The component sends a
  chunk when it is written (`http.lisp` `%serve-respond`); the JDK transports keep a
  response until it ended, so the corpus's `/stream` answers `unseen` there (its
  `deviates:`). Since l31 (2026-11-24) the Java subset's exchange is the transport's
  `RontoHttpServer.Reply`, taken on the transport's thread and held by the responder
  (`.kb/http-server.md`, "An exchange the application takes"): sending a chunk as it is
  written is `sendResponseHeaders(status, 0)` at `%http-server-head` and a write and a
  flush at `%http-server-write`. What stands in the way is the cut
  (`a-handler-that-throws-after-the-head`): once the handler returned,
  `com.sun.net.httpserver` has no way to close a connection with no last chunk -- a JDK
  cuts one only for a handler that throws --, so a body in chunks failing on another
  thread would end as a whole one. Measure what a JDK does, and decide. `--native` stays
  whole: the runner sends a response `Content-Length`-framed (`.kb/http-server.md`).
- **The values of a field sent more than once** are one value joined by `, `: the
  environment's header table is Clack's (`%http-headers-table`), built on three
  backends; the raw tuple still has them apart, but the runner joins them before the
  module sees them (`serve.rs` `head_json`, the reactor envelope's `headers` object).
- **Under `wasmtime serve` the program runs only while the host has a request of the
  instance in hand** (since k07, 2026-11-19, a request that waits is suspended and stays
  in hand after its response while a thread waits for a time or a reply it holds:
  `.kb/java-frontend.md`, "The host's events give the turns under a component"). What is
  left: a thread asleep since before the first request goes on when a request arrives,
  not before; an instance kept running with no request is the host's to give.
- **What a served program costs on wasm.** Every one is lowered resumable and carries
  the scheduler, the monitors and the resume bodies: the sizes are in
  `.kb/java-frontend.md` ("Sizes", k11). Not measured: lowering resumable only a program
  whose handlers or `main` can wait, with a plain call of the handler otherwise (the
  executor's hand-over is what reaches `wait` in every program).
- **Not there**: `HttpsServer` (`.todo/050`), `SimpleFileServer` (the subset has no
  `java.nio.file`), `InetAddress.getLocalHost`, an address of sixteen octets, any name
  lookup.
- **A second server** in one program is refused by name: the JVM backend has one handler
  slot (`.kb/clack.md`), the component one exported handler.

## Done when

Each item is made, or measured and decided with the numbers in `.kb/java-frontend.md`.
