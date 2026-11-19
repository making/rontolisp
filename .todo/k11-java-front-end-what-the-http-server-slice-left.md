# k11. Java front end: what the HTTP server slice (k04) left

Difficulty: High

**Important -- licence and naming: read `.kb/java-frontend-provenance.md` before writing
anything.** No JDK jar, source, Javadoc text or test is copied or translated; a behaviour is
matched by running a program under a JDK.

State after k04 (2026-11-19, `.kb/java-frontend.md`, "com.sun.net.httpserver";
`.kb/http-server.md`, "Clack's streaming writer"): `com.sun.net.httpserver`'s `HttpServer`,
`HttpContext`, `HttpHandler`, `HttpExchange` and `Headers` serve on the interpreter, the
JVM, `--native` and as a wasi:http component under `wasmtime serve`, pinned beside a JDK's
answers by `FetchSpecE2eTest#javaHttpServer` (`java-http-server-spec.yaml`). What a
`deviations.md` paragraph ("The HTTP server") states and a slice could remove:

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
  primitive of the `%http-server-*` seam. With it a stop by a handler no longer needs its
  own thread (the response is out before the handler returns), and an exchange answered
  by another thread after its handler returned becomes possible. `--native` stays whole:
  the runner sends a response `Content-Length`-framed (`.kb/http-server.md`).
- **The wasm targets and waiting.** A `main` that waits for ever after `start()`
  (`latch.await()`, `Thread.currentThread().join()`) ends the program with "every thread
  .. waits", and a handler that waits for a thread, or sends a request through the client
  in a program that starts threads, with "a thread waiting inside a method the run time
  calls" (measured 2026-11-19, both targets). What it takes: the point where every thread
  waits is where a `--native` module serves its next request, and the transport's call
  of a handler is a thread of the scheduler's (`JavaResumable`; `.todo/k07`). Under
  `wasmtime serve` the top level has to return for `handle` to be called, so a `main`
  suspended for ever would have to end the initialization there.
- **A handler on the executor's thread.** The executor is kept and handed nothing; with
  one the interpreter and the JVM run each request on the transport's own thread. A
  JDK runs `executor.execute(task)`: thread names, a pool's bound, a direct executor
  (`Runnable::run`) running handlers one at a time. It takes the transport's thread
  waiting for the task, which on one stack is the item above.
- **The values of a field sent more than once** are one value joined by `, `: the
  environment's header table is Clack's (`%http-headers-table`), built on three
  backends; the raw tuple still has them apart.
- **Not there**: `HttpsServer` (`.todo/050`), `Filter`, `Authenticator` /
  `BasicAuthenticator`, `HttpHandlers`, `SimpleFileServer`, `HttpServer.create` with a
  path and a handler, `HttpExchange.getPrincipal`; `InetAddress.getByName` and any name
  lookup; `stop(delay)` waiting its delay.
- **A second server** in one program is refused by name: the JVM backend has one handler
  slot (`.kb/clack.md`), the component one exported handler.
- **`--native` cannot tell the port it bound for port 0** (`getAddress()` answers 0): the
  runner's `rlhttp` has no import for it (`rontolisp-native/runner/src/http/serve.rs`).
- **A Preview 1 module** throws the directive's call-time words from `create`
  (`HTTP-HANDLER requires --component ..`); the client is refused where it is compiled,
  by its own name (`WasmFetchCompiler.reject`).
