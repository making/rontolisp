# Shared state under concurrently served requests (interpreter + JVM)

Any process-wide mutable state a handler can reach must be thread-safe: `rontolisp:http-handler` /
`serve` runs ONE VIRTUAL THREAD PER REQUEST on the interpreter, the JVM and the Servlet transport
(`.kb/fetch-http.md`). WASM backends are single-threaded and exempt. Only a BURST exposes these
bugs, and each looks like someone else's fault.

- Servlet (`-o app.war`, `.kb/http-server.md`): the container reuses ONE platform thread, so
  `RontoHttpServlet` must `startAsync` onto a fresh virtual thread.
- Fixed: special variables (`.kb/dynamic-special-variables.md`); stream-table handle allocation
  (`.kb/read-load-streams.md`); the JVM backend's five lazy embeds (`_javaInit`, `_objcInit`,
  simd/blas/gpu inits) are emitted `ACC_SYNCHRONIZED` by `JvmLispCompiler`, else
  `LinkageError: attempted duplicate class definition`.
- Lazy loads (interpreter): `LispEvaluator.libraryLoadLock` guards EVERY load and every read of a
  guarding flag -- `resolveFunction`'s slow path plus the `ensure*Loaded` gates and
  `applyJsonHelper`. Fast path stays lock-free. Inside the lock the flag is set BEFORE evaluating
  (stops a library re-entering its own gate). A new lazy load MUST be
  take-lock / check-flag / set-flag / evaluate.
- `Environment.NameMap` promotes a scope outgrowing its 8-entry linear arrays to a
  `ConcurrentHashMap` (the global environment always is one); `LispEvaluator.specialVars` likewise.

## Tests
- `LispEvaluatorTest#concurrentFirstCallsOfALazyLoadedLibraryAllResolve` (5 rounds x 16 threads,
  FRESH evaluator per round -- round 1 alone never reproduces it).
- `HttpHandlerTest#concurrentRequestsGetTheirOwnSocketHandle` + `HttpHandlerJvmTest`; `WarE2eTest`.
- Not in the suite: bursts of 12 concurrent POSTs against `examples/db/postgres-web.lisp`.
