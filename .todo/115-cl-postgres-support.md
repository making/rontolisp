# Make cl-postgres (Postmodern's PostgreSQL driver) actually run

Goal: `(ql:quickload "cl-postgres")` loads, and a real query round-trip works
against a live PostgreSQL:

```lisp
(ql:quickload "cl-postgres")
(let ((conn (cl-postgres:open-database "mydb" "myuser" "mypass" "127.0.0.1" 5432)))
  (print (cl-postgres:exec-query conn "select 42, 'hello'" 'cl-postgres:list-row-reader))
  (cl-postgres:close-database conn))
```

The verbatim upstream sources run -- nothing is vendored or patched.

## Status (2026-07-26)

The driver runs a live query round-trip on every backend that can open a TCP
socket:

- **Interpreter** -- `dc8bc14c`. The full auth ladder (`trust`, `password`,
  `md5`, SCRAM-SHA-256) completes against `postgres:17-alpine`.
- **JVM** -- `5050b292`. The whole stack (quickloads + all driver files)
  compiles to a `.class` and queries live; output identical to the interpreter.
- **WASM `--component`** -- `4e7fa05f`. `examples/db/postgres-hello.lisp`
  compiled `--component --optimize` returns `((42 "hello"))` and
  `((1) (2) (3))` under
  `wasmtime run -W gc=y -W exceptions=y -S tcp=y -S inherit-network=y`.
- **WASM Preview 1** -- not applicable: TCP sockets are a compile error there by
  design (`.kb/tcp-sockets.md`). "All four backends" for this driver means the
  three above.

Load order that works: package -> features -> config -> oid -> errors ->
data-types -> sql-string -> trivial-utf-8 -> strings-ascii -> communicate ->
messages -> ieee-floats -> interpret -> saslprep -> scram -> protocol -> public
-> bulk-copy, with `md5`, `split-sequence`, `ironclad`, `cl-base64`, `cl-ppcre`,
`uax-15` and `alexandria` quickloaded first.

## What is left

### The automated end-to-end test

Testcontainers PostgreSQL (user instruction). All four auth methods are verified
BY HAND on the interpreter and the query round-trip by hand on all three
backends; what is missing is the automated, opt-in env-gated test (the
`RONTOLISP_HTTP_E2E` pattern). `examples/db/postgres-hello.lisp` + its README
already exist.

**Two measured performance facts to plan the E2E around** (neither is a
correctness problem):

1. `uax-15` alone takes ~10 minutes to load on the interpreter (34k lines of
   UnicodeData.txt parsed in interpreted Lisp), and cl-postgres pulls it through
   `saslprep`. ASCII credentials never call `uax-15:normalize`
   (`saslprep-normalize` short-circuits on printable ASCII), so only the LOAD
   costs, not the run -- but an E2E that quickloads per backend is not viable as
   written.
2. **SCRAM-SHA-256 needs a raised `authentication_timeout` on the interpreter.**
   With the 60-second default it fails as `READ-BYTE: end of file` while the
   server log says `FATAL: canceling authentication due to timeout` -- the
   4096-round PBKDF2 (two HMAC-SHA256 per round, in interpreted Lisp) does not
   finish in time. With `postgres -c authentication_timeout=600` the same
   program authenticates and queries successfully. The E2E must either raise
   that setting or run SCRAM on a compiled backend.

### TLS / `sslmode`

**TLS is interpreter/JVM only** (`.kb/tcp-sockets.md`), so a connection that
negotiates SSL cannot complete on WASM; a plain-TCP connection can. The auth
ladder is all plain TCP, so it stays reachable everywhere. Whether `sslmode`
support means teaching WASM TLS or documenting an explicit per-backend
limitation is a decision for whoever hits it -- it must not stall the plain-TCP
path.

## Conclusions worth not re-deriving

- **`restart-case` needs nothing.** All 4 sites are `(restart-case (error X)
  (clauses...))` and cl-postgres never invokes a restart itself (zero
  library-side `invoke-restart`/`find-restart`), so the existing lite
  primary-form-only expansion is behavior-identical here. The real restart gate
  is Postmodern proper, which is out of scope.
- **The `ironclad` slice will not be widened** (ciphers / the rest of
  public-key / prng / the other digests) -- the next real consumer decides.
  `dotimes-unrolled` users stay out with it: its definition loads, but no
  expansion of it does (`symbol-macrolet` is still unsupported).
- **An unresolved wasm mystery is parked, not load-bearing.** `%nlx-tag` on
  wasm-GC mints an i31 VALUE id instead of a GC-struct identity because the
  identity scheme tripped an engine-level divergence (wasmtime 46/47 vs V8) at
  cl-ppcre scale. Whose bug it was is still unknown; the seed for anyone who
  wants it is in `.kb/do-return-block.md` and the history of this file
  (`git show 4c220716:.todo/115-cl-postgres-support.md`).

## Out of scope

Postmodern proper (s-sql, the `postmodern` system) is a SEPARATE follow-up on
top -- it adds heavy CLOS/MOP usage and is where the real restart-system gate
lives.
