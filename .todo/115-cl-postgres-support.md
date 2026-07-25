# Make cl-postgres (Postmodern's PostgreSQL driver) actually run

Goal: `(ql:quickload "cl-postgres")` loads, and a real query round-trip works
against a live PostgreSQL:

```lisp
(ql:quickload "cl-postgres")
(let ((conn (cl-postgres:open-database "mydb" "myuser" "mypass" "127.0.0.1" 5432)))
  (print (cl-postgres:exec-query conn "select 42, 'hello'" 'cl-postgres:list-row-reader))
  (cl-postgres:close-database conn))
```

The usocket shim (todo-114, `.kb/tcp-sockets.md`) already covers the socket
layer cl-postgres uses (`socket-connect` + `:element-type '(unsigned-byte 8)`
+ `socket-stream`); the `#-(or allegro sbcl ccl)` reader conditionals in
`public.lisp` select the usocket path under rontolisp's feature set. This todo
is everything ABOVE the socket.

## Empirical state (updated 2026-07-25)

**M1 (the `.asd` front-end) is merged on develop**: the verbatim
cl-postgres.asd parses (defparameter env, `#.*string-file*` resolves to
"strings-ascii", the usocket/sb-bsd-sockets `:feature` clauses drop). Pinned
by `AsdfSystemsTest.parsesTheClPostgresAsdHeaderShape`.

**M2 (deps) is DONE on develop as of 2026-07-25** -- and done BETTER than
planned: every dependency loads from its real unmodified sources on all four
backends instead of through a shim (see blocker 4 and the milestones).

**The `cl-postgres-wip` branch is now obsolete, not merely frozen.** It was
parked with the M2 JDK-backed crypto shims (dead: the real libraries load) plus
an M4 pre-work batch whose useful parts have since landed on develop
independently -- source-file `#.` (2026-07-18), the `find-package` fold and
`defgeneric` inline `:method` (2026-07-25). Only `with-standard-io-syntax` and
`encode-universal-time` remain unimplemented, and both are tens of lines
(blocker 5). Recommendation: start M4 fresh from develop rather than revive a
branch last exercised before todo-116 shipped.

Iterate with the cached copy in
`~/.rontolisp/quicklisp/software/postmodern-*/cl-postgres/`.

## Blocker inventory (grep counts over cl-postgres/*.lisp)

1. **.asd front-end** (first gate, small): `defparameter` forms in the .asd
   feeding `#.` component names (`(:file #.*string-file*)` — currently the
   skip leaves the component nameless), and `(:feature (:or :allegro ...)
   "usocket")` clauses inside `:depends-on` (must evaluate against
   `reader.Features`; under rontolisp the usocket clause is feature-false —
   fine, usocket is built in). Suggested lite fix: allow top-level
   `defparameter` of a literal/`#+`-conditional value in a `.asd` into the
   parse-time data env, and resolve `#.<var>` in the .asd against it.
2. **`#.` read-time eval in SOURCE files -- SHIPPED** (2026-07-18, the jzon
   work): source files support `#.` on every path (marker-mode reader +
   `UserMacroExpander` resolution against the macro-time evaluator; see
   `.kb/reader-features.md`). The ~46 cl-postgres sites need no new mechanism.
3. **Condition system — mostly SHIPPED** (todo-116 Phases 1-3, commit
   a8b957b, 2026-07-12; the durable record is `.kb/error-handling.md`, and
   `.todo/039` remains the API catalog). `define-condition` with
   slots/`:reader`s (11), `handler-case` (6) and `unwind-protect` (5) all
   work on the interpreter + JVM, and wasm-GC catches too since todo-129
   (only `--no-gc` rejects the catching forms). So cl-postgres's
   `errors.lisp` `database-error` hierarchy is expressible today. What is
   left for this todo is small and specific:
   - **`handler-bind`** — exactly ONE real site: `public.lisp:386`,
     `wait-for-notification`, catching `postgresql-notification`
     (LISTEN/NOTIFY only, not on the `exec-query` path). The
     `errors.lisp:142` grep hit is inside a docstring, not code. Until it
     exists, the file still LOADS (defun bodies are lazy on the
     interpreter); only a call to `wait-for-notification` fails, so
     LISTEN/NOTIFY is simply unsupported for now.
   - **`cerror` -- SHIPPED** (the cl-base64 work, todo-085): it lowers to
     `error` with the continue-format dropped (not continuable, no restarts).
     The 4 cl-postgres sites (protocol.lisp:269/289 auth edge + the SCRAM
     signature checks) need nothing further.
   - **`restart-case`** (4 sites) needs **nothing**. The todo-116 Phase 4
     survey (see `.kb/error-handling.md`, "The Phase 4 survey") proved the
     verbatim cl-postgres needs NO restart system at all: all 4 sites are
     `(restart-case (error X) (clauses...))`, and cl-postgres never invokes
     a restart itself (zero library-side `invoke-restart`/`find-restart`),
     so the existing lite primary-form-only `expandRestartCase` is
     behavior-identical to real CL here. The real restart gate is Postmodern
     proper, which is out of scope (see the milestones).
