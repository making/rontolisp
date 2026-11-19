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
  chunk when it is written (`http.lisp` `%serve-respond`); the JDK transports write a
  response once the handler returned (`RontoHttpServer.dispatch` / `writeResponse`), so
  the corpus's `/stream` answers `unseen` there (its `deviates:`). Lead: the transport
  hands the normalizer its `respond` (`%http-normalize-streaming res respond`,
  `LispEvaluator.normalizeClackResponse`), a function over the `HttpExchange` --
  `sendResponseHeaders(status, 0)`, then each chunk written and flushed. The interpreter
  needs the exchange in `RontoHttpServer.Handler`; the JVM backend calls the compiled
  normalizer by a unary INVOKESTATIC (`JvmHttpHandlerRuntimeBuilder`), so its responder
  has to be a function value built in bytecode, or the exchange reached through a
  primitive of the `%http-server-*` seam. Since k11 a handler runs on the executor's
  thread, so the exchange cannot be a thread-local read where a chunk is written: the
  responder has to hold it from the transport's thread on. `--native` stays whole: the
  runner sends a response `Content-Length`-framed (`.kb/http-server.md`).
- **An exchange answered after its handler returned.** The thread the transport called on
  waits for the task (`HttpServing.Task.await`) and closes the exchange there; a JDK's
  goes on to the next request and the exchange lives until it is closed. It takes the
  item above on the host's threads (a response written by whoever closes), and on one
  stack a transport call that returns before its response is out: the component's
  `%serve-handle` awaiting a future the program settles, a `--native` exchange answered
  out of order.
- **The values of a field sent more than once** are one value joined by `, `: the
  environment's header table is Clack's (`%http-headers-table`), built on three
  backends; the raw tuple still has them apart, but the runner joins them before the
  module sees them (`serve.rs` `head_json`, the reactor envelope's `headers` object).
- **Under `wasmtime serve` the program runs only while a request is answered.** A
  thread asleep, a `Timer`, a scheduled task go on when the next request is: an
  instance kept running between requests is the host's to give. A request that waits
  holding its instance is `.todo/k07`'s (the scheduler waiting on the host's futures
  comes first).
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
