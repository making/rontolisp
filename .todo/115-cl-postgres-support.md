# Make cl-postgres (Postmodern's PostgreSQL driver) actually run

Goal: `(ql:quickload "cl-postgres")` loads, and a real query round-trip works
against a live PostgreSQL:

```lisp
(ql:quickload "cl-postgres")
(let ((conn (cl-postgres:open-database "mydb" "myuser" "mypass" "127.0.0.1" 5432)))
  (print (cl-postgres:exec-query conn "select 42, 'hello'" 'cl-postgres:list-row-reader))
  (cl-postgres:close-database conn))
```

Iterate against the cached copy in
`~/.rontolisp/quicklisp/software/postmodern-*/cl-postgres/`. Run the verbatim
upstream sources -- vendoring a patched subset (dropping scram/saslprep behind a
rontolisp feature) is acceptable only as a temporary stepping stone.

## Where this stands (2026-07-25)

Everything below the driver itself is done; what remains is the driver.

- **The `.asd` parses** (defparameter env, `#.*string-file*`, the `:feature`
  clauses in `:depends-on`), pinned by
  `AsdfSystemsTest.parsesTheClPostgresAsdHeaderShape`.
- **Every dependency runs REAL on all four backends** -- `md5`,
  `split-sequence`, `cl-base64`, `cl-ppcre`, `uax-15`, `ironclad`. No shims.
  The `ironclad` slice covers all nine `ironclad:` names `scram.lisp` calls,
  with RFC 7677 section 3 pinned end to end (`.kb/asdf.md`).
- **The condition system carries `errors.lisp`**: `define-condition` with
  slots/`:reader`s, `handler-case`, `unwind-protect` and a lite `cerror` all
  work on the interpreter, the JVM and wasm-GC (only `--no-gc` rejects the
  catching forms). `.kb/error-handling.md`.
- **The socket layer is covered** by the usocket shim (`.kb/tcp-sockets.md`):
  `socket-connect` + `:element-type '(unsigned-byte 8)` + `socket-stream`, and
  the `#-(or allegro sbcl ccl)` reader conditionals in `public.lisp` select
  that path under rontolisp's feature set.
- **The `cl-postgres-wip` branch is dead.** Its content was the JDK-backed
  crypto shims (obsolete: the real libraries load) plus pre-work that has since
  landed on develop independently. Start from develop; do not revive it.

Two conclusions worth not re-deriving:

- **`restart-case` needs nothing.** All 4 sites are `(restart-case (error X)
  (clauses...))` and cl-postgres never invokes a restart itself (zero
  library-side `invoke-restart`/`find-restart`), so the existing lite
  primary-form-only expansion is behavior-identical here. The real restart gate
  is Postmodern proper, which is out of scope.
- **The `ironclad` slice will not be widened** (ciphers / the rest of
  public-key / prng / the other digests) -- the next real consumer decides.
  `dotimes-unrolled` users stay out with it: its definition loads, but no
  expansion of it does (`symbol-macrolet` is still unsupported).

## What is left

### Session record 2026-07-26 (the interpreter path is DONE, the compile path is not)

**The interpreter runs a real query round-trip on the FULL auth ladder.**
`trust`, `password`, `md5` and **SCRAM-SHA-256** all complete against a live
PostgreSQL 17 (`docker run postgres:17-alpine`)
and `(cl-postgres:exec-query conn "select 42, 'hello'" 'cl-postgres:list-row-reader)`
returns `((42 "hello"))`. The load order that works is package -> features ->
config -> oid -> errors -> data-types -> sql-string -> trivial-utf-8 ->
strings-ascii -> communicate -> messages -> ieee-floats -> interpret ->
saslprep -> scram -> protocol -> public -> bulk-copy, with `md5`,
`split-sequence`, `ironclad`, `cl-base64`, `cl-ppcre`, `uax-15` and
`alexandria` quickloaded first.

**Everything below was ADDED to make that happen** (all four backends unless
noted; each has a doc page + catalog entry + table row):