4. **Dependency systems**: `:depends-on ("md5" "split-sequence" "ironclad"
   "cl-base64" "uax-15")`. **The dependency grind (formerly `.todo/154`) is
   DONE** -- every other system runs REAL on all 4 backends
   (the REAL-source policy of `.todo/147` superseded the original
   shim-everything strategy below).
   - `split-sequence` runs REAL (todo-054 verification chain).
   - `cl-base64` runs REAL (todo-085, all 4 backends).
   - `md5` runs REAL on ALL 4 backends (`Md5E2eTest`; the WASM exclusion was
     retired by the boxed exact-integer path, `.kb/wasm-bignum.md`).
   - `cl-ppcre` v2.1.2 runs REAL on all 4 backends (`ClPpcreE2eTest`).
   - `uax-15` v0.1.3 runs REAL on all 4 backends (`Uax15E2eTest`).
   - `ironclad`: the SHA-256/HMAC/PBKDF2/HKDF slice runs REAL on all 4 backends
     (`IroncladE2eTest`, todo-173 -- the "real loading is infeasible" verdict
     was WRONG: the executable `.asd` blocked parsing, not loading, and
     `eval/AsdOverrides` substitutes a hand-authored replacement while keeping
     the real sources). **The JDK-backed shim strategy is dead** -- that was
     the frozen `cl-postgres-wip` M2's whole reason to exist.
     Residual gap: of the nine `ironclad:` names `scram.lisp` calls, six are in
     the slice (`digest-sequence`, `make-hmac`, `update-hmac`, `hmac-digest`,
     `ascii-string-to-byte-array`, `hex-string-to-byte-array`) and THREE are not
     (verified by grep 2026-07-25). Both routes are small:
     - `pbkdf2-hash-password` (`kdf/password-hash.lisp`, 61 lines): its body is
       the one-liner `(pbkdf2-derive-key digest password salt iterations
       (digest-length digest))`, all of which the slice already has. The file's
       only out-of-slice reference is `make-random-salt` (prng/), and only as the
       DEFAULT of its `:salt` keyword -- cl-postgres passes an explicit salt, so
       on the interpreter the default never evaluates; the compile paths are
       eager, so they need a `make-random-salt` stub (or a prng slice). Add the
       file to `ironclad-slice.asd`.
     - `integer-to-octets` / `octets-to-integer` (`public-key/public-key.lisp`):
       self-contained `ldb`/`loop` byte<->integer converters (no
       arbitrary-precision math, no elliptic curves) that merely LIVE in a
       3,065-line file. Loading that file whole is not viable, so the route is a
       `ShimLibraries.leafModuleForms` substitution for `public-key.lisp`
       exposing just these two (the jzon numeric-leaf precedent).
     SCRAM is the LAST auth method in M5's order, so this does not block M4.
     Widening the slice further (ciphers / public-key / prng / the other digests)
     is NOT planned -- the next real consumer decides. `dotimes-unrolled` users
     stay out until then: its DEFINITION loads, but no expansion of it does
     (`symbol-macrolet` is still unsupported).
