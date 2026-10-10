# Shared state under concurrently served requests (interpreter + JVM)

Any process-wide mutable state a handler can reach must be thread-safe: `rontolisp:http-handler` /
`serve` runs ONE VIRTUAL THREAD PER REQUEST on the interpreter, the JVM and the Servlet transport
(`.kb/fetch-http.md`). WASM backends are single-threaded and exempt. Only a BURST exposes these
bugs, and each looks like someone else's fault.

- Servlet (`-o app.war`, `.kb/http-server.md`): the container reuses ONE platform thread, so
  `RontoHttpServlet` must `startAsync` onto a fresh virtual thread.
- Fixed: special variables (`.kb/dynamic-special-variables.md`); stream-table handle allocation
  (`.kb/read-load-streams.md`); the JVM backend's lazy inits (`_javaInit`, `_objcInit`, `_ffiInit`,
  simd/gpu/geom inits) are emitted `ACC_SYNCHRONIZED` by `JvmLispCompiler` (found when they still
  `defineClass`d: the second of two racing first calls answered `LinkageError: attempted duplicate
  class definition`).
- Clojure `clojure.java.io` (`.kb/clojure-frontend.md`, "`clojure.java.io`"): a literal
  resource's contents are kept by statements at the program's start, not where the call runs;
  the stream and mark registries are read and written under one mutex; the jar reader's cache
  under its own.
- Clojure runtime tables (`clojure.lisp`, 2026-10-10), each made eagerly, never on first use (two
  first requests each made one and lost the other's entries; on the JVM a reader saw a table
  published before its order list, `GETHASH: #<java LinkedHashMap> is not of type HASH-TABLE`):
  metadata (`%clojure-meta-guard`), interned vars (`%clojure-var-guard`), namespaces
  (`%clojure-ns-guard`), and the structural-key classes, representatives and typed kinds
  (`%clojure-key-guard`). The key guard never holds the program's code: a type's own hash or
  equality and a lazy seq run outside it, and a class is made only when its bucket is still
  the one compared against (`%clojure-key-class`), else the newer classes are compared and
  the store retried -- a lock held there could deadlock on a lazy seq waiting for a thread that
  stores a key.
- Protocol and interface rows are written only as the program starts: a type's at its
  definition, a `reify`'s once per SITE, hoisted ahead of its top-level datum, the evaluation's
  methods carried in the value (`.kb/clojure-frontend.md`, "`reify`"). Before, every
  evaluation stored rows under a `gensym` tag into the same tables every dispatch reads
  (`The function NIL is undefined` under a burst), and the JVM's `gensym` counter is a plain
  static increment. Reads stay lock-free. Not covered: an `extend`/`extend-type` or
  `defmethod` executed while requests run (every one in the corpus is top-level).
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
- `ClojureIoConcurrentRequestsTest` (48 first requests at once, interpreter 3 rounds, JVM 2,
  fresh program each; every request opens 24 writers: before the guard 17-23 of 24 kept their text).
- `ClojureRuntimeTablesConcurrentRequestsTest` (48 first requests at once, 3 rounds per leg,
  fresh program each; each request checks 40 values for metadata, a reify's protocol and
  interface methods, vector and deftype map keys, `#'x` and `the-ns` identity). Before
  (2026-10-10) every round failed on both legs: 500s from the races above, a lost namespace
  identity; with only the guards, the reify rows still failed.
- Not in the suite: bursts of 12 concurrent POSTs against `examples/db/postgres-web.lisp`.
