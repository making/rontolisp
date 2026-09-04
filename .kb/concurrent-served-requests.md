# Shared state under concurrently served requests (interpreter + JVM)

Invariant: any process-wide mutable state a handler can reach must be thread-safe --
`rontolisp:http-handler` / `serve` runs ONE VIRTUAL THREAD PER REQUEST on the interpreter,
the JVM and the Servlet transport (`.kb/fetch-http.md`). WASM backends are single-threaded
and exempt (`.kb/wasm-gc-final-types.md`). Only a BURST exposes these bugs; each looks like
someone else's fault (dropped DB connection, undefined function, stale value).

- Servlet (`-o app.war`, `.kb/http-server.md`): the container reuses ONE platform thread
  across requests, so `RontoHttpServlet` must `startAsync` and run the blocking pipeline on
  a fresh virtual thread. `WarE2eTest` pins it as a distinct-thread count under a 4-thread
  connector pool.
- Fixed instances: special variables (`.kb/dynamic-special-variables.md`); stream-table
  handle allocation was "reserve slot, then store", so two concurrent `tcp-connect`s shared
  a handle (`.kb/read-load-streams.md`); the JVM backend's five lazy embeds (`_javaInit`,
  `_objcInit`, simd/blas/gpu inits) guarded `Lookup.defineClass` behind a plain int field ->
  `LinkageError: attempted duplicate class definition`, fixed by emitting all five
  `ACC_SYNCHRONIZED` in `JvmLispCompiler`; lazy library loading (url/linalg/vec/usocket/
  json/gray/wit.lisp, prelude, and the generated condition-renderer / restart /
  slot-unbound runtimes) set its flag before installing definitions.

## Rule for lazy loads (interpreter)

`LispEvaluator.libraryLoadLock` guards EVERY load and every read of a guarding flag: the
slow path of `resolveFunction` (re-checking `lookupFunctionOrNull` after taking the lock)
plus `ensureUsocketLoaded` / `ensureWitLoaded` / `ensureGrayStreamsLoaded` /
`ensureConditionReportRuntimeLoaded` / `ensureRestartRuntimeLoaded` / `applyJsonHelper`.
Fast path (name resolves globally) stays lock-free. Inside the lock the flag is still set
BEFORE evaluating -- deliberate, it stops a library re-entering its own gate forever, and is
safe only because the flag cannot be observed without the lock. A new lazy load MUST be
take-lock / check-flag / set-flag / evaluate.

`Environment.NameMap` promotes a scope outgrowing its 8-entry linear arrays to a
`ConcurrentHashMap` (the global environment always is one -- a plain `HashMap` rehash can
hide a long-defined name from a concurrent reader); `LispEvaluator.specialVars` likewise.

## Tests

- `LispEvaluatorTest#concurrentFirstCallsOfALazyLoadedLibraryAllResolve` -- 5 rounds x 16
  threads, FRESH evaluator per round (round 1 alone never reproduces it).
- `HttpHandlerTest#concurrentRequestsGetTheirOwnSocketHandle` + `HttpHandlerJvmTest` twin.
- Not in the suite: four bursts of 12 concurrent POSTs against `examples/db/postgres-web.lisp`
  on interpreter AND compiled class, expecting 48/48. The 500 hides the cause -- temporary
  `ex.printStackTrace()` in `RontoHttpServer.dispatch`.
