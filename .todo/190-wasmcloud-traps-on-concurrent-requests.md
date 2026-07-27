# 190 - A wasmCloud component traps on concurrent requests

`wash dev` (2.5.2), `examples/db/postgres-web.lisp` (a PostgreSQL connection per
request), 12 concurrent POSTs, measured 2026-07-27: **10 succeed, 2 answer 500**
and wash logs

```
P3 run_concurrent failed err=error while executing at wasm backtrace:
    wasm trap: cast failure
P3 component task ended before producing a response
```

Sequentially the same program is 12/12. The same component under
`wasmtime serve` is 12/12 concurrent as well (its log shows interleaved task
ids, so those requests genuinely overlapped), so this is wasmCloud-specific --
or at least it needs wasmCloud's scheduling to show up.

## Not the two obvious causes

Both were isolated and came back **negative** under `wash dev`:

- **Module globals / special bindings.** The `.todo/189` reproduction (a handler
  that binds a special and does real work) answers all 8 concurrent requests
  correctly here. Whatever `.todo/189` fixes will not fix this.
- **The socket registry.** A handler doing nothing but
  `(rontolisp:tcp-connect ...)` + `(close ...)` per request -- no specials, no
  library -- is 12/12 concurrent. So `*sock-table*` / `*sock-next-fd*` under
  concurrent registration is not enough on its own to break.

## Where to look next

`cast failure` is how a **null global read** surfaces on wasm-GC (it is exactly
how the serve top-level-init bug announced itself -- `.kb/tcp-sockets.md`), so
the question is which state a second concurrent request finds uninitialised
inside one wash instance.

The instance model is the thing to pin down first, because the two observations
do not fit one story:

- **Sequentially**, wash re-runs the program's whole top level on every request
  (a `drop table` + `create table` startup pair emptied the table on each one),
  i.e. each request looks like a fresh instance.
- **Concurrently**, requests evidently share something, or there would be
  nothing to corrupt.

If wash starts an instance per request but lets a second request in before the
first has finished its serve-init `_start` (the `serveInitGlobalIndex` flag in
`WasmExportCompiler.emitBody`), then request two sees the flag already set and
runs against globals the top level has not filled yet -- which matches the
trap and matches why a program with NO meaningful top level (both isolations
above) never shows it. That is the first hypothesis to test: give the
socket-only reproduction a top level that defines a global the handler reads,
and hammer it.

Related: `.todo/189` (the JVM half of the same "concurrency matrix" session,
different cause), `.kb/tcp-sockets.md` (serve top-level init, wash's loopback
routing).
