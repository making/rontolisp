# cl-dbi + dbd-postgres: `(dbi:connect :postgres ...)` round trip

Difficulty: 中〜高 (the driver substrate — cl-postgres — already works; the
work is dbi's own load gates, the runtime driver-loading seam on the compile
backends, and two small shim decisions)

Part of the Mito milestone `.todo/238`. Blocked by `.todo/240`
(symbol-macrolet: driver.lisp:295 setf-through-symbol-macro) and `.todo/242`
(`(setf (find-class ...))`: utils.lisp). Independent of trivia — can run in
parallel with `.todo/243`/`.todo/244`.

## Goal

`(ql:quickload "dbd-postgres")` and a live round trip on
interpreter + JVM + component:

```lisp
(let ((conn (dbi:connect :postgres :database-name "mydb" :username "u" :password "p"
                         :host "127.0.0.1" :port 5432)))
  (dbi:do-sql conn "create table t (id serial primary key, name text)")
  (dbi:execute (dbi:prepare conn "insert into t (name) values (?)") (list "a"))
  (print (dbi:fetch-all (dbi:execute (dbi:prepare conn "select * from t"))))
  (dbi:disconnect conn))
```

PostgreSQL ONLY (milestone scope): dbd-mysql/dbd-sqlite3 stay out (FFI).

## Known items

1. **What already parses**: dbi.asd's `#1=` labels, `(:feature ...)` dep and
   `:if-feature` components (probed 2026-08-02) — today the thread-feature
   expression matches nothing, so bordeaux-threads is dropped and
   `src/cache/single.lisp` is chosen over `cache/thread.lisp`.
2. **The cache thread-safety decision**: rontolisp DOES serve concurrent
   requests (one virtual thread per request, `.kb/concurrent-served-requests.md`),
   and dbi's prepared-statement cache is per-connection state. Decide like
   postmodern-deps.asd's `:postmodern-thread-safe ON` did: a hand-authored dbi
   override (or a recognized feature) selecting `cache/thread.lisp` +
   bordeaux-threads (the bt shim's locks are real on interpreter/JVM, no-op
   tautologies on single-threaded WASM). Write the reason either way.
3. **`tg:finalize` (dbd-postgres, trivial-garbage)**: used to auto-close
   leaked connections. Shim decision: a no-op `finalize` + documented explicit
   `dbi:disconnect` (matches the GC realities of all backends). trivial-garbage
   is already in the cache; check whether a shim system exists before writing
   one.
4. **Runtime driver loading on the compile backends**: `dbi:connect` resolves
   `:postgres` -> `(asdf:load-system "dbd-postgres")` AT RUNTIME. On the
   interpreter that just works; the compile paths resolve quickloads
   EAGERLY (`.kb/load-inliner.md`), so the program must contain
   `(ql:quickload "dbd-postgres")` itself and dbi's runtime load-system must
   SHORT-CIRCUIT on the already-loaded system instead of erroring. Verify the
   short-circuit; document the required explicit quickload (mito's docs page
   inherits this note).
5. dbi signals conditions (`dbi-error` hierarchy via the aliased bracket
   names) and uses `with-retrying`/restarts lightly — the postmodern restart
   precedent (`.todo/115` conclusions) suggests the lite expansion suffices;
   verify at the `execute-with-retry` call sites mito uses.

## Acceptance

- The round-trip program above: identical output on interpreter, JVM class,
  WASM component (wasmtime `-S tcp=y -S inherit-network=y`, IPv4 literal —
  `.todo/048`), against `postgres:17-alpine`.
- `dbi:with-transaction` commit + rollback paths pinned.
- Prepared-statement cache exercised twice (the cache decision proven), and
  `dbi:disconnect` leaves no dangling socket (interpreter/JVM).
- Unit/E2E scaffolding may reuse the ClPostgresE2eTest container plumbing,
  but the FULL milestone E2E lives in `.todo/250` — keep this session's test
  opt-in and small.