5. **Stream/socket gaps + the four missing CL primitives** (inventory verified
   against develop 2026-07-25 -- these are now the FIRST thing to do in M4,
   each is tens of lines):
   - **`encode-universal-time`** (2 sites) -- the urgent one: it is evaluated
     at LOAD time by `(defconstant +start-of-2000+ (encode-universal-time
     ...))` in `interpret.lisp:427`, so the file cannot load without it.
   - **`force-output`** (16 sites, incl. `protocol.lisp:193` on the exec-query
     path) -- a no-op is correct, socket writes are unbuffered.
   - **`with-standard-io-syntax`** (3 sites, e.g. inside the `intern` call at
     `communicate.lisp:10`) -- a lite `progn` is enough.
   - **`(listen socket)`** (1 site, `protocol.lisp:232`, the MITM check) --
     needs an `InputStream.available()`-style primitive. A nil-returning stub
     silently DISABLES that security check, so decide deliberately.
   - `handler-bind` (2 sites) is LISTEN/NOTIFY only, not on the exec-query
     path -- defer it.
   - `read-byte`/`write-byte`/`read-sequence`/`write-sequence` all exist;
     verify the SOCKET-handle branch. cl-postgres's own buffered reader does
     byte-at-a-time socket reads (21 sites) -- a perf item, not correctness.
6. **CLOS/macro surface**: 4 `defclass` / 7 `defgeneric` / 3 `defmethod`
   (single dispatch — should fit the static subset; verify `:initform`/
   `:reader` coverage), 18 `defmacro` (loadable-library defmacro works since
   the cl-who work), `eval-when` (4, expands to progn — check none need real
   compile-time eval), `declaim inline` (no-op, fine), `the` (no-op, fine).
7. **`loop` coverage**: 72 uses across 13 files — rontolisp's `loop` is a
   subset; expect `:for x :across vector`, `:collect ... :into`, multi-clause
   forms. Inventory the failing shapes by iterating file loads and extend
   `expandLoop` case by case.
8. **Misc**: `with-standard-io-syntax` (3, in sql-string printing — lite
   no-op progn is probably acceptable), `scale-float` (1, ieee-floats.lisp;
   cl-postgres bundles its own float codec — check `ieee-floats.lisp`
   actually compiles), int8/OID values need arithmetic beyond i31 on WASM
   (bignum path exists on interpreter/JVM; WASM floats — acceptable, document).

## Suggested milestones

- **M1 (front-end)**: DONE 2026-07-11, merged on develop -- .asd
  defparameter/`#.`/`:feature` support; the cl-postgres system graph parses
  and file loading starts. Pinned by AsdfSystemsTest over the verbatim
  cl-postgres.asd header shape.
- **M2 (deps)**: DONE 2026-07-25, and NOT as shims -- every dependency
  (`md5`, `split-sequence`, `cl-base64`, `cl-ppcre`, `uax-15`, `ironclad`)
  loads from its REAL unmodified sources on all four backends. The
  `:depends-on` chain resolves with no network beyond the tarballs.
  **Recommendation: abandon the frozen `cl-postgres-wip` branch rather than
  re-run it.** Its main content was the M2 JDK shims, which are now dead
  weight; of its M4 pre-work, `#.`/`find-package`/`defgeneric :method` are on
  develop already and only `with-standard-io-syntax` + `encode-universal-time`
  are missing -- cheaper to add fresh (see blocker 5) than to revive a
  stale branch.
- **M3 (conditions + unwind-protect)**: todo-116. Gate: `errors.lisp` +
  `protocol.lisp` load; `handler-case` over `database-error` works on the
  interpreter.
- **M4 (grind)**: iterate `load`-next-file/fix-first-error through
  `communicate.lisp` → `messages.lisp` → `interpret.lisp` → `protocol.lisp`
  → `public.lisp` (loop shapes, `#.` sites, stream gaps land here).
- **M5 (e2e)**: Docker/testcontainers PostgreSQL; `trust` auth first (no
  digests needed!), then `password`, `md5`, finally SCRAM-SHA-256. A
  `LispEvaluatorTest`-style opt-in test (env-gated like RONTOLISP_HTTP_E2E)
  plus an `examples/db/` program with per-backend headers.
- Postmodern proper (s-sql, the `postmodern` system) is a SEPARATE follow-up
  on top — it adds heavy CLOS/MOP usage; do not scope it here.

## Scope decisions to make early

- Interpreter/JVM only first? (WASM lacks TLS and bignums — a
  realistic driver target is interpreter + JVM; WASM = document as
  unsupported for now.)
- Vendor a patched cl-postgres (skip scram/saslprep files via a rontolisp
  feature) vs. running the verbatim upstream source. Verbatim is the todo-054
  tradition (split-sequence/cl-who run unmodified) and should stay the goal;
  a temporary vendored subset is acceptable as an M4 stepping stone.