- CL primitives: `encode-universal-time` / `decode-universal-time` (prelude,
  era-based Gregorian; nil zone = GMT), `force-output` / `finish-output`,
  `listen` (real: `available()`/`ready()`; the component reports its chunk
  buffer; Preview 1 rejects it), `open-stream-p` (real against the stream
  table), `mismatch`, `digit-char`, `decode-float`, `arrayp`, `bit`,
  `hash-table-test`/`-size`/`-rehash-size`/`-rehash-threshold`,
  `with-open-stream`, `multiple-value-prog1`, `do-external-symbols`
  (interpreter-only), `internal-time-units-per-second` and
  `lambda-list-keywords` reader constants.
- `&whole` in defmacro AND destructuring-bind; `&rest`/`&body` followed by a
  destructuring PATTERN (alexandria's `if-let`).
- `intern` with a package designator (interpreter; the compilers lower it to a
  call-time signal), `(close stream :abort t)`, `(setf (ldb ...) v)`,
  a computed `gensym` prefix (compilers lower it to string construction --
  the printed name matches the interpreter on all four backends).
- **rontolisp:random-bytes** -- the cryptographic entropy primitive
  (`SecureRandom` / WASI `random_get`) that finally retired the signalling
  `ironclad-prng.lisp` stub: the shim now implements `*prng*`, `make-prng`,
  `random-data`, `random-bits` and `strong-random` FOR REAL (rejection
  sampling, so the SCRAM client nonce is uniform).
- Compile-path fixes: `#.` inside a backquote template no longer splits into
  construction code (`%read-eval-template`), `FreeVarAnalyzer` knows the
  `with-*` stream binders, `ClosRegistry.findClass` resolves a same-member
  name from a sibling package, an UNDEFINED condition type degrades to a plain
  simple-condition instead of failing the build, `:displaced-to` tolerates an
  and `:displaced-to` tolerates an explicit `nil` `:adjustable`.

### Reverted in this session, and why (do not just re-apply it)

**Widening `CrossLambdaExitLowering` to a plain `return` and to flet/labels
boundaries broke WASM and was backed out.** cl-postgres' `message-case` expands
into a `labels` local function that does `(return)` out of the enclosing
`loop` -- a cross-lambda exit the pass does not cover today (its documented
scope is a NAMED `return-from` crossing an explicit `(lambda ...)`). Adding a
nil-block scope for `loop`/`do`/`dotimes`/`dolist`/`%block`, a bare-`return`
case, and lambdaDepth+1 for flet/labels definition bodies made the JVM compile
that shape correctly (checked against the interpreter) -- and made
`ClPpcreE2eTest` trap at RUNTIME on BOTH wasm backends (`wasm trap:
unreachable`, exit 134), with `Uax15E2eTest` failing likewise and
`IroncladE2eTest` emitting an INVALID module ("expected eqref but nothing on
stack"). The `%nlx-catch`/`%nlx-throw` machinery therefore does not survive
this widening on wasm-GC as written. Re-doing it means designing the WASM side
FIRST, with `ClPpcreE2eTest`/`Uax15E2eTest`/`IroncladE2eTest` as the gate --
not extending the JVM side and assuming WASM follows.

### Where the next session starts

The interpreter work is committed (`dc8bc14c`). The compile path is two
INDEPENDENT pieces of design work, either of which can go first:

- **JVM**: the oversized-method / 16-bit branch-offset overflow below. Measure
  which expansion inflates `%SHARED-INITIALIZE--m2` BEFORE touching
  `am.ik.jvm` -- if it is the runtime-`typep` dispatch chain (whose length
  grows with the number of registered classes), the fix belongs in the
  expansion, and wide-branch relaxation in the emitter is the heavier
  alternative.
- **WASM**: the cross-lambda plain-`return` lowering (the reverted work
  above). Design the wasm-GC side first; `ClPpcreE2eTest` / `Uax15E2eTest` /
  `IroncladE2eTest` are the gate that caught the naive version.

Not yet run for this feature: the native-image `CiSpecE2eTest` (the pinned
introspection listings in `ci-spec.yaml` changed with the new operators, and
`./mvnw test` cannot catch a stale expectation there -- see CLAUDE.md).

### The remaining gate: the JVM backend cannot build the whole stack yet

The interpreter is green end to end; the JVM compile of the full program
(quickloads + all cl-postgres files) stops at

```
StackMapAugmenter: method IRONCLAD::%SHARED-INITIALIZE--m2: Index -31123 out of bounds
```

which is a **16-bit branch-offset overflow**: one emitted method body has grown
past 32 KB, so a `goto`/`if` offset no longer fits a signed short. `am.ik.jvm`
declares `GOTO_W` but never emits it, and the emitter patches branches in place
(no relaxation pass), so this needs either wide-branch relaxation in
`am.ik.jvm` or a smaller method. Worth measuring FIRST which expansion inflates
that body -- a `(typep x runtime-type)` inside ironclad's `shared-initialize
:after` expands to a per-class dispatch chain, and the class count grows with
every loaded library, so the fix may belong there rather than in the emitter.
This is the same family as `.todo/137` (the 255-local-slot ceiling): the JVM
backend has no scale guards.

WASM was not reached. Both wasm-GC backends are still to be tried, on the
component model over WASI 0.3 sockets (user, 2026-07-26).

### Then: end-to-end

Testcontainers PostgreSQL (user instruction). All four auth methods (`trust`,
`password`, `md5`, SCRAM-SHA-256) are verified BY HAND on the interpreter
already; what is missing is the automated, opt-in env-gated test (the
`RONTOLISP_HTTP_E2E` pattern). `examples/db/postgres-hello.lisp` + its README
landed this session.

**Two measured performance facts to plan the E2E around** (neither is a
correctness problem):

1. `uax-15` alone takes ~10 minutes to load on the interpreter (34k lines of
   UnicodeData.txt parsed in interpreted Lisp), and cl-postgres pulls it
   through `saslprep`. ASCII credentials never call `uax-15:normalize`
   (`saslprep-normalize` short-circuits on printable ASCII), so only the LOAD
   costs, not the run -- but an E2E that quickloads per backend is not viable
   as written.
2. **SCRAM-SHA-256 needs a raised `authentication_timeout` on the
   interpreter.** With the 60-second default it fails as `READ-BYTE: end of
   file` while the server log says `FATAL: canceling authentication due to
   timeout` -- the 4096-round PBKDF2 (two HMAC-SHA256 per round, in interpreted
   Lisp) does not finish in time. With
   `postgres -c authentication_timeout=600` the same program authenticates and
   queries successfully (verified 2026-07-26). The E2E must either raise that
   setting or run SCRAM on a compiled backend.

## Backend target: ALL FOUR (user, 2026-07-25)

Not a scope decision to make later -- the driver runs on the interpreter, the
JVM and both wasm-GC backends, the same bar every loadable library here meets.
The earlier "interpreter + JVM first" note is retracted: half its reasoning
(int8/OID arithmetic beyond i31) expired when arbitrary-precision exact
integers landed on both wasm backends (`.kb/wasm-bignum.md`).

One real obstacle survives, and it is the one to plan around rather than
discover late: **TLS is interpreter/JVM only** (`.kb/tcp-sockets.md`), so a
connection that negotiates SSL cannot complete on WASM. A plain-TCP connection
can. Two consequences:

- The M5 auth ladder (trust -> password -> md5 -> SCRAM) is all plain TCP, so
  it is reachable on four backends as written -- run it on four, not two.
- Whether `sslmode` support means teaching WASM TLS or documenting an explicit
  per-backend limitation is a decision for whoever hits it; do not let it stall
  the plain-TCP path.

## Out of scope

Postmodern proper (s-sql, the `postmodern` system) is a SEPARATE follow-up on
top -- it adds heavy CLOS/MOP usage and is where the real restart-system gate
lives.
