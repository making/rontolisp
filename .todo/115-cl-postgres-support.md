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

## Status (2026-07-27)

The driver runs a live query round-trip on every backend that can open a TCP
socket, pinned by `ClPostgresE2eTest` (opt-in, see below):

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

## The automated end-to-end test -- DONE 2026-07-27

`ClPostgresE2eTest`, opt-in via `RONTOLISP_POSTGRES_E2E=1`: a Testcontainers
`postgres:17-alpine` (user instruction) started with a per-role `pg_hba.conf` --
`trustuser`/`passuser`/`md5user`/`scramuser`, one role per method, so a broken
rung cannot succeed through another one. Three exercises, each run on the
interpreter, the JVM and the `--component` backend and asserted to produce
byte-identical output: the `trust`/`password`/`md5` ladder (each probe also asks
`current_user`, the proof of which role got in), SCRAM-SHA-256, and CRUD
(create/insert/select/update/delete/drop plus a parameterised `prepare-query` +
`exec-prepared` run twice, so the extended protocol is covered; each backend
owns its own table). A tenth test pins the Preview 1 compile error.

**The three SCRAM legs used to be separately opt-in**
(`RONTOLISP_POSTGRES_SCRAM_E2E=1`): they cost 2m20s / 20 s / 25 s and every
second of that was PBKDF2. `.todo/188` made it 54 s / 10 s / 38 s wall (of which
PBKDF2 is ~50 s / ~1 s / ~28 s), so the gate is gone and they run whenever the
class runs -- ~4 minutes for the whole class including container startup.

Unlike the other library E2Es it drives the real CLI in a subprocess (the
component leg needs the socket library splices only `RontoLispCli` wires up, and
the JVM leg needs a path-free `-o Probe.class`), running the test's own
classpath -- so no packaged jar is required.

Three things the test had to encode:

1. `postgres -c authentication_timeout=600`. With the 60-second default a rung
   that outruns it fails as `READ-BYTE: end of file` while the server log says
   `FATAL: canceling authentication due to timeout`. Still needed after
   `.todo/188`: the interpreter's PBKDF2 is ~50 s here, under 20% of margin, and
   the component's ~28 s carries the WASM module-size tax that todo has not
   closed. Drop the flag when that lands.
2. The component leg connects to the container's IP ADDRESS, not its network
   alias: `tcp-connect` takes only IPv4 literals on WASM (`.todo/048`, which now
   also records the ugly failure mode a hostname produces through a library).
3. The wasm leg gets its own wasmtime container on the PostgreSQL container's
   network, rather than the shared `WasmtimeSupport` one -- container to
   container, so no host-port bridging is involved.

The load-cost worry this section used to carry is gone: `uax-15` gained derived
compile-time tables (todo-179), so the whole quickload is ~0.2 s interpreted and
a trust-auth connect-query-close program runs in 0.7 s wall.

## What is left: TLS / `sslmode`

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
