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

### 1. Four missing CL primitives (do these first; each is tens of lines)

Inventory verified against develop 2026-07-25.

- **`encode-universal-time`** (2 sites) -- the urgent one: `interpret.lisp:427`
  evaluates it at LOAD time in `(defconstant +start-of-2000+
  (encode-universal-time ...))`, so the file cannot load without it.
- **`force-output`** (16 sites, incl. `protocol.lisp:193` on the exec-query
  path) -- a no-op is correct, socket writes are unbuffered.
- **`(listen socket)`** (1 site, `protocol.lisp:232`) -- needs an
  `InputStream.available()`-style primitive. It guards a MITM check, so a
  nil-returning stub silently DISABLES that check: decide deliberately.
- **`handler-bind`** -- exactly ONE real site, `public.lisp:386`
  (`wait-for-notification`, catching `postgresql-notification`); the
  `errors.lisp:142` grep hit is inside a docstring. Deferrable: the file still
  loads without it (defun bodies are lazy on the interpreter) and only
  LISTEN/NOTIFY is unsupported until it exists.

### 2. The load grind

Iterate `load`-next-file / fix-first-error through `communicate.lisp` ->
`messages.lisp` -> `interpret.lisp` -> `protocol.lisp` -> `public.lisp`.
Expect to land in:

- **`loop` shapes** (72 uses across 13 files) -- rontolisp's `loop` is a subset;
  expect `:for x :across vector`, `:collect ... :into`, multi-clause forms.
  Inventory the failing shapes by iterating file loads and extend `expandLoop`
  case by case.
- **CLOS/macro surface**: 4 `defclass` / 7 `defgeneric` / 3 `defmethod` (single
  dispatch -- should fit the static subset; verify `:initform`/`:reader`
  coverage), 18 `defmacro`, `eval-when` (4 -- check none needs real
  compile-time eval).
- **Streams**: `read-byte`/`write-byte`/`read-sequence`/`write-sequence` all
  exist; verify the SOCKET-handle branch. cl-postgres's own buffered reader
  does byte-at-a-time socket reads (21 sites) -- a perf item, not correctness.
- **Misc**: `scale-float` (1 site, `ieee-floats.lisp` -- cl-postgres bundles its
  own float codec; check the file actually compiles).

### 3. End-to-end

Testcontainers PostgreSQL (user instruction). Auth in order: `trust` (needs no
digests at all), then `password`, `md5`, finally SCRAM-SHA-256. Ship an opt-in
env-gated test (the `RONTOLISP_HTTP_E2E` pattern) plus an `examples/db/` program
with per-backend headers.

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
