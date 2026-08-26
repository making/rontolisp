# Shared state under concurrently served requests (interpreter + JVM)

**The invariant: any process-wide mutable state a request handler can reach must be
thread-safe, because `rontolisp:http-handler` / `serve` runs ONE VIRTUAL THREAD PER
REQUEST on the interpreter and the JVM** (`.kb/fetch-http.md`; the WASM backends are
single-threaded by construction and are exempt from everything here -- their
concurrent-serve story is the HOST's cast lowering, `.kb/wasm-gc-final-types.md`). A handler is
ordinary Lisp, so "state a handler can reach" is broad: the stream table, the global
function/variable namespaces, every lazily loaded library, the condition/CLOS registries.
Sequential requests never see any of it -- **only a burst does**, which is why every bug
in this family was found by a load test and not by the suite.

The failure shape is always the same: a burst of N concurrent requests loses a few, and
the losses look like something else's fault (the database dropped the connection, a
function is undefined, a stale value came back). Three have been found and fixed so far:

- **Special variables** (fixed earlier, `.kb/dynamic-special-variables.md`): a dynamic
  binding established by one request was visible to another.
- **The stream table** (.todo/193): handle allocation was "reserve a slot, then store", so
  two concurrent `tcp-connect`s took the same handle -- one socket was dropped and both
  Lisp handles denoted the survivor, interleaving two PostgreSQL handshakes on one
  connection. Mechanics and the per-backend rule: `.kb/read-load-streams.md`.
- **Lazy library loading** (.todo/193, the same load test): every Lisp-source library
  (url.lisp, linalg.lisp, vec.lisp, usocket.lisp, json.lisp, gray.lisp, wit.lisp, the
  prelude) and every GENERATED runtime (the condition renderer, the restart runtime, the
  slot-unbound helpers) is evaluated into the global environment on first use, behind a
  `boolean` flag that was set BEFORE the definitions were installed. A request arriving in
  that window skipped the loader and then failed to resolve the very name being defined:
  `The function RONTOLISP:QUERY-PARAM is undefined`, 2-4 of 12 requests, first burst only.

## The rule for the interpreter's lazy loads

`LispEvaluator.libraryLoadLock` guards **every** load and every read of a flag that
guards one: the whole slow path of `resolveFunction` (which re-checks
`lookupFunctionOrNull` after taking the lock -- another thread may have finished the load
while this one waited) plus `ensureUsocketLoaded` / `ensureWitLoaded` /
`ensureGrayStreamsLoaded` / `ensureConditionReportRuntimeLoaded` /
`ensureRestartRuntimeLoaded` / `applyJsonHelper`. The fast path -- a name that resolves in
the global namespace -- stays lock-free, so steady state pays nothing.

Inside the lock the flag is still set BEFORE the forms are evaluated. That is deliberate
and must stay: it is what stops a library whose own forms resolve back into the same gate
from recursing forever, and it is safe now precisely because no other thread can observe
the flag without first acquiring the lock. A new lazy load MUST follow the same shape --
take the lock, check the flag, set it, evaluate -- or it reintroduces the bug in a new
place. (The lock is reentrant; Java 25 no longer pins a virtual thread inside
`synchronized`, so blocking on it under load is cheap.)

The global environment itself is the other half: `Environment.NameMap` promotes a scope
that outgrows its 8-entry linear arrays to a **`ConcurrentHashMap`**, which the global
environment always is -- a lazy load writes into it while other request threads read it,
and a plain `HashMap` rehash can make a long-defined name momentarily invisible.
`LispEvaluator.specialVars` is a `ConcurrentHashMap` keyset for the same reason.

## Pinning tests

- `LispEvaluatorTest#concurrentFirstCallsOfALazyLoadedLibraryAllResolve` -- 5 rounds x 16
  threads, a FRESH evaluator per round (a library is cold exactly once per evaluator, and
  the window only opens once the JIT has warmed the loader up: round 1 alone never
  reproduces it).
- `HttpHandlerTest#concurrentRequestsGetTheirOwnSocketHandle` and its
  `HttpHandlerJvmTest` twin -- the stream-table half, through a real served handler.
- The end-to-end check this family is measured by is not in the suite: four bursts of 12
  concurrent POSTs against `examples/db/postgres-web.lisp` (a real PostgreSQL connection
  per request) on the interpreter AND on the compiled class, expecting 48/48. Anything
  less is a new instance of the bug family, and the 500 hides the cause -- the fastest
  diagnosis is a temporary `ex.printStackTrace()` in `RontoHttpServer.dispatch`
  (.todo/191 is the standing item for surfacing it properly).
